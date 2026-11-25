# dd-steam-toolkit — 《暗黑地牢》余烬 PC 工坊（Steam 独立存档管家 & 完整修改器）

**简体中文** | [English](README.en.md)

> [!IMPORTANT]
> **免责声明与正版使用须知**：本工具仅供个人技术学习与单机体验辅助参考，使用前您**必须自行通过官方渠道合法购买并安装正版 Steam 版《暗黑地牢》（*Darkest Dungeon*）**；本项目为社区开源工具，与 Red Hook Studios 及 Valve/Steam 无任何官方关联，严禁用于商业售卖或违规再分发。完整声明见根目录 [README.md](../README.md)。

> 返回导航：[项目总览](../README.md) · [English README](../README.en.md) · [工作区总索引](../INDEX.md) · [修改器技术指南](../docs/MODIFIER-GUIDE.md) · [Android 启动器](../app/README.md)

从 Android「余烬一体化启动器」中独立拆出并深度适配 **PC Steam 正版《暗黑地牢》** 的桌面版存档管家与全功能修改器。

**零第三方 Python 依赖**（纯 Python 3.10+ 标准库），无需安装 Gradle、Android SDK、Pillow 或任何外部 GUI 框架；在 Windows 上双击 `启动余烬PC工坊.bat` 即可通过系统内置 Edge / Chrome 以**独立桌面应用窗口**启动，同时提供完整命令行接口（CLI）与可选单文件 `.exe` 打包脚本。

---

## 核心特性一览

### 1. 存档档案柜 & 实时自动留档（对标 APK `SnapshotStore` + `libtorcharchive.so`）
- **游戏内实时自动留档**：后台线程每 1.5 秒监测 Steam `userdata/<UID>/262060/remote/profile_0..8` 变动（1.5 秒防抖，跳过 `.tmp`/`.old` 中间态），每次进房间、战斗结束、返回城镇或退出游戏均自动生成一份 **SHA-256 去重校验**的确定性 ZIP 快照。
- **原子事务回档与中断恢复**：回档前严格校验快照 SHA-256 → 解压至临时事务目录并校验 `persist.game.json` → **强制先行备份当前现役存档** → 原子目录替换；若中途断电或中断，下次运行自动从 `.torcharchive-restore` 恢复原档。
- **版本对比、备注与跨平台互导**：
  - 查看任意快照与上一版本的精确文件变更（新增/修改/删除列表）；
  - 手动随时留档、添加/修改中文备注、一键清理旧自动快照（自动保留手动与带备注快照）；
  - 支持导入外部 Steam 备份 ZIP 或 **Android 余烬启动器导出的存档 ZIP**，勾选「自动还原创意工坊 Mod 来源」即可把本机已订阅的工坊 ID 从 `source: mod` 自动还原为 `source: Steam`。

### 2. 全维度无损二进制 DSON 存档修改器
每次写入前自动执行 **进程安全检查（`Darkest.exe`） + 强制写前快照 + DSON 往返结构校验 + 原子替换**：
- **庄园与货币**：修改庄园名称、切换游戏模式（光辉 `radiant` / 极暗 `darkest` / 血月冥暗 `new_game_plus`）、编辑 7 项货币（金币、半身像、肖像画、地契、纹章、彗星碎片、建筑蓝图）与无尽农庄最高波次；内置 `+10万金币 / +100全传家宝 / 一键全拉满` 快捷条。
- **花名册英雄精修**：
  - 一键 `全体满血清压清病复活`、`全体满级满装 (Lv.6)`；
  - 实时搜索（中文/英文职业与姓名）与 4 态筛选（全部 / 需恢复 / 含疾病 / 已阵亡）；
  - 自动兼容 Steam 版绝对血量与百分比血量格式；
  - 英雄精修弹窗：姓名、HP、压力、Lv.0–6 快捷对齐（含自定义 XP）、武器/护甲 1–5 级、美德/折磨二选一网格、复活并清除死门/心梗标记、3 套个人预设；
  - 怪癖与疾病弹窗：一键清除全部疾病、一键锁定所有非疾病怪癖、逐项锁定/解锁/移除，内置 **22 项中文精选正面怪癖 + 自动扫描 Steam 本体及 DLC `quirk_library.json` 全量库**，支持任意自定义/Mod 怪癖 ID。
- **饰品仓库与分类图鉴**：
  - 已拥有饰品搜索、`+1 复制`、移除；
  - 分类饰品图鉴内置 **52 项热门中文饰品**（先祖/极稀有/稀有/收藏家/狂人/鸦翼/奖杯/疯狂），并自动索引本机 Steam 本体与全部已购 DLC 的 `*.entries.trinkets.json`，支持 `×1 / ×2 / ×4` 批量添加与自定义/Mod 饰品 ID。
