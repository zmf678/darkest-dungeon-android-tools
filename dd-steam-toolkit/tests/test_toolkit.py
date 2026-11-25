"""Comprehensive unit & end-to-end tests for dd-steam-toolkit (PC Steam Edition)."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from http.server import ThreadingHTTPServer

from torch_pc.catalog import build_catalog_payload
from torch_pc.content_modifier import (
    MOD_ID,
    ModifierConfig,
    PRESETS,
    apply_to_steam,
    generate_content_mod,
    load_installed_config,
    pack_mod_zip,
    uninstall_from_steam,
)
from torch_pc.dson import (
    DsonDocument,
    DsonNode,
    bool_node,
    dd_hash,
    float_node,
    int_node,
    is_dson,
    parse_dson,
    read_applied_mods,
    serialize_dson,
    string_node,
    write_applied_mods,
)
from torch_pc.mod_manager import build_slot_mod_view, ensure_mod_in_slot, get_slot_mods, set_slot_mods
from torch_pc.save_editor import (
    add_trinket,
    batch_improve_heroes,
    edit_raw_scalar,
    inspect_raw_path,
    list_dson_files,
    list_heroes,
    list_slots,
    list_trinkets,
    read_building_progress,
    read_dungeon_xp,
    read_estate,
    remove_trinket,
    unlock_all_buildings,
    update_hero,
    write_dungeon_xp,
    write_estate,
)
from torch_pc.save_watcher import SaveWatcher
from torch_pc.snapshot_store import SnapshotStore
from torch_pc.steam_env import InstalledMod, detect_environment
from torch_pc.web_server import AppContext, make_handler


def _make_synthetic_profile(saves_root: Path, slot: str = "profile_0") -> Path:
    """Create a realistic minimal binary DSON Darkest Dungeon profile for portable CI tests."""
    prof = saves_root / slot
    prof.mkdir(parents=True, exist_ok=True)
    header = bytes.fromhex("01b10000000000004000000000000000")

    # persist.game.json
    game_root = DsonNode(
        "base_root",
        True,
        children=[
            string_node("estatename", "测试庄园"),
            string_node("game_mode", "darkest"),
            bool_node("inraid", False),
            string_node("date_time", "2026-11-25 18:00:00"),
            DsonNode(
                "applied_ugcs_1_0",
                True,
                children=[
                    DsonNode("0", True, children=[string_node("name", "111222333"), string_node("source", "Steam")]),
                    DsonNode("1", True, children=[string_node("name", "local_custom"), string_node("source", "mod")]),
                ],
            ),
            DsonNode("persistent_ugcs", True, children=[]),
        ],
    )
    (prof / "persist.game.json").write_bytes(serialize_dson(DsonDocument(header, game_root)))

    # persist.estate.json
    estate_root = DsonNode(
        "base_root",
        True,
        children=[
            DsonNode(
                "wallet",
                True,
                children=[
                    DsonNode("0", True, children=[int_node("amount", 12500), string_node("type", "gold")]),
                    DsonNode("1", True, children=[int_node("amount", 10), string_node("type", "bust")]),
                ],
            ),
            int_node("endless_wave_highscore", 12),
            DsonNode(
                "trinkets",
                True,
                children=[
                    DsonNode(
                        "items",
                        True,
                        children=[
                            DsonNode("0", True, children=[string_node("id", "focus_ring"), int_node("amount", 1)]),
                        ],
                    )
                ],
            ),
        ],
    )
    (prof / "persist.estate.json").write_bytes(serialize_dson(DsonDocument(header, estate_root)))

    # persist.roster.json (with embedded hero DSON)
    hero_inner = DsonNode(
        "base_root",
        True,
        children=[
            DsonNode("actor", True, children=[string_node("name", "迪斯马"), float_node("current_hp", 0.45)]),
            string_node("heroClass", "highwayman"),
            int_node("roster.status", 3),  # dead
            int_node("roster.before_on_start_town_visit_status", 3),
            float_node("m_Stress", 125.0),
            int_node("resolveXp", 8),
            int_node("weapon_rank", 1),
            int_node("armour_rank", 1),
            string_node("affliction_type_id", "paranoid"),
            int_node("affliction_severity", 1),
            string_node("virtue_type_id", ""),
            DsonNode(
                "quirks",
                True,
                children=[
                    DsonNode("rabies", True, children=[bool_node("is_locked", False), bool_node("is_new", False)]),
                    DsonNode("quick_reflexes", True, children=[bool_node("is_locked", False), bool_node("is_new", False)]),
                ],
            ),
        ],
    )
    raw_node = DsonNode("raw_data", False)
    raw_node.set_embedded_dson(serialize_dson(DsonDocument(header, hero_inner)))
    roster_root = DsonNode(
        "base_root",
        True,
        children=[
            int_node("highest_resolve_xp", 8),
            DsonNode(
                "heroes",
                True,
                children=[
                    DsonNode("1", True, children=[DsonNode("hero_file_data", True, children=[raw_node])]),
                ],
            ),
        ],
    )
    (prof / "persist.roster.json").write_bytes(serialize_dson(DsonDocument(header, roster_root)))

    # persist.upgrades.json
    up_root = DsonNode("base_root", True, children=[DsonNode("purchases", True, children=[])])
    (prof / "persist.upgrades.json").write_bytes(serialize_dson(DsonDocument(header, up_root)))

    # persist.progression.json
    prog_root = DsonNode(
        "base_root",
        True,
        children=[
            DsonNode(
                "dungeon",
                True,
                children=[
                    DsonNode("crypts", True, children=[int_node("xp", 12)]),
                ],
            )
        ],
    )
    (prof / "persist.progression.json").write_bytes(serialize_dson(DsonDocument(header, prog_root)))
    return prof


def _make_synthetic_steam_game(game_dir: Path) -> None:
    (game_dir / "inventory").mkdir(parents=True, exist_ok=True)
    (game_dir / "inventory/base.currency.inventory.items.darkest").write_text(
        'inventory_item:\t.type "gold"\t.id ""\t.base_stack_limit 1750\t.purchase_gold_value 0\t.sell_gold_value 0\n',
        encoding="utf-8",
    )
    (game_dir / "inventory/base.gem.inventory.items.darkest").write_text(
        'inventory_item:\t.type "gem"\t.id "ruby"\t.base_stack_limit 5\t.can_modify_stack_limit false\t.purchase_gold_value 0\t.sell_gold_value 1250\n',
        encoding="utf-8",
    )
    (game_dir / "inventory/base.supply.inventory.items.darkest").write_text(
        'inventory_item:\t.type "supply"\t.id "torch"\t.base_stack_limit 8\t.purchase_gold_value 75\t.sell_gold_value 5\n',
        encoding="utf-8",
    )
    (game_dir / "inventory/base.inventory.system_configs.darkest").write_text(
        'inventory_system_config:\t.type "raid"\t.max_slots 16\t.use_stack_limits true\n'
        'inventory_system_config:\t.type "hero_equipped_trinkets"\t.max_slots 2\t.use_stack_limits true\n',
        encoding="utf-8",
    )
    (game_dir / "shared/quirk").mkdir(parents=True, exist_ok=True)
    (game_dir / "shared/rules.json").write_text(
        json.dumps({"cutoffAlwaysHit": 0.95, "monster_crit": {"stress_damage": 10, "companion_stress_damage": 5}}),
        encoding="utf-8",
    )
    (game_dir / "shared/quirk/quirk_library.json").write_text(
        json.dumps({"quirks": [{"id": "custom_steam_pos", "is_positive": True, "is_disease": False}, {"id": "custom_steam_dis", "is_positive": False, "is_disease": True}]}),
        encoding="utf-8",
    )
    (game_dir / "trinkets").mkdir(parents=True, exist_ok=True)
    (game_dir / "trinkets/base.entries.trinkets.json").write_text(
        json.dumps({"entries": [{"id": "steam_extra_trinket", "rarity": "very_rare"}]}),
        encoding="utf-8",
    )
    (game_dir / "heroes/crusader").mkdir(parents=True, exist_ok=True)
    (game_dir / "heroes/crusader/crusader.info.darkest").write_text(
        'armour: .name "crusader_armour_0" .def 5% .prot 0 .hp 33 .spd 0\n'
        'weapon: .name "crusader_weapon_0" .atk 0% .dmg 6 12 .crit 3% .spd 1\n',
        encoding="utf-8",
    )
    # DLC hero + DLC monster (monster must NOT be touched!)
    (game_dir / "dlc/702540_shieldbreaker/heroes/shieldbreaker").mkdir(parents=True, exist_ok=True)
    (game_dir / "dlc/702540_shieldbreaker/heroes/shieldbreaker/shieldbreaker.info.darkest").write_text(
        'armour: .name "sb_armour_0" .def 8% .prot 0 .hp 20 .spd 0\n'
        'weapon: .name "sb_weapon_0" .atk 0% .dmg 5 10 .crit 6% .spd 5\n',
        encoding="utf-8",
    )
    (game_dir / "dlc/702540_shieldbreaker/monsters/snake_A").mkdir(parents=True, exist_ok=True)
    (game_dir / "dlc/702540_shieldbreaker/monsters/snake_A/snake_A.info.darkest").write_text(
        'armour: .name "snake" .def 10% .hp 50\n',
        encoding="utf-8",
    )
    (game_dir / "upgrades/building").mkdir(parents=True, exist_ok=True)
    (game_dir / "upgrades/building/blacksmith.upgrades.json").write_text(
        json.dumps({"trees": [{"requirements": [{"currency_cost": [{"type": "deed", "amount": 10}]}]}]}),
        encoding="utf-8",
    )


class TorchPcToolkitTests(unittest.TestCase):
    def test_dd_hash_and_applied_mods_preserve_steam_source(self):
        self.assertEqual(dd_hash("sanitarium.cost"), 32447680)
        self.assertEqual(dd_hash("blacksmith.weapon"), -884840462)

        with tempfile.TemporaryDirectory() as td:
            saves = Path(td) / "remote"
            prof = _make_synthetic_profile(saves)
            raw = (prof / "persist.game.json").read_bytes()
            self.assertEqual(read_applied_mods(raw), [("111222333", "Steam"), ("local_custom", "mod")])

            # Enable torch_content_modifier on top; existing Steam mod must stay source='Steam'
            changed = ensure_mod_in_slot(saves, "profile_0", MOD_ID, source="mod", enable=True, top_priority=True)
            self.assertTrue(changed)
            after = get_slot_mods(saves, "profile_0")
            self.assertEqual(
                after,
                [
                    {"name": MOD_ID, "source": "mod"},
                    {"name": "111222333", "source": "Steam"},
                    {"name": "local_custom", "source": "mod"},
                ],
            )

    def test_snapshot_store_dedup_restore_diff_and_import(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            saves = root / "remote"
            prof = _make_synthetic_profile(saves)
            store = SnapshotStore(root / "history")

            # Capture initial + dedup second capture
            first = store.capture_changed(saves, trigger="auto")
            self.assertEqual(len(first), 1)
            second = store.capture_changed(saves, trigger="auto")
            self.assertEqual(len(second), 0)

            # Mutate gold and capture again
            write_estate(prof, wallet_updates={"gold": 777777}, store=None)
            third = store.capture_changed(saves, trigger="auto")
            self.assertEqual(len(third), 1)
            self.assertEqual(third[0].gold, 777777)

            # Diff should show persist.estate.json changed
            diff = store.compare_snapshots(third[0].id, first[0].id)
            self.assertIn("profile_0/persist.estate.json", diff["changed"])

            # Restore first snapshot -> gold reverts to 12500 & pre-restore backup is created
            store.restore(saves, first[0].id)
            self.assertEqual(read_estate(prof)["wallet"]["gold"], 12500)
            self.assertGreaterEqual(len(store.list_records("profile_0")), 3)

            # Export and re-import as Android-style mod->Steam remap into profile_1
            exported = root / "android_export.zip"
            store.export_snapshot_zip(first[0].id, exported)
            imported = store.import_save_zip(
                saves,
                exported,
                target_slot="profile_1",
                convert_mod_to_steam=True,
                workshop_ids={"local_custom"},
            )
            self.assertEqual(imported, ["profile_1"])
            self.assertEqual(
                get_slot_mods(saves, "profile_1"),
                [{"name": "111222333", "source": "Steam"}, {"name": "local_custom", "source": "Steam"}],
            )

            # Corrupted checksum must be rejected
            zp = store.archive_path(first[0].id)
            zp.write_bytes(zp.read_bytes() + b"tamper")
            with self.assertRaises(IOError):
                store.restore(saves, first[0].id)

    def test_save_watcher_auto_captures_on_disk_change(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            saves = root / "remote"
            prof = _make_synthetic_profile(saves)
            store = SnapshotStore(root / "history")
            captured = []
            watcher = SaveWatcher(store, saves, poll_interval_sec=0.5, debounce_sec=0.5, on_capture=captured.append)
            self.assertTrue(watcher.start())
            try:
                write_estate(prof, wallet_updates={"gold": 88888}, store=None)
                deadline = time.time() + 4.0
                while not captured and time.time() < deadline:
                    time.sleep(0.1)
                self.assertEqual(len(captured), 1)
                self.assertEqual(captured[0].gold, 88888)
            finally:
                watcher.stop()

    def test_complete_save_editor_and_raw_nested_dson(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            saves = root / "remote"
            prof = _make_synthetic_profile(saves)
            store = SnapshotStore(root / "history")

            self.assertEqual(len(list_slots(saves)), 1)
            write_estate(prof, estate_name="余烬城堡", game_mode="radiant", wallet_updates={"gold": 500000, "shard": 200}, endless_highscore=99, store=store)
            est = read_estate(prof)
            self.assertEqual(est["estate_name"], "余烬城堡")
            self.assertEqual(est["game_mode"], "radiant")
            self.assertEqual(est["wallet"]["gold"], 500000)
            self.assertEqual(est["wallet"]["shard"], 200)
            self.assertEqual(est["endless_wave_highscore"], 99)

            # Hero editing + quirks
            update_hero(
                prof,
                guid="1",
                name="余烬迪斯马",
                hp_fraction=1.0,
                stress=0.0,
                resolve_xp=48,
                weapon_rank=4,
                armour_rank=4,
                virtue="vigorous",
                revive=True,
                quirks=[("luminous", True), ("corvid_eye", True)],
                store=store,
            )
            h = list_heroes(prof)[0]
            self.assertEqual(h.name, "余烬迪斯马")
            self.assertEqual(h.status, 0)
            self.assertEqual(h.resolve_level, 6)
            self.assertEqual(h.virtue, "vigorous")
            self.assertEqual(h.affliction, "")
            self.assertEqual(h.disease_count, 0)
            self.assertEqual([(q["id"], q["locked"]) for q in h.quirks], [("luminous", True), ("corvid_eye", True)])

            # Trinkets
            add_trinket(prof, "ancestors_map", 2, store=store)
            tr = list_trinkets(prof)
            self.assertEqual(len(tr), 3)
            remove_trinket(prof, 0, store=store)
            self.assertEqual(len(list_trinkets(prof)), 2)

            # Buildings & Dungeons
            changed = unlock_all_buildings(prof, store=store)
            self.assertGreater(changed, 80)
            bprog = read_building_progress(prof)
            self.assertTrue(all(b["unlocked"] == b["total"] for b in bprog))

            write_dungeon_xp(prof, {"crypts": 150, "cove": 150}, store=store)
            dmap = {d["id"]: d for d in read_dungeon_xp(prof)}
            self.assertEqual(dmap["crypts"]["level"], 7)
            self.assertEqual(dmap["cove"]["level"], 7)

            # Raw DSON penetrating embedded hero DSON
            self.assertIn("persist.roster.json", list_dson_files(prof))
            inner_entries = inspect_raw_path(prof, "persist.roster.json", ["heroes", "1", "hero_file_data", "raw_data"])
            keys = {e.key for e in inner_entries}
            self.assertIn("m_Stress", keys)
            edit_raw_scalar(prof, "persist.roster.json", ["heroes", "1", "hero_file_data", "raw_data", "m_Stress"], "float", "15.5", store=store)
            self.assertAlmostEqual(list_heroes(prof)[0].stress, 15.5, places=1)

            # Every write created a pre-edit snapshot
            self.assertGreaterEqual(len(store.list_records("profile_0")), 6)

    def test_content_modifier_covers_dlc_heroes_and_preserves_steam_mods(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            game = root / "DarkestDungeon"
            saves = root / "remote"
            _make_synthetic_steam_game(game)
            _make_synthetic_profile(saves)
            store = SnapshotStore(root / "history")

            cat = build_catalog_payload(game)
            self.assertTrue(any(t["id"] == "steam_extra_trinket" for t in cat["trinkets"]))
            self.assertTrue(any(q["id"] == "custom_steam_pos" for q in cat["positive_quirks"]))
            self.assertTrue(any(d["id"] == "custom_steam_dis" for d in cat["diseases"]))

            cfg = PRESETS["godmode"]
            info = apply_to_steam(game, cfg, saves_root=saves, enable_for_slot="profile_0", store=store)
            self.assertEqual(info["heroes_patched"], 2)  # crusader + DLC shieldbreaker (NOT snake monster!)
            self.assertEqual(info["buildings_patched"], 1)

            mod_dir = game / "mods" / MOD_ID
            self.assertTrue((mod_dir / "heroes/crusader/crusader.info.darkest").is_file())
            self.assertTrue((mod_dir / "dlc/702540_shieldbreaker/heroes/shieldbreaker/shieldbreaker.info.darkest").is_file())
            self.assertFalse((mod_dir / "dlc/702540_shieldbreaker/monsters/snake_A/snake_A.info.darkest").exists())
            self.assertIn(".hp 66", (mod_dir / "heroes/crusader/crusader.info.darkest").read_text(encoding="utf-8"))
            self.assertIn(".hp 40", (mod_dir / "dlc/702540_shieldbreaker/heroes/shieldbreaker/shieldbreaker.info.darkest").read_text(encoding="utf-8"))

            installed, loaded = load_installed_config(game)
            self.assertTrue(installed)
            self.assertEqual(loaded.stack_multiplier, 99)

            # Workshop mod 111222333 MUST remain source='Steam'
            mods = get_slot_mods(saves, "profile_0")
            self.assertEqual(mods[0], {"name": MOD_ID, "source": "mod"})
            self.assertIn({"name": "111222333", "source": "Steam"}, mods)

            # Uninstall cleanly removes mod dir & unsubscribes from profile_0
            res = uninstall_from_steam(game, saves_root=saves, store=store)
            self.assertTrue(res["removed_dir"])
            self.assertEqual(res["unsubscribed_slots"], ["profile_0"])
            self.assertFalse(mod_dir.exists())
            self.assertNotIn({"name": MOD_ID, "source": "mod"}, get_slot_mods(saves, "profile_0"))

    def test_http_server_end_to_end_and_csrf_protection(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            game = root / "DarkestDungeon"
            saves = root / "remote"
            home = root / "home"
            _make_synthetic_steam_game(game)
            _make_synthetic_profile(saves)

            ctx = AppContext(archive_home=home, explicit_game_dir=game, explicit_saves_root=saves, auto_watch=False)
            srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(ctx))
            port = srv.server_address[1]
            t = threading.Thread(target=srv.serve_forever, daemon=True)
            t.start()
            base = f"http://127.0.0.1:{port}"
            try:
                html = urlopen(f"{base}/").read().decode("utf-8")
                self.assertIn(ctx.token, html)

                st = json.loads(urlopen(f"{base}/api/state").read().decode("utf-8"))
                self.assertEqual(len(st["slots"]), 1)

                # Missing token must be rejected with 403
                bad_req = Request(f"{base}/api/save/estate", data=b'{"slot":"profile_0"}', headers={"Content-Type": "application/json"}, method="POST")
                with self.assertRaises(HTTPError) as cm:
                    urlopen(bad_req)
                self.assertEqual(cm.exception.code, 403)
                cm.exception.close()

                # Valid token updates gold and returns ok
                good_req = Request(
                    f"{base}/api/save/estate",
                    data=json.dumps({"slot": "profile_0", "wallet": {"gold": 321000}, "force": True}).encode("utf-8"),
                    headers={"Content-Type": "application/json", "X-Torch-Token": ctx.token},
                    method="POST",
                )
                resp = json.loads(urlopen(good_req).read().decode("utf-8"))
                self.assertTrue(resp["ok"])

                detail = json.loads(urlopen(f"{base}/api/save/detail?slot=profile_0").read().decode("utf-8"))
                self.assertEqual(detail["estate"]["wallet"]["gold"], 321000)
            finally:
                srv.shutdown()
                srv.server_close()

    def test_real_local_steam_save_copy_roundtrip_and_edits(self):
        """If local Steam Darkest Dungeon save exists, verify lossless round-trip & edits on an isolated temp copy."""
        env = detect_environment()
        if not env.active_saves_root or not env.game_dir:
            self.skipTest("Local Steam Darkest Dungeon not installed")
        src_root = Path(env.active_saves_root)
        slots = list_slots(src_root)
        if not slots:
            self.skipTest("No local Steam save slots found")

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            copy_saves = root / "remote"
            src_slot = Path(slots[0].path)
            dst_slot = copy_saves / src_slot.name
            shutil.copytree(src_slot, dst_slot)
            store = SnapshotStore(root / "history")

            # Lossless DSON round-trip on every persist.*.json in real Steam save
            for f in dst_slot.glob("*.json"):
                raw = f.read_bytes()
                if is_dson(raw):
                    rebuilt = serialize_dson(parse_dson(raw))
                    self.assertEqual(len(rebuilt), len(raw), f"Length mismatch on {f.name}")

            # Verify full save editor operations on the temp copy
            orig_mods = get_slot_mods(copy_saves, src_slot.name)
            write_estate(dst_slot, wallet_updates={"gold": 999999}, store=store)
            batch_improve_heroes(dst_slot, max_level_and_gear=True, heal_and_purify=True, store=store)
            unlock_all_buildings(dst_slot, store=store)
            add_trinket(dst_slot, "ancestors_map", 1, store=store)
            ensure_mod_in_slot(copy_saves, src_slot.name, MOD_ID, source="mod", enable=True, top_priority=True, store=store)

            new_mods = get_slot_mods(copy_saves, src_slot.name)
            self.assertEqual(new_mods[0], {"name": MOD_ID, "source": "mod"})
            # All original Steam Workshop mods keep source='Steam'
            for orig in orig_mods:
                self.assertIn(orig, new_mods)

            # Verify restore rolls back cleanly to original gold
            oldest = store.list_records(src_slot.name)[-1]
            store.restore(copy_saves, oldest.id)
            self.assertEqual(read_estate(dst_slot)["wallet"]["gold"], slots[0].gold)


if __name__ == "__main__":
    unittest.main()
