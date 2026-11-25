"""Zero-dependency local HTTP server & native Chromium/Edge app-window launcher for Torch Archive PC."""

from __future__ import annotations

from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, unquote, urlparse
import webbrowser

from . import APP_TITLE, __version__
from .catalog import build_catalog_payload
from .content_modifier import (
    MOD_ID,
    ModifierConfig,
    PRESETS,
    apply_to_steam,
    generate_content_mod,
    load_installed_config,
    pack_mod_zip,
    uninstall_from_steam,
)
from .mod_manager import build_slot_mod_view, set_slot_mods
from .save_editor import (
    add_trinket,
    batch_improve_heroes,
    dump_profile_json,
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
from .save_watcher import SaveWatcher
from .snapshot_store import SnapshotStore
from .steam_env import (
    default_archive_home,
    detect_environment,
    is_game_running,
    launch_steam_game,
)


def _resource_dir() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS) / "torch_pc" / "web_ui"  # type: ignore[attr-defined]
    return Path(__file__).resolve().parent / "web_ui"


class AppContext:
    """Shared thread-safe runtime state for the local PC toolkit server."""

    def __init__(
        self,
        archive_home: Optional[Path] = None,
        explicit_game_dir: Optional[Path] = None,
        explicit_saves_root: Optional[Path] = None,
        auto_watch: bool = True,
    ):
        self.home = archive_home or default_archive_home()
        self.home.mkdir(parents=True, exist_ok=True)
        self.config_path = self.home / "config.json"
        self.store = SnapshotStore(self.home / "snapshots")
        self.token = secrets.token_hex(16)
        self._lock = threading.RLock()

        saved_cfg = self._load_config()
        self.custom_game_dir: Optional[Path] = explicit_game_dir or (
            Path(saved_cfg["game_dir"]) if saved_cfg.get("game_dir") else None
        )
        self.custom_saves_root: Optional[Path] = explicit_saves_root or (
            Path(saved_cfg["saves_root"]) if saved_cfg.get("saves_root") else None
        )
        self.env = self.refresh_env()
        self.watcher = SaveWatcher(self.store, self.saves_root())
        if auto_watch and saved_cfg.get("auto_watch", True) and self.saves_root():
            self.watcher.start()

    def _load_config(self) -> Dict[str, Any]:
        if not self.config_path.is_file():
            return {}
        try:
            data = json.loads(self.config_path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def save_config(self) -> None:
        cfg = {
            "game_dir": str(self.custom_game_dir) if self.custom_game_dir else "",
            "saves_root": str(self.custom_saves_root) if self.custom_saves_root else "",
            "auto_watch": self.watcher.is_running() if hasattr(self, "watcher") else True,
        }
        tmp = self.home / ".config.json.tmp"
        tmp.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.config_path)

    def refresh_env(self):
        with self._lock:
            self.env = detect_environment(
                explicit_game_dir=self.custom_game_dir,
                explicit_saves_root=self.custom_saves_root,
            )
            return self.env

    def saves_root(self) -> Optional[Path]:
        return Path(self.env.active_saves_root) if self.env.active_saves_root else None

    def game_dir(self) -> Optional[Path]:
        return Path(self.env.game_dir) if self.env.game_dir else None

    def profile_dir(self, slot: str) -> Path:
        root = self.saves_root()
        if not root:
            raise FileNotFoundError("尚未检测到 Steam 存档目录，请在概览页指定。")
        if not slot or not slot.startswith("profile_") or len(slot) != 9 or not slot[-1].isdigit():
            raise ValueError(f"无效档位: {slot}")
        prof = root / slot
        if not prof.is_dir():
            raise FileNotFoundError(f"找不到档位目录: {prof}")
        return prof

    def ensure_game_not_running(self, allow_while_running: bool = False) -> None:
        if not allow_while_running and is_game_running():
            raise RuntimeError(
                "检测到《暗黑地牢》(Darkest.exe) 正在运行！为防止游戏内存回写或 Steam 云同步覆盖修改，请先退出游戏后再写入（或勾选允许运行中强制写入）。"
            )

    def build_state(self) -> Dict[str, Any]:
        env = self.refresh_env()
        sroot = self.saves_root()
        slots = [s.to_dict() for s in list_slots(sroot)] if sroot else []
        gdir = self.game_dir()
        installed, mod_cfg = load_installed_config(gdir)
        return {
            "version": __version__,
            "title": APP_TITLE,
            "archive_home": str(self.home),
            "env": env.to_dict(),
            "slots": slots,
            "watcher": asdict(self.watcher.status()),
            "rules": {
                "installed": installed,
                "config": mod_cfg.to_dict(),
            },
            "snapshot_count": len(self.store.list_ids()),
        }


