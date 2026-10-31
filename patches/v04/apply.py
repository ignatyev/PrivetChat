#!/usr/bin/env python3
"""Apply lock-screen relay on top of v0.3 voice assistant source."""
from pathlib import Path
import shutil

for file in Path('patches/v04/app').rglob('*'):
    if file.is_file():
        destination = Path('app') / file.relative_to('patches/v04/app')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, destination)

def replace_once(filename, old, new):
    path = Path(filename)
    contents = path.read_text(encoding='utf-8')
    assert contents.count(old) == 1, f'Expected once in {filename}: {old}'
    path.write_text(contents.replace(old, new), encoding='utf-8')

replace_once('app/src/main/AndroidManifest.xml', '    </application>', '''        <activity
            android:name=".UnlockToChatActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleTop"
            android:showWhenLocked="true"
            android:turnScreenOn="true" />
    </application>''')
replace_once('app/build.gradle', 'versionCode 3', 'versionCode 4')
replace_once('app/build.gradle', "versionName '0.3-voice-launch-experimental'", "versionName '0.4-lockscreen-relay-experimental'")
print('Applied v0.4 lock-screen relay; OS authentication is still required')
