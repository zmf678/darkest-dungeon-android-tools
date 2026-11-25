# 《暗黑地牢》余烬项目工作区总索引（Torch Archive v2.1）

> **免责声明与正版使用须知**：本项目仅供个人学习与技术研究参考，使用前**必须搭配已合法购买的 Steam 正版《暗黑地牢》目录**；严禁用于商业用途或违规再分发。详见 [README.md](README.md) / [README.en.md](README.en.md)。

本索引汇总当前工作区内的全部技术文档、模块说明、源码文件与自动化测试入口。

---

## 文档导航

| 文档 | 定位 | 适用读者 |
| :--- | :--- | :--- |
| [README.md](README.md) | 项目总览（简体中文）、免责声明、v2.1 核心特性与快速上手 | 所有用户、开发者 |
| [README.en.md](README.en.md) | Project Overview (English), Disclaimer, v2.1 Features & Quickstart | International users & developers |
| [INDEX.md](INDEX.md) | 工作区总索引、源码全量清单与任务速查矩阵 | 所有开发者 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | v2.1 一体化启动器架构、A2-2/A1 分发模式、全机型分辨率与高刷适配及 Steam 纹理转换规范 | 架构师、开发者 |
| [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | 手机内置与 PC 端双端修改器使用指南、二进制 DSON 规范与规则覆写参数表 | 修改器使用者、DSON 开发者 |
| [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | 工具链环境、Gradle 构建、Steam 转换核验、单测与真机排障 | 构建、测试与排障人员 |
| [docs/ROUTE-C-PURE-STEAM-PLAN.md](docs/ROUTE-C-PURE-STEAM-PLAN.md) | *（归档备忘）* 路线 C（v3.0）纯正版 Steam 安卓开源运行方案总体架构、M0–M4 路线图与 15 项任务清单 | 历史方案归档 |
| [docs/M0-BENCHMARK.md](docs/M0-BENCHMARK.md) | *（归档备忘）* 路线 C 阶段 M0 实测基准报告（环境、64 位裁剪同步、多运行时/图形后端 FPS 与内存对比） | 历史实测归档 |
| [docs/M0-MUMU-POC-GUIDE.md](docs/M0-MUMU-POC-GUIDE.md) | *（归档备忘）* 路线 C 阶段 M0 MuMu Android 15 模拟器复现手册、避坑实录与交接清单 | 历史复现归档 |
| [app/README.md](app/README.md) | Android 一体化启动器 `dev.torcharchive.ddgame` 包结构、UI 页签与引擎类说明 | Android 客户端开发者 |
| [dd-steam-converter/README.md](dd-steam-converter/README.md) | PC 端 Steam 资源转换、存档迁移、ADB 推送、CLI/GUI 修改器说明 | PC 工具链开发者与桌面用户 |
| [dd-steam-toolkit/README.md](dd-steam-toolkit/README.md) | 独立 PC Steam 版《暗黑地牢》余烬存档管家（实时自动留档/原子回档）与完整修改器说明（简体中文） | PC Steam 玩家与桌面开发者 |
| [dd-steam-toolkit/README.en.md](dd-steam-toolkit/README.en.md) | Standalone PC Steam Save Manager & Complete Modifier Guide (English) | International PC Steam users |
| [game-patch/README.md](game-patch/README.md) | 原生 Hook（`commit.c`）、Java 主机测试集与 v1 双包方案历史参考 | 原生 Hook / 单测维护者 |
| [DESIGN.md](DESIGN.md) | 余烬档案馆色彩、字体与原创头图提示词设计规范 | UI / 视觉设计人员 |
| [PRODUCT.md](PRODUCT.md) | 初始产品定位简述 | 产品规划参考 |

---

## 全量受控文件索引

> 以下清单完整覆盖当前仓库受 Git 追踪的全部源码、配置、资源、构建与测试文件（不含 `.gitignore` 忽略的本地构建产物、二进制包及会话临时文档）。

### 1. 仓库根目录与构建配置

| 文件 | 用途说明 |
| :--- | :--- |
| [build.gradle](build.gradle) | 顶层 Gradle 构建脚本（声明 AGP 插件版本与仓库配置） |
| [settings.gradle](settings.gradle) | Gradle 多模块设置文件（引入 `:app` 模块） |
| [gradle.properties](gradle.properties) | Gradle JVM 与 AndroidX/构建属性配置 |
| [.gitignore](.gitignore) | Git 忽略规则（排除本地构建产物、`dist/`、临时包与密钥文件） |

### 2. Android 一体化启动器 UI 与工程配置（`app/`）

详细模块说明见 [app/README.md](app/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [app/build.gradle](app/build.gradle) | `:app` 模块构建配置（`applicationId 'dev.torcharchive.ddgame'`、`versionName '2.1.0'`、`minSdk 26`、`targetSdk 36`、支持 `-ParchiveLab=true`） |
| [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) | 单 APK 多进程清单：主进程 `MainActivity` 与独立游戏子进程隔离声明 |
| [app/src/main/java/dev/torcharchive/dd/MainActivity.java](app/src/main/java/dev/torcharchive/dd/MainActivity.java) | 余烬启动器主界面，提供「启动 / 存档 / 修改 / 模组 / 画面 / 指南」6 个工作区页签与系统文件选择器流转 |
| [app/src/main/java/dev/torcharchive/dd/LiveArchive.java](app/src/main/java/dev/torcharchive/dd/LiveArchive.java) | 同 UID 直连门面层（零跨应用 `ContentProvider` IPC）：统一调度存档快照、Steam 迁移包导入、Mod 安装排序与显示配置 |
| [app/src/main/java/dev/torcharchive/dd/ModifierSection.java](app/src/main/java/dev/torcharchive/dd/ModifierSection.java) | 启动器「修改」页签完整交互控制器：庄园/资源、英雄嵌套 DSON、饰品仓库、建筑解锁、原始 DSON 树与通用规则修改器卡片 |
| [app/src/main/java/dev/torcharchive/dd/ArchiveStore.java](app/src/main/java/dev/torcharchive/dd/ArchiveStore.java) | 启动器本地存档仓与 Mod 预设库存储（导入、留档列表、预设管理与导出） |
| [app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml) | `ArchiveTheme` 主题样式定义（深炭黑背景与透明系统栏配置） |
| [app/src/main/res/drawable/archive_mark.xml](app/src/main/res/drawable/archive_mark.xml) | 余烬启动器矢量应用图标 |
| [app/src/main/res/drawable-nodpi/archive_illustration.png](app/src/main/res/drawable-nodpi/archive_illustration.png) | 顶部原创中世纪档案室与铁艺提灯水墨风格头图 |
| [app/src/main/res/xml/backup_rules.xml](app/src/main/res/xml/backup_rules.xml) | Android 数据提取与备份排除规则配置 |
| [app/src/main/assets/display.ttf](app/src/main/assets/display.ttf) | Noto Serif SC 展示标题字体 |
| [app/src/main/assets/NotoSerifSC-OFL.txt](app/src/main/assets/NotoSerifSC-OFL.txt) | `display.ttf` 的 SIL Open Font License 授权文件 |

### 3. Android 运行时与修改器引擎（`app/src/main/java/dev/torcharchive/game/`）

| 文件 | 用途说明 |
| :--- | :--- |
| [DsonCodec.java](app/src/main/java/dev/torcharchive/game/DsonCodec.java) | 无损 Red Hook 二进制 DSON（`0x0000B101`）编解码器，支持类型化/原始树节点读写及 `Steam → mod` 转换 |
| [SnapshotStore.java](app/src/main/java/dev/torcharchive/game/SnapshotStore.java) | 原子档位快照存储（自动留档历史、损坏版本校验、中断替换恢复与回档前强制备份） |
| [GameArchive.java](app/src/main/java/dev/torcharchive/game/GameArchive.java) | 游戏进程接入层：初始化 `SnapshotStore`、加载 `libtorcharchive.so` 安装保存后自动留档回调 |
| [PakMount.java](app/src/main/java/dev/torcharchive/game/PakMount.java) | A2-2 外置 `dd_assets.pak` 路径解析、清单校验与原子导入器（支持 A1 内置资产回退） |
| [DisplayConfig.java](app/src/main/java/dev/torcharchive/game/DisplayConfig.java) | 全机型物理屏幕与支持刷新率探测、4–6 档动态分辨率生成与 60 / 90 / 120 Hz / 自动最高高刷适配 |
| [NxProcessControl.java](app/src/main/java/dev/torcharchive/game/NxProcessControl.java) | 游戏子进程生命周期管理（存活检测、写前安全停机、启动与重启） |
| [ModBridge.java](app/src/main/java/dev/torcharchive/game/ModBridge.java) | `Android/media/dev.torcharchive.ddgame/mods/` 模组管理、Steam 迁移包解包导入与 `applied_ugcs_1_0` 槽位启停排序 |
| [SaveEditor.java](app/src/main/java/dev/torcharchive/game/SaveEditor.java) | 手机端全维度二进制存档修改引擎（庄园、钱包、英雄嵌套 DSON、怪癖/疾病、饰品、建筑 `ddHash` 与原始节点树） |
| [ContentModGenerator.java](app/src/main/java/dev/torcharchive/game/ContentModGenerator.java) | 手机端非破坏性规则覆盖 Mod 生成器（生成与管理 `torch_content_modifier`） |
| [CatalogData.java](app/src/main/java/dev/torcharchive/game/CatalogData.java) | 内置中文名称与常用饰品、怪癖、疾病、美德、折磨与建筑目录常量 |

### 4. PC 端 Steam 转换器、迁移工具与桌面修改器（`dd-steam-converter/`）

详细命令说明见 [dd-steam-converter/README.md](dd-steam-converter/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [run.py](dd-steam-converter/run.py) | PC 端 CLI / GUI 统一启动入口 |
| [requirements.txt](dd-steam-converter/requirements.txt) | Python 运行依赖清单（`Pillow`、`numpy` 等） |
| [dd_converter/\_\_init\_\_.py](dd-steam-converter/dd_converter/__init__.py) | 包元数据与版本号定义 |
| [dd_converter/steam_detect.py](dd-steam-converter/dd_converter/steam_detect.py) | 自动扫描 Windows 注册表与 `libraryfolders.vdf`，识别 Steam 游戏本体、DLC、创意工坊 `262060` Mod 与 `userdata` 存档 |
| [dd_converter/dson.py](dd-steam-converter/dd_converter/dson.py) | Python 版无损二进制 DSON 解析/序列化与 Red Hook 签名哈希 `dd_hash()` |
| [dd_converter/dds_encoder.py](dd-steam-converter/dd_converter/dds_encoder.py) | 双引擎 ASTC 4×4 纹理编码器（优先官方 ARM `astcenc`，内置向量化 `Pillow + NumPy` 回落） |
| [dd_converter/pak_builder.py](dd-steam-converter/dd_converter/pak_builder.py) | 生成并核验 `dd_assets.pak`（`ZIP_STORED`、保留色彩查找表 LUT PNG、精确重建资源清单） |
| [dd_converter/migration.py](dd-steam-converter/dd_converter/migration.py) | 打包 Steam 存档（自动 `Steam → mod` 转换）与关联创意工坊 Mod 为 `steam-migration.zip` |
| [dd_converter/adb_push.py](dd-steam-converter/dd_converter/adb_push.py) | ADB 设备列举与一键安装 APK / 推送 `dd_assets.pak` / 同步 Mod 目录 |
| [dd_converter/apk_bundler.py](dd-steam-converter/dd_converter/apk_bundler.py) | A1 模式整包合并工具：将薄壳 APK 与 `dd_assets.pak` 合并为单 APK 并执行 16 KB 对齐签名 |
| [dd_converter/save_editor.py](dd-steam-converter/dd_converter/save_editor.py) | PC 端完整二进制 DSON 存档修改器（写前自动备份到 `.torch-backups/`） |
| [dd_converter/content_modifier.py](dd-steam-converter/dd_converter/content_modifier.py) | PC 端非破坏性内容修改器生成（含全英雄 HP/伤害/速度/闪避倍率及建筑折扣，兼容 Steam 与安卓） |
| [dd_converter/cli.py](dd-steam-converter/dd_converter/cli.py) | 子命令解析与调度（`detect`、`convert`、`verify`、`migrate`、`push`、`build-apk`、`edit-save`、`build-mod`、`gui`） |
| [dd_converter/gui.py](dd-steam-converter/dd_converter/gui.py) | 余烬视觉风格的 PC 桌面图形界面（集成转换、迁移、完整存档修改器与规则修改器） |
| [tests/test_converter.py](dd-steam-converter/tests/test_converter.py) | Python 自动化单元测试与真实固件回归测试（DSON 往返、ASTC 4×4 块级精确验证、迁移、双修改器） |

### 4.5 独立 PC Steam 版存档管家 & 完整修改器（`dd-steam-toolkit/`）

详细使用与命令说明见 [dd-steam-toolkit/README.md](dd-steam-toolkit/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [run.py](dd-steam-toolkit/run.py) | PC Steam 独立版统一入口（默认启动余烬桌面窗口，支持全套 CLI 子命令） |
| [启动余烬PC工坊.bat](dd-steam-toolkit/启动余烬PC工坊.bat) | Windows 双击一键启动脚本（零第三方 Python 依赖） |
| [build_exe.py](dd-steam-toolkit/build_exe.py) | 可选 PyInstaller 单文件 `TorchArchivePC.exe` 打包脚本 |
| [torch_pc/dson.py](dd-steam-toolkit/torch_pc/dson.py) | 无损二进制 DSON 编解码器与 `applied_ugcs_1_0` 保留来源读写 |
| [torch_pc/steam_env.py](dd-steam-toolkit/torch_pc/steam_env.py) | 多 Steam 账号/DLC/创意工坊/本地 Mod 探测、`Darkest.exe` 进程安全检查与一键启动 |
| [torch_pc/snapshot_store.py](dd-steam-toolkit/torch_pc/snapshot_store.py) | SHA-256 去重校验快照仓、原子事务回档、中断恢复、变更对比与跨平台 ZIP 导入导出 |
| [torch_pc/save_watcher.py](dd-steam-toolkit/torch_pc/save_watcher.py) | 后台实时存档变更监控线程（对标安卓 `libtorcharchive.so` 自动留档） |
| [torch_pc/catalog.py](dd-steam-toolkit/torch_pc/catalog.py) | 完整中文图鉴 + 动态索引 Steam 本体及 DLC 全量饰品与怪癖库 |
| [torch_pc/save_editor.py](dd-steam-toolkit/torch_pc/save_editor.py) | 全维度二进制存档修改引擎（写前强制留档、兼容 Steam 绝对/百分比血量） |
| [torch_pc/content_modifier.py](dd-steam-toolkit/torch_pc/content_modifier.py) | 非破坏性规则 Mod 生成器（4 套预设 + 本体/DLC 英雄倍率 + 建筑折扣 + Steam 直装挂载） |
| [torch_pc/mod_manager.py](dd-steam-toolkit/torch_pc/mod_manager.py) | Steam 创意工坊与本地 Mod 槽位加载排序管理（严格保留 `Steam` / `mod` 来源） |
| [torch_pc/web_server.py](dd-steam-toolkit/torch_pc/web_server.py) | 零依赖 `127.0.0.1` 会话令牌本地服务与 Edge/Chrome `--app` 独立桌面窗口启动器 |
| [torch_pc/cli.py](dd-steam-toolkit/torch_pc/cli.py) | 完整 CLI（`ui`、`detect`、`watch`、`snapshot`、`edit-save`、`build-mod`、`mods`） |
| [torch_pc/web_ui/](dd-steam-toolkit/torch_pc/web_ui/index.html) | 余烬深色桌面端单页前端（`index.html`、`style.css`、`app.js`） |
| [tests/test_toolkit.py](dd-steam-toolkit/tests/test_toolkit.py) | 单元测试、HTTP API 端到端测试及本机真实 Steam 存档副本回归测试 |

### 5. Java 主机测试集与 v1 历史参考（`game-patch/`）

详细说明见 [game-patch/README.md](game-patch/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [DsonCodecCheck.java](game-patch/test/dev/torcharchive/game/DsonCodecCheck.java) | Java 主机验证程序：校验 `DsonCodec` 对真实二进制 `persist.game.json` 无损往返与 Mod 映射转换 |
| [SnapshotStoreCheck.java](game-patch/test/dev/torcharchive/game/SnapshotStoreCheck.java) | Java 主机验证程序：校验 `SnapshotStore` 连续留档、防抖去重、多文件逐字节恢复与损坏拦截 |
| [UnifiedEngineCheck.java](game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java) | Java 主机集成验证程序：覆盖 `DisplayConfig` 预设、`SaveEditor` 嵌套英雄/饰品/建筑编辑与 `ContentModGenerator` |
| [ArchiveProvider.java](game-patch/src/dev/torcharchive/game/ArchiveProvider.java) | *（v1 历史参考）* 原双包跨应用 `ContentProvider` 通信服务端 |
| [game-patch DisplayConfig.java](game-patch/src/dev/torcharchive/game/DisplayConfig.java) | *（v1 历史参考）* 早期分辨率配置模块 |
| [game-patch DsonCodec.java](game-patch/src/dev/torcharchive/game/DsonCodec.java) | *（v1 历史参考）* 早期 DSON 编解码模块 |
| [game-patch GameArchive.java](game-patch/src/dev/torcharchive/game/GameArchive.java) | *（v1 历史参考）* 早期留档接入模块 |
| [game-patch ModBridge.java](game-patch/src/dev/torcharchive/game/ModBridge.java) | *（v1 历史参考）* 早期 Mod 槽位桥接模块 |
| [game-patch SnapshotStore.java](game-patch/src/dev/torcharchive/game/SnapshotStore.java) | *（v1 历史参考）* 早期快照仓模块 |

---

## 常见任务速查矩阵

| 您想做什么 | 首先查看文档 | 对应入口 / 核心文件 |
| :--- | :--- | :--- |
| 编译 Android 启动器工程与运行 Lint | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [app/build.gradle](app/build.gradle) |
| 从正版 Steam 转换生成 `dd_assets.pak` 并校验 | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [dd-steam-converter/run.py](dd-steam-converter/run.py)、[pak_builder.py](dd-steam-converter/dd_converter/pak_builder.py) |
| 理解 A2-2 外置直读、高刷适配或 ASTC 纹理规范 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | [PakMount.java](app/src/main/java/dev/torcharchive/game/PakMount.java)、[dds_encoder.py](dd-steam-converter/dd_converter/dds_encoder.py) |
| 修改存档（金币、英雄属性/怪癖、饰品、建筑、原始字段） | [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | [SaveEditor.java](app/src/main/java/dev/torcharchive/game/SaveEditor.java)、[torch_pc/save_editor.py](dd-steam-toolkit/torch_pc/save_editor.py) |
| 纯 PC Steam 实时自动留档、原子回档与独立桌面修改器 | [dd-steam-toolkit/README.md](dd-steam-toolkit/README.md) | [dd-steam-toolkit/run.py](dd-steam-toolkit/run.py)、[启动余烬PC工坊.bat](dd-steam-toolkit/启动余烬PC工坊.bat) |
| 生成非破坏性游戏规则修改 Mod（堆叠、倍速、全员数值等） | [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | [ContentModGenerator.java](app/src/main/java/dev/torcharchive/game/ContentModGenerator.java)、[content_modifier.py](dd-steam-converter/dd_converter/content_modifier.py) |
| 适配手机/平板/折叠屏分辨率、60/90/120 Hz 高刷与挖孔避让 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | [DisplayConfig.java](app/src/main/java/dev/torcharchive/game/DisplayConfig.java) |
| 运行 Python 与 Java 主机自动化测试 | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [test_converter.py](dd-steam-converter/tests/test_converter.py)、[test_toolkit.py](dd-steam-toolkit/tests/test_toolkit.py)、[UnifiedEngineCheck.java](game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java) |

---

## 本地忽略目录与文件说明

为保持仓库精简，以下构建产物与本地文件已写入 [.gitignore](.gitignore)，仅存在于本地工作区中：

- **输出与中间产物**：`dist/`、`decoded/`、`*.apk`、`*.pak`、`*.dex`、`*.so`、`*.zip`、`*.keystore`、`.gradle/`、`**/build/`
- **PC 修改器自动备份**：`.torch-backups/`
- **本地阶段性规划与总结笔记**：`STEAM-TO-ANDROID-LAUNCHER-PLAN.md`、`NEXT-CHAT-AUTO-SAVES.md`、`TABLET-RESOLUTION-AND-MOD-MANAGER.md`、`PHASE-SUMMARY-*.md`
