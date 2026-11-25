# Darkest Dungeon Android Tools — Torch Launcher & Steam Companion Toolchain (v2.3)

[简体中文](README.md) | **English**

> [!IMPORTANT]
> **Disclaimer & Legitimate Ownership Notice (Read Before Use)**
>
> 1. **Educational & Research Purpose Only**: This repository—including all source code, scripts, and documentation—is provided strictly for personal learning, technical research, and cross-platform development study, with no warranty or commercial support of any kind.
> 2. **Legitimate Steam Copy Required**: To use this toolchain, you **must legally purchase and own a genuine Steam copy of *Darkest Dungeon* (and any corresponding DLCs)** and point the converter to your own local Steam installation for asset conversion, save management, and Workshop mod migration.
> 3. **Intellectual Property**: This is an independent community companion tool and is **not affiliated with, endorsed by, or sponsored by Red Hook Studios, Valve/Steam, or any game publisher**. All game titles, trademarks, artwork, audio, scripts, and data remain the sole property of their respective copyright holders.
> 4. **No Commercial Use or Redistribution**: Commercial use, paid bundling, DRM circumvention, or unauthorized redistribution of copyrighted game files or converted asset packs is strictly prohibited. Users bear sole responsibility for complying with applicable local laws and software EULAs.
> 5. **Copyright Inquiries**: If any rights holder has concerns regarding content in this repository, please open an issue or contact the maintainer for prompt review and removal.

> **Documentation**: [简体中文 README](README.md) · [Workspace Index](INDEX.md) · [Standalone PC Steam Toolkit (`dd-steam-toolkit/`)](dd-steam-toolkit/README.en.md) · [Architecture Overview](docs/ARCHITECTURE.md) · [Modifier Guide](docs/MODIFIER-GUIDE.md) · [Build & Test Guide](docs/BUILD-AND-TEST.md) · [Android Launcher (`app/`)](app/README.md) · [PC Converter (`dd-steam-converter/`)](dd-steam-converter/README.md)

---

## Prerequisites & Quickstart

