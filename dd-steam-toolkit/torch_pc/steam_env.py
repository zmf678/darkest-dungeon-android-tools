"""PC Steam environment detection, multi-account discovery, process safety checks, and game launch."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Dict, List, Optional

from .dson import is_dson, parse_dson, read_applied_mods

APP_ID = "262060"
GAME_PROCESS_NAMES = ("Darkest.exe", "Darkest", "darkest.bin.x86_64", "darkest.bin.x86")

DLC_LABELS = {
    "445700_musketeer": "火枪手 (The Musketeer)",
    "580100_crimson_court": "猩红庭院 (The Crimson Court)",
    "702540_shieldbreaker": "破盾者 (The Shieldbreaker)",
    "735730_color_of_madness": "疯狂色彩 (The Color of Madness)",
    "1117860_butchers_circus": "屠夫马戏团 (The Butcher's Circus)",
    "2445510_fires_edge": "余烬之刃 (Fire's Edge)",
}


@dataclass
class InstalledMod:
    mod_id: str
    title: str
    version: str
    path: str
    source_kind: str  # "Steam" (workshop) or "mod" (local mods/)
    file_count: int = 0

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class SaveAccount:
    account_id: str
    saves_root: str
    slot_names: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class SteamEnvironment:
    steam_root: Optional[str]
    game_dir: Optional[str]
    workshop_dir: Optional[str]
    active_saves_root: Optional[str]
    accounts: List[SaveAccount] = field(default_factory=list)
    dlcs: List[Dict[str, str]] = field(default_factory=list)
    mods: List[InstalledMod] = field(default_factory=list)
    game_running: bool = False

    def to_dict(self) -> Dict[str, object]:
        return {
            "steam_root": self.steam_root,
            "game_dir": self.game_dir,
            "workshop_dir": self.workshop_dir,
            "active_saves_root": self.active_saves_root,
            "accounts": [a.to_dict() for a in self.accounts],
            "dlcs": self.dlcs,
            "mods": [m.to_dict() for m in self.mods],
            "game_running": self.game_running,
        }


def find_steam_root() -> Optional[Path]:
    if os.name == "nt":
        try:
            import winreg

            for hive, subkey in (
                (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam"),
            ):
                try:
                    with winreg.OpenKey(hive, subkey) as key:
                        val, _ = winreg.QueryValueEx(key, "SteamPath")
                        p = Path(val)
                        if p.is_dir():
                            return p
                except OSError:
                    continue
        except ImportError:
            pass
    for candidate in (
        Path(r"C:\Program Files (x86)\Steam"),
        Path(r"C:\Program Files\Steam"),
        Path(r"D:\Steam"),
        Path(r"E:\Steam"),
        Path.home() / ".steam/steam",
        Path.home() / ".local/share/Steam",
        Path.home() / "Library/Application Support/Steam",
    ):
        if candidate.is_dir():
            return candidate
    return None


def parse_library_folders(steam_root: Path) -> List[Path]:
    libs = [steam_root]
    vdf = steam_root / "steamapps" / "libraryfolders.vdf"
    if not vdf.is_file():
        return libs
    text = vdf.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r'"path"\s+"([^"]+)"', text):
        raw = m.group(1).replace("\\\\", "\\")
        p = Path(raw)
        if p.is_dir() and p not in libs:
            libs.append(p)
    return libs


def parse_mod_metadata(folder: Path) -> tuple[str, str]:
    px = folder / "project.xml"
    title = folder.name
    version = ""
    if px.is_file():
        try:
            raw_bytes = px.read_bytes()
            if raw_bytes.startswith(b"\xff\xfe") or raw_bytes.startswith(b"\xfe\xff"):
                raw = raw_bytes.decode("utf-16", errors="replace")
            else:
                raw = raw_bytes.decode("utf-8", errors="replace")
            m = re.search(r"<(?:title|mod_title)>\s*(?:<!\[CDATA\[)?([^<]+)", raw, re.I)
            if m:
                parsed = m.group(1).replace("]]>", "").strip()
                if parsed:
                    title = parsed[:120]
            maj = re.search(r"<VersionMajor>\s*(\d+)", raw, re.I)
            mn = re.search(r"<VersionMinor>\s*(\d+)", raw, re.I)
            if maj:
                version = f"{maj.group(1)}.{mn.group(1)}" if mn else maj.group(1)
        except OSError:
            pass
    return title, version


def is_game_running() -> bool:
    """Check whether Darkest Dungeon PC executable is currently running."""
    try:
        if os.name == "nt":
            out = subprocess.check_output(
                ["tasklist", "/FI", "IMAGENAME eq Darkest.exe", "/NH"],
                stderr=subprocess.DEVNULL,
                text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return "darkest.exe" in out.lower()
        out = subprocess.check_output(["ps", "-A", "-o", "comm="], stderr=subprocess.DEVNULL, text=True)
        lowered = {line.strip().lower() for line in out.splitlines() if line.strip()}
        return any(name.lower() in lowered for name in GAME_PROCESS_NAMES)
    except Exception:
        return False


def launch_steam_game(game_dir: Optional[Path] = None) -> str:
    """Launch Steam Darkest Dungeon via steam:// URI (falls back to direct executable)."""
    url = f"steam://rungameid/{APP_ID}"
    if os.name == "nt":
        try:
            os.startfile(url)  # type: ignore[attr-defined]
            return f"已通过 Steam 启动《暗黑地牢》({url})"
        except OSError:
            pass
        if game_dir:
            for rel in ("_windows/Darkest.exe", "_windowsnosteam/Darkest.exe", "Darkest.exe"):
                exe = game_dir / rel
                if exe.is_file():
                    subprocess.Popen([str(exe)], cwd=str(exe.parent))
                    return f"已启动本地程序：{exe}"
        raise RuntimeError("未能唤醒 Steam 客户端，请确认已安装 Steam 或手动指定游戏目录。")
    opener = "open" if sys.platform == "darwin" else "xdg-open"
    subprocess.Popen([opener, url])
    return f"已请求启动 Steam《暗黑地牢》({url})"


