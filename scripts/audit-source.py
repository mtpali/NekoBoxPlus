#!/usr/bin/env python3
import hashlib
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = ROOT/'upstream'
NBA = UPSTREAM/'workspace/NekoBoxForAndroid'
res = NBA/'app/src/main/res'
for path in res.rglob('*.xml'):
    ET.parse(path)
metadata = (NBA/'nb4a.properties').read_text()
assert 'PACKAGE_NAME=com.nb4a.mobiletina' in metadata
helpers = (NBA/'buildSrc/src/main/kotlin/Helpers.kt').read_text()
assert 'include("armeabi-v7a")' in helpers and 'include("arm64-v8a")' in helpers
assert 'include("x86")' not in helpers and 'include("x86_64")' not in helpers
paths = subprocess.check_output(['git','-C',str(UPSTREAM),'ls-tree','-r','--name-only','HEAD','workspace/NekoBoxForAndroid/app/src/main/res/menu'], text=True).splitlines()
for path in paths:
    original = subprocess.check_output(['git','-C',str(UPSTREAM),'show','HEAD:'+path], text=True)
    current = (UPSTREAM/path).read_text()
    ids = lambda text: set(re.findall(r'android:id="@\+?id/([^"]+)"', text))
    assert ids(original) <= ids(current), 'Removed menu actions: '+path
settings = 'workspace/NekoBoxForAndroid/app/src/main/java/io/nekohasekai/sagernet/ui/compose/GlobalSettingsScreen.kt'
original = subprocess.check_output(['git','-C',str(UPSTREAM),'show','HEAD:'+settings], text=True)
current = (UPSTREAM/settings).read_text()
keys = lambda text: set(re.findall(r'(?:sw|text|list|action)\("([^"]+)"', text))
assert keys(original) <= keys(current), 'An upstream setting was removed'
for path in res.glob('values*/*.xml'):
    for name, value in re.findall(r'<string[^>]*name="(app_name(?:_long)?)"[^>]*>(.*?)</string>',path.read_text(),re.S):
        assert value == 'VPN', (path, name, value)
for color in ['white','yellow','blue','red']:
    assert (res/f'raw/mt_auto_{color}.bin').stat().st_size > 50_000
assert re.fullmatch('[a-f0-9]{64}', (ROOT/'RELEASE_CERT_SHA256').read_text().strip())
print('Verified XML, identity, ARM ABIs, artwork, and preservation of every original menu action and setting.')
