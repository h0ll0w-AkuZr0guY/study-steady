"""Small ADB UI regression helper; operates only the selected connected device."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser()
p.add_argument('--adb',default=os.environ.get('ADB') or shutil.which('adb'))
p.add_argument('--serial')
p.add_argument('action',choices=['tap','labels','capture'])
p.add_argument('value',nargs='?')
a=p.parse_args()
if not a.adb: raise SystemExit('Set ADB or pass --adb')
serial=a.serial
if not serial:
    lines=subprocess.check_output([a.adb,'devices'],text=True).splitlines()
    devices=[s.split()[0] for s in lines if s.endswith('\tdevice')]
    if len(devices)!=1: raise SystemExit('Select a device with --serial')
    serial=devices[0]
base=[a.adb,'-s',serial]
def adb(*args): return subprocess.check_output(base+list(args),text=True,encoding='utf-8')
if a.action=='capture':
    if not a.value: raise SystemExit('Specify output PNG')
    adb('shell','screencap','-p','/sdcard/StudySteady-qa.png')
    subprocess.run(base+['pull','/sdcard/StudySteady-qa.png',str(Path(a.value))],check=True)
else:
    result=adb('shell','uiautomator','dump','/sdcard/StudySteady-qa.xml')
    if 'dumped to:' not in result: raise SystemExit('UI dump did not complete; refusing to use a stale hierarchy')
    tree=ET.fromstring(adb('shell','cat','/sdcard/StudySteady-qa.xml'))
    nodes=[n for n in tree.iter('node') if n.get('text')]
    if a.action=='labels':
        for n in nodes: print(n.get('text'),n.get('bounds'))
    else:
        matches=[n for n in nodes if n.get('text')==a.value and n.get('enabled')=='true']
        if len(matches)!=1: raise SystemExit('Expected one matching enabled label, found '+str(len(matches)))
        x1,y1,x2,y2=map(int,re.findall(r'\d+',matches[0].get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

