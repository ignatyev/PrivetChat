#!/usr/bin/env python3
"""Apply v0.3 overlay to the archived v0.2 Android Studio project."""
from pathlib import Path
import shutil

root = Path("app")

def replace_once(path, old, new):
    p = Path(path)
    content = p.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected {p}: expected one match, got {count}: {old[:60]}")
    p.write_text(content.replace(old, new), encoding="utf-8")

for f in (Path("patches/app")).rglob("*"):
    if f.is_file():
        dest = Path("app") / f.relative_to("patches/app")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dest)

replace_once(
    "app/src/main/java/com/example/privetchat/AssistantSessionService.kt",
    "            startAssistantActivity(ChatGptLauncher.intent(context))",
    "            VoiceButtonAccessibilityService.armOnce(context)\n"
    "            startAssistantActivity(ChatGptLauncher.intent(context))"
)
replace_once(
    "app/src/main/AndroidManifest.xml",
    "    </application>",
    """        <service
            android:name=".VoiceButtonAccessibilityService"
            android:label="Привет, Чат — запуск голосового режима"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/voice_button_accessibility" />
        </service>
    </application>"""
)
replace_once(
    "app/src/main/res/layout/activity_main.xml",
    '        <Button\n            android:id="@+id/openChat"',
    '        <Button\n'
    '            android:id="@+id/accessibilitySettings"\n'
    '            android:layout_width="match_parent"\n'
    '            android:layout_height="wrap_content"\n'
    '            android:text="3. Разрешить автоматическое нажатие Voice" />\n'
    '        <Button\n'
    '            android:id="@+id/openChat"'
)
replace_once(
    "app/src/main/java/com/example/privetchat/MainActivity.kt",
    '        findViewById<Button>(R.id.openChat).setOnClickListener',
    '        findViewById<Button>(R.id.accessibilitySettings).setOnClickListener {\n'
    '            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))\n'
    '        }\n'
    '        findViewById<Button>(R.id.openChat).setOnClickListener'
)
replace_once("app/build.gradle", "versionCode 2", "versionCode 3")
replace_once("app/build.gradle", "versionName '0.2-experimental'", "versionName '0.3-voice-launch-experimental'")
print("Applied optional ChatGPT Voice button accessibility integration (v0.3)")
