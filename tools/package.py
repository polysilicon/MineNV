#!/usr/bin/env python3
"""Build every part and pack a release into dist/:

  OverworldSupplyLine-<v>.zip            -> Melty component "main"
      Data/NVSE/Plugins/OverworldSupplyLine.dll, osl.ini     (into New Vegas's Data)
      OSL/osl-launch.exe, OSL/THIRD-PARTY-NOTICES.md          (into Melty's own folder for the mashup)
  OverworldSupplyLine-Minecraft-<v>.zip  -> Melty component "minecraft" ({localappdata}/OverworldSupplyLine)
      Prism/ (portable Prism Launcher) with the instance "OverworldSupplyLine": Minecraft 26.3, Fabric Loader,
      Fabric API and the mashup's mod. Prism downloads Minecraft and Java itself after the player signs in.

Downloads are pinned by hash. Usage: python3 tools/package.py [--skip-build]
Needs: Python 3, JDK 25 (JAVA_HOME), Go, clang-cl + lld-link + xwin SDK in /opt/xwin (see newvegas/build.sh).
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
CACHE = os.path.join(ROOT, "dist", "cache")

PRISM_VERSION = "11.1.1"
PRISM_ZIP = f"PrismLauncher-Windows-MSVC-Portable-{PRISM_VERSION}.zip"
PRISM_URL = f"https://github.com/PrismLauncher/PrismLauncher/releases/download/{PRISM_VERSION}/{PRISM_ZIP}"
PRISM_SHA256 = "ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7"
FABRIC_API_URL = "https://cdn.modrinth.com/data/P7dR8mSH/versions/bNnaTiuM/fabric-api-0.161.0%2B26.3.jar"
FABRIC_API_SHA512 = "ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"
INSTANCE = "OverworldSupplyLine"


def run(cmd, cwd=None, env=None):
    print("+ " + " ".join(cmd), flush=True)
    subprocess.check_call(cmd, cwd=cwd, env=env)


def version():
    props = open(os.path.join(ROOT, "minecraft/gradle.properties")).read()
    return re.search(r"^version=(.+)$", props, re.M).group(1).strip()


def fetch(url, name, algo, digest):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"downloading {url}", flush=True)
        with urllib.request.urlopen(url) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    h = hashlib.new(algo, open(path, "rb").read()).hexdigest()
    if h != digest:
        os.remove(path)
        raise SystemExit(f"{name}: {algo} {h} doesn't match the pinned {digest}")
    return path


def build(v):
    run([sys.executable, os.path.join(ROOT, "tools/gen.py")])
    env = dict(os.environ)
    run(["./gradlew", "--no-daemon", "-q", "build"], cwd=os.path.join(ROOT, "minecraft"), env=env)
    run(["bash", "build.sh"], cwd=os.path.join(ROOT, "newvegas"))
    env.update(GOOS="windows", GOARCH="386", CGO_ENABLED="0")
    run(["go", "build", "-trimpath", "-ldflags", "-s -w -H windowsgui", "-o", os.path.join(DIST, "build", "osl-launch.exe"), "."],
        cwd=os.path.join(ROOT, "launcher"), env=env)


def write_zip(path, entries):
    """entries: {archive name: (source path or bytes)}; forward slashes, sorted, fixed timestamps."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in sorted(entries):
            src = entries[name]
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, src if isinstance(src, bytes) else open(src, "rb").read())


README_TXT = b"""Mojavecraft (Fallout: New Vegas x Minecraft: Java Edition)
Install and play from Melty: https://melty.gg/m/mojavecraft  -  source: https://github.com/polysilicon/MineNV

First Play: Prism Launcher opens - sign in with the Microsoft account that owns Minecraft: Java Edition.
It downloads Minecraft once (about 1 GB); New Vegas starts when Minecraft is ready.
In game: "Minecraft is linked." appears top left. B = build mode (left click breaks, right click places,
1-9 pick a block, I = Minecraft inventory). J = salvage junk into Minecraft items. Outdoors only.
Logs: Data/NVSE/Plugins/osl.log, osl-launch.log (here), and Prism's instance logs/latest.log.
"""

OSL_INI = b"""; Overworld Supply Line: settings for the New Vegas plugin (Melty replaces this file on updates).
[Composite]
; 1: New Vegas objects in front hide Minecraft blocks. 0: Minecraft is always drawn on top.
bDepthTest=1
; How far (game units, 70 = one block) behind New Vegas's surface a Minecraft block may still show.
iDepthBiasUnits=2
"""

INSTANCE_CFG = f"""[General]
InstanceType=OneSix
name={INSTANCE}
iconKey=default
notes=Managed by Overworld Supply Line: New Vegas starts this instance by itself. Its mods are replaced with each release.
OverrideJavaArgs=true
JvmArgs=--enable-native-access=ALL-UNNAMED -Dosl.startHidden=true
OverrideMemory=true
MinMemAlloc=512
MaxMemAlloc=3072
OverrideConsole=true
ShowConsole=false
AutoCloseConsole=false
ShowConsoleOnError=true
""".replace("\n", "\r\n").encode()

