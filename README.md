# Darkest Dungeon Android Tools — 《暗黑地牢》余烬一体化启动器 & Steam 转安卓工具链 (v2.1)

> **私有仓库内置运行层说明**：本仓库现为**私有仓库**，已在 [switch-runtime/](switch-runtime/) 目录直接内置精简后的 **Switch 运行时壳**（预组装可直装的 `TorchLauncher-v2.1.apk` 与供源码重编译用的 `wicked-runtime-shell.apk`）及 **Switch 游戏主程序与触屏差异层**（`switch-diff-layer.zip`，含 `exefs/main`、`overlay/` 触屏界面层、23 份 `filelist.csv`、`dlc_info.csv` 与 Switch 专属兼容件）。日常使用与构建均**不再需要额外寻找 3.8 GB 原版基础 APK**，只需本机正版 Steam《暗黑地牢》目录即可完成转换与部署；请勿将本仓库重新设为公开。

> **文档与索引导航**：[工作区总索引 (INDEX.md)](INDEX.md) · [PC Steam 独立存档与修改器 (dd-steam-toolkit)](dd-steam-toolkit/README.md) · [系统架构与纹理规范](docs/ARCHITECTURE.md) · [完整修改器指南](docs/MODIFIER-GUIDE.md) · [构建测试与排障](docs/BUILD-AND-TEST.md) · [Android 启动器模块](app/README.md) · [PC 转换器](dd-steam-converter/README.md) · [原生 Hook 与单测](game-patch/README.md)

---

## 使用前必读：前置条件与两步上手清单

