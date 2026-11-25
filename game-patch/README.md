# 《暗黑地牢》自动存档、平板分辨率与模组直连实验版

实验版以本机 Wicked Android 2.2.2 APK 为输入，通过独立包名（`dev.torcharchive.ddgame`）安装，并配合同签名「余烬档案馆」实验版（`dev.torcharchive.dd.lab`）提供三项扩展能力：

1. **保存即自动留档**：游戏完成存档提交及临时文件收尾后，补丁同步捕获发生变化的完整档位，使用原始文件字节生成 ZIP，通过签名保护的本机 `ContentProvider` 供档案馆查看和回档。
2. **平板分辨率与全屏适配（`2.2.2-archive.2+`）**：
   - 接管 `NxActivity.resolution()` 与 `NxActivity$LayerView.onMeasure()`，放宽 16:9 强校验，扩展游戏内选项 `options.value_definitions.json` 至 6 档（`1280×720`、`1920×1080`、`2560×1440`、`3008×1692`、`2560×1600`、`3008×1880`），同时严格保持 `ZIP_STORED` 与原 `filelist.csv` 字节长度一致。
   - 支持「等比清晰（原比例）」与「全屏铺满（去黑边）」两种缩放模式，可在档案馆「画面」页一键切换，重启游戏生效。
3. **本地模组直连管理（`2.2.2-archive.2+`）**：
   - 扩展 `ArchiveProvider` 支持直接列出、流式解压安装、删除 `Android/media/dev.torcharchive.ddgame/mods/` 下的 Mod。
   - 内置无损 Red Hook 二进制 DSON 编解码器（`DsonCodec.java`），支持读取并修改各档位 `persist.game.json` 的 `applied_ugcs_1_0`（启用状态、加载顺序与 Steam→本地 `mod` 映射转换），写入前强制触发 `SnapshotStore.backup()` 留档保护。

## 安装与迁移

1. 安装游戏实验版 `dev.torcharchive.ddgame`，再安装同一证书签名的余烬档案馆实验版 `dev.torcharchive.dd.lab`。两个实验包可以与已有游戏及档案馆并行安装。
2. 如需迁移原版或 PC Steam 存档：在游戏内选择 **存档与模组 → 存档 → 导入** 选择 `profile_N` 目录并指定目标档位。若导入的是 PC Steam 存档，可直接在档案馆「模组 → 档位模组配置」中保存，一键完成 `source: Steam → mod` 本地映射。
3. 回档、修改档位 Mod 或切换分辨率后，完全退出实验版游戏并重新打开即可生效。

实验版模组目录为 `Android/media/dev.torcharchive.ddgame/mods`。

## 本地复现

在 Windows 上进入 `game-patch` 目录并运行 `python build.py --help` 查看参数。脚本核对源 APK 的 SHA-256，使用 JDK 21、Android SDK 36、Build Tools 36、NDK r28 和 Apktool 3.0.3 构建，输出改用独立包名、已对齐和已签名的本地实验 APK（`2.2.2-archive.2`）。APK 内的游戏资源和现有原生库来自用户提供的输入。暂存空间约需 12 GB。签名参数可通过环境变量或文件提供。

在档案馆项目根目录运行 `gradle -ParchiveLab=true :app:assembleDebug :app:lintDebug` 构建并行安装版。两份实验 APK 须使用相同证书。已有档案馆若要原地更新，需要用原分发证书对默认包名构建签名。

## 验证

- 主机测试覆盖：
  - `test/dev/torcharchive/game/SnapshotStoreCheck.java`：三次紧邻提交、重复捕获抑制、重新打开历史、多文件逐字节恢复、回档前版本保留、拒绝已损坏版本、还原被中断替换。
  - `test/dev/torcharchive/game/DsonCodecCheck.java`：真实二进制 `persist.game.json` 字节级无损往返一致、Steam→本地映射转换、排序修改与清空重读校验。
- 构建校验：APK 16 KB ZIP 对齐与 `apksigner verify` 校验；档案馆 Gradle 构建与 Android Lint 校验。
- 真机环境说明：部分系统（如 HyperOS）在完全划掉游戏后台后会拦截跨应用唤醒 `ArchiveProvider`；重新打开一次实验版游戏再切回档案馆即可正常同步。
