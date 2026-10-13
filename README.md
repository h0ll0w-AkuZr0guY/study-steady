# Study Steady

把喜欢的故事随身带走。一个面向 **PC Galgame** 的离线 Android 游戏管理器，基于 [Winlator 11.2](https://github.com/brunodev85/winlator)、Wine 与 Box64。

[下载 APK](https://github.com/h0ll0w-AkuZr0guY/study-steady/releases) · [使用说明](docs/USER_GUIDE.md) · [性能与兼容性](docs/COMPATIBILITY.md) · [构建说明](docs/BUILDING.md) · [实测记录](VALIDATION.md)

## 能做什么

- 普通 ZIP 导入完整游戏目录，流式解压，支持 ZIP64、UTF-8 和可选 GBK 文件名。
- 游戏图标库；选择任意嵌套 EXE、重命名、选择图标图片、固定安卓桌面快捷方式。
- 点图标直接启动，支持触控及继续、菜单、隐藏、快进、返回等边缘按键。
- 每款游戏可配置工作目录、CPU 预设、虚拟按键及全屏缩放；提供运行环境高级设置。
- 游戏原有 SAVE / LOAD 持久保存；提供常见存档位置备份、验证与事务恢复。
- CPU 性能、均衡、稳定模式；图形方案、分辨率及 FPS 显示可切换。日常默认关闭调试日志。

## 安装与第一次游玩

需要 ARM64 Android 8.0 或更高版本。已连接真机测试为小米 Pad 6 Pro / Adreno 730 / Android 15。首次启动会解压 APK 自带的离线运行环境。

1. 在 Release 下载签名 APK 并安装。
2. 把合法持有的完整 PC 游戏目录压成 ZIP，放到设备上；点「导入游戏 ZIP」。
3. 导入结束后选择实际启动的 EXE，点游戏图标开始。
4. 在游戏内保存；安卓返回键打开运行菜单，选择 Exit 退出环境。再次启动可使用游戏的 LOAD。
5. 需要桌面入口时，点「管理 → 添加到安卓桌面」，按照系统提示确认。

导入需要同时容纳外部 ZIP、解压后的游戏及运行环境。完成后可自行删除外部 ZIP。当前解压上限 64 GiB / 100000 项，预留 512 MiB 空间。

**兼容性按游戏验证。** Windows API、图形、视频、字体、运行库、授权验证与第三方插件都会影响运行结果。不能承诺所有 PC 游戏或所有 Galgame 都可玩。安装包及仓库不包含商业游戏、个人存档或游戏补丁。

## 数据与升级

游戏保存在应用私有 Wine 环境中，关闭应用和重启设备不会主动清理存档。移除游戏库图标仅移除索引，保留原文件。卸载应用或清除应用数据会删除内部游戏与存档，务必先备份。

此版本沿用 **`com.winlator`** 包名，兼容上游原生库的固定路径，与官方 Winlator 不能同时安装。后续项目版本必须使用相同签名覆盖安装；遇到签名冲突请先保护存档，不能用卸载作为日常升级方式。

备份覆盖 Wine 用户目录、注册表、游戏各级 `save` / `saves` / `savedata` 目录及常见小型保存、配置文件。特殊引擎自定义路径需要单独确认。详见 [使用说明](docs/USER_GUIDE.md)。

## 项目与维护

当前发布线为 **0.2.x 预发布**，优先保证剧情、输入与保存，再进行性能和界面改进。通过 [issue 模板](https://github.com/h0ll0w-AkuZr0guY/study-steady/issues/new/choose) 报告错误或游戏兼容性。CI 检查 ZIP 安全、存档恢复、游戏库持久化，并构建未签名 release APK。发布过程见 [维护说明](docs/MAINTENANCE.md)。

源码保留 Winlator 的 LGPL-2.1 许可与第三方声明，见 [LICENSE](LICENSE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。派生应用与自有 Unicode 启动辅助程序的源码、构建脚本随仓库提供。预编译运行组件的固定来源与源码参考见第三方声明。

本项目以最初移植 Study Steady 的经历命名，面向用户自行提供的 PC Galgame 游戏库。
