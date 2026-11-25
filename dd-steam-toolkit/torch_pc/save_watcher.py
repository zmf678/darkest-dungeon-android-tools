"""Background real-time save watcher for PC Steam Darkest Dungeon.

PC equivalent of TorchLauncher's native `libtorcharchive.so` commit hook:
monitors `profile_0..8` mtimes/sizes with debouncing and automatically captures
SHA-256 deduplicated snapshots whenever Steam saves the game (entering rooms,
finishing combat, returning to Hamlet, or exiting).
"""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

from .snapshot_store import SnapshotRecord, SnapshotStore, valid_profile


@dataclass
class WatcherStatus:
    running: bool
    saves_root: Optional[str]
    poll_interval_sec: float
    debounce_sec: float
    captured_count: int
    last_capture_at: Optional[int]
    last_event: str


class SaveWatcher:
    def __init__(
        self,
        store: SnapshotStore,
        saves_root: Optional[Path] = None,
        poll_interval_sec: float = 1.5,
        debounce_sec: float = 1.5,
        on_capture: Optional[Callable[[SnapshotRecord], None]] = None,
    ):
        self.store = store
        self._saves_root = saves_root
        self.poll_interval_sec = max(0.5, poll_interval_sec)
        self.debounce_sec = max(0.5, debounce_sec)
        self._on_capture = on_capture
        self._stop_evt = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.RLock()
        self._captured_count = 0
        self._last_capture_at: Optional[int] = None
        self._last_event = "未启动自动留档"
        self._last_fingerprints: Dict[str, Tuple[Tuple[str, int, int], ...]] = {}
        self._dirty_since: Dict[str, float] = {}

    def set_saves_root(self, saves_root: Optional[Path]) -> None:
        with self._lock:
            self._saves_root = saves_root
            self._last_fingerprints.clear()
            self._dirty_since.clear()
            if saves_root and saves_root.is_dir():
                self.store.baseline(saves_root)
                self._last_fingerprints = self._scan_fingerprints(saves_root)

    def start(self) -> bool:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return True
            if self._saves_root is None or not self._saves_root.is_dir():
                self._last_event = "未配置有效存档目录，无法启动监控"
                return False
            self.store.baseline(self._saves_root)
            self._last_fingerprints = self._scan_fingerprints(self._saves_root)
            self._dirty_since.clear()
            self._stop_evt.clear()
            self._thread = threading.Thread(target=self._run_loop, name="torch-save-watcher", daemon=True)
            self._thread.start()
            self._last_event = f"正在实时监听 {self._saves_root}"
            return True

    def stop(self) -> None:
        self._stop_evt.set()
        t = self._thread
        if t is not None and t.is_alive():
            t.join(timeout=3.0)
        with self._lock:
            self._thread = None
            self._last_event = "已暂停自动留档"

    def is_running(self) -> bool:
        with self._lock:
            return self._thread is not None and self._thread.is_alive()

    def status(self) -> WatcherStatus:
        with self._lock:
            return WatcherStatus(
                running=self.is_running(),
                saves_root=str(self._saves_root) if self._saves_root else None,
                poll_interval_sec=self.poll_interval_sec,
                debounce_sec=self.debounce_sec,
                captured_count=self._captured_count,
                last_capture_at=self._last_capture_at,
                last_event=self._last_event,
            )

    @staticmethod
    def _scan_fingerprints(saves_root: Path) -> Dict[str, Tuple[Tuple[str, int, int], ...]]:
        fps: Dict[str, Tuple[Tuple[str, int, int], ...]] = {}
        for i in range(9):
            slot = f"profile_{i}"
            prof = saves_root / slot
            if not valid_profile(prof):
                continue
            entries: List[Tuple[str, int, int]] = []
            try:
                for f in sorted(prof.iterdir(), key=lambda x: x.name):
                    if f.name.endswith(".tmp") or f.name.endswith(".old") or f.name.startswith("."):
                        continue
                    if f.is_file():
                        st = f.stat()
                        entries.append((f.name, st.st_size, st.st_mtime_ns))
            except OSError:
                continue
            fps[slot] = tuple(entries)
        return fps

    def _run_loop(self) -> None:
        while not self._stop_evt.wait(self.poll_interval_sec):
            with self._lock:
                saves_root = self._saves_root
            if saves_root is None or not saves_root.is_dir():
                continue

            now = time.monotonic()
            current = self._scan_fingerprints(saves_root)

            for slot, fp in current.items():
                prev = self._last_fingerprints.get(slot)
                if prev != fp:
                    self._last_fingerprints[slot] = fp
                    self._dirty_since[slot] = now

            # Remove disappeared slots
            for gone in list(self._last_fingerprints.keys()):
                if gone not in current:
                    self._last_fingerprints.pop(gone, None)
                    self._dirty_since.pop(gone, None)

            # Flush slots whose files have settled for debounce_sec
            for slot, changed_at in list(self._dirty_since.items()):
                if now - changed_at < self.debounce_sec:
                    continue
                self._dirty_since.pop(slot, None)
                prof = saves_root / slot
                if not valid_profile(prof):
                    continue
                try:
                    rec = self.store.backup_slot(prof, trigger="auto", note="", force=False)
                    if rec is not None:
                        with self._lock:
                            self._captured_count += 1
                            self._last_capture_at = rec.timestamp_ms
                            self._last_event = f"自动留档 {slot}（{rec.estate_name}）"
                        if self._on_capture:
                            self._on_capture(rec)
                except Exception as exc:
                    with self._lock:
                        self._last_event = f"自动留档等待重试（{slot}）: {exc}"