PRISM_CFG = """[General]
Language=en_US
ApplicationTheme=dark
AutomaticJavaDownload=true
AutomaticJavaSwitch=true
UserAskedAboutAutomaticJavaDownload=true
CloseAfterLaunch=true
QuitAfterGameStop=true
ShowConsole=false
AutoCloseConsole=false
ShowConsoleOnError=true
LowMemWarning=false
""".replace("\n", "\r\n").encode()


def mmc_pack():
    comps = [
        {"cachedName": "LWJGL 3", "cachedVersion": "3.4.3", "cachedVolatile": True, "dependencyOnly": True, "uid": "org.lwjgl3", "version": "3.4.3"},
        {"cachedName": "Minecraft", "cachedRequires": [{"suggests": "3.4.3", "uid": "org.lwjgl3"}], "cachedVersion": "26.3", "important": True,
         "uid": "net.minecraft", "version": "26.3"},
        {"cachedName": "Intermediary Mappings", "cachedRequires": [{"equals": "26.3", "uid": "net.minecraft"}], "cachedVersion": "26.3",
         "cachedVolatile": True, "dependencyOnly": True, "uid": "net.fabricmc.intermediary", "version": "26.3"},
        {"cachedName": "Fabric Loader", "cachedRequires": [{"uid": "net.fabricmc.intermediary"}], "cachedVersion": "0.19.5",
         "uid": "net.fabricmc.fabric-loader", "version": "0.19.5"},
    ]
    return (json.dumps({"components": comps, "formatVersion": 1}, indent=4) + "\n").encode()


def main():
    v = version()
    os.makedirs(os.path.join(DIST, "build"), exist_ok=True)
    if "--skip-build" not in sys.argv:
        build(v)
    jar = os.path.join(ROOT, "minecraft/build/libs", f"osl-{v}.jar")
    dll = os.path.join(ROOT, "newvegas/build/OverworldSupplyLine.dll")
    exe = os.path.join(DIST, "build", "osl-launch.exe")
    for f in (jar, dll, exe):
        if not os.path.exists(f):
            raise SystemExit(f"missing {f}: build first")
    notices = open(os.path.join(ROOT, "THIRD-PARTY-NOTICES.md"), "rb").read()

    main_zip = os.path.join(DIST, f"OverworldSupplyLine-{v}.zip")
    write_zip(main_zip, {
        "Data/NVSE/Plugins/OverworldSupplyLine.dll": dll,
        "Data/NVSE/Plugins/osl.ini": OSL_INI,
        "OSL/osl-launch.exe": exe,
        "OSL/THIRD-PARTY-NOTICES.md": notices,
        "OSL/README.txt": README_TXT,
    })

    prism = fetch(PRISM_URL, PRISM_ZIP, "sha256", PRISM_SHA256)
    fabric_api = fetch(FABRIC_API_URL, "fabric-api-0.161.0+26.3.jar", "sha512", FABRIC_API_SHA512)
    entries = {}
    with zipfile.ZipFile(prism) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = info.filename
            # the portable zip may hold one top folder: strip it so prismlauncher.exe sits in Prism/
            parts = name.split("/", 1)
            if len(parts) == 2 and parts[0].lower().startswith("prismlauncher"):
                name = parts[1]
            entries["Prism/" + name] = z.read(info)
    if "Prism/prismlauncher.exe" not in entries:
        raise SystemExit("Prism zip layout changed: no prismlauncher.exe at its root")
    inst = f"Prism/instances/{INSTANCE}/"
    entries.update({
        "Prism/prismlauncher.cfg": PRISM_CFG,
        inst + "instance.cfg": INSTANCE_CFG,
        inst + "mmc-pack.json": mmc_pack(),
        # fixed names: an update replaces the jars instead of leaving two versions side by side
        inst + ".minecraft/mods/overworld-supply-line.jar": jar,
        inst + ".minecraft/mods/fabric-api.jar": fabric_api,
        "README.txt": README_TXT,
        "THIRD-PARTY-NOTICES.md": notices + (
            "\n## Prism Launcher (GPL-3.0)\nBundled unmodified: Prism Launcher " + PRISM_VERSION + ", "
            "https://prismlauncher.org/. Source: https://github.com/PrismLauncher/PrismLauncher/tree/" + PRISM_VERSION + "\n"
            "\n## Fabric API (Apache-2.0)\nBundled unmodified: Fabric API 0.161.0+26.3, https://github.com/FabricMC/fabric\n"
            "\n## Fabric Loader (Apache-2.0)\nDownloaded by Prism Launcher from https://maven.fabricmc.net/ on first start.\n").encode(),
    })
    mc_zip = os.path.join(DIST, f"OverworldSupplyLine-Minecraft-{v}.zip")
    write_zip(mc_zip, entries)

    manifest = {}
    for path, comp in ((main_zip, "main"), (mc_zip, "minecraft")):
        with zipfile.ZipFile(path) as z:
            manifest[comp] = {"file": os.path.basename(path), "size": os.path.getsize(path),
                              "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
                              "entries": [{"path": i.filename, "size": i.file_size} for i in z.infolist()]}
        print(f"{os.path.basename(path)}: {os.path.getsize(path) / 1e6:.1f} MB, {len(manifest[comp]['entries'])} files")
    json.dump(manifest, open(os.path.join(DIST, "manifest.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
