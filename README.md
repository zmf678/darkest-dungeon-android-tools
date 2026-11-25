# Darkest Dungeon Android Tools — 《暗黑地牢》余烬一体化启动器 & Steam 伴侣工具链 (v2.3)

**简体中文** | [English](README.en.md)

> [!IMPORTANT]
> **免责声明与正版使用须知（使用前必读）**
>
> 1. **仅供学习与研究参考**：本项目及所含全部源码、脚本与文档仅面向个人技术学习、跨平台适配研究与编程交流用途，不提供任何形式的商业支持或担保。
> 2. **必须搭配已购买的正版 Steam 游戏使用**：使用本工具链前，您**必须自行通过官方渠道合法购买并安装正版 Steam 版《暗黑地牢》（*Darkest Dungeon*）及对应 DLC**，并在本地指向您个人已购的正版 Steam 目录进行资源转换、存档管理与 Mod 迁移。
> 3. **版权归属声明**：本项目为社区个人开源伴侣工具，与 Red Hook Studios、Valve/Steam 及任何游戏发行方无任何官方关联或授权关系。《暗黑地牢》的游戏名称、商标、美术、音频、文本与数据版权均归原权利人所有。
> 4. **禁止商业与违规用途**：严禁将本项目或其转换生成的任何文件用于商业售卖、付费打包、绕过正版验证或未经授权的公开再分发；因违反相关法律法规或软件许可协议所引发的一切责任，均由使用者自行承担。
> 5. **合规配合**：如版权方对本仓库中的任何内容存在异议，欢迎通过 Issue 联系仓库维护者，我们将在核实后第一时间配合修改或下架。

> **文档导航**：[English README](README.en.md) · [工作区总索引 (INDEX.md)](INDEX.md) · [独立 PC Steam 存档与修改器 (dd-steam-toolkit)](dd-steam-toolkit/README.md) · [系统架构说明](docs/ARCHITECTURE.md) · [完整修改器指南](docs/MODIFIER-GUIDE.md) · [构建测试与部署](docs/BUILD-AND-TEST.md) · [Android 启动器模块](app/README.md) · [PC 转换器](dd-steam-converter/README.md) · [单测与工具集](game-patch/README.md)

---

## 使用前准备与快速上手

