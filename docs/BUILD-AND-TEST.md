# 构建、自动化测试与真机部署指南

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [系统架构](ARCHITECTURE.md) · [修改器指南](MODIFIER-GUIDE.md)

本文档整理 Windows PowerShell 环境下的完整构建命令、Python 与 Java 主机验证测试套件、真机 ADB 部署步骤及常见排障要点。

---

## 1. 环境准备

- **Android 构建工具**：JDK 21、Android SDK（`compileSdk 36`）、Build Tools `36.0.0`、Android NDK r28、Gradle、`apktool.jar`
- **PC Python 工具链**：Python 3.10+，安装依赖：
  ```powershell
  pip install -r dd-steam-converter\requirements.txt
  ```
  - （强烈推荐）从 ARM 官方 `astc-encoder` Release 下载 `astcenc-avx2.exe` 放入 `dd-steam-converter\bin\`（或加入 `PATH`），可将纹理转换质量提升至 51–55 dB PSNR（与原版一致）；未安装时自动使用内置 NumPy ASTC 4×4 编码器。
- **BYO 输入准备**：
  - 本机正版 Steam《暗黑地牢》安装目录（支持自动探测，也可通过 `--steam-dir` 指定）；
  - 本机自备的匹配 Wicked 2.2.2 基础 APK（[launcher-build.py](../launcher-build.py) 会校验其 SHA-256）。

若 Android SDK 未放在默认路径，请在当前 PowerShell 会话中先显式指定环境变量：

```powershell
$env:JAVA_HOME = 'D:\DevTools\Java\jdk-21'
$env:ANDROID_HOME = 'D:\path\to\android-sdk'
$env:GRADLE_USER_HOME = 'D:\path\to\gradle-home'
```

---

## 2. 构建与转换全流程命令

### 2.1 编译一体化启动器外壳（`:app`）

```powershell
gradle --offline :app:assembleDebug :app:lintDebug
```

输出产物位于 `app\build\outputs\apk\debug\app-debug.apk`。

### 2.2 组装薄壳启动器 APK（A2-2 模式，约 15 MB）

若直接下载 GitHub Release 提供的 `TorchLauncher-Shell-v2.0.0.apk`（已内置本仓库原创 7.5 KB 的 `libtorcharchive.so`），可省略 `--ndk` 与上面的 Gradle 步骤，直接运行：

```powershell
python launcher-build.py `
  "C:\path\to\base-dd.apk" `
  "dist\TorchLauncher-v2.0.apk" `
  --shell-apk "TorchLauncher-Shell-v2.0.0.apk" `
  --jdk "D:\DevTools\Java\jdk-21" `
  --build-tools "D:\path\to\android-sdk\build-tools\36.0.0" `
  --apktool "D:\path\to\apktool.jar" `
  --keystore "C:\Users\<user>\.android\debug.keystore" `
  --key-alias androiddebugkey `
  --key-password pass:android
```

如需从源码重新编译 `libtorcharchive.so`（搭配本地 `:app:assembleDebug` 产物），追加 `--ndk` 即可：

```powershell
python launcher-build.py `
  "C:\path\to\base-dd.apk" `
  "dist\TorchLauncher-v2.0.apk" `
  --shell-apk "app\build\outputs\apk\debug\app-debug.apk" `
  --jdk "D:\DevTools\Java\jdk-21" `
  --build-tools "D:\path\to\android-sdk\build-tools\36.0.0" `
  --ndk "D:\path\to\android-sdk\ndk\28.2.13676358" `
  --apktool "D:\path\to\apktool.jar" `
  --keystore "C:\Users\<user>\.android\debug.keystore" `
  --key-alias androiddebugkey `
  --key-password pass:android
```

> 若需构建可直接安装的 **A1 整包 APK**，可追加 `--bundle-pak dist\dd_assets.pak`，或对已有薄壳 APK 运行 `python dd-steam-converter\run.py build-apk ...`。

### 2.3 转换 Steam 资源、校验与存档迁移

