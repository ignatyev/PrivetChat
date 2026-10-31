#!/usr/bin/env python3
"""Add local Haier TV voice commands to PrivetChat v0.5 without cloud API."""
from pathlib import Path

service = Path('app/src/main/java/com/example/privetchat/AssistantModeService.kt')
s = service.read_text(encoding='utf-8')

old = r'\"[unk]\"'
additional = ','.join(r'\"'+x+r'\"' for x in (
    'включи телевизор', 'выключи телевизор',
    'телевизор громче', 'телевизор тише', 'телевизор домой',
    'телевизор назад', 'телевизор вверх', 'телевизор вниз',
    'телевизор влево', 'телевизор вправо', 'телевизор окей',
))
assert s.count(old) == 1, 'Vosk grammar changed; refuse to patch blindly'
s = s.replace(old, additional + ',' + old, 1)

anchor = '        if (normalized.contains("привет чат") || normalized.contains("привет чет")) {'
assert s.count(anchor) == 1, 'Voice trigger changed; refuse to patch blindly'
bridge = '''        // Offline command recognition. Haier Remote processes the command locally over Wi-Fi.
        // Keep the existing ChatGPT wake phrase intact for non-TV requests.
        val tvCommand = when {
            normalized.contains("выключи телевизор") -> "sleep"
            normalized.contains("включи телевизор") -> "wake"
            normalized.contains("телевизор громче") -> "volume_up"
            normalized.contains("телевизор тише") -> "volume_down"
            normalized.contains("телевизор домой") -> "home"
            normalized.contains("телевизор назад") -> "back"
            normalized.contains("телевизор вверх") -> "up"
            normalized.contains("телевизор вниз") -> "down"
            normalized.contains("телевизор влево") -> "left"
            normalized.contains("телевизор вправо") -> "right"
            normalized.contains("телевизор окей") -> "ok"
            else -> null
        }
        if (tvCommand != null) {
            triggered = true
            main.post {
                if (!destroyed && enabled() && !pausedForChat) {
                    try {
                        val command = Intent("ru.haier.remote.action.VOICE_COMMAND")
                            .setClassName("ru.haier.remote", "ru.haier.remote.VoiceCommandReceiver")
                            .putExtra("command", tvCommand)
                        sendBroadcast(command)
                        report("Команда телевизору отправлена: $tvCommand")
                    } catch (error: Exception) {
                        report("Ошибка управления телевизором: ${error.message}")
                    }
                }
                // Debounce Vosk partial/final hypotheses of the same phrase.
                main.postDelayed({ triggered = false }, 2500L)
            }
            return
        }
'''
s = s.replace(anchor, bridge + anchor, 1)
service.write_text(s, encoding='utf-8')

gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
assert 'versionCode 5' in g and "versionName '0.5-background-listening'" in g
g = g.replace('versionCode 5', 'versionCode 6', 1)
g = g.replace("versionName '0.5-background-listening'", "versionName '0.6-haier-control'", 1)
gradle.write_text(g, encoding='utf-8')
print('v0.6: local Haier TV voice bridge enabled')
