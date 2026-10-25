#!/usr/bin/env python3
import hashlib
import json
import re
import subprocess
import sys
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
    assert ids(original) - {'action_connection_tcp_ping','action_selection_tcp_ping','action_connection_icmp_ping','action_selection_icmp_ping'} <= ids(current), 'Removed menu actions: '+path
settings = 'workspace/NekoBoxForAndroid/app/src/main/java/io/nekohasekai/sagernet/ui/compose/GlobalSettingsScreen.kt'
original = subprocess.check_output(['git','-C',str(UPSTREAM),'show','HEAD:'+settings], text=True)
current = (UPSTREAM/settings).read_text()
keys = lambda text: set(re.findall(r'(?:sw|text|list|action)\(\s*"([^"]+)"', text))
# Source removals explicitly authorized by the user through release105.
assert keys(original) - {'changeIcon', 'enableGroupUpdateDialog', 'notificationCountryIndicator', 'useToolbar', 'profileCountryIndicator', 'showBottomBarInSettings', 'compactStatsBar', 'legacyMainView', 'showDirectSpeed', 'showGroupInNotification', 'persistentStatusNotification', 'alwaysShowAddress', 'tabDoubleTapToNavigate', 'configureToolbarLayout'} <= keys(current), 'An unapproved upstream setting was removed'
assert 'changeIcon' not in keys(current)
for path in res.glob('values*/*.xml'):
    for name, value in re.findall(r'<string[^>]*name="(app_name(?:_long)?)"[^>]*>(.*?)</string>',path.read_text(),re.S):
        assert value == 'فیلترشکن', (path, name, value)
for color in ['white','yellow','blue','red']:
    assert (res/f'raw/mt_auto_{color}.bin').stat().st_size > 50_000
assert re.fullmatch('[a-f0-9]{64}', (ROOT/'RELEASE_CERT_SHA256').read_text().strip())
for relative in json.loads((ROOT/'SOURCE_REMOVALS.json').read_text()):
    assert not (UPSTREAM/relative).exists(), 'Requested removal was not applied: '+relative
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(NBA/'app/src/main/AndroidManifest.xml').getroot()
aliases = manifest.findall('application/activity-alias')
for relative in ['app/src/main/res/xml/shortcuts.xml', 'app/src/debug/res/xml/shortcuts.xml']:
    shortcuts = ET.parse(NBA/relative).getroot()
    assert len(shortcuts) == 1 and shortcuts[0].get(android+'shortcutId') == 'toggle'
assert not any('QuickEnableShortcut' in a.get(android+'name', '') or 'QuickDisableShortcut' in a.get(android+'name', '')
               for a in manifest.findall('application/activity'))
assert len(aliases) == 1 and aliases[0].get(android+'name') == 'io.nekohasekai.sagernet.launcher.NekoBoxPlus'
for directory in res.iterdir():
    if re.fullmatch(r'values-[a-z]{2,3}(?:-r[A-Z]{2})?', directory.name) and directory.name != 'values-night':
        assert not any(directory.glob('*.xml')), 'Unexpected translation: '+directory.name
locales = ET.parse(res/'xml/locales_config.xml').getroot()
assert [locale.get(android+'name') for locale in locales] == ['en']
assert not (res/'drawable/splash_screen.xml').exists()
assert '@android:color/transparent' in (res/'values-v31/themes.xml').read_text()
assert 'android:inset="18%"' in (res/'drawable/mobiletina_launcher_safe.xml').read_text()
font = res/'font/mobiletina.ttf'
assert hashlib.sha256(font.read_bytes()).hexdigest() == (ROOT/'REFERENCE_EXCLAVE_FONT_SHA256').read_text().strip()
assert 'name="fontFamily">@font/mobiletina' in (res/'values/themes.xml').read_text()
main = (NBA/'app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt').read_text()
setup = main.split('private suspend fun performProxyAppsFirstSetup() {',1)[1].split('\n    }',1)[0]
assert 'DataStore.proxyApps = false' in setup and 'DataStore.proxyApps = true' not in setup
assert 'R.layout.layout_about' in (NBA/'app/src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt').read_text()
assert (NBA/'app/src/main/assets/LICENSE').stat().st_size > 100
print('Verified XML, identity, ARM ABIs, original menu/actions, authorized removals, single language/icon, startup defaults, Exclave font, artwork, and license.')

# The reference layouts/assets and actual welcome replace the former approximation.
for name, digest in json.loads((ROOT/'EXCLAVE_UI_ASSETS_SHA256.json').read_text()).items():
    if name != 'mt_nav.webp':
        assert hashlib.sha256((res/'drawable-nodpi'/name).read_bytes()).hexdigest() == digest