- **城镇建筑与地牢进度**：
  - 实时展示城镇 8 座核心建筑（铁匠铺、公会、马车、疗养院、酒馆、修道院、篷车、露营导师）解锁进度，基于 Red Hook `dd_hash` 一键全解锁全部 20 棵升级树；
  - 6 大地牢区域（遗迹/荒野/战壕/海湾/庭院/农庄）等级与 XP 编辑及 `全区满级 (150 XP)`。
- **全字段原始 DSON 二进制浏览器**：
  - 横向切换档位内所有 `persist.*.json`；支持可点击面包屑导航，**可直接穿透进入英雄嵌套 DSON (`raw_data`)**；支持字段实时搜索、`int32 / float32 / bool / string` 校验写回以及导出全档位解码 JSON。

### 3. 非破坏性游戏规则修改器（`torch_content_modifier`）
通过生成标准 Darkest Dungeon 覆盖 Mod 安装到 `<Steam>\DarkestDungeon\mods\torch_content_modifier\`，**不修改游戏原文件，随时可一键卸载恢复原版**：
- **4 套一键平衡预设**：`还原原版` / `轻度舒适` / `爽快开荒` / `终极无双`；
- **一 · 背包、饰品槽与补给**：金币/传家宝/宝石/补给堆叠 `1x–99x`、饰品槽位 `2–4 格`、远征背包 `16–24 格`、补给品商店免费；
- **二 · 战斗节奏与输出**：战斗动画倍速 `1.0x–4.0x`、我方暴击倍率 `1.5x–3.0x`、解除 95% 命中上限、100% 无损撤退；
- **三 · 压力管理与美德**：美德概率 `25%–100%`、受压倍率 `100%–0%`、城镇闲置每周回压 `5–100`；
- **四 · 马车招募与养成**：马车刷新 `1x–3x`、花名册扩容 `+0–+100`、极速决心升级；
- **五 · PC Steam 专属深度数值覆写**：读取本机游戏本体与全部已购 DLC（如火枪手、破盾者、猩红庭院苦修者等，自动跳过怪物文件），动态生成全英雄 **HP 倍率 (`1.0x–3.0x`)、伤害倍率 (`1.0x–3.0x`)、速度加成 (`+0–+10`)、闪避加成 (`+0–+30`)** 以及 **城镇建筑升级费用折扣 (`0%–100%`)**；
- **一键置顶挂载至档位**：自动写入 `applied_ugcs_1_0` 并**严格保留原有创意工坊 Mod 的 `source: Steam`**，同时支持导出通用 `.zip` 分享或备份。

### 4. Steam 模组加载排序管理
- 自动扫描本机 `steamapps/workshop/content/262060/` 创意工坊与本地 `mods/` 目录，读取 `project.xml` 标题与版本号；
- 可视化调整各档位 `applied_ugcs_1_0` 启用状态与加载优先级（上移/下移/启停），严格区分 `Steam` 与 `mod` 来源。

---

## 快速上手

### 前置条件
- 已安装 **Python 3.10+**（无需 `pip install` 任何第三方依赖）。
- 已安装正版 PC Steam《暗黑地牢》（亦支持手动指定 GOG / Epic / 自定义存档目录）。

### 方式一：图形桌面界面（推荐）
- **Windows**：直接双击 [启动余烬PC工坊.bat](启动余烬PC工坊.bat)，或在终端执行：
  ```powershell
  python dd-steam-toolkit\run.py
  ```
- 程序会自动扫描本机 Steam 目录与全部账号存档，在本地 `127.0.0.1` 启动带随机防跨站令牌的安全服务，并优先以 Edge / Chrome 独立桌面窗口打开（若未检测到则使用系统默认浏览器）。
- **常用启动参数**：
  ```powershell
  # 使用默认浏览器标签页打开（而非独立应用窗口）
  python dd-steam-toolkit\run.py ui --browser

  # 指定固定端口或自定义目录
  python dd-steam-toolkit\run.py ui --port 18620 --game-dir "D:\Steam\steamapps\common\DarkestDungeon"

  # 仅启动服务、不自动开启后台自动留档
  python dd-steam-toolkit\run.py ui --no-watch
  ```

### 方式二：命令行（CLI）快捷操作

```powershell
# 1. 探测本机 Steam 游戏、DLC、创意工坊与全部档位
python dd-steam-toolkit\run.py detect

# 2. 启动命令行实时自动留档守护（玩游戏时挂在后台即可）
python dd-steam-toolkit\run.py watch

# 3. 手动留档 / 列出快照 / 原子回档 / 清理冗余自动快照
python dd-steam-toolkit\run.py snapshot backup --slot profile_0 --note "进极暗地牢前"
python dd-steam-toolkit\run.py snapshot list --slot profile_0
python dd-steam-toolkit\run.py snapshot restore --id <SNAPSHOT_ID>
python dd-steam-toolkit\run.py snapshot prune --keep 30

