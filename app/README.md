# Android 一体化启动器模块（`app/`）

> 返回导航：[工作区总索引](../INDEX.md) · [系统架构](../docs/ARCHITECTURE.md) · [修改器指南](../docs/MODIFIER-GUIDE.md) · [构建测试指南](../docs/BUILD-AND-TEST.md)

`app/` 为「暗黑地牢 · 余烬启动器（v2.3.1）」原创 Android 客户端工程源码（`applicationId = "dev.torcharchive.ddgame"`，`minSdk 26`，`targetSdk 36`），提供统一启动入口、外置数据包挂载、分辨率与高刷设置、自动留档回档、联网模组工坊与内置修改器。

---

## 模块分层与核心类清单

### 1. 应用界面与状态层（`src/main/java/dev/torcharchive/dd/`）

| 文件 | 职责说明 |
| :--- | :--- |
| [MainActivity.java](src/main/java/dev/torcharchive/dd/MainActivity.java) | 主活动界面，组织「启动 / 存档 / 修改 / 模组 / 画面 / 指南」6 个工作区页签，处理系统文档选择器（SAF）与自适应手机/平板布局 |
| [LiveArchive.java](src/main/java/dev/torcharchive/dd/LiveArchive.java) | 同 UID 直连门面：直接桥接当前应用内沙盒数据与 `:nx` 子进程控制，统一协调回档、Mod 导入启停、分辨率切换与状态刷新 |
| [ModifierSection.java](src/main/java/dev/torcharchive/dd/ModifierSection.java) | 「修改」页签控制器：提供庄园与钱包、英雄嵌套 DSON 属性/怪癖、饰品图鉴增删、城镇建筑一键解锁、原始 DSON 树编辑器与非破坏性规则修改器界面 |
| [ArchiveStore.java](src/main/java/dev/torcharchive/dd/ArchiveStore.java) | 本地存档仓与模组预设仓：支持手动导入档位 ZIP、查看自动留档列表、保存/导出与管理本地 Mod ZIP 预设 |
| [WorkshopSection.java](src/main/java/dev/torcharchive/dd/WorkshopSection.java) | 原生工坊搜索、详情、下载进度及公开 ZIP 入口，适配手机单列和平板双列 |
| [WorkshopClient.java](src/main/java/dev/torcharchive/dd/WorkshopClient.java) | 读取 Steam 公开工坊页面与条目详情，通过 HTTPS 下载公开 ZIP |
| [LauncherUpdates.java](src/main/java/dev/torcharchive/dd/LauncherUpdates.java) | 查询 GitHub 正式发行版，筛选 `TorchLauncher-v*.apk` 正式安装包并比较版本 |

### 2. 游戏引擎接入、存档与修改内核（`src/main/java/dev/torcharchive/game/`）

| 文件 | 职责说明 |
| :--- | :--- |
| [PakMount.java](src/main/java/dev/torcharchive/game/PakMount.java) | A2-2 外置 `dd_assets.pak` 路径解析、流式导入与清单读取（优先检测 A1 内置资产，其次自定义路径，最后默认 `Android/media/dev.torcharchive.ddgame/dd_assets.pak`） |
| [DisplayConfig.java](src/main/java/dev/torcharchive/game/DisplayConfig.java) | 物理分辨率与支持刷新率探测、4–6 组比例档位生成、60 / 90 / 120 Hz / 自动最高高刷适配与挖孔安全区控制 |
| [NxProcessControl.java](src/main/java/dev/torcharchive/game/NxProcessControl.java) | 游戏子进程查询、写前安全停机、拉起及重启控制 |
| [GameArchive.java](src/main/java/dev/torcharchive/game/GameArchive.java) | 游戏进程启动挂钩：恢复中断替换、创建基线快照并注册存档提交自动留档回调 |
| [SnapshotStore.java](src/main/java/dev/torcharchive/game/SnapshotStore.java) | 原子化档位留档仓库（自动捕获变更档位、跳过重复字节状态、拒绝损坏快照、回档前保留当前档位备份） |
| [DsonCodec.java](src/main/java/dev/torcharchive/game/DsonCodec.java) | 无损 Red Hook 二进制 DSON（`0x0000B101`）解析与重序列化引擎 |
| [ModBridge.java](src/main/java/dev/torcharchive/game/ModBridge.java) | `mods/` 目录扫描、Mod ZIP / `steam-migration.zip` 解包、`persist.game.json` 内 `applied_ugcs_1_0` 启停与排序管理 |
| [SaveEditor.java](src/main/java/dev/torcharchive/game/SaveEditor.java) | 手机端完整二进制 DSON 存档编辑 API（涵盖 `persist.estate.json`、`persist.roster.json` 嵌套英雄 DSON、`persist.upgrades.json` 建筑树与任意原始节点路径） |
| [ContentModGenerator.java](src/main/java/dev/torcharchive/game/ContentModGenerator.java) | 生成并启停标准覆盖 Mod `torch_content_modifier`，管理堆叠、槽位、战斗倍速、暴击、命中、撤退、美德、压力等规则 |
| [CatalogData.java](src/main/java/dev/torcharchive/game/CatalogData.java) | 常用饰品、正面怪癖、疾病、美德、折磨与建筑名称中文目录常量 |

---

## 清单、资源与本地构建配置

- **Gradle 配置**：[app/build.gradle](build.gradle) 启用 Java 17 源码兼容性，支持 `-ParchiveLab=true` 并行测试包名 `.lab`。
- **进程定义**：[AndroidManifest.xml](src/main/AndroidManifest.xml) 声明主 UI `MainActivity` 及独立游戏子进程隔离。
- **原创界面资源**：
  - 主题：[styles.xml](src/main/res/values/styles.xml)
  - 图标与头图：[archive_mark.xml](src/main/res/drawable/archive_mark.xml)、[archive_illustration.png](src/main/res/drawable-nodpi/archive_illustration.png)
  - 字体与授权：[display.ttf](src/main/assets/display.ttf)、[NotoSerifSC-OFL.txt](src/main/assets/NotoSerifSC-OFL.txt)
  - 备份排除规则：[backup_rules.xml](src/main/res/xml/backup_rules.xml)

### 构建与 Lint 校验命令

```powershell
# 编译启动器工程并执行 Android Lint（在仓库根目录执行）
gradle --offline :app:assembleDebug :app:lintDebug
```

更多测试与部署步骤请见 [docs/BUILD-AND-TEST.md](../docs/BUILD-AND-TEST.md)。
