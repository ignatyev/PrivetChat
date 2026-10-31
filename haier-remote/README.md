# Haier Remote 1.9 — integration with PrivetChat

This directory documents the Haier Remote voice bridge; the complete 1.9 standalone Android project is available in the ZIP attachment from the chat. **The complete Haier Remote source tree has not yet been copied into this repository.**

The supplied `VoiceCommandReceiver.java` is intended for `app/src/main/java/ru/haier/remote/` in Haier Remote 1.8/1.9. Register it in Haier Remote's `AndroidManifest.xml`:

```xml
<receiver android:name=".VoiceCommandReceiver" android:exported="true">
  <intent-filter>
    <action android:name="ru.haier.remote.action.VOICE_COMMAND" />
  </intent-filter>
</receiver>
```

The receiver accepts **only** fixed named commands, not raw keycodes or arbitrary host addresses; any installed app can still send those fixed commands while the receiver is exported.

PrivetChat `patches/v06/apply.py` adds offline Vosk recognition of commands such as “включи телевизор”, “выключи телевизор”, “телевизор громче/тише” and navigation. It sends explicit Android broadcasts to Haier Remote on the **same Android phone**, which controls the TV via existing Wi-Fi methods and Wake-on-LAN.

Requirements:
- Both APKs must be installed on the same phone.
- Haier Remote must be paired with the Haier TV and able to turn it on from its own app.
- Phone and TV are connected to the relevant local network.
- Enable PrivetChat's background listening and assistant role.

No Home Assistant or permanently running PC is required. ChatGPT itself has no direct access to the TV. Test voice recognition and installation before relying on it.

**Build note:** PrivetChat's GitHub Action builds only the assistant. To build Haier Remote 1.9, use the full source ZIP and its own build workflow.
