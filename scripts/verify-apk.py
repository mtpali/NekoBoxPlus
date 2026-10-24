#!/usr/bin/env python3
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
apk, aapt = map(Path, sys.argv[1:3])
badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
assert package and package[1] == 'com.nb4a.mobiletina', badging[:200]
assert 'application-debuggable' not in badging
expected_abis = {'armeabi-v7a', 'arm64-v8a'} if 'universal' in apk.name else ({'armeabi-v7a'} if 'armeabi-v7a' in apk.name else {'arm64-v8a'})
with zipfile.ZipFile(apk) as z:
    abis = {name.split('/')[1] for name in z.namelist() if name.startswith('lib/') and name.endswith('.so')}
    assert abis == expected_abis, (apk.name, abis)
    for abi in abis:
        core = z.read(f'lib/{abi}/libgojni.so')
        assert b'amneziawg' in core.lower(), f'Missing Amnezia backend: {abi}'
        assert core[:4] == b'\x7fELF'
        assert core[4] == (1 if abi == 'armeabi-v7a' else 2), f'Incorrect ELF class: {abi}'
        assert int.from_bytes(core[18:20], 'little') == (40 if abi == 'armeabi-v7a' else 183), f'Incorrect CPU: {abi}'
    expected = {hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'overlay/workspace/NekoBoxForAndroid/app/src/main/res/raw').glob('mt_auto_*.bin')}
    actual = {hashlib.sha256(z.read(p)).hexdigest() for p in z.namelist() if p.endswith('.bin') and z.getinfo(p).file_size < 500_000}
    assert expected and expected <= actual, 'Connection artwork was changed or removed'
report = {'file': apk.name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'package': package[1], 'versionCode': package[2], 'versionName': package[3], 'abis': sorted(abis),
          'native_source': (ROOT/'UPSTREAM_COMMIT').read_text().strip(), 'debuggable': False}
apk.with_suffix('.verification.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
