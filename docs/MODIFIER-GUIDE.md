# 游戏内容完整修改器使用与开发指南（Android + PC）

> 返回导航：[工作区总索引](../INDEX.md) · [项目总览](../README.md) · [PC Steam 独立存档与修改器](../dd-steam-toolkit/README.md) · [系统架构](ARCHITECTURE.md) · [PC 工具链说明](../dd-steam-converter/README.md)

《暗黑地牢》余烬项目在**手机启动器端**与 **PC 桌面端**（推荐纯 PC Steam 玩家直接使用独立的 [dd-steam-toolkit/](../dd-steam-toolkit/README.md)）同时提供完整双轨修改能力：
1. **二进制存档修改器**：直接无损读写档位 `profile_N/` 内的 `persist.*.json`（含英雄嵌套 DSON）；
2. **非破坏性游戏规则修改器**：生成标准覆盖 Mod `torch_content_modifier`，PC Steam 与安卓端通用，可随时一键启用或卸载，不破坏原始资源包 `dd_assets.pak`。

---

## 1. 双端修改器模块对照

| 能力模块 | Android 启动器端 | PC Steam 独立工坊（推荐） | PC 转换器附带端 |
| :--- | :--- | :--- | :--- |
| 修改入口 | 主界面「修改」页签（[MainActivity.java](../app/src/main/java/dev/torcharchive/dd/MainActivity.java) + [ModifierSection.java](../app/src/main/java/dev/torcharchive/dd/ModifierSection.java)） | [dd-steam-toolkit/run.py](../dd-steam-toolkit/run.py) / [启动余烬PC工坊.bat](../dd-steam-toolkit/启动余烬PC工坊.bat)（独立桌面窗口 + 完整 CLI） | [dd-steam-converter/run.py](../dd-steam-converter/run.py) `edit-save` / `build-mod` / `gui` |
| DSON 编解码底层 | [DsonCodec.java](../app/src/main/java/dev/torcharchive/game/DsonCodec.java) | [torch_pc/dson.py](../dd-steam-toolkit/torch_pc/dson.py) | [dson.py](../dd-steam-converter/dd_converter/dson.py) |
| 二进制存档编辑 | [SaveEditor.java](../app/src/main/java/dev/torcharchive/game/SaveEditor.java) | [torch_pc/save_editor.py](../dd-steam-toolkit/torch_pc/save_editor.py) | [save_editor.py](../dd-steam-converter/dd_converter/save_editor.py) |
| 规则覆盖 Mod 生成 | [ContentModGenerator.java](../app/src/main/java/dev/torcharchive/game/ContentModGenerator.java) | [torch_pc/content_modifier.py](../dd-steam-toolkit/torch_pc/content_modifier.py)（含本体+DLC 英雄与建筑折扣） | [content_modifier.py](../dd-steam-converter/dd_converter/content_modifier.py) |
| 写前安全与自动留档 | 停止 `:nx` + [SnapshotStore.java](../app/src/main/java/dev/torcharchive/game/SnapshotStore.java) | `Darkest.exe` 检测 + [snapshot_store.py](../dd-steam-toolkit/torch_pc/snapshot_store.py) + 后台实时自动留档 [save_watcher.py](../dd-steam-toolkit/torch_pc/save_watcher.py) | 自动备份到 `.torch-backups/` |

---

## 2. 二进制 DSON 格式与关键数据结构

Red Hook 存档文件（`persist.game.json`、`persist.estate.json`、`persist.roster.json`、`persist.upgrades.json`、`persist.campaign_mash.json` 等）实质为魔数 `0x0000B101`、含 64 字节头部及 Meta1/Meta2 索引表的二进制 DSON 容器：

