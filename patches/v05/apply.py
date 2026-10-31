#!/usr/bin/env python3
"""Apply v0.5 always-listening mode after v0.3 and v0.4 overlays."""
from pathlib import Path
import shutil

for source in Path("patches/v05/app").rglob("*"):
    if source.is_file():
        target = Path("app") / source.relative_to("patches/v05/app")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

gradle = Path("app/build.gradle")
content = gradle.read_text(encoding="utf-8")
assert "versionCode 4" in content
assert "versionName '0.4-lockscreen-relay-experimental'" in content
content = content.replace("versionCode 4", "versionCode 5", 1)
content = content.replace("versionName '0.4-lockscreen-relay-experimental'",
                          "versionName '0.5-background-listening'", 1)
gradle.write_text(content, encoding="utf-8")
print("v0.5: persistent toggle and automatic microphone handoff configured")
