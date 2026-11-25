# 系统架构与底层技术规范（v2.1）

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [English README](../README.en.md) · [Android 启动器模块](../app/README.md) · [修改器指南](MODIFIER-GUIDE.md) · [构建测试指南](BUILD-AND-TEST.md)

> **免责声明**：本文档仅用于个人技术学习与跨平台架构研究。使用本工具链必须搭配您个人合法购买的正版 Steam《暗黑地牢》目录。

本文档说明《暗黑地牢》余烬一体化启动器 v2.1 的单应用进程拓扑、A2-2 / A1 资源挂载模式、全机型分辨率与 60 / 90 / 120 Hz 高刷适配，以及 PC 端 Steam 正版资源的 ASTC 4×4 纹理转换与校验规范。

---

## 1. 一体化单 APK 架构与进程拓扑

v1 实验版采用双包分离架构，在部分安卓系统上容易受到跨应用后台唤醒拦截。v2.x 彻底重构为**桌面唯一入口的单 APK 架构**（`applicationId = "dev.torcharchive.ddgame"`，详见 [app/build.gradle](../app/build.gradle) 与 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml)）：

```text
dev.torcharchive.ddgame（同一 Linux UID，共享应用沙盒与 Android/media 权限）
├── 主进程：MainActivity（余烬启动器：启动 / 存档 / 修改 / 模组 / 画面 / 指南）
│     ├── LiveArchive ──▶ 直接调度 GameArchive / SnapshotStore / SaveEditor / ModBridge / PakMount / DisplayConfig
│     └── ModifierSection ──▶ 深色余烬修改器与 4 个应用内全屏二级详情页（原生适配 Android 13–17 手势返回）
└── 独立游戏子进程：承载游戏运行环境、自动留档监听、外置资源包挂载与分辨率/高刷控制
```

### 同 UID 直连与进程互斥

- **零跨应用 IPC**：[LiveArchive.java](../app/src/main/java/dev/torcharchive/dd/LiveArchive.java) 直接在应用沙盒内读写游戏存档与 `mods/` 目录。
- **写前强制停机与自动快照**：任何回档、修改存档（[SaveEditor.java](../app/src/main/java/dev/torcharchive/game/SaveEditor.java)）或调整 Mod 配置前，均会通过 [NxProcessControl.java](../app/src/main/java/dev/torcharchive/game/NxProcessControl.java) 安全终止游戏子进程，并强制由 [SnapshotStore.java](../app/src/main/java/dev/torcharchive/game/SnapshotStore.java) 先拍摄一份当前档位快照，避免内存态覆写或误操作导致丢档。

---

## 2. 两种资源挂载模式：A2-2（默认）与 A1

| 对比维度 | A2-2 外置单包直读（默认推荐） | A1 单 APK 内置模式 |
| :--- | :--- | :--- |
| 启动器 APK 体积 | 约 15 MB | 约 3.8 GB+（含全量资源） |
| 游戏资源位置 | `Android/media/dev.torcharchive.ddgame/dd_assets.pak`（支持自定义路径） | 启动器 APK 内部资源目录 |
| 适用场景 | 日常更新启动器无需重传数 GB 资源包，ADB / 文件管理器导入便捷 | 希望只传输并安装单个离线安装包的设备 |

### A2-2 运行时解析顺序（[PakMount.java](../app/src/main/java/dev/torcharchive/game/PakMount.java)）

`PakMount.resolvePakPath(Context)` 在游戏启动阶段按以下优先级解析资源包路径：
1. 检查当前 APK 自身是否已内置完整资源清单（A1 内置模式）；
2. 检查用户在启动器中显式选择的自定义路径 `custom_pak_path`；
3. 检查默认外置路径 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`。

---

## 3. 全机型分辨率与 60 / 90 / 120 Hz 高刷适配（[DisplayConfig.java](../app/src/main/java/dev/torcharchive/game/DisplayConfig.java)）

- **自动探测物理屏幕与刷新率**：运行时通过 Android `WindowMetrics` 与 `Display.getSupportedModes()` 读取设备真实分辨率及面板支持的刷新率列表（例如 60 / 90 / 120 / 144 / 165 Hz），在 `640×360` 至 `4096×2560` 合法范围内偶数对齐生成 4–6 组推荐分辨率档位。
- **超宽屏防拉伸策略**：针对宽高比 `> 1.85:1` 的超宽屏手机（如 20:9 / `3168×1440`），`defaultResolutionForScreen()` 默认优先推荐本机高度对齐的无拉伸 `16:9` 清晰档（如 `2560×1440`）；16:10 平板（如 `3008×1880`）保留原生全屏默认不变。
- **双缩放模式与挖孔避让**：支持「等比清晰（Fit，按渲染帧比例无拉伸显示）」与「全屏铺满（Fill，消除黑边）」，并独立提供挖孔/刘海安全区避让开关 `avoid_cutout`。
- **高刷帧率控制**：提供 **60 Hz / 90 Hz / 120 Hz / 自动最高** 四档选择，运行时同步匹配物理 `Display.Mode`、`preferredRefreshRate` 与 Android 11+ `Surface.setFrameRate()`，兼顾低功耗稳帧与旗舰高刷流畅体验。

---

## 4. Steam 正版资源转换与 ASTC 4×4 纹理规范

PC 端转换器 [pak_builder.py](../dd-steam-converter/dd_converter/pak_builder.py) 与 [dds_encoder.py](../dd-steam-converter/dd_converter/dds_encoder.py) 负责将用户本机合法购买的 Steam《暗黑地牢》本体与已购 DLC 资源转换为移动端高效数据包：

1. **双引擎 ASTC 4×4 纹理管线**：
   - 将 Steam 贴图编码为移动端 GPU 原生硬件解码的 ASTC 4×4 压缩纹理（直通 Alpha、不翻转 Y 轴），大幅降低移动端显存带宽占用。
   - **优先路径**：自动发现 `dd-steam-converter/bin/` 或 `PATH` 中的官方 ARM `astcenc`（`-cl ... 4x4 -fast -j 1`），多分区 + 双平面 Alpha 编码画质达 **51–55 dB PSNR**（肉眼几乎无损）。
   - **内置 NumPy 回落路径**：纯色块采用无损 ASTC Void-Extent；不透明块采用 3-bit 8 级权重（`CEM=8`）；含透明通道块采用 2-bit 4 级权重（`CEM=12`）；配合最小二乘端点优化与防 Blue Contraction 约束，画质达 **41–44 dB PSNR**。
2. **LUT 与调色 PNG 原图保留**：
   - 色彩查找表（`colour_grade`）与染色贴图（`tint.png`）保留原始无损 `.png`，不进行有损转码。
3. **无压缩直读与清单精确校验**：
   - `dd_assets.pak` 全部条目以 `ZIP_STORED`（不压缩）写入，支持移动端零解压偏移直读；同时全量重建资源清单 `filelist.csv` 的精确字节数，并由 `run.py verify` 逐行核验完整性。
