"""Package Steam save profiles (with automatic source: Steam -> mod conversion) & Workshop mods into steam-migration.zip."""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Dict, List, Optional
import zipfile

from .dson import convert_steam_mods_to_local, is_dson
from .steam_detect import SaveSlotInfo, WorkshopMod


@dataclass
class MigrationSummary:
    output_zip: Path
    slots: List[str]
    mods: List[str]
    missing_mods: List[str]


def build_migration_zip(
    profiles: List[SaveSlotInfo],
    available_mods: Dict[str, WorkshopMod],
    output_zip: Path,
    extra_mod_ids: Optional[List[str]] = None,
) -> MigrationSummary:
    if not profiles and not extra_mod_ids:
        raise ValueError("No save profiles or mods selected for migration")

    output_zip.parent.mkdir(parents=True, exist_ok=True)
    needed_mods: List[str] = []
    seen_mods = set()
    missing_mods: List[str] = []
    included_slots: List[str] = []

    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for prof in profiles:
            included_slots.append(prof.slot)
            for f in sorted(prof.path.rglob("*")):
                if not f.is_file():
                    continue
                rel = f.relative_to(prof.path).as_posix()
                data = f.read_bytes()
                if f.name == "persist.game.json" and is_dson(data):
                    data, mod_names = convert_steam_mods_to_local(data)
                    for m in mod_names:
                        if m not in seen_mods:
                            seen_mods.add(m)
                            needed_mods.append(m)
                zf.writestr(f"saves/{prof.slot}/{rel}", data)

        for m in extra_mod_ids or []:
            if m not in seen_mods:
                seen_mods.add(m)
                needed_mods.append(m)

        packed_mods: List[str] = []
        for mod_id in needed_mods:
            mod = available_mods.get(mod_id)
            if mod is None or not mod.path.is_dir():
                missing_mods.append(mod_id)
                continue
            packed_mods.append(mod_id)
            for f in sorted(mod.path.rglob("*")):
                if f.is_file():
                    rel = f.relative_to(mod.path).as_posix()
                    zf.write(f, f"mods/{mod_id}/{rel}")

        manifest = {
            "slots": included_slots,
            "mods": packed_mods,
            "missing_mods": missing_mods,
        }
        zf.writestr("migration-manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False))

    return MigrationSummary(
        output_zip=output_zip,
        slots=included_slots,
        mods=packed_mods,
        missing_mods=missing_mods,
    )
