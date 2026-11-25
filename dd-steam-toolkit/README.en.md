# dd-steam-toolkit — Torch Archive PC Workshop (Standalone Steam Save Manager & Complete Modifier)

[简体中文](README.md) | **English**

> [!IMPORTANT]
> **Disclaimer & Legitimate Ownership Notice**: This tool is provided strictly for personal learning and single-player convenience. You **must legally purchase and own a genuine Steam copy of *Darkest Dungeon*** before using it. This is an independent community open-source tool not affiliated with Red Hook Studios or Valve/Steam; commercial use or unauthorized redistribution is prohibited. See the root [README.en.md](../README.en.md) for the full notice.

> Navigation: [Project Overview (EN)](../README.en.md) · [项目总览 (中文)](../README.md) · [Workspace Index](../INDEX.md) · [Modifier Guide](../docs/MODIFIER-GUIDE.md)

Extracted from the Android **Torch Launcher** app and tailored specifically for **PC Steam *Darkest Dungeon***, `dd-steam-toolkit` delivers live automatic save snapshots, atomic transactional rollbacks, a full lossless binary DSON save editor, a non-destructive game rules mod generator, and a Steam Workshop/local mod load-order manager in a single standalone tool.

**Zero third-party Python dependencies** (Python 3.10+ standard library only). No Gradle, Android SDK, Pillow, or external GUI libraries required—just run `python dd-steam-toolkit/run.py` (or double-click `启动余烬PC工坊.bat` on Windows) to open a native standalone Edge/Chrome desktop app window.

---

## Key Features

### 1. Save Vault & Real-Time Auto-Snapshots
- **Live Save Watcher**: Background thread polls Steam `userdata/<UID>/262060/remote/profile_0..8` every 1.5s (with 1.5s debounce, ignoring `.tmp`/`.old` staging files) and automatically captures **SHA-256 deduplicated** deterministic ZIP snapshots whenever the game saves (entering rooms, finishing battles, returning to Hamlet, or exiting).
- **Transactional Atomic Rollback & Crash Recovery**: Verifies the snapshot ZIP's SHA-256 digest, stages and validates `persist.game.json`, **forces a backup of your current active slot first**, and atomically swaps directories—automatically recovering from `.torcharchive-restore` if interrupted.
- **Diffs, Notes & Cross-Platform Import/Export**: Inspect file-level diffs against previous snapshots, add notes, prune old automatic snapshots (preserving manual/annotated ones), export standard ZIPs, and import PC backups or **Android Torch Launcher save ZIPs** (with optional auto-remapping of subscribed Workshop IDs back to `source: Steam`).

### 2. Full Lossless Binary DSON Save Editor
Every write enforces **`Darkest.exe` process check + mandatory pre-edit snapshot + DSON round-trip verification + atomic file replace**:
- **Estate & Currencies**: Estate name, difficulty mode (`radiant` / `darkest` / `new_game_plus`), all 7 currencies (`gold`, `bust`, `portrait`, `deed`, `crest`, `shard`, `blueprint`), Endless Harvest wave highscore, plus `+100k Gold`, `+100 Heirlooms`, and `Max All` shortcuts.
- **Hero Roster & Detail Modals**:
  - Batch `Heal / Purify / Cure / Revive All` and `Max All Heroes (Lv.6 + Gear 5)`;
  - Real-time search & status filters (`All` / `Needs Recovery` / `Diseased` / `Dead`), supporting both normalized (`<= 1.0`) and absolute Steam town HP values;
  - Hero Attribute modal: name, HP, stress, `Lv.0–6` + custom XP, weapon/armour ranks `1–5`, Virtue/Affliction selectors, revive & clear Death's Door/heart attack flags;
  - Quirks & Diseases modal: one-click cure all diseases, lock all non-disease quirks, toggle lock/remove per entry, curated positive quirks + dynamically indexed quirks/diseases from your Steam base & DLC `quirk_library.json`, plus custom/mod quirk IDs.
- **Trinket Inventory & Categorized Codex**: Search owned trinkets (`+1` duplicate / remove), browse categorized codex merged with all base & owned DLC `*.entries.trinkets.json`, batch add `×1 / ×2 / ×4`, or insert any custom/mod trinket ID.
- **Town Buildings & Dungeon Progression**: Inspect upgrade progress across all 8 Hamlet buildings and unlock all 20 upgrade trees via Red Hook's `dd_hash`; inspect/edit XP & levels for all 6 dungeon regions (`Max All 150 XP`).
- **Raw Binary DSON Explorer**: Switch between all `persist.*.json` files, navigate with clickable breadcrumbs **including penetrating embedded hero DSONs (`raw_data`)**, filter keys, edit `int32 / float32 / bool / string` scalars safely, or export a full decoded JSON dump.

