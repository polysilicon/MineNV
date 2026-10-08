#!/usr/bin/env python3
"""Preflight: lay every sheet over the others before a build.

Errors (the build stops):
  - a cell that is missing, null or empty where the sheet needs a value
  - a reference between sheets that does not resolve (builds.perk -> perks, quests.chest -> chests, ...)
  - a Minecraft item that Minecraft 26.3 does not have (checked against the game jar when it is available)
  - a duplicate row id
Open checkboxes (listed, the build goes on):
  - every cell whose row says verified "no: ..." (what is still to check, and how)

Usage: python3 tools/preflight.py [--mc-jar <minecraft client jar>] [--quiet]
"""
import glob
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEETS = os.path.join(ROOT, "sheets")

# Columns that may legitimately be empty, per sheet.
OPTIONAL = {
    "loot": {"nv_edid"},
    "quests": {"nv_edid"},
    "perks": {"nv_items", "actor_value"},
    "link": {"fields"},
    "keymap": {"note"},
}


def load_sheets():
    sheets = {}
    for path in sorted(glob.glob(os.path.join(SHEETS, "*.json"))):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        sheets[data["sheet"]] = data
    return sheets


def find_mc_jar(explicit):
    if explicit:
        return explicit
    pattern = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.3/*.jar")
    found = glob.glob(pattern)
    return found[0] if found else None


def mc_items(jar):
    names = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            m = re.fullmatch(r"assets/minecraft/items/([a-z0-9_]+)\.json", n)
            if m:
                names.add("minecraft:" + m.group(1))
    return names


def base_item(spec):
    """'minecraft:bow[custom_name="x"]' -> 'minecraft:bow'"""
    return spec.split("[", 1)[0]


def main(argv):
    quiet = "--quiet" in argv
    jar = None
    if "--mc-jar" in argv:
        jar = argv[argv.index("--mc-jar") + 1]
    sheets = load_sheets()
    errors, todo = [], []

    def ids(name):
        return {r["id"] for r in sheets[name]["rows"]}

    # 1. every cell filled, ids unique
    for name, sheet in sheets.items():
        cols = list(sheet["columns"])
        seen = set()
        for r in sheet["rows"]:
            rid = r.get("id", "?")
            if rid in seen:
                errors.append(f"{name}: duplicate id {rid}")
            seen.add(rid)
            for c in cols:
                if c not in r:
                    errors.append(f"{name}.{rid}.{c}: missing")
                    continue
                v = r[c]
                empty = v is None or v == "" or v == []
                if empty and c not in OPTIONAL.get(name, set()):
                    errors.append(f"{name}.{rid}.{c}: empty")
                if isinstance(v, str) and v.startswith("no:") and c != "verified":
                    todo.append(f"{name}.{rid}.{c}: {v[3:].strip()}")
            for c in r:
                if c not in cols:
                    errors.append(f"{name}.{rid}.{c}: column not declared in the sheet")
            v = r.get("verified", "")
            if isinstance(v, str) and v.startswith("no:"):
                todo.append(f"{name}.{rid}: {v[3:].strip()}")

    # 2. references between sheets
    perks, chests = ids("perks"), ids("chests")
    for r in sheets["builds"]["rows"]:
        if r["perk"] not in perks:
            errors.append(f"builds.{r['id']}.perk: no perk '{r['perk']}'")
    for r in sheets["quests"]["rows"]:
        if r["chest"] not in chests:
            errors.append(f"quests.{r['id']}.chest: no chest '{r['chest']}'")
    used_perks = {r["perk"] for r in sheets["builds"]["rows"]}
    for p in perks - used_perks:
        errors.append(f"perks.{p}: no build unlocks it")
    used_chests = {r["chest"] for r in sheets["quests"]["rows"]}
    starter = next((r["value"] for r in sheets["constants"]["rows"] if r["id"] == "STARTER_CHEST"), None)
    if starter not in chests:
        errors.append(f"constants.STARTER_CHEST: no chest '{starter}'")
    used_chests.add(starter)
    for c in chests - used_chests:
        errors.append(f"chests.{c}: no quest drops it")
    rules = {"farmland_with_crops", "bed_door_roof", "active_beacon"}
    for r in sheets["builds"]["rows"]:
        if r["rule"] not in rules:
            errors.append(f"builds.{r['id']}.rule: no detector '{r['rule']}'")
    for r in sheets["perks"]["rows"]:
        if r["kind"] not in ("passive", "daily"):
            errors.append(f"perks.{r['id']}.kind: '{r['kind']}'")
        if r["kind"] == "daily" and not r["nv_items"]:
            errors.append(f"perks.{r['id']}: a daily perk needs nv_items")
        if r["kind"] == "passive" and not r["actor_value"]:
            errors.append(f"perks.{r['id']}: a passive perk needs actor_value")

    # keys -> link messages
    link = ids("link")
    codes = set()
    for r in sheets["keys"]["rows"]:
        a = r["mc_action"]
        if r["nv_code"] in codes:
            errors.append(f"keys.{r['id']}.nv_code: {r['nv_code']} used twice")
        codes.add(r["nv_code"])
        if r["mode"] not in ("always", "build"):
            errors.append(f"keys.{r['id']}.mode: '{r['mode']}'")
        if a in ("toggle_build",):
            need = "cam"
        elif a == "salvage":
            need = "give"
        else:
            need = a.split(":", 1)[0]
        if need not in link:
            errors.append(f"keys.{r['id']}.mc_action: link has no message '{need}'")
        if a.startswith("key:") and a[4:] not in ("use", "attack", "pick", "inventory", "escape"):
            errors.append(f"keys.{r['id']}.mc_action: unknown key '{a[4:]}'")
        if a.startswith("slot:") and not 0 <= int(a[5:]) <= 8:
            errors.append(f"keys.{r['id']}.mc_action: slot out of range")

    # keymap: unique codes, owners, the switch key stays with New Vegas
    if "keymap" in sheets:
        dk, sd = set(), set()
        for r in sheets["keymap"]["rows"]:
            if r["dik"] in dk:
                errors.append(f"keymap.{r['id']}.dik: {r['dik']} twice")
            if r["sdl"] in sd:
                errors.append(f"keymap.{r['id']}.sdl: {r['sdl']} twice")
            dk.add(r["dik"]); sd.add(r["sdl"])
            if r["owner"] not in ("minecraft", "newvegas"):
                errors.append(f"keymap.{r['id']}.owner: '{r['owner']}'")
            if r["owner"] == "newvegas" and not r["note"]:
                errors.append(f"keymap.{r['id']}.note: say what New Vegas does with it")
        owners = {r["dik"]: r["owner"] for r in sheets["keymap"]["rows"]}
        for r in sheets["keys"]["rows"]:
            if r["mc_action"] == "toggle_build" and owners.get(r["nv_code"]) != "newvegas":
                errors.append(f"keys.{r['id']}: the switch key must be a New Vegas key in keymap.json")

    # 3. Minecraft items exist
    jar = find_mc_jar(jar)
    if jar and os.path.exists(jar):
        items = mc_items(jar)
        specs = []
        for r in sheets["loot"]["rows"]:
            specs += [(f"loot.{r['id']}.gives", g[0]) for g in r["gives"]]
        for r in sheets["chests"]["rows"]:
            specs += [(f"chests.{r['id']}.items", g[0]) for g in r["items"]]
        for where, spec in specs:
            if base_item(spec) not in items:
                errors.append(f"{where}: Minecraft 26.3 has no item '{base_item(spec)}'")
    else:
        todo.append("Minecraft item ids: not checked (no Minecraft 26.3 jar found; run the Gradle build once)")

    # report
    if not quiet or errors:
        print(f"preflight: {len(sheets)} sheets, {sum(len(s['rows']) for s in sheets.values())} rows")
    for e in errors:
        print("ERROR  " + e)
    if not quiet:
        print(f"{len(todo)} open checkboxes (not yet verified):")
        for t in todo:
            print("  [ ] " + t)
    print("preflight: " + ("FAILED" if errors else "clean"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
