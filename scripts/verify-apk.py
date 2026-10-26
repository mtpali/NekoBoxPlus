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
properties = dict(line.split('=',1) for line in (ROOT/'upstream/workspace/NekoBoxForAndroid/nb4a.properties').read_text().splitlines() if '=' in line)
assert package[2] == str(int(properties['VERSION_CODE']) * 5)
assert package[3] == properties['VERSION_NAME']
assert 'application-debuggable' not in badging
assert "application-label:'فیلترشکن'" in badging, 'Incorrect public application label'
resources = subprocess.check_output([str(aapt), 'dump', 'resources', str(apk)], text=True)
assert 'mipmap/ic_launcher_light' not in resources and 'mipmap/ic_launcher_dark' not in resources
assert 'drawable/splash_screen' not in resources
assert 'drawable/ic_service_active' not in resources, 'Legacy cat notification icon remains'

def resource_files(name):
    block = re.search(r'(?m)^    resource 0x[0-9a-f]+ ' + re.escape(name) + r'\n((?:      .*\n)+)', resources)
    assert block, 'Missing resource: ' + name
    return re.findall(r'\(([^)]*)\) \(file\) (\S+) type=XML', block[1])

for name in ['mipmap/ic_launcher', 'mipmap/ic_launcher_round']:
    variants = resource_files(name)
    assert {config for config, _ in variants} == {'anydpi', 'anydpi-v26'}
    for config, path in variants:
        tree = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), '--file', path], text=True)
        assert 'drawable(0x01010199)=@0x0106000b' in tree, 'Missing fixed white launcher background: ' + config
        assert ('E: adaptive-icon' if config.endswith('v26') else 'E: layer-list') in tree
mark_files = resource_files('drawable/mobiletina_notification')
assert len(mark_files) == 1
mark_tree = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), '--file', mark_files[0][1]], text=True)
assert 'E: vector' in mark_tree and 'E: path' in mark_tree and '0xffffffff' in mark_tree
configurations = subprocess.check_output([str(aapt), 'dump', 'configurations', str(apk)], text=True)
removed_locales = {match[1] for relative in json.loads((ROOT/'SOURCE_REMOVALS.json').read_text())
                  if (match := re.search(r'/res/values-([a-z]{2,3})(?:-r[A-Z]{2})?/', relative))}
for language in removed_locales:
    assert not re.search(r'(?m)^\s*(?:b\+)?' + language + r'(?:-|\+|\s*$)', configurations), 'Unexpected APK translation: ' + language
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
    expected = {hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'overlay/workspace/NekoBoxForAndroid/app/src/main/res/raw').glob('mt_*.bin')}
    actual = {hashlib.sha256(z.read(p)).hexdigest() for p in z.namelist() if p.endswith('.bin') and z.getinfo(p).file_size < 500_000}
    assert expected and expected <= actual, 'Connection artwork was changed or removed'
    reference_manifest = json.loads((ROOT/'EXCLAVE_UI_ASSETS_SHA256.json').read_text())
    reference_art = {v for k,v in reference_manifest.items() if k != 'mt_nav.webp'}
    packaged_art = {hashlib.sha256(z.read(name)).hexdigest() for name in z.namelist()
                    if name.endswith('.webp') and z.getinfo(name).file_size < 500_000}
    assert reference_art <= packaged_art, 'Exclave interface artwork is missing or changed'
    assert reference_manifest['mt_nav.webp'] not in packaged_art, 'Plaintext social header must not be packaged'
    font_digest = (ROOT/'REFERENCE_EXCLAVE_FONT_SHA256').read_text().strip()
    assert any(hashlib.sha256(z.read(name)).hexdigest() == font_digest
               for name in z.namelist() if name.endswith('.ttf')), 'Missing Exclave font'
report = {'file': apk.name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'package': package[1], 'versionCode': package[2], 'versionName': package[3], 'abis': sorted(abis),
          'native_source': (ROOT/'UPSTREAM_COMMIT').read_text().strip(), 'debuggable': False,
          'white_launcher_backgrounds': True, 'brand_notification_vector': True, 'legacy_cat_removed': True}
import runpy
report.update(runpy.run_path(str(ROOT/'scripts/verify-release-protection.py'))['verify'](apk, abis))
apk.with_suffix('.verification.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
