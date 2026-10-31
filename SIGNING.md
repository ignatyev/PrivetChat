# PrivetChat: upgrade APKs without uninstalling

Android only installs an APK over an existing app when **package ID** and **signing certificate** match, and the new versionCode is greater.

The old GitHub workflow used the runner's fresh ephemeral Android debug key. Different runs could have different signing certificates, which explains "app not installed" during updates.

## One-time setup

1. Download **PrivetChat-GitHub-secret.txt** from the private ChatGPT conversation where the key was generated. Keep both that text and **PrivetChat-signing.keystore** securely backed up. Do **not** commit either to this public repository.
2. In GitHub, open this repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
3. Name: `PRIVETCHAT_SIGNING_KEYSTORE_B64`. Value: paste the **entire single-line text** from `PrivetChat-GitHub-secret.txt` (no quotation marks or spaces). Save.
4. Open **Actions** → **Build Android APK** → **Run workflow** → select `feature/haier-tv-voice`, and run it. The workflow refuses to produce a debug APK if the secret is absent, avoiding accidental new certificates.
5. Download the resulting APK from **Artifacts**.

## First transition

Previously installed PrivetChat builds are most likely signed with a **different** ephemeral debug certificate. If Android refuses to install this APK over the old one, you must **uninstall PrivetChat once**, install the new consistently signed APK and re-enable assistant/microphone/accessibility settings. The existing private signing key cannot be recovered from the old APK.

From that point onwards, **keep using this same secret** for new builds. APK updates will preserve app data provided you do not alter applicationId or decrease versionCode.

## Signing details

- Android default debug keystore filename: `$HOME/.android/debug.keystore`
- Keystore alias: `androiddebugkey`
- Keystore password and key password: `android`
- The signing key itself is secret and must remain outside the repository.
- The workflow validates compilation with `assembleRelease` first, checks for the signing secret, then builds and uploads `assembleDebug`.

## Voice feedback limitations

Version 0.7 shows a short Android toast and retains the last command status in PrivetChat. It can report "not sent" when Haier Remote's receiver isn't available or a local dispatch throws. "Sent" means dispatched into Android toward Haier Remote, **not** guaranteed executed by the TV. Actual TV acknowledgment would require additional protocol/status handling in Haier Remote.
