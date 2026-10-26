#!/usr/bin/env python3
from pathlib import Path
import hashlib,sys
root=Path(__file__).resolve().parent.parent
digest=hashlib.sha256((root/'UPSTREAM_COMMIT').read_bytes())
for path in [root/'patches/native.patch']+sorted(p for p in (root/'native-overlay').rglob('*') if p.is_file()):
    digest.update(str(path.relative_to(root)).encode()+b'\0'+path.read_bytes()+b'\0')
value=digest.hexdigest()
print(value if '--hash' in sys.argv else 'native-'+(root/'UPSTREAM_COMMIT').read_text().strip()[:8]+'-'+value[:12]+'-arm')
