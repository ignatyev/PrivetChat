#!/usr/bin/env python3
"""v0.8: explicit ChatGPT/Haier wake-word routing and two-turn TV commands."""
from pathlib import Path
import shutil

service = Path("app/src/main/java/com/example/privetchat/AssistantModeService.kt")
s = service.read_text(encoding="utf-8")

marker = "    private var triggered = false"
assert s.count(marker) == 1, "Unexpected service state"
s = s.replace(marker, """    @Volatile private var triggered = false
    @Volatile private var waitingForTvUntil = 0L
    private val tvCommandWindowMs = 10_000L""", 1)

start = s.index("                newRecognizer = Recognizer(newModel, 16_000f,")
end = s.index("                newSpeech = SpeechService(newRecognizer, 16_000f)", start)
s = s[:start] + """                newRecognizer = Recognizer(newModel, 16_000f,
                    VoiceCommandRouter.grammar())
""" + s[end:]

start = s.index("    private fun recognize(json: String?, key: String) {")
end = s.index("    /** Pause Vosk,", start)
s = s[:start] + """    private fun recognize(json: String?, key: String) {
        if (triggered || destroyed || !enabled() || pausedForChat || json.isNullOrBlank()) return
        val transcript = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (transcript.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        val waiting = waitingForTvUntil > now
        val route = VoiceCommandRouter.route(transcript, waiting)

        // Partial hypotheses can change as the speaker finishes. Only an explicit
        // full GPT phrase may fire from a partial result; TV needs a final result.
        if (key == "partial" && !route.openChat) return

        if (route.openChat) {
            triggered = true
            waitingForTvUntil = 0L
            main.post {
                if (destroyed || !enabled() || pausedForChat) return@post
                pauseForChat()
                try {
                    showSession(Bundle.EMPTY, 0)
                    report("Открываю голосовой ChatGPT")
                } catch (error: Exception) {
                    report("Ошибка запуска ChatGPT: ${error.message}")
                    chatUiEnded = true
                    scheduleResumeCheck()
                }
            }
            return
        }

        if (route.tvCommand != null) {
            val command = route.tvCommand
            triggered = true
            waitingForTvUntil = 0L
            main.post {
                if (!destroyed && enabled() && !pausedForChat) {
                    sendTvCommand(command)
                }
                main.postDelayed({ triggered = false }, 1700L)
            }
            return
        }

        if (key != "partial" && route.waitForTv) {
            waitingForTvUntil = now + tvCommandWindowMs
            if (route.addressed) {
                main.post { report("Слушаю команду телевизору (10 секунд)") }
            } else {
                main.post { report("Не понял команду телевизору. Повтори, пожалуйста") }
            }
            return
        }

        if (key != "partial" && now - lastWordsTime > 3000L) {
            lastWordsTime = now
            main.post { report("Распознано: $transcript") }
        }
    }

    private fun sendTvCommand(tvCommand: String) {
        try {
            val target = ComponentName(
                "ru.haier.remote", "ru.haier.remote.VoiceCommandReceiver")
            val receiverInfo = try {
                @Suppress("DEPRECATION")
                packageManager.getReceiverInfo(target, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            when {
                receiverInfo == null -> tvFeedback(
                    "Не отправлено: не найден приёмник Haier Remote")
                !receiverInfo.exported -> tvFeedback(
                    "Не отправлено: Haier Remote не принимает внешние команды")
                else -> {
                    val command = Intent("ru.haier.remote.action.VOICE_COMMAND")
                        .setComponent(target).putExtra("command", tvCommand)
                    sendBroadcast(command)
                    tvFeedback("Передано пульту: $tvCommand (без подтверждения ТВ)")
                }
            }
        } catch (error: Exception) {
            tvFeedback("Ошибка отправки команды: ${error.message}")
        }
    }

""" + s[end:]

# Every restored build starts with source + v0.3–v0.7 overlays.
shutil.copy2("patches/v08/VoiceCommandRouter.kt",
    "app/src/main/java/com/example/privetchat/VoiceCommandRouter.kt")
service.write_text(s, encoding="utf-8")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 7" in g and "versionName '0.7-haier-feedback'" in g
g = g.replace("versionCode 7", "versionCode 8", 1)
g = g.replace("versionName '0.7-haier-feedback'", "versionName '0.8-voice-routing'", 1)
gradle.write_text(g, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_main.xml")
ui = layout.read_text(encoding="utf-8")
ui = ui.replace("Привет, Чат · v0.5", "Привет, Чат · v0.8")
ui = ui.replace(
    "Скажи «Привет, Чат», даже с выключенным экраном.",
    "Скажи «Привет, чат GPT» для ChatGPT или «Привет, чат» для телевизора.")
layout.write_text(ui, encoding="utf-8")
print("v0.8: explicit GPT wake, two-stage Haier commands, safe partial matching")
