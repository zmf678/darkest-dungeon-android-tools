"""CLI entrypoint for dd-steam-converter: convert, verify, migrate, push, build-apk, edit-save, build-mod, gui."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import List, Optional

from .adb_push import list_devices, push_to_device
from .apk_bundler import bundle_standalone_apk
from .content_modifier import MOD_ID, ModifierConfig, generate_content_mod, pack_mod_zip
from .migration import build_migration_zip
from .pak_builder import BuildOptions, build_pak, default_switch_diff_path, verify_pak
from .save_editor import (
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
from .steam_detect import detect_steam


def cmd_detect(args: argparse.Namespace) -> int:
    env = detect_steam(args.steam_dir, args.steam_root)
    print(f"Steam Root   : {env.steam_root}")
    print(f"Game Dir     : {env.game_dir}")
    print(f"Workshop Dir : {env.workshop_dir}")
    print(f"DLCs ({len(env.dlcs)})   : {list(env.dlcs.keys())}")
    print(f"Mods ({len(env.mods)})   : {len(env.mods)} installed")
    print(f"Save Profiles: {len(env.save_profiles)}")
    for p in env.save_profiles:
        print(f"  - {p.slot}: {p.estate_name} [{p.game_mode}] ({len(p.applied_mods)} mods) @ {p.path}")
    return 0


def cmd_convert(args: argparse.Namespace) -> int:
    env = detect_steam(args.steam_dir, args.steam_root)
    steam_dir = args.steam_dir or env.game_dir
    if not steam_dir:
        raise SystemExit("Could not auto-detect Steam DarkestDungeon folder. Pass --steam-dir.")

    base_apk = args.base_apk or default_switch_diff_path()
    if not base_apk or not base_apk.is_file():
        raise SystemExit(
            "Could not find switch-runtime/switch-diff-layer.zip. Pass --switch-diff or --base-apk."
        )

    def progress(stage: str, done: int, total: int) -> None:
        print(f"[{stage}] {done}/{total}", flush=True)

    res = build_pak(
        BuildOptions(
            steam_dir=steam_dir,
            base_apk=base_apk,
            output_pak=args.output,
            texture_mode=args.texture_mode,
            audio_source=args.audio_source,
            include_base_dlcs=not args.steam_dlcs_only,
            workers=args.workers,
            progress_cb=progress,
        )
    )
    print(f"PAK built: {args.output} ({res.total_entries} files, {res.total_bytes / 1048576:.2f} MB)")
    print(f"Verification ok={res.ok}, checked CSV rows={res.checked_csv_rows}")
    if not res.ok:
        print("Non-stored:", res.non_stored_entries)
        print("Missing:", res.missing_entries)
        print("Mismatches:", res.size_mismatches)
        return 1
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    res = verify_pak(args.pak)
    print(json.dumps({
        "ok": res.ok,
        "total_entries": res.total_entries,
        "total_bytes": res.total_bytes,
        "checked_csv_rows": res.checked_csv_rows,
        "non_stored_sample": res.non_stored_entries,
        "missing_sample": res.missing_entries,
        "size_mismatches_sample": res.size_mismatches,
        "manifest": res.manifest,
    }, indent=2, ensure_ascii=False))
    return 0 if res.ok else 1


def cmd_migrate(args: argparse.Namespace) -> int:
    env = detect_steam(args.steam_dir, args.steam_root)
    chosen_slots = set(args.slots or [])
    profiles = [p for p in env.save_profiles if not chosen_slots or p.slot in chosen_slots]
    if not profiles:
        raise SystemExit("No matching Steam save profiles found.")
    summary = build_migration_zip(profiles, env.mods, args.output, args.extra_mods)
    print(f"Migration package: {summary.output_zip}")
    print(f"  Slots: {summary.slots}")
    print(f"  Mods packed ({len(summary.mods)}): {summary.mods}")
    if summary.missing_mods:
        print(f"  Missing mods: {summary.missing_mods}")
    return 0


def cmd_push(args: argparse.Namespace) -> int:
    print("ADB devices:", list_devices())
    push_to_device(
        package=args.package,
        pak_path=args.pak,
        apk_path=args.apk,
        mods_dir=args.mods_dir,
        serial=args.serial,
    )
    print("ADB push completed.")
    return 0


def cmd_build_apk(args: argparse.Namespace) -> int:
    out = bundle_standalone_apk(
        shell_apk=args.shell_apk,
        pak_path=args.pak,
        output_apk=args.output,
        build_tools=args.build_tools,
        java_exe=args.java,
        keystore=args.keystore,
        key_alias=args.key_alias,
        key_password=args.key_password,
    )
    print(f"Standalone A1 APK created: {out} ({out.stat().st_size / 1048576:.2f} MB)")
    return 0


def cmd_edit_save(args: argparse.Namespace) -> int:
    prof = args.profile
    if args.action == "info":
        print(json.dumps(read_estate(prof), indent=2, ensure_ascii=False))
        for h in list_heroes(prof):
            print(f"Hero #{h.guid}: {h.name} ({h.hero_class}) hp={h.hp_fraction:.2f} stress={h.stress:.0f} xp={h.resolve_xp} wp={h.weapon_rank} ar={h.armour_rank}")
        print(f"Trinkets ({len(list_trinkets(prof))}):", list_trinkets(prof)[:20])
    elif args.action == "max-resources":
        write_estate(prof, wallet_updates={
            "gold": 999999, "bust": 999, "portrait": 999,
            "deed": 999, "crest": 999, "shard": 9999, "blueprint": 20,
        })
        print("All currencies maxed.")
    elif args.action == "wallet":
        upd = {}
        for item in args.set_kv or []:
            k, v = item.split("=", 1)
            upd[k.strip()] = int(v.strip())
        write_estate(prof, estate_name=args.estate_name, game_mode=args.game_mode, wallet_updates=upd)
        print("Estate & wallet updated.")
    elif args.action == "max-heroes":
        cnt = batch_improve_heroes(prof, max_level_and_gear=True, heal_and_purify=True)
        print(f"Updated {cnt} heroes to full HP / 0 stress / Lv6 / rank 4 gear / cured diseases.")
    elif args.action == "heal-heroes":
        cnt = batch_improve_heroes(prof, max_level_and_gear=False, heal_and_purify=True)
        print(f"Healed & purified {cnt} heroes.")
    elif args.action == "edit-hero":
        update_hero(
            prof,
            args.guid,
            name=args.hero_name,
            hp_fraction=args.hp,
            stress=args.stress,
            resolve_xp=args.xp,
            weapon_rank=args.weapon,
            armour_rank=args.armour,
            affliction=args.affliction,
            virtue=args.virtue,
            revive=args.revive,
        )
        print(f"Hero #{args.guid} updated.")
    elif args.action == "add-trinket":
        add_trinket(prof, args.trinket_id, args.amount)
        print(f"Added trinket {args.trinket_id} x{args.amount}")
    elif args.action == "remove-trinket":
        remove_trinket(prof, args.index)
        print(f"Removed trinket at index {args.index}")
    elif args.action == "unlock-buildings":
        n = unlock_all_buildings(prof)
        print(f"Unlocked all town building upgrades ({n} entries added/enabled).")
    elif args.action == "dump-json":
        data = dump_profile_json(prof)
        if args.out_json:
            args.out_json.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"Dumped full profile DSON tree to {args.out_json}")
        else:
            print(json.dumps(data, indent=2, ensure_ascii=False)[:4000])
    elif args.action == "set-raw":
        edit_raw_field(prof, args.file, args.path.split("/"), args.type, args.value)
        print(f"Updated {args.file}:{args.path}")
    return 0


def cmd_build_mod(args: argparse.Namespace) -> int:
    env = detect_steam(args.steam_dir)
    steam_dir = args.steam_dir or env.game_dir
    cfg = ModifierConfig(
        stack_multiplier=args.stack_mult,
        free_supplies=args.free_supplies,
        hero_trinket_slots=args.trinket_slots,
        raid_inventory_slots=args.raid_slots,
        combat_speed_scale=args.combat_speed,
        player_crit_multiplier=args.crit_mult,
        always_hit_cap=1.0 if args.always_hit else 0.95,
        guaranteed_retreat=args.guaranteed_retreat,
        virtue_chance_percent=args.virtue_chance,
        stress_scale=args.stress_scale,
        idle_stress_heal=args.idle_stress_heal,
        fast_resolve_xp=args.fast_xp,
        stage_coach_multiplier=args.stagecoach_mult,
        roster_capacity_bonus=args.roster_bonus,
        building_cost_discount=args.building_discount,
        hero_hp_multiplier=args.hero_hp_mult,
        hero_dmg_multiplier=args.hero_dmg_mult,
        hero_speed_bonus=args.hero_spd_bonus,
        hero_dodge_bonus=args.hero_dodge_bonus,
    )
    out_dir = args.output_dir
    if args.install_steam and steam_dir:
        out_dir = steam_dir / "mods" / MOD_ID
    generate_content_mod(out_dir, cfg, steam_dir)
    print(f"Generated complete content modifier mod at: {out_dir}")
    if args.zip_out:
        pack_mod_zip(out_dir, args.zip_out)
        print(f"Packed mod ZIP: {args.zip_out}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="dd-steam-converter", description="Darkest Dungeon Steam->Android Converter & Complete Modifier")
    sub = p.add_subparsers(dest="command", required=True)

    d = sub.add_parser("detect", help="Auto-detect Steam Darkest Dungeon, DLCs, Workshop mods, and saves")
    d.add_argument("--steam-dir", type=Path)
    d.add_argument("--steam-root", type=Path)
    d.set_defaults(func=cmd_detect)

    c = sub.add_parser("convert", help="Convert Steam resources + Switch diff layer / base APK -> dd_assets.pak")
    c.add_argument(
        "--switch-diff",
        "--base-apk",
        dest="base_apk",
        type=Path,
        default=None,
        help="Path to switch-runtime/switch-diff-layer.zip (auto-detected by default) or full Wicked 2.2.2 APK",
    )
    c.add_argument("--output", type=Path, required=True)
    c.add_argument("--steam-dir", type=Path)
    c.add_argument("--steam-root", type=Path)
    c.add_argument("--texture-mode", choices=["dds", "png"], default="dds")
    c.add_argument("--audio-source", choices=["auto", "base", "steam"], default="auto")
    c.add_argument("--steam-dlcs-only", action="store_true")
    c.add_argument("--workers", type=int, default=8)
    c.set_defaults(func=cmd_convert)

    v = sub.add_parser("verify", help="Verify dd_assets.pak ZIP_STORED flags & every filelist.csv byte size")
    v.add_argument("pak", type=Path)
    v.set_defaults(func=cmd_verify)

    m = sub.add_parser("migrate", help="Bundle Steam saves (Steam->mod) & Workshop mods into steam-migration.zip")
    m.add_argument("--output", type=Path, required=True)
    m.add_argument("--steam-dir", type=Path)
    m.add_argument("--steam-root", type=Path)
    m.add_argument("--slots", nargs="*")
    m.add_argument("--extra-mods", nargs="*")
    m.set_defaults(func=cmd_migrate)

    pu = sub.add_parser("push", help="Push dd_assets.pak / APK / mods to Android device via ADB")
    pu.add_argument("--package", default="dev.torcharchive.ddgame")
    pu.add_argument("--pak", type=Path)
    pu.add_argument("--apk", type=Path)
    pu.add_argument("--mods-dir", type=Path)
    pu.add_argument("--serial")
    pu.set_defaults(func=cmd_push)

    ba = sub.add_parser("build-apk", help="A1 mode: combine thin-shell launcher APK + dd_assets.pak")
    ba.add_argument("--shell-apk", type=Path, required=True)
    ba.add_argument("--pak", type=Path, required=True)
    ba.add_argument("--output", type=Path, required=True)
    ba.add_argument("--build-tools", type=Path, required=True)
    ba.add_argument("--java", type=Path, required=True)
    ba.add_argument("--keystore", type=Path, required=True)
    ba.add_argument("--key-alias", required=True)
    ba.add_argument("--key-password", required=True)
    ba.set_defaults(func=cmd_build_apk)

    es = sub.add_parser("edit-save", help="Complete binary DSON save editor (currency, heroes, trinkets, buildings, raw)")
    es.add_argument("profile", type=Path, help="Path to profile_N folder")
    es.add_argument("action", choices=[
        "info", "max-resources", "wallet", "max-heroes", "heal-heroes",
        "edit-hero", "add-trinket", "remove-trinket", "unlock-buildings", "dump-json", "set-raw",
    ])
    es.add_argument("--set-kv", nargs="*")
    es.add_argument("--estate-name")
    es.add_argument("--game-mode")
    es.add_argument("--guid")
    es.add_argument("--hero-name")
    es.add_argument("--hp", type=float)
    es.add_argument("--stress", type=float)
    es.add_argument("--xp", type=int)
    es.add_argument("--weapon", type=int)
    es.add_argument("--armour", type=int)
    es.add_argument("--affliction")
    es.add_argument("--virtue")
    es.add_argument("--revive", action="store_true")
    es.add_argument("--trinket-id")
    es.add_argument("--amount", type=int, default=1)
    es.add_argument("--index", type=int, default=0)
    es.add_argument("--out-json", type=Path)
    es.add_argument("--file")
    es.add_argument("--path")
    es.add_argument("--type", choices=["int", "float", "bool", "string"])
    es.add_argument("--value")
    es.set_defaults(func=cmd_edit_save)

    bm = sub.add_parser("build-mod", help="Generate complete non-destructive content modifier Mod (PC & Android)")
    bm.add_argument("--output-dir", type=Path, default=Path(MOD_ID))
    bm.add_argument("--steam-dir", type=Path)
    bm.add_argument("--install-steam", action="store_true")
    bm.add_argument("--zip-out", type=Path)
    bm.add_argument("--stack-mult", type=int, default=1)
    bm.add_argument("--free-supplies", action="store_true")
    bm.add_argument("--trinket-slots", type=int, default=2)
    bm.add_argument("--raid-slots", type=int, default=16)
    bm.add_argument("--combat-speed", type=float, default=1.0)
    bm.add_argument("--crit-mult", type=float, default=1.5)
    bm.add_argument("--always-hit", action="store_true")
    bm.add_argument("--guaranteed-retreat", action="store_true")
    bm.add_argument("--virtue-chance", type=int, default=25)
    bm.add_argument("--stress-scale", type=float, default=1.0)
    bm.add_argument("--idle-stress-heal", type=float, default=5.0)
    bm.add_argument("--fast-xp", action="store_true")
    bm.add_argument("--stagecoach-mult", type=int, default=1)
    bm.add_argument("--roster-bonus", type=int, default=0)
    bm.add_argument("--building-discount", type=int, default=0)
    bm.add_argument("--hero-hp-mult", type=float, default=1.0)
    bm.add_argument("--hero-dmg-mult", type=float, default=1.0)
    bm.add_argument("--hero-spd-bonus", type=int, default=0)
    bm.add_argument("--hero-dodge-bonus", type=int, default=0)
    bm.set_defaults(func=cmd_build_mod)

    g = sub.add_parser("gui", help="Launch Ember-themed desktop GUI")
    g.set_defaults(func=lambda _a: launch_gui())
    return p


def launch_gui() -> int:
    from .gui import main as gui_main

    gui_main()
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
