"""A1 standalone single-APK bundler: combines thin-shell TorchLauncher APK + dd_assets.pak, zipaligns & signs."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

zipfile.ZIP64_LIMIT = 0xFFFFFFFF - 1


def bundle_standalone_apk(
    shell_apk: Path,
    pak_path: Path,
    output_apk: Path,
    build_tools: Path,
    java_exe: Path,
    keystore: Path,
    key_alias: str,
    key_password: str,
) -> Path:
    if not shell_apk.is_file():
        raise FileNotFoundError(f"Shell APK not found: {shell_apk}")
    if not pak_path.is_file():
        raise FileNotFoundError(f"PAK not found: {pak_path}")

    with tempfile.TemporaryDirectory(prefix="dd-a1-apk-") as td:
        work = Path(td)
        unsigned = work / "unsigned.apk"
        with zipfile.ZipFile(shell_apk, "r") as sh, zipfile.ZipFile(pak_path, "r") as pk, zipfile.ZipFile(
            unsigned, "w", allowZip64=True
        ) as out:
            for info in sh.infolist():
                if info.filename.startswith("META-INF/") or info.filename.startswith("assets/nx/"):
                    continue
                dst = zipfile.ZipInfo(info.filename, info.date_time)
                dst.compress_type = info.compress_type
                with sh.open(info) as r, out.open(dst, "w") as w:
                    shutil.copyfileobj(r, w, 4 * 1024 * 1024)

            for info in pk.infolist():
                if info.is_dir() or not info.filename.startswith("assets/nx/"):
                    continue
                dst = zipfile.ZipInfo(info.filename, info.date_time)
                dst.compress_type = zipfile.ZIP_STORED
                with pk.open(info) as r, out.open(dst, "w") as w:
                    shutil.copyfileobj(r, w, 4 * 1024 * 1024)

        aligned = work / "aligned.apk"
        align = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
        subprocess.run([str(align), "-P", "16", "-f", "4", str(unsigned), str(aligned)], check=True)

        signed = work / "signed.apk"
        signer = build_tools / "lib/apksigner.jar"
        subprocess.run(
            [
                str(java_exe),
                "-jar",
                str(signer),
                "sign",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                key_alias,
                "--ks-pass",
                key_password,
                "--out",
                str(signed),
                str(aligned),
            ],
            check=True,
        )
        subprocess.run([str(java_exe), "-jar", str(signer), "verify", "--print-certs", str(signed)], check=True)
        subprocess.run([str(align), "-c", "-P", "16", "4", str(signed)], check=True)

        output_apk.parent.mkdir(parents=True, exist_ok=True)
        if output_apk.exists():
            output_apk.unlink()
        shutil.move(signed, output_apk)
        return output_apk
