"""ADB one-click push helper for dd_assets.pak, mods, and unified launcher APK."""

from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
from typing import List, Optional


def find_adb() -> str:
    found = shutil.which("adb")
    if found:
        return found
    for cand in (Path(r"D:\Android\platform-tools\adb.exe"),):
        if cand.is_file():
            return str(cand)
    raise FileNotFoundError("adb executable not found on PATH")


def list_devices() -> List[str]:
    adb = find_adb()
    out = subprocess.check_output([adb, "devices"], text=True)
    devices: List[str] = []
    for line in out.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def push_to_device(
    package: str = "dev.torcharchive.ddgame",
    pak_path: Optional[Path] = None,
    apk_path: Optional[Path] = None,
    mods_dir: Optional[Path] = None,
    serial: Optional[str] = None,
) -> None:
    adb = find_adb()
    devices = list_devices()
    if not devices:
        raise RuntimeError("No authorized ADB device connected. Enable USB debugging and try again.")
    target = serial or devices[0]
    prefix = [adb, "-s", target]

    if apk_path is not None:
        subprocess.run([*prefix, "install", "-r", str(apk_path)], check=True)

    media_root = f"/storage/emulated/0/Android/media/{package}"
    subprocess.run([*prefix, "shell", "mkdir", "-p", f"{media_root}/mods"], check=True)

    if pak_path is not None:
        subprocess.run([*prefix, "push", str(pak_path), f"{media_root}/dd_assets.pak"], check=True)

    if mods_dir is not None and mods_dir.is_dir():
        subprocess.run([*prefix, "push", str(mods_dir), f"{media_root}/mods/"], check=True)
