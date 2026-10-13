# 第三方组件与来源记录

本项目是面向用户自备 PC Galgame 的 Study Steady Android 启动器，基于 Winlator 修改，属于非官方构建。记录日期：2026-10-14。

## 核心组件

| 组件 | 上游与许可 | 在本项目中的用途 |
| --- | --- | --- |
| Winlator / Winlator app，Bruno 及项目贡献者 | [主仓库](https://github.com/brunodev85/winlator)、[应用源码](https://github.com/brunodev85/winlator-app)、[LGPL-2.1 许可](https://github.com/brunodev85/winlator-app/blob/3981d86efa4f333b2a34a7da8b6521476cd8c8b9/LICENSE) | Android 界面、容器管理、输入与显示、本地运行环境 |
| Wine，Wine project authors | [项目](https://www.winehq.org/)、[源码](https://gitlab.winehq.org/wine/wine)、[LGPL-2.1-or-later 声明](https://github.com/wine-mirror/wine/blob/master/LICENSE)、[完整许可](https://github.com/wine-mirror/wine/blob/master/COPYING.LIB) | 在 Android 容器内执行原版 Windows 游戏与 DLL |
| Box64，ptitSeb 及贡献者 | [源码](https://github.com/ptitSeb/box64)、[MIT 许可](https://github.com/ptitSeb/box64/blob/main/LICENSE) | 在 ARM64 设备上执行 x86-64 代码；Wine WoW64 运行 32 位游戏 |
| Mesa，Mesa 项目贡献者 | [项目](https://www.mesa3d.org/)、[许可证说明](https://docs.mesa3d.org/license.html) | Turnip、Zink 等图形组件；以组件自身的许可及版权说明为准 |
| DXVK，DXVK 项目贡献者 | [源码与许可](https://github.com/doitsujin/dxvk) | Direct3D 到 Vulkan 的转换 |

Winlator 的完整 LGPL-2.1 许可保存在 `upstream/winlator-app-main/LICENSE`。上游包含的其他组件保留自己的许可证，Winlator 的 LGPL 不会把它们统一改成 LGPL。原生库还包括 GLib、PulseAudio、FluidSynth、libsndfile、libinstpatch、Ogg/Vorbis/Opus/FLAC、PCRE、LLVM 运行库、Oboe、virglrenderer 等；组件身份与版本应以实际使用的上游构建记录为准。

## 本次构建来源

| 文件 | 来源与固定身份 |
| --- | --- |
| `downloads/Winlator_11.2.apk` | [官方 11.2 发布页](https://github.com/brunodev85/winlator/releases/tag/v11.2.0)；SHA-256：`ea5c81bf0e5b90a6b9ccb8007121ab6738c8646a5a60b341ce0ac2b33f1d100a` |
| `downloads/winlator-app.zip` | [Winlator app 固定提交](https://github.com/brunodev85/winlator-app/tree/3981d86efa4f333b2a34a7da8b6521476cd8c8b9)；ZIP 注释记录提交 `3981d86efa4f333b2a34a7da8b6521476cd8c8b9`；SHA-256：`2732d7ccf7ecc9235621c86d2a572ded6b2ae2456577bc15faece8112a443be2` |

`prepare_build.py`从已核对散列的官方 APK 提取 ARM64 原生 `.so`；`libzstd-jni`由匹配版本的 Maven AAR 提供。运行环境和其他资源来自应用源码包。提取的原生库没有在本次开发中重新编译。

本次修改包括专用启动界面 `StudySteadyActivity.java`、游戏导入及存档备份代码 `study/StudyArchive.java`、构建配置、清单和触摸按键配置。新增或修改代码的记录日期为 2026-10-13 至 2026-10-14；Winlator 派生代码继续采用其 LGPL-2.1 许可。

## 再分发源码的具体要求

分发修改后的 Winlator 二进制时，应同时提供其完整对应源码，包括本项目修改、接口定义、编译和安装脚本，并保留版权与 LGPL 许可文本及修改说明。通过下载站分发时，LGPL-2.1 第 4 条允许在同一地点提供等价的源码下载渠道。对于受 LGPL 约束的链接组件，第 6 条还规定适用的替换或重新链接方式，以及为调试修改而进行逆向工程的权利。请使用上面的完整许可判断具体打包方式适用的条款。

Wine 等预编译 LGPL 组件需要与所发二进制相对应的源码及补丁；通用上游主页、最新分支链接或 APK 散列不能代替对应源码。本目录已保留本次 Winlator 应用源码与修改，但尚未整理官方 APK 内每个预编译组件对应的完整源码和构建集合。Box64 的 MIT 许可要求随其副本保留版权与许可文本，不要求公开修改源码。

游戏本身、EntisGLS 与 E-mote 的 Windows 程序、DLL 和素材由用户现有游戏目录提供，权利仍归原权利人。本项目的代码许可不授予公开分发这些游戏文件的权利。参见 `RESEARCH.md` 中原生移植研究和上游资料。


## Study Steady 0.2.0 修改

新增图标游戏库、通用 ZIP 导入、游戏与安卓桌面快捷方式管理、性能设置、常见保存目录备份，以及 `native/study_launch.c` Unicode 启动助手。修复桌面子窗口禁用和虚拟左键压制直接点击的问题，修正点击焦点的全屏坐标变换。源码和构建脚本随仓库提供。

GNU libc 的日语 locale 由已附带的 `localedef`、ja_JP locale 源定义及 UTF-8 charmap 在设备本地生成，保留上游许可。没有在 APK 内加入从用户电脑提取的游戏组件。设备自带字体只在设备内复制，不进入公开源码或 Release。

新增启动助手的源码采用 LGPL-2.1-or-later；应用的 Winlator 派生源码保留 LGPL-2.1。`tools/build_native.py` 提供其 MinGW-w64 构建命令。公开 Release 的运行组件仍来自固定官方 APK和固定应用资源；预编译组件的完整对应源码集合尚未完成独立审计，不能将本仓库描述为完全从原生源码重建所有运行组件的发行版。
