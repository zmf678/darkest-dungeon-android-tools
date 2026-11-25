"""Unified CLI & desktop launcher for Darkest Dungeon PC Steam Torch Archive & Modifier."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
import time
from typing import List, Optional

from . import APP_TITLE, __version__
from .content_modifier import (
    MOD_ID,
    ModifierConfig,
    PRESETS,
    apply_to_steam,
    generate_content_mod,
    pack_mod_zip,
    uninstall_from_steam,
)
from .mod_manager import build_slot_mod_view, ensure_mod_in_slot
from .save_editor import (
    add_trinket,
    batch_improve_heroes,
    dump_profile_json,
    edit_raw_scalar,
    list_heroes,
    list_slots,
    list_trinkets,
    read_building_progress,
    read_dungeon_xp,
    read_estate,
    unlock_all_buildings,
    update_hero,
    write_dungeon_xp,
    write_estate,
)
from .save_watcher import SaveWatcher
from .snapshot_store import SnapshotStore
from .steam_env import default_archive_home, detect_environment, is_game_running
from .web_server import serve_app


def _fmt_ts(ts_ms: int) -> str:
    return datetime.fromtimestamp(ts_ms / 1000.0).strftime("%Y-%m-%d %H:%M:%S")


def _resolve_profile(args) -> tuple[Path, SnapshotStore]:
    env = detect_environment(
        explicit_game_dir=Path(args.game_dir) if getattr(args, "game_dir", None) else None,
        explicit_saves_root=Path(args.saves_root) if getattr(args, "saves_root", None) else None,
    )
    store = SnapshotStore(default_archive_home() / "snapshots")
    if getattr(args, "profile", None):
        p = Path(args.profile)
        if p.is_dir() and (p / "persist.game.json").is_file():
            return p, store
        if env.active_saves_root and (Path(env.active_saves_root) / args.profile).is_dir():
            return Path(env.active_saves_root) / args.profile, store
        raise FileNotFoundError(f"找不到档位目录: {args.profile}")
    if not env.active_saves_root:
        raise FileNotFoundError("未自动检测到 Steam 存档目录，请使用 --saves-root 或传入完整 profile_N 路径。")
    slot = getattr(args, "slot", None) or "profile_0"
    prof = Path(env.active_saves_root) / slot
    if not prof.is_dir():
        raise FileNotFoundError(f"找不到档位目录: {prof}")
    return prof, store


def _cmd_detect(args) -> int:
    env = detect_environment(
        explicit_game_dir=Path(args.game_dir) if args.game_dir else None,
        explicit_saves_root=Path(args.saves_root) if args.saves_root else None,
    )
    print(f"Steam Root   : {env.steam_root or '(未找到)'}")
    print(f"Game Dir     : {env.game_dir or '(未找到)'}")
    print(f"Workshop Dir : {env.workshop_dir or '(未找到)'}")
    print(f"Game Running : {'是 (Darkest.exe 运行中)' if env.game_running else '否'}")
    print(f"DLCs ({len(env.dlcs)})     : {[d['id'] for d in env.dlcs]}")
    print(f"Mods ({len(env.mods)})     : {sum(1 for m in env.mods if m.source_kind == 'Steam')} 创意工坊 + {sum(1 for m in env.mods if m.source_kind == 'mod')} 本地")
    print(f"Active Saves : {env.active_saves_root or '(未找到)'}")
    if env.active_saves_root:
        for s in list_slots(Path(env.active_saves_root)):
            print(
                f"  - {s.slot}: {s.estate_name} [{s.game_mode}] 金币={s.gold} 英雄={s.hero_count} 饰品={s.trinket_count} Mod={s.applied_mods_count}"
            )
    return 0


def _cmd_watch(args) -> int:
    env = detect_environment(explicit_saves_root=Path(args.saves_root) if args.saves_root else None)
    if not env.active_saves_root:
        print("错误：未检测到 Steam 存档目录，请用 --saves-root 指定。", file=sys.stderr)
        return 1
    saves_root = Path(env.active_saves_root)
    store = SnapshotStore(default_archive_home() / "snapshots")
    print(f"开始实时监听 Steam 存档：{saves_root}")
    print(f"快照将保存在：{store.history_dir}")
    print("游戏每次保存都会自动留档（SHA-256 去重校验），按 Ctrl+C 停止。")

    def on_cap(rec) -> None:
        print(f"[{_fmt_ts(rec.timestamp_ms)}] 自动留档 {rec.slot} | {rec.estate_name} | 金币={rec.gold} 英雄={rec.hero_count} | {rec.id[:24]}")

    watcher = SaveWatcher(store, saves_root, poll_interval_sec=args.interval, debounce_sec=args.debounce, on_capture=on_cap)
    watcher.start()
    try:
        while True:
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\n已停止实时留档。")
    finally:
        watcher.stop()
    return 0


def _cmd_snapshot(args) -> int:
    store = SnapshotStore(default_archive_home() / "snapshots")
    if args.snap_action == "list":
        recs = store.list_records(slot=args.slot)
        if not recs:
            print("暂无快照记录。")
            return 0
        for r in recs:
            note = f" | 备注: {r.note}" if r.note else ""
            print(f"{r.id}  {_fmt_ts(r.timestamp_ms)}  {r.slot}  [{r.trigger}]  {r.estate_name}  金币={r.gold} 英雄={r.hero_count}{note}")
        return 0

    if args.snap_action == "backup":
        prof, _ = _resolve_profile(args)
        rec = store.backup_slot(prof, trigger="manual", note=args.note or "", force=True)
        print(f"快照已创建: {rec.id} ({rec.estate_name}, {rec.size_bytes} bytes)")
        return 0

    if args.snap_action == "restore":
        if is_game_running() and not args.force:
            print("错误：检测到 Darkest.exe 正在运行，请先退出游戏或追加 --force。", file=sys.stderr)
            return 1
        env = detect_environment(explicit_saves_root=Path(args.saves_root) if args.saves_root else None)
        if not env.active_saves_root:
            print("错误：未找到 Steam 存档目录。", file=sys.stderr)
            return 1
        slot = store.restore(Path(env.active_saves_root), args.id, target_slot=args.target_slot)
        print(f"已原子回档至 {slot}（回档前已自动备份原档）。")
        return 0

    if args.snap_action == "prune":
        removed = store.prune(keep_per_slot=args.keep, slot=args.slot)
        print(f"已清理 {removed} 份冗余自动快照。")
        return 0

    return 1


def _cmd_edit_save(args) -> int:
    prof, store = _resolve_profile(args)
    if args.action == "info":
        est = read_estate(prof)
        heroes = list_heroes(prof)
        trinkets = list_trinkets(prof)
        buildings = read_building_progress(prof)
        dungeons = read_dungeon_xp(prof)
        print(f"档位: {prof}")
        print(f"庄园: {est['estate_name']} ({est['game_mode']}) | 货币: {est['wallet']} | 无尽最高波次: {est['endless_wave_highscore']}")
        print(f"英雄 ({len(heroes)}):")
        for h in heroes:
            hp_str = f"{h.hp_raw:.0f} HP" if h.hp_is_absolute else f"{h.hp_fraction*100:.0f}%"
            print(f"  #{h.guid} {h.name} ({h.hero_class_label}) Lv.{h.resolve_level} HP={hp_str} 压力={h.stress:.0f} 武器={h.weapon_rank+1} 护甲={h.armour_rank+1} 状态={h.status_label}")
        print(f"饰品仓库: {len(trinkets)} 件")
        print("城镇建筑:", ", ".join(f"{b['label']} {b['unlocked']}/{b['total']}" for b in buildings))
        print("地牢区域:", ", ".join(f"{d['label']} Lv.{d['level']}({d['xp']}xp)" for d in dungeons))
        return 0

    if args.action == "dump":
        print(json.dumps(dump_profile_json(prof), indent=2, ensure_ascii=False))
        return 0

    if is_game_running() and not args.force:
        print("错误：检测到 Darkest.exe 正在运行，请先退出游戏或追加 --force。", file=sys.stderr)
        return 1

    if args.action == "max-resources":
        write_estate(
            prof,
            wallet_updates={"gold": 999999, "bust": 999, "portrait": 999, "deed": 999, "crest": 999, "shard": 9999, "blueprint": 20},
            store=store,
        )
        print("已拉满全部货币、传家宝、彗星碎片与蓝图（写前已自动快照）。")
        return 0

    if args.action == "max-heroes":
        n = batch_improve_heroes(prof, max_level_and_gear=True, heal_and_purify=True, store=store)
        print(f"已治愈、清病复活并升满 {n} 名英雄（写前已自动快照）。")
        return 0

    if args.action == "edit-hero":
        update_hero(
            prof,
            guid=args.guid,
            name=args.name,
            hp_fraction=args.hp,
            stress=args.stress,
            resolve_xp=args.xp,
            weapon_rank=args.weapon,
            armour_rank=args.armour,
            affliction=args.affliction,
            virtue=args.virtue,
            revive=args.revive,
            store=store,
        )
        print(f"已更新英雄 #{args.guid}。")
        return 0

    if args.action == "add-trinket":
        add_trinket(prof, args.trinket_id, amount=args.amount, store=store)
        print(f"已添加饰品 {args.trinket_id} ×{args.amount}。")
        return 0

    if args.action == "unlock-buildings":
        n = unlock_all_buildings(prof, store=store)
        print(f"已全解锁城镇 8 座建筑（新增/激活 {n} 项升级）。")
        return 0

    if args.action == "max-dungeons":
        write_dungeon_xp(prof, {k: 150 for k in ("crypts", "weald", "warrens", "cove", "courtyard", "farmstead")}, store=store)
        print("已将 6 大地牢区域均提升至满级 (150 XP)。")
        return 0

    if args.action == "set-raw":
        parts = [p for p in args.path.split("/") if p]
        edit_raw_scalar(prof, args.file, parts, args.type, args.value, store=store)
        print(f"已更新 {args.file}:/{'/'.join(parts)}。")
        return 0

    return 1


def _cmd_build_mod(args) -> int:
    env = detect_environment(
        explicit_game_dir=Path(args.game_dir) if args.game_dir else None,
        explicit_saves_root=Path(args.saves_root) if args.saves_root else None,
    )
    store = SnapshotStore(default_archive_home() / "snapshots")
    gdir = Path(env.game_dir) if env.game_dir else None

    if args.uninstall:
        if not gdir:
            print("错误：未检测到 Steam 游戏目录。", file=sys.stderr)
            return 1
        info = uninstall_from_steam(gdir, Path(env.active_saves_root) if env.active_saves_root else None, store=store)
        print(f"已卸载规则修改器 Mod：{info}")
        return 0

    cfg = ModifierConfig(**PRESETS[args.preset].to_dict()) if args.preset else ModifierConfig()
    if args.stack_mult is not None:
        cfg.stack_multiplier = args.stack_mult
    if args.free_supplies:
        cfg.free_supplies = True
    if args.trinket_slots is not None:
        cfg.hero_trinket_slots = args.trinket_slots
    if args.raid_slots is not None:
        cfg.raid_inventory_slots = args.raid_slots
    if args.combat_speed is not None:
        cfg.combat_speed_scale = args.combat_speed
    if args.crit_mult is not None:
        cfg.player_crit_multiplier = args.crit_mult
    if args.always_hit:
        cfg.always_hit_cap = 1.0
    if args.guaranteed_retreat:
        cfg.guaranteed_retreat = True
    if args.virtue_chance is not None:
        cfg.virtue_chance_percent = args.virtue_chance
    if args.stress_scale is not None:
        cfg.stress_scale = args.stress_scale
    if args.idle_stress_heal is not None:
        cfg.idle_stress_heal = args.idle_stress_heal
    if args.fast_xp:
        cfg.fast_resolve_xp = True
    if args.stagecoach_mult is not None:
        cfg.stage_coach_multiplier = args.stagecoach_mult
    if args.roster_bonus is not None:
        cfg.roster_capacity_bonus = args.roster_bonus
    if args.building_discount is not None:
        cfg.building_cost_discount = args.building_discount
    if args.hero_hp_mult is not None:
        cfg.hero_hp_multiplier = args.hero_hp_mult
    if args.hero_dmg_mult is not None:
        cfg.hero_dmg_multiplier = args.hero_dmg_mult
    if args.hero_spd_bonus is not None:
        cfg.hero_speed_bonus = args.hero_spd_bonus
    if args.hero_dodge_bonus is not None:
        cfg.hero_dodge_bonus = args.hero_dodge_bonus

    if args.install_steam:
        if not gdir:
            print("错误：未找到 Steam 游戏目录，请使用 --game-dir 指定。", file=sys.stderr)
            return 1
        info = apply_to_steam(
            gdir,
            cfg,
            saves_root=Path(env.active_saves_root) if env.active_saves_root else None,
            enable_for_slot=args.enable_slot,
            store=store,
        )
        print(f"已安装到 Steam mods/{MOD_ID}: {info}")
        if args.zip_out:
            pack_mod_zip(gdir / "mods" / MOD_ID, Path(args.zip_out))
            print(f"已导出 ZIP: {args.zip_out}")
        return 0

    out_dir = Path(args.output or (Path.cwd() / "dist" / MOD_ID))
    info = generate_content_mod(out_dir, cfg, gdir)
    print(f"已生成规则修改器 Mod: {info}")
    if args.zip_out:
        pack_mod_zip(out_dir, Path(args.zip_out))
        print(f"已导出 ZIP: {args.zip_out}")
    return 0


def _cmd_mods(args) -> int:
    env = detect_environment(
        explicit_game_dir=Path(args.game_dir) if args.game_dir else None,
        explicit_saves_root=Path(args.saves_root) if args.saves_root else None,
    )
    if not env.active_saves_root:
        print("错误：未找到 Steam 存档目录。", file=sys.stderr)
        return 1
    sroot = Path(env.active_saves_root)
    if args.mod_action == "list":
        view = build_slot_mod_view(sroot, args.slot, env.mods)
        print(f"=== {args.slot} 已启用 Mod ({len(view['active'])}) ===")
        for idx, m in enumerate(view["active"], 1):
            print(f"  {idx:2d}. [{m['source']}] {m['title']} ({m['name']})")
        print(f"=== 本机未在 {args.slot} 启用的 Mod ({len(view['inactive'])}) ===")
        for m in view["inactive"]:
            print(f"   - [{m['source']}] {m['title']} ({m['name']})")
        return 0
    store = SnapshotStore(default_archive_home() / "snapshots")
    if args.mod_action in ("enable", "disable"):
        changed = ensure_mod_in_slot(
            sroot,
            args.slot,
            args.mod_id,
            source=args.source,
            enable=(args.mod_action == "enable"),
            top_priority=args.top,
            store=store,
        )
        print("已更新档位 Mod 订阅。" if changed else "档位 Mod 状态无变化。")
        return 0
    return 1


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="torch-pc", description=f"{APP_TITLE} v{__version__}")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    sub = p.add_subparsers(dest="cmd")

    # Default / UI server
    g = sub.add_parser("ui", help="启动余烬 PC 桌面图形界面（默认命令）")
    g.add_argument("--port", type=int, default=0, help="指定本地监听端口（默认自动分配空闲端口）")
    g.add_argument("--browser", action="store_true", help="使用默认浏览器打开而非 Edge/Chrome 独立窗口")
    g.add_argument("--no-open", action="store_true", help="仅启动本地服务，不自动打开窗口")
    g.add_argument("--no-watch", action="store_true", help="启动时不自动开启实时留档监听")
    g.add_argument("--game-dir", help="手动指定 Steam 游戏目录")
    g.add_argument("--saves-root", help="手动指定 Steam remote/ 存档目录")

    # Detect
    d = sub.add_parser("detect", help="探测本机 Steam《暗黑地牢》环境、DLC、创意工坊与存档")
    d.add_argument("--game-dir", help="手动指定 Steam 游戏目录")
    d.add_argument("--saves-root", help="手动指定存档目录")

    # Watch
    w = sub.add_parser("watch", help="命令行后台实时监控 Steam 存档变更并自动留档")
    w.add_argument("--saves-root", help="手动指定存档目录")
    w.add_argument("--interval", type=float, default=1.5, help="轮询间隔秒数（默认 1.5）")
    w.add_argument("--debounce", type=float, default=1.5, help="写入稳定防抖秒数（默认 1.5）")

    # Snapshots
    sn = sub.add_parser("snapshot", help="存档快照备份、列举、原子回档与清理")
    sn.add_argument("snap_action", choices=["list", "backup", "restore", "prune"])
    sn.add_argument("--slot", help="档位名如 profile_0")
    sn.add_argument("--profile", help="直接指定 profile_N 目录")
    sn.add_argument("--saves-root", help="手动指定存档根目录")
    sn.add_argument("--id", help="restore 时的快照 ID")
    sn.add_argument("--target-slot", help="restore 时覆盖到指定目标档位")
    sn.add_argument("--note", help="backup 备注说明")
    sn.add_argument("--keep", type=int, default=30, help="prune 时每档位保留最新自动快照数")
    sn.add_argument("--force", action="store_true", help="忽略游戏运行中检测强制写入")

    # Save editor
    es = sub.add_parser("edit-save", help="命令行全维度二进制 DSON 存档修改器")
    es.add_argument("profile", help="档位目录路径或档位名（如 profile_0）")
    es.add_argument(
        "action",
        choices=["info", "max-resources", "max-heroes", "edit-hero", "add-trinket", "unlock-buildings", "max-dungeons", "set-raw", "dump"],
    )
    es.add_argument("--saves-root")
    es.add_argument("--guid")
    es.add_argument("--name")
    es.add_argument("--hp", type=float)
    es.add_argument("--stress", type=float)
    es.add_argument("--xp", type=int)
    es.add_argument("--weapon", type=int)
    es.add_argument("--armour", type=int)
    es.add_argument("--virtue")
    es.add_argument("--affliction")
    es.add_argument("--revive", action="store_true")
    es.add_argument("--trinket-id")
    es.add_argument("--amount", type=int, default=1)
    es.add_argument("--file")
    es.add_argument("--path")
    es.add_argument("--type", choices=["bool", "int", "float", "string"], default="int")
    es.add_argument("--value")
    es.add_argument("--force", action="store_true")

    # Content rules modifier
    bm = sub.add_parser("build-mod", help="生成/安装/卸载非破坏性游戏规则修改器 Mod")
    bm.add_argument("--preset", choices=list(PRESETS.keys()), help="直接套用预设方案")
    bm.add_argument("--install-steam", action="store_true", help="直接安装到 Steam mods/torch_content_modifier")
    bm.add_argument("--enable-slot", help="安装后自动置顶启用到指定档位（如 profile_0）")
    bm.add_argument("--uninstall", action="store_true", help="卸载规则修改器并恢复原版")
    bm.add_argument("--output", help="导出目录路径")
    bm.add_argument("--zip-out", help="同时打包为 ZIP")
    bm.add_argument("--game-dir")
    bm.add_argument("--saves-root")
    bm.add_argument("--stack-mult", type=int)
    bm.add_argument("--free-supplies", action="store_true")
    bm.add_argument("--trinket-slots", type=int)
    bm.add_argument("--raid-slots", type=int)
    bm.add_argument("--combat-speed", type=float)
    bm.add_argument("--crit-mult", type=float)
    bm.add_argument("--always-hit", action="store_true")
    bm.add_argument("--guaranteed-retreat", action="store_true")
    bm.add_argument("--virtue-chance", type=int)
    bm.add_argument("--stress-scale", type=float)
    bm.add_argument("--idle-stress-heal", type=float)
    bm.add_argument("--fast-xp", action="store_true")
    bm.add_argument("--stagecoach-mult", type=int)
    bm.add_argument("--roster-bonus", type=int)
    bm.add_argument("--building-discount", type=int)
    bm.add_argument("--hero-hp-mult", type=float)
    bm.add_argument("--hero-dmg-mult", type=float)
    bm.add_argument("--hero-spd-bonus", type=int)
    bm.add_argument("--hero-dodge-bonus", type=int)

    # Steam Mod manager
    mm = sub.add_parser("mods", help="管理 Steam 档位 Mod 订阅与加载顺序")
    mm.add_argument("mod_action", choices=["list", "enable", "disable"])
    mm.add_argument("--slot", default="profile_0")
    mm.add_argument("--mod-id", help="Mod ID（创意工坊数字 ID 或本地目录名）")
    mm.add_argument("--source", choices=["Steam", "mod"], default="mod")
    mm.add_argument("--top", action="store_true", help="置顶优先级")
    mm.add_argument("--game-dir")
    mm.add_argument("--saves-root")

    return p


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.cmd is None or args.cmd == "ui":
        serve_app(
            port=getattr(args, "port", 0),
            open_ui=not getattr(args, "no_open", False),
            prefer_app_window=not getattr(args, "browser", False),
            game_dir=Path(args.game_dir) if getattr(args, "game_dir", None) else None,
            saves_root=Path(args.saves_root) if getattr(args, "saves_root", None) else None,
            auto_watch=not getattr(args, "no_watch", False),
        )
        return 0
    if args.cmd == "detect":
        return _cmd_detect(args)
    if args.cmd == "watch":
        return _cmd_watch(args)
    if args.cmd == "snapshot":
        return _cmd_snapshot(args)
    if args.cmd == "edit-save":
        return _cmd_edit_save(args)
    if args.cmd == "build-mod":
        return _cmd_build_mod(args)
    if args.cmd == "mods":
        return _cmd_mods(args)
    parser.print_help()
    return 0