### What You Need
1. **A legitimately purchased local Steam installation of *Darkest Dungeon*** (used to convert base game & owned DLC assets, migrate personal saves, and package subscribed Workshop mods).
2. **PC Python 3.10+ Environment**:
   - Install dependencies: `pip install -r dd-steam-converter\requirements.txt`
   - *(Strongly recommended)* Download `astcenc-avx2.exe` (or `astcenc`) from [ARM astc-encoder Releases](https://github.com/ARM-software/astc-encoder/releases) and place it in `dd-steam-converter\bin\` or your system `PATH` for near-lossless 51–55 dB PSNR texture quality.
   - *(Optional)* Install `ffmpeg` in your `PATH` to transcode `.ogv` cutscenes for mobile playback.
3. ***(Optional)* Android Build Toolchain**: Only required if you want to modify and recompile the Android launcher source under `app/` (JDK 21, Android SDK 36, Gradle).

### Quick Workflow
1. **Detect & convert your local Steam assets**:
   ```powershell
   python dd-steam-converter\run.py detect
   python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"
   python dd-steam-converter\run.py verify "dist\dd_assets.pak"
   ```
2. ***(Optional)* Package your Steam saves & Workshop mods**:
   ```powershell
   python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"
   ```
3. **Deploy to your Android phone or tablet**: Push `dd_assets.pak` via ADB (`run.py push`) or copy it to `Android/media/dev.torcharchive.ddgame/dd_assets.pak` (or import directly inside the launcher), then open **Torch Launcher** to play.

---

## Core Features

### 1. Unified Android Companion & Launcher (`app/`)
- **Single-APK Same-UID Architecture**: Combines launcher management, save history snapshots, mod manager, display tuning, and save/rule editors into a single Android entry point (`dev.torcharchive.ddgame`), eliminating cross-app IPC restrictions. All save writes and rollbacks safely stop the game process and capture an automatic backup snapshot first.
- **External Asset Pack Direct Mount (A2-2)**: Streams converted Steam assets directly from `Android/media/dev.torcharchive.ddgame/dd_assets.pak` (`ZIP_STORED`) without unpacking gigabytes of files—updating the launcher APK never requires re-transferring your asset pack.
- **Universal Display & 60 / 90 / 120 Hz High-Refresh Adaptation (`DisplayConfig`)**:
  - Automatically detects physical screen resolution and supported display refresh modes (up to 165 Hz) across phones, tablets, and foldables, generating 4–6 tailored resolution presets;
  - Supports **Aspect-Fit (Crisp)** vs **Full-Screen Fill**, display cutout avoidance, and **60 Hz / 90 Hz / 120 Hz / Auto Max** refresh-rate selection;
  - Defaults ultrawide phones (`> 1.85:1`) to undistorted native-height 16:9 while keeping native full-screen framing on 16:10 tablets.
- **Automatic Save Snapshots & Atomic Rollback (`SnapshotStore`)**: Captures versioned save snapshots automatically on save commits and lets you atomically roll back to any prior point in one tap.
- **Online Mod Workshop**: Browse the Darkest Dungeon Steam Workshop from the Android Mods tab, sort by trending, top rated, or most recent, and search by name, description, or Workshop link. The launcher can download publicly available ZIPs into the local library, including author-provided HTTPS links in the Public ZIP section. Inspect the archive file list before installing.
- **Launcher Updates**: The launcher checks GitHub for stable Android releases on startup and caches successful checks for 24 hours. The Launch tab offers a manual check, release notes, and an APK download link for Android’s normal update flow.

For subscription-only items, subscribe in the desktop Steam client and ZIP the entire `steamapps/workshop/content/262060/<item ID>` folder. Import the ZIP from Local Mods, or use a Steam migration bundle to transfer saves as well. Enable installed mods in the desired save slot.

### 2. PC Steam Asset Converter & Migration Suite (`dd-steam-converter/`)
- **Automatic Steam Discovery**: Scans Windows Registry and `libraryfolders.vdf` to detect the base *Darkest Dungeon* install, owned DLCs, Workshop mods (`262060`), and `userdata` save slots.
- **Dual-Engine ASTC 4×4 Texture Pipeline**: Automatically uses ARM `astcenc` when available (51–55 dB PSNR) and falls back cleanly to a vectorized `Pillow + NumPy` ASTC 4×4 encoder (41–44 dB PSNR), preserving raw colour-grade/LUT PNGs and rebuilding verified manifest files.
- **One-Click Save & Workshop Migration**: Includes a lossless binary DSON codec that remaps Workshop mod references (`source: Steam → mod`) while preserving load order, accessible via both CLI and an Ember-themed desktop GUI.

### 3. Complete Save Editor & Non-Destructive Rules Modifier (Mobile + PC)
- **Lossless Binary DSON Save Editor (`SaveEditor` / `save_editor.py`)**:
  - **Estate & Currencies**: Slot KPI overview card, one-tap difficulty switcher, compact 2-column currency grid, and quick boost presets (`+100k Gold`, `+100 Heirlooms`, `Max All`);
  - **Roster & Full-Screen Gothic Secondary Pages (Hero Dossier & Quirks/Diseases)**: Visual HP & Stress dual progress bars, class/level/status pills, search & status filters (`Needs Recovery` / `Diseased` / `Dead`); dedicated dark secondary pages with direct `Lv.0–6` & gear rank selectors, 3-column Virtue/Affliction grids, searchable 22-entry positive quirk codex, one-tap disease cure, and native Android 13–17 predictive back navigation;
  - **Trinket Inventory & Categorized Codex Secondary Page**: Searchable owned inventory with `+1` quick duplicate; categorized codex filtering across 11 rarity/faction groups with live owned counts and `×1 / ×2 / ×4` batch add;
  - **Town Buildings & Dungeon Regions**: One-tap full unlock for all 8 town facilities, 2-column region level readout, and one-tap max region XP;
  - **Raw DSON Tree Browser & Node Editor**: File selector pills, clickable breadcrumb bar, key search, and validated `int32 / float32 / bool / string` editing.
- **Non-Destructive Game Rules Modifier (`ContentModGenerator` / `content_modifier.py`)**:
  - 4 one-tap presets (`Vanilla` / `Quality of Life` / `Fast Progression` / `Overpowered`) and 4 grouped rule cards (Inventory & Supplies, Combat & Speed, Stress & Virtue, Town & Recruitment) generating a standalone removable override mod (`torch_content_modifier`) compatible with both Steam PC and Android.

### 4. Standalone PC Steam Save Manager & Complete Modifier (`dd-steam-toolkit/`)
> If you **only play *Darkest Dungeon* on PC Steam** and do not need Android conversion, you can use [dd-steam-toolkit/](dd-steam-toolkit/README.en.md) ([中文说明](dd-steam-toolkit/README.md)) completely standalone.

- **Zero Third-Party Dependencies**: Built purely on the Python 3.10+ standard library—no `pip install` needed. Launch via `python dd-steam-toolkit\run.py` (or double-click `dd-steam-toolkit\启动余烬PC工坊.bat` on Windows) to open a native Chromium/Edge standalone desktop app window, with full CLI support and an optional single-file `.exe` packager (`build_exe.py`).
- **Live Auto-Snapshots & Atomic Rollback**: Background watcher monitors `userdata/<UID>/262060/remote/profile_0..8` and captures SHA-256 deduplicated ZIP snapshots automatically whenever Steam saves. Supports snapshot diffs, notes, crash-safe transactional rollbacks (always backing up your active save first), and bidirectional ZIP import/export between PC Steam and Android Torch Launcher (auto-restoring `source: Steam` for subscribed Workshop IDs).
- **Steam-Native Complete Editor & Load Order Manager**:
  - Detects running `Darkest.exe` before writes to prevent in-memory overwrites or Steam Cloud conflicts;
  - Includes all 5 editor tabs (Estate & Currencies, Heroes & Quirks/Diseases with absolute/percentage HP support, Categorized Trinket Codex dynamically indexing base + owned DLC trinkets & quirks, Town Buildings & Dungeon Regions, and Raw DSON Tree Browser penetrating embedded hero DSONs);
  - Non-destructive rules mod (`torch_content_modifier`) scales both base and owned DLC hero stats plus `upgrades/building/` discounts, and strictly preserves `source: Steam` for existing Workshop subscriptions when mounting or reordering `applied_ugcs_1_0`.

---

## Repository Layout

```text
├── README.md                 # Documentation (Simplified Chinese)
├── README.en.md              # Documentation (English)
├── INDEX.md                  # Full workspace index & source map
├── docs/                     # Architecture, Modifier Guide, Build & Test docs
├── app/                      # Unified Android Launcher & In-App Modifier source
├── dd-steam-converter/       # PC Steam converter, migration, ADB push, CLI & GUI modifier
├── dd-steam-toolkit/         # Standalone PC Steam save manager, live auto-snapshots & Ember desktop modifier (zero 3rd-party deps)
├── game-patch/               # Java host verification suite & historical v1 reference
├── DESIGN.md                 # Visual & interaction design specification
└── PRODUCT.md                # Product positioning summary
```

---

## Common Commands

### PC Conversion, Migration & Desktop Modifier

```powershell
# Install Python dependencies
pip install -r dd-steam-converter\requirements.txt

# Auto-detect local Steam install, DLCs, Workshop mods, and save profiles
python dd-steam-converter\run.py detect

# Convert local genuine Steam assets into a single dd_assets.pak
python dd-steam-converter\run.py convert --output "dist\dd_assets.pak"

# Verify dd_assets.pak integrity
python dd-steam-converter\run.py verify "dist\dd_assets.pak"

# Package Steam saves (auto-mapping Workshop mods) and subscribed mods
python dd-steam-converter\run.py migrate --output "dist\steam-migration.zip"

# Push asset pack to an Android device via ADB
python dd-steam-converter\run.py push --pak "dist\dd_assets.pak"

# Launch the Ember-themed desktop GUI
python dd-steam-converter\run.py gui
```

### Standalone PC Steam Save Manager & Modifier (Zero 3rd-Party Dependencies)

```powershell
# Launch the Ember PC desktop window (or double-click dd-steam-toolkit\启动余烬PC工坊.bat on Windows)
python dd-steam-toolkit\run.py

# Run background live auto-snapshot watcher in CLI mode
python dd-steam-toolkit\run.py watch

# Inspect Steam saves, create a manual snapshot, or install a balanced rules preset
python dd-steam-toolkit\run.py detect
python dd-steam-toolkit\run.py snapshot backup --slot profile_0 --note "Before Darkest Dungeon"
python dd-steam-toolkit\run.py build-mod --preset adventurer --install-steam --enable-slot profile_0
```

### Android Build & Automated Tests

```powershell
# Compile Android launcher APK and run Android Lint (JDK 21 + Android SDK 36)
gradle --offline :app:assembleDebug :app:lintDebug

# Run Python unit tests (Steam converter + standalone PC Steam toolkit)
python -m unittest discover -s dd-steam-converter\tests -v
python -m unittest discover -s dd-steam-toolkit\tests -v
```
