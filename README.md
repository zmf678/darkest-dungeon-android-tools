# Darkest Dungeon Android Tools — 《暗黑地牢》余烬一体化启动器 & Steam 转安卓工具链 (v2.0)

> **开源与版权合规声明（BYO 模式）**：本仓库（`darkest-dungeon-android-tools`）**不包含任何第三方或受版权保护的游戏二进制文件、APK、`.so` 原生库、`exefs/main` 或游戏资源**。所有工具均需在本地指向您个人合法购买的正版 Steam《暗黑地牢》目录及本机自备的基础运行壳 APK。

> **文档与索引导航**：[工作区总索引 (INDEX.md)](INDEX.md) · [系统架构与纹理规范](docs/ARCHITECTURE.md) · [完整修改器指南](docs/MODIFIER-GUIDE.md) · [构建测试与排障](docs/BUILD-AND-TEST.md) · [Android 启动器模块](app/README.md) · [PC 转换器](dd-steam-converter/README.md) · [原生 Hook 与单测](game-patch/README.md)

---

## 核心特性

1. **一体化薄壳启动器 APK（`app/` + `launcher-build.py`，约 15 MB）**
   - 将「余烬档案馆」与游戏运行时合并为桌面唯一入口 **「暗黑地牢 · 余烬启动器」**（`dev.torcharchive.ddgame`）。
   - 游戏进程（`:nx`）与启动器主进程共享同一 Linux UID，彻底消除跨应用 `ContentProvider` 通信与 HyperOS `WakePathChecker` 拦截。
   - **A2-2 外置单包直读（默认）**：`PakMount.resolvePakPath()` 接管 `NxActivity.nxApk()`，直接从 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`（`ZIP_STORED`）零解压偏移读取全部游戏资源；同时兼容 A1 单包内置模式。
   - **全机型通用画面适配（`DisplayConfig`）**：运行时自动探测任意手机、平板、折叠屏物理分辨率，生成 4–6 组等比/高分/省电档位，支持「等比清晰 / 全屏铺满」与挖孔安全区避让；针对 `> 1.85:1` 超宽屏手机（如 20:9 / 22:9）默认自动回落本机高度对齐的 16:9 无拉伸分辨率并置于推荐首位，16:10 平板保留原生全屏。
   - **自动留档与直连原子回档（`GameArchive` / `SnapshotStore`）**：每次游戏保存自动生成快照，改档/回档前自动停止 `:nx` 进程并强制先行备份。

2. **PC 端 Steam 资源转换与一键迁移（`dd-steam-converter/`）**
   - 自动扫描 Windows 注册表与 Steam `libraryfolders.vdf`，识别本体、已购 DLC、创意工坊 Mod（`262060`）和 `userdata` 存档。
   - 支持 **ASTC 4×4（DXT3 DDS 头封装，匹配 `libddnx.so`）双引擎纹理编码**：优先自动调用官方 ARM `astcenc`（放入 `dd-steam-converter/bin/` 或系统 `PATH`，画质达 51–55 dB PSNR，与原版无异）；无 `astcenc` 时自动回落至内置向量化 `Pillow + NumPy` 编码器（Void-Extent 纯色块 + `CEM=8` 3-bit 权重不透明块 + `CEM=12` 2-bit 权重透明块 + 最小二乘端点优化 + 防 Blue Contraction 约束，画质 41–44 dB PSNR）。
   - 自动保留 980 个原始 LUT/调色 `.png`、剔除 308 MB 俄语包 `overlay_ru`、处理音频兼容策略，并**100% 精确重建全部 23 份 `filelist.csv`**。
   - 输出 `dd_assets.pak`（`ZIP_STORED` + `pak-manifest.json`）与 `steam-migration.zip`（自动将存档内 `source: Steam` 转为 `source: mod`），支持 ADB 一键推送与 `--build-apk`（A1 单包导出）。

3. **游戏内容完整修改器（手机端内置 + PC 端配套）**
   - **全维度二进制存档修改器（`SaveEditor` / `save_editor.py`）**：
     - 庄园名称、游戏模式（`radiant` / `darkest` / `new_game_plus`）、金币/传家宝/彗星碎片/建筑蓝图编辑与一键拉满；
     - **全英雄嵌套 DSON 编辑**：读取与修改 `persist.roster.json` 内每个英雄的嵌入式二进制 DSON（名称、当前生命、压力值、决心等级 XP、武器/护甲等级、美德/折磨、复活阵亡英雄、怪癖锁定/添加与疾病清除）；
     - 饰品仓库自由增删（内置全流派先祖/极稀有/星晶/boss 奖杯图鉴或自定义 ID）；
     - 城镇全部 8 座建筑升级一键全解锁（`persist.upgrades.json`）与各地牢区域 XP 修改；
     - **全字段原始 DSON 树状编辑器**：可浏览并修改任意 `persist.*.json` 任意标量字段，所有写档均强制先拍快照并通过往返结构自检。
   - **非破坏性游戏规则修改器（`ContentModGenerator` / `content_modifier.py`）**：
     - 生成独立标准覆盖 Mod `torch_content_modifier`（PC Steam 与安卓通用，随时一键卸载还原）：
       - 金币/传家宝/宝石/补给叠加上限倍率（1x–99x）与补给品免费；
       - 英雄饰品装备槽位扩展（2–4 格）与远征背包格扩展（16–24 格）；
       - 战斗动画倍速（1.0x–3.0x）、暴击倍率、解除 95% 命中上限、100% 无损撤退、美德触发概率、受压倍率、每周回压与极速升级；
       - 马车招募数与花名册上限扩容；PC 端还支持全英雄基础 HP/伤害/速度/闪避倍率与建筑升级折扣。

---

## 目录结构

```text
├── INDEX.md                 # 工作区总索引与全量源码文件清单
├── docs/                    # 系统架构（ARCHITECTURE）、修改器指南（MODIFIER-GUIDE）、构建测试（BUILD-AND-TEST）
├── app/                     # 余烬一体化启动器 Android 原创源码（含修改器 UI 与引擎层，见 app/README.md）
├── game-patch/              # libtorcharchive.so (native/commit.c)、主机端自动化单测与 v1 历史参考
├── launcher-build.py        # 薄壳 APK 组装脚本（提取壳 + 4 处最小 Smali Hook + 16KB 对齐签名）
├── dd-steam-converter/      # PC 端 Steam 资源转换器、DSON 迁移、ADB 推送与完整桌面修改器
├── DESIGN.md                # 视觉与交互设计规范
├── PRODUCT.md               # 初始产品定位简述
└── README.md
```

---

## 快速构建与使用

### 1. 构建一体化薄壳启动器 APK（约 15 MB）

```powershell
# 1) 编译启动器外壳（需要 Android SDK 36 + JDK 21）
gradle --offline :app:assembleDebug :app:lintDebug