### 前置条件
1. **个人已合法购买并安装的 Steam 正版《暗黑地牢》目录**（用于读取本体与已购 DLC 资源、创意工坊 Mod 及个人存档）。
2. **PC 端 Python 3.10+ 环境**：
   - 安装依赖：`pip install -r dd-steam-converter\requirements.txt`；
   - （强烈推荐）从 [ARM astc-encoder Releases](https://github.com/ARM-software/astc-encoder/releases) 下载 `astcenc-avx2.exe`（或 `astcenc`）放入 `dd-steam-converter\bin\` 或系统 `PATH`，可将贴图压缩画质提升至 51–55 dB PSNR（接近无损）；
   - （可选）在系统 `PATH` 中安装 `ffmpeg`，用于将 Steam 过场视频 `.ogv` 转码为移动端兼容格式。
3. **（可选）Android 源码构建环境**：仅当您需要自行修改并编译 `app/` 启动器源码时，才需要 JDK 21、Android SDK 36 与 Gradle。

### 快速使用流程
1. **探测并转换个人正版 Steam 资源**：
   ```powershell
   python dd-steam-converter\run.py detect
   python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"
   python dd-steam-converter\run.py verify "dist\dd_assets.pak"
   ```
2. **（可选）打包个人 Steam 存档与创意工坊 Mod**：
   ```powershell
   python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"
   ```
3. **部署到安卓手机 / 平板**：开启 USB 调试后通过 ADB 推送数据包，或将 `dd_assets.pak` 与 `steam-migration.zip` 拷贝到设备并在启动器内导入，打开桌面「暗黑地牢 · 余烬启动器」即可使用。

---

## 核心特性

### 1. 余烬一体化安卓启动器（`app/`）
- **单应用同 UID 架构**：将启动管理界面、存档快照、Mod 管理与画面设置整合进桌面唯一入口 **「暗黑地牢 · 余烬启动器」**（`dev.torcharchive.ddgame`），彻底规避跨应用通信限制；任何写档、回档或修改 Mod 配置前均会自动安全停机并先行留档。
- **外置单包直读（A2-2）**：支持从 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`（`ZIP_STORED`）零解压直接挂载本地转换好的 Steam 资源包，日常更新启动器无需反复重传数 GB 资源。
- **全机型分辨率与 60 / 90 / 120 Hz 高刷适配（`DisplayConfig`）**：
  - 自动探测手机、平板与折叠屏物理分辨率及面板支持刷新率（最高支持至 165 Hz），动态生成 4–6 组推荐分辨率档位；
  - 支持「等比清晰 / 全屏铺满」双缩放模式、挖孔安全区避让，以及 **60 Hz / 90 Hz / 120 Hz / 自动最高** 刷新率切换；
  - 针对 `> 1.85:1` 超宽屏手机默认优先推荐无拉伸 16:9 清晰档位，16:10 平板保留原生全屏比例。
- **自动留档与原子回档（`SnapshotStore`）**：每次游戏保存自动生成时间线快照，支持随时一键原子恢复任意历史版本并自动保护当前进度。
- **联网模组工坊**：在「模组 → 在线工坊」浏览《暗黑地牢》Steam 创意工坊，按热度、评分或发布时间查看，可搜索名称、简介或粘贴条目链接。条目有公开 ZIP 时可下载到本地模组库，也可以在「模组 → 公开 ZIP」粘贴作者提供的 HTTPS 下载链接。下载后可以查看包内文件并安装。

Steam 条目只提供订阅下载时，在电脑 Steam 订阅后，将 `steamapps/workshop/content/262060/<条目ID>` 文件夹整体压缩为 ZIP，再在安卓「本地模组」导入。需要同时迁移存档时可使用 Steam 迁移包。安装后在「档位模组配置」启用所需模组。

### 2. PC 端 Steam 资源转换与迁移工具链（`dd-steam-converter/`）
- **自动探测本机 Steam 环境**：读取 Windows 注册表与 `libraryfolders.vdf`，自动识别《暗黑地牢》本体、已购 DLC、创意工坊 Mod（AppID `262060`）与 `userdata` 存档。
- **双引擎高画质 ASTC 4×4 纹理管线**：优先自动调用官方 ARM `astcenc`（51–55 dB PSNR）；未安装时平滑回落至内置向量化 `Pillow + NumPy` ASTC 4×4 编码器（41–44 dB PSNR），保留色彩查找表（LUT）原图并精确重建全部资源清单与校验和。
- **Steam 存档与创意工坊一键迁移**：内置无损二进制 DSON 编解码器，打包存档时自动将创意工坊 Mod 标识 `source: Steam` 映射为本地 `source: mod`，保持原有加载优先级；支持 CLI 与余烬主题桌面 GUI。

### 3. 双端完整修改器（手机端深色余烬工坊 + 二级详情页 & PC 端配套）
- **全维度二进制存档修改器（`SaveEditor` / `save_editor.py`）**：
  - **庄园与货币**：顶部档位 KPI 概览卡、难度直切、2 列紧凑货币网格与 `+10万金币 / +100全传家宝 / 一键拉满` 快捷条；
  - **花名册与「英雄精修 / 怪癖疾病」全屏二级页**：可视化生命/压力双进度条、职业/等级/状态彩色徽章、搜索与状态筛选（需恢复 / 含疾病 / 阵亡）；二级页支持 `Lv.0–6` 与武器/护甲 `1–5 级` 直接点选、美德/折磨 3 列网格、22 项精选正面怪癖图鉴搜索锁定与一键清病，原生适配 Android 13–17 手势返回；
  - **饰品仓库与「分类饰品图鉴」二级页**：支持仓库检索与 `+1` 快捷复制；图鉴页按 11 类流派筛选、搜索、展示已有数量并支持 `×1 / ×2 / ×4` 批量收入；
  - **建筑与地牢进度**：城镇 8 座建筑一键全解锁、6 大区域等级预览与一键满级；
  - **全字段原始 DSON 浏览器与节点编辑二级页**：支持 `persist.*.json` 横向胶囊切换、可点击面包屑导航、字段搜索与 `int32 / float32 / bool / string` 往返校验写入。
- **非破坏性游戏规则修改器（`ContentModGenerator` / `content_modifier.py`）**：
  - 内置 4 套一键平衡预设（`还原原版 / 轻度舒适 / 爽快开荒 / 终极无双`）与 4 组主题卡片（背包补给、战斗节奏、压力美德、城镇招募），生成独立标准覆盖 Mod `torch_content_modifier`（PC Steam 与安卓通用，随时一键卸载还原）。

### 4. 独立 PC Steam 版存档管家 & 完整修改器（`dd-steam-toolkit/`，纯 PC 玩家独立可用）
> 若您**仅游玩 PC Steam 版《暗黑地牢》**、不需要安卓转换与部署功能，可直接单独使用 [dd-steam-toolkit/](dd-steam-toolkit/README.md)（[English Guide](dd-steam-toolkit/README.en.md)）。

- **零第三方依赖、双击即开**：纯 Python 3.10+ 标准库驱动，无需 `pip install` 任何外部库，双击 `dd-steam-toolkit\启动余烬PC工坊.bat` 或执行 `python dd-steam-toolkit\run.py` 即可通过系统内置 Edge / Chrome 以**独立桌面应用窗口**运行，亦支持完整 CLI 与可选单文件 `.exe` 打包（`build_exe.py`）。
- **实时自动留档 & 原子事务回档**：后台线程实时监听 Steam `userdata/<UID>/262060/remote/profile_0..8` 变动，每次游戏保存自动生成 SHA-256 去重校验快照；支持变更 Diff 对比、备注管理、一键原子回档（回档前强制备份现役存档），以及 Steam ↔ 安卓余烬存档 ZIP 互导（导回 PC 时可自动将本机已订阅工坊 ID 还原为 `source: Steam`）。
- **Steam 深度适配的完整修改器 & 模组排序**：
  - 写档/回档前自动检测 `Darkest.exe` 进程状态并给出 Steam Cloud 同步提示，防止内存回写或云存档覆盖；
  - 完整复刻手机端 5 大精修页签（庄园货币、花名册英雄精修与怪癖疾病、分类饰品图鉴、城镇 8 座建筑与 6 大地牢区域、支持穿透英雄嵌套 DSON 的全字段原始浏览器），并自动索引本机 Steam 本体与全部已购 DLC 的饰品与怪癖库；
  - 生成规则覆写 Mod `torch_content_modifier`（同步覆盖本体与已购 DLC 英雄数值及建筑升级折扣）或调整档位 Mod 排序时，**严格保留原有创意工坊 Mod 的 `source: Steam`**，支持一键卸载恢复原版。

---

## 目录结构

```text
├── README.md                 # 项目说明（简体中文）
├── README.en.md              # Project README (English)
├── INDEX.md                  # 工作区总索引与源码清单
├── docs/                     # 系统架构（ARCHITECTURE）、修改器指南（MODIFIER-GUIDE）、构建测试（BUILD-AND-TEST）
├── app/                      # 余烬一体化启动器 Android 原创源码（含修改器 UI 与引擎层，见 app/README.md）
├── dd-steam-converter/       # PC 端 Steam 正版资源转换、存档/Mod 迁移、ADB 推送与桌面修改器
├── dd-steam-toolkit/         # 独立 PC Steam 存档管家、实时自动快照、Mod 排序与余烬桌面修改器（零第三方依赖）
├── game-patch/               # Java 主机端自动化测试套件与历史参考模块
├── DESIGN.md                 # 余烬档案馆视觉与交互设计规范
└── PRODUCT.md                # 产品定位简述
```

---

## 常用命令速查

### PC 端转换、迁移与桌面修改器

```powershell
# 安装 Python 依赖
pip install -r dd-steam-converter\requirements.txt

# 探测本机正版 Steam 目录、DLC、创意工坊与存档
python dd-steam-converter\run.py detect

# 从本机正版 Steam 目录生成单文件资源包 dd_assets.pak
python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"

# 校验 dd_assets.pak 完整性
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# 打包 Steam 存档（自动转换本地 Mod 标识）与创意工坊 Mod
python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"

# ADB 一键推送到安卓设备
python dd-steam-converter\run.py push --pak "dist\dd_assets.pak"

# 启动 PC 端余烬主题桌面图形界面（转换 / 迁移 / 存档修改 / 规则修改）
python dd-steam-converter\run.py gui
```

### 独立 PC Steam 版存档管家 & 完整修改器（无需安装第三方依赖）

```powershell
# 启动余烬 PC 独立桌面窗口（Windows 亦可直接双击 dd-steam-toolkit\启动余烬PC工坊.bat）
python dd-steam-toolkit\run.py

# 仅在后台运行 Steam 存档实时自动留档守护
python dd-steam-toolkit\run.py watch

# 探测 Steam 环境、查看档位、手动留档或套用规则预设
python dd-steam-toolkit\run.py detect
python dd-steam-toolkit\run.py snapshot backup --slot profile_0 --note "进极暗地牢前"
python dd-steam-toolkit\run.py build-mod --preset adventurer --install-steam --enable-slot profile_0
```

### Android 启动器编译与自动化测试

```powershell
# 编译 Android 启动器工程并执行静态检查（需 JDK 21 + Android SDK 36）
gradle --offline :app:assembleDebug :app:lintDebug

# 运行 Python 单元测试（Steam 转安卓工具链 + 独立 PC Steam 存档与修改器）
python -m unittest discover -s dd-steam-converter\tests -v
python -m unittest discover -s dd-steam-toolkit\tests -v
```
