"""PC complete non-destructive game content & balance modifier generating standard DD override Mods
(compatible with both PC Steam and Android TorchLauncher).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import shutil
from typing import Optional
import zipfile

MOD_ID = "torch_content_modifier"


@dataclass
class ModifierConfig:
    stack_multiplier: int = 1           # 1, 2, 5, 10, 99
    free_supplies: bool = False
    hero_trinket_slots: int = 2         # 2, 3, 4
    raid_inventory_slots: int = 16      # 16, 20, 24
    combat_speed_scale: float = 1.0     # 1.0 .. 4.0
    player_crit_multiplier: float = 1.5 # 1.5 .. 3.0
    always_hit_cap: float = 0.95        # 0.95 or 1.00
    guaranteed_retreat: bool = False
    virtue_chance_percent: int = 25     # 25, 50, 75, 100
    stress_scale: float = 1.0           # 1.0, 0.5, 0.25, 0.0
    idle_stress_heal: float = 5.0       # 5, 25, 50, 100
    fast_resolve_xp: bool = False
    stage_coach_multiplier: int = 1     # 1, 2, 3
    roster_capacity_bonus: int = 0      # 0, 20, 50, 100
    building_cost_discount: int = 0     # 0..100 (% reduction in heirloom upgrade costs!)
    hero_hp_multiplier: float = 1.0     # 1.0 .. 3.0 (scales all hero *.info.darkest HP)
    hero_dmg_multiplier: float = 1.0    # 1.0 .. 3.0 (scales all hero *.info.darkest DMG)
    hero_speed_bonus: int = 0           # 0 .. +10 SPD across all heroes
    hero_dodge_bonus: int = 0           # 0 .. +30 DODGE across all heroes


def _project_xml() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<project>
    <PreviewIconFile></PreviewIconFile>
    <ItemDescriptionShort/>
    <ModDataPath></ModDataPath>
    <Title>余烬完整游戏修改器 (Torch Content Modifier)</Title>
    <Language>schinese</Language>
    <UpdateDetails/>
    <Visibility>private</Visibility>
    <UploadMode>direct_upload</UploadMode>
    <VersionMajor>2</VersionMajor>
    <VersionMinor>0</VersionMinor>
    <TargetBuild>0</TargetBuild>
    <Tags>
        <Tags>Gameplay</Tags>
        <Tags>Tweaks</Tags>
    </Tags>
    <ItemDescription>由 dd-steam-converter 完整修改器生成的通用非破坏性覆盖 Mod（PC 与安卓通用）。</ItemDescription>
</project>
"""


def _scale_darkest_int_field(text: str, field: str, mult: int, force_zero: bool = False) -> str:
    pattern = re.compile(rf"(\.{re.escape(field)}\s+)(\d+)")

    def repl(m: re.Match) -> str:
        val = int(m.group(2))
        nxt = 0 if force_zero else max(1, val * mult) if val > 0 else 0
        return f"{m.group(1)}{nxt}"

    return pattern.sub(repl, text)


def _scale_hero_info_darkest(text: str, cfg: ModifierConfig) -> str:
    out_lines = []
    for line in text.splitlines():
        if line.lstrip().startswith("armour:") and cfg.hero_hp_multiplier != 1.0:
            line = re.sub(
                r"(\.hp\s+)(\d+)",
                lambda m: f"{m.group(1)}{max(1, round(int(m.group(2)) * cfg.hero_hp_multiplier))}",
                line,
            )
        if line.lstrip().startswith("armour:") and cfg.hero_dodge_bonus != 0:
            line = re.sub(
                r"(\.def\s+)(\d+(?:\.\d+)?)%",
                lambda m: f"{m.group(1)}{round(float(m.group(2)) + cfg.hero_dodge_bonus)}%",
                line,
            )
        if line.lstrip().startswith("weapon:") and cfg.hero_dmg_multiplier != 1.0:
            def scale_dmg(m: re.Match) -> str:
                lo = max(1, round(int(m.group(2)) * cfg.hero_dmg_multiplier))
                hi = max(lo, round(int(m.group(4)) * cfg.hero_dmg_multiplier))
                return f"{m.group(1)}{lo}{m.group(3)}{hi}"

            line = re.sub(r"(\.dmg\s+)(\d+)(\s+)(\d+)", scale_dmg, line)
        if line.lstrip().startswith("weapon:") and cfg.hero_speed_bonus != 0:
            line = re.sub(
                r"(\.spd\s+)(-?\d+)",
                lambda m: f"{m.group(1)}{int(m.group(2)) + cfg.hero_speed_bonus}",
                line,
            )
        out_lines.append(line)
    return "\n".join(out_lines) + "\n"