# 2) 组装薄壳 APK（指向您本机自备的基础运行壳 APK）
python launcher-build.py `
  "C:\path\to\base-dd.apk" `
  "dist\TorchLauncher-v2.0.apk" `
  --shell-apk "app\build\outputs\apk\debug\app-debug.apk" `
  --jdk "D:\DevTools\Java\jdk-21" `
  --build-tools "D:\Android\build-tools\36.0.0" `
  --ndk "D:\Android\ndk\28.2.13676358" `
  --apktool "D:\DevTools\apktool\apktool.jar" `
  --keystore "C:\Users\36134\.android\debug.keystore" `
  --key-alias androiddebugkey `
  --key-password pass:android
```

### 2. 转换 Steam 资源、打包存档/Mod 并推送到安卓

```powershell
# 安装 Python 依赖
pip install -r dd-steam-converter\requirements.txt

# 探测本机 Steam 目录、DLC、创意工坊与存档
python dd-steam-converter\run.py detect

# 从 Steam 生成单文件数据包 dd_assets.pak（ZIP_STORED + 自动重建 filelist.csv）
python dd-steam-converter\run.py convert `
  --base-apk "C:\path\to\base-dd.apk" `
  --output "dist\dd_assets.pak"

# 校验 dd_assets.pak 完整性
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# 打包 Steam 存档（自动 Steam->mod）与对应创意工坊 Mod
python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"

# ADB 一键安装启动器并推送数据包（也可把 dd_assets.pak 放手机 Android/media/dev.torcharchive.ddgame/ 后在启动器内导入）
python dd-steam-converter\run.py push --apk "dist\TorchLauncher-v2.0.apk" --pak "dist\dd_assets.pak"

# 或启动 PC 桌面图形界面（含转换、迁移、完整存档修改器、游戏内容修改器）
python dd-steam-converter\run.py gui
```

### 3. 自动化测试

```powershell
# Python 单元测试（DSON 往返、ASTC 4x4 DDS 编码、迁移、存档修改器、内容修改器）
python -m unittest discover -s dd-steam-converter\tests -v
```
