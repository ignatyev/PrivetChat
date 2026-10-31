package com.example.privetchat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Permanent (while enabled) listening through the selected system voice assistant.
 * Mic is released during ChatGPT Voice and reacquired after its capture ends.
 * No foreground service, notification, network connection or API key.
 */
class AssistantModeService : VoiceInteractionService(), RecognitionListener {
    companion object {
        const val CONTROL = "com.example.privetchat.ASSISTANT_CONTROL"
        const val STATUS = "com.example.privetchat.ASSISTANT_STATUS"
        const val EXTRA_STATUS = "text"
        const val EXTRA_CHAT_ENDED = "chat_ended"
        const val PREF = "assistant_settings"
        const val PREF_LISTEN = "listen_enabled"
        const val PREF_INITIALIZED = "background_listening_initialized_v05"
        private const val PREF_PAUSED = "paused_for_chat"
        private const val PREF_PAUSE_AT = "pause_started_elapsed"

        @Volatile var instance: AssistantModeService? = null
        @Volatile var listening: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var loading = false
    private var destroyed = false
    private var triggered = false
    private var serviceReady = false
    private var lastWordsTime = 0L
    private var generation = 0

    private var pausedForChat = false
    private var pauseStarted = 0L
    private var audioWasInUse = false
    private var audioQuietSince = 0L
    private var chatUiEnded = false

    private val resumeMonitor = object : Runnable {
        override fun run() {
            if (destroyed || !serviceReady || !enabled() || !pausedForChat) return
            val now = SystemClock.elapsedRealtime()
            val activeRecordings = try {
                getSystemService(AudioManager::class.java)
                    .activeRecordingConfigurations.isNotEmpty()
            } catch (_: Exception) {
                false
            }
            // Do not resume during any other application's audio capture.
            if (activeRecordings) {
                audioWasInUse = true
                audioQuietSince = 0
            } else if (audioQuietSince == 0L) {
                audioQuietSince = now
            }

            val quiet = !activeRecordings && audioQuietSince != 0L &&
                now - audioQuietSince >= 5500L
            val startedLongEnough = now - pauseStarted >= 5500L
            val endedByUi = chatUiEnded && quiet && startedLongEnough
            val recordingEnded = audioWasInUse && quiet && startedLongEnough
            // Recovery only if ChatGPT never took the microphone (failed launch).
            val launchTimedOut = !audioWasInUse && quiet &&
                now - pauseStarted >= 45000L

            if (endedByUi || recordingEnded || launchTimedOut) {
                pausedForChat = false
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .remove(PREF_PAUSED).remove(PREF_PAUSE_AT).apply()
                report("Возобновляю фоновое прослушивание")
                startRecognition()
            } else {
                main.postDelayed(this, 1700L)
            }
        }
    }

    private val controls = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CONTROL) return
            if (!enabled()) {
                pausedForChat = false
                main.removeCallbacks(resumeMonitor)
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .remove(PREF_PAUSED).remove(PREF_PAUSE_AT).apply()
                stopRecognition()
                report("Прослушивание выключено пользователем")
            } else if (intent.getBooleanExtra(EXTRA_CHAT_ENDED, false)) {
                chatUiEnded = true
                scheduleResumeCheck()
            } else if (pausedForChat) {
                scheduleResumeCheck()
            } else {
                startRecognition()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        pausedForChat = prefs.getBoolean(PREF_PAUSED, false)
        pauseStarted = prefs.getLong(PREF_PAUSE_AT, 0L)
        val now = SystemClock.elapsedRealtime()
        // Clear orphaned pause, including after reboot / process death.
        if (pauseStarted == 0L || pauseStarted > now || now - pauseStarted > 10 * 60_000L) {
            pausedForChat = false
            prefs.edit().remove(PREF_PAUSED).remove(PREF_PAUSE_AT).apply()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controls, IntentFilter(CONTROL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controls, IntentFilter(CONTROL))
        }
    }

    override fun onReady() {
        super.onReady()
        serviceReady = true
        if (!destroyed && enabled()) {
            if (pausedForChat) scheduleResumeCheck() else startRecognition()
        }
    }

    private fun enabled(): Boolean =
        getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(PREF_LISTEN, false)

