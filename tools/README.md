# Study Steady 本地打包与部署工具

只使用 Python 3 标准库。命令在项目根目录运行，原始游戏文件只读，不上传，不删除。

```powershell
python tools/game_archive.py inspect "D:\AVGamerPlay\Study Steady"
python tools/game_archive.py package "D:\AVGamerPlay\Study Steady" dist/StudySteady.zip --dry-run
python tools/game_archive.py package "D:\AVGamerPlay\Study Steady" dist/StudySteady.zip
python tools/game_archive.py package "D:\AVGamerPlay\Study Steady" dist/StudySteady-with-launchers.zip --launcher "补丁/汉化补丁/ststeady_patch.exe"
python tools/game_archive.py verify dist/StudySteady-with-launchers.zip
python tools/game_archive.py verify dist/StudySteady.zip
python tools/game_archive.py verify dist/StudySteady.zip --crc
python -m unittest discover -s tests -p "test_tools.py" -v
```

`inspect` 和 `package --dry-run` 仅输出 JSON 文件计划，包含总字节数、ZIP64 需求和排除项，不读取游戏文本或计算哈希。`package` 一边写入 ZIP 一边计算 SHA-256，支持大于 4 GB 的单文件，使用 ZIP_STORED 和 ZIP64。现有输出需要显式 `--overwrite`，写入临时文件成功后才替换最终文件。`--no-saves` 可排除当前 PC 存档。

ZIP 根目录包含游戏根文件和 `study-manifest.json`。清单结构：

```json
{"schema":1,"gameId":"study-steady","launcher":"ststeady_patch.exe","files":[{"path":"script.noa","size":123,"sha256":"64个小写十六进制字符"}],"totalBytes":123}
```

示例仅说明结构，实际必需文件为选定的启动 EXE、`script.noa`、`psb.noa`。清单自身不列在 `files` 中。打包文件白名单为根目录 `.noa` 文件，以及 `ststeady.exe`、`ststeady_patch.exe`、`emotedriver.dll`、`hook.dll`、`AymKze.txt`。另保留 `补丁/汉化补丁/` 下三个精确文件：`ststeady_patch.exe`、`hook.dll`、`patchcn.noa`；其他补丁目录和文件仍排除。`--launcher` 可选择根目录两个 EXE 或这个嵌套 EXE，默认仍为根目录 `ststeady_patch.exe`。可选 `savedata/` 递归包含安全文件名，接受 Unicode 和未知存档扩展名，但排除 EXE/DLL/APK 等可执行文件。安装器、卸载器、启动 BAT、`EntisGLS_DVD_PATCH.exe/.dll` 均排除。校验拒绝绝对路径、路径穿越、重复项、大小不符、未知根文件、软链接、Unicode/大小写冲突、非 STORED 或加密 ZIP。限制 4096 个文件、64 GiB 总量、1 MiB 清单。

## 原电脑存档迁移

```powershell
python tools/make_pc_save_backup.py --dry-run
python tools/make_pc_save_backup.py --output dist/PC-save-backup.zip
python tools/make_pc_save_backup.py --verify dist/PC-save-backup.zip
```

默认读取当前用户 `%APPDATA%/ま～まれぇど/スタディ§ステディv1.01`。可通过 `--source` 指定同一游戏的原始存档目录。工具逐字节复制普通进度文件和缩略图，计算 SHA-256，保持原文件不变，不解释或修改其内容。ZIP 的 `save-manifest.json` 使用 `{schema:1,gameId:"study-steady",kind:"save-backup",files:[{path,size,sha256}],totalBytes}`；文件路径位于 `.wine/drive_c/users/xuser/AppData/Roaming/ま～まれぇど/スタディ§ステディv1.01/` 下，与应用的“恢复存档”功能兼容。生成后自动校验所有 SHA-256 和 CRC，现有输出需要 `--overwrite`。不导入游戏补丁目录中的额外存档包。

