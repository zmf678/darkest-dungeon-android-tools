"""Auto-detect Steam Darkest Dungeon installation, DLCs, Workshop mods, and save slots."""

from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path
import re
from typing import Dict, List, Optional
import xml.etree.ElementTree as ET

from .dson import is_dson, parse_dson, read_applied_mods

APP_ID = "262060"

DLC_MAP = {
    "445700_musketeer": "dlc_the_musketeer",
    "580100_crimson_court": "dlc_the_crimson_court",
    "702540_shieldbreaker": "dlc_the_shieldbreaker",
    "735730_color_of_madness": "dlc_the_color_of_madness",
    "2445510_fires_edge": "dlc_the_fire_s_edge",
}


@dataclass
class WorkshopMod:
    mod_id: str
    title: str
    path: Path
    source_kind: str  # "workshop" or "local"


@dataclass
class SaveSlotInfo:
    slot: str
    path: Path
    estate_name: str
    game_mode: str
    date_time: str
    applied_mods: List[str] = field(default_factory=list)


@dataclass
class SteamEnvironment:
    steam_root: Optional[Path]
    game_dir: Optional[Path]
    workshop_dir: Optional[Path]
    dlcs: Dict[str, Path] = field(default_factory=dict)
    mods: Dict[str, WorkshopMod] = field(default_factory=dict)
    save_profiles: List[SaveSlotInfo] = field(default_factory=list)


def _find_steam_root() -> Optional[Path]:
    if os.name == "nt":
        try:
            import winreg

            for root_hive, subkey in (
                (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam"),
            ):
                try:
                    with winreg.OpenKey(root_hive, subkey) as key:
                        val, _ = winreg.QueryValueEx(key, "SteamPath")
                        p = Path(val)
                        if p.is_dir():
                            return p
                except OSError:
                    continue
        except ImportError:
            pass
    for candidate in (
        Path(r"D:\Steam"),
        Path(r"C:\Program Files (x86)\Steam"),
        Path(r"C:\Program Files\Steam"),
        Path.home() / ".steam/steam",
        Path.home() / ".local/share/Steam",
    ):
        if candidate.is_dir():
            return candidate
    return None


def _parse_library_folders(steam_root: Path) -> List[Path]:
    libs = [steam_root]
    vdf = steam_root / "steamapps/libraryfolders.vdf"
    if not vdf.is_file():
        return libs
    text = vdf.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r'"path"\s+"([^"]+)"', text):
        raw = m.group(1).replace("\\\\", "\\")
        p = Path(raw)
        if p.is_dir() and p not in libs:
            libs.append(p)
    return libs


def _parse_mod_title(folder: Path) -> str:
    px = folder / "project.xml"
    if px.is_file():
        try:
            raw = px.read_text(encoding="utf-8", errors="replace")
            m = re.search(r"<(?:title|mod_title)>\s*(?:<!\[CDATA\[)?([^<]+)", raw, re.I)
            if m:
                return m.group(1).strip()
        except OSError:
            pass
    return folder.name


def detect_steam(explicit_game_dir: Optional[Path] = None, explicit_steam_root: Optional[Path] = None) -> SteamEnvironment:
    steam_root = explicit_steam_root or _find_steam_root()
    game_dir = explicit_game_dir
    workshop_dir: Optional[Path] = None

    libraries = _parse_library_folders(steam_root) if steam_root else []
    for lib in libraries:
        candidate = lib / "steamapps/common/DarkestDungeon"
        if game_dir is None and candidate.is_dir():
            game_dir = candidate
        ws = lib / "steamapps/workshop/content" / APP_ID
        if workshop_dir is None and ws.is_dir():
            workshop_dir = ws

    dlcs: Dict[str, Path] = {}
    if game_dir and (game_dir / "dlc").is_dir():
        for d in sorted((game_dir / "dlc").iterdir()):
            if d.is_dir():
                dlcs[d.name] = d

    mods: Dict[str, WorkshopMod] = {}
    if workshop_dir and workshop_dir.is_dir():
        for d in sorted(workshop_dir.iterdir()):
            if d.is_dir():
                mods[d.name] = WorkshopMod(d.name, _parse_mod_title(d), d, "workshop")
    if game_dir and (game_dir / "mods").is_dir():
        for d in sorted((game_dir / "mods").iterdir()):
            if d.is_dir() and not d.name.startswith("."):
                mods[d.name] = WorkshopMod(d.name, _parse_mod_title(d), d, "local")

    save_profiles: List[SaveSlotInfo] = []
    if steam_root and (steam_root / "userdata").is_dir():
        for prof in sorted((steam_root / "userdata").glob(f"*/{APP_ID}/remote/profile_*")):
            if not re.fullmatch(r"profile_[0-8]", prof.name):
                continue
            pg = prof / "persist.game.json"
            if not pg.is_file():
                continue
            raw = pg.read_bytes()
            if not is_dson(raw):
                continue
            doc = parse_dson(raw)
            estate = (doc.root.child("estatename").as_string() if doc.root.child("estatename") else "") or "Unnamed"
            mode = doc.root.child("game_mode").as_string() if doc.root.child("game_mode") else "darkest"
            dt = doc.root.child("date_time").as_string() if doc.root.child("date_time") else ""
            applied = [name for name, _src in read_applied_mods(raw)]
            save_profiles.append(SaveSlotInfo(prof.name, prof, estate, mode, dt, applied))

    return SteamEnvironment(
        steam_root=steam_root,
        game_dir=game_dir,
        workshop_dir=workshop_dir,
        dlcs=dlcs,
        mods=mods,
        save_profiles=save_profiles,
    )
