"""Steam Workshop & local mod manager for PC Darkest Dungeon save slots.

Strictly preserves each mod's `source` ('Steam' for Workshop items, 'mod' for local `mods/` folders)
when toggling or reordering `applied_ugcs_1_0` inside `persist.game.json`.
"""

from __future__ import annotations

import os
from pathlib import Path
import re
from typing import Dict, List, Optional, Tuple

from .dson import is_dson, read_applied_mods, write_applied_mods
from .snapshot_store import SnapshotStore, valid_profile
from .steam_env import InstalledMod

SLOT_RE = re.compile(r"^profile_[0-8]$")


def get_slot_mods(saves_root: Path, slot: str) -> List[Dict[str, str]]:
    if not SLOT_RE.match(slot):
        raise ValueError(f"无效档位: {slot}")
    pg = saves_root / slot / "persist.game.json"
    if not pg.is_file():
        return []
    raw = pg.read_bytes()
    if not is_dson(raw):
        return []
    return [{"name": name, "source": src or "mod"} for name, src in read_applied_mods(raw)]


def set_slot_mods(
    saves_root: Path,
    slot: str,
    mods: List[Tuple[str, str]],
    store: Optional[SnapshotStore] = None,
    backup_note: str = "修改档位 Mod 列表前自动备份",
) -> List[Dict[str, str]]:
    if not SLOT_RE.match(slot):
        raise ValueError(f"无效档位: {slot}")
    prof = saves_root / slot
    pg = prof / "persist.game.json"
    if not valid_profile(prof):
        raise FileNotFoundError(f"该档位不存在有效存档: {slot}")

    cleaned: List[Tuple[str, str]] = []
    seen: set[str] = set()
    for name, src in mods:
        n = (name or "").strip()
        s = (src or "mod").strip() or "mod"
        if not n or n in seen:
            continue
        seen.add(n)
        cleaned.append((n, s))

    original = pg.read_bytes()
    updated = write_applied_mods(original, cleaned)
    verified = read_applied_mods(updated)
    if verified != cleaned:
        raise RuntimeError("DSON Mod 列表校验失败，已中止写入")

    if store is not None:
        store.backup_slot(prof, trigger="pre-edit", note=backup_note, force=True)

    tmp = prof / ".persist.game.json.tmp"
    with open(tmp, "wb") as f:
        f.write(updated)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, pg)

    if store is not None:
        store.baseline(saves_root)

    return [{"name": n, "source": s} for n, s in cleaned]


def ensure_mod_in_slot(
    saves_root: Path,
    slot: str,
    mod_id: str,
    source: str = "mod",
    enable: bool = True,
    top_priority: bool = True,
    store: Optional[SnapshotStore] = None,
) -> bool:
    """Enable or disable a single mod in a slot while keeping every other mod's exact `source` intact."""
    if not SLOT_RE.match(slot):
        return False
    prof = saves_root / slot
    pg = prof / "persist.game.json"
    if not pg.is_file():
        return False
    raw = pg.read_bytes()
    if not is_dson(raw):
        return False
    current = read_applied_mods(raw)
    next_list = [(n, s) for n, s in current if n != mod_id]
    if enable:
        entry = (mod_id, source)
        if top_priority:
            next_list.insert(0, entry)
        else:
            next_list.append(entry)
    if next_list == current:
        return False
    note = f"{'启用' if enable else '停用'} Mod {mod_id} 前自动备份"
    set_slot_mods(saves_root, slot, next_list, store=store, backup_note=note)
    return True


def build_slot_mod_view(
    saves_root: Path,
    slot: str,
    installed_mods: List[InstalledMod],
) -> Dict[str, object]:
    """Return enriched active load order + available uninstalled/inactive mods for a Steam slot."""
    active = get_slot_mods(saves_root, slot)
    by_key: Dict[Tuple[str, str], InstalledMod] = {(m.mod_id, m.source_kind): m for m in installed_mods}
    by_id: Dict[str, InstalledMod] = {}
    for m in installed_mods:
        by_id.setdefault(m.mod_id, m)

    active_enriched = []
    active_ids = set()
    for item in active:
        name = item["name"]
        src = item["source"]
        active_ids.add(name)
        meta = by_key.get((name, src)) or by_id.get(name)
        active_enriched.append(
            {
                "name": name,
                "source": src,
                "title": meta.title if meta else name,
                "version": meta.version if meta else "",
                "installed": meta is not None,
            }
        )

    inactive = [
        {
            "name": m.mod_id,
            "source": m.source_kind,
            "title": m.title,
            "version": m.version,
            "path": m.path,
        }
        for m in installed_mods
        if m.mod_id not in active_ids
    ]
    return {"slot": slot, "active": active_enriched, "inactive": inactive}
