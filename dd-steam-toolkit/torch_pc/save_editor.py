"""Complete lossless binary DSON save editor for PC Steam Darkest Dungeon.

Matches & extends APK SaveEditor.java:
- Estate name, mode, 7 currencies, endless wave highscore
- Full hero roster inspection, nested hero DSON editing (HP, stress, XP/level, weapon/armour, virtue/affliction, revive)
- Hero quirks & diseases editing (lock/unlock/add/remove/cure all)
- Trinket inventory & categorized codex addition/removal
- Town 8 buildings upgrade progress & one-click full unlock (`dd_hash`)
- Dungeon 6 regions XP & level editing
- Full raw DSON binary tree explorer (including nested hero DSON) & verified scalar writes
- Every write automatically creates a verified SnapshotStore backup and updates deduplication baseline
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
import os
from pathlib import Path
import re
from typing import Dict, List, Optional, Tuple

from .catalog import (
    BUILDING_GROUPS,
    BUILDING_TREES,
    CURRENCIES,
    DISEASE_IDS,
    DUNGEONS,
    dungeon_level_for_xp,
    hero_class_label,
    is_disease,
    quirk_label,
    resolve_level_for_xp,
    trinket_category,
    trinket_label,
)
from .dson import (
    DsonDocument,
    DsonNode,
    bool_node,
    byte_node,
    dd_hash,
    float_node,
    int_node,
    is_dson,
    parse_dson,
    read_applied_mods,
    serialize_dson,
    string_node,
    to_editable_dict,
)
from .snapshot_store import SnapshotStore, valid_profile

SLOT_RE = re.compile(r"^profile_[0-8]$")


@dataclass
class SlotSummary:
    slot: str
    path: str
    estate_name: str
    game_mode: str
    in_raid: bool
    date_time: str
    gold: int
    heirloom_total: int
    hero_count: int
    trinket_count: int
    applied_mods_count: int

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class HeroSummary:
    guid: str
    name: str
    hero_class: str
    hero_class_label: str
    status: int  # 0=idle, 1=in_raid, 2=activity, 3=dead
    status_label: str
    hp_fraction: float
    hp_raw: float
    hp_is_absolute: bool
    stress: float
    resolve_xp: int
    resolve_level: int
    weapon_rank: int
    armour_rank: int
    affliction: str
    virtue: str
    quirks: List[Dict[str, object]]
    disease_count: int

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class RawEntry:
    index: int
    key: str
    is_object: bool
    child_count: int
    type_tag: str  # "obj", "dson", "bool", "int", "float", "string", "bytes"
    display_value: str
    raw_value: str
    editable: bool

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


def _status_label(status: int) -> str:
    return {0: "空闲", 1: "地牢探险中", 2: "城镇设施中", 3: "已阵亡"}.get(status, f"状态{status}")


def _read_doc(path: Path) -> DsonDocument:
    if not path.is_file():
        raise FileNotFoundError(f"找不到存档文件: {path}")
    return parse_dson(path.read_bytes())


def _write_doc_verified(path: Path, doc: DsonDocument) -> None:
    encoded = serialize_dson(doc)
    check = parse_dson(encoded)
    if check.root.name != doc.root.name or len(check.root.children) != len(doc.root.children):
        raise RuntimeError("DSON 往返结构自检失败，已中止写入")
    tmp = path.with_name(f".{path.name}.tmp")
    with open(tmp, "wb") as f:
        f.write(encoded)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _pre_write_backup(profile_dir: Path, store: Optional[SnapshotStore], note: str) -> None:
    if store is not None and valid_profile(profile_dir):
        store.backup_slot(profile_dir, trigger="pre-edit", note=note, force=True)


def _post_write_baseline(profile_dir: Path, store: Optional[SnapshotStore]) -> None:
    if store is not None:
        store.baseline(profile_dir.parent)


def _read_wallet(estate_root: DsonNode) -> Dict[str, int]:
    wallet: Dict[str, int] = {c["id"]: 0 for c in CURRENCIES}
    wn = estate_root.child("wallet")
    if wn and wn.is_object:
        for item in wn.children:
            if not item.is_object:
                continue
            t = item.child("type")
            a = item.child("amount")
            if t and a:
                wallet[t.as_string()] = a.as_int()
    return wallet


def list_slots(saves_root: Path) -> List[SlotSummary]:
    out: List[SlotSummary] = []
    if not saves_root.is_dir():
        return out
    for i in range(9):
        slot = f"profile_{i}"
        prof = saves_root / slot
        if not valid_profile(prof):
            continue
        gp = prof / "persist.game.json"
        raw_game = gp.read_bytes()
        if not is_dson(raw_game):
            continue
        game = parse_dson(raw_game)
        estate_name = (game.root.child("estatename").as_string() if game.root.child("estatename") else "") or "未命名庄园"
        mode = (game.root.child("game_mode").as_string() if game.root.child("game_mode") else "") or "darkest"
        in_raid = game.root.child("inraid").as_bool() if game.root.child("inraid") else False
        dt = game.root.child("date_time").as_string() if game.root.child("date_time") else ""
        mods = read_applied_mods(raw_game)

        gold = 0
        heirlooms = 0
        trinket_cnt = 0
        ep = prof / "persist.estate.json"
        if ep.is_file():
            est = _read_doc(ep)
            w = _read_wallet(est.root)
            gold = w.get("gold", 0)
            heirlooms = w.get("bust", 0) + w.get("portrait", 0) + w.get("deed", 0) + w.get("crest", 0)
            tr = est.root.child("trinkets")
            items = tr.child("items") if tr else None
            if items and items.is_object:
                trinket_cnt = len(items.children)

        hero_cnt = 0
        rp = prof / "persist.roster.json"
        if rp.is_file():
            ros = _read_doc(rp)
            heroes = ros.root.child("heroes")
            if heroes and heroes.is_object:
                hero_cnt = len(heroes.children)

        out.append(
            SlotSummary(
                slot=slot,
                path=str(prof),
                estate_name=estate_name,
                game_mode=mode,
                in_raid=in_raid,
                date_time=dt,
                gold=gold,
                heirloom_total=heirlooms,
                hero_count=hero_cnt,
                trinket_count=trinket_cnt,
                applied_mods_count=len(mods),
            )
        )
    return out


def read_estate(profile_dir: Path) -> Dict[str, object]:
    game = _read_doc(profile_dir / "persist.game.json")
    estate = _read_doc(profile_dir / "persist.estate.json")
    wallet = _read_wallet(estate.root)
    hs = estate.root.child("endless_wave_highscore")
    return {
        "slot": profile_dir.name,
        "estate_name": game.root.child("estatename").as_string() if game.root.child("estatename") else "",
        "game_mode": game.root.child("game_mode").as_string() if game.root.child("game_mode") else "darkest",
        "in_raid": game.root.child("inraid").as_bool() if game.root.child("inraid") else False,
        "date_time": game.root.child("date_time").as_string() if game.root.child("date_time") else "",
        "wallet": wallet,
        "endless_wave_highscore": hs.as_int() if hs else 0,
    }


def write_estate(
    profile_dir: Path,
    estate_name: Optional[str] = None,
    game_mode: Optional[str] = None,
    wallet_updates: Optional[Dict[str, int]] = None,
    endless_highscore: Optional[int] = None,
    store: Optional[SnapshotStore] = None,
) -> None:
    _pre_write_backup(profile_dir, store, "修改庄园/货币前自动备份")
    if estate_name is not None or game_mode is not None:
        gp = profile_dir / "persist.game.json"
        game = _read_doc(gp)
        if estate_name is not None and estate_name.strip():
            game.root.set_child(string_node("estatename", estate_name.strip()))
        if game_mode is not None and game_mode.strip():
            game.root.set_child(string_node("game_mode", game_mode.strip()))
        _write_doc_verified(gp, game)

    ep = profile_dir / "persist.estate.json"
    estate = _read_doc(ep)
    if wallet_updates:
        wn = estate.root.ensure_object("wallet")
        by_type: Dict[str, DsonNode] = {}
        for item in wn.children:
            if item.is_object and item.child("type"):
                by_type[item.child("type").as_string()] = item
        for k, v in wallet_updates.items():
            amt = max(0, int(v))
            if k in by_type:
                by_type[k].set_child(int_node("amount", amt))
            elif amt > 0:
                created = DsonNode(str(len(wn.children)), True, children=[int_node("amount", amt), string_node("type", k)])
                wn.children.append(created)
                by_type[k] = created
    if endless_highscore is not None:
        estate.root.set_child(int_node("endless_wave_highscore", max(0, int(endless_highscore))))
    _write_doc_verified(ep, estate)
    _post_write_baseline(profile_dir, store)


def list_heroes(profile_dir: Path, extra_diseases: Optional[set[str]] = None) -> List[HeroSummary]:
    rp = profile_dir / "persist.roster.json"
    if not rp.is_file():
        return []
    roster = _read_doc(rp)
    heroes_node = roster.root.child("heroes")
    out: List[HeroSummary] = []
    if not heroes_node or not heroes_node.is_object:
        return out
    for wrapper in heroes_node.children:
        fd = wrapper.child("hero_file_data")
        raw_node = fd.child("raw_data") if fd else None
        emb = raw_node.as_embedded_dson() if raw_node else None
        if not emb:
            continue
        hr = parse_dson(emb).root
        actor = hr.child("actor")
        quirks_node = hr.child("quirks")
        qlist: List[Dict[str, object]] = []
        dis_cnt = 0
        if quirks_node and quirks_node.is_object:
            for q in quirks_node.children:
                if not q.is_object:
                    continue
                lk = q.child("is_locked")
                nw = q.child("is_new")
                dis = is_disease(q.name, extra_diseases)
                if dis:
                    dis_cnt += 1
                qlist.append(
                    {
                        "id": q.name,
                        "label": quirk_label(q.name),
                        "locked": lk.as_bool() if lk else False,
                        "is_new": nw.as_bool() if nw else False,
                        "is_disease": dis,
                    }
                )
        cls = hr.child("heroClass").as_string() if hr.child("heroClass") else ""
        status = hr.child("roster.status").as_int() if hr.child("roster.status") else 0
        xp = hr.child("resolveXp").as_int() if hr.child("resolveXp") else 0
        hp_raw = round(actor.child("current_hp").as_float(), 4) if actor and actor.child("current_hp") else 1.0
        hp_abs = hp_raw > 1.001
        hp_frac = 1.0 if hp_abs else max(0.0, min(1.0, hp_raw))
        out.append(
            HeroSummary(
                guid=wrapper.name,
                name=actor.child("name").as_string() if actor and actor.child("name") else f"英雄#{wrapper.name}",
                hero_class=cls,
                hero_class_label=hero_class_label(cls),
                status=status,
                status_label=_status_label(status),
                hp_fraction=hp_frac,
                hp_raw=hp_raw,
                hp_is_absolute=hp_abs,
                stress=round(hr.child("m_Stress").as_float(), 2) if hr.child("m_Stress") else 0.0,
                resolve_xp=xp,
                resolve_level=resolve_level_for_xp(xp),
                weapon_rank=hr.child("weapon_rank").as_int() if hr.child("weapon_rank") else 0,
                armour_rank=hr.child("armour_rank").as_int() if hr.child("armour_rank") else 0,
                affliction=hr.child("affliction_type_id").as_string() if hr.child("affliction_type_id") else "",
                virtue=hr.child("virtue_type_id").as_string() if hr.child("virtue_type_id") else "",
                quirks=qlist,
                disease_count=dis_cnt,
            )
        )
    out.sort(key=lambda h: int(h.guid) if h.guid.isdigit() else 999999)
    return out


def update_hero(
    profile_dir: Path,
    guid: str,
    name: Optional[str] = None,
    hp_fraction: Optional[float] = None,
    stress: Optional[float] = None,
    resolve_xp: Optional[int] = None,
    weapon_rank: Optional[int] = None,
    armour_rank: Optional[int] = None,
    affliction: Optional[str] = None,
    virtue: Optional[str] = None,
    revive: bool = False,
    quirks: Optional[List[Tuple[str, bool]]] = None,
    store: Optional[SnapshotStore] = None,
) -> None:
    _pre_write_backup(profile_dir, store, f"修改英雄 #{guid} 前自动备份")
    rp = profile_dir / "persist.roster.json"
    roster = _read_doc(rp)
    heroes = roster.root.child("heroes")
    wrapper = heroes.child(str(guid)) if heroes else None
    raw_node = wrapper.child("hero_file_data").child("raw_data") if wrapper and wrapper.child("hero_file_data") else None
    if not raw_node or not raw_node.as_embedded_dson():
        raise KeyError(f"找不到英雄 GUID: {guid}")

    hdoc = parse_dson(raw_node.as_embedded_dson())
    hr = hdoc.root
    actor = hr.ensure_object("actor")
    if name is not None and name.strip():
        actor.set_child(string_node("name", name.strip()))
    if hp_fraction is not None:
        val = float(hp_fraction)
        prev_hp = actor.child("current_hp").as_float() if actor.child("current_hp") else 1.0
        if val > 1.001:
            # Caller explicitly passed an absolute HP value
            actor.set_child(float_node("current_hp", max(1.0, val)))
        elif prev_hp > 1.001 and val >= 0.999:
            # Preserve existing full absolute HP in Steam saves instead of shrinking it to 1.0
            actor.set_child(float_node("current_hp", prev_hp))
        else:
            actor.set_child(float_node("current_hp", max(0.01, min(1.0, val))))
    if stress is not None:
        hr.set_child(float_node("m_Stress", max(0.0, min(200.0, float(stress)))))
    if resolve_xp is not None:
        hr.set_child(int_node("resolveXp", max(0, int(resolve_xp))))
    if weapon_rank is not None:
        hr.set_child(int_node("weapon_rank", max(0, min(4, int(weapon_rank)))))
    if armour_rank is not None:
        hr.set_child(int_node("armour_rank", max(0, min(4, int(armour_rank)))))
    if affliction is not None:
        hr.set_child(string_node("affliction_type_id", affliction))
        hr.set_child(int_node("affliction_severity", 0 if not affliction else 1))
    if virtue is not None:
        hr.set_child(string_node("virtue_type_id", virtue))
        if virtue:
            hr.set_child(string_node("affliction_type_id", ""))
            hr.set_child(int_node("affliction_severity", 0))
    if revive:
        if hr.child("roster.status") and hr.child("roster.status").as_int() == 3:
            hr.set_child(int_node("roster.status", 0))
            hr.set_child(int_node("roster.before_on_start_town_visit_status", 0))
        hr.set_child(bool_node("is_death_heart_attack_completed", False))
        hr.set_child(bool_node("has_had_heart_attack", False))
        hr.set_child(int_node("visited_deaths_door", 0))
    if quirks is not None:
        qn = hr.ensure_object("quirks")
        existing = {c.name: c for c in qn.children}
        rebuilt: List[DsonNode] = []
        seen_ids: set[str] = set()
        for qid, locked in quirks:
            qid = qid.strip()
            if not qid or qid in seen_ids:
                continue
            seen_ids.add(qid)
            node = existing.get(qid)
            if node is None:
                node = DsonNode(
                    qid,
                    True,
                    children=[
                        bool_node("is_new", False),
                        bool_node("is_locked", bool(locked)),
                        int_node("trinketId", 0),
                        int_node("mission_count", 0),
                        int_node("replaces_quirk", 0),
                        bool_node("replaces_quirk_viewed", False),
                        int_node("evolution_duration_remaining", 0),
                    ],
                )
            else:
                node.set_child(bool_node("is_locked", bool(locked)))
            rebuilt.append(node)
        qn.children = rebuilt

    raw_node.set_embedded_dson(serialize_dson(hdoc))
    _write_doc_verified(rp, roster)
    _post_write_baseline(profile_dir, store)


def batch_improve_heroes(
    profile_dir: Path,
    max_level_and_gear: bool = False,
    heal_and_purify: bool = True,
    extra_diseases: Optional[set[str]] = None,
    store: Optional[SnapshotStore] = None,
) -> int:
    _pre_write_backup(profile_dir, store, "批量强化全体英雄前自动备份")
    rp = profile_dir / "persist.roster.json"
    roster = _read_doc(rp)
    heroes = roster.root.child("heroes")
    if not heroes or not heroes.is_object:
        return 0
    all_diseases = DISEASE_IDS | (extra_diseases or set())
    count = 0
    for wrapper in heroes.children:
        fd = wrapper.child("hero_file_data")
        raw_node = fd.child("raw_data") if fd else None
        emb = raw_node.as_embedded_dson() if raw_node else None
        if not emb:
            continue
        hdoc = parse_dson(emb)
        hr = hdoc.root
        actor = hr.ensure_object("actor")
        if heal_and_purify:
            prev_hp = actor.child("current_hp").as_float() if actor.child("current_hp") else 1.0
            if prev_hp <= 1.001:
                actor.set_child(float_node("current_hp", 1.0))
            hr.set_child(float_node("m_Stress", 0.0))
            hr.set_child(string_node("affliction_type_id", ""))
            hr.set_child(int_node("affliction_severity", 0))
            if hr.child("roster.status") and hr.child("roster.status").as_int() == 3:
                hr.set_child(int_node("roster.status", 0))
                hr.set_child(int_node("roster.before_on_start_town_visit_status", 0))
            hr.set_child(bool_node("is_death_heart_attack_completed", False))
            hr.set_child(bool_node("has_had_heart_attack", False))
            hr.set_child(int_node("visited_deaths_door", 0))
            qn = hr.child("quirks")
            if qn and qn.is_object:
                qn.children = [q for q in qn.children if q.name not in all_diseases]
        if max_level_and_gear:
            hr.set_child(int_node("resolveXp", 48))
            hr.set_child(int_node("weapon_rank", 4))
            hr.set_child(int_node("armour_rank", 4))
        raw_node.set_embedded_dson(serialize_dson(hdoc))
        count += 1
    if max_level_and_gear:
        roster.root.set_child(int_node("highest_resolve_xp", 48))
    _write_doc_verified(rp, roster)
    _post_write_baseline(profile_dir, store)
    return count


def list_trinkets(profile_dir: Path) -> List[Dict[str, object]]:
    ep = profile_dir / "persist.estate.json"
    if not ep.is_file():
        return []
    estate = _read_doc(ep)
    t = estate.root.child("trinkets")
    items = t.child("items") if t else None
    out: List[Dict[str, object]] = []
    if items and items.is_object:
        for idx, node in enumerate(items.children):
            if node.is_object and node.child("id"):
                tid = node.child("id").as_string()
                amt = node.child("amount").as_int() if node.child("amount") else 1
                out.append(
                    {
                        "index": idx,
                        "id": tid,
                        "label": trinket_label(tid),
                        "category": trinket_category(tid),
                        "amount": amt,
                    }
                )
    return out


def add_trinket(
    profile_dir: Path,
    trinket_id: str,
    amount: int = 1,
    store: Optional[SnapshotStore] = None,
) -> None:
    tid = (trinket_id or "").strip()
    if not tid:
        raise ValueError("饰品 ID 不能为空")
    _pre_write_backup(profile_dir, store, f"添加饰品 {tid} 前自动备份")
    ep = profile_dir / "persist.estate.json"
    estate = _read_doc(ep)
    items = estate.root.ensure_object("trinkets").ensure_object("items")
    for _ in range(max(1, min(99, int(amount)))):
        items.children.append(
            DsonNode(
                str(len(items.children)),
                True,
                children=[
                    string_node("id", tid),
                    string_node("type", "trinket"),
                    int_node("amount", 1),
                    int_node("added_buffs", 0),
                    string_node("hero_name", ""),
                    string_node("previous_trinket_id", ""),
                    bool_node("did_transform", False),
                    int_node("trinkets_gained_count", 0),
                ],
            )
        )
    for idx, ch in enumerate(items.children):
        ch.name = str(idx)
    _write_doc_verified(ep, estate)
    _post_write_baseline(profile_dir, store)


def remove_trinket(
    profile_dir: Path,
    index: int,
    store: Optional[SnapshotStore] = None,
) -> None:
    _pre_write_backup(profile_dir, store, f"移除饰品 #{index} 前自动备份")
    ep = profile_dir / "persist.estate.json"
    estate = _read_doc(ep)
    items = estate.root.ensure_object("trinkets").ensure_object("items")
    if index < 0 or index >= len(items.children):
        raise IndexError(f"饰品索引越界: {index}")
    del items.children[index]
    for idx, ch in enumerate(items.children):
        ch.name = str(idx)
    _write_doc_verified(ep, estate)
    _post_write_baseline(profile_dir, store)


def read_building_progress(profile_dir: Path) -> List[Dict[str, object]]:
    """Inspect unlocked upgrade nodes across the 8 Hamlet buildings."""
    up = profile_dir / "persist.upgrades.json"
    purchased: set[Tuple[int, int]] = set()
    if up.is_file():
        doc = _read_doc(up)
        purchases = doc.root.child("purchases")
        if purchases and purchases.is_object:
            for p in purchases.children:
                if not p.is_object:
                    continue
                inst = p.child("instance_number").as_int() if p.child("instance_number") else 0
                if inst != 0:
                    continue
                bought = p.child("is_purchased")
                if bought and bought.as_bool():
                    tid = p.child("tree_id").as_int() if p.child("tree_id") else 0
                    req = p.child("requirement_code").as_byte() if p.child("requirement_code") else 0
                    purchased.add((tid, req))

    out: List[Dict[str, object]] = []
    for grp in BUILDING_GROUPS:
        prefix = grp["prefix"]
        total = 0
        done = 0
        for tree_id, reqs in BUILDING_TREES:
            if not tree_id.startswith(prefix):
                continue
            thash = dd_hash(tree_id)
            for code_str in reqs:
                total += 1
                if (thash, ord(code_str[0])) in purchased:
                    done += 1
        out.append({"id": prefix.rstrip("."), "label": grp["label"], "unlocked": done, "total": total})
    return out


def unlock_all_buildings(profile_dir: Path, store: Optional[SnapshotStore] = None) -> int:
    _pre_write_backup(profile_dir, store, "解锁全部城镇建筑前自动备份")
    up = profile_dir / "persist.upgrades.json"
    doc = _read_doc(up)
    purchases = doc.root.ensure_object("purchases")
    existing: Dict[Tuple[int, int, int], DsonNode] = {}
    for p in purchases.children:
        if not p.is_object:
            continue
        inst = p.child("instance_number").as_int() if p.child("instance_number") else 0
        tid = p.child("tree_id").as_int() if p.child("tree_id") else 0
        req = p.child("requirement_code").as_byte() if p.child("requirement_code") else 0
        existing[(inst, tid, req)] = p

    changed = 0
    for tree_id, reqs in BUILDING_TREES:
        thash = dd_hash(tree_id)
        for code_str in reqs:
            code = ord(code_str[0])
            key = (0, thash, code)
            if key in existing:
                node = existing[key]
                bought = node.child("is_purchased")
                if not bought or not bought.as_bool():
                    node.set_child(bool_node("is_purchased", True))
                    changed += 1
            else:
                node = DsonNode(
                    str(len(purchases.children)),
                    True,
                    children=[
                        int_node("instance_number", 0),
                        int_node("tree_id", thash),
                        byte_node("requirement_code", code),
                        bool_node("is_purchased", True),
                    ],
                )
                purchases.children.append(node)
                existing[key] = node
                changed += 1
    for idx, ch in enumerate(purchases.children):
        ch.name = str(idx)
    _write_doc_verified(up, doc)
    _post_write_baseline(profile_dir, store)
    return changed


def read_dungeon_xp(profile_dir: Path) -> List[Dict[str, object]]:
    xp_map: Dict[str, int] = {d["id"]: 0 for d in DUNGEONS}
    pf = profile_dir / "persist.progression.json"
    if pf.is_file():
        doc = _read_doc(pf)
        dn = doc.root.child("dungeon")
        if dn and dn.is_object:
            for child in dn.children:
                if child.is_object:
                    xp_node = child.child("xp")
                    xp_map[child.name] = xp_node.as_int() if xp_node else 0
    out: List[Dict[str, object]] = []
    known_ids = {d["id"] for d in DUNGEONS}
    for d in DUNGEONS:
        xp = xp_map.get(d["id"], 0)
        out.append({"id": d["id"], "label": d["label"], "xp": xp, "level": dungeon_level_for_xp(xp)})
    for k, xp in xp_map.items():
        if k not in known_ids:
            out.append({"id": k, "label": k, "xp": xp, "level": dungeon_level_for_xp(xp)})
    return out


def write_dungeon_xp(
    profile_dir: Path,
    dungeon_xp: Dict[str, int],
    store: Optional[SnapshotStore] = None,
) -> None:
    _pre_write_backup(profile_dir, store, "修改地牢区域经验前自动备份")
    pf = profile_dir / "persist.progression.json"
    doc = _read_doc(pf)
    dn = doc.root.ensure_object("dungeon")
    for region, xp in dungeon_xp.items():
        area = dn.ensure_object(region)
        area.set_child(int_node("xp", max(0, int(xp))))
    _write_doc_verified(pf, doc)
    _post_write_baseline(profile_dir, store)


def list_dson_files(profile_dir: Path) -> List[str]:
    files: List[str] = []
    for p in sorted(profile_dir.iterdir(), key=lambda x: x.name):
        if not p.is_file() or not p.name.endswith(".json"):
            continue
        try:
            if is_dson(p.read_bytes()[:64]):
                files.append(p.name)
        except OSError:
            continue
    return files


def inspect_raw_path(profile_dir: Path, filename: str, path_parts: List[str]) -> List[RawEntry]:
    if "/" in filename or "\\" in filename or ".." in filename:
        raise ValueError("非法存档文件名")
    doc = _read_doc(profile_dir / filename)
    cur = doc.root
    for seg in path_parts:
        if not cur.is_object and cur.as_embedded_dson() is not None:
            cur = parse_dson(cur.as_embedded_dson()).root
        nxt = cur.child(seg)
        if nxt is None:
            raise KeyError(f"路径不存在: /{'/'.join(path_parts)}")
        cur = nxt
    if not cur.is_object and cur.as_embedded_dson() is not None:
        cur = parse_dson(cur.as_embedded_dson()).root
    if not cur.is_object:
        raise TypeError("目标节点不是对象，无法展开")

    out: List[RawEntry] = []
    for idx, c in enumerate(cur.children):
        if c.is_object:
            out.append(RawEntry(idx, c.name, True, len(c.children), "obj", f"{{{len(c.children)} 项}}", "", False))
        elif c.single_byte:
            v = c.as_byte() & 0xFF
            disp = ("true (1)" if v == 1 else "false (0)") if v <= 1 else str(v)
            raw_v = "true" if v == 1 else ("false" if v == 0 else str(v))
            out.append(RawEntry(idx, c.name, False, 0, "bool", disp, raw_v, True))
        elif c.as_embedded_dson() is not None:
            inner = parse_dson(c.as_embedded_dson())
            out.append(
                RawEntry(
                    idx,
                    c.name,
                    True,
                    len(inner.root.children),
                    "dson",
                    f"嵌套 DSON ({len(inner.root.children)} 项)",
                    "",
                    False,
                )
            )
        elif c.is_string_payload():
            sv = c.as_string()
            out.append(RawEntry(idx, c.name, False, 0, "string", sv, sv, True))
        elif len(c.payload) == 4:
            iv = c.as_int()
            fv = c.as_float()
            looks_float = not math.isnan(fv) and not math.isinf(fv) and abs(iv) > 100000 and 1e-4 <= abs(fv) < 1e7
            disp = f"int={iv} | float={fv:.4g}"
            tag = "float" if looks_float else "int"
            raw_v = f"{fv:.6g}" if looks_float else str(iv)
            out.append(RawEntry(idx, c.name, False, 0, tag, disp, raw_v, True))
        else:
            out.append(RawEntry(idx, c.name, False, 0, "bytes", f"[{len(c.payload)} bytes]", c.payload.hex(), False))
    return out


def edit_raw_scalar(
    profile_dir: Path,
    filename: str,
    path_parts: List[str],
    value_type: str,
    new_value: str,
    store: Optional[SnapshotStore] = None,
) -> None:
    if "/" in filename or "\\" in filename or ".." in filename:
        raise ValueError("非法存档文件名")
    if not path_parts:
        raise ValueError("字段路径不能为空")
    _pre_write_backup(profile_dir, store, f"原始 DSON 修改 {filename}/{'/'.join(path_parts)}")
    fp = profile_dir / filename
    root_doc = _read_doc(fp)
    emb_carrier: Optional[DsonNode] = None
    emb_doc: Optional[DsonDocument] = None
    cur = root_doc.root
    for seg in path_parts:
        if not cur.is_object and cur.as_embedded_dson() is not None:
            emb_carrier = cur
            emb_doc = parse_dson(cur.as_embedded_dson())
            cur = emb_doc.root
        nxt = cur.child(seg)
        if nxt is None:
            raise KeyError(f"找不到字段: {'/'.join(path_parts)}")
        cur = nxt
    if cur.is_object:
        raise TypeError("目标节点是对象，仅支持修改标量字段")

    vt = value_type.strip().lower()
    if vt == "bool":
        v = new_value.strip().lower()
        if v in ("1", "true", "yes"):
            cur.set_byte(1)
        elif v in ("0", "false", "no"):
            cur.set_byte(0)
        else:
            cur.set_byte(int(v))
    elif vt == "int":
        cur.set_int(int(new_value.strip()))
    elif vt == "float":
        cur.set_float(float(new_value.strip()))
    elif vt == "string":
        cur.set_string(new_value)
    else:
        raise ValueError(f"不支持的标量类型: {value_type}")

    if emb_carrier is not None and emb_doc is not None:
        emb_carrier.set_embedded_dson(serialize_dson(emb_doc))
    _write_doc_verified(fp, root_doc)
    _post_write_baseline(profile_dir, store)


def dump_profile_json(profile_dir: Path) -> Dict[str, object]:
    out: Dict[str, object] = {}
    for f in sorted(profile_dir.glob("*.json")):
        raw = f.read_bytes()
        if is_dson(raw):
            out[f.name] = to_editable_dict(parse_dson(raw).root)
    return out