def generate_content_mod(
    output_mod_dir: Path,
    cfg: ModifierConfig,
    steam_dir: Optional[Path] = None,
) -> Path:
    if output_mod_dir.exists():
        shutil.rmtree(output_mod_dir)
    output_mod_dir.mkdir(parents=True, exist_ok=True)

    (output_mod_dir / "project.xml").write_text(_project_xml(), encoding="utf-8")
    (output_mod_dir / "modifier-config.json").write_text(
        json.dumps(asdict(cfg), indent=2, ensure_ascii=False), encoding="utf-8"
    )

    # 1. Inventory stacks & free supplies
    mult = max(1, int(cfg.stack_multiplier))
    if mult != 1 or cfg.free_supplies:
        if steam_dir and (steam_dir / "inventory").is_dir():
            for fname in (
                "base.currency.inventory.items.darkest",
                "base.gem.inventory.items.darkest",
                "base.supply.inventory.items.darkest",
            ):
                src = steam_dir / "inventory" / fname
                if src.is_file():
                    txt = src.read_text(encoding="utf-8", errors="replace")
                    if mult != 1:
                        txt = _scale_darkest_int_field(txt, "base_stack_limit", mult)
                        txt = txt.replace(".can_modify_stack_limit false", ".can_modify_stack_limit true")
                    if cfg.free_supplies and fname == "base.supply.inventory.items.darkest":
                        txt = _scale_darkest_int_field(txt, "purchase_gold_value", 1, force_zero=True)
                    dst = output_mod_dir / "inventory" / fname
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    dst.write_text(txt, encoding="utf-8")
        else:
            inv_dir = output_mod_dir / "inventory"
            inv_dir.mkdir(parents=True, exist_ok=True)
            (inv_dir / "base.currency.inventory.items.darkest").write_text(
                f'inventory_item:\t.type "gold"\t.id ""\t.base_stack_limit {1750 * mult}\t.purchase_gold_value 0\t.sell_gold_value 0\n'
                f'inventory_item:\t.type "heirloom"\t.id "portrait"\t.base_stack_limit {3 * mult}\t.purchase_gold_value 0\t.sell_gold_value 0\n'
                f'inventory_item:\t.type "heirloom"\t.id "bust"\t.base_stack_limit {6 * mult}\t.purchase_gold_value 0\t.sell_gold_value 0\n'
                f'inventory_item:\t.type "heirloom"\t.id "crest"\t.base_stack_limit {12 * mult}\t.purchase_gold_value 0\t.sell_gold_value 0\n'
                f'inventory_item:\t.type "heirloom"\t.id "deed"\t.base_stack_limit {6 * mult}\t.purchase_gold_value 0\t.sell_gold_value 0\n',
                encoding="utf-8",
            )

    # 2. System inventory configs (trinket slots / raid slots)
    if cfg.hero_trinket_slots != 2 or cfg.raid_inventory_slots != 16:
        inv_dir = output_mod_dir / "inventory"
        inv_dir.mkdir(parents=True, exist_ok=True)
        if steam_dir and (steam_dir / "inventory/base.inventory.system_configs.darkest").is_file():
            txt = (steam_dir / "inventory/base.inventory.system_configs.darkest").read_text(encoding="utf-8")
            txt = re.sub(
                r'(\.type\s+"raid"\s+\.max_slots\s+)\d+',
                rf"\g<1>{max(16, min(32, cfg.raid_inventory_slots))}",
                txt,
            )
            txt = re.sub(
                r'(\.type\s+"hero_equipped_trinkets"\s+\.max_slots\s+)\d+',
                rf"\g<1>{max(2, min(4, cfg.hero_trinket_slots))}",
                txt,
            )
        else:
            txt = (
                f'inventory_system_config:\t.type "raid"\t.max_slots {cfg.raid_inventory_slots}\t.use_stack_limits true\n'
                f'inventory_system_config:\t.type "hero_equipped_trinkets"\t.max_slots {cfg.hero_trinket_slots}\t.use_stack_limits true\n'
            )
        (inv_dir / "base.inventory.system_configs.darkest").write_text(txt, encoding="utf-8")

    # 3. Combat speed
    if abs(cfg.combat_speed_scale - 1.0) > 0.01:
        sp_dir = output_mod_dir / "shared"
        sp_dir.mkdir(parents=True, exist_ok=True)
        (sp_dir / "speed.rules.json").write_text(
            json.dumps({"time_scale_combat": round(cfg.combat_speed_scale, 2)}, indent=4),
            encoding="utf-8",
        )

    # 4. Shared combat/stress/virtue rules (only patch if Steam template exists or changed)
    if (
        abs(cfg.player_crit_multiplier - 1.5) > 0.01
        or abs(cfg.always_hit_cap - 0.95) > 0.001
        or cfg.guaranteed_retreat
        or cfg.virtue_chance_percent != 25
        or abs(cfg.stress_scale - 1.0) > 0.01
    ):
        rules_src = steam_dir / "shared/rules.json" if steam_dir else None
        if rules_src and rules_src.is_file():
            rules = json.loads(rules_src.read_text(encoding="utf-8"))
        else:
            rules = {}
        rules["cutoffAlwaysHit"] = round(cfg.always_hit_cap, 2)
        rules["player_crit_multiplier_table"] = [{"chance": 1, "value": round(cfg.player_crit_multiplier, 2)}]
        if cfg.guaranteed_retreat:
            rules["combat_retreat_chance"] = 1.0
            rules["combat_retreat_stress"] = 0.0
        if cfg.virtue_chance_percent != 25:
            aff = max(0.0, min(0.95, 1.0 - cfg.virtue_chance_percent / 100.0))
            crit = max(0.05, min(1.0, cfg.virtue_chance_percent / 100.0))
            rules["affliction_base_onset_chance"] = round(aff, 2)
            rules["affliction_critical_success_chance"] = round(crit, 2)
            if cfg.virtue_chance_percent >= 100:
                rules["affliction_min_chance"] = 0.0
                rules["affliction_max_chance"] = 0.0
        if abs(cfg.stress_scale - 1.0) > 0.01:
            s = max(0.0, cfg.stress_scale)
            if "monster_crit" in rules:
                rules["monster_crit"]["stress_damage"] = round(rules["monster_crit"].get("stress_damage", 10) * s)
                rules["monster_crit"]["companion_stress_damage"] = round(
                    rules["monster_crit"].get("companion_stress_damage", 5) * s
                )
            for key in (
                "passed_turn_stress_dmg",
                "deaths_door_self_stress_dmg",
                "deaths_door_companion_stress_dmg",
                "effectiveDifficultyBarkStress",
            ):
                if key in rules:
                    rules[key] = round(float(rules[key]) * s, 2)
            if "effectiveDifficultyDungeonStartingStress" in rules:
                rules["effectiveDifficultyDungeonStartingStress"] = [
                    round(x * s, 1) for x in rules["effectiveDifficultyDungeonStartingStress"]
                ]
            if "effectiveDifficultyStressDmgModifiers" in rules:
                rules["effectiveDifficultyStressDmgModifiers"] = [
                    round(x * s, 2) for x in rules["effectiveDifficultyStressDmgModifiers"]
                ]
        sp_dir = output_mod_dir / "shared"
        sp_dir.mkdir(parents=True, exist_ok=True)
        (sp_dir / "rules.json").write_text(json.dumps(rules, indent=4), encoding="utf-8")

    # 5. Roster variables
    if abs(cfg.idle_stress_heal - 5.0) > 0.01 or cfg.fast_resolve_xp:
        rv_dir = output_mod_dir / "campaign/roster"
        rv_dir.mkdir(parents=True, exist_ok=True)
        rv = {
            "name_id_format": "hero_name_%d",
            "resolve_level_thresholds": [0, 1, 2, 4, 6, 9, 12] if cfg.fast_resolve_xp else [0, 2, 8, 14, 24, 36, 48],
            "town_visit_town_progression": {"idle_hero_stress_heal": round(cfg.idle_stress_heal, 1)},
            "town_visit_non_town_progression": {"idle_hero_stress_heal": 0.0},
        }
        (rv_dir / "roster.variables.json").write_text(json.dumps(rv, indent=4), encoding="utf-8")

    # 6. Stage coach
    if cfg.stage_coach_multiplier != 1 or cfg.roster_capacity_bonus != 0:
        sc_src = steam_dir / "campaign/town/buildings/stage_coach/stage_coach.building.json" if steam_dir else None
        if sc_src and sc_src.is_file():
            sc = json.loads(sc_src.read_text(encoding="utf-8"))
        else:
            sc = {
                "on_start_town_visit_priority": 0,
                "requirements": {"number_of_quests_finished": 0, "highest_dungeon_level": 0},
                "data": {
                    "stores": [
                        {
                            "id": "hero_recruit",
                            "data": {
                                "first_hero_classes": ["plague_doctor", "vestal"],
                                "number_of_recruits_upgrades": [{"amount": 2}],
                                "roster_size_upgrades": [{"amount": 9}],
                            },
                        }
                    ]
                },
            }
        rec_data = sc["data"]["stores"][0]["data"]
        for entry in rec_data.get("number_of_recruits_upgrades", []):
            entry["amount"] = int(entry["amount"]) * max(1, cfg.stage_coach_multiplier)
        for entry in rec_data.get("roster_size_upgrades", []):
            entry["amount"] = int(entry["amount"]) + max(0, cfg.roster_capacity_bonus)
        sc_dst = output_mod_dir / "campaign/town/buildings/stage_coach/stage_coach.building.json"
        sc_dst.parent.mkdir(parents=True, exist_ok=True)
        sc_dst.write_text(json.dumps(sc, indent=4), encoding="utf-8")

    # 7. Hero stats scaling across all base & DLC heroes (when Steam dir provided)
    if steam_dir and (
        cfg.hero_hp_multiplier != 1.0
        or cfg.hero_dmg_multiplier != 1.0
        or cfg.hero_speed_bonus != 0
        or cfg.hero_dodge_bonus != 0
    ):
        for hero_info in sorted((steam_dir / "heroes").glob("*/*.info.darkest")):
            rel = hero_info.relative_to(steam_dir)
            dst = output_mod_dir / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(_scale_hero_info_darkest(hero_info.read_text(encoding="utf-8", errors="replace"), cfg), encoding="utf-8")

    # 8. Building upgrade cost discount (when Steam dir provided)
    if steam_dir and cfg.building_cost_discount > 0:
        factor = max(0.0, 1.0 - min(100, cfg.building_cost_discount) / 100.0)
        for up_file in sorted((steam_dir / "upgrades/building").glob("*.upgrades.json")):
            data = json.loads(up_file.read_text(encoding="utf-8"))
            for tree in data.get("trees", []):
                for req in tree.get("requirements", []):
                    for cost in req.get("currency_cost", []):
                        cost["amount"] = round(int(cost.get("amount", 0)) * factor)
            dst = output_mod_dir / "upgrades/building" / up_file.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(json.dumps(data, indent=4), encoding="utf-8")

    return output_mod_dir


def pack_mod_zip(mod_dir: Path, zip_path: Path) -> Path:
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(mod_dir.rglob("*")):
            if f.is_file():
                zf.write(f, f"{mod_dir.name}/{f.relative_to(mod_dir).as_posix()}")
    return zip_path