### 你需要先准备什么
1. **本机已安装的正版 Steam《暗黑地牢》目录**（用于转换本体/已购 DLC 贴图、音频、配置，以及迁移存档与创意工坊 Mod）。
2. **PC 端 Python 环境**（日常直接使用仓库内置的 [switch-runtime/TorchLauncher-v2.1.apk](switch-runtime/TorchLauncher-v2.1.apk) 与 [switch-runtime/switch-diff-layer.zip](switch-runtime/switch-diff-layer.zip)，**无需安装 Gradle、JDK、Android SDK、Android NDK 或 apktool**）：
   - Python 3.10+（执行 `pip install -r dd-steam-converter\requirements.txt`）；
   - （强烈推荐）从 [ARM astc-encoder Releases](https://github.com/ARM-software/astc-encoder/releases) 下载 `astcenc-avx2.exe`（或 `astcenc`）放入 `dd-steam-converter\bin\`，可将贴图画质拉满至原版 51–55 dB PSNR；
   - （可选）若需把 Steam 过场 `.ogv` 转码为 `.mp4`，在系统 `PATH` 中安装 `ffmpeg`。
3. **仅当需要修改并重编启动器 APK 源码时**，才需要 JDK 21、Android Build Tools（`zipalign` / `apksigner.jar`）、`apktool.jar` 及签名密钥，且可直接以仓库内 9 MB 的 [switch-runtime/wicked-runtime-shell.apk](switch-runtime/wicked-runtime-shell.apk) 作为 `launcher-build.py` 输入。

### 两步跑通安卓真机
1. **转换正版 Steam 资源为单文件包**（自动读取仓库内 `switch-runtime\switch-diff-layer.zip`）：
   运行 `python dd-steam-converter\run.py convert --output dist\dd_assets.pak`（可选同时运行 `migrate` 打包 Steam 存档与创意工坊 Mod）。
2. **安装并推送到手机/平板**：手机开启 USB 调试后运行 `python dd-steam-converter\run.py push --apk switch-runtime\TorchLauncher-v2.1.apk --pak dist\dd_assets.pak`，打开桌面「暗黑地牢 · 余烬启动器」点「开始游戏」即可。

---

## 核心特性

1. **一体化薄壳启动器 APK（`app/` + `launcher-build.py`，约 15 MB）**
   - 将「余烬档案馆」与游戏运行时合并为桌面唯一入口 **「暗黑地牢 · 余烬启动器」**（`dev.torcharchive.ddgame`）。
   - 游戏进程（`:nx`）与启动器主进程共享同一 Linux UID，彻底消除跨应用 `ContentProvider` 通信与 HyperOS `WakePathChecker` 拦截。
   - **A2-2 外置单包直读（默认）**：`PakMount.resolvePakPath()` 接管 `NxActivity.nxApk()`，直接从 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`（`ZIP_STORED`）零解压偏移读取全部游戏资源；同时兼容 A1 单包内置模式。
   - **全机型通用画面、60 / 90 / 120 Hz 高刷与高清 Mod 贴图适配（`DisplayConfig` + 纯 GOT 原生补丁 + `Textures` Hook）**：运行时自动探测任意手机、平板、折叠屏物理分辨率与面板支持刷新率，生成 4–6 组等比/高分/省电档位，支持「等比清晰 / 全屏铺满」与挖孔安全区避让；新增 **60 / 90 / 120 Hz / 自动最高（最高 165 Hz）** 高刷切换（纯 `.got` 数据页 Hook 兼容 Android 15+ SELinux，零 `.text` 改写）；同时将内置 Mod 贴图转换倍率从 `0.65×` 改为 `1.0×` 并将 `heroes/` 列入无损 PNG 白名单，消除角色 Mod 缩图发糊。
   - **自动留档与直连原子回档（`GameArchive` / `SnapshotStore`）**：每次游戏保存自动生成快照，改档/回档前自动停止 `:nx` 进程并强制先行备份。

2. **PC 端 Steam 资源转换与一键迁移（`dd-steam-converter/`）**
   - 自动扫描 Windows 注册表与 Steam `libraryfolders.vdf`，识别本体、已购 DLC、创意工坊 Mod（`262060`）和 `userdata` 存档。
   - 支持 **ASTC 4×4（DXT3 DDS 头封装，匹配 `libddnx.so`）双引擎纹理编码**：优先自动调用官方 ARM `astcenc`（放入 `dd-steam-converter/bin/` 或系统 `PATH`，画质达 51–55 dB PSNR，与原版无异）；无 `astcenc` 时自动回落至内置向量化 `Pillow + NumPy` 编码器（Void-Extent 纯色块 + `CEM=8` 3-bit 权重不透明块 + `CEM=12` 2-bit 权重透明块 + 最小二乘端点优化 + 防 Blue Contraction 约束，画质 41–44 dB PSNR）。
   - 自动保留 980 个原始 LUT/调色 `.png`、剔除 308 MB 俄语包 `overlay_ru`、处理音频兼容策略，并**100% 精确重建全部 23 份 `filelist.csv`**。
   - 输出 `dd_assets.pak`（`ZIP_STORED` + `pak-manifest.json`）与 `steam-migration.zip`（自动将存档内 `source: Steam` 转为 `source: mod`），支持 ADB 一键推送与 `--build-apk`（A1 单包导出）。

3. **游戏内容完整修改器（手机端深色余烬工坊 + 二级详情页 + PC 端配套）**
   - **全维度二进制存档修改器（`SaveEditor` / `save_editor.py`）**：
     - 顶部档位 KPI 概览卡（金币/传家宝/英雄/饰品）、2 列紧凑货币网格与 `+10万金币 / +100传家宝 / 一键全拉满` 快捷条；
     - **花名册英雄卡片与「英雄精修 / 怪癖疾病」全屏二级页**：可视化生命值与压力双进度条、职业/等级/状态徽章、姓名搜索与状态筛选（需恢复/含疾病/阵亡）；点入深色余烬二级页直接点选 Lv.0–6、武器/护甲 1–5 级、美德/折磨网格与 22 项精选正面怪癖图鉴（支持 Android 13–17 手势返回）；
     - **饰品仓库与「分类饰品图鉴」二级页**：支持按先祖/极稀有/稀有/收藏家/鸦翼/奖杯/疯狂分类筛选、搜索、已拥有数量标注与 `×1 / ×2 / ×4` 批量收入仓库；
     - 城镇 8 座建筑一键全解锁、各地牢区域等级预览与一键全区满级；
     - **全字段原始 DSON 浏览器与节点编辑二级页**：支持文件横向胶囊切换、可点击面包屑导航、字段搜索与 `int32 / float32 / bool / string` 校验写回。
   - **非破坏性游戏规则修改器（`ContentModGenerator` / `content_modifier.py`）**：
     - 内置 4 套一键预设方案（`还原原版 / 轻度舒适 / 爽快开荒 / 终极无双`）与 4 组主题卡片（背包补给、战斗输出、压力美德、城镇招募），生成独立标准覆盖 Mod `torch_content_modifier`（随时一键卸载还原）。

4. **独立 PC Steam 版存档管家 & 完整修改器（`dd-steam-toolkit/`，零第三方依赖）**
   - 将 APK 中的「存档档案柜 + 全维度修改器 + 非破坏性规则 Mod」完整拆出并深度适配 **PC 正版 Steam《暗黑地牢》**，仅需 Python 3.10+ 标准库即可双击 `启动余烬PC工坊.bat` 以独立桌面窗口运行（详见 [dd-steam-toolkit/README.md](dd-steam-toolkit/README.md)）。
   - **实时自动留档 + 原子回档**：后台线程实时监听 Steam `remote/profile_0..8` 变动并生成 SHA-256 去重校验快照，支持版本变更对比、一键原子回档、备注管理以及 Steam 与安卓余烬存档 ZIP 互导。
   - **Steam 深度适配**：写档前自动检测 `Darkest.exe` 进程状态；自动扫描本体与已购 DLC 的全部饰品/怪癖/英雄文件；安装规则 Mod `torch_content_modifier` 或调整 Mod 排序时严格保留原有创意工坊 Mod 的 `source: Steam`。

---

## 目录结构

```text
├── INDEX.md                 # 工作区总索引与全量源码文件清单
├── docs/                    # 系统架构（ARCHITECTURE）、修改器指南（MODIFIER-GUIDE）、构建测试（BUILD-AND-TEST）
├── switch-runtime/          # 私有仓库内置 Switch 运行时壳（TorchLauncher-v2.1.apk / wicked-runtime-shell.apk）与 Switch 主程序+触屏差异层（switch-diff-layer.zip）
├── app/                     # 余烬一体化启动器 Android 原创源码（含修改器 UI 与引擎层，见 app/README.md）
├── game-patch/              # libtorcharchive.so (native/commit.c)、主机端自动化单测与 v1 历史参考
├── launcher-build.py        # 薄壳 APK 组装脚本（支持 switch-runtime/wicked-runtime-shell.apk 与完整基础 APK，注入 4 处 Smali Hook 并 16KB 对齐签名）
├── dd-steam-converter/      # PC 端 Steam 资源转换器、DSON 迁移、ADB 推送与配套修改器
├── dd-steam-toolkit/        # 独立 PC Steam 版《暗黑地牢》余烬存档管家 & 完整修改器（零第三方依赖，桌面窗口 + CLI）
├── DESIGN.md                # 视觉与交互设计规范
├── PRODUCT.md               # 初始产品定位简述
└── README.md
```

---

## 快速构建与使用

### 1. 直接转换 Steam 资源、打包存档/Mod 并推送到安卓（推荐，免构建 APK）

```powershell
# 安装 Python 依赖
pip install -r dd-steam-converter\requirements.txt

# 探测本机 Steam 目录、DLC、创意工坊与存档
python dd-steam-converter\run.py detect

# 从 Steam + 仓库内置 switch-runtime\switch-diff-layer.zip 生成单文件数据包 dd_assets.pak
# （无需手动指定 --base-apk，也可用 --switch-diff / --base-apk 显式覆盖）
python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"

# 校验 dd_assets.pak 完整性
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# 打包 Steam 存档（自动 Steam->mod）与对应创意工坊 Mod
python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"

# ADB 一键安装仓库预置启动器并推送数据包
python dd-steam-converter\run.py push `
  --apk "switch-runtime\TorchLauncher-v2.1.apk" `
  --pak "dist\dd_assets.pak"

# 或启动 PC 桌面图形界面（默认自动填充 switch-runtime\switch-diff-layer.zip）
python dd-steam-converter\run.py gui
```

### 2. （可选）从源码重新构建一体化薄壳启动器 APK（约 15 MB）

```powershell
# 方式 A（免 Gradle / 免 Android SDK / 免 NDK，直接复用仓库内 9 MB 运行时壳）：
python launcher-build.py `
  "switch-runtime\wicked-runtime-shell.apk" `
  "dist\TorchLauncher-v2.1.apk" `
  --shell-apk "TorchLauncher-Shell-v2.1.0.apk" `
  --jdk "D:\DevTools\Java\jdk-21" `
  --build-tools "D:\Android\build-tools\36.0.0" `
  --apktool "D:\DevTools\apktool\apktool.jar" `
  --keystore "C:\Users\<user>\.android\debug.keystore" `
  --key-alias androiddebugkey `
  --key-password pass:android

# 方式 B（从源码全量自编，需 Android SDK 36 + JDK 21 + NDK）：
gradle --offline :app:assembleDebug :app:lintDebug
python launcher-build.py `
  "switch-runtime\wicked-runtime-shell.apk" `
  "dist\TorchLauncher-v2.1.apk" `
  --shell-apk "app\build\outputs\apk\debug\app-debug.apk" `
  --jdk "D:\DevTools\Java\jdk-21" `
  --build-tools "D:\Android\build-tools\36.0.0" `
  --ndk "D:\Android\ndk\28.2.13676358" `
  --apktool "D:\DevTools\apktool\apktool.jar" `
  --keystore "C:\Users\<user>\.android\debug.keystore" `
  --key-alias androiddebugkey `
  --key-password pass:android
```

### 3. 独立 PC Steam 版存档管家 & 完整修改器（纯 PC 玩家开箱即用）

```powershell
# 零第三方依赖，直接启动余烬 PC 桌面窗口（或双击 dd-steam-toolkit\启动余烬PC工坊.bat）
python dd-steam-toolkit\run.py

# 或仅启动后台实时自动留档守护
python dd-steam-toolkit\run.py watch
```

### 4. 自动化测试

```powershell
# Python 单元测试（Steam 转换器 + PC 独立存档管家与修改器）
python -m unittest discover -s dd-steam-converter\tests -v
python -m unittest discover -s dd-steam-toolkit\tests -v
```
