# 构建、自动化测试与真机部署指南

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [English README](../README.en.md) · [系统架构](ARCHITECTURE.md) · [修改器指南](MODIFIER-GUIDE.md)

> **免责声明**：本项目仅供学习参考，使用必须搭配您个人合法购买的正版 Steam《暗黑地牢》目录。

本文档整理 Windows PowerShell 环境下的 Android 启动器编译、Steam 正版资源转换核验、自动化单测以及真机 ADB 部署步骤。

---

## 1. 环境准备

- **PC Python 工具链**（日常转换、迁移与桌面修改器仅需此环境）：Python 3.10+，安装依赖：
  ```powershell
  pip install -r dd-steam-converter\requirements.txt
  ```
  - （强烈推荐）从 ARM 官方 `astc-encoder` Release 下载 `astcenc-avx2.exe` 放入 `dd-steam-converter\bin\`（或加入 `PATH`），可将贴图画质拉满至 51–55 dB PSNR；未安装时自动回落至内置 NumPy ASTC 4×4 编码器。
  - （可选）安装 `ffmpeg` 到系统 `PATH`，用于转换 Steam 过场视频 `.ogv`。
- **Android 源码编译工具**（仅修改 `app/` 源码重编时需要）：JDK 21、Android SDK（`compileSdk 36`）、Gradle。
- **正版游戏目录**：本机已安装的 Steam 正版《暗黑地牢》目录（支持自动探测，也可通过 `--steam-dir` 显式指定）。

若 Android SDK 未放在默认路径，请在当前 PowerShell 会话中先指定环境变量：

```powershell
$env:JAVA_HOME = 'D:\DevTools\Java\jdk-21'
$env:ANDROID_HOME = 'D:\path\to\android-sdk'
$env:GRADLE_USER_HOME = 'D:\path\to\gradle-home'
```

---

## 2. 编译与转换流程命令

### 2.1 编译 Android 启动器与静态检查（`:app`）

```powershell
gradle --offline :app:assembleDebug :app:lintDebug
```

输出产物位于 `app\build\outputs\apk\debug\app-debug.apk`。

### 2.2 转换 Steam 正版资源、校验与存档迁移

```powershell
# 1. 自动扫描本机正版 Steam 目录、已购 DLC、创意工坊 Mod 与存档
python dd-steam-converter\run.py detect

# 2. 从正版 Steam 资源转换生成单文件数据包 dd_assets.pak（ZIP_STORED + ASTC 4x4 + 自动重建清单）
python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"

# 3. 逐条目校验 dd_assets.pak 压缩方式与清单字节数精确匹配
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# 4. 打包 Steam 存档（自动将 Steam Mod 标识转为本地 mod）及关联创意工坊 Mod
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
- `dds_encoder` 的 ASTC 4×4 块级精确编码验证；
- `migration`、`save_editor`（钱包/英雄嵌套 DSON/饰品/建筑/原始字段）与 `content_modifier` 端到端测试。

### 3.2 Java 主机端校验（`game-patch/test/`）

可在 JDK 21 下编译 [app/src/main/java/dev/torcharchive/game/](../app/src/main/java/dev/torcharchive/game) 与测试类运行：
- [DsonCodecCheck.java](../game-patch/test/dev/torcharchive/game/DsonCodecCheck.java)：真实二进制 `persist.game.json` 无损往返、`Steam → mod` 与排序修改校验；
- [SnapshotStoreCheck.java](../game-patch/test/dev/torcharchive/game/SnapshotStoreCheck.java)：连续三次提交留档、去重抑制、多文件逐字节回档、损坏快照拒绝与中断恢复校验；
- [UnifiedEngineCheck.java](../game-patch/test/dev/torcharchive/game/UnifiedEngineCheck.java)：[DisplayConfig.java](../app/src/main/java/dev/torcharchive/game/DisplayConfig.java) 多分辨率预设、`SaveEditor` 全链路及 `ContentModGenerator` 校验。

---

## 4. 真机 ADB 部署与常用排障

### 4.1 推送数据包与迁移包

```powershell
# 推送外置数据包到默认目录 Android/media/dev.torcharchive.ddgame/dd_assets.pak
python dd-steam-converter\run.py push --pak "dist\dd_assets.pak"

# 如需同时推送迁移包到设备 Download 目录供启动器内导入：
adb push "dist\steam-migration.zip" /sdcard/Download/steam-migration.zip
```

### 4.2 常见问题排查

1. **画面比例或高刷不生效**：在启动器「画面」页修改分辨率、缩放模式或刷新率（60 / 90 / 120 Hz / 自动最高）后，请点击「保存并重启游戏」使配置在新进程中生效；部分手机需在系统「显示与亮度」中先允许应用开启高刷新率。
2. **平板角色 Mod 显得偏软**：在 16:10 高分平板上，推荐选择本机原生分辨率（如 `3008×1880`）搭配「等比清晰（原比例）」，避免 16:9 渲染帧在全屏铺满模式下经历二次竖向拉伸。
3. **PowerShell 常用坑规避**：
   - 在 PowerShell 中向 Python 传内联脚本请使用 `@' ... '@ | python -`，不要使用 Bash `<< 'PY'`；
   - 真机截屏请用 `adb shell screencap -p /sdcard/screen.png && adb pull /sdcard/screen.png .`，不要用 `adb exec-out screencap -p > screen.png`（PowerShell 重定向会把 PNG 按 UTF-16LE 破坏）。
