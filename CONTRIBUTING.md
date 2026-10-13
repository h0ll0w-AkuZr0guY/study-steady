# Contributing

优先级：能进入剧情并可靠保存、读档；随后处理输入、性能与游戏库体验。

在提出兼容性或性能修改时，请填写 issue 模板，给出设备、游戏版本、驱动、CPU 预设、分辨率和复现步骤。性能结果必须比较同一场景；标题静止画面的低刷新率不代表剧情卡顿。

提交前执行 `python tools/run_archive_tests.py`、Python 单元测试和 Android release 构建。涉及触控时运行 `tests/fixtures/input_probe.c`，验证点击、边缘按键、关闭环境后重新启动的计数。涉及存档时，验证恢复失败的回滚与原文件保留。

不要提交游戏素材、商业 DLL、个人存档、设备标识、签名密钥或访问凭据。应用来源与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。新增依赖应记录版本、来源和校验值。

发布者使用固定签名证书，递增 Android versionCode，并更新 CHANGELOG、VALIDATION 和发行校验值。CI 只产生未签名 APK，公开 Release 的安装包由维护者签名。