```powershell
# 1. 自动扫描 Steam 目录、DLC、创意工坊与存档
python dd-steam-converter\run.py detect

# 2. 转换生成单文件数据包 dd_assets.pak（ZIP_STORED + ASTC 4x4 DDS + 重建 filelist.csv）
python dd-steam-converter\run.py convert `
  --base-apk "C:\path\to\base-dd.apk" `
  --output "dist\dd_assets.pak"

# 3. 逐条目校验 dd_assets.pak 压缩方式与所有 filelist.csv 字节精确匹配
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# 4. 打包 Steam 存档（自动转换 Steam -> mod）及关联创意工坊 Mod
python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"
```

---

## 3. 自动化测试与校验套件

### 3.1 Python 单元测试（[test_converter.py](../dd-steam-converter/tests/test_converter.py)）

```powershell
python -m unittest discover -s dd-steam-converter\tests -v
```

覆盖范围：
- Red Hook `dd_hash()` 哈希与已知建筑升级树 ID 精确匹配；
- 当本机存在 Steam `profile_0` 时，执行全目录二进制 DSON 无损往返校验与 `Steam → mod` 转换校验；
- `dds_encoder` 的 ASTC 4×4 + DXT3 DDS 头块级精确编码验证；
- `migration`、`save_editor`（钱包/英雄嵌套 DSON/饰品/建筑/原始字段）与 `content_modifier` 端到端测试。

### 3.2 Java 主机端校验（`game-patch/test/`）

可在 JDK 21 下编译 [app/src/main/java/dev/torcharchive/game/](../app/src/main/java/dev/torcharchive/game) 与测试类运行：
- [DsonCodecCheck.java](../game-patch/test/dev/torcharchive/game/DsonCodecCheck.java)：真实二进制 `persist.game.json` 无损往返、`Steam → mod` 与排序修改校验；
- [SnapshotStoreCheck.java](../game-patch/test/dev/torcharchive/game/SnapshotStoreCheck.java)：连续三次提交留档、去重抑制、多文件逐字节回档、损坏快照拒绝与中断恢复校验；
- [UnifiedEngineCheck.java](../game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java)：[DisplayConfig.java](../app/src/main/java/dev/torcharchive/game/DisplayConfig.java) 多分辨率预设、`SaveEditor` 全链路及 `ContentModGenerator` 校验。

> 注：由于主机端没有 Android 运行时 `android.jar` 的完整实现，编译主机检查时需配合最小接口桩或从已编译 Android 类路径提供 `Context`、`SharedPreferences` 等桩类；`ContentModGenerator.generateMod()` 与 `SaveEditor` 均使用纯 `Path` 与标准 Java 序列化以保证主机可测。

---

## 4. 真机 ADB 部署与常见排障

### 4.1 推送安装（推荐命令）

```powershell
python dd-steam-converter\run.py push `
  --apk "dist\TorchLauncher-v2.0.apk" `
  --pak "dist\dd_assets.pak"

# 如需同时推送迁移包到手机 Download 目录供启动器内导入：
adb push "dist\steam-migration.zip" /sdcard/Download/steam-migration.zip
```

### 4.2 真机验证关键检查点（以 Android 17 真机 `1440×3168` 实测为准）

1. **启动器主界面与状态卡片**：打开「暗黑地牢 · 余烬启动器」，确认「启动」页显示 `dd_assets.pak` 已挂载（例如 11,874 文件）、当前适配分辨率已探测到位，且竖屏工具栏标题不折行；
2. **MultiDex 类加载完整**：进入游戏进程（`:nx`）无 `ClassNotFoundException`（若发生此类报错，核查 [launcher-build.py](../launcher-build.py) 是否保留了壳的 `classes2.dex`..`classesN.dex` 并将游戏 dex 放在 `classes<N+1>.dex`）；
3. **纹理与主菜单渲染正常**：游戏主菜单、标题与按钮图像清晰锐利，无黑屏或彩色方块噪点（若出现花屏，请确认 `dd_assets.pak` 使用最新版 `dds_encoder.py` 的 ASTC 4×4 编码而非普通 DXT3 块，且保留了 `colour_grade` / `tint.png` 原始 PNG）；
4. **PowerShell 常用坑规避**：
   - 在 PowerShell 中向 Python 传内联脚本请使用 `@' ... '@ | python -`，不要使用 Bash `<< 'PY'`；
   - 真机截屏请用 `adb shell screencap -p /sdcard/screen.png && adb pull /sdcard/screen.png .`，不要用 `adb exec-out screencap -p > screen.png`（PowerShell 重定向会把 PNG 按 UTF-16LE 破坏）。
