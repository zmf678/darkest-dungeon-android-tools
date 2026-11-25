# dd-steam-converter

PC 端《暗黑地牢》Steam 正版资源转换、存档与创意工坊一站式迁移、以及完整内容/存档修改器工具集（无需 Android SDK/JDK 即可执行资源转换与修改）。

## 安装依赖

```powershell
pip install -r requirements.txt
```

## 常用命令

```powershell
# 1. 自动扫描 Steam 游戏、DLC、创意工坊与存档
python run.py detect

# 2. 转换生成外置单包 dd_assets.pak（默认 DXT3 .dds，剔除俄语包，精确重建 filelist.csv）
python run.py convert --base-apk "C:\path\to\base.apk" --output "..\dist\dd_assets.pak"

# 3. 逐条核验 dd_assets.pak（ZIP_STORED + 所有 filelist.csv 字节精确匹配）
python run.py verify "..\dist\dd_assets.pak"

# 4. 一站式迁移 Steam 存档（自动 Steam -> mod）与创意工坊 Mod
python run.py migrate --output "..\dist\steam-migration.zip"

# 5. ADB 一键推送到设备
python run.py push --apk "..\dist\TorchLauncher-v2.0.apk" --pak "..\dist\dd_assets.pak"

# 6. A1 兼容模式：把薄壳启动器与 dd_assets.pak 合并为单个独立完整 APK
python run.py build-apk --shell-apk "..\dist\TorchLauncher-v2.0.apk" --pak "..\dist\dd_assets.pak" --output "..\dist\TorchLauncher-Full-A1.apk" ...

# 7. PC 端完整存档修改器（写前自动备份到 .torch-backups/）
python run.py edit-save "D:\Steam\userdata\...\profile_0" info
python run.py edit-save "D:\Steam\userdata\...\profile_0" max-resources
python run.py edit-save "D:\Steam\userdata\...\profile_0" max-heroes
python run.py edit-save "D:\Steam\userdata\...\profile_0" unlock-buildings
python run.py edit-save "D:\Steam\userdata\...\profile_0" add-trinket --trinket-id ancestors_map --amount 1

# 8. 生成通用游戏内容修改器 Mod（PC Steam 与安卓通用）
python run.py build-mod --stack-mult 10 --free-supplies --trinket-slots 4 --combat-speed 2.0 --crit-mult 2.5 --always-hit --guaranteed-retreat --virtue-chance 100 --stress-scale 0.5 --hero-hp-mult 1.5 --building-discount 50 --zip-out "..\dist\torch_content_modifier.zip"

# 9. 启动桌面 GUI
python run.py gui
```
