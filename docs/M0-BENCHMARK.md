# 路线 C（v3.0）阶段 M0 实测基准报告

> 关联规划：[ROUTE-C-PURE-STEAM-PLAN.md](ROUTE-C-PURE-STEAM-PLAN.md)
> 实测日期：2026-11-25
> 实测结论：**M0 验收通过（PASS）** — 纯正版 Steam 64 位无 DRM 主程序 `_windowsnosteam/win64/Darkest.exe` 已在 Android 15（MuMu 模拟器）上经 100% 开源兼容栈完整点亮，主菜单 `79–103 FPS`、「老路」战斗 `82–89 FPS`、中文字体与 2D 骨骼贴图零异常、FMOD 双路音频直出 Android `AAudio_sink`、自动存档 `profile_0` 正常落盘。

---

## 1. 目标设备硬件与内核环境（`T0.1`）

### 1.1 首轮实测平台：MuMu Android 15 模拟器（实例 #3，`127.0.0.1:16480`）

| 探测项 | 实测值 | 备注 |
| :--- | :--- | :--- |
| **设备标识** | `MuMu安卓设备-2-1`（伪装机型 `Xiaomi 2304FPN6DC / ishtar`） | Hyper-V 后端 |
| **SoC / CPU** | `Intel(R) Core(TM) i7-14650HX`（4 vCPU，SSE4.2 / AVX2） | `ro.product.cpu.abi=x86_64`；`abilist=x86_64,arm64-v8a,x86`；`uname -m` 伪装为 `aarch64`；通过 `binfmt_misc` → `/system/bin/houdini64` 翻译 `arm64-v8a` ELF |
| **GPU / 图形栈** | MuMu 虚拟化透传驱动（`libEGL_kona.so` / `libGLESv2_kona.so` / `/vendor/lib64/hw/vulkan.kona.so`） | GLES Renderer 上报 `Adreno (TM) 640`，支持 **OpenGL ES 3.2**（`196610`）与 **Vulkan 1.3.0**（`4206592`）；无 `/dev/kgsl-3d0`，不可用高通硬件专用 Turnip |
| **PAGESIZE** | `4096`（4 KB） | `getconf PAGESIZE` 实测 |
| **Android SDK** | `35`（Android `15.0`） | 内核 `6.1.90-perf+` |
| **内存与存储** | RAM `3.84 GiB`（可用 `~3.05 GiB`）；`/data` 与 `/sdcard` 可用 `104 GiB` | `/data` 与 `/tmp` 均无 `noexec` 挂载限制 |
| **屏幕与权限** | `1080×1920 @ 60 Hz`（`280 dpi`）；SELinux `Permissive`；`adb shell` 为 `uid=2000(shell)` | 无 `su` 二进制 |

> [!NOTE]
> **模拟器与真机架构差异说明**：规划文档原定目标机为高通骁龙 ARM64 真机（`PJZ110`）。MuMu 底层为 `x86_64` + `houdini64` 且无物理 `/dev/kgsl-3d0`，因此本轮 M0 在 MuMu 上完成了全套端到端闭环验证（纯 64 位 Steam 同步、Bionic ARM64EC vs glibc Box64 运行时边界验证、原生 `x86_64` Bionic Wine 11.0 实跑《暗黑地牢》及多图形后端性能对比）。后续接入骁龙 ARM64 真机时，沿用同一套同步脚本与测试流程即可补齐 Turnip+Zink 与 ARM64EC+FEXCore 真机温控对比数据。

---

## 2. 纯 64 位正版 Steam 裁剪同步（`T0.2`）

已实现原型同步脚本 [pc_sync.py](../dd-steam-converter/dd_converter/pc_sync.py) 与对应单元测试 [test_converter.py](../dd-steam-converter/tests/test_converter.py)，针对本机正版 Steam 目录（`D:\Steam\steamapps\common\DarkestDungeon`）执行裁剪并推送至设备目录 `/sdcard/Android/media/dev.torcharchive.ddgame/steam/`：

| 指标 | 实测结果 |
| :--- | :--- |
| **保留文件数 / 体积** | `8,599` 文件 / `2,087.3 MB` |
| **排除冗余数 / 体积** | `130` 文件 / `132.0 MB`（排除 `_windows/`、`_windowsnosteam/win32/`、`shaders_d3d11/`、`shaders_xb1/`、`app.log*`、`steam_appid.txt`） |
| **首次全量同步耗时** | `77.8 s`（无压缩 tar 流 `2,102.0 MB` 经 `adb push` 以 `47.9 MB/s` 传输 `43.85 s` + 设备端解压与完整性核对） |
| **二次无变更增量耗时** | `1.1 s`（跳过 `8,599` 文件，传输 `0` 文件） |
| **远端完整性验收** | **PASS**（`remote_files=8599/8599`，`missing=[]`；已核对 `_windowsnosteam/win64/Darkest.exe`、`SDL2.dll`、`fmod.dll`、`fmodstudio.dll`、`shaders/`、`audio/`、`fonts/`、`localization/` 及 2 款已购 DLC 全部就绪） |

