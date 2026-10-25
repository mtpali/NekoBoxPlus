#!/usr/bin/env python3
"""Check real Release DEX, native libraries and R8 mapping rather than just Gradle switches."""
import hashlib,json,re,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
def verify(apk,abis):
    c=json.loads((ROOT/'BRANDING_PROTECTION.json').read_text())
    digest=bytes.fromhex(c['digest_sha256'])
    signer=bytes.fromhex((ROOT/'RELEASE_CERT_SHA256').read_text().strip())
    mask=bytes([109,19,167,76,242,137,53,222,81,184,15,195,122,230,36,149,209,72,187,2,111,172,115,224,25,132,215,62,161,91,201,38])
    token=bytes(a^b for a,b in zip(signer,digest,strict=True)).hex().encode()
    native_token=bytes(a^b^d for a,b,d in zip(signer,digest,mask,strict=True))
    with zipfile.ZipFile(apk) as z:
        dex=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
        assert token in dex,'Masked signer pin missing'
        assert signer.hex().encode() not in dex,'Unmasked pin remains in DEX'
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex',name) or name=='resources.arsc':
                raw=z.read(name)
                for text in ['mobile.tina','www.instagram.com','instagram://']:
                    assert text.encode() not in raw and text.encode('utf-16le') not in raw,('Plaintext branding',name,text)
        for abi in abis:
            guard=z.read(f'lib/{abi}/libv.so')
            assert guard[:4]==b'\x7fELF' and guard[4]==(1 if abi=='armeabi-v7a' else 2)
            assert int.from_bytes(guard[18:20],'little')==(40 if abi=='armeabi-v7a' else 183)
            assert native_token in guard and digest in guard,'Native guard contract mismatch'
    mapping=(ROOT/'upstream/workspace/NekoBoxForAndroid/app/build/outputs/mapping/plusRelease/mapping.txt').read_text()
    for simple in ['MobileTinaAboutContent','MobileTinaWelcomeContent','MobileTinaIntegrity','MobileTinaAssets']:
        name='io.nekohasekai.sagernet.utils.'+simple
        match=re.search(r'^'+re.escape(name)+r' -> ([^:]+):$',mapping,re.M)
        assert match and match[1]!=name,'Protected class not renamed: '+name
    current='';count=0
    for line in mapping.splitlines():
        if line and not line[0].isspace() and not line.startswith('#') and ' -> ' in line:current=line.split(' -> ')[0]
        elif line.startswith('    ') and ' -> ' in line and '(' not in line:
            if current=='io.nekohasekai.sagernet.AndroidTunPayload' or current.startswith('moe.matsuri.nb4a.SingBoxOptions$'):
                left,right=line.strip().split(' -> ')
                assert left.split()[-1]==right,'Core JSON field renamed: '+current+'.'+left
                count+=1
    assert count>50,'Core JSON preservation evidence missing'
    return {'obfuscated':True,'branding_plaintext_absent':True,'native_guard_verified':True,'core_json_fields_preserved':count,
            'branding_digest':c['digest_sha256'],'mapping_sha256':hashlib.sha256(mapping.encode()).hexdigest()}
