#!/usr/bin/env python3
from pathlib import Path
import shutil,subprocess
root=Path(__file__).resolve().parent.parent
upstream=root/'upstream'
assert subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()==(root/'UPSTREAM_COMMIT').read_text().strip()
patch=root/'patches/native.patch'
if subprocess.run(['git','-C',str(upstream),'apply','--check',str(patch)],capture_output=True).returncode==0:
    subprocess.run(['git','-C',str(upstream),'apply',str(patch)],check=True)
else:
    subprocess.run(['git','-C',str(upstream),'apply','--reverse','--check',str(patch)],check=True)
for source in (root/'native-overlay').rglob('*'):
    if source.is_file():
        destination=upstream/source.relative_to(root/'native-overlay')
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,destination)
print('Prepared native per-app counters over the pinned core')
