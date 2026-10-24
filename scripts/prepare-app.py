#!/usr/bin/env python3
"""Apply reviewable application changes to the pinned upstream checkout."""
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = ROOT / "upstream"
expected = (ROOT / "UPSTREAM_COMMIT").read_text().strip()
actual = subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True).strip()
if actual != expected:
    raise SystemExit(f"Upstream mismatch: {actual} != {expected}")
patch = ROOT / "patches/app.patch"
check = subprocess.run(["git", "-C", str(UPSTREAM), "apply", "--check", str(patch)], capture_output=True)
if check.returncode == 0:
    subprocess.run(["git", "-C", str(UPSTREAM), "apply", str(patch)], check=True)
else:
    subprocess.run(["git", "-C", str(UPSTREAM), "apply", "--reverse", "--check", str(patch)], check=True)
for source in sorted((ROOT / "overlay").rglob("*")):
    if source.is_file():
        target = UPSTREAM / source.relative_to(ROOT / "overlay")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
# Keep user-authorized removals explicit and reproducible without changing the upstream pin.
for relative in json.loads((ROOT / "SOURCE_REMOVALS.json").read_text()):
    target = UPSTREAM / relative
    if not relative.startswith("workspace/NekoBoxForAndroid/app/src/") or not target.resolve().is_relative_to(UPSTREAM.resolve()):
        raise SystemExit(f"Invalid removal path: {relative}")
    target.unlink(missing_ok=True)
res = UPSTREAM / "workspace/NekoBoxForAndroid/app/src/main/res"
for source in res.glob("values*/**/*.xml"):
    text = source.read_text()
    changed = re.sub(r'(<string\s+[^>]*name="app_name(?:_long)?"[^>]*>).*?(</string>)', r'\g<1>VPN\2', text, flags=re.S)
    if changed != text:
        source.write_text(changed)
print("Prepared VPN source: original core/menu destinations retained, requested UI removals applied.")