---

## 3. 开源运行时拉取与启动验证（`T0.3`）

所有开源运行时包均下载至被 `.gitignore` 忽略的本地目录 `dist/runtime-poc/`（零商业资产、零入库）：

| 候选运行链 | 开源制品与校验（SHA-256 前 16 位） | ELF / 链接类型 | MuMu (`x86_64` + `houdini64`) 实测结果 | 架构启示 |
| :--- | :--- | :--- | :--- | :--- |
| **候选 A：Wine 10 ARM64EC + FEXCore** | `StevenMXZ/Winlator-Contents` `proton-10-arm64ec.wcp.xz` (`248.5 MB`, `2501AF75FFE4CFFA...`) | **纯 Android 原生 Bionic `aarch64`**（`/system/bin/linker64`，无 glibc/proot 依赖），内置全套 `aarch64-unix/*.so` 与 `prefixPack.txz` | **PASS（版本验证）**：在 MuMu `houdini64` 下直接输出 **`wine-10.0`**，无 linker 冲突 | **极适合 M1 真机默认主路线**：免 proot、免 glibc `LD_LIBRARY_PATH` 污染，兼容 Android 15+ 严格命名空间 |
| **候选 B：Box64 v0.4.4 + Wine64** | `brunodev85/winlator` `Winlator_11.2.apk` (`150.3 MB`, `EA5C81BF0E5B90A6...`) | glibc `aarch64`（`box64-0.4.4.tzst` + `62.2 MB` 精简 `rootfs.tzst` + `turnip-26.2.0` / `zink-22.2.5` / `vortek-2.1` / `virgl-23.1.9` / `gladio-1.1`） | **受限于模拟器 `houdini64` 边界**：启动容器时因 `LD_LIBRARY_PATH` 指向 glibc `libc.so` linker script，导致 `/system/bin/houdini64` 报错 `has bad ELF magic: 2f2a2047` 退出 | 该冲突仅发生在 `x86_64` 模拟器 `houdini64` 层；真实 ARM64 手机直接执行 ELF 不受影响，仍保留作真机备选路线 |
| **原生基线 X：Android Bionic `x86_64` Wine 11.0** | Termux 官方 `x11-repo`：`wine-stable 11.0-2` + `mesa 26.3.1` + `virglrenderer-android 1.3.0` + `pulseaudio 17.0-5` + `tigervnc 1.16.2` + `AVNC v3.3.1` (`61CB507E9F334F02...`) | **纯 Android 原生 Bionic `x86_64`**（零 CPU 转译、零 proot） | **PASS（全链路实跑通过）**：输出 **`wine-11.0`**，`wineboot --init` 仅 `18 s` 初始化最小 Prefix，完整点亮《暗黑地牢》 | 验证了纯开源 Android Bionic Wine + OpenGL + PulseAudio AAudio 跑《暗黑地牢》100% 畅通 |

> [!TIP]
> **MuMu 模拟器显示桥接排障实录**：`Termux:X11` (`com.termux.x11`) 跨包连接依赖静态广播 `com.termux.x11.CmdEntryPoint.ACTION_START`，MuMu 系统策略明确拦截该跨包广播（`Skipping delivery to com.termux.x11 blocked by mumu`）。切用纯本地 TCP 环回的 `Xvnc :1` (`127.0.0.1:5901`) + 开源 `AVNC` 客户端后彻底绕开拦截；同时这也再次印证 M1 阶段自研同进程/同 UID 嵌入式 `SurfaceView` 桥接（无跨包静态广播依赖）的必要性。

---

## 4. `Darkest.exe` 实测结果与运行时锁定结论（`T0.4`）

测试命令：`wine Darkest.exe -size 1280 720 -skip`（实测分辨率 `1280×720`，`GALLIUM_HUD=simple,fps` 实时叠加帧率监控）。

### 4.1 图形渲染后端对比（Android 15 + Wine 11.0）

| 图形后端 | OpenGL 上报版本 / 渲染器 | 画面验收 | 主菜单 FPS | 「老路」实景 / 战斗 FPS | CPU 占用（游戏 / 渲染器） | 峰值 RSS 内存 | 结论 |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| **`VirGL --angle-gl` + `MESA_GL_VERSION_OVERRIDE=3.3COMPAT` + `MESA_GLSL_VERSION_OVERRIDE=330`** | `3.3 (Compatibility Profile) Mesa 26.3.1` / `virgl (ANGLE Adreno 640, OpenGL ES 3.2)` | **PASS**（锐利清晰、零花屏、零缺字、高斯模糊与火把光效正常） | **`79–103 FPS`**（峰值 `110.9 FPS`） | **`82–92 FPS`**（战斗 `88.6 FPS`、Hover 面板 `82.0 FPS`） | `27.6%` / `27.0%` | `Darkest.exe`: `1,300 MB`；`virgl`: `654 MB` | **MuMu 模拟器实测最优（10× 提速）** |
| `llvmpipe`（纯 CPU 软渲染对照组） | `4.6 (Compatibility Profile) Mesa 26.3.1` / `llvmpipe (LLVM 21.1.8)` | **PASS**（画面完全正确） | `10.3 FPS` | — | `96.9%` / — | `884 MB` | 仅作画面正确性对照 |
| `VirGL` 默认无参模式（未覆写 GL 3.3） | `2.1 Mesa 26.3.1` / `virgl (Adreno 640)` | 黑屏（仅自定义手绘光标可见） | — | — | `26.5%` / `15.4%` | `593 MB` | 需启用 `--angle-gl` + GL 3.3 兼容覆写 |
| `VirGL --angle-vulkan` / `Zink (drisw)` | MuMu 虚拟化 `vulkan.kona.so` 挂起 / 缺少 DRI3 | 不可用 | — | — | — | — | 受限于 MuMu 虚拟 Vulkan 与 Xvnc 无 DRI3 |

