# 《暗黑地牢》余烬项目工作区总索引（Torch Archive v2.0）

> **私有仓库内置运行层说明**：本仓库为**私有仓库**，已在 [switch-runtime/](switch-runtime/) 目录内置精简版 **Switch 运行时壳**（`TorchLauncher-v2.0.apk`、`wicked-runtime-shell.apk`）与 **Switch 游戏主程序与触屏差异层**（`switch-diff-layer.zip`），工具链开箱即可搭配本机正版 Steam《暗黑地牢》目录运行，不再依赖 3.8 GB 完整基础 APK（`dist/` 与本地生成的 `.pak`/`.apk` 仍受 `.gitignore` 忽略）。请勿将本仓库设为公开。

本索引汇总 `d:\暗黑地牢apk` 工作区内的全部技术文档、模块说明、源码文件、构建与测试脚本，以及日常开发与排查的任务速查入口。

---

## 文档导航

| 文档 | 定位 | 适用读者 |
| :--- | :--- | :--- |
| [README.md](README.md) | 项目总览、v2.0 核心特性与快速上手命令 | 所有用户、新接手开发者 |
| [INDEX.md](INDEX.md) | 工作区总索引、源码全量清单与任务速查矩阵 | 所有开发者 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | v2.0 一体化薄壳架构、4 处 Smali Hook、A2-2/A1 模式、ASTC 4×4 DDS 纹理与 `filelist.csv` 规范 | 架构师、逆向/移植开发者 |
| [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | 手机内置与 PC 端双端修改器使用指南、二进制 DSON 规范与规则覆写参数表 | 修改器使用者、DSON 开发者 |
| [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | 工具链环境、Gradle/薄壳 APK 构建、Steam 转换核验、单测与真机排障 | 构建发布、测试与排障人员 |
| [docs/ROUTE-C-PURE-STEAM-PLAN.md](docs/ROUTE-C-PURE-STEAM-PLAN.md) | *（归档备忘）* 路线 C（v3.0）纯正版 Steam 安卓开源运行方案总体架构、M0–M4 路线图与 15 项任务清单 | 历史方案归档 |
| [docs/M0-BENCHMARK.md](docs/M0-BENCHMARK.md) | *（归档备忘）* 路线 C 阶段 M0 实测基准报告（环境、64 位裁剪同步、多运行时/图形后端 FPS 与内存对比） | 历史实测归档 |
| [docs/M0-MUMU-POC-GUIDE.md](docs/M0-MUMU-POC-GUIDE.md) | *（归档备忘）* 路线 C 阶段 M0 MuMu Android 15 模拟器复现手册、避坑实录与交接清单 | 历史复现归档 |
| [app/README.md](app/README.md) | Android 一体化启动器 `dev.torcharchive.ddgame` 包结构、UI 页签与引擎类说明 | Android 客户端开发者 |
| [dd-steam-converter/README.md](dd-steam-converter/README.md) | PC 端 Steam 资源转换、存档迁移、ADB 推送、CLI/GUI 修改器说明 | PC 工具链开发者与桌面用户 |
| [game-patch/README.md](game-patch/README.md) | 原生 Hook（`commit.c`）、Java 主机测试集与 v1 双包方案历史参考 | 原生 Hook / 单测维护者 |
| [DESIGN.md](DESIGN.md) | 余烬档案馆色彩、字体与原创头图提示词设计规范 | UI / 视觉设计人员 |
| [PRODUCT.md](PRODUCT.md) | 初始产品定位简述 | 产品规划参考 |

---

## 全量受控文件索引

> 以下清单完整覆盖当前仓库受 Git 追踪的全部源码、配置、资源、构建与测试文件（不含 `.gitignore` 忽略的本地构建产物、二进制包及会话临时文档）。

### 1. 仓库根目录与构建配置

| 文件 | 用途说明 |
| :--- | :--- |
| [launcher-build.py](launcher-build.py) | 一体化薄壳启动器 APK 组装脚本：校验 `switch-runtime/wicked-runtime-shell.apk` 或完整基础 APK SHA-256、对 `NxActivity` 与 `LayerView` 注入 4 处最小 Smali Hook、编译/复用 `libtorcharchive.so`、合并壳与游戏 MultiDex（`classes<N+1>.dex`）、支持可选 `--bundle-pak` A1 整包、16 KB 对齐与签名 |
| [build.gradle](build.gradle) | 顶层 Gradle 构建脚本（声明 AGP 插件版本与仓库配置） |
| [settings.gradle](settings.gradle) | Gradle 多模块设置文件（引入 `:app` 模块） |
| [gradle.properties](gradle.properties) | Gradle JVM 与 AndroidX/构建属性配置 |
| [.gitignore](.gitignore) | Git 忽略规则（排除 `dist/` 及本地临时 `.apk`/`.pak`/`.so`/`.dex`/`.zip`/`.keystore`/`.torch-backups/`，仅显式放行私有仓库受控目录 `switch-runtime/`） |

### 1.5 私有仓库内置 Switch 运行时壳与触屏差异层（`switch-runtime/`）

| 文件 | 体积 | 用途说明 |
| :--- | :---: | :--- |
| [TorchLauncher-v2.1.apk](switch-runtime/TorchLauncher-v2.1.apk) | 14.90 MB | 预组装、16 KB 对齐且已签名的余烬一体化薄壳启动器 v2.1.0（`versionCode=40`，含深色修改器二级页、60/90/120 Hz 纯 GOT 高刷适配、角色 Mod 原图高清补丁、7 处 Smali Hook、新版 `libtorcharchive.so` 与 Wicked Switch 运行时壳），可直接安装或原地覆盖 v1/v2.0 |
| [wicked-runtime-shell.apk](switch-runtime/wicked-runtime-shell.apk) | 9.01 MB | 从 Wicked 2.2.2 基础 APK 抽取的精简 Switch 运行时壳（`AndroidManifest.xml`、`resources.arsc`、`res/`、`classes.dex`、4 个 `arm64-v8a/*.so` 与 5 个辅助小资产），供 `launcher-build.py` 源码重编使用 |
| [switch-diff-layer.zip](switch-runtime/switch-diff-layer.zip) | 38.01 MB | Switch 游戏主程序（`assets/nx/exefs/main`）+ Switch 触屏/UI 覆写差异层（`assets/nx/overlay/`，已剔除 `overlay_ru/`）+ 23 份 `filelist.csv` + 5 份 `dlc_info.csv` + 39 项 Switch 专属 `romfs/` 差异件与 DLC `.loc` 兼容件，由 `dd-steam-converter` 自动识别 |
| [SHA256SUMS.txt](switch-runtime/SHA256SUMS.txt) | < 1 KB | 上述 3 个内置二进制包的精确 SHA-256 校验清单 |

### 2. Android 一体化启动器 UI 与工程配置（`app/`）

详细模块说明见 [app/README.md](app/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [app/build.gradle](app/build.gradle) | `:app` 模块构建配置（`applicationId 'dev.torcharchive.ddgame'`、`versionName '2.0.0'`、`minSdk 26`、`targetSdk 36`、支持 `-ParchiveLab=true`） |
| [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) | 单 APK 多进程清单：主进程 `MainActivity`、独立游戏进程 `:nx`（`ru.darkest.port.NxActivity`）、重启中转进程 `:restart`（`RestartActivity`） |
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
| [DisplayConfig.java](app/src/main/java/dev/torcharchive/game/DisplayConfig.java) | 全机型物理屏幕探测、4–6 档动态分辨率生成、`NxActivity.resolution()` 与 `LayerView.onMeasure()` 注入适配 |
| [NxProcessControl.java](app/src/main/java/dev/torcharchive/game/NxProcessControl.java) | `:nx` 游戏子进程生命周期管理（存活检测、写前安全停止 `:nx`、启动与重启游戏） |
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
| [dd_converter/dds_encoder.py](dd-steam-converter/dd_converter/dds_encoder.py) | 基于 `Pillow + NumPy` 的向量化 ASTC 4×4 编码器（封装为 Wicked `libddnx.so` 所需的 128 字节 DXT3 DDS 头） |
| [dd_converter/pak_builder.py](dd-steam-converter/dd_converter/pak_builder.py) | 生成并核验 `dd_assets.pak`（`ZIP_STORED`、保留 980 个原版 LUT/tint PNG、剔除 `overlay_ru`、精确重建全部 23 份 `filelist.csv`） |
| [dd_converter/migration.py](dd-steam-converter/dd_converter/migration.py) | 打包 Steam 存档（自动 `Steam → mod` 转换）与关联创意工坊 Mod 为 `steam-migration.zip` |
| [dd_converter/adb_push.py](dd-steam-converter/dd_converter/adb_push.py) | ADB 设备列举与一键安装 APK / 推送 `dd_assets.pak` / 同步 Mod 目录 |
| [dd_converter/apk_bundler.py](dd-steam-converter/dd_converter/apk_bundler.py) | A1 模式整包合并工具：将薄壳 APK 与 `dd_assets.pak` 合并为单 APK 并执行 16 KB 对齐签名 |
| [dd_converter/save_editor.py](dd-steam-converter/dd_converter/save_editor.py) | PC 端完整二进制 DSON 存档修改器（写前自动备份到 `.torch-backups/`） |
| [dd_converter/content_modifier.py](dd-steam-converter/dd_converter/content_modifier.py) | PC 端非破坏性内容修改器生成（含全英雄 HP/伤害/速度/闪避倍率及建筑折扣，兼容 Steam 与安卓） |
| [dd_converter/cli.py](dd-steam-converter/dd_converter/cli.py) | 子命令解析与调度（`detect`、`convert`、`verify`、`migrate`、`push`、`build-apk`、`edit-save`、`build-mod`、`gui`） |
| [dd_converter/gui.py](dd-steam-converter/dd_converter/gui.py) | 余烬视觉风格的 PC 桌面图形界面（集成转换、迁移、完整存档修改器与规则修改器） |
| [tests/test_converter.py](dd-steam-converter/tests/test_converter.py) | Python 自动化单元测试与真实固件回归测试（DSON 往返、ASTC 4×4 块级精确验证、迁移、双修改器） |

### 5. 原生 Hook、Java 主机测试集与 v1 历史参考（`game-patch/`）

详细说明见 [game-patch/README.md](game-patch/README.md)。

| 文件 | 用途说明 |
| :--- | :--- |
| [native/commit.c](game-patch/native/commit.c) | 编译为 `libtorcharchive.so` 的 C 原生 GOT Hook：拦截 `libddnx.so` 存档提交后的 `nftw` 调用，触发 Java `GameArchive.afterCommit()` 自动留档 |
| [DsonCodecCheck.java](game-patch/test/dev/torcharchive/game/DsonCodecCheck.java) | Java 主机验证程序：校验 `DsonCodec` 对真实二进制 `persist.game.json` 无损往返与 Mod 映射转换 |
| [SnapshotStoreCheck.java](game-patch/test/dev/torcharchive/game/SnapshotStoreCheck.java) | Java 主机验证程序：校验 `SnapshotStore` 连续留档、防抖去重、多文件逐字节恢复与损坏拦截 |
| [UnifiedEngineCheck.java](game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java) | Java 主机集成验证程序：覆盖 `DisplayConfig` 预设、`SaveEditor` 嵌套英雄/饰品/建筑编辑与 `ContentModGenerator` |
| [game-patch/build.py](game-patch/build.py) | *（v1 历史参考）* 原双包架构中生成独立补丁版游戏 APK 的构建脚本 |
| [ArchiveProvider.java](game-patch/src/dev/torcharchive/game/ArchiveProvider.java) | *（v1 历史参考）* 原双包跨应用 `ContentProvider` 通信服务端 |
| [game-patch DisplayConfig.java](game-patch/src/dev/torcharchive/game/DisplayConfig.java) | *（v1 历史参考）* 早期游戏内分辨率配置模块 |
| [game-patch DsonCodec.java](game-patch/src/dev/torcharchive/game/DsonCodec.java) | *（v1 历史参考）* 早期游戏侧 DSON 编解码模块 |
| [game-patch GameArchive.java](game-patch/src/dev/torcharchive/game/GameArchive.java) | *（v1 历史参考）* 早期游戏侧留档接入模块 |
| [game-patch ModBridge.java](game-patch/src/dev/torcharchive/game/ModBridge.java) | *（v1 历史参考）* 早期游戏侧 Mod 槽位桥接模块 |
| [game-patch SnapshotStore.java](game-patch/src/dev/torcharchive/game/SnapshotStore.java) | *（v1 历史参考）* 早期游戏侧快照仓模块 |

---

## 常见任务速查矩阵

| 您想做什么 | 首先查看文档 | 对应入口 / 核心文件 |
| :--- | :--- | :--- |
| 构建约 15 MB 薄壳启动器 APK | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [launcher-build.py](launcher-build.py)、[app/build.gradle](app/build.gradle) |
| 从正版 Steam 转换生成 `dd_assets.pak` 并校验 | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [dd-steam-converter/run.py](dd-steam-converter/run.py)、[pak_builder.py](dd-steam-converter/dd_converter/pak_builder.py) |
| 理解 A2-2 外置直读、Smali Hook 或 ASTC 纹理原理 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | [PakMount.java](app/src/main/java/dev/torcharchive/game/PakMount.java)、[dds_encoder.py](dd-steam-converter/dd_converter/dds_encoder.py) |
| 修改存档（金币、英雄属性/怪癖、饰品、建筑、原始字段） | [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | [SaveEditor.java](app/src/main/java/dev/torcharchive/game/SaveEditor.java)、[save_editor.py](dd-steam-converter/dd_converter/save_editor.py) |
| 生成非破坏性游戏规则修改 Mod（堆叠、倍速、全员数值等） | [docs/MODIFIER-GUIDE.md](docs/MODIFIER-GUIDE.md) | [ContentModGenerator.java](app/src/main/java/dev/torcharchive/game/ContentModGenerator.java)、[content_modifier.py](dd-steam-converter/dd_converter/content_modifier.py) |
| 适配手机/平板/折叠屏分辨率与刘海避让 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | [DisplayConfig.java](app/src/main/java/dev/torcharchive/game/DisplayConfig.java) |
| 运行 Python 与 Java 主机自动化测试 | [docs/BUILD-AND-TEST.md](docs/BUILD-AND-TEST.md) | [test_converter.py](dd-steam-converter/tests/test_converter.py)、[UnifiedEngineCheck.java](game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java) |

---

## 本地忽略目录与文件说明

为保持仓库精简（仅保留 `switch-runtime/` 下的 61.9 MB 精简运行时壳与触屏差异层，不提交 3.8 GB+ 整包与构建产物），以下目录和文件已写入 [.gitignore](.gitignore)，仅存在于本地工作区中、不会提交到远端仓库：

- **输出与中间产物**：`dist/`、`decoded/`、`*.apk`、`*.pak`、`*.dex`、`*.so`、`*.zip`、`*.keystore`、`.gradle/`、`**/build/`（`switch-runtime/` 内受控文件除外）
- **PC 修改器自动备份**：`.torch-backups/`
- **本地阶段性规划与总结笔记**：`STEAM-TO-ANDROID-LAUNCHER-PLAN.md`、`NEXT-CHAT-AUTO-SAVES.md`、`TABLET-RESOLUTION-AND-MOD-MANAGER.md`、`PHASE-SUMMARY-*.md`
