"""Build the local save-history add-on against the matching Wicked 2.2.2 APK."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

SOURCE = Path(__file__).resolve().parent
BASE_HASH = "0b84730f228f4308fd408c880ad5fdfcb4f10fc096d97079857bbbc17a619410"
PACKAGE = "dev.torcharchive.ddgame"
PERMISSION = PACKAGE + ".permission.ARCHIVE"
OPTIONS_OVERLAY = "assets/nx/overlay/shared/options/options.value_definitions.json"
TABLET_RESOLUTIONS = [
    [1280, 720],
    [1920, 1080],
    [2560, 1440],
    [3008, 1692],
    [2560, 1600],
    [3008, 1880],
]
ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])
# Android APK readers accept ZIP32 offsets through 4 GiB. Python defaults to
# the older 2 GiB threshold, below the size of this input.
zipfile.ZIP64_LIMIT = 0xFFFFFFFF - 1


def run(*command):
    subprocess.run([str(item) for item in command], check=True)


def exact_input(apk):
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != BASE_HASH:
        raise SystemExit(f"The input APK does not match the pinned Wicked 2.2.2 build: {digest}")


def patch_manifest(project):
    manifest = project / "AndroidManifest.xml"
    tree = ET.parse(manifest)
    root = tree.getroot()
    if root.get("package") != "com.darkestdungeon":
        raise ValueError("Unexpected input package")
    root.set("package", PACKAGE)
    declaration = ET.Element("permission", {
        ANDROID + "name": PERMISSION,
        ANDROID + "protectionLevel": "signature",
    })
    root.insert(1, declaration)
    application = root.find("application")
    label = "暗黑地牢 · 存档实验版"
    application.set(ANDROID + "label", label)
    for activity in application.findall("activity"):
        if activity.get(ANDROID + "name") in {"ru.darkest.port.MainActivity", "ru.darkest.port.NxActivity"}:
            activity.set(ANDROID + "label", label)
    ET.SubElement(application, "provider", {
        ANDROID + "name": "dev.torcharchive.game.ArchiveProvider",
        ANDROID + "authorities": PACKAGE + ".archive",
        ANDROID + "permission": PERMISSION,
        ANDROID + "exported": "true",
        ANDROID + "process": ":nx",
    })
    tree.write(manifest, encoding="utf-8", xml_declaration=True)
    config = project / "apktool.yml"
    yaml = config.read_text(encoding="utf-8")
    yaml, count = re.subn(r"(?m)^([ \t]*versionName:)[^\r\n]+$", r"\1 2.2.2-archive.2", yaml)
    if count != 1:
        raise ValueError("Unexpected input version metadata")
    config.write_text(yaml, encoding="utf-8")


def patch_activity(project):
    activity = project / "smali/ru/darkest/port/NxActivity.smali"
    text = activity.read_text(encoding="utf-8")
    begin = text.index(".method protected onCreate(Landroid/os/Bundle;)V")
    end = text.index(".end method", begin)
    on_create = text[begin:end]
    load = "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"
    matches = re.findall(r"const-string (\w+), \"ddnx\"\n\s*\n\s*invoke-static \{\1\}, Ljava/lang/System;->loadLibrary\(Ljava/lang/String;\)V", on_create)
    if len(matches) != 1:
        raise ValueError("Unexpected native-library launch sequence")
    load = load.replace("v0", matches[0])
    on_create = on_create.replace(load, load + "\n\n    invoke-static {p0}, Ldev/torcharchive/game/GameArchive;->install(Landroid/content/Context;)V", 1)
    text = text[:begin] + on_create + text[end:]

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


def patch_options_overlay(raw_bytes):
    doc = json.loads(raw_bytes.decode("utf-8"))
    patched = False
    for entry in doc.get("value_definitions", []):
        if entry.get("id") == "resolution":
            entry.setdefault("data", {})["range"] = TABLET_RESOLUTIONS
            patched = True
    if not patched:
        raise ValueError("Missing resolution definition in options.value_definitions.json")
    compact = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    target_len = len(raw_bytes)
    if len(compact) > target_len:
        raise ValueError(f"Patched options JSON ({len(compact)}B) exceeds filelist.csv length ({target_len}B)")
    return compact + b" " * (target_len - len(compact))


def copy_entry(source, target, entry):
    entry = source.getinfo(entry) if isinstance(entry, str) else entry
    with source.open(entry) as in_file, target.open(entry, "w") as out_file:
        shutil.copyfileobj(in_file, out_file, 4 * 1024 * 1024)


def compile_addon(args, work):
    classes = work / "classes"
    classes.mkdir()
    compiler = args.jdk / "bin/javac.exe"
    api = args.sdk / "platforms/android-36/android.jar"
    run(compiler, "-encoding", "UTF-8", "--release", "11", "-Xlint:all", "-cp", api, "-d", classes, *sorted((SOURCE / "src").rglob("*.java")))
    dex = work / "addon.zip"
    java = args.jdk / "bin/java.exe"
    d8 = args.build_tools / "lib/d8.jar"
    run(java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "26", "--lib", api,
        "--output", dex, *sorted(classes.rglob("*.class")))
    library = work / "libtorcharchive.so"
    clang = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    run(clang, "--target=aarch64-linux-android26", "-O2", "-shared", "-fPIC",
        "-Wall", "-Wextra", "-Werror", "-Wl,-z,max-page-size=16384",
        "-Wl,-soname,libtorcharchive.so", SOURCE / "native/commit.c", "-ldl", "-o", library)
    return dex, library


def build(args, work):
    java = args.jdk / "bin/java.exe"
    skeleton = work / "input-code.apk"
    project = work / "decoded"
    rebuilt = work / "patched-code.apk"
    with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(skeleton, "w") as small:
        for entry in original.infolist():
            if entry.filename in {"AndroidManifest.xml", "resources.arsc", "classes.dex"} or entry.filename.startswith("res/"):
                copy_entry(original, small, entry)

    run(java, "-jar", args.apktool, "d", "--no-debug-info", "-o", project, skeleton)
    patch_manifest(project)
    patch_activity(project)
    run(java, "-jar", args.apktool, "b", "-o", rebuilt, project)
    dex, library = compile_addon(args, work)

    unsigned = work / "unsigned.apk"
    with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(rebuilt) as patched, zipfile.ZipFile(dex) as addon, \
            zipfile.ZipFile(unsigned, "w", allowZip64=False) as bundle:
        for entry in patched.infolist():
            if entry.filename in {"AndroidManifest.xml", "resources.arsc", "classes.dex"} or entry.filename.startswith("res/"):
                copy_entry(patched, bundle, entry)
        bundle.writestr("classes2.dex", addon.read("classes.dex"), compress_type=zipfile.ZIP_DEFLATED)
        bundle.write(library, "lib/arm64-v8a/libtorcharchive.so", compress_type=zipfile.ZIP_STORED)
        for entry in original.infolist():
            if entry.filename == OPTIONS_OVERLAY:
                patched_json = patch_options_overlay(original.read(entry))
                bundle.writestr(OPTIONS_OVERLAY, patched_json, compress_type=zipfile.ZIP_STORED)
            elif entry.filename.startswith(("assets/", "lib/")):
                copy_entry(original, bundle, entry)

    print("Aligning and signing local APK", flush=True)
    aligned = work / "aligned.apk"
    align = args.build_tools / "zipalign.exe"
    run(align, "-P", "16", "-f", "4", unsigned, aligned)
    signer = args.build_tools / "lib/apksigner.jar"
    signed = work / "signed.apk"
    key_args = ("--ks", args.keystore, "--ks-key-alias", args.key_alias, "--ks-pass", args.key_password)
    run(java, "-jar", signer, "sign", *key_args, "--out", signed, aligned)
    run(java, "-jar", signer, "verify", "--print-certs", signed)
    run(align, "-c", "-P", "16", "4", signed)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise FileExistsError(args.output)
    shutil.move(signed, args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    for name in ("jdk", "sdk", "build-tools", "ndk", "apktool", "keystore"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--key-alias", required=True)
    parser.add_argument("--key-password", required=True, help="apksigner env: or file: password reference")
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit(f"Output already exists: {args.output}")
    exact_input(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dd-archive-") as directory:
        build(args, Path(directory))
    print(args.output)


if __name__ == "__main__":
    main()
