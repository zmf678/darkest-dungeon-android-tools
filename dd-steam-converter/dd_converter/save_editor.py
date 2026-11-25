"""PC complete binary DSON save editor with automatic timestamped backups and Steam catalog indexing."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import json
from pathlib import Path
import re
import shutil
from typing import Dict, List, Optional, Tuple

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
    serialize_dson,
    string_node,
    to_editable_dict,
)

BUILDING_TREES: List[Tuple[str, List[str]]] = [
    ("abbey.meditation", ["a", "b", "c", "d", "e", "f"]),
    ("abbey.prayer", ["a", "b", "c", "d", "e", "f"]),
    ("abbey.flagellation", ["a", "b", "c", "d", "e", "f"]),
    ("blacksmith.weapon", ["a", "b", "c", "d"]),
    ("blacksmith.armour", ["a", "b", "c", "d"]),
    ("blacksmith.cost", ["a", "b", "c", "d", "e"]),
    ("camping_trainer.cost", ["a", "b", "c", "d", "e"]),
    ("guild.skill_levels", ["a", "b", "c", "d"]),
    ("guild.cost", ["a", "b", "c", "d", "e"]),
    ("nomad_wagon.numitems", ["a", "b", "c", "d"]),
    ("nomad_wagon.cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.disease_quirk_cost", ["a", "b", "c", "d", "e"]),
    ("sanitarium.slots", ["a", "b", "c", "d"]),
    ("stage_coach.numrecruits", ["a", "b", "c", "d", "e"]),
    ("stage_coach.rostersize", ["a", "b", "c", "d", "e"]),
    ("stage_coach.upgraded_recruits", ["a", "b", "c"]),
    ("tavern.bar", ["a", "b", "c", "d", "e", "f"]),
    ("tavern.gambling", ["a", "b", "c", "d", "e", "f"]),
    ("tavern.brothel", ["a", "b", "c", "d", "e", "f"]),
]

FALLBACK_TRINKETS = [
    "ancestors_map", "ancestors_candle", "ancestors_pen", "ancestors_portrait",
    "ancestors_lantern", "ancestors_coat", "ancestors_musket_ball", "ancestors_pistol",
    "ancestors_handkerchief", "ancestors_moustache_cream", "ancestors_tentacle_idol",
    "ancestors_bottle", "ancestors_scroll", "ancestors_signet_ring", "focus_ring",
    "sun_ring", "moon_ring", "tough_ring", "legendary_bracer", "cleansing_crystal",
    "sacred_scroll", "dismas_head", "barristans_head", "junias_head", "dd_trinket",
]

FALLBACK_POSITIVE_QUIRKS = [
    "tough", "hard_skinned", "quick_reflexes", "quickdraw", "natural_swing",
    "natural_eye", "evasive", "slugger", "unerring", "deadly", "precision_striker",
    "eagle_eye", "luminous", "prismatic_solidity", "prismatic_speed", "prismatic_precision",
    "corvid_eye", "corvid_grace", "early_riser", "photomania", "unyielding", "hippocratic",
]

DISEASE_IDS = {
    "bad_humours", "creeping_cough", "hemophilia", "the_runs", "wasting_sickness",
    "tetanus", "rabies", "syphilis", "stomach_cramp", "bulimic", "lethargy",
    "spotted_fever", "vertigo", "vampiric_spirits", "ennui", "the_ague",
    "hysterical_blindness", "scurvy", "the_worries", "the_fits", "tapeworm",
    "the_red_plague", "the_black_plague",
}


@dataclass
class HeroSummary:
    guid: str
    name: str
    hero_class: str
    status: int
    hp_fraction: float
    stress: float
    resolve_xp: int
    weapon_rank: int
    armour_rank: int
    affliction: str
    virtue: str
    quirks: List[Tuple[str, bool]]  # (quirk_id, locked)


def load_game_catalog(steam_dir: Optional[Path]) -> Dict[str, List[str]]:
    trinkets = list(FALLBACK_TRINKETS)
    pos_quirks = list(FALLBACK_POSITIVE_QUIRKS)
    diseases = sorted(DISEASE_IDS)
    if steam_dir and steam_dir.is_dir():
        found_t: List[str] = []
        for p in [steam_dir / "trinkets/base.entries.trinkets.json", *(steam_dir / "dlc").rglob("*.entries.trinkets.json")]:
            if p.is_file():
                try:
                    data = json.loads(p.read_text(encoding="utf-8", errors="replace"))
                    for e in data.get("entries", []):
                        tid = e.get("id")
                        if tid and tid not in found_t:
                            found_t.append(tid)
                except Exception:
                    pass
        if found_t:
            trinkets = found_t

        ql = steam_dir / "shared/quirk/quirk_library.json"
        if ql.is_file():
            try:
                data = json.loads(ql.read_text(encoding="utf-8", errors="replace"))
                p_list: List[str] = []
                d_list: List[str] = []
                for q in data.get("quirks", []):
                    qid = q.get("id")
                    if not qid:
                        continue
                    if q.get("is_disease"):
                        d_list.append(qid)
                    elif q.get("is_positive"):
                        p_list.append(qid)
                if p_list:
                    pos_quirks = p_list
                if d_list:
                    diseases = d_list
            except Exception:
                pass
    return {"trinkets": trinkets, "positive_quirks": pos_quirks, "diseases": diseases}


def backup_profile(profile_dir: Path) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_root = profile_dir.parent / ".torch-backups" / f"{profile_dir.name}-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=True)
    for f in profile_dir.iterdir():
        if f.is_file() and not f.name.endswith(".tmp"):
            shutil.copy2(f, backup_root / f.name)
    return backup_root


def _read_doc(path: Path) -> DsonDocument:
    if not path.is_file():
        raise FileNotFoundError(f"Save file not found: {path}")
    return parse_dson(path.read_bytes())


def _write_doc_verified(path: Path, doc: DsonDocument) -> None:
    encoded = serialize_dson(doc)
    check = parse_dson(encoded)
    if check.root.name != doc.root.name or len(check.root.children) != len(doc.root.children):
        raise RuntimeError("DSON round-trip verification failed")
    tmp = path.with_name(f".{path.name}.tmp")
    tmp.write_bytes(encoded)
    tmp.replace(path)


def read_estate(profile_dir: Path) -> Dict[str, object]:
    game = _read_doc(profile_dir / "persist.game.json")
    estate = _read_doc(profile_dir / "persist.estate.json")
    wallet: Dict[str, int] = {k: 0 for k in ("gold", "bust", "portrait", "deed", "crest", "shard", "blueprint")}
    wn = estate.root.child("wallet")
    if wn and wn.is_object:
        for entry in wn.children:
            if not entry.is_object:
                continue
            t = entry.child("type")
            a = entry.child("amount")
            if t and a:
                wallet[t.as_string()] = a.as_int()
    hs = estate.root.child("endless_wave_highscore")
    return {
        "estate_name": game.root.child("estatename").as_string() if game.root.child("estatename") else "",
        "game_mode": game.root.child("game_mode").as_string() if game.root.child("game_mode") else "darkest",
        "wallet": wallet,
        "endless_wave_highscore": hs.as_int() if hs else 0,
    }


def write_estate(
    profile_dir: Path,
    estate_name: Optional[str] = None,
    game_mode: Optional[str] = None,
    wallet_updates: Optional[Dict[str, int]] = None,
    endless_highscore: Optional[int] = None,
    create_backup: bool = True,
) -> None:
    if create_backup:
        backup_profile(profile_dir)
    if estate_name is not None or game_mode is not None:
        gp = profile_dir / "persist.game.json"
        game = _read_doc(gp)
        if estate_name is not None:
            game.root.set_child(string_node("estatename", estate_name.strip()))
        if game_mode is not None:
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


def list_heroes(profile_dir: Path) -> List[HeroSummary]:
    roster = _read_doc(profile_dir / "persist.roster.json")
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
        qlist: List[Tuple[str, bool]] = []
        if quirks_node and quirks_node.is_object:
            for q in quirks_node.children:
                if q.is_object:
                    lk = q.child("is_locked")
                    qlist.append((q.name, lk.as_bool() if lk else False))
        out.append(
            HeroSummary(
                guid=wrapper.name,
                name=actor.child("name").as_string() if actor and actor.child("name") else f"Hero#{wrapper.name}",
                hero_class=hr.child("heroClass").as_string() if hr.child("heroClass") else "",
                status=hr.child("roster.status").as_int() if hr.child("roster.status") else 0,
                hp_fraction=actor.child("current_hp").as_float() if actor and actor.child("current_hp") else 1.0,
                stress=hr.child("m_Stress").as_float() if hr.child("m_Stress") else 0.0,
                resolve_xp=hr.child("resolveXp").as_int() if hr.child("resolveXp") else 0,
                weapon_rank=hr.child("weapon_rank").as_int() if hr.child("weapon_rank") else 0,
                armour_rank=hr.child("armour_rank").as_int() if hr.child("armour_rank") else 0,
                affliction=hr.child("affliction_type_id").as_string() if hr.child("affliction_type_id") else "",
                virtue=hr.child("virtue_type_id").as_string() if hr.child("virtue_type_id") else "",
                quirks=qlist,
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
    create_backup: bool = True,
) -> None:
    if create_backup:
        backup_profile(profile_dir)
    rp = profile_dir / "persist.roster.json"
    roster = _read_doc(rp)
    heroes = roster.root.child("heroes")
    wrapper = heroes.child(guid) if heroes else None
    raw_node = wrapper.child("hero_file_data").child("raw_data") if wrapper and wrapper.child("hero_file_data") else None
    if not raw_node or not raw_node.as_embedded_dson():
        raise KeyError(f"Hero not found: {guid}")
    hdoc = parse_dson(raw_node.as_embedded_dson())
    hr = hdoc.root
    actor = hr.ensure_object("actor")
    if name is not None and name.strip():
        actor.set_child(string_node("name", name.strip()))
    if hp_fraction is not None:
        actor.set_child(float_node("current_hp", max(0.01, min(1.0, float(hp_fraction)))))
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
        for qid, locked in quirks:
            qid = qid.strip()
            if not qid:
                continue
            node = existing.get(qid)
            if node is None:
                node = DsonNode(
                    qid,
                    True,
                    children=[
                        bool_node("is_new", False),
                        bool_node("is_locked", locked),
                        int_node("trinketId", 0),
                        int_node("mission_count", 0),
                        int_node("replaces_quirk", 0),
                        bool_node("replaces_quirk_viewed", False),
                        int_node("evolution_duration_remaining", 0),
                    ],
                )
            else:
                node.set_child(bool_node("is_locked", locked))
            rebuilt.append(node)
        qn.children = rebuilt

    raw_node.set_embedded_dson(serialize_dson(hdoc))
    _write_doc_verified(rp, roster)


def batch_improve_heroes(
    profile_dir: Path,
    max_level_and_gear: bool = False,
    heal_and_purify: bool = True,
    create_backup: bool = True,
) -> int:
    if create_backup:
        backup_profile(profile_dir)
    rp = profile_dir / "persist.roster.json"
    roster = _read_doc(rp)
    heroes = roster.root.child("heroes")
    if not heroes or not heroes.is_object:
        return 0
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
            actor.set_child(float_node("current_hp", 1.0))
            hr.set_child(float_node("m_Stress", 0.0))
            hr.set_child(string_node("affliction_type_id", ""))
            hr.set_child(int_node("affliction_severity", 0))
            if hr.child("roster.status") and hr.child("roster.status").as_int() == 3:
                hr.set_child(int_node("roster.status", 0))
                hr.set_child(int_node("roster.before_on_start_town_visit_status", 0))
            qn = hr.child("quirks")
            if qn and qn.is_object:
                qn.children = [q for q in qn.children if q.name not in DISEASE_IDS]
        if max_level_and_gear:
            hr.set_child(int_node("resolveXp", 48))
            hr.set_child(int_node("weapon_rank", 4))
            hr.set_child(int_node("armour_rank", 4))
        raw_node.set_embedded_dson(serialize_dson(hdoc))
        count += 1
    if max_level_and_gear:
        roster.root.set_child(int_node("highest_resolve_xp", 48))
    _write_doc_verified(rp, roster)
    return count


def list_trinkets(profile_dir: Path) -> List[Tuple[int, str, int]]:
    estate = _read_doc(profile_dir / "persist.estate.json")
    t = estate.root.child("trinkets")
    items = t.child("items") if t else None
    out: List[Tuple[int, str, int]] = []
    if items and items.is_object:
        for idx, node in enumerate(items.children):
            if node.is_object and node.child("id"):
                amt = node.child("amount").as_int() if node.child("amount") else 1
                out.append((idx, node.child("id").as_string(), amt))
    return out


def add_trinket(profile_dir: Path, trinket_id: str, amount: int = 1, create_backup: bool = True) -> None:
    if create_backup:
        backup_profile(profile_dir)
    ep = profile_dir / "persist.estate.json"
    estate = _read_doc(ep)
    items = estate.root.ensure_object("trinkets").ensure_object("items")
    for _ in range(max(1, amount)):
        items.children.append(
            DsonNode(
                str(len(items.children)),
                True,
                children=[
                    string_node("id", trinket_id.strip()),
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


def remove_trinket(profile_dir: Path, index: int, create_backup: bool = True) -> None:
    if create_backup:
        backup_profile(profile_dir)
    ep = profile_dir / "persist.estate.json"
    estate = _read_doc(ep)
    items = estate.root.ensure_object("trinkets").ensure_object("items")
    if index < 0 or index >= len(items.children):
        raise IndexError(f"Trinket index out of bounds: {index}")
    del items.children[index]
    for idx, ch in enumerate(items.children):
        ch.name = str(idx)
    _write_doc_verified(ep, estate)


def unlock_all_buildings(profile_dir: Path, create_backup: bool = True) -> int:
    if create_backup:
        backup_profile(profile_dir)
    up = profile_dir / "persist.upgrades.json"
    doc = _read_doc(up)
    purchases = doc.root.ensure_object("purchases")
    existing = {}
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
    return changed


def dump_profile_json(profile_dir: Path) -> Dict[str, object]:
    out: Dict[str, object] = {}
    for f in sorted(profile_dir.glob("*.json")):
        raw = f.read_bytes()
        if is_dson(raw):
            out[f.name] = to_editable_dict(parse_dson(raw).root)
    return out


def edit_raw_field(
    profile_dir: Path,
    filename: str,
    path_parts: List[str],
    value_type: str,
    new_value: str,
    create_backup: bool = True,
) -> None:
    if not path_parts:
        raise ValueError("Field path cannot be empty")
    if create_backup:
        backup_profile(profile_dir)
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
            raise KeyError(f"Field not found: {'/'.join(path_parts)}")
        cur = nxt
    if cur.is_object:
        raise TypeError("Target node is an object, not a scalar")

    if value_type == "bool":
        cur.set_bool(new_value.strip().lower() in ("1", "true", "yes"))
    elif value_type == "int":
        cur.set_int(int(new_value.strip()))
    elif value_type == "float":
        cur.set_float(float(new_value.strip()))
    elif value_type == "string":
        cur.set_string(new_value)
    else:
        raise ValueError(f"Unsupported scalar type: {value_type}")

    if emb_carrier is not None and emb_doc is not None:
        emb_carrier.set_embedded_dson(serialize_dson(emb_doc))
    _write_doc_verified(fp, root_doc)
