"""Unit and real-fixture tests for dd-steam-converter."""

from __future__ import annotations

import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dd_converter.content_modifier import ModifierConfig, generate_content_mod, pack_mod_zip
from dd_converter.dds_encoder import encode_rgba_to_dxt3_bytes
from dd_converter.dson import (
    convert_steam_mods_to_local,
    dd_hash,
    is_dson,
    parse_dson,
    read_applied_mods,
    serialize_dson,
)
from dd_converter.migration import build_migration_zip
from dd_converter.save_editor import (
    add_trinket,
    batch_improve_heroes,
    dump_profile_json,
    edit_raw_field,
    list_heroes,
    list_trinkets,
    read_estate,
    remove_trinket,
    unlock_all_buildings,
    update_hero,
    write_estate,
)
from dd_converter.steam_detect import SaveSlotInfo, WorkshopMod

REAL_STEAM_DIR = Path(r"D:\Steam\steamapps\common\DarkestDungeon")
REAL_PROFILE_0 = Path(r"D:\Steam\userdata\1566444036\262060\remote\profile_0")


class DsonAndConverterTests(unittest.TestCase):
    def test_dd_hash_matches_known_upgrade_tree(self) -> None:
        self.assertEqual(dd_hash("sanitarium.cost"), 32447680)
        self.assertEqual(dd_hash("blacksmith.weapon"), -884840462)

    @unittest.skipUnless(REAL_PROFILE_0.is_dir(), "Real Steam profile_0 fixture not present")
    def test_lossless_dson_roundtrip_all_profile_files(self) -> None:
        from dd_converter.dson import to_editable_dict

        checked = 0
        for p in sorted(REAL_PROFILE_0.glob("*.json")):
            raw = p.read_bytes()
            if not is_dson(raw):
                continue
            doc = parse_dson(raw)
            reserialized = serialize_dson(doc)
            self.assertEqual(len(reserialized), len(raw), f"Length mismatch in {p.name}")
            if p.name == "persist.game.json":
                self.assertEqual(raw, reserialized)
            # Canonical re-serialization must be strictly idempotent and parsed trees identical
            self.assertEqual(serialize_dson(parse_dson(reserialized)), reserialized)
            self.assertEqual(
                to_editable_dict(doc.root),
                to_editable_dict(parse_dson(reserialized).root),
                f"Parsed tree mismatch in {p.name}",
            )
            checked += 1
        self.assertGreaterEqual(checked, 10)

    @unittest.skipUnless(REAL_PROFILE_0.is_dir(), "Real Steam profile_0 fixture not present")
    def test_steam_to_mod_conversion(self) -> None:
        raw = (REAL_PROFILE_0 / "persist.game.json").read_bytes()
        converted, mod_names = convert_steam_mods_to_local(raw)
        self.assertTrue(mod_names)
        for _name, src in read_applied_mods(converted):
            self.assertEqual(src, "mod")

    def test_dds_dxt3_encoder_header_and_size(self) -> None:
        img = Image.new("RGBA", (13, 9), (200, 120, 60, 180))
        dds = encode_rgba_to_dxt3_bytes(img)
        self.assertEqual(dds[:4], b"DDS ")
        h, w = struct.unpack_from("<II", dds, 12)
        self.assertEqual((w, h), (13, 9))
        self.assertEqual(dds[84:88], b"DXT3")
        expected_blocks = ((13 + 3) // 4) * ((9 + 3) // 4)
        self.assertEqual(len(dds), 128 + expected_blocks * 16)

    @unittest.skipUnless(REAL_PROFILE_0.is_dir(), "Real Steam profile_0 fixture not present")
    def test_save_editor_and_migration_on_copy(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            prof = td_path / "profile_0"
            for f in REAL_PROFILE_0.iterdir():
                if f.is_file():
                    (prof).mkdir(parents=True, exist_ok=True)
                    (prof / f.name).write_bytes(f.read_bytes())

            write_estate(prof, estate_name="测试PC庄园", game_mode="radiant", wallet_updates={"gold": 555555, "shard": 333})
            est = read_estate(prof)
            self.assertEqual(est["estate_name"], "测试PC庄园")
            self.assertEqual(est["wallet"]["gold"], 555555)
            self.assertEqual(est["wallet"]["shard"], 333)
            self.assertTrue((td_path / ".torch-backups").is_dir())

            heroes = list_heroes(prof)
            self.assertTrue(heroes)
            first = heroes[0]
            update_hero(prof, first.guid, name="PC英雄", hp_fraction=1.0, stress=0.0, resolve_xp=48, weapon_rank=4, armour_rank=4, virtue="stalwart", revive=True)
            updated = next(h for h in list_heroes(prof) if h.guid == first.guid)
            self.assertEqual(updated.name, "PC英雄")
            self.assertEqual(updated.resolve_xp, 48)
            self.assertEqual(updated.virtue, "stalwart")

            self.assertGreater(batch_improve_heroes(prof, max_level_and_gear=True, heal_and_purify=True), 0)
            before_t = len(list_trinkets(prof))
            add_trinket(prof, "ancestors_map", 1)
            self.assertEqual(len(list_trinkets(prof)), before_t + 1)
            remove_trinket(prof, 0)
            self.assertEqual(len(list_trinkets(prof)), before_t)

            self.assertGreater(unlock_all_buildings(prof), 0)
            edit_raw_field(prof, "persist.game.json", ["totalelapsed"], "float", "999.5")
            dumped = dump_profile_json(prof)
            self.assertIn("persist.game.json", dumped)

            # Migration zip with fake workshop mod
            fake_mod = td_path / "ws_mod"
            fake_mod.mkdir()
            (fake_mod / "project.xml").write_text("<project><Title>TestMod</Title></project>", encoding="utf-8")
            mig_zip = td_path / "mig.zip"
            mods_seen = read_applied_mods((prof / "persist.game.json").read_bytes())
            avail = {mods_seen[0][0]: WorkshopMod(mods_seen[0][0], "TestMod", fake_mod, "workshop")} if mods_seen else {}
            slot_info = SaveSlotInfo("profile_0", prof, "测试", "radiant", "", [m[0] for m in mods_seen])
            summary = build_migration_zip([slot_info], avail, mig_zip)
            self.assertTrue(mig_zip.is_file())
            self.assertEqual(summary.slots, ["profile_0"])
            with zipfile.ZipFile(mig_zip) as zf:
                self.assertIn("saves/profile_0/persist.game.json", zf.namelist())
                self.assertIn("migration-manifest.json", zf.namelist())

    def test_content_modifier_generation(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            mod_dir = Path(td) / "torch_content_modifier"
            cfg = ModifierConfig(
                stack_multiplier=10,
                free_supplies=True,
                hero_trinket_slots=4,
                raid_inventory_slots=24,
                combat_speed_scale=2.0,
                player_crit_multiplier=2.5,
                always_hit_cap=1.0,
                guaranteed_retreat=True,
                virtue_chance_percent=100,
                stress_scale=0.5,
                idle_stress_heal=50.0,
                fast_resolve_xp=True,
                stage_coach_multiplier=2,
                roster_capacity_bonus=20,
                building_cost_discount=50,
                hero_hp_multiplier=1.5,
                hero_dmg_multiplier=1.5,
                hero_speed_bonus=3,
                hero_dodge_bonus=10,
            )
            steam_arg = REAL_STEAM_DIR if REAL_STEAM_DIR.is_dir() else None
            generate_content_mod(mod_dir, cfg, steam_arg)
            self.assertTrue((mod_dir / "project.xml").is_file())
            self.assertTrue((mod_dir / "shared/speed.rules.json").is_file())
            rules = json.loads((mod_dir / "shared/rules.json").read_text(encoding="utf-8"))
            self.assertEqual(rules["cutoffAlwaysHit"], 1.0)
            self.assertEqual(rules["combat_retreat_chance"], 1.0)
            z = pack_mod_zip(mod_dir, Path(td) / "mod.zip")
            self.assertTrue(z.is_file())


if __name__ == "__main__":
    unittest.main()
