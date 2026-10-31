#!/usr/bin/env python3
"""v0.9: screen-off vibration feedback with distinct TV, GPT and error patterns."""
from pathlib import Path

service = Path("app/src/main/java/com/example/privetchat/AssistantModeService.kt")
s = service.read_text(encoding="utf-8")

def replace_once(source, old, new, description):
    assert source.count(old) == 1, f"Expected one {description}, got {source.count(old)}"
    return source.replace(old, new, 1)

s = replace_once(s, "import android.os.Looper\n",
    "import android.os.Looper\nimport android.os.VibrationEffect\nimport android.os.Vibrator\nimport android.os.VibratorManager\n",
    "service vibration imports")

s = replace_once(s,
    '                    showSession(Bundle.EMPTY, 0)\n                    report("Открываю голосовой ChatGPT")',
    '                    showSession(Bundle.EMPTY, 0)\n                    vibrateFeedback("chat")\n                    report("Открываю голосовой ChatGPT")',
    "GPT opening feedback")

s = replace_once(s,
    '                } catch (error: Exception) {\n                    report("Ошибка запуска ChatGPT: ${error.message}")',
    '                } catch (error: Exception) {\n                    vibrateFeedback("error")\n                    report("Ошибка запуска ChatGPT: ${error.message}")',
    "GPT error feedback")

s = replace_once(s,
    '                main.post { report("Слушаю команду телевизору (10 секунд)") }',
    '                main.post {\n                    vibrateFeedback("tv")\n                    report("Слушаю команду телевизору (10 секунд)")\n                }',
    "TV listening window feedback")

s = replace_once(s,
    "    private fun sendTvCommand(tvCommand: String) {",
    '''    /**
     * Works while the screen is off; no activity/toast or notification required.
     * TV or TV-ready: single pulse. ChatGPT: two. Failure: three.
     */
    private fun vibrateFeedback(kind: String) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            }
            if (vibrator?.hasVibrator() != true) return
            val pattern = when (kind) {
                "chat" -> longArrayOf(0, 65, 95, 65)
                "error" -> longArrayOf(0, 55, 75, 55, 75, 55)
                else -> longArrayOf(0, 65)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
            // Feedback must never prevent voice recognition or TV control.
        }
    }

    private fun sendTvCommand(tvCommand: String) {''',
    "vibrator implementation")

s = replace_once(s,
    '''    private fun tvFeedback(message: String) {
        getSharedPreferences(PREF, MODE_PRIVATE)''',
    '''    private fun tvFeedback(message: String) {
        val failure = message.startsWith("Не отправлено") || message.startsWith("Ошибка")
        vibrateFeedback(if (failure) "error" else "tv")
        getSharedPreferences(PREF, MODE_PRIVATE)''',
    "TV result haptic feedback")
service.write_text(s, encoding="utf-8")

manifest = Path("app/src/main/AndroidManifest.xml")
m = manifest.read_text(encoding="utf-8")
assert 'android.permission.VIBRATE' not in m, "VIBRATE permission already present"
m = replace_once(m, "    <application",
    '    <uses-permission android:name="android.permission.VIBRATE" />\n\n    <application',
    "VIBRATE manifest declaration")
manifest.write_text(m, encoding="utf-8")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 8" in g and "versionName '0.8-voice-routing'" in g
g = g.replace("versionCode 8", "versionCode 9", 1)
g = g.replace("versionName '0.8-voice-routing'", "versionName '0.9-haptic-feedback'", 1)
gradle.write_text(g, encoding="utf-8")

layout = Path("app/src/main/res/layout/activity_main.xml")
ui = layout.read_text(encoding="utf-8")
ui = ui.replace("Привет, Чат · v0.8", "Привет, Чат · v0.9")
layout.write_text(ui, encoding="utf-8")
print("v0.9: permission and distinct TV/GPT/failure haptics; stable signing unchanged")