def _open_os_folder(path: Path) -> None:
    if not path.exists():
        raise FileNotFoundError(f"目录不存在: {path}")
    if os.name == "nt":
        os.startfile(str(path))  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])


def make_handler(ctx: AppContext):
    ui_dir = _resource_dir()

    class TorchRequestHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: object) -> None:
            # Keep console tidy unless TORCH_DEBUG is set
            if os.environ.get("TORCH_DEBUG"):
                super().log_message(format, *args)

        def _send_bytes(
            self,
            status: int,
            body: bytes,
            content_type: str,
            extra_headers: Optional[Dict[str, str]] = None,
        ) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if extra_headers:
                for k, v in extra_headers.items():
                    self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def _send_json(self, payload: Any, status: int = HTTPStatus.OK) -> None:
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self._send_bytes(status, raw, "application/json; charset=utf-8")

        def _read_json(self) -> Dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length > 20 * 1024 * 1024:
                raise ValueError("请求体过大")
            raw = self.rfile.read(length) if length > 0 else b"{}"
            if not raw.strip():
                return {}
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("请求 JSON 必须是对象")
            return data

        def _check_token(self) -> bool:
            tok = self.headers.get("X-Torch-Token", "")
            return secrets.compare_digest(tok, ctx.token)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path
            qs = parse_qs(parsed.query)
            try:
                if path == "/" or path == "/index.html":
                    html = (ui_dir / "index.html").read_text(encoding="utf-8")
                    html = html.replace("__TORCH_TOKEN__", ctx.token)
                    self._send_bytes(HTTPStatus.OK, html.encode("utf-8"), "text/html; charset=utf-8")
                    return

                if path.startswith("/assets/"):
                    rel = path[len("/assets/") :]
                    if ".." in rel or "/" in rel or "\\" in rel:
                        self._send_json({"error": "非法路径"}, HTTPStatus.BAD_REQUEST)
                        return
                    fp = ui_dir / rel
                    if not fp.is_file():
                        self._send_json({"error": "文件不存在"}, HTTPStatus.NOT_FOUND)
                        return
                    ctype, _ = mimetypes.guess_type(str(fp))
                    self._send_bytes(HTTPStatus.OK, fp.read_bytes(), f"{ctype or 'application/octet-stream'}; charset=utf-8")
                    return

                if path == "/api/state":
                    self._send_json(ctx.build_state())
                    return

                if path == "/api/catalog":
                    self._send_json(build_catalog_payload(ctx.game_dir()))
                    return

                if path == "/api/snapshots":
                    slot = qs.get("slot", [None])[0] or None
                    recs = [r.to_dict() for r in ctx.store.list_records(slot=slot)]
                    self._send_json({"snapshots": recs, "watcher": asdict(ctx.watcher.status())})
                    return

                if path == "/api/snapshots/diff":
                    snap_id = qs.get("id", [""])[0]
                    self._send_json(ctx.store.compare_snapshots(snap_id))
                    return

                if path == "/api/snapshots/export":
                    snap_id = qs.get("id", [""])[0]
                    zp = ctx.store.archive_path(snap_id)
                    data = zp.read_bytes()
                    fname = f"{snap_id[:36]}.zip"
                    self._send_bytes(
                        HTTPStatus.OK,
                        data,
                        "application/zip",
                        {"Content-Disposition": f'attachment; filename="{fname}"'},
                    )
                    return

                if path == "/api/save/detail":
                    slot = qs.get("slot", ["profile_0"])[0]
                    prof = ctx.profile_dir(slot)
                    cat = build_catalog_payload(ctx.game_dir())
                    extra_dis = {d["id"] for d in cat["diseases"]}  # type: ignore[index]
                    self._send_json(
                        {
                            "slot": slot,
                            "estate": read_estate(prof),
                            "heroes": [h.to_dict() for h in list_heroes(prof, extra_diseases=extra_dis)],
                            "trinkets": list_trinkets(prof),
                            "buildings": read_building_progress(prof),
                            "dungeons": read_dungeon_xp(prof),
                            "dson_files": list_dson_files(prof),
                        }
                    )
                    return

                if path == "/api/save/raw":
                    slot = qs.get("slot", ["profile_0"])[0]
                    fname = qs.get("file", ["persist.game.json"])[0]
                    raw_p = qs.get("path", [""])[0]
                    parts = [unquote(seg) for seg in raw_p.split("/") if seg] if raw_p else []
                    entries = [e.to_dict() for e in inspect_raw_path(ctx.profile_dir(slot), fname, parts)]
                    self._send_json({"file": fname, "path": parts, "entries": entries})
                    return

                if path == "/api/save/dump":
                    slot = qs.get("slot", ["profile_0"])[0]
                    dumped = dump_profile_json(ctx.profile_dir(slot))
                    raw = json.dumps(dumped, indent=2, ensure_ascii=False).encode("utf-8")
                    self._send_bytes(
                        HTTPStatus.OK,
                        raw,
                        "application/json; charset=utf-8",
                        {"Content-Disposition": f'attachment; filename="{slot}-dson-dump.json"'},
                    )
                    return

                if path == "/api/rules":
                    installed, cfg = load_installed_config(ctx.game_dir())
                    self._send_json(
                        {
                            "installed": installed,
                            "config": cfg.to_dict(),
                            "presets": {k: v.to_dict() for k, v in PRESETS.items()},
                            "has_steam_game": bool(ctx.game_dir()),
                        }
                    )
                    return

                if path == "/api/mods":
                    slot = qs.get("slot", ["profile_0"])[0]
                    sroot = ctx.saves_root()
                    if not sroot:
                        self._send_json({"slot": slot, "active": [], "inactive": []})
                        return
                    env = ctx.refresh_env()
                    self._send_json(build_slot_mod_view(sroot, slot, env.mods))
                    return

                self._send_json({"error": f"未知接口: {path}"}, HTTPStatus.NOT_FOUND)
            except FileNotFoundError as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.NOT_FOUND)
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
            except Exception as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.INTERNAL_SERVER_ERROR)

        def do_POST(self) -> None:
            if not self._check_token():
                self._send_json({"error": "无效的本地会话令牌 (X-Torch-Token)"}, HTTPStatus.FORBIDDEN)
                return
            parsed = urlparse(self.path)
            path = parsed.path
            qs = parse_qs(parsed.query)
            try:
                if path == "/api/snapshots/import":
                    length = int(self.headers.get("Content-Length", "0") or "0")
                    if length <= 0 or length > 200 * 1024 * 1024:
                        raise ValueError("ZIP 文件大小不合法（需小于 200 MB）")
                    body = self.rfile.read(length)
                    target_slot = qs.get("target_slot", [None])[0] or None
                    convert_steam = qs.get("convert_mod_to_steam", ["0"])[0] in ("1", "true")
                    force_run = qs.get("force", ["0"])[0] in ("1", "true")
                    ctx.ensure_game_not_running(force_run)
                    sroot = ctx.saves_root()
                    if not sroot:
                        raise FileNotFoundError("未检测到存档目录，无法导入。")
                    ws_ids = {m.mod_id for m in ctx.env.mods if m.source_kind == "Steam"}
                    with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tf:
                        tf.write(body)
                        tmp_zip = Path(tf.name)
                    try:
                        slots = ctx.store.import_save_zip(
                            sroot,
                            tmp_zip,
                            target_slot=target_slot,
                            convert_mod_to_steam=convert_steam,
                            workshop_ids=ws_ids,
                        )
                    finally:
                        tmp_zip.unlink(missing_ok=True)
                    self._send_json({"ok": True, "imported_slots": slots, "message": f"已导入档位：{', '.join(slots)}"})
                    return

                if path == "/api/rules/export-zip":
                    body = self._read_json()
                    cfg = ModifierConfig.from_dict(body.get("config", {}))
                    with tempfile.TemporaryDirectory(prefix="torch-mod-zip-") as td:
                        mod_dir = Path(td) / MOD_ID
                        generate_content_mod(mod_dir, cfg, ctx.game_dir())
                        out_zip = Path(td) / f"{MOD_ID}.zip"
                        pack_mod_zip(mod_dir, out_zip)
                        data = out_zip.read_bytes()
                    self._send_bytes(
                        HTTPStatus.OK,
                        data,
                        "application/zip",
                        {"Content-Disposition": f'attachment; filename="{MOD_ID}.zip"'},
                    )
                    return

                body = self._read_json()

                if path == "/api/config":
                    gd = str(body.get("game_dir", "")).strip()
                    sr = str(body.get("saves_root", "")).strip()
                    ctx.custom_game_dir = Path(gd) if gd else None
                    ctx.custom_saves_root = Path(sr) if sr else None
                    ctx.refresh_env()
                    was_running = ctx.watcher.is_running()
                    ctx.watcher.set_saves_root(ctx.saves_root())
                    if was_running and ctx.saves_root():
                        ctx.watcher.start()
                    ctx.save_config()
                    self._send_json({"ok": True, "state": ctx.build_state(), "message": "路径配置已更新"})
                    return

                if path == "/api/launch-game":
                    msg = launch_steam_game(ctx.game_dir())
                    self._send_json({"ok": True, "message": msg})
                    return

                if path == "/api/open-folder":
                    kind = str(body.get("kind", "saves"))
                    if kind == "saves":
                        p = ctx.saves_root()
                        if not p:
                            raise FileNotFoundError("未找到 Steam 存档目录")
                        _open_os_folder(p)
                    elif kind == "mods":
                        gd = ctx.game_dir()
                        if not gd:
                            raise FileNotFoundError("未找到 Steam 游戏目录")
                        mods = gd / "mods"
                        mods.mkdir(parents=True, exist_ok=True)
                        _open_os_folder(mods)
                    elif kind == "snapshots":
                        _open_os_folder(ctx.store.history_dir)
                    else:
                        raise ValueError(f"未知目录类型: {kind}")
                    self._send_json({"ok": True, "message": "已在文件资源管理器中打开目录"})
                    return

                if path == "/api/watcher/toggle":
                    enable = bool(body.get("enable", not ctx.watcher.is_running()))
                    if enable:
                        ok = ctx.watcher.start()
                        if not ok:
                            raise RuntimeError(ctx.watcher.status().last_event)
                    else:
                        ctx.watcher.stop()
                    ctx.save_config()
                    self._send_json({"ok": True, "watcher": asdict(ctx.watcher.status())})
                    return

                if path == "/api/snapshots/backup":
                    slot = str(body.get("slot", "profile_0"))
                    note = str(body.get("note", ""))
                    rec = ctx.store.backup_slot(ctx.profile_dir(slot), trigger="manual", note=note, force=True)
                    self._send_json({"ok": True, "snapshot": rec.to_dict() if rec else None, "message": f"已为 {slot} 创建验证快照"})
                    return

                if path == "/api/snapshots/restore":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    snap_id = str(body.get("id", ""))
                    target_slot = body.get("target_slot") or None
                    sroot = ctx.saves_root()
                    if not sroot:
                        raise FileNotFoundError("未检测到存档目录")
                    restored = ctx.store.restore(sroot, snap_id, target_slot=target_slot)
                    self._send_json({"ok": True, "slot": restored, "message": f"已原子回档至 {restored}（原档已先自动备份）"})
                    return

                if path == "/api/snapshots/rename":
                    ctx.store.rename_note(str(body.get("id", "")), str(body.get("note", "")))
                    self._send_json({"ok": True, "message": "快照备注已更新"})
                    return

                if path == "/api/snapshots/delete":
                    ctx.store.delete_snapshot(str(body.get("id", "")))
                    self._send_json({"ok": True, "message": "快照已删除"})
                    return

                if path == "/api/snapshots/prune":
                    keep = int(body.get("keep_per_slot", 30))
                    slot = body.get("slot") or None
                    removed = ctx.store.prune(keep_per_slot=keep, slot=slot)
                    self._send_json({"ok": True, "removed": removed, "message": f"已清理 {removed} 份冗余自动快照"})
                    return

                if path == "/api/save/estate":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    write_estate(
                        ctx.profile_dir(slot),
                        estate_name=body.get("estate_name"),
                        game_mode=body.get("game_mode"),
                        wallet_updates=body.get("wallet"),
                        endless_highscore=body.get("endless_wave_highscore"),
                        store=ctx.store,
                    )
                    self._send_json({"ok": True, "message": "庄园与货币修改已写入（写前已自动快照）"})
                    return

                if path == "/api/save/heroes/batch":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    cat = build_catalog_payload(ctx.game_dir())
                    extra_dis = {d["id"] for d in cat["diseases"]}  # type: ignore[index]
                    cnt = batch_improve_heroes(
                        ctx.profile_dir(slot),
                        max_level_and_gear=bool(body.get("max_level_and_gear", False)),
                        heal_and_purify=bool(body.get("heal_and_purify", False)),
                        extra_diseases=extra_dis,
                        store=ctx.store,
                    )
                    self._send_json({"ok": True, "count": cnt, "message": f"已批量处理 {cnt} 名英雄（写前已自动快照）"})
                    return

                if path == "/api/save/hero/update":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    guid = str(body.get("guid", ""))
                    raw_quirks = body.get("quirks")
                    quirks_arg: Optional[List[Tuple[str, bool]]] = None
                    if isinstance(raw_quirks, list):
                        quirks_arg = [(str(q["id"]), bool(q.get("locked", False))) for q in raw_quirks if q.get("id")]
                    update_hero(
                        ctx.profile_dir(slot),
                        guid=guid,
                        name=body.get("name"),
                        hp_fraction=body.get("hp_fraction"),
                        stress=body.get("stress"),
                        resolve_xp=body.get("resolve_xp"),
                        weapon_rank=body.get("weapon_rank"),
                        armour_rank=body.get("armour_rank"),
                        affliction=body.get("affliction"),
                        virtue=body.get("virtue"),
                        revive=bool(body.get("revive", False)),
                        quirks=quirks_arg,
                        store=ctx.store,
                    )
                    self._send_json({"ok": True, "message": f"英雄 #{guid} 数据已保存（写前已自动快照）"})
                    return

                if path == "/api/save/trinket/add":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    tid = str(body.get("trinket_id", ""))
                    amt = int(body.get("amount", 1))
                    add_trinket(ctx.profile_dir(slot), tid, amount=amt, store=ctx.store)
                    self._send_json({"ok": True, "message": f"已添加饰品 {tid} ×{amt}"})
                    return

                if path == "/api/save/trinket/remove":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    idx = int(body.get("index", -1))
                    remove_trinket(ctx.profile_dir(slot), idx, store=ctx.store)
                    self._send_json({"ok": True, "message": "已从饰品仓库移除该饰品"})
                    return

                if path == "/api/save/buildings/unlock":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    changed = unlock_all_buildings(ctx.profile_dir(slot), store=ctx.store)
                    self._send_json({"ok": True, "changed": changed, "message": f"城镇 8 座建筑已全解锁（新增/激活 {changed} 项升级）"})
                    return

                if path == "/api/save/dungeons/update":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    dmap = {str(k): int(v) for k, v in (body.get("dungeons") or {}).items()}
                    write_dungeon_xp(ctx.profile_dir(slot), dmap, store=ctx.store)
                    self._send_json({"ok": True, "message": "地牢区域经验已保存"})
                    return

                if path == "/api/save/raw/edit":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    fname = str(body.get("file", ""))
                    parts = [str(x) for x in (body.get("path") or [])]
                    vtype = str(body.get("type", "int"))
                    val = str(body.get("value", ""))
                    edit_raw_scalar(ctx.profile_dir(slot), fname, parts, vtype, val, store=ctx.store)
                    self._send_json({"ok": True, "message": f"字段 /{'/'.join(parts)} 已校验写回"})
                    return

                if path == "/api/rules/apply":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    gdir = ctx.game_dir()
                    if not gdir:
                        raise FileNotFoundError("未找到 Steam《暗黑地牢》安装目录，请先在概览页指定。")
                    cfg = ModifierConfig.from_dict(body.get("config", {}))
                    enable_slot = body.get("enable_slot") or None
                    info = apply_to_steam(gdir, cfg, ctx.saves_root(), enable_slot, store=ctx.store)
                    msg = f"规则修改器 Mod 已安装到 Steam mods/（共 {info['file_count']} 个文件）"
                    if enable_slot:
                        msg += f"，并已置顶启用到 {enable_slot}（原有创意工坊 Mod 来源已完整保留）"
                    self._send_json({"ok": True, "info": info, "message": msg})
                    return

                if path == "/api/rules/uninstall":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    gdir = ctx.game_dir()
                    if not gdir:
                        raise FileNotFoundError("未找到 Steam《暗黑地牢》安装目录。")
                    info = uninstall_from_steam(gdir, ctx.saves_root(), store=ctx.store)
                    self._send_json({"ok": True, "info": info, "message": "已卸载规则修改器 Mod 并恢复原版"})
                    return

                if path == "/api/mods/save":
                    ctx.ensure_game_not_running(bool(body.get("force", False)))
                    slot = str(body.get("slot", "profile_0"))
                    sroot = ctx.saves_root()
                    if not sroot:
                        raise FileNotFoundError("未检测到存档目录")
                    raw_mods = body.get("mods") or []
                    pairs = [(str(m["name"]), str(m.get("source", "mod"))) for m in raw_mods if m.get("name")]
                    saved = set_slot_mods(sroot, slot, pairs, store=ctx.store)
                    self._send_json({"ok": True, "mods": saved, "message": f"档位 {slot} Mod 排序已保存（共 {len(saved)} 个）"})
                    return

                self._send_json({"error": f"未知接口: {path}"}, HTTPStatus.NOT_FOUND)
            except FileNotFoundError as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.NOT_FOUND)
            except (ValueError, KeyError, IndexError, TypeError) as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
            except RuntimeError as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.CONFLICT)
            except Exception as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.INTERNAL_SERVER_ERROR)

    return TorchRequestHandler


