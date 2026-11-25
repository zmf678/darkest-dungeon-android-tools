"""Assemble the unified Torch Launcher thin-shell APK (A2-2) or full bundled APK (A1)
against a user-supplied matching Wicked 2.2.2 APK without storing copyrighted binaries in the repo.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
BASE_HASH = "0b84730f228f4308fd408c880ad5fdfcb4f10fc096d97079857bbbc17a619410"

WICKED_LIBS = (
    "lib/arm64-v8a/libc++_shared.so",
    "lib/arm64-v8a/libddnx.so",
    "lib/arm64-v8a/libddport.so",
    "lib/arm64-v8a/libddastc.so",
)

WICKED_HELPER_ASSETS = (
    "assets/pc_fonts.zip",
    "assets/ui_merge_base.zip",
    "assets/base_png.txt",
    "assets/layout_keys.txt",
    "assets/mod_icon.png",
)

# Android APK readers accept ZIP32 offsets through 4 GiB.
zipfile.ZIP64_LIMIT = 0xFFFFFFFF - 1


def run(*command, env=None):
    subprocess.run([str(item) for item in command], check=True, env=env)


def exact_input(apk: Path):
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != BASE_HASH:
        raise SystemExit(f"The base APK does not match the pinned Wicked 2.2.2 build: {digest}")


def patch_wicked_smali(project: Path):
    activity = project / "smali/ru/darkest/port/NxActivity.smali"
    text = activity.read_text(encoding="utf-8")

    begin = text.index(".method protected onCreate(Landroid/os/Bundle;)V")
    end = text.index(".end method", begin)
    on_create = text[begin:end]

    # Hook 1: install GameArchive after System.loadLibrary("ddnx")
    m_load = re.search(
        r"(const-string (\w+), \"ddnx\"\s+invoke-static \{\2\}, Ljava/lang/System;->loadLibrary\(Ljava/lang/String;\)V)",
        on_create,
    )
    if not m_load:
        raise ValueError("Unexpected native-library launch sequence in NxActivity.onCreate")
    on_create = (
        on_create[: m_load.end()]
        + "\n\n    invoke-static {p0}, Ldev/torcharchive/game/GameArchive;->install(Landroid/content/Context;)V"
        + on_create[m_load.end() :]
    )

    # Hook 2: replace applicationInfo.sourceDir passed to NxActivity.nxApk with PakMount.resolvePakPath(p0)
    m_apk = re.search(
        r"invoke-virtual \{p0\}, Lru/darkest/port/NxActivity;->getApplicationInfo\(\)Landroid/content/pm/ApplicationInfo;\s+"
        r"move-result-object (\w+)\s+"
        r"iget-object (\w+), \1, Landroid/content/pm/ApplicationInfo;->sourceDir:Ljava/lang/String;",
        on_create,
    )
    if not m_apk:
        raise ValueError("Could not locate sourceDir -> nxApk sequence in NxActivity.onCreate")
    out_reg = m_apk.group(2)
    replacement = (
        "invoke-static {p0}, Ldev/torcharchive/game/PakMount;->resolvePakPath(Landroid/content/Context;)Ljava/lang/String;\n\n"
        f"    move-result-object {out_reg}"
    )
    on_create = on_create[: m_apk.start()] + replacement + on_create[m_apk.end() :]

    text = text[:begin] + on_create + text[end:]

    # Hook 3: NxActivity.resolution() -> DisplayConfig.resolveResolution(Context)
    res_begin = text.index(".method private resolution()[I")
    res_end = text.index(".end method", res_begin) + len(".end method")
    res_stub = (
        ".method private resolution()[I\n"
        "    .locals 1\n\n"
        "    invoke-static {p0}, Ldev/torcharchive/game/DisplayConfig;->resolveResolution(Landroid/content/Context;)[I\n"
        "    move-result-object v0\n\n"
        "    return-object v0\n"
        ".end method"
    )
    text = text[:res_begin] + res_stub + text[res_end:]
    activity.write_text(text, encoding="utf-8")

    # Hook 4: NxActivity$LayerView.onMeasure(II)V -> DisplayConfig.measure(...)
    layer_view = project / "smali/ru/darkest/port/NxActivity$LayerView.smali"
    lv_text = layer_view.read_text(encoding="utf-8")
    m_begin = lv_text.index(".method protected onMeasure(II)V")
    m_end = lv_text.index(".end method", m_begin) + len(".end method")
    m_stub = (
        ".method protected onMeasure(II)V\n"
        "    .locals 4\n\n"
        "    invoke-virtual {p0}, Lru/darkest/port/NxActivity$LayerView;->getContext()Landroid/content/Context;\n"
        "    move-result-object v0\n\n"
        "    invoke-static {}, Lru/darkest/port/NxActivity;->-$$Nest$sfgetLAYER_W()I\n"
        "    move-result v1\n\n"
        "    invoke-static {}, Lru/darkest/port/NxActivity;->-$$Nest$sfgetLAYER_H()I\n"
        "    move-result v2\n\n"
        "    invoke-static {v0, p1, p2, v1, v2}, Ldev/torcharchive/game/DisplayConfig;->measure(Landroid/content/Context;IIII)[I\n"
        "    move-result-object v3\n\n"
        "    const/4 v0, 0x0\n"
        "    aget v0, v3, v0\n\n"
        "    const/4 v1, 0x1\n"
        "    aget v1, v3, v1\n\n"
        "    invoke-virtual {p0, v0, v1}, Lru/darkest/port/NxActivity$LayerView;->setMeasuredDimension(II)V\n\n"
        "    return-void\n"
        ".end method"
    )
    layer_view.write_text(lv_text[:m_begin] + m_stub + lv_text[m_end:], encoding="utf-8")


def compile_native_commit(ndk: Path, work: Path) -> Path:
    library = work / "libtorcharchive.so"
    clang_candidates = list((ndk / "toolchains/llvm/prebuilt").glob("*/bin/clang*"))
    clang = next((p for p in clang_candidates if p.name in ("clang.exe", "clang")), None)
    if clang is None:
        raise FileNotFoundError(f"Could not locate clang inside NDK: {ndk}")
    commit_c = ROOT / "game-patch/native/commit.c"
    run(
        clang,
        "--target=aarch64-linux-android26",
        "-O2",
        "-shared",
        "-fPIC",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wl,-z,max-page-size=16384",
        "-Wl,-soname,libtorcharchive.so",
        commit_c,
        "-ldl",
        "-o",
        library,
    )
    return library


def write_stored(bundle: zipfile.ZipFile, arcname: str, data: bytes):
    info = zipfile.ZipInfo(arcname)
    info.compress_type = zipfile.ZIP_STORED
    bundle.writestr(info, data)


def copy_entry(source: zipfile.ZipFile, target: zipfile.ZipFile, entry_name: str, compress_type=None):
    src_info = source.getinfo(entry_name)
    dst_info = zipfile.ZipInfo(entry_name)
    dst_info.date_time = src_info.date_time
    dst_info.compress_type = src_info.compress_type if compress_type is None else compress_type
    with source.open(src_info) as in_file, target.open(dst_info, "w") as out_file:
        shutil.copyfileobj(in_file, out_file, 4 * 1024 * 1024)


def run_gradle_shell(args) -> Path:
    gradle = args.gradle
    if gradle is None:
        raise SystemExit("Pass --shell-apk or provide --gradle to build launcher-shell.apk")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.jdk)
    if args.sdk:
        env["ANDROID_HOME"] = str(args.sdk)
    if args.gradle_home:
        env["GRADLE_USER_HOME"] = str(args.gradle_home)
    cmd = [str(gradle), "--offline", ":app:assembleDebug"]
    if args.lab:
        cmd.append("-ParchiveLab=true")
    subprocess.run(cmd, cwd=ROOT, env=env, check=True)
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    if not apk.is_file():
        raise FileNotFoundError(f"Expected Gradle output missing: {apk}")
    return apk


def resolve_native_commit(args, shell_apk: Path, work: Path) -> Path:
    if args.native_lib:
        if not args.native_lib.is_file():
            raise FileNotFoundError(f"Missing --native-lib file: {args.native_lib}")
        return args.native_lib
    if args.ndk:
        return compile_native_commit(args.ndk, work)
    with zipfile.ZipFile(shell_apk) as shell:
        lib_entry = "lib/arm64-v8a/libtorcharchive.so"
        if lib_entry in shell.namelist():
            extracted = work / "libtorcharchive.so"
            extracted.write_bytes(shell.read(lib_entry))
            print("Using prebuilt open-source libtorcharchive.so from --shell-apk (no NDK required).", flush=True)
            return extracted
    raise SystemExit(
        "Need libtorcharchive.so: pass --ndk to compile from source, --native-lib <path>, "
        "or use a Release --shell-apk that bundles lib/arm64-v8a/libtorcharchive.so."
    )


def build(args, work: Path):
    java = args.jdk / ("bin/java.exe" if os.name == "nt" else "bin/java")
    skeleton = work / "wicked-code.apk"
    project = work / "decoded"
    rebuilt_wicked = work / "wicked-patched.apk"

    with zipfile.ZipFile(args.base_apk) as original, zipfile.ZipFile(skeleton, "w") as small:
        for entry in original.infolist():
            if entry.filename in {"AndroidManifest.xml", "resources.arsc", "classes.dex"} or entry.filename.startswith("res/"):
                copy_entry(original, small, entry.filename)

    run(java, "-jar", args.apktool, "d", "--no-debug-info", "-q", "-o", project, skeleton)
    patch_wicked_smali(project)
    run(java, "-jar", args.apktool, "b", "-q", "-o", rebuilt_wicked, project)

    shell_apk = args.shell_apk if args.shell_apk else run_gradle_shell(args)
    native_lib = resolve_native_commit(args, shell_apk, work)

    unsigned = work / "unsigned.apk"
    allow_zip64 = args.bundle_pak is not None
    with zipfile.ZipFile(shell_apk) as shell, \
            zipfile.ZipFile(rebuilt_wicked) as wicked_dex_apk, \
            zipfile.ZipFile(args.base_apk) as base_apk, \
            zipfile.ZipFile(unsigned, "w", allowZip64=allow_zip64) as bundle:

        # 1. Copy compiled launcher shell entries (Manifest, res, resources.arsc, all classes*.dex, display.ttf, etc.)
        max_dex_idx = 1
        for entry in shell.infolist():
            if entry.filename.startswith("META-INF/"):
                continue
            if entry.filename.startswith("lib/"):
                continue
            m_dex = re.fullmatch(r"classes(\d*)\.dex", entry.filename)
            if m_dex:
                idx = int(m_dex.group(1)) if m_dex.group(1) else 1
                max_dex_idx = max(max_dex_idx, idx)
            copy_entry(shell, bundle, entry.filename)

        # 2. Wicked patched classes.dex -> next available classes<N>.dex
        wicked_dex_name = f"classes{max_dex_idx + 1}.dex"
        bundle.writestr(wicked_dex_name, wicked_dex_apk.read("classes.dex"), compress_type=zipfile.ZIP_DEFLATED)

        # 3. Native libraries (all ZIP_STORED for Android M+ extractNativeLibs=false & 16KB page alignment)
        write_stored(bundle, "lib/arm64-v8a/libtorcharchive.so", native_lib.read_bytes())
        for lib_name in WICKED_LIBS:
            copy_entry(base_apk, bundle, lib_name, compress_type=zipfile.ZIP_STORED)

        # 4. Small Wicked helper assets required by built-in Mod converter (~7.9 MB)
        for asset_name in WICKED_HELPER_ASSETS:
            copy_entry(base_apk, bundle, asset_name, compress_type=zipfile.ZIP_STORED)

        # 5. Optional A1 full-bundle mode (--bundle-pak dd_assets.pak)
        if args.bundle_pak is not None:
            print(f"Bundling A1 full assets from {args.bundle_pak} ...", flush=True)
            with zipfile.ZipFile(args.bundle_pak) as pak:
                for entry in pak.infolist():
                    if entry.is_dir():
                        continue
                    if entry.filename.startswith("assets/nx/"):
                        copy_entry(pak, bundle, entry.filename, compress_type=zipfile.ZIP_STORED)

    print("Aligning (16KB) and signing Torch Launcher APK...", flush=True)
    aligned = work / "aligned.apk"
    align = args.build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    run(align, "-P", "16", "-f", "4", unsigned, aligned)

    signer = args.build_tools / "lib/apksigner.jar"
    signed = work / "signed.apk"
    key_args = ("--ks", args.keystore, "--ks-key-alias", args.key_alias, "--ks-pass", args.key_password)
    run(java, "-jar", signer, "sign", *key_args, "--out", signed, aligned)
    run(java, "-jar", signer, "verify", "--print-certs", signed)
    run(align, "-c", "-P", "16", "4", signed)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        args.output.unlink()
    shutil.move(signed, args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_apk", type=Path, help="Local Wicked 2.2.2 APK (hash-verified, never committed)")
    parser.add_argument("output", type=Path, help="Output TorchLauncher APK path")
    parser.add_argument("--shell-apk", type=Path, help="Prebuilt launcher-shell APK from :app:assembleDebug")
    parser.add_argument("--gradle", type=Path, help="Path to gradle.bat / gradle if --shell-apk is omitted")
    parser.add_argument("--gradle-home", type=Path, help="Optional GRADLE_USER_HOME")
    parser.add_argument("--lab", action="store_true", help="Build .lab package variant")
    parser.add_argument("--bundle-pak", type=Path, help="Optional dd_assets.pak to produce A1 full standalone APK")
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, help="Optional Android NDK path (omit if --shell-apk bundles libtorcharchive.so)")
    parser.add_argument("--native-lib", type=Path, help="Optional prebuilt open-source libtorcharchive.so path")
    parser.add_argument("--apktool", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--key-password", required=True)
    args = parser.parse_args()

    exact_input(args.base_apk)
    if args.bundle_pak and not args.bundle_pak.is_file():
        raise SystemExit(f"Missing --bundle-pak file: {args.bundle_pak}")
    with tempfile.TemporaryDirectory(prefix="torch-launcher-") as directory:
        build(args, Path(directory))
    size_mb = args.output.stat().st_size / (1024 * 1024)
    print(f"Built unified launcher APK: {args.output} ({size_mb:.2f} MB)")


if __name__ == "__main__":
    main()