### 3. Non-Destructive Game Rules Modifier (`torch_content_modifier`)
Generates a standard *Darkest Dungeon* override mod inside `<Steam>/DarkestDungeon/mods/torch_content_modifier/` without modifying any original game files:
- **4 One-Click Presets**: `Vanilla`, `Quality of Life`, `Fast Progression`, `Overpowered`.
- **5 Rule Groups**:
  1. Inventory stack multipliers (`1x–99x`), hero trinket slots (`2–4`), raid backpack slots (`16–24`), free provisions/supplies;
  2. Combat animation speed (`1.0x–4.0x`), player crit multiplier (`1.5x–3.0x`), remove 95% hit cap (`100%`), guaranteed zero-stress retreat;
  3. Virtue chance (`25%–100%`), incoming stress scale (`100%–0%`), weekly idle town stress recovery (`5–100`);
  4. Stage Coach recruit multiplier (`1x–3x`), roster capacity bonus (`+0–+100`), fast resolve XP thresholds;
  5. **PC Steam-Exclusive Deep Overrides**: dynamically scales base & owned DLC hero `.info.darkest` stats (HP multiplier, DMG multiplier, SPD bonus, DODGE bonus—automatically skipping monster files) and discounts `upgrades/building/*.upgrades.json` costs (`0%–100%`).
- **Safe Steam Slot Mounting**: Automatically mounts `torch_content_modifier` (`source: mod`) at top priority in the chosen save slot while **strictly preserving `source: Steam`** on all existing Workshop mods; uninstall cleanly in one click or export as a portable `.zip`.

### 4. Steam Workshop & Local Mod Load Order Manager
- Automatically detects subscribed Workshop mods (`steamapps/workshop/content/262060/`) and local `mods/` folders with titles and versions from `project.xml`.
- Toggle and reorder `applied_ugcs_1_0` load priority per save slot while preserving `Steam` vs `mod` sources.

---

## Quickstart

### Prerequisites
- **Python 3.10+** (standard library only—no `pip install` needed).
- A genuine PC Steam installation of *Darkest Dungeon* (custom/GOG/Epic save directories are also supported via UI or CLI flags).

### Option 1: Desktop App Window (Recommended)
- **Windows**: Double-click [启动余烬PC工坊.bat](启动余烬PC工坊.bat), or run:
  ```powershell
  python dd-steam-toolkit\run.py
  ```
- Starts a local `127.0.0.1` server secured with a random per-session anti-CSRF token and opens a standalone Edge/Chrome `--app` window (falling back to your default browser).
- Useful flags:
  ```powershell
  python dd-steam-toolkit\run.py ui --browser       # Open in a normal browser tab
  python dd-steam-toolkit\run.py ui --port 18620    # Bind a fixed local port
  python dd-steam-toolkit\run.py ui --no-watch      # Start without auto-watcher
  ```

### Option 2: Command-Line Interface (CLI)
```powershell
# Detect Steam installation, DLCs, Workshop mods, and save slots
python dd-steam-toolkit\run.py detect

# Run background live auto-snapshot watcher
python dd-steam-toolkit\run.py watch

# Manual snapshot, list, atomic restore, prune
python dd-steam-toolkit\run.py snapshot backup --slot profile_0 --note "Before boss"
python dd-steam-toolkit\run.py snapshot list --slot profile_0
python dd-steam-toolkit\run.py snapshot restore --id <SNAPSHOT_ID>
python dd-steam-toolkit\run.py snapshot prune --keep 30

# Binary DSON save editor commands (auto-snapshots before every write)
python dd-steam-toolkit\run.py edit-save profile_0 info
python dd-steam-toolkit\run.py edit-save profile_0 max-resources
python dd-steam-toolkit\run.py edit-save profile_0 max-heroes
python dd-steam-toolkit\run.py edit-save profile_0 unlock-buildings
python dd-steam-toolkit\run.py edit-save profile_0 max-dungeons
python dd-steam-toolkit\run.py edit-save profile_0 add-trinket --trinket-id ancestors_map --amount 2

# Install rules modifier preset to Steam mods/ and enable on profile_0
python dd-steam-toolkit\run.py build-mod --preset adventurer --install-steam --enable-slot profile_0
python dd-steam-toolkit\run.py build-mod --uninstall

# Manage Steam slot mod load order
python dd-steam-toolkit\run.py mods list --slot profile_0
```

### Option 3: Optional Single-File `.exe` Build (Windows)
With PyInstaller installed (`pip install pyinstaller`):
```powershell
python dd-steam-toolkit\build_exe.py
```
Outputs a self-contained `dd-steam-toolkit\dist\TorchArchivePC.exe`.

---

## Steam Cloud & Safety Tips

1. **Snapshot Location**: Stored outside your game directory at `%LOCALAPPDATA%\TorchArchivePC\snapshots\` (or `~/.local/share/TorchArchivePC/snapshots/` on Linux/macOS; override with `TORCH_PC_HOME`).
2. **Process Guard**: Save writes, rollbacks, and mod changes are blocked by default while `Darkest.exe` is running to avoid memory overwrites. Live auto-snapshotting is read-only and safe while playing.
3. **Steam Cloud**: Edit or roll back saves while the game is closed, then launch *Darkest Dungeon* via Steam. If Steam prompts about a Cloud conflict, choose **Upload Local Files**.

---

## Running Tests

```powershell
python -m unittest discover -s dd-steam-toolkit\tests -v
```
