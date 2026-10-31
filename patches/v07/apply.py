#!/usr/bin/env python3
"""v0.7: visible command-delivery feedback and persistent status.

This only confirms local Android dispatch to Haier Remote, NOT TV execution.
"""
from pathlib import Path

service = Path("app/src/main/java/com/example/privetchat/AssistantModeService.kt")
source = service.read_text(encoding="utf-8")

for old, new in (
    ("import android.content.BroadcastReceiver", "import android.content.BroadcastReceiver\nimport android.content.ComponentName"),
    ("import android.service.voice.VoiceInteractionService", "import android.service.voice.VoiceInteractionService\nimport android.widget.Toast"),
):
    assert source.count(old) == 1, f"Service changed: {old}"
    source = source.replace(old, new, 1)

old = '''                        val command = Intent("ru.haier.remote.action.VOICE_COMMAND")
                            .setClassName("ru.haier.remote", "ru.haier.remote.VoiceCommandReceiver")
                            .putExtra("command", tvCommand)
                        sendBroadcast(command)
                        report("Команда телевизору отправлена: $tvCommand")'''
new = '''                        val target = ComponentName(
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
                                    .setComponent(target)
                                    .putExtra("command", tvCommand)
                                sendBroadcast(command)
                                tvFeedback("Передано пульту: $tvCommand (без подтверждения ТВ)")
                            }
                        }'''
assert source.count(old) == 1, "Command-dispatch block changed"
source = source.replace(old, new, 1)

old = '''                        report("Ошибка управления телевизором: __ERROR__")'''
new = '''                        tvFeedback("Ошибка отправки команды: __ERROR__")'''
old=old.replace("__ERROR__", "$" + "{error.message}")
new=new.replace("__ERROR__", "$" + "{error.message}")
assert source.count(old) == 1, "Error handler changed"
source = source.replace(old, new, 1)

old = '''    private fun report(message: String) {'''
new = '''    private fun tvFeedback(message: String) {
        getSharedPreferences(PREF, MODE_PRIVATE)
            .edit().putString("last_tv_status", message).apply()
        report(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun report(message: String) {'''
assert source.count(old) == 1, "Status reporter changed"
source = source.replace(old, new, 1)
service.write_text(source, encoding="utf-8")

manifest = Path("app/src/main/AndroidManifest.xml")
s = manifest.read_text(encoding="utf-8")
marker = "    <application"
assert s.count(marker) == 1, "Manifest application element changed"
s = s.replace(marker, '''    <queries>
        <package android:name="ru.haier.remote" />
    </queries>

''' + marker, 1)
manifest.write_text(s, encoding="utf-8")

activity = Path("app/src/main/java/com/example/privetchat/MainActivity.kt")
s = activity.read_text(encoding="utf-8")
marker = '        updateToggle()\n    }\n\n    private fun askForRequiredPermissions()'
assert s.count(marker) == 1, "MainActivity status layout changed"
s = s.replace(marker, '''        val lastTv = getSharedPreferences(AssistantModeService.PREF, MODE_PRIVATE)
            .getString("last_tv_status", null)
        if (lastTv != null) assistantLabel.append("\\nПоследняя команда: $lastTv")
        updateToggle()
    }

    private fun askForRequiredPermissions()''', 1)
activity.write_text(s, encoding="utf-8")

gradle = Path("app/build.gradle")
g = gradle.read_text(encoding="utf-8")
assert "versionCode 6" in g and "versionName '0.6-haier-control'" in g
g = g.replace("versionCode 6", "versionCode 7", 1)
g = g.replace("versionName '0.6-haier-control'", "versionName '0.7-haier-feedback'", 1)
gradle.write_text(g, encoding="utf-8")
print("v0.7: local delivery status + Toast + manifest visibility enabled")
