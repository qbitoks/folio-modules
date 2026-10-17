#!/usr/bin/env python3
"""Puts a released module APK into modules.json: update_index.py <module> <apk> <url>.
Engines live in `modules` (tts-<id>/), viewers in `viewers` (<id>/app/, e.g. documents)."""
import hashlib, json, os, re, sys

module, apk, url = sys.argv[1:4]
viewer = os.path.isdir(f"{module}/app")
build = open(f"{module}/app/build.gradle.kts" if viewer else f"tts-{module}/build.gradle.kts", encoding="utf-8").read()
code = int(re.search(r"versionCode = (\d+)", build).group(1))
name = re.search(r'versionName = "([^"]+)"', build).group(1)
package = re.search(r'applicationId = "([^"]+)"', build).group(1)
digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()

index = json.load(open("modules.json", encoding="utf-8"))
entry = next(m for m in index["viewers" if viewer else "modules"] if m["id"] == module)
assert entry["package"] == package, f"{package} != {entry['package']}"
entry.update(versionCode=code, versionName=name, url=url, sha256=digest, bytes=os.path.getsize(apk))
with open("modules.json", "w", encoding="utf-8") as f:
    json.dump(index, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"{module} {name} ({code}) {digest}")