home = (NBA/'app/src/main/java/io/nekohasekai/sagernet/ui/MobileTinaHomeFragment.kt').read_text()
assert 'R.layout.mobiletina_home' in home and 'welcomeSeen' not in home
assert 'mobiletina_welcome_title' not in (res/'values/mobiletina_strings.xml').read_text()
assert 'MobileTinaFirstLaunchDialog.showOnce' in main
header = ET.parse(res/'layout/mobiletina_home_header.xml').getroot()
header_ids = {n.get(android+'id') for n in header.iter()}
assert {'@+id/mobiletina_scan','@+id/mobiletina_add','@+id/mobiletina_more','@+id/btn_mode_manual','@+id/btn_mode_auto'} <= header_ids
assert (res/'layout/mobiletina_manual_dock.xml').is_file()
owned_resources = set()
for path in res.rglob('*'):
    if not path.is_file():
        continue
    if path.parent.name.startswith('values') and path.suffix == '.xml':
        for node in ET.parse(path).getroot():
            if node.get('name'):
                owned_resources.add((node.tag, node.get('name')))
    else:
        owned_resources.add((path.parent.name.split('-')[0], path.stem))
for path in (ROOT/'overlay/workspace/NekoBoxForAndroid/app/src/main/res').rglob('*.xml'):
    for kind, name in re.findall(r'@(string|layout|drawable|color|font)/([A-Za-z0-9_.]+)', path.read_text()):
        assert (kind, name) in owned_resources, ('Missing reference resource', path, kind, name)

subprocess.run([sys.executable, str(ROOT/'scripts/branding-contract.py')], check=True)


assert hashlib.sha256((res/'drawable-nodpi/mobiletina_launcher.png').read_bytes()).hexdigest() == (ROOT/'LAUNCHER_ICON_SHA256').read_text().strip()

# Release105 removes these complete application features, not just their settings rows.
application_source = "\n".join(p.read_text() for p in (NBA/'app/src/main/java').rglob('*') if p.suffix in {'.kt', '.java'})
for token in ('notificationCountryIndicator','useToolbar','profileCountryIndicator','showBottomBarInSettings','compactStatsBar','legacyMainView','showDirectSpeed','showGroupInNotification','persistentStatusNotification','alwaysShowAddress','tabDoubleTapToNavigate','configureToolbarLayout','startIcmpPing','icmpPingWithAddress','canICMPing','ToolsFragment','LogcatFragment','WebviewFragment','BackupFragment','GitBackupRepository','CountryFlagRenderer'):
    assert token not in application_source, 'Removed feature remains: '+token
assert 'syncImportedPayloads(targetId, payloads)' in (NBA/'app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt').read_text()
notification = (NBA/'app/src/main/java/io/nekohasekai/sagernet/bg/ServiceNotification.kt').read_text()
assert '.setCustomContentView(content()).setCustomBigContentView(content())' in notification
assert 'setOnClickPendingIntent(R.id.mobiletina_notification_stop, stop)' in notification
assert '.setOngoing(true)' in notification
assert not (NBA/'app/src/main/assets/metacubexd.tgz').exists()
print('Verified release105 complete feature removals, import metadata and always-present notification stop control.')

expiry_source = (NBA/'app/src/main/java/io/nekohasekai/sagernet/ui/MobileTinaSubscriptionExpiry.kt').read_text()
assert 'System.currentTimeMillis' not in expiry_source
assert 'listOf("https://api.github.com/zen", "https://github.com/robots.txt"' in expiry_source
assert 'fun nowMillis(): Long?' in expiry_source
original_manifest = ET.fromstring(subprocess.check_output(['git','-C',str(UPSTREAM),'show','HEAD:workspace/NekoBoxForAndroid/app/src/main/AndroidManifest.xml']))
activity_names = lambda doc: {a.get(android+'name') for a in doc.findall('application/activity')}
removed_activities = {'GitBackupSettingsActivity','WebDAVSettingsActivity','CellularNetworkActivity','ConnectionTestNotificationActionActivity','QuickDisableShortcut','QuickEnableShortcut','RuleSetMatchActivity','SpeedTestActivity','StunActivity','ToolbarLayoutActivity'}
assert {name for name in activity_names(original_manifest) if name.rsplit('.',1)[-1] not in removed_activities} <= activity_names(manifest)
print('Verified GitHub-first trusted clock, no device-date expiration fallback and preserved application activities.')