- **无损往返保证**：[DsonCodec.java](../app/src/main/java/dev/torcharchive/game/DsonCodec.java) 与 [dson.py](../dd-steam-converter/dd_converter/dson.py) 在节点中保留 `extraFlags`（bit 31 与 bit 1）和原始标量载荷；修改特定节点后重新序列化，可确保 `persist.game.json` 逐字节精确、其余所有文件长度与解析树完全等价。
- **英雄嵌套 DSON（`persist.roster.json`）**：
  - 每个英雄节点位于 `heroes.<guid>.hero_file_data.raw_data`，其字节载荷为：`[uint32_le 长度前缀] + [内部独立 DSON (0x0000B101)]`；
  - 关键可改字段：
    - `actor.current_hp`：`float32`，有效范围 `0.0 .. 1.0`（生命百分比）；
    - `m_Stress`：`float32`，范围 `0 .. 200`（压力值）；
    - `resolveXp` / `weapon_rank` / `armour_rank` / `roster.status`：`int32`（`status`: `0=空闲`、`1=探险中`、`2=城镇设施中`、`3=已阵亡`，修改为 `0` 即可原地复活英雄）；
    - 怪癖列表（锁定/新增正面怪癖）与疾病列表（清除全疾病）以及美德/折磨（`virtue` / `affliction`）。
- **城镇建筑升级哈希（`persist.upgrades.json`）**：
  - Red Hook 使用有符号字节哈希 `h = h * 53 + (int8)b` 映射升级树 `tree_id`（对应 [dson.py](../dd-steam-converter/dd_converter/dson.py) 中的 `dd_hash()` 与 Java 侧等价实现）；
  - 例如 `sanitarium.cost` 哈希为 `32447680`，`blacksmith.weapon` 哈希为 `-884840462`；建筑全解锁即向 `persist.upgrades.json` 写入全部 20 组建筑升级树的每一级节点。

---

## 3. 二进制存档修改器使用说明

### 手机端（「修改」页签）
打开「余烬启动器」切至 **修改** 页签，选择目标档位（`profile_0`–`profile_8`）即可直接操作，无需手工搬运存档文件：
1. **庄园与货币**：修改庄园名称、游戏模式（`radiant` / `darkest` / `new_game_plus`）、无尽最高波数，编辑或一键拉满金币、半身像、肖像、地契、徽章、彗星碎片、建筑蓝图；
2. **英雄全维度编辑**：查看全部在册及阵亡英雄状态；一键全员满状态（满血、零压、6 级、4 阶锻造/防具、清病复活）或逐个编辑 HP、压力、XP、武器/护甲、美德/折磨、锁定顶级怪癖；
3. **饰品仓库增删**：基于内置全流派图鉴（[CatalogData.java](../app/src/main/java/dev/torcharchive/game/CatalogData.java)）或手动输入任意饰品 ID 添加/移除饰品；
4. **建筑与原始树**：一键全解锁 8 座城镇建筑升级、任意 `persist.*.json` 树节点路径浏览与标量修改（支持 `int` / `float` / `bool` / `string`）。

### PC CLI 常用命令（`dd-steam-converter/run.py edit-save`）

```powershell
# 查看档位概览（庄园、英雄列表与当前饰品）
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" info

# 一键拉满金币/传家宝/碎片/蓝图
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" max-resources

# 批量提升全部英雄至 Lv6 / 4阶装备 / 满血 / 零压力 / 清除疾病
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" max-heroes

# 修改指定 GUID 英雄属性
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" edit-hero `
  --guid <HERO_GUID> --hp 1.0 --stress 0 --xp 48 --weapon 4 --armour 4 --virtue focused --revive

