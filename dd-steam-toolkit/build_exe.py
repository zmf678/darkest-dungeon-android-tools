#!/usr/bin/env python3
"""Optional helper to package dd-steam-toolkit into a standalone Windows .exe via PyInstaller."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main() -> int:
    web_ui = ROOT / "torch_pc" / "web_ui"
    sep = ";" if sys.platform.startswith("win") else ":"
    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--onefile",
        "--name",
        "TorchArchivePC",
        "--add-data",
        f"{web_ui}{sep}torch_pc/web_ui",
        str(ROOT / "run.py"),
    ]
    print("执行打包命令:", " ".join(cmd))
    return subprocess.call(cmd, cwd=str(ROOT))


if __name__ == "__main__":
    raise SystemExit(main())