def open_desktop_window(url: str, prefer_app_window: bool = True) -> str:
    """Open URL in standalone Edge/Chrome --app desktop window on Windows, else default browser."""
    if prefer_app_window and os.name == "nt":
        candidates = [
            Path(os.environ.get("PROGRAMFILES(X86)", "")) / "Microsoft/Edge/Application/msedge.exe",
            Path(os.environ.get("PROGRAMFILES", "")) / "Microsoft/Edge/Application/msedge.exe",
            Path(os.environ.get("PROGRAMFILES", "")) / "Google/Chrome/Application/chrome.exe",
            Path(os.environ.get("PROGRAMFILES(X86)", "")) / "Google/Chrome/Application/chrome.exe",
            Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Chrome/Application/chrome.exe",
        ]
        for exe in candidates:
            if exe.is_file():
                try:
                    subprocess.Popen(
                        [str(exe), f"--app={url}", "--window-size=1280,860"],
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                    )
                    return f"已使用独立桌面窗口打开（{exe.name}）"
                except OSError:
                    continue
    webbrowser.open(url)
    return "已在系统默认浏览器打开"


def serve_app(
    port: int = 0,
    open_ui: bool = True,
    prefer_app_window: bool = True,
    game_dir: Optional[Path] = None,
    saves_root: Optional[Path] = None,
    auto_watch: bool = True,
) -> None:
    ctx = AppContext(
        explicit_game_dir=game_dir,
        explicit_saves_root=saves_root,
        auto_watch=auto_watch,
    )
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(ctx))
    actual_port = server.server_address[1]
    url = f"http://127.0.0.1:{actual_port}/"
    print(f"{APP_TITLE} v{__version__}")
    print(f"本地服务地址: {url}")
    print(f"快照档案目录: {ctx.store.history_dir}")
    if ctx.saves_root():
        print(f"Steam 存档目录: {ctx.saves_root()}")
    if ctx.game_dir():
        print(f"Steam 游戏目录: {ctx.game_dir()}")
    print("按 Ctrl+C 停止服务并退出。")

    if open_ui:
        threading.Timer(0.25, lambda: open_desktop_window(url, prefer_app_window=prefer_app_window)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n正在安全停止自动留档与本地服务…")
    finally:
        ctx.watcher.stop()
        server.server_close()
