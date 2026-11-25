"""Atomic save snapshot store & archive vault for PC Steam Darkest Dungeon.

Ported from Android TorchLauncher's SnapshotStore.java & ArchiveStore.java with:
- Deterministic SHA-256 deduplicated ZIP snapshots (`seq-timestamp-slot-sha256.zip`)
- Transactional restore with checksum verification, forced pre-restore backup, and interrupted-restore recovery
- Human-friendly labels/notes, snapshot diff summaries, ZIP export/import, and cross-platform Android/Steam save import
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading
import time
from typing import Dict, List, Optional, Tuple
import zipfile

from .dson import is_dson, parse_dson, read_applied_mods, write_applied_mods

SNAPSHOT_ID_RE = re.compile(r"^([0-9]{19})-([0-9]{13})-(profile_[0-8])-([0-9a-f]{64})$")
SLOT_RE = re.compile(r"^profile_[0-8]$")
MAIN_SAVE_FILE = "persist.game.json"
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024  # 1 GB
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)


@dataclass
class SnapshotRecord:
    id: str
    slot: str
    timestamp_ms: int
    digest: str
    size_bytes: int
    trigger: str  # "auto", "manual", "pre-edit", "pre-restore", "pre-import"
    note: str
    estate_name: str
    game_mode: str
    in_raid: bool
    gold: int
    hero_count: int
    trinket_count: int
    date_time: str
    file_count: int

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


def valid_profile(profile_dir: Path) -> bool:
    if not profile_dir.is_dir() or profile_dir.is_symlink():
        return False
    main = profile_dir / MAIN_SAVE_FILE
    return main.is_file() and not main.is_symlink()


def _extract_profile_summary(profile_dir: Path) -> Dict[str, object]:
    summary: Dict[str, object] = {
        "estate_name": "未命名庄园",
        "game_mode": "darkest",
        "in_raid": False,
        "gold": 0,
        "hero_count": 0,
        "trinket_count": 0,
        "date_time": "",
    }
    try:
        gp = profile_dir / MAIN_SAVE_FILE
        if gp.is_file():
            raw = gp.read_bytes()
            if is_dson(raw):
                doc = parse_dson(raw)
                n_est = doc.root.child("estatename")
                n_mode = doc.root.child("game_mode")
                n_raid = doc.root.child("inraid")
                n_dt = doc.root.child("date_time")
                if n_est and n_est.as_string():
                    summary["estate_name"] = n_est.as_string()
                if n_mode and n_mode.as_string():
                    summary["game_mode"] = n_mode.as_string()
                if n_raid:
                    summary["in_raid"] = n_raid.as_bool()
                if n_dt:
                    summary["date_time"] = n_dt.as_string()
    except Exception:
        pass

    try:
        ep = profile_dir / "persist.estate.json"
        if ep.is_file():
            raw = ep.read_bytes()
            if is_dson(raw):
                est = parse_dson(raw)
                wn = est.root.child("wallet")
                if wn and wn.is_object:
                    for item in wn.children:
                        if item.is_object and item.child("type") and item.child("type").as_string() == "gold":
                            a = item.child("amount")
                            if a:
                                summary["gold"] = a.as_int()
                tr = est.root.child("trinkets")
                items = tr.child("items") if tr else None
                if items and items.is_object:
                    summary["trinket_count"] = len(items.children)
    except Exception:
        pass

    try:
        rp = profile_dir / "persist.roster.json"
        if rp.is_file():
            raw = rp.read_bytes()
            if is_dson(raw):
                ros = parse_dson(raw)
                heroes = ros.root.child("heroes")
                if heroes and heroes.is_object:
                    summary["hero_count"] = len(heroes.children)
    except Exception:
        pass
    return summary


def _write_deterministic_zip(profile_dir: Path, out_path: Optional[Path]) -> Tuple[str, int]:
    """Pack a profile_N directory deterministically and return (sha256_hex, file_count)."""
    slot = profile_dir.name
    files: List[Path] = []
    total_size = 0
    for root, dirs, filenames in os.walk(profile_dir):
        dirs.sort()
        filenames.sort()
        for fname in filenames:
            if fname.endswith(".tmp") or fname.endswith(".old") or fname.startswith("."):
                continue
            fp = Path(root) / fname
            if fp.is_symlink() or not fp.is_file():
                continue
            total_size += fp.stat().st_size
            if total_size > MAX_ARCHIVE_BYTES:
                raise IOError("Save profile exceeds 1 GB limit")
            files.append(fp)

    hasher = hashlib.sha256()

    class _HashingSink:
        def __init__(self, real_file):
            self._f = real_file
            self._pos = 0

        def write(self, data: bytes) -> int:
            hasher.update(data)
            self._pos += len(data)
            if self._f is not None:
                return self._f.write(data)
            return len(data)

        def tell(self) -> int:
            return self._pos

        def flush(self) -> None:
            if self._f is not None:
                self._f.flush()

        def seekable(self) -> bool:
            return False

    fh = open(out_path, "wb") if out_path is not None else None
    try:
        sink = _HashingSink(fh)
        with zipfile.ZipFile(sink, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
            for fp in files:
                rel = f"{slot}/{fp.relative_to(profile_dir).as_posix()}"
                zi = zipfile.ZipInfo(filename=rel, date_time=FIXED_ZIP_TIME)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = 0o644 << 16
                zf.writestr(zi, fp.read_bytes())
        if fh is not None:
            fh.flush()
            os.fsync(fh.fileno())
    finally:
        if fh is not None:
            fh.close()
    return hasher.hexdigest(), len(files)


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


class SnapshotStore:
    """Thread-safe atomic snapshot repository compatible with TorchLauncher's format."""

    def __init__(self, history_dir: Path):
        self.history_dir = history_dir
        self.history_dir.mkdir(parents=True, exist_ok=True)
        self._meta_path = self.history_dir / "snapshots-meta.json"
        self._lock = threading.RLock()
        self._latest: Dict[str, str] = {}
        self._sequence: int = 0
        self._meta: Dict[str, Dict[str, object]] = self._load_meta()
        self._reindex()

    def _load_meta(self) -> Dict[str, Dict[str, object]]:
        if not self._meta_path.is_file():
            return {}
        try:
            data = json.loads(self._meta_path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def _save_meta(self) -> None:
        tmp = self.history_dir / ".snapshots-meta.json.tmp"
        tmp.write_text(json.dumps(self._meta, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self._meta_path)

    def _reindex(self) -> None:
        self._latest.clear()
        for snap_id in self.list_ids():
            m = SNAPSHOT_ID_RE.match(snap_id)
            if not m:
                continue
            seq = int(m.group(1))
            slot = m.group(3)
            digest = m.group(4)
            if seq > self._sequence:
                self._sequence = seq
            if slot not in self._latest:
                self._latest[slot] = digest

    def list_ids(self) -> List[str]:
        with self._lock:
            ids: List[str] = []
            for p in self.history_dir.iterdir():
                if p.is_file() and p.name.endswith(".zip"):
                    stem = p.name[:-4]
                    if SNAPSHOT_ID_RE.match(stem):
                        ids.append(stem)
            ids.sort(reverse=True)
            return ids

    def archive_path(self, snap_id: str) -> Path:
        if not snap_id or not SNAPSHOT_ID_RE.match(snap_id):
            raise ValueError(f"非法快照 ID: {snap_id}")
        p = self.history_dir / f"{snap_id}.zip"
        if not p.is_file() or p.is_symlink():
            raise FileNotFoundError(f"找不到快照文件: {snap_id}")
        return p

    def list_records(self, slot: Optional[str] = None) -> List[SnapshotRecord]:
        with self._lock:
            out: List[SnapshotRecord] = []
            for snap_id in self.list_ids():
                m = SNAPSHOT_ID_RE.match(snap_id)
                if not m:
                    continue
                s_slot = m.group(3)
                if slot and s_slot != slot:
                    continue
                ts_ms = int(m.group(2))
                digest = m.group(4)
                zp = self.history_dir / f"{snap_id}.zip"
                size_bytes = zp.stat().st_size if zp.is_file() else 0
                meta = self._meta.get(snap_id, {})
                out.append(
                    SnapshotRecord(
                        id=snap_id,
                        slot=s_slot,
                        timestamp_ms=ts_ms,
                        digest=digest,
                        size_bytes=size_bytes,
                        trigger=str(meta.get("trigger", "auto")),
                        note=str(meta.get("note", "")),
                        estate_name=str(meta.get("estate_name", "未命名庄园")),
                        game_mode=str(meta.get("game_mode", "darkest")),
                        in_raid=bool(meta.get("in_raid", False)),
                        gold=int(meta.get("gold", 0)),
                        hero_count=int(meta.get("hero_count", 0)),
                        trinket_count=int(meta.get("trinket_count", 0)),
                        date_time=str(meta.get("date_time", "")),
                        file_count=int(meta.get("file_count", 0)),
                    )
                )
            return out

    def baseline(self, saves_root: Path) -> None:
        """Record current active profiles as deduplication baseline without creating new snapshots."""
        with self._lock:
            for i in range(9):
                slot = f"profile_{i}"
                prof = saves_root / slot
                if valid_profile(prof):
                    digest, _ = _write_deterministic_zip(prof, None)
                    self._latest[slot] = digest
                else:
                    self._latest.pop(slot, None)

    def capture_changed(self, saves_root: Path, trigger: str = "auto", note: str = "") -> List[SnapshotRecord]:
        """Scan profile_0..8 and snapshot any slot whose SHA-256 changed since last snapshot/baseline."""
        created: List[SnapshotRecord] = []
        with self._lock:
            for i in range(9):
                slot = f"profile_{i}"
                prof = saves_root / slot
                if valid_profile(prof):
                    rec = self._publish(prof, force=False, trigger=trigger, note=note)
                    if rec is not None:
                        created.append(rec)
                else:
                    self._latest.pop(slot, None)
        return created

    def backup_slot(
        self,
        profile_dir: Path,
        trigger: str = "manual",
        note: str = "",
        force: bool = True,
    ) -> Optional[SnapshotRecord]:
        with self._lock:
            if not valid_profile(profile_dir):
                raise FileNotFoundError(f"档位不存在有效存档: {profile_dir}")
            return self._publish(profile_dir, force=force, trigger=trigger, note=note)

    def _publish(
        self,
        profile_dir: Path,
        force: bool,
        trigger: str,
        note: str,
    ) -> Optional[SnapshotRecord]:
        slot = profile_dir.name
        if not SLOT_RE.match(slot):
            raise ValueError(f"无效档位目录名: {slot}")
        fd, tmp_name = tempfile.mkstemp(prefix=".capture-", suffix=".zip", dir=str(self.history_dir))
        os.close(fd)
        tmp_path = Path(tmp_name)
        try:
            digest, file_count = _write_deterministic_zip(profile_dir, tmp_path)
            if not force and digest == self._latest.get(slot):
                return None
            self._sequence += 1
            ts_ms = int(time.time() * 1000)
            snap_id = f"{self._sequence:019d}-{ts_ms:013d}-{slot}-{digest}"
            dest = self.history_dir / f"{snap_id}.zip"
            os.replace(tmp_path, dest)
            self._latest[slot] = digest

            summary = _extract_profile_summary(profile_dir)
            meta = {
                "trigger": trigger,
                "note": note.strip(),
                "file_count": file_count,
                **summary,
            }
            self._meta[snap_id] = meta
            self._save_meta()
            return SnapshotRecord(
                id=snap_id,
                slot=slot,
                timestamp_ms=ts_ms,
                digest=digest,
                size_bytes=dest.stat().st_size,
                trigger=trigger,
                note=note.strip(),
                estate_name=str(summary["estate_name"]),
                game_mode=str(summary["game_mode"]),
                in_raid=bool(summary["in_raid"]),
                gold=int(summary["gold"]),
                hero_count=int(summary["hero_count"]),
                trinket_count=int(summary["trinket_count"]),
                date_time=str(summary["date_time"]),
                file_count=file_count,
            )
        finally:
            if tmp_path.exists():
                try:
                    tmp_path.unlink()
                except OSError:
                    pass

    def rename_note(self, snap_id: str, note: str) -> None:
        with self._lock:
            self.archive_path(snap_id)
            entry = self._meta.setdefault(snap_id, {})
            entry["note"] = (note or "").strip()[:120]
            self._save_meta()

    def delete_snapshot(self, snap_id: str) -> None:
        with self._lock:
            p = self.archive_path(snap_id)
            p.unlink()
            self._meta.pop(snap_id, None)
            self._save_meta()
            self._reindex()

    def prune(self, keep_per_slot: int = 30, slot: Optional[str] = None) -> int:
        """Keep the newest N snapshots per slot (preserving manually annotated ones) and delete older auto ones."""
        keep_per_slot = max(1, int(keep_per_slot))
        removed = 0
        with self._lock:
            by_slot: Dict[str, List[SnapshotRecord]] = {}
            for rec in self.list_records(slot=slot):
                by_slot.setdefault(rec.slot, []).append(rec)
            for s, recs in by_slot.items():
                # Keep all manually noted or latest keep_per_slot snapshots
                for idx, rec in enumerate(recs):
                    if idx >= keep_per_slot and rec.trigger == "auto" and not rec.note:
                        try:
                            self.archive_path(rec.id).unlink()
                            self._meta.pop(rec.id, None)
                            removed += 1
                        except OSError:
                            continue
            if removed:
                self._save_meta()
                self._reindex()
        return removed

    def compare_snapshots(self, snap_id: str, previous_id: Optional[str] = None) -> Dict[str, object]:
        """Compare file list & CRCs between a snapshot and its predecessor (like APK ArchiveStore.slots)."""
        cur_idx = self._zip_crc_index(self.archive_path(snap_id))
        if previous_id is None:
            m = SNAPSHOT_ID_RE.match(snap_id)
            slot = m.group(3) if m else None
            all_ids = [r.id for r in self.list_records(slot=slot)]
            if snap_id in all_ids:
                pos = all_ids.index(snap_id)
                if pos + 1 < len(all_ids):
                    previous_id = all_ids[pos + 1]
        prev_idx = self._zip_crc_index(self.archive_path(previous_id)) if previous_id else {}
        changed_files: List[str] = []
        added_files: List[str] = []
        removed_files: List[str] = []
        for name, sig in sorted(cur_idx.items()):
            if name not in prev_idx:
                added_files.append(name)
            elif prev_idx[name] != sig:
                changed_files.append(name)
        for name in sorted(prev_idx.keys()):
            if name not in cur_idx:
                removed_files.append(name)
        return {
            "snapshot_id": snap_id,
            "previous_id": previous_id,
            "total_files": len(cur_idx),
            "added": added_files,
            "changed": changed_files,
            "removed": removed_files,
        }

    @staticmethod
    def _zip_crc_index(zip_path: Path) -> Dict[str, str]:
        idx: Dict[str, str] = {}
        with zipfile.ZipFile(zip_path, "r") as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                idx[info.filename] = f"{info.file_size}:{info.CRC}"
        return idx

    def restore(self, saves_root: Path, snap_id: str, target_slot: Optional[str] = None) -> str:
        """Atomically restore a verified snapshot into saves_root (with forced pre-restore backup & crash recovery)."""
        with self._lock:
            self.recover(saves_root)
            zip_path = self.archive_path(snap_id)
            m = SNAPSHOT_ID_RE.match(snap_id)
            assert m is not None
            orig_slot = m.group(3)
            expected_digest = m.group(4)
            if _file_sha256(zip_path) != expected_digest:
                raise IOError("快照 SHA-256 校验失败，文件可能已损坏，已中止回档。")

            dest_slot = target_slot or orig_slot
            if not SLOT_RE.match(dest_slot):
                raise ValueError(f"无效目标档位: {dest_slot}")

            saves_root.mkdir(parents=True, exist_ok=True)
            target = saves_root / dest_slot
            tx = saves_root / ".torcharchive-restore"
            tx.mkdir(exist_ok=False)
            staged = tx / "staged"
            previous = tx / "previous"
            try:
                (tx / "slot").write_text(dest_slot, encoding="ascii")
                staged.mkdir()
                self._unpack_slot_zip(zip_path, orig_slot, staged)
                if not valid_profile(staged):
                    raise IOError("快照中缺少核心存档文件 persist.game.json")
                if target.exists():
                    if valid_profile(target):
                        self._publish(target, force=True, trigger="pre-restore", note=f"回档前自动保护（目标 {snap_id[:12]}）")
                    os.replace(target, previous)
                os.replace(staged, target)
            finally:
                self.recover(saves_root)

            self.baseline(saves_root)
            return dest_slot

    @staticmethod
    def recover(saves_root: Path) -> None:
        tx = saves_root / ".torcharchive-restore"
        if not tx.is_dir():
            return
        previous = tx / "previous"
        if previous.exists():
            slot_file = tx / "slot"
            slot = slot_file.read_text(encoding="ascii").strip() if slot_file.is_file() else ""
            if not SLOT_RE.match(slot):
                raise IOError("异常回档事务，请手动检查 .torcharchive-restore")
            target = saves_root / slot
            if not target.exists():
                os.replace(previous, target)
            elif not valid_profile(target):
                raise IOError(f"上次恢复中断，原档保留在 {previous}")
        shutil.rmtree(tx, ignore_errors=True)

    @staticmethod
    def _unpack_slot_zip(zip_path: Path, src_slot: str, staged: Path) -> None:
        total_bytes = 0
        prefix = f"{src_slot}/"
        with zipfile.ZipFile(zip_path, "r") as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                name = info.filename.replace("\\", "/")
                if not name.startswith(prefix) or ".." in name.split("/"):
                    raise IOError(f"快照包含非法条目: {name}")
                rel = name[len(prefix) :]
                if not rel or rel.startswith("/"):
                    raise IOError(f"快照包含非法相对路径: {name}")
                dest = (staged / rel).resolve()
                if not str(dest).startswith(str(staged.resolve())):
                    raise IOError(f"快照路径越界: {name}")
                total_bytes += info.file_size
                if total_bytes > MAX_ARCHIVE_BYTES:
                    raise IOError("快照解压体积超过 1 GB 上限")
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(zf.read(info))

    def export_snapshot_zip(self, snap_id: str, output_path: Path) -> Path:
        src = self.archive_path(snap_id)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, output_path)
        return output_path

    def import_save_zip(
        self,
        saves_root: Path,
        zip_path: Path,
        target_slot: Optional[str] = None,
        convert_mod_to_steam: bool = False,
        workshop_ids: Optional[set[str]] = None,
    ) -> List[str]:
        """Import a ZIP archive (PC backup, snapshot ZIP, or Android TorchLauncher save export) into saves_root."""
        if not zip_path.is_file():
            raise FileNotFoundError(f"找不到要导入的 ZIP 文件: {zip_path}")
        imported_slots: List[str] = []
        with tempfile.TemporaryDirectory(prefix="torch-import-") as tmpdir:
            work = Path(tmpdir)
            with zipfile.ZipFile(zip_path, "r") as zf:
                total = 0
                for info in zf.infolist():
                    if info.is_dir():
                        continue
                    name = info.filename.replace("\\", "/")
                    if name.startswith("/") or ".." in name.split("/"):
                        raise IOError(f"ZIP 包含不安全路径: {name}")
                    total += info.file_size
                    if total > MAX_ARCHIVE_BYTES:
                        raise IOError("导入 ZIP 超过 1 GB 上限")
                    dest = (work / name).resolve()
                    if not str(dest).startswith(str(work.resolve())):
                        raise IOError(f"ZIP 路径越界: {name}")
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(zf.read(info))

            found_profiles = self._find_profile_dirs(work)
            if not found_profiles:
                raise IOError("ZIP 中未找到有效的 Darkest Dungeon 存档（缺少 persist.game.json）。")

            if target_slot:
                if not SLOT_RE.match(target_slot):
                    raise ValueError(f"无效目标档位: {target_slot}")
                found_profiles = [found_profiles[0]]

            saves_root.mkdir(parents=True, exist_ok=True)
            with self._lock:
                for src_prof in found_profiles:
                    slot = target_slot or (src_prof.name if SLOT_RE.match(src_prof.name) else "profile_0")
                    if convert_mod_to_steam and workshop_ids:
                        pg = src_prof / MAIN_SAVE_FILE
                        raw = pg.read_bytes()
                        if is_dson(raw):
                            mods = read_applied_mods(raw)
                            remapped = [
                                (m_name, "Steam" if m_name in workshop_ids else m_src)
                                for m_name, m_src in mods
                            ]
                            if remapped != mods:
                                pg.write_bytes(write_applied_mods(raw, remapped))

                    dst_prof = saves_root / slot
                    if valid_profile(dst_prof):
                        self._publish(dst_prof, force=True, trigger="pre-import", note=f"导入 {zip_path.name} 前自动备份")
                    incoming = saves_root / f".incoming-{slot}-{int(time.time() * 1000)}"
                    shutil.copytree(src_prof, incoming)
                    if dst_prof.exists():
                        old = saves_root / f".old-{slot}-{int(time.time() * 1000)}"
                        os.replace(dst_prof, old)
                        shutil.rmtree(old, ignore_errors=True)
                    os.replace(incoming, dst_prof)
                    imported_slots.append(slot)
                self.baseline(saves_root)
        return imported_slots

    @staticmethod
    def _find_profile_dirs(root: Path) -> List[Path]:
        if (root / MAIN_SAVE_FILE).is_file():
            return [root]
        found: List[Path] = []
        for p in sorted(root.rglob(MAIN_SAVE_FILE)):
            prof = p.parent
            if any(part.startswith(".") or part == "replaced" for part in prof.relative_to(root).parts):
                continue
            found.append(prof)
        return found
