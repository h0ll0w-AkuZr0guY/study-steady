# 构建

依赖 Python 3.10+、JDK 21、Android SDK platform 36 / build-tools 36.0.0。Gradle 8.14.3 / Android Gradle Plugin 8.13.2 由 wrapper 与脚本固定。首次依赖下载需要网络。

仓库保留应用 Java/C++ 源码、资源定义及运行资源散列清单；`prepare_build.py` 首次从官方 Winlator 11.2 Release 下载 APK，验证 SHA-256，然后提取 ARM64 原生库，同时从固定源码提交获取并逐一校验运行资源。它不获取任何商业游戏。固定身份见第三方声明。

```powershell
$env:JAVA_HOME = '<JDK 21 路径>'
$env:ANDROID_SDK_ROOT = '<Android SDK 路径>'
python tools/run_archive_tests.py
python -m unittest discover -s tests -p 'test_*.py' -v
./build.ps1
```

输出 debug APK 到 dist/StudySteadyMobile.apk。正式构建：

```powershell
./build-release.ps1 -Unsigned
```

签名时设置 STUDY_KEYSTORE、STUDY_KEY_ALIAS、STUDY_STORE_PASS、STUDY_KEY_PASS 四个环境变量，再执行不带 -Unsigned 的脚本。私钥放在仓库外，口令不写入源码。脚本验证签名并产生 SHA256SUMS.txt。

## Unicode 启动助手

原组件使用 ANSI 启动接口，本项目的 `native/study_launch.c` 使用 ShellExecuteExW。Java 将工作目录和 EXE 路径以 UTF-16LE 写入私有交接文件，助手直接读取，不执行 shell 脚本。

已附带编译的 study-launch.exe；用 MinGW-w64 重新构建：

```text
python tools/build_native.py
```

CI 安装 x86_64-w64-mingw32-gcc，重新编译该助手再构建 Android APK。上游 ARM64 原生运行库由固定官方 APK 提取，没有在本项目中重新编译；对应来源和构建材料限制见第三方声明。

Linux 构建：设置 ANDROID_SDK_ROOT，运行 prepare_build.py，然后在 upstream/winlator-app-main 执行 `chmod +x gradlew` 和 `./gradlew :app:assembleRelease --console=plain`。CI 产物是未签名 APK，不能替代发布者签名的 Release。
