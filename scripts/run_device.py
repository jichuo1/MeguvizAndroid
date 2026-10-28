"""Run the explicit 13-scenario matrix suite on one connected device and verify installed identity."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
from verification import ROOT, DEVICE_SCENARIOS, DEVICE_SCENARIO_TESTS, parse_instrumentation, sha, source_hash

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--target', type=int, choices=(29,37), required=True)
parser.add_argument('--adb', default='adb')
args = parser.parse_args()

def adb(*parts):
    return subprocess.run([args.adb,'-s',args.serial,*parts],capture_output=True,text=True,check=True).stdout.strip()

if adb('get-state') != 'device': raise SystemExit('Requested device is not available')
api = int(adb('shell','getprop','ro.build.version.sdk'))
if api not in (27,29,30,37): raise SystemExit('Device is outside required API matrix')
flavor = 'plain' if args.target == 37 else 'embedded'
package = f'io.github.jichuo1.meguviz.samples.{flavor}'
apk = ROOT/f'samples/{flavor}/build/outputs/apk/debug/{flavor}-debug.apk'
test_apk = ROOT/f'samples/{flavor}/build/outputs/apk/androidTest/debug/{flavor}-debug-androidTest.apk'
if not apk.is_file() or not test_apk.is_file(): raise SystemExit('Build current sample/test APK first')
before = source_hash()
host_path = ROOT/'verification/gates/host.json'
if not host_path.is_file(): raise SystemExit('Run verifyHost before device installation')
host = json.loads(host_path.read_text(encoding='utf-8'))
if host.get('status') != 'PASS' or host.get('source_tree_sha256') != before:
    raise SystemExit('Host evidence is not PASS for current inputs; refusing to relabel an old APK')
identity = next((c for c in host.get('checks',[]) if c.get('name') == f'{flavor}-device-artifacts'),None)
if (not identity or identity.get('apk_path') != apk.relative_to(ROOT).as_posix()
        or identity.get('test_apk_path') != test_apk.relative_to(ROOT).as_posix()
        or identity.get('apk_sha256') != sha(apk) or identity.get('test_apk_sha256') != sha(test_apk)):
    raise SystemExit('APK/test APK identity differs from current host-built evidence')
directory = ROOT/f'verification/device-results/api-{api}-target-{args.target}'
directory.mkdir(parents=True,exist_ok=True)
fingerprint = adb('shell','getprop','ro.build.fingerprint')
adb('install','-r',str(apk)); adb('install','-r',str(test_apk))
paths = adb('shell','pm','path',package).splitlines()
base = next((p[len('package:'):] for p in paths if p.endswith('/base.apk')),None)
if not base: raise SystemExit('Installed base.apk path missing')
installed = directory/'installed.apk'; adb('pull',base,str(installed))
if sha(installed) != sha(apk): raise SystemExit('Installed APK differs from current local candidate')
test_paths = adb('shell','pm','path',package+'.test').splitlines()
test_base = next((p[len('package:'):] for p in test_paths if p.endswith('/base.apk')),None)
if not test_base: raise SystemExit('Installed instrumentation APK missing')
installed_test = directory/'installed-test.apk'; adb('pull',test_base,str(installed_test))
if sha(installed_test) != sha(test_apk): raise SystemExit('Installed test APK differs from host-built candidate')
classes=','.join(sorted({name.split('#',1)[0] for name in DEVICE_SCENARIO_TESTS.values()}))
raw = adb('shell','am','instrument','-w','-r','-e','class',classes,f'{package}.test/androidx.test.runner.AndroidJUnitRunner')
log = directory/'instrumentation.log'; log.write_text(raw,encoding='utf-8')
parsed=parse_instrumentation(raw)
scenarios={s:('PASS' if method in parsed['successful'] else 'FAIL' if method in parsed['failures'] or method in parsed['errors']
              else 'SKIPPED' if method in parsed['skipped'] else 'NOT_RUN') for s,method in DEVICE_SCENARIO_TESTS.items()}
scenario_tests={s:[method] for s,method in DEVICE_SCENARIO_TESTS.items() if method in parsed['successful']}
passed=(parsed['finished_successfully'] and parsed['tests']==len(DEVICE_SCENARIOS)
        and len(parsed['successful'])==len(DEVICE_SCENARIOS) and all(value=='PASS' for value in scenarios.values())
        and not parsed['failures'] and not parsed['errors'] and not parsed['skipped'] and source_hash()==before)
status='PASS' if passed else 'FAIL' if parsed['failures'] or parsed['errors'] else 'BLOCKED'
result = dict(schema_version=1,source_tree_sha256=before,timestamp_unix=time.time(),
              serial=args.serial,api=api,target=args.target,fingerprint=fingerprint,
              tests=parsed['tests'],failures=len(parsed['failures']),errors=len(parsed['errors']),skipped=len(parsed['skipped']),
              raw_log=log.relative_to(ROOT).as_posix(),raw_sha256=sha(log),apk=apk.relative_to(ROOT).as_posix(),apk_sha256=sha(apk),
              test_apk=test_apk.relative_to(ROOT).as_posix(),test_apk_sha256=sha(test_apk),
              installed_apk_sha256=sha(installed),installed_test_apk_sha256=sha(installed_test),scenarios=scenarios,scenario_tests=scenario_tests,
              status=status,observations=parsed['diagnostics'],
              note='Thirteen required methods. Two-native-window isolation is tested, not OS split-screen/PiP; local config overrides do not alter global settings.')
receipt = ROOT/f'verification/device-results/api-{api}-target-{args.target}.json'
receipt.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2))
raise SystemExit(0 if passed else 1 if status=='FAIL' else 2)