### 4.2 全维度验收清单

| 验收项 | 实测结果 | 证据 / 备注 |
| :--- | :---: | :--- |
| **冷启动时间** | `~20 s`（含首次 Prefix `18 s` + 进程拉起 `~2 s`） | 首次初始化 `wineboot --init` 仅执行一次 |
| **二次热启动时间** | **`1.80 s` – `1.91 s`**（启动至 X11 窗口映射） | 实测 `WARM_WINDOW_MS=1797` / `1912`，远优于 5–8 s 目标 |
| **主菜单 / 战斗帧率** | 主菜单 **`79–103 FPS`** / 「老路」战斗 **`82–89 FPS`** | 远超 ≥ 45–60 FPS 验收门禁 |
| **2D 骨骼贴图与着色器** | **PASS** | 雷纳德、迪斯马斯、土匪骨骼动画、受击/目标锁定光环、背景虚化着色器、DLC 图标全部正常 |
| **简体中文字体渲染** | **PASS** | 游戏内置位图字体（`fonts/*.fnt` + `.tga`）完整渲染「战役」「老路」「驿站马车已被摧毁…」无缺字/方块 |
| **FMOD 音频输出** | **PASS** | `PulseAudio` 成功接管 `Darkest.exe` 双路音频流（`float32le 2ch 48000Hz` + `44100Hz`）直出 Android **`AAudio_sink`** |
| **鼠标悬停（Hover）与输入** | **PASS** | 悬停土匪稳定唤出右侧抗性面板；`A`/`D` 走廊平滑移动、左键技能/地图操作响应即时 |
| **存档与语言配置落盘** | **PASS** | 自动落盘于 `drive_c/users/<user>/Documents/Darkest/profile_0/`（全套标准 DSON：`persist.game.json` / `roster` / `estate` / `raid` / `map` / `upgrades`）及 `persist.options.json`（`data.values.language = "schinese"`） |
| **连续运行稳定性** | **PASS** | 连续实测运行 **> 13 分钟** 零崩溃、零内存持续泄漏（稳定在 `1.27 GB` RSS） |

### 4.3 锁定 M1–M4 默认运行时架构结论

基于本次 M0 实测，正式锁定以下技术路线进入阶段 M1：

1. **Android ARM64 真机默认主引擎（锁定）**：**纯 Android Bionic 编译的 Wine 10 ARM64EC + FEXCore（MIT）**
   - **理由**：本次拆检与实测证实 Bionic ARM64EC 运行时直接走 Android `/system/bin/linker64`，彻底规避 `proot` 与 glibc `LD_LIBRARY_PATH` 污染风险，非常适合封装进同 UID 单 APK 的 `:pcgame` 子进程；
   - **备选引擎**：**Box64 + Wine64**（面向 16 KB 页或特殊厂商内核兜底）。
2. **多 GPU 图形后端自动探测与分级策略（锁定）**：
   - **高通 Adreno 真机（首选）**：`Mesa Turnip + Zink`（完整兼容 OpenGL 3.3 Compatibility + `#version 120`）；
   - **非骁龙 / 模拟器 / 通用兜底（本次 MuMu 已实战验证 88+ FPS！）**：**`VirGL (--angle-gl) + MESA_GL_VERSION_OVERRIDE=3.3COMPAT + MESA_GLSL_VERSION_OVERRIDE=330`**（借 ANGLE 桥接 Android 系统 OpenGL ES 3.2，消除纯 VirGL 2.1 黑屏并直接达到 80–100+ FPS！）；
   - **极端兼容兜底**：`系统 Vulkan + Zink` / `GL4ES`。
3. **音频与显示桥接（锁定）**：
   - 音频采用 **Wine PulseAudio → Android `AAudio_sink`**（本次已验证双路 48 kHz/44.1 kHz 零异常）；
   - 显示桥接在 M1 采用**同应用内嵌 SurfaceView 桥接**，彻底规避类似 MuMu 拦截跨包静态广播的问题；
   - 预置 `persist.options.json` 支持由启动器按 `DisplayConfig` 精确下发分辨率与中文语言，并在 `Documents/Darkest` 建立软链映射至应用沙盒 `files/pc/save/`，无缝衔接 `SaveEditor`、`DsonCodec` 与 `FileObserver` 自动快照。