# 4. 完整二进制存档修改（写前自动拍快照）
python dd-steam-toolkit\run.py edit-save profile_0 info
python dd-steam-toolkit\run.py edit-save profile_0 max-resources
python dd-steam-toolkit\run.py edit-save profile_0 max-heroes
python dd-steam-toolkit\run.py edit-save profile_0 unlock-buildings
python dd-steam-toolkit\run.py edit-save profile_0 max-dungeons
python dd-steam-toolkit\run.py edit-save profile_0 add-trinket --trinket-id ancestors_map --amount 2
python dd-steam-toolkit\run.py edit-save profile_0 edit-hero --guid 1 --hp 1.0 --stress 0 --xp 48 --weapon 4 --armour 4 --virtue vigorous --revive
python dd-steam-toolkit\run.py edit-save profile_0 set-raw --file persist.game.json --path estatename --type string --value "余烬庄园"

# 5. 一键套用规则修改器预设并安装到 Steam mods/ 且置顶挂载到 profile_0
python dd-steam-toolkit\run.py build-mod --preset adventurer --install-steam --enable-slot profile_0

# 卸载规则修改器并恢复原版
python dd-steam-toolkit\run.py build-mod --uninstall

# 6. 查看或管理 Steam 档位 Mod 订阅
python dd-steam-toolkit\run.py mods list --slot profile_0
python dd-steam-toolkit\run.py mods enable --slot profile_0 --mod-id torch_content_modifier --source mod --top
```

### 方式三：可选打包为独立单文件 `.exe`（Windows 免 Python 分发）

若本机已安装 `pyinstaller`（`pip install pyinstaller`），可执行：
```powershell
python dd-steam-toolkit\build_exe.py
```
构建完成后会在 `dd-steam-toolkit\dist\TorchArchivePC.exe` 生成独立单文件可执行程序，可发给未装 Python 的 Windows 玩家直接双击运行。

---

## Steam 云同步与数据安全说明

1. **快照存放位置**：默认保存在 `%LOCALAPPDATA%\TorchArchivePC\snapshots\`（Linux/macOS 为 `~/.local/share/TorchArchivePC/snapshots/`，可通过环境变量 `TORCH_PC_HOME` 自定义），不会污染游戏存档目录。
2. **进程保护**：默认检测 `Darkest.exe` 是否在运行；若游戏正在运行，写档、回档与改 Mod 操作会被安全拦截提示先退出游戏（也可在右上角按需勾选「允许运行中写入」）。**实时自动留档**只做只读打包，可在游戏运行全程放心开启。
3. **Steam Cloud 配合建议**：
   - 修改存档或执行回档前，请先正常退出《暗黑地牢》；
   - 操作完成后再从 Steam（或侧栏「启动 Steam《暗黑地牢》」）启动游戏；
   - 若 Steam 弹出「云存档冲突」提示，请选择 **「本地文件（上传至云端）」**，即可将修改/回档结果同步至 Steam Cloud。

---

## 模块结构与自动化测试

```text
dd-steam-toolkit/
├── run.py                      # 统一启动入口（默认桌面窗口 / 完整 CLI）
├── 启动余烬PC工坊.bat           # Windows 一键双击启动脚本
├── build_exe.py                # 可选 PyInstaller 单文件 .exe 打包脚本
├── torch_pc/
│   ├── dson.py                 # 无损二进制 DSON 编解码与 applied_ugcs_1_0 读写
│   ├── steam_env.py            # 多 Steam 账号/DLC/工坊探测、Darkest.exe 检测与启动
│   ├── snapshot_store.py       # SHA-256 原子快照仓、事务回档、Diff 与跨平台 ZIP 导入导出
│   ├── save_watcher.py         # 后台实时自动留档监控线程
│   ├── catalog.py              # 中文精选图鉴 + Steam 本体/DLC 动态全量索引
│   ├── save_editor.py          # 全维度二进制存档修改引擎
│   ├── content_modifier.py     # 非破坏性规则 Mod 生成器（覆盖本体 + DLC 英雄与建筑折扣）
│   ├── mod_manager.py          # Steam 创意工坊/本地 Mod 槽位排序管理器
│   ├── web_server.py           # 零依赖 127.0.0.1 安全本地服务与桌面窗口启动器
│   ├── cli.py                  # 全套 CLI 子命令
│   └── web_ui/                 # 余烬深色前端（index.html / style.css / app.js）
└── tests/
    └── test_toolkit.py         # 单元测试 + HTTP 端到端测试 + 本机真实 Steam 存档副本回归测试
```

运行完整自动化测试：
```powershell
python -m unittest discover -s dd-steam-toolkit\tests -v
```
