#!/usr/bin/env python3
"""Cross-check the public native/JVM branding contract before compiling."""
import hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
APP=ROOT/'upstream/workspace/NekoBoxForAndroid/app'
JAVA=APP/'src/main/java/io/nekohasekai/sagernet/utils'
RES=APP/'src/main/res'
c=json.loads((ROOT/'BRANDING_PROTECTION.json').read_text())
def strings(path):
    result={}
    for m in re.finditer(r'fun (\w+)\(\) = decode\((\d+), intArrayOf\(([^)]*)\)\)',path.read_text()):
        result[m[1]]=bytes(int(v)^((int(m[2])+i*73)&255) for i,v in enumerate(m[3].split(','))).decode()
    return result
values=strings(JAVA/'MobileTinaAboutContent.kt')
welcome=strings(JAVA/'MobileTinaWelcomeContent.kt')['welcome']
h=hashlib.sha256()
for value in ['فیلترشکن','فیلترشکن']+[values[n] for n in c['protected_text_methods']]+[welcome]:h.update(value.encode());h.update(b'\0')
for asset in c['assets']:
    actual=hashlib.sha256((RES/'raw'/asset['file']).read_bytes()).digest()
    assert actual.hex()==asset['ciphertext_sha256'],asset['file']
    h.update(actual)
assert h.hexdigest()==c['digest_sha256']
native=APP/'src/main/cpp'
assert (native/'branding-digest.txt').read_text().strip()==h.hexdigest()
assert bytes(int(x,16) for x in re.findall(r'0x([0-9a-f]{2})',(native/'branding_digest.h').read_text()))==h.digest()
numbers=re.search(r'expectedDigest = byteArrayOf\(([^)]*)\)',(JAVA/'MobileTinaIntegrity.kt').read_text())[1]
assert bytes(int(x.strip())&255 for x in numbers.split(','))==h.digest()
hashes=re.findall(r'\b(?:WHITE|YELLOW|BLUE|RED)\(R.raw.\w+, "([a-f0-9]{64})"\)',(JAVA/'MobileTinaAssets.kt').read_text())
material=hashlib.sha512(('|'.join(x[::-1] for x in hashes)+'|mt/assets/v2').encode()).digest()
for item,expected in zip(c['assets'],hashes+[c['header_plaintext_sha256']],strict=True):
    plain=subprocess.run(['openssl','enc','-d','-aes-256-ctr','-K',material[:32].hex(),'-iv',material[32:48].hex()],input=(RES/'raw'/item['file']).read_bytes(),capture_output=True,check=True).stdout
    assert hashlib.sha256(plain).hexdigest()==expected,item['file']
for p in (APP/'src/main').rglob('*'):
    if p.suffix in {'.kt','.java','.xml'}:
        assert not any(x in p.read_text() for x in ['mobile.tina','instagram://','www.instagram.com']),p
rules=(APP/'proguard-rules.pro').read_text()
assert '-dontobfuscate' not in rules and '-keep class io.nekohasekai.sagernet.**' not in rules
print('Branding contract verified: encoded identity, five original artwork hashes, native/JVM digest and R8 policy.')
