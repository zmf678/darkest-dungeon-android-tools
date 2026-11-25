"""Build and verify dd_assets.pak (ZIP_STORED) from a genuine Steam Darkest Dungeon install + BYO base APK."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import struct
import subprocess
from typing import Callable, Dict, List, Optional, Set, Tuple
import zipfile

from . import __version__
from .dds_encoder import png_bytes_to_dds
from .steam_detect import DLC_MAP

ROMFS_ROOT_DIRS = (
    "activity_log",
    "analytics",
    "audio",
    "campaign",
    "colours",
    "curios",
    "cursors",
    "dungeons",
    "effects",
    "fe_flow",
    "fonts",
    "fx",
    "game_over",
    "heroes",
    "inventory",
    "loading_screen",
    "localization",
    "loot",
    "maps",
    "modes",
    "monsters",
    "overlays",
    "panels",
    "props",
    "raid",
    "raid_results",
    "scripts",
    "scrolls",
    "shaders",
    "shared",
    "trinkets",
    "upgrades",
    "user_information",
    "video",
)

EXTRA_SKIP_NAMES = {".DS_Store", "Thumbs.db", "desktop.ini"}


@dataclass
class BuildOptions:
    steam_dir: Path
    base_apk: Path
    output_pak: Path
    texture_mode: str = "dds"       # "dds" or "png"
    audio_source: str = "auto"      # "auto", "base", or "steam"
    include_base_dlcs: bool = True  # retain base APK DLCs if Steam copy lacks them
    workers: int = 8
    progress_cb: Optional[Callable[[str, int, int], None]] = None


@dataclass
class VerifyResult:
    ok: bool
    total_entries: int
    total_bytes: int
    checked_csv_rows: int
    non_stored_entries: List[str] = field(default_factory=list)
    missing_entries: List[str] = field(default_factory=list)
    size_mismatches: List[str] = field(default_factory=list)
    manifest: Optional[dict] = None


def _fsb5_mode(raw: bytes) -> Optional[int]:
    pos = raw.find(b"FSB5")
    if pos < 0 or pos + 28 > len(raw):
        return None
    return struct.unpack_from("<I", raw, pos + 24)[0]


def _ffmpeg_ogv_to_mp4(ogv_path: Path) -> Optional[bytes]:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return None
    import tempfile

    with tempfile.TemporaryDirectory(prefix="dd-vid-") as td:
        out_mp4 = Path(td) / (ogv_path.stem + ".mp4")
        subprocess.run(
            [
                ffmpeg,
                "-y",
                "-i",
                str(ogv_path),
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-movflags",
                "+faststart",
                str(out_mp4),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return out_mp4.read_bytes()


def _parse_filelist_rows(raw_csv: bytes) -> List[Tuple[str, str, int]]:
    rows: List[Tuple[str, str, int]] = []
    for line in raw_csv.decode("utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(",")
        if len(parts) < 3:
            continue
        vpath = parts[0].strip()
        apath = parts[1].strip()
        try:
            sz = int(parts[2].strip())
        except ValueError:
            sz = 0
        rows.append((vpath, apath, sz))
    return rows


def _write_stored_bytes(zf: zipfile.ZipFile, arcname: str, data: bytes) -> None:
    info = zipfile.ZipInfo(arcname)
    info.compress_type = zipfile.ZIP_STORED
    zf.writestr(info, data)


def _should_keep_raw_png(rel_path: str, raw_png_set: Set[str]) -> bool:
    if rel_path in raw_png_set:
        return True
    low = rel_path.lower()
    fname = low.rsplit("/", 1)[-1]
    return "colour_grade" in fname or fname == "tint.png"


def _rebuild_scoped_filelist(
    prefix: str,
    fallback_prefix: Optional[str],
    actual_sizes: Dict[str, int],
    base_csv_bytes: Optional[bytes],
    texture_mode: str,
    raw_png_set: Set[str],
) -> bytes:
    """Rebuild a 100%-accurate filelist.csv with exact byte sizes against actual_sizes."""
    base_rows = _parse_filelist_rows(base_csv_bytes) if base_csv_bytes else []
    out_lines: List[str] = []
    seen_actual: Set[str] = set()
    seen_virtual: Set[str] = set()

    def lookup(rel: str) -> Optional[Tuple[str, int]]:
        k1 = prefix + rel
        if k1 in actual_sizes:
            return k1, actual_sizes[k1]
        if fallback_prefix:
            k2 = fallback_prefix + rel
            if k2 in actual_sizes:
                return k2, actual_sizes[k2]
        return None

    for vpath, apath, _old_sz in base_rows:
        target_rel = apath if apath else vpath
        if texture_mode == "png" and vpath.lower().endswith(".png") and target_rel.lower().endswith(".dds"):
            hit = lookup(vpath)
            if hit:
                out_lines.append(f"{vpath},,{hit[1]}")
                seen_actual.add(hit[0])
                seen_virtual.add(vpath)
                continue
        hit = lookup(target_rel)
        if hit:
            out_lines.append(f"{vpath},{apath},{hit[1]}")
            seen_actual.add(hit[0])
            seen_virtual.add(vpath)
        elif apath.lower().endswith(".dds") and vpath.lower().endswith(".png"):
            # If kept as raw .png in Steam pass-through
            hit_png = lookup(vpath)
            if hit_png:
                out_lines.append(f"{vpath},,{hit_png[1]}")
                seen_actual.add(hit_png[0])
                seen_virtual.add(vpath)

    # Append any extra files present in prefix (or fallback_prefix for root romfs/aoc CSVs)
    scan_prefixes = [prefix] if fallback_prefix is None else [prefix]
    for sp in scan_prefixes:
        for full_key in sorted(actual_sizes):
            if not full_key.startswith(sp):
                continue
            if full_key in seen_actual or full_key.endswith("/filelist.csv"):
                continue
            rel = full_key[len(sp) :]
            if rel in ("filelist.csv", "dlc_info.csv"):
                continue
            if rel.lower().endswith(".dds"):
                vrel = rel[:-4] + ".png"
                if vrel not in seen_virtual:
                    out_lines.append(f"{vrel},{rel},{actual_sizes[full_key]}")
                    seen_virtual.add(vrel)
            elif rel not in seen_virtual:
                out_lines.append(f"{rel},,{actual_sizes[full_key]}")
                seen_virtual.add(rel)

    return ("\n".join(out_lines) + "\n").encode("utf-8")


def build_pak(opts: BuildOptions) -> VerifyResult:
    if not opts.steam_dir.is_dir():
        raise FileNotFoundError(f"Steam DarkestDungeon directory not found: {opts.steam_dir}")
    if not opts.base_apk.is_file():
        raise FileNotFoundError(f"Base APK not found: {opts.base_apk}")

    def report(stage: str, done: int, total: int) -> None:
        if opts.progress_cb:
            opts.progress_cb(stage, done, total)

    opts.output_pak.parent.mkdir(parents=True, exist_ok=True)
    temp_pak = opts.output_pak.with_suffix(".pak.tmp")
    if temp_pak.exists():
        temp_pak.unlink()

    actual_sizes: Dict[str, int] = {}
    base_csvs: Dict[str, bytes] = {}
    raw_png_set: Set[str] = set()
    dlcs_included: List[str] = []

    with zipfile.ZipFile(opts.base_apk, "r") as base_zf:
        base_infos = {i.filename: i for i in base_zf.infolist() if not i.is_dir()}
        if "assets/nx/exefs/main" not in base_infos:
            raise ValueError("Base APK does not contain assets/nx/exefs/main")

        for name in base_infos:
            if name.startswith("assets/nx/") and name.endswith("/filelist.csv") and not name.startswith("assets/nx/overlay_ru/"):
                csv_bytes = base_zf.read(name)
                base_csvs[name] = csv_bytes
                for vpath, apath, _sz in _parse_filelist_rows(csv_bytes):
                    if vpath.lower().endswith(".png") and not apath:
                        raw_png_set.add(vpath)

        steam_romfs_files: List[Tuple[str, Path]] = []
        for top in ROMFS_ROOT_DIRS:
            d = opts.steam_dir / top
            if not d.is_dir():
                continue
            for p in d.rglob("*"):
                if p.is_file() and p.name not in EXTRA_SKIP_NAMES:
                    rel = p.relative_to(opts.steam_dir).as_posix()
                    steam_romfs_files.append((rel, p))

        steam_dlc_dirs: Dict[str, Path] = {}
        dlc_root = opts.steam_dir / "dlc"
        if dlc_root.is_dir():
            for d in sorted(dlc_root.iterdir()):
                if d.is_dir() and d.name in DLC_MAP:
                    steam_dlc_dirs[DLC_MAP[d.name]] = d

        with zipfile.ZipFile(temp_pak, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as out_zf:
            # 1. exefs/main from base APK
            exes_bytes = base_zf.read("assets/nx/exefs/main")
            _write_stored_bytes(out_zf, "assets/nx/exefs/main", exes_bytes)
            actual_sizes["assets/nx/exefs/main"] = len(exes_bytes)

            # 2. overlay/ non-CSV files from base APK (excluding overlay_ru/ and deferring all filelist.csv)
            overlay_names = [
                n
                for n in sorted(base_infos)
                if n.startswith("assets/nx/overlay/") and not n.endswith("/filelist.csv")
            ]
            report("Copying Switch overlay UI layer", 0, len(overlay_names))
            for idx, n in enumerate(overlay_names, 1):
                data = base_zf.read(n)
                _write_stored_bytes(out_zf, n, data)
                actual_sizes[n] = len(data)
                if idx % 50 == 0:
                    report("Copying Switch overlay UI layer", idx, len(overlay_names))

            # 3. Map Steam romfs + DLC tasks
            steam_covered_arcs: Set[str] = set()
            conversion_tasks: List[Tuple[str, Path, str]] = []

            for rel, src_path in steam_romfs_files:
                low = rel.lower()
                if low.endswith(".png"):
                    if opts.texture_mode == "png" or _should_keep_raw_png(rel, raw_png_set):
                        arc = f"assets/nx/romfs/{rel}"
                        conversion_tasks.append((arc, src_path, "copy"))
                    else:
                        arc = f"assets/nx/romfs/{rel[:-4]}.dds"
                        conversion_tasks.append((arc, src_path, "dds"))
                    steam_covered_arcs.add(arc)
                    steam_covered_arcs.add(f"assets/nx/romfs/{rel}")
                elif low.endswith(".ogv"):
                    arc = f"assets/nx/romfs/{rel[:-4]}.mp4"
                    conversion_tasks.append((arc, src_path, "video"))
                    steam_covered_arcs.add(arc)
                elif low.endswith(".bank"):
                    arc = f"assets/nx/romfs/{rel}"
                    use_base = False
                    if opts.audio_source == "base" and arc in base_infos:
                        use_base = True
                    elif opts.audio_source == "auto" and arc in base_infos:
                        sw_mode = _fsb5_mode(base_zf.read(arc)[:64])
                        pc_mode = _fsb5_mode(src_path.read_bytes()[:64])
                        if sw_mode is not None and pc_mode is not None and sw_mode != pc_mode:
                            use_base = True
                    if not use_base:
                        conversion_tasks.append((arc, src_path, "copy"))
                        steam_covered_arcs.add(arc)
                elif rel == "filelist.csv":
                    continue
                else:
                    arc = f"assets/nx/romfs/{rel}"
                    conversion_tasks.append((arc, src_path, "copy"))
                    steam_covered_arcs.add(arc)

            for dlc_nx_name, dlc_src_dir in sorted(steam_dlc_dirs.items()):
                dlcs_included.append(dlc_nx_name)
                for p in dlc_src_dir.rglob("*"):
                    if not p.is_file() or p.name in EXTRA_SKIP_NAMES:
                        continue
                    rel = p.relative_to(dlc_src_dir).as_posix()
                    if rel.endswith("filelist.csv"):
                        continue
                    low = rel.lower()
                    if low.endswith(".png"):
                        keep_raw = opts.texture_mode == "png" or _should_keep_raw_png(rel, raw_png_set)
                        arc = f"assets/nx/aoc/{dlc_nx_name}/{rel if keep_raw else rel[:-4] + '.dds'}"
                        kind = "copy" if keep_raw else "dds"
                    elif low.endswith(".ogv"):
                        arc = f"assets/nx/aoc/{dlc_nx_name}/{rel[:-4]}.mp4"
                        kind = "video"
                    elif low.endswith(".bank") and opts.audio_source != "steam":
                        arc = f"assets/nx/aoc/{dlc_nx_name}/{rel}"
                        if opts.audio_source == "base" and arc in base_infos:
                            continue
                        if opts.audio_source == "auto" and arc in base_infos:
                            sw_mode = _fsb5_mode(base_zf.read(arc)[:64])
                            pc_mode = _fsb5_mode(p.read_bytes()[:64])
                            if sw_mode is not None and pc_mode is not None and sw_mode != pc_mode:
                                continue
                        kind = "copy"
                    else:
                        arc = f"assets/nx/aoc/{dlc_nx_name}/{rel}"
                        kind = "copy"
                    conversion_tasks.append((arc, p, kind))
                    steam_covered_arcs.add(arc)
                    steam_covered_arcs.add(f"assets/nx/aoc/{dlc_nx_name}/{rel}")

            # 4. Run multithreaded conversion of Steam assets and write ZIP_STORED
            total_tasks = len(conversion_tasks)
            report("Converting Steam assets", 0, total_tasks)

            def process_one(item: Tuple[str, Path, str]) -> Tuple[str, Optional[bytes]]:
                arc, src_path, kind = item
                if kind == "copy":
                    return arc, src_path.read_bytes()
                if kind == "dds":
                    return arc, png_bytes_to_dds(src_path, prefer_texconv=True)
                if kind == "video":
                    mp4 = _ffmpeg_ogv_to_mp4(src_path)
                    return arc, mp4
                return arc, None

            completed = 0
            with ThreadPoolExecutor(max_workers=max(1, opts.workers)) as pool:
                futures = [pool.submit(process_one, t) for t in conversion_tasks]
                for fut in as_completed(futures):
                    arc, payload = fut.result()
                    completed += 1
                    if payload is not None:
                        _write_stored_bytes(out_zf, arc, payload)
                        actual_sizes[arc] = len(payload)
                    elif arc in base_infos:
                        fallback = base_zf.read(arc)
                        _write_stored_bytes(out_zf, arc, fallback)
                        actual_sizes[arc] = len(fallback)
                    if completed % 250 == 0 or completed == total_tasks:
                        report("Converting Steam assets", completed, total_tasks)

            # 5. Copy Switch-only diff entries & base APK audio/DLC fallback
            report("Merging Switch diff layer & DLCs", 0, 1)
            for name in sorted(base_infos):
                if name.endswith("/filelist.csv"):
                    continue
                if name.startswith("assets/nx/romfs/"):
                    if name not in actual_sizes and name not in steam_covered_arcs:
                        data = base_zf.read(name)
                        _write_stored_bytes(out_zf, name, data)
                        actual_sizes[name] = len(data)
                elif name.startswith("assets/nx/aoc/"):
                    parts = name.split("/")
                    if len(parts) < 4:
                        continue
                    dlc_nx_name = parts[3]
                    if dlc_nx_name in steam_dlc_dirs:
                        if name not in actual_sizes and name not in steam_covered_arcs:
                            data = base_zf.read(name)
                            _write_stored_bytes(out_zf, name, data)
                            actual_sizes[name] = len(data)
                    elif opts.include_base_dlcs:
                        if dlc_nx_name not in dlcs_included:
                            dlcs_included.append(dlc_nx_name)
                        data = base_zf.read(name)
                        _write_stored_bytes(out_zf, name, data)
                        actual_sizes[name] = len(data)

            # 6. Rebuild EVERY filelist.csv (romfs, aoc/**, overlay, overlay/aoc/**) deterministically
            for csv_name, csv_bytes in sorted(base_csvs.items()):
                prefix = csv_name[: -len("filelist.csv")]
                if csv_name == "assets/nx/romfs/filelist.csv":
                    rebuilt = _rebuild_scoped_filelist(prefix, None, actual_sizes, csv_bytes, opts.texture_mode, raw_png_set)
                elif csv_name == "assets/nx/overlay/filelist.csv":
                    rebuilt = _rebuild_scoped_filelist(prefix, "assets/nx/romfs/", actual_sizes, csv_bytes, opts.texture_mode, raw_png_set)
                elif csv_name.startswith("assets/nx/overlay/aoc/"):
                    dlc_nx_name = csv_name.split("/")[4]
                    if dlc_nx_name not in dlcs_included:
                        continue
                    fb = prefix.replace("assets/nx/overlay/aoc/", "assets/nx/aoc/")
                    rebuilt = _rebuild_scoped_filelist(prefix, fb, actual_sizes, csv_bytes, opts.texture_mode, raw_png_set)
                elif csv_name.startswith("assets/nx/aoc/"):
                    dlc_nx_name = csv_name.split("/")[3]
                    if dlc_nx_name not in dlcs_included:
                        continue
                    rebuilt = _rebuild_scoped_filelist(prefix, None, actual_sizes, csv_bytes, opts.texture_mode, raw_png_set)
                else:
                    continue
                _write_stored_bytes(out_zf, csv_name, rebuilt)
                actual_sizes[csv_name] = len(rebuilt)

            # 7. Write pak-manifest.json
            manifest = {
                "converter_version": __version__,
                "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "texture_mode": opts.texture_mode,
                "audio_source": opts.audio_source,
                "dlcs": sorted(dlcs_included),
                "total_files": len(actual_sizes),
                "total_bytes": sum(actual_sizes.values()),
            }
            manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
            _write_stored_bytes(out_zf, "assets/nx/pak-manifest.json", manifest_bytes)
            actual_sizes["assets/nx/pak-manifest.json"] = len(manifest_bytes)

    shutil.move(temp_pak, opts.output_pak)
    report("Verifying dd_assets.pak", 1, 1)
    return verify_pak(opts.output_pak)


def verify_pak(pak_path: Path) -> VerifyResult:
    if not pak_path.is_file():
        raise FileNotFoundError(f"PAK not found: {pak_path}")

    non_stored: List[str] = []
    missing: List[str] = []
    mismatches: List[str] = []
    checked_rows = 0
    manifest: Optional[dict] = None

    with zipfile.ZipFile(pak_path, "r") as zf:
        infos = {i.filename: i for i in zf.infolist() if not i.is_dir()}
        for name, info in infos.items():
            if info.compress_type != zipfile.ZIP_STORED:
                non_stored.append(name)

        if "assets/nx/exefs/main" not in infos:
            missing.append("assets/nx/exefs/main")
        if "assets/nx/pak-manifest.json" in infos:
            manifest = json.loads(zf.read("assets/nx/pak-manifest.json").decode("utf-8"))

        for csv_name in sorted(n for n in infos if n.startswith("assets/nx/") and n.endswith("/filelist.csv")):
            prefix = csv_name[: -len("filelist.csv")]
            fallback_prefix: Optional[str] = None
            if csv_name == "assets/nx/overlay/filelist.csv":
                fallback_prefix = "assets/nx/romfs/"
            elif csv_name.startswith("assets/nx/overlay/aoc/"):
                fallback_prefix = prefix.replace("assets/nx/overlay/aoc/", "assets/nx/aoc/")

            rows = _parse_filelist_rows(zf.read(csv_name))
            for vpath, apath, expected_size in rows:
                checked_rows += 1
                rel = apath if apath else vpath
                info = infos.get(prefix + rel)
                if info is None and fallback_prefix is not None:
                    info = infos.get(fallback_prefix + rel)
                key_label = prefix + rel
                if info is None:
                    missing.append(f"{csv_name} -> {key_label}")
                elif info.file_size != expected_size:
                    mismatches.append(f"{key_label}: csv={expected_size}, actual={info.file_size}")

        total_bytes = sum(i.file_size for i in infos.values())

    ok = not non_stored and not missing and not mismatches
    return VerifyResult(
        ok=ok,
        total_entries=len(infos),
        total_bytes=total_bytes,
        checked_csv_rows=checked_rows,
        non_stored_entries=non_stored[:25],
        missing_entries=missing[:25],
        size_mismatches=mismatches[:25],
        manifest=manifest,
    )
