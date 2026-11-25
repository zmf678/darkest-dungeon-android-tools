# dd-steam-converter

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [English README](../README.en.md) · [系统架构](../docs/ARCHITECTURE.md) · [修改器指南](../docs/MODIFIER-GUIDE.md)

> **使用声明**：本工具仅供个人学习与技术研究参考，必须搭配您个人合法购买的正版 Steam《暗黑地牢》本地目录使用。

PC 端《暗黑地牢》Steam 正版资源转换、存档与创意工坊一站式迁移、以及完整内容/存档修改器工具集（无需安装 Android SDK/JDK 即可执行资源转换与修改）。

## 安装依赖

```powershell
pip install -r requirements.txt
```

> **推荐画质增强（可选）**：从 [ARM-software/astc-encoder](https://github.com/ARM-software/astc-encoder/releases) 下载对应平台的 `astcenc-avx2.exe`（或 `astcenc`）放入 `dd-steam-converter/bin/` 或加入系统 `PATH`。转换器会自动发现并优先使用官方 `astcenc` 编码（51–55 dB PSNR，接近无损）；未安装时自动回落至内置 NumPy ASTC 4×4 编码器（41–44 dB PSNR）。

## 模块结构

| 模块文件 | 用途说明 |
| :--- | :--- |
| [run.py](run.py) | CLI / GUI 统一入口脚本 |
| [steam_detect.py](dd_converter/steam_detect.py) | Windows 注册表与 `libraryfolders.vdf` 扫描（本体、已购 DLC、创意工坊 `262060`、`userdata`） |
| [dson.py](dd_converter/dson.py) | 无损二进制 DSON（`0x0000B101`）解析/序列化与 Red Hook 哈希 `dd_hash()` |
| [dds_encoder.py](dd_converter/dds_encoder.py) | 双引擎 ASTC 4×4 编码器（优先 ARM `astcenc`，内置 NumPy 向量化编码回落） |
| [pak_builder.py](dd_converter/pak_builder.py) | 生成及逐条校验 `dd_assets.pak`（`ZIP_STORED`、保留 LUT PNG、精确重建清单） |
| [migration.py](dd_converter/migration.py) | 打包 Steam 存档（`Steam → mod`）与创意工坊 Mod 为 `steam-migration.zip` |
| [adb_push.py](dd_converter/adb_push.py) | ADB 设备发现、安装启动器 APK、推送 `dd_assets.pak` 与 Mod 目录 |
| [apk_bundler.py](dd_converter/apk_bundler.py) | A1 模式：将薄壳 APK 与 `dd_assets.pak` 合并为单 APK 并执行 16 KB 对齐签名 |
| [save_editor.py](dd_converter/save_editor.py) | 完整二进制 DSON 存档修改器（写前自动备份到 `.torch-backups/`） |
| [content_modifier.py](dd_converter/content_modifier.py) | 非破坏性内容规则覆盖 Mod（`torch_content_modifier`）生成器 |
| [cli.py](dd_converter/cli.py) / [gui.py](dd_converter/gui.py) | 命令行调度与余烬主题桌面图形界面 |
| [tests/test_converter.py](tests/test_converter.py) | Python 自动化单元测试与真实存档回归集 |

## 常用命令

```powershell
# 1. 自动扫描本机正版 Steam 游戏、DLC、创意工坊与存档
python run.py detect

# 2. 转换生成外置单包 dd_assets.pak（ASTC 4×4 压缩纹理 + 保留 LUT 原图 + 自动重建清单）
python run.py convert --output "..\dist\dd_assets.pak"

# 3. 逐条核验 dd_assets.pak 完整性与字节精确匹配
python run.py verify "..\dist\dd_assets.pak"

# 4. 一站式迁移 Steam 存档（自动 Steam -> mod）与创意工坊 Mod
python run.py migrate --output "..\dist\steam-migration.zip"

# 5. ADB 一键推送到设备
python run.py push --pak "..\dist\dd_assets.pak"

# 6. PC 端完整存档修改器（写前自动备份到 .torch-backups/）
python run.py edit-save "D:\Steam\userdata\...\profile_0" info
python run.py edit-save "D:\Steam\userdata\...\profile_0" max-resources
python run.py edit-save "D:\Steam\userdata\...\profile_0" max-heroes
python run.py edit-save "D:\Steam\userdata\...\profile_0" unlock-buildings
python run.py edit-save "D:\Steam\userdata\...\profile_0" add-trinket --trinket-id ancestors_map --amount 1

# 7. 生成通用游戏内容修改器 Mod（PC Steam 与安卓通用）
python run.py build-mod --stack-mult 10 --free-supplies --trinket-slots 4 --combat-speed 2.0 --crit-mult 2.5 --always-hit --guaranteed-retreat --virtue-chance 100 --stress-scale 0.5 --hero-hp-mult 1.5 --building-discount 50 --zip-out "..\dist\torch_content_modifier.zip"

# 8. 启动桌面 GUI
python run.py gui
```
