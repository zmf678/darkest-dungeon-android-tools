# 系统架构与底层技术规范（v2.0）

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [Android 启动器模块](../app/README.md) · [修改器指南](MODIFIER-GUIDE.md) · [构建测试指南](BUILD-AND-TEST.md)

本文档说明《暗黑地牢》余烬一体化启动器 v2.0 的进程拓扑、两种资源分发模式（A2-2 / A1）、4 处最小 Smali Hook、外置数据包 `dd_assets.pak` 构造以及真机验证确认的 Wicked `libddnx.so` ASTC 4×4 纹理与 `filelist.csv` 规范。

---

## 1. 一体化单 APK 架构与进程拓扑

v1 实验版采用「游戏 APK + 档案馆 APK」双包分离架构，通过跨应用 `ContentProvider`（[ArchiveProvider.java](../game-patch/src/dev/torcharchive/game/ArchiveProvider.java)）通信，在 HyperOS / ColorOS 等系统上容易受到 `WakePathChecker` 后台唤醒拦截。

v2.0 彻底重构为**桌面唯一入口的单 APK 架构**（`applicationId = "dev.torcharchive.ddgame"`，详见 [app/build.gradle](../app/build.gradle) 与 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml)）：

```text
dev.torcharchive.ddgame（同一 Linux UID，共享私有目录与 Android/media 权限）
├── 主进程：MainActivity（余烬启动器：启动 / 存档 / 修改 / 模组 / 画面 / 指南）
│     └── LiveArchive ──▶ 直接调用 GameArchive / SnapshotStore / SaveEditor / ModBridge / PakMount / DisplayConfig
├── 游戏子进程 (:nx)：ru.darkest.port.NxActivity（横屏传感器锁定 + libddnx.so 等 4 个原生库）
│     ├── onCreate 注入 GameArchive.install() ──▶ 加载 libtorcharchive.so Hook 存档提交 nftw
│     ├── onCreate 注入 PakMount.resolvePakPath() ──▶ 向 NxActivity.nxApk() 返回 dd_assets.pak 或 APK 自身路径
│     └── resolution() / LayerView.onMeasure() ──▶ 调用 DisplayConfig 动态适配屏幕尺寸与裁剪比例
└── 重启子进程 (:restart)：ru.darkest.port.RestartActivity
```

### 同 UID 直连与进程互斥

- **零跨应用 IPC**：[LiveArchive.java](../app/src/main/java/dev/torcharchive/dd/LiveArchive.java) 直接在应用沙盒内读写游戏存档与 `mods/` 目录。
- **写前强制停机**：任何回档、修改存档（[SaveEditor.java](../app/src/main/java/dev/torcharchive/game/SaveEditor.java)）或调整 Mod 配置前，均会通过 [NxProcessControl.java](../app/src/main/java/dev/torcharchive/game/NxProcessControl.java) 检查并终止 `:nx` 子进程，避免游戏内存态覆盖落盘文件。

---

## 2. 两种打包分发模式：A2-2（默认）与 A1

| 对比维度 | A2-2 外置单包直读（默认推荐） | A1 单 APK 内置模式 |
| :--- | :--- | :--- |
| 启动器 APK 体积 | 约 15 MB（仅含壳、DEX、5 个 `.so` 与 5 个辅助小资产） | 约 3.8 GB+（含全量 `assets/nx/`） |
| 游戏资源位置 | `Android/media/dev.torcharchive.ddgame/dd_assets.pak`（支持自定义路径） | 启动器 APK 内部的 `assets/nx/` |
| 构建方式 | [launcher-build.py](../launcher-build.py)（不传 `--bundle-pak`） | `launcher-build.py --bundle-pak` 或 `run.py build-apk`（[apk_bundler.py](../dd-steam-converter/dd_converter/apk_bundler.py)） |
| 适用场景 | 日常更新启动器代码无需重打 3.8 GB 资源包，ADB / 管理器导入便捷 | 希望只传输并安装一个独立 APK 的离线设备 |

### A2-2 运行时解析顺序（[PakMount.java](../app/src/main/java/dev/torcharchive/game/PakMount.java)）

