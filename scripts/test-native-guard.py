#!/usr/bin/env python3
"""Known-vector C++ checks; no Android emulator or device is used."""
import hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
CPP=ROOT/'overlay/workspace/NekoBoxForAndroid/app/src/main/cpp'
signer=bytes.fromhex((ROOT/'RELEASE_CERT_SHA256').read_text().strip())
text=bytes.fromhex(json.loads((ROOT/'BRANDING_PROTECTION.json').read_text())['digest_sha256'])
mask=bytes([109,19,167,76,242,137,53,222,81,184,15,195,122,230,36,149,209,72,187,2,111,172,115,224,25,132,215,62,161,91,201,38])
token=bytes(a^b^c for a,b,c in zip(signer,text,mask,strict=True))
u64=(1<<64)-1;state=42^0x9e3779b97f4a7c15
for data,shift,right in [(signer,11,29),(text,7,31)]:
    for byte in data:
        state^=byte;state=(((state<<shift)|(state>>(64-shift)))*1099511628211)&u64;state^=state>>right
expected=state^0xd6e8feb86659fd93
java_bin=shutil.which('javac') or shutil.which('java')
java_home=Path(os.environ.get('JAVA_HOME') or Path(java_bin).resolve().parent.parent)
if not (java_home/'include/jni.h').is_file():raise SystemExit('A full JDK with JNI headers is required.')
def literal(data):return ','.join(str(x) for x in data)
with tempfile.TemporaryDirectory(prefix='vpn-guard-') as temp:
    work=Path(temp)
    (work/'native_token.h').write_text('static const volatile uint8_t kToken[32]={'+literal(token)+'};\n')
    code='#include <assert.h>\n#include "'+str(CPP/'mobiletina_guard.cpp')+'"\n'
    code+='int main(){uint8_t actual[32];const uint8_t signer[32]={'+literal(signer)+'};\n'
    code+='expected_signer(actual);assert(equal(actual,signer,32));\n'
    code+='sha256(reinterpret_cast<const uint8_t*>("abc"),3,actual);const uint8_t abc[32]={'+literal(hashlib.sha256(b'abc').digest())+'};assert(equal(actual,abc,32));\n'
    code+='sha256(nullptr,0,actual);const uint8_t empty[32]={'+literal(hashlib.sha256(b'').digest())+'};assert(equal(actual,empty,32));\n'
    code+='assert(proof(signer,kTextDigest,42)==UINT64_C('+str(expected)+'));actual[0]^=1;assert(!equal(actual,empty,32));}\n'
    (work/'test.cpp').write_text(code)
    subprocess.run([shutil.which('c++'),'-std=c++17','-O2','-I'+str(work),'-I'+str(java_home/'include'),'-I'+str(java_home/'include/linux'),str(work/'test.cpp'),'-o',str(work/'test')],check=True)
    subprocess.run([str(work/'test')],check=True)
print('5 native checks passed: signer token, SHA-256 abc/empty, nonce proof and changed digest rejection.')
