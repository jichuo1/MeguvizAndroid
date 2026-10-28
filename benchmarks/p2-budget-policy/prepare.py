"""Freeze the current host-verified runtime for the P2 budget policy probe."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import verification as v


def source_digest():
    digest=hashlib.sha256()
    ignored={'build','.gradle','__pycache__','inputs','results'}
    paths=[]
    for path in HERE.rglob('*'):
        if path.is_file() and not any(p in ignored for p in path.relative_to(HERE).parts):
            if path.relative_to(HERE).as_posix()=='gradle/verification-metadata.xml': continue
            paths.append(path)
    for path in sorted(paths):
        digest.update(path.relative_to(HERE).as_posix().encode());digest.update(b'\0');digest.update(path.read_bytes())
    return digest.hexdigest()


def current():
    host=ROOT/'verification/gates/host.json';data=v.read_json(host)
    source=v.source_hash()
    if data.get('status')!='PASS' or data.get('source_tree_sha256')!=source:
        raise ValueError('Current host PASS required')
    record=next(c for c in data['checks'] if c['name']=='local-publication')
    path=v.artifact_path('android-runtime','aar')
    expected=next(x for x in record['artifacts'] if x['path']==path.relative_to(ROOT).as_posix())
    if v.sha(path)!=expected['sha256']: raise ValueError('Current runtime differs from host publication')
    return data,host,source,path


def prepare():
    inputs=HERE/'inputs';metadata=HERE/'gradle/verification-metadata.xml'
    if inputs.exists() or metadata.exists(): raise ValueError('Existing frozen probe inputs must be preserved')
    host,host_path,source,aar=current()
    inputs.mkdir();metadata.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(aar,inputs/'android-runtime.aar')
    shutil.copy2(ROOT/'gradle/verification-metadata.xml',metadata)
    record=dict(schema_version=1,status='FROZEN_UNMEASURED',source_tree_sha256=source,
        host_run_id=host['run_id'],host_receipt_sha256=v.sha(host_path),
        runtime_aar_sha256=v.sha(aar),runtime_original=str(aar.relative_to(ROOT)),
        verification_metadata_sha256=v.sha(metadata),probe_source_sha256=source_digest(),
        root_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        root_commit_scope='HEAD at preparation; source fingerprint also includes uncommitted files',
        scope='Task probe, single runtime AAR in test APK; no physical performance acceptance')
    (inputs/'manifest.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(record,indent=2))


def check():
    inputs=HERE/'inputs';manifest=v.read_json(inputs/'manifest.json')
    host,host_path,source,aar=current()
    if (manifest['source_tree_sha256']!=source or manifest['host_run_id']!=host['run_id']
        or manifest['host_receipt_sha256']!=v.sha(host_path) or manifest['runtime_aar_sha256']!=v.sha(aar)
        or manifest['runtime_aar_sha256']!=v.sha(inputs/'android-runtime.aar')
        or manifest['verification_metadata_sha256']!=v.sha(HERE/'gradle/verification-metadata.xml')
        or manifest['probe_source_sha256']!=source_digest()):
        raise ValueError('Frozen input identity differs from current source, host, AAR or probe')
    print('Current host, source, runtime, metadata and probe identity verified')


def rebind():
    """Preserve the prior unmeasured identity before a source-only probe repair."""
    inputs=HERE/'inputs';path=inputs/'manifest.json';old=v.read_json(path)
    host,host_path,source,aar=current()
    if (old['source_tree_sha256']!=source or old['host_run_id']!=host['run_id']
        or old['host_receipt_sha256']!=v.sha(host_path) or old['runtime_aar_sha256']!=v.sha(aar)
        or old['runtime_aar_sha256']!=v.sha(inputs/'android-runtime.aar')
        or old['verification_metadata_sha256']!=v.sha(HERE/'gradle/verification-metadata.xml')):
        raise ValueError('Source-only repair cannot replace changed runtime inputs')
    archive=ROOT/'verification/p2/budget-policy-frozen-before-repair.json'
    if archive.exists(): raise ValueError('Previous freeze archive already exists')
    shutil.copy2(path,archive)
    old['probe_source_sha256']=source_digest()
    old['scope']='Rebound after preregistered probe-only configuration update; original freeze archived, no samples taken'
    path.write_text(json.dumps(old,indent=2)+'\n',encoding='utf-8')
    check()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('prepare','check','rebind'))
    args=parser.parse_args();globals()[args.action]()
