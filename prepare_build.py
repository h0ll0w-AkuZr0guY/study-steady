"""Prepare a reproducible local Winlator fork using upstream native binaries."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import zipfile
import urllib.request

ROOT = Path(__file__).resolve().parent
APP = ROOT / 'upstream/winlator-app-main'
APK = ROOT / 'downloads/Winlator_11.2.apk'
EXPECTED = 'ea5c81bf0e5b90a6b9ccb8007121ab6738c8646a5a60b341ce0ac2b33f1d100a'
if not APK.is_file():
    APK.parent.mkdir(parents=True, exist_ok=True)
    temporary = APK.with_suffix('.download')
    url = 'https://github.com/brunodev85/winlator/releases/download/v11.2.0/Winlator_11.2.apk'
    print('Downloading pinned upstream native runtime...', flush=True)
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != EXPECTED:
            raise SystemExit('Downloaded upstream APK checksum differs')
        temporary.replace(APK)
    finally:
        temporary.unlink(missing_ok=True)
if hashlib.sha256(APK.read_bytes()).hexdigest() != EXPECTED:
    raise SystemExit('Upstream APK checksum differs from recorded download')
SOURCE = ROOT / 'downloads/winlator-app.zip'
if SOURCE.exists() and hashlib.sha256(SOURCE.read_bytes()).hexdigest() != '2732d7ccf7ecc9235621c86d2a572ded6b2ae2456577bc15faece8112a443be2':
    raise SystemExit('Upstream source ZIP checksum differs from recorded download')
asset_manifest = json.loads((ROOT / 'runtime-assets.json').read_text(encoding='utf-8'))
missing_assets = [record for record in asset_manifest['files'] if not (APP / record['path']).is_file()]
if missing_assets:
    resource_zip = SOURCE if SOURCE.exists() else ROOT / 'downloads/runtime-source.zip'
    if not resource_zip.exists():
        url = 'https://codeload.github.com/brunodev85/winlator-app/zip/' + asset_manifest['sourceCommit']
        temporary = resource_zip.with_suffix('.download')
        print('Downloading pinned upstream resource archive...', flush=True)
        try:
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output)
            temporary.replace(resource_zip)
        finally:
            temporary.unlink(missing_ok=True)
    with zipfile.ZipFile(resource_zip) as resources:
        prefix = resources.namelist()[0].split('/')[0] + '/'
        for record in missing_assets:
            data = resources.read(prefix + record['path'])
            if len(data) != record['size'] or hashlib.sha256(data).hexdigest() != record['sha256']:
                raise SystemExit('Pinned resource differs: ' + record['path'])
            target = APP / record['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
with zipfile.ZipFile(APK) as archive:
    for info in archive.infolist():
        if info.filename.startswith('lib/arm64-v8a/') and info.filename.endswith('.so'):
            if 'libzstd-jni' in info.filename:
                continue  # Supplied by the matching AAR dependency.
            target = APP / 'app/src/main/jniLibs/arm64-v8a' / Path(info.filename).name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))

(APP / 'app/build.gradle').write_text('''apply plugin: 'com.android.application'
android {
    namespace 'com.winlator'
    compileSdk 36
    buildFeatures { buildConfig true }
    defaultConfig {
        applicationId 'com.winlator'
        minSdkVersion 26
        targetSdkVersion 28
        versionCode 102
        versionName '0.2.0'
        ndk { abiFilters 'arm64-v8a' }
    }
    buildTypes { debug { minifyEnabled false } }
    packagingOptions { jniLibs { useLegacyPackaging true } }
    lint { checkReleaseBuilds false }
}
dependencies {
    implementation 'androidx.appcompat:appcompat:1.4.0'
    implementation 'androidx.preference:preference:1.2.1'
    implementation 'com.google.android.material:material:1.4.0'
    implementation 'com.github.luben:zstd-jni:1.5.2-3@aar'
    implementation 'org.tukaani:xz:1.7'
    implementation 'org.apache.commons:commons-compress:1.20'
    implementation 'androidx.lifecycle:lifecycle-process:2.5.1'
}
''', encoding='utf-8')
top = APP / 'build.gradle'
top.write_text('''buildscript {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        mavenCentral()
        google()
    }
    dependencies { classpath 'com.android.tools.build:gradle:8.13.2' }
}
allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        mavenCentral()
        google()
    }
}
''', encoding='utf-8')
(APP / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx3072m\nandroid.useAndroidX=true\nandroid.nonFinalResIds=false\nandroid.nonTransitiveRClass=false\n', encoding='utf-8')
sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
if not sdk or not Path(sdk, 'platforms/android-36/android.jar').is_file():
    raise SystemExit('Set ANDROID_SDK_ROOT to an SDK with Android 36 installed')
(APP / 'local.properties').write_text('sdk.dir='+Path(sdk).as_posix()+'\n', encoding='utf-8')
(APP / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n', encoding='utf-8')

manifest = APP / 'app/src/main/AndroidManifest.xml'
content = (ROOT / 'upstream/AndroidManifest.upstream.xml').read_text(encoding='utf-8')
content = content.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="${applicationId}.FileProvider"')
content = content.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_study"')
content = content.replace('android:label="@string/app_name"', 'android:label="Study Steady" android:allowBackup="false"')
intent_filter = '''            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>'''
content = content.replace(intent_filter, '')
if 'com.winlator.StudySteadyActivity' not in content:
    content = content.replace('        <activity android:name="com.winlator.MainActivity"', '''        <activity android:name="com.winlator.StudySteadyActivity"
            android:theme="@style/AppThemeDark" android:exported="true"
            android:launchMode="singleTop" android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <activity android:name="com.winlator.MainActivity"''')
manifest.write_text(content, encoding='utf-8')
util = APP / 'app/src/main/java/com/winlator/core/AppUtils.java'
util.write_text(util.read_text(encoding='utf-8').replace('/data/data/com.avgamer.study/storage', '/data/data/com.winlator/storage'), encoding='utf-8')
util = APP / 'app/src/main/java/com/winlator/core/FileUtils.java'
util.write_text(util.read_text(encoding='utf-8').replace('"com.winlator.FileProvider"', 'activity.getPackageName()+".FileProvider"'), encoding='utf-8')

profile = {'id': 99, 'name': 'Study Steady', 'cursorSpeed': 1, 'elements': []}
for text, binding, x, y, toggle in [
    ('继续', 'MOUSE_LEFT_BUTTON', .94, .88, False),
    ('菜单', 'MOUSE_RIGHT_BUTTON', .06, .88, False),
    ('隐藏', 'KEY_SPACE', .06, .72, False),
    ('快进', 'KEY_CTRL_L', .94, .72, True),
    ('返回', 'KEY_ESC', .06, .12, False),
]:
    profile['elements'].append({'type':'BUTTON', 'shape':'ROUND_RECT', 'bindings':[binding,'NONE','NONE','NONE'], 'scale': .9, 'x':x,'y':y,'toggleSwitch':toggle,'text':text,'iconId':0})
(APP / 'app/src/main/assets/inputcontrols/profiles/controls-99.icp').write_text(json.dumps(profile, ensure_ascii=False), encoding='utf-8')
print('Prepared Android project:', APP)
