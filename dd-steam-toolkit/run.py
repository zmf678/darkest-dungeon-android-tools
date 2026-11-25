#!/usr/bin/env python3
"""Entrypoint for Darkest Dungeon PC Steam Torch Archive & Complete Modifier."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from torch_pc.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