`verify` 检查全部文件的大小、SHA-256 和读取时 CRC；`--crc` 增加一次独立 CRC 全量扫描。约 10.7 GB 的游戏会占用相近大小的主机 ZIP 空间。

本机新版数据包为 `dist/StudySteady-with-launchers.zip`：25 个文件，原始数据共 10,612,203,136 字节，清单默认启动程序为 `补丁/汉化补丁/ststeady_patch.exe`。应用可以切换到根目录的启动程序。旧版 `dist/StudySteady.zip` 保留，其清单只有最初 22 个文件。

## APK 与 ZIP 部署

```powershell
python tools/adb_deploy.py --serial "设备序列号" deploy --apk dist/StudySteady-debug.apk --archive dist/StudySteady.zip
python tools/adb_deploy.py --serial "设备序列号" deploy --apk dist/StudySteady-debug.apk --install
python tools/adb_deploy.py --serial "设备序列号" deploy --archive dist/StudySteady.zip --push --launch
```

默认只列出部署计划。`--install` 执行 `adb install -r`，保留应用数据；`--push` 复制 ZIP 到 `/sdcard/Download/StudySteady.zip`；`--launch` 启动 `com.winlator/com.winlator.StudySteadyActivity`。多台设备在线时必须指定 `--serial`。可用全局 `--adb` 指定 adb 路径。ZIP 导入方式先检查可用空间能容纳 ZIP、解包游戏和额外 1 GiB。原生运行库包含固定 `com.winlator` 路径，因此 APK 保留该包名（`--package` 默认值），无法与官方同包名 Winlator 并存。签名不兼容时安装会失败，工具不会卸载已有应用或清理应用数据。

## 空间受限时直接导入

先安装 debug APK，打开应用完成容器准备。debug APK 的 `run-as` 必须可用。以下方式通过 TAR 流直接写入应用私有暂存目录，设备无需同时保存 ZIP：

```powershell
python tools/adb_deploy.py --serial "设备序列号" deploy-direct "D:\AVGamerPlay\Study Steady" --archive dist/StudySteady.zip --container-id 1
python tools/adb_deploy.py --serial "设备序列号" probe-stream
python tools/adb_deploy.py --serial "设备序列号" deploy-direct "D:\AVGamerPlay\Study Steady" --archive dist/StudySteady.zip --container-id 1 --execute --finish-import
```

默认打印计划；`--execute` 才传输，`--finish-import` 在传输成功后发送 `com.avgamer.study.FINISH_IMPORT` 给应用进行最终校验。`probe-stream` 通过 `exec-in` 和固定长度 `dd` 写入唯一命名的 4096 字节 cache 临时文件（包含全部二进制字节值），读取 SHA-256 验证后删除这个精确路径；每次直接导入也会自动执行该验证。Windows 版 adb 的 `shell` 二进制标准输入会被文本模式截断或转换换行，因此游戏使用 `exec-in run-as ... tar -xf - -C ...`，参数交给 adb 自行转义。文本命令负责预检、原子创建暂存目录和取得 READY 回执；15 秒内缺失则退出，不发送游戏数据。最终检查 TAR 最后一个文件 `study-manifest.json` 的完整 SHA-256，再交给应用校验所有游戏文件。传输使用已打包 ZIP 的清单，并在传输中重新计算原文件 SHA-256。也可使用 `--manifest` 指向已导出的 JSON；两者都不提供时会先完整读取原文件生成哈希。要求剩余空间至少为游戏大小加 1 GiB。暂存目录为 `files/rootfs/home/xuser-1/.wine/drive_c/.StudySteady-import`，目标为同级 `StudySteady`。已有暂存目录或目标会直接拒绝，工具不删除它们。中断后保留暂存目录用于诊断，应用可提供明确的重试处理。字体由独立 Wine 环境安装流程处理，不添加到游戏清单。