def default_archive_home() -> Path:
    """Persistent user-level directory for snapshots, metadata, and toolkit config."""
    env_override = os.environ.get("TORCH_PC_HOME")
    if env_override:
        base = Path(env_override)
    elif os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "TorchArchivePC"
    else:
        base = Path.home() / ".local" / "share" / "TorchArchivePC"
    base.mkdir(parents=True, exist_ok=True)
    return base


def _list_valid_slots(saves_root: Path) -> List[str]:
    found: List[str] = []
    if not saves_root.is_dir():
        return found
    for i in range(9):
        slot = f"profile_{i}"
        pg = saves_root / slot / "persist.game.json"
        if pg.is_file():
            try:
                if is_dson(pg.read_bytes()[:64]):
                    found.append(slot)
            except OSError:
                continue
    return found


def detect_environment(
    explicit_game_dir: Optional[Path] = None,
    explicit_steam_root: Optional[Path] = None,
    explicit_saves_root: Optional[Path] = None,
) -> SteamEnvironment:
    steam_root = explicit_steam_root or find_steam_root()
    game_dir = explicit_game_dir
    workshop_dir: Optional[Path] = None

    libraries = parse_library_folders(steam_root) if steam_root else []
    for lib in libraries:
        candidate = lib / "steamapps" / "common" / "DarkestDungeon"
        if game_dir is None and candidate.is_dir():
            game_dir = candidate
        ws = lib / "steamapps" / "workshop" / "content" / APP_ID
        if workshop_dir is None and ws.is_dir():
            workshop_dir = ws

    dlcs: List[Dict[str, str]] = []
    if game_dir and (game_dir / "dlc").is_dir():
        for d in sorted((game_dir / "dlc").iterdir()):
            if d.is_dir():
                dlcs.append({"id": d.name, "label": DLC_LABELS.get(d.name, d.name), "path": str(d)})

    mods: List[InstalledMod] = []
    seen_mods: set[tuple[str, str]] = set()
    if game_dir and (game_dir / "mods").is_dir():
        for d in sorted((game_dir / "mods").iterdir(), key=lambda x: x.name.lower()):
            if d.is_dir() and not d.name.startswith("."):
                title, ver = parse_mod_metadata(d)
                key = (d.name, "mod")
                if key not in seen_mods:
                    seen_mods.add(key)
                    mods.append(InstalledMod(d.name, title, ver, str(d), "mod"))
    if workshop_dir and workshop_dir.is_dir():
        for d in sorted(workshop_dir.iterdir(), key=lambda x: x.name.lower()):
            if d.is_dir() and not d.name.startswith("."):
                title, ver = parse_mod_metadata(d)
                key = (d.name, "Steam")
                if key not in seen_mods:
                    seen_mods.add(key)
                    mods.append(InstalledMod(d.name, title, ver, str(d), "Steam"))

    accounts: List[SaveAccount] = []
    if steam_root and (steam_root / "userdata").is_dir():
        for uid_dir in sorted((steam_root / "userdata").iterdir()):
            remote = uid_dir / APP_ID / "remote"
            if remote.is_dir():
                slots = _list_valid_slots(remote)
                accounts.append(SaveAccount(uid_dir.name, str(remote), slots))

    # Also check Documents/Darkest (GOG / non-Steam / legacy Windows location)
    docs_darkest = Path.home() / "Documents" / "Darkest"
    if docs_darkest.is_dir():
        doc_slots = _list_valid_slots(docs_darkest)
        if doc_slots:
            accounts.append(SaveAccount("Documents/Darkest", str(docs_darkest), doc_slots))

    active_saves: Optional[str] = None
    if explicit_saves_root:
        p = Path(explicit_saves_root)
        # Accept either remote/ parent or direct profile_N folder
        if re.fullmatch(r"profile_[0-8]", p.name) and (p / "persist.game.json").is_file():
            p = p.parent
        active_saves = str(p)
        if all(Path(a.saves_root) != p for a in accounts):
            accounts.insert(0, SaveAccount("custom", str(p), _list_valid_slots(p)))
    else:
        for a in accounts:
            if a.slot_names:
                active_saves = a.saves_root
                break
        if active_saves is None and accounts:
            active_saves = accounts[0].saves_root

    return SteamEnvironment(
        steam_root=str(steam_root) if steam_root else None,
        game_dir=str(game_dir) if game_dir else None,
        workshop_dir=str(workshop_dir) if workshop_dir else None,
        active_saves_root=active_saves,
        accounts=accounts,
        dlcs=dlcs,
        mods=mods,
        game_running=is_game_running(),
    )
