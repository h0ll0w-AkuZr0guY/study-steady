"""Build the small Unicode Windows launcher with a MinGW-w64 cross compiler."""
from pathlib import Path
import os
import shutil
import subprocess
root = Path(__file__).resolve().parents[1]
compiler = os.environ.get('STUDY_MINGW_CC') or shutil.which('x86_64-w64-mingw32-gcc')
if not compiler:
    raise SystemExit('Install MinGW-w64 or set STUDY_MINGW_CC')
output = root / 'upstream/winlator-app-main/app/src/main/assets/study/study-launch.exe'
output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([compiler, '-municode', '-mwindows', '-O2', '-s', '-Wl,--no-insert-timestamp',
                str(root / 'native/study_launch.c'), '-o', str(output), '-lshell32'], check=True)