    private fun scheduleResumeCheck() {
        main.removeCallbacks(resumeMonitor)
        if (pausedForChat && enabled()) main.postDelayed(resumeMonitor, 1700L)
    }

    private fun startRecognition() {
        if (destroyed || !serviceReady || pausedForChat || loading || listening || !enabled()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Разрешите микрофон в настройках приложения")
            return
        }
        triggered = false
        loading = true
        val myGeneration = ++generation
        report("Загрузка локальной модели…")
        worker.execute {
            var newModel: Model? = null
            var newRecognizer: Recognizer? = null
            var newSpeech: SpeechService? = null
            try {
                val dir = ModelInstaller.ensure(applicationContext)
                newModel = Model(dir.absolutePath)
                newRecognizer = Recognizer(newModel, 16_000f,
                    "[\"привет чат\",\"привет чет\",\"привет чат гпт\",\"[unk]\"]")
                newSpeech = SpeechService(newRecognizer, 16_000f)
                main.post {
                    if (destroyed || myGeneration != generation || !enabled() || pausedForChat) {
                        newSpeech?.shutdown()
                        newRecognizer?.close()
                        newModel?.close()
                        loading = false
                    } else {
                        model = newModel
                        recognizer = newRecognizer
                        speech = newSpeech
                        loading = false
                        try {
                            newSpeech?.startListening(this)
                            listening = true
                            report("Слушаю «Привет, Чат» в фоне")
                        } catch (error: Exception) {
                            report("Не удалось включить микрофон: ${error.message}")
                            stopRecognition()
                            main.postDelayed({ startRecognition() }, 8000L)
                        }
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (myGeneration == generation) {
                        loading = false
                        report("Ошибка распознавания: ${error.message}")
                        main.postDelayed({ startRecognition() }, 15000L)
                    }
                    newSpeech?.shutdown()
                    newRecognizer?.close()
                    newModel?.close()
                }
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) = recognize(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = recognize(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = recognize(hypothesis, "text")
    override fun onTimeout() = Unit
    override fun onError(exception: Exception?) {
        main.post {
            if (pausedForChat || !enabled()) return@post
            report("Перезапуск распознавания после ошибки")
            stopRecognition()
            main.postDelayed({ startRecognition() }, 8000L)
        }
    }

    private fun recognize(json: String?, key: String) {
        if (triggered || destroyed || !enabled() || pausedForChat || json.isNullOrBlank()) return
        val transcript = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (transcript.isBlank()) return
        val normalized = transcript.lowercase(Locale.ROOT).replace('ё', 'е')
        if (normalized.contains("привет чат") || normalized.contains("привет чет")) {
            triggered = true
            main.post {
                if (destroyed || !enabled()) return@post
                pauseForChat()
                try {
                    showSession(Bundle.EMPTY, 0)
                    report("Ключевая фраза распознана")
                } catch (error: Exception) {
                    report("Ошибка запуска: ${error.message}")
                    chatUiEnded = true
                    scheduleResumeCheck()
                }
            }
        } else if (SystemClock.elapsedRealtime() - lastWordsTime > 3000L) {
            lastWordsTime = SystemClock.elapsedRealtime()
            report("Распознано: $transcript")
        }
    }

    /** Pause Vosk, but keep the user's persistent enable switch ON. */
    fun pauseForChat() {
        if (!pausedForChat) {
            pausedForChat = true
            pauseStarted = SystemClock.elapsedRealtime()
            audioWasInUse = false
            audioQuietSince = 0L
            chatUiEnded = false
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(PREF_PAUSED, true)
                .putLong(PREF_PAUSE_AT, pauseStarted).apply()
        }
        stopRecognition()
        report("Ожидаю завершения голосового разговора")
        scheduleResumeCheck()
    }

    private fun stopRecognition() {
        ++generation
        listening = false
        loading = false
        try { speech?.cancel() } catch (_: Exception) {}
        try { speech?.shutdown() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        speech = null
        recognizer = null
        model = null
    }

    private fun report(message: String) {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onShutdown() {
        serviceReady = false
        main.removeCallbacks(resumeMonitor)
        stopRecognition()
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        serviceReady = false
        main.removeCallbacks(resumeMonitor)
        stopRecognition()
        try { unregisterReceiver(controls) } catch (_: Exception) {}
        worker.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