# 添加指定饰品 / 全解锁城镇建筑 / 修改原始 DSON 标量字段
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" add-trinket --trinket-id ancestors_map --amount 1
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" unlock-buildings
python dd-steam-converter\run.py edit-save "D:\Steam\userdata\<uid>\262060\remote\profile_0" set-raw --file persist.game.json --path estate_name --type string --value "余烬庄园"
```

---

## 4. 非破坏性内容修改器（`torch_content_modifier`）

两种平台均会在目标 `mods/torch_content_modifier/` 下生成标准 Darkest Dungeon Mod 目录（含 `project.xml` 与覆盖的 `.darkest` / `.json` 文件），并可通过一键删除与取消档位 `applied_ugcs_1_0` 订阅完整还原。

### 支持参数对照表

| 功能项 | 安卓端（[ContentModGenerator.java](../app/src/main/java/dev/torcharchive/game/ContentModGenerator.java)） | PC 端（[content_modifier.py](../dd-steam-converter/dd_converter/content_modifier.py) / CLI） | 覆盖的游戏文件 |
| :--- | :--- | :--- | :--- |
| 金币/传家宝/宝石/补给堆叠倍率 | `stackMultiplier`（`1..99`） | `--stack-mult` | `inventory/base.{currency,gem,supply}.inventory.items.darkest` |
| 补给品商店免费 | `freeSupplies` | `--free-supplies` | `inventory/base.supply.inventory.items.darkest` |
| 英雄饰品装备槽位 | `heroTrinketSlots`（`2..4`） | `--trinket-slots` | `inventory/base.inventory.system_configs.darkest` |
| 远征背包格数 | `raidInventorySlots`（`16..24`） | `--raid-slots` | `inventory/base.inventory.system_configs.darkest` |
| 战斗动画倍速 | `combatSpeedScale`（`1.0..3.0`） | `--combat-speed`（最高 `4.0`） | `shared/speed.rules.json` |
| 玩家暴击倍率 | `playerCritMultiplier` | `--crit-mult` | `shared/rules.json` |
| 解除 95% 命中上限 | `alwaysHitCap` | `--always-hit` | `shared/rules.json` |
| 100% 撤退成功 | `guaranteedRetreat` | `--guaranteed-retreat` | `shared/rules.json` |
| 美德概率（%） | `virtueChancePercent`（`25..100`） | `--virtue-chance` | `shared/rules.json` |
| 受压倍率 | `stressScale`（`0.0..1.0`） | `--stress-scale` | `shared/rules.json` |
| 每周城镇闲置回压 / 极速升级 | `idleStressHeal`、`fastResolveXp` | `--idle-stress-heal`、`--fast-xp` | `campaign/roster/roster.variables.json` |
| 马车刷新倍率 / 名册扩容 | `stageCoachMultiplier`、`rosterCapacityBonus` | `--stagecoach-mult`、`--roster-bonus` | `campaign/town/buildings/stage_coach/stage_coach.building.json` |
| 全英雄 HP / 伤害倍率与速度 / 闪避加成 | *(PC 端扫描 Steam `heroes/` 生成)* | `--hero-hp-mult`、`--hero-dmg-mult`、`--hero-spd-bonus`、`--hero-dodge-bonus` | 需本机 Steam 目录生成后打 ZIP 导入安卓使用 |
| 城镇建筑升级折扣（%） | *(PC 端基于 Steam `upgrades/` 生成)* | `--building-discount`（`0..100`） | `upgrades/town/*.upgrades.json` |

### 在 PC 上生成带全英雄数值/建筑折扣的通用 Mod 并安装到手机或 Steam

```powershell
# 直接安装到 Steam 本地 mods/ 并同时打包一份可导入安卓启动器的 ZIP
python dd-steam-converter\run.py build-mod `
  --install-steam `
  --zip-out "dist\torch_content_modifier.zip" `
  --stack-mult 10 --free-supplies --trinket-slots 4 --raid-slots 24 `
  --combat-speed 2.0 --crit-mult 2.5 --always-hit --guaranteed-retreat `
  --virtue-chance 100 --stress-scale 0.5 --idle-stress-heal 50 --fast-xp `
  --stagecoach-mult 3 --roster-bonus 50 `
  --building-discount 50 --hero-hp-mult 1.5 --hero-dmg-mult 1.5 --hero-spd-bonus 4 --hero-dodge-bonus 15
```

生成 `dist\torch_content_modifier.zip` 后，在手机端「余烬启动器 → 模组 → 导入模组 ZIP」选中并在目标档位启用即可生效。
