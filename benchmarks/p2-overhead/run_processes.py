"""Run exactly six independent paired probes on a named task-owned emulator."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import verification as v

PACKAGE='io.github.jichuo1.meguviz.p2overhead'
ADB=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True);parser.add_argument('--avd',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial): raise SystemExit('Only a named emulator is supported')
    output=args.output.resolve();allowed=(HERE/'results').resolve()
    if not output.is_relative_to(allowed) or output==allowed or output.exists():
        raise SystemExit('Output must be a new directory inside this probe results folder')
    subprocess.run([sys.executable,str(HERE/'prepare.py'),'check'],check=True)
    build=v.read_json(HERE/'build/probe-build-receipt.json')
    identity=build['identity'];app=Path(build['app_apk']['path']);test=Path(build['test_apk']['path'])
    if v.sha(app)!=build['app_apk']['sha256'] or v.sha(test)!=build['test_apk']['sha256']:
        raise SystemExit('Probe APK differs from build receipt')
    def adb(*parts,required=True,binary=False):
        result=subprocess.run([str(ADB),'-s',args.serial,*map(str,parts)],capture_output=True,timeout=150)
        if required and result.returncode: raise RuntimeError(result.stderr.decode('utf-8','replace'))
        return result.stdout if binary else result.stdout.decode('utf-8','replace').strip()
    if adb('get-state')!='device' or args.avd not in adb('emu','avd','name').splitlines():
        raise SystemExit('Emulator identity mismatch')
    if adb('shell','getprop','sys.boot_completed')!='1': raise SystemExit('Emulator not booted')
    if int(adb('shell','getprop','ro.build.version.sdk'))<33: raise SystemExit('Actual enabled-vsync comparison requires API33+')
    def installed(package):
        data=adb('shell','pm','path',package,required=False).splitlines()
        if not data:return None
        if len(data)!=1 or not data[0].startswith('package:'): raise RuntimeError('Unexpected installed APK path')
        return hashlib.sha256(adb('exec-out','cat',data[0][8:],binary=True)).hexdigest()
    old=(installed(PACKAGE),installed(PACKAGE+'.test'))
    expected=(v.sha(app),v.sha(test))
    if old==(None,None):
        if 'Success' not in adb('install',app) or 'Success' not in adb('install',test):
            raise RuntimeError('APK installation failed')
    elif old!=expected: raise SystemExit('Existing emulator candidate differs; do not overwrite')
    if (installed(PACKAGE),installed(PACKAGE+'.test'))!=expected: raise SystemExit('Installed APK hash mismatch')
    output.mkdir(parents=True)
    execution=dict(status='RUNNING',serial=args.serial,avd=args.avd,api=int(adb('shell','getprop','ro.build.version.sdk')),
        fingerprint=adb('shell','getprop','ro.build.fingerprint'),app_sha256=expected[0],test_sha256=expected[1],
        identity=identity,runs=[],scope='Task-owned emulator only; not physical-device performance acceptance')
    try:
        for index in range(6):
            directory=output/f'run-{index}';directory.mkdir()
            adb('shell','am','force-stop',PACKAGE)
            for service in ('battery','thermalservice'):
                (directory/(service+'-before.txt')).write_text(adb('shell','dumpsys',service,required=False),encoding='utf-8')
            run_id=str(uuid.uuid4());started=time.time()
            log=adb('shell','am','instrument','-w','-r','-e','class',PACKAGE+'.P2OverheadTest#pairedBudgetAndRetirement',
                    '-e','process_index',str(index),'-e','process_run_id',run_id,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner')
            (directory/'instrumentation.log').write_text(log,encoding='utf-8')
            parsed=v.parse_instrumentation(log)
            if (not parsed['finished_successfully'] or parsed['tests']!=1 or len(parsed['successful'])!=1
                    or parsed['failures'] or parsed['errors'] or parsed['skipped']):
                raise RuntimeError('Instrumentation run incomplete; raw log retained')
            values={}
            for line in log.splitlines():
                if line.startswith('INSTRUMENTATION_STATUS: p2_result_') or line.startswith('INSTRUMENTATION_STATUS: p2_run_id='):
                    key,value=line[len('INSTRUMENTATION_STATUS: '):].split('=',1);values[key]=value
            if values.get('p2_run_id')!=run_id: raise RuntimeError('Run ID not bound to raw output')
            remote=values.get('p2_result_path','')
            if not remote.startswith('/storage/emulated/0/Android/data/'+PACKAGE+'/files/p2-overhead-results/'):
                raise RuntimeError('Unexpected remote output path')
            data=adb('exec-out','cat',remote,binary=True)
            if hashlib.sha256(data).hexdigest()!=values.get('p2_result_sha256'): raise RuntimeError('Result SHA mismatch')
            path=directory/'raw.json';path.write_bytes(data);raw=json.loads(data)
            if (raw['process_index']!=index or raw['process_run_id']!=run_id or raw['identity']!=identity
                    or raw['app_apk_sha256']!=expected[0] or raw['test_apk_sha256']!=expected[1]):
                raise RuntimeError('Runtime identity mismatch')
            subprocess.run([sys.executable,str(HERE/'prepare.py'),'check'],check=True,capture_output=True)
            for service in ('battery','thermalservice'):
                (directory/(service+'-after.txt')).write_text(adb('shell','dumpsys',service,required=False),encoding='utf-8')
            execution['runs'].append(dict(index=index,pid=raw['process_pid'],run_id=run_id,started_unix=started,
                                          finished_unix=time.time(),raw_sha256=digest(path)))
            print('Process',index,'completed 20 paired blocks, pid',raw['process_pid'],flush=True)
        if len({r['pid'] for r in execution['runs']})!=6: raise RuntimeError('Processes are not independent')
        execution['status']='PASS'
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
        execution['status']='FAIL';execution['reason']=str(error)
    finally:
        adb('shell','am','force-stop',PACKAGE,required=False)
        (output/'execution.json').write_text(json.dumps(execution,indent=2)+'\n',encoding='utf-8')
    if execution['status']!='PASS': raise SystemExit(1)

if __name__=='__main__':main()
