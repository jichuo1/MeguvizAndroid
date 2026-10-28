"""Rebuild two test-only resources APKs; no external dependencies or app installation."""
from pathlib import Path
import hashlib
import subprocess

here = Path(__file__).resolve().parent
sdk = Path.home() / 'AppData/Local/Android/Sdk'
aapt = sdk / 'build-tools/37.0.0/aapt2.exe'
android_jar = sdk / 'platforms/android-37.0/android.jar'
assets = here.parent / 'src/androidTest/assets'
scratch = here.parent / 'build/loader-fixtures'
assets.mkdir(parents=True, exist_ok=True)
scratch.mkdir(parents=True, exist_ok=True)
for name in ('one', 'two'):
    compiled = scratch / (name + '.zip')
    output = assets / ('fixture-' + name + '.apk')
    subprocess.run([str(aapt), 'compile', '--dir', str(here/name/'res'), '-o', str(compiled)], check=True)
    subprocess.run([str(aapt), 'link', '-I', str(android_jar), '--manifest', str(here/'AndroidManifest.xml'),
                    '--package-id', '0x80', '--allow-reserved-package-id', '-o', str(output), str(compiled)], check=True)
    print(name, output.stat().st_size, hashlib.sha256(output.read_bytes()).hexdigest())