`PakMount.resolvePakPath(Context)` 在 `NxActivity.onCreate()` 调用 `nxApk()` 前执行，按以下优先级返回路径：
1. 检查当前 APK 自身是否内置完整 `assets/nx/`（`exefs/main` 与 `romfs/filelist.csv` 均存在）；若是，则作为 **A1 内置模式**直接返回 `applicationInfo.sourceDir`；
2. 检查用户在启动器中显式选择的自定义路径 `custom_pak_path`；
3. 检查默认目录 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`。

---

## 3. 薄壳 APK 组装与 4 处最小 Smali Hook

私有仓库已在 `switch-runtime/` 内置两份运行时壳文件，免去每次处理 3.8 GB 完整基础 APK：
- **[switch-runtime/TorchLauncher-v2.0.apk](../switch-runtime/TorchLauncher-v2.0.apk)**（14.87 MB）：已组装、16 KB 对齐并签名的薄壳启动器成品，可直接安装；
- **[switch-runtime/wicked-runtime-shell.apk](../switch-runtime/wicked-runtime-shell.apk)**（9.01 MB，`SHA-256: 130ae6b9...09be86f2`）：仅包含 Wicked 2.2.2 代码与原生库的精简运行壳，供 [launcher-build.py](../launcher-build.py) 重编组装使用（脚本同时兼容原始完整 Wicked 2.2.2 APK `0b84730f...a619410`）。

组装流程如下：

1. **反编译与最小化 Smali 注入（`patch_wicked_smali()`）**：仅抽取并修改 Wicked 的 `NxActivity.smali` 与 `NxActivity$LayerView.smali`，共注入 4 处 Hook：
   - **Hook 1**：在 `System.loadLibrary("ddnx")` 之后调用 `GameArchive.install(Context)`；
   - **Hook 2**：将传给 `NxActivity.nxApk()` 的 `applicationInfo.sourceDir` 替换为 `PakMount.resolvePakPath(Context)`；
   - **Hook 3**：覆写 `NxActivity.resolution()`，委派给 `DisplayConfig.resolveResolution(Context)`；
   - **Hook 4**：覆写 `NxActivity$LayerView.onMeasure(II)`，委派给 `DisplayConfig.measure(Context, ...)`。
2. **原生库编译与保留**：
   - 使用 NDK Clang 编译 [game-patch/native/commit.c](../game-patch/native/commit.c) 生成 16 KB 页对齐的 `libtorcharchive.so`（通过 GOT Hook 拦截 `libddnx.so` 偏移 `0xab338` 对 `nftw@0x127590` 的存档收尾遍历调用，完成后回调 `GameArchive.afterCommit()`）；
   - 从本地基础 APK 抽取 `arm64-v8a` 下的 `libc++_shared.so`、`libddnx.so`、`libddport.so`、`libddastc.so`，统一以 `ZIP_STORED` 写入 `lib/arm64-v8a/`。
3. **MultiDex 安全拼接**：Gradle Debug 输出通常包含多个 DEX（例如 `classes.dex`–`classes5.dex`）。[launcher-build.py](../launcher-build.py) 会保留启动器壳的全部 `classes*.dex`，再将改写后的 Wicked `classes.dex` 放入下一个顺位（如 `classes6.dex`），彻底避免覆写壳类导致的 `ClassNotFoundException`。
4. **辅助资产保留与对齐签名**：保留 5 个启动器内嵌规则生成或字体所需的辅助小资产（`pc_fonts.zip`、`ui_merge_base.zip`、`base_png.txt`、`layout_keys.txt`、`mod_icon.png`），最终通过 `zipalign -P 16 -f 4` 执行 16 KB 对齐并调用 `apksigner` 完成签名校验。

---

## 4. 全机型分辨率与屏幕适配（[DisplayConfig.java](../app/src/main/java/dev/torcharchive/game/DisplayConfig.java)）

- **自动探测物理尺寸**：运行时读取设备真实宽高比，在 `640×360` 至 `4096×2560` 合法范围内偶数对齐生成 4–6 组推荐档位（原比例高分 / 均衡 / 省电等）。
- **超宽屏防拉伸策略**：由于 Switch 引擎内部按固定 16:9 画布绘制并拉伸到 `LAYER_W × LAYER_H`，针对宽高比 `> 1.85:1` 的超宽屏手机（如 20:9 / `3168×1440`），`defaultResolutionForScreen()` 默认回落至本机高度对齐的 16:9（如 `2560×1440`）并将无拉伸 16:9 档位排在首位；16:10 平板（如 `3008×1880`）保持原生全屏默认不变。
- **双缩放模式**：支持「等比清晰（Fit，按渲染帧比例缩放，无拉伸）」与「全屏铺满（Fill，去除黑边）」，并独立控制挖孔安全区避让开关 `avoid_cutout`。

---

## 5. Steam 资源转换、ASTC 4×4 DDS 与 `filelist.csv` 规范

PC 端转换器 [pak_builder.py](../dd-steam-converter/dd_converter/pak_builder.py) 与 [dds_encoder.py](../dd-steam-converter/dd_converter/dds_encoder.py) 默认自动加载私有仓库内置的 **[switch-runtime/switch-diff-layer.zip](../switch-runtime/switch-diff-layer.zip)**（38.01 MB，含 `assets/nx/exefs/main`、`assets/nx/overlay/`、23 份 `filelist.csv`、5 份 `dlc_info.csv` 与 39 项 Switch 专属 `romfs/` 差异件及 DLC `.loc` 兼容件；亦可通过 `--base-apk` 覆盖为完整基础 APK），并严格对齐 Wicked `libddnx.so` 的资源读取机制：

1. **纹理封装格式（ASTC 4×4 in DXT3 DDS Header）**：
   - Wicked 引擎 `nxgl_CompressedTexImage2D` 在底层会将 `GL_COMPRESSED_RGBA_S3TC_DXT3_EXT`（`0x83f2`）直接重映射为 `GL_COMPRESSED_RGBA_ASTC_4x4_KHR`（`0x93b0`）。
   - 因此 `assets/nx/` 下的 `.dds` 文件结构实为：**128 字节 `DXT3` FourCC DDS 文件头**（`dwDepth = 1`、`dwMipMapCount = 1`）+ **每块 16 字节的 ASTC 4×4 数据块**（不翻转 Y 轴、直通 Alpha）。
   - [dds_encoder.py](../dd-steam-converter/dd_converter/dds_encoder.py) 采用双引擎编码：
     - **优先路径**：自动发现 `dd-steam-converter/bin/` 或 `PATH` 中的官方 ARM `astcenc`（`-cl ... 4x4 -fast -j 1`），多分区 + 双平面 Alpha 编码画质达 **51–55 dB PSNR**（与原版资产几乎无差）。
     - **内置 NumPy 回落路径**：纯色块采用无损 ASTC Void-Extent（`0xFC 0xFD`）；不透明块采用 `mode=0x053, cem=8`（`FMT_RGB_DIRECT`，8-bit 端点 + 3-bit 8 级权重）；含透明通道块采用 `mode=0x042, cem=12`（`FMT_RGBA_DIRECT`，8-bit 端点 + 2-bit 4 级权重）；配合 1 步最小二乘端点优化与严格的 **`R1 + G1 + B1 >= R0 + G0 + B0`** 排序约束，彻底避免硬件解码触发 ASTC Blue Contraction 导致发暗发糊与块内权重反转。
2. **LUT 与调色 PNG 保留**：
   - `filelist.csv` 中未指定 `.dds` 映射的原始 `.png`（包括文件名含 `colour_grade` 与 `tint.png` 的 980 个色彩查找表/染色贴图）保持原始 `.png` 不做转码。
3. **俄语包剔除与存储约束**：
   - 自动剔除体积约 308 MB 的 `assets/nx/overlay_ru/`。
   - `dd_assets.pak` 内**所有条目必须使用 `ZIP_STORED`（不压缩）**，且同步重建全部 23 份 `filelist.csv` 中的实际文件大小，由 `run.py verify` 对每一行路径和字节数执行精确校验。
