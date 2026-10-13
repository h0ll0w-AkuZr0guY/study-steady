> 本文件保留原型期的引擎研究。当前应用行为与真机验证状态以 VALIDATION.md 和 CHANGELOG.md 为准。

# Study Steady Android 路线核查

核查日期：2026-10-13。当前实现通过 Winlator/Wine 运行原版 Windows 程序。构建成功只证明 APK 可以生成；目标设备上的启动、文字、声音、E-mote 演出、完整剧情及游戏内存档读写需要分别实测。

## 本地证据

原版目录包含 `ststeady.exe`、`ststeady_patch.exe`、`emotedriver.dll` 及 NOA 资源。只读检查 `ststeady.exe`发现 EntisGLS4、Sakura2VM、ECSSakura2::EnvironmentVM、ECSEmoteDevice 与 ECSEmoteSprite 标记；`emotedriver.dll`依赖 Direct3D 9 相关接口。

原有 `启动游戏.bat`调用 `EntisGLS_DVD_PATCH.exe ststeady_patch.exe`。本启动器直接执行游戏 EXE，其启动路径与该批处理不同，不能根据静态分析认定两者的启动结果相同。

## 原生 Android 移植

[EntisGLS 官方说明](https://www.entis.jp/gls/about.html)明确支持 Windows 和 Android，以及通过工具生成 APK。[官方下载页](https://www.entis.jp/gls/download.html)提供源码及工具。[4.07.03 SDK](https://www.entis.jp/gls/bin/EntisGLS4.07.03.zip)包含 ARM64/ARMv7/x86/x86-64 Android 库、Java 界面代码和 JNI 模板。研究仅通过 HTTP Range 阅读了 ZIP 目录、许可、文档及相关源码，没有为研究下载该 SDK 的运行库。

SDK 的 Android Sakura2VM 程序打开 `assets://cotopha.xml`，随后加载环境、主模块并运行。包目录中没有 E-mote 驱动，通用 VM 也不包含 Study Steady 的 ECSEmoteDevice/ECSEmoteSprite 游戏接口。源码是否完整覆盖该游戏的所有调用与存档对象，需要额外验证。

[M2 的 Android 示例 SDK](https://emote.mtwo.co.jp/download/sdk/)只能显示自带示例角色，不能用于任意游戏角色。正式 Android SDK 的方案见 [M2 产品页](https://emote.mtwo.co.jp/products/)，ARM64 支持见 [M2 公告](https://emote.mtwo.co.jp/2018/08/27/e-mote3-9-5/)。这些事实说明原生 Android 版本技术上存在，但当前目录没有可直接替代本游戏 Windows 驱动的 Android E-mote 实现。

## 公共工具的边界

[arc_unpacker](https://github.com/vn-tools/arc_unpacker)包含 [NOA 解码代码](https://github.com/vn-tools/arc_unpacker/blob/master/src/dec/entis/noa_archive_decoder.cc)和 ERI/MIO 解码器；[EntisGLS_Tools](https://github.com/crskycode/EntisGLS_Tools)用于 CSX 字符串提取、编辑与回填；[FreeMote](https://github.com/UlyssesWu/FreeMote)用于 PSB 解包、转换及查看。这些工具的资源处理能力不能证明其能够完整执行 Study Steady 的脚本、动画和存档。

[原版 Winlator](https://github.com/brunodev85/winlator)明确以 Wine、Box86/Box64 执行 Windows 程序。当前采用的 [11.2 发布](https://github.com/brunodev85/winlator/releases/tag/v11.2.0)适合验证原程序兼容性；研究未找到可靠的一手资料证明 Study Steady 在该版本和用户设备上已经完整兼容。
