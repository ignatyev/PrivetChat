#!/usr/bin/env python3
"""v0.10: persistent TV mode, explicit voice exit, and ordered commands."""
from pathlib import Path
import shutil

service = Path("app/src/main/java/com/example/privetchat/AssistantModeService.kt")
s = service.read_text(encoding="utf-8")

def change(old, new, name):
    global s
    count = s.count(old)
    assert count == 1, f"{name}: expected once, found {count}"
    s = s.replace(old, new, 1)

change(
    "    @Volatile private var waitingForTvUntil = 0L\n    private val tvCommandWindowMs = 10_000L",
    '''    @Volatile private var tvModeActive = false
    private var lastCompletedResult = ""
    private var lastCompletedAt = 0L
    // TV dispatch is serialized on the main thread to preserve navigation order.
    private val tvQueue = java.util.ArrayDeque<String>()
    private var tvDispatchScheduled = false
    private val tvDispatcher = object : Runnable {
        override fun run() {
            if (!tvModeActive || destroyed || !enabled() || pausedForChat) {
                tvQueue.clear()
                tvDispatchScheduled = false
                return
            }
            val command = tvQueue.pollFirst()
            if (command == null) {
                tvDispatchScheduled = false
                return
            }
            sendTvCommand(command)
            if (tvQueue.isNotEmpty()) main.postDelayed(this, 220L)
            else tvDispatchScheduled = false
        }
    }

    private fun enqueueTvCommands(commands: List<String>) {
        tvQueue.addAll(commands)
        if (!tvDispatchScheduled && tvQueue.isNotEmpty()) {
            tvDispatchScheduled = true
            main.post(tvDispatcher)
        }
    }

    private fun clearTvCommands() {
        main.removeCallbacks(tvDispatcher)
        tvQueue.clear()
        tvDispatchScheduled = false
    }''',
    "TV session state and ordered dispatch")

change(
    '    override fun onFinalResult(hypothesis: String?) = recognize(hypothesis, "text")',
    '    override fun onFinalResult(hypothesis: String?) = recognize(hypothesis, "final")',
    "Vosk final callback differentiation")

start = s.index("    private fun recognize(json: String?, key: String) {")
end = s.index("    /**\n     * Works while the screen is off;", start)
s = s[:start] + '''    private fun recognize(json: String?, key: String) {
        if (triggered || destroyed || !enabled() || pausedForChat || json.isNullOrBlank()) return
        val transcript = try {
            JSONObject(json).optString(if (key == "partial") "partial" else "text")
        } catch (_: Exception) { "" }
        if (transcript.isBlank()) return

        val route = VoiceCommandRouter.route(transcript, tvModeActive)
        // Partial Vosk hypotheses can change; only GPT may launch from a partial.
        if (key == "partial" && !route.openChat) return

        if (key != "partial") {
            val now = SystemClock.elapsedRealtime()
            // A finalResult sometimes repeats the immediately preceding result.
            // Do not throttle actual repeated words in successive onResult callbacks.
            if (key == "final" && transcript == lastCompletedResult &&
                now - lastCompletedAt < 1500L) return
            lastCompletedResult = transcript
            lastCompletedAt = now
        }

        if (route.openChat) {
            triggered = true
            tvModeActive = false
            main.post {
                clearTvCommands()
                if (destroyed || !enabled() || pausedForChat) return@post
                pauseForChat()
                try {
                    showSession(Bundle.EMPTY, 0)
                    vibrateFeedback("chat")
                    report("Открываю голосовой ChatGPT")
                } catch (error: Exception) {
                    vibrateFeedback("error")
                    report("Ошибка запуска ChatGPT: " + error.message)
                    chatUiEnded = true
                    scheduleResumeCheck()
                }
            }
            return
        }
        if (key == "partial") return

        if (route.exitTvMode) {
            tvModeActive = false
            main.post {
                clearTvCommands()
                vibrateFeedback("tv")
                report("Режим управления телевизором выключен")
            }
            return
        }

        if (route.enterTvMode || route.tvCommands.isNotEmpty()) {
            val wasActive = tvModeActive
            tvModeActive = true
            main.post {
                if (destroyed || !enabled() || pausedForChat || !tvModeActive) return@post
                if (!wasActive) {
                    vibrateFeedback("tv")
                    report("Управление телевизором включено. Для завершения скажи «выход»")
                }
                if (route.tvCommands.isNotEmpty()) {
                    enqueueTvCommands(route.tvCommands)
                } else if (wasActive && route.enterTvMode) {
                    report("Управление телевизором уже включено")
                }
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (route.addressed && tvModeActive) {
            main.post { report("Не понял команду телевизору: $transcript") }
        } else if (now - lastWordsTime > 3000L) {
            lastWordsTime = now
            main.post { report("Распознано: $transcript") }
        }
    }

''' + s[end:]

# Stop TV control explicitly when the user disables background listening.
change('''            if (!enabled()) {
                pausedForChat = false''',
       '''            if (!enabled()) {
                tvModeActive = false
                clearTvCommands()
                pausedForChat = false''',
       "user microphone toggle")

# GPT taking the microphone also cancels a TV session, even when launched via UI.
change('''    fun pauseForChat() {
        if (!pausedForChat) {''',
       '''    fun pauseForChat() {
        tvModeActive = false
        clearTvCommands()
        if (!pausedForChat) {''',
       "ChatGPT session handoff")

service.write_text(s, encoding="utf-8")
shutil.copy2("patches/v10/VoiceCommandRouter.kt",
    "app/src/main/java/com/example/privetchat/VoiceCommandRouter.kt")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 9" in g and "versionName '0.9-haptic-feedback'" in g
g = g.replace("versionCode 9", "versionCode 10", 1)
g = g.replace("versionName '0.9-haptic-feedback'",
              "versionName '0.10-continuous-tv'", 1)
gradle.write_text(g, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_main.xml")
ui = layout.read_text(encoding="utf-8")
ui = ui.replace("Привет, Чат · v0.9", "Привет, Чат · v0.10")
ui = ui.replace(
    "Скажи «Привет, чат GPT» для ChatGPT или «Привет, чат» для телевизора.",
    "«Привет, чат» — телевизор; затем команды без обращения до слова «выход». «Привет, чат GPT» — ChatGPT.")
layout.write_text(ui, encoding="utf-8")

print("v0.10: TV mode stays active until exit; ordered commands and stable signing")
