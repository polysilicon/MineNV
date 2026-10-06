#!/usr/bin/env python3
"""A stand-in for the New Vegas plugin: drives the Minecraft side over the link exactly as the plugin does
(rows of sheets/link.json), checks what comes back, and composites Minecraft's exported frame over a fake
Mojave picture so the frame layout and depth can be looked at.

Needs the dev client running (minecraft/: ./gradlew runClient, which allows test commands).
Usage: python3 tools/fake_newvegas.py [--out DIR]
"""
import asyncio
import json
import math
import mmap
import os
import struct
import sys
import time

import numpy as np
import websockets
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONST = {r["id"]: r["value"] for r in json.load(open(os.path.join(ROOT, "sheets/constants.json")))["rows"]}
PORT = CONST["LINK_PORT"]
SHM = "/dev/shm/" + CONST["FRAME_SHM"].split("\\")[-1]

GROUND_Y = 63          # top of New Vegas's (fake) ground, in blocks
EYE = (0.5, GROUND_Y + 1 + 1.62, 0.5)
WS = 0x000DA726        # a made-up worldspace form id


class Run:
    def __init__(self, out):
        self.out = out
        self.got = []
        self.frame = 0
        self.yaw, self.pitch = 180.0, 35.0   # facing north (-Z), looking down
        self.build = False
        self.results = []

    def check(self, name, ok, detail=""):
        self.results.append((name, ok, detail))
        print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)

    def seen(self, t, pred=lambda m: True, since=0):
        return [m for m in self.got[since:] if m.get("t") == t and pred(m)]

    async def wait_for(self, t, pred=lambda m: True, since=0, timeout=15.0):
        end = time.time() + timeout
        while time.time() < end:
            hits = self.seen(t, pred, since)
            if hits:
                return hits[0]
            await asyncio.sleep(0.1)
        return None

    async def reader(self, ws):
        async for raw in ws:
            m = json.loads(raw)
            self.got.append(m)
            if m.get("t") not in ("blocks",):
                print("  <- " + raw[:160], flush=True)

    async def camera(self, ws):
        while True:
            self.frame += 1
            await ws.send(json.dumps({"t": "cam", "f": self.frame, "p": list(EYE), "r": [self.yaw, self.pitch, 0.0], "fov": 70.0,
                                      "fp": True, "pl": [EYE[0], GROUND_Y + 1, EYE[2]], "h": self.yaw, "ws": WS, "build": self.build}))
            await asyncio.sleep(1 / 30)

    async def go(self):
        async with websockets.connect(f"ws://127.0.0.1:{PORT}", max_size=None) as ws:
            asyncio.create_task(self.reader(ws))
            hello = await self.wait_for("hello")
            self.check("link.hello", hello is not None and hello.get("shm", "").endswith("OSLFrame"), str(hello))
            perks0 = await self.wait_for("perks")
            self.check("link.perks on connect", perks0 is not None, str(perks0))
            asyncio.create_task(self.camera(ws))
            # a clean test area: nothing left from earlier runs, then the world forgets its rewards
            await ws.send(json.dumps({"t": "cmd", "c": "fill -16 64 -16 16 90 16 minecraft:air"}))
            await asyncio.sleep(1.0)
            await ws.send(json.dumps({"t": "devreset"}))

            # flat New Vegas ground around the player
            cols = []
            for x in range(-16, 17):
                for z in range(-16, 17):
                    cols += [x, z, GROUND_Y - 3, GROUND_Y]
            await ws.send(json.dumps({"t": "ground", "c": cols}))
            await asyncio.sleep(2.0)
            # the starter kit arrives once per world (the toast may have come before we connected)
            await ws.send(json.dumps({"t": "cmd", "c": "clear @a"}))
            await ws.send(json.dumps({"t": "cmd", "c": "give @a minecraft:oak_planks 64"}))
            await asyncio.sleep(1.0)

            # build mode: place a block with the use key
            self.build = True
            n = len(self.got)
            await ws.send(json.dumps({"t": "slot", "n": 0}))
            await asyncio.sleep(0.3)
            await ws.send(json.dumps({"t": "key", "k": "use", "down": True}))
            await asyncio.sleep(0.15)
            await ws.send(json.dumps({"t": "key", "k": "use", "down": False}))
            placed = await self.wait_for("blocks", lambda m: len(m.get("set", [])) >= 3, since=n, timeout=5)
            self.check("link.key use -> link.blocks set", placed is not None, str(placed)[:120])
            if placed:
                x, y, z = placed["set"][:3]
                self.check("blocks are reported in New Vegas's own coordinates", abs(x) < 64 and y == GROUND_Y + 1, f"{x},{y},{z}")

            # salvage
            n = len(self.got)
            await ws.send(json.dumps({"t": "give", "items": [["minecraft:stone_bricks", 16], ["minecraft:iron_ingot", 2]], "why": "Scrap metal hammered into bricks and ingots"}))
            t = await self.wait_for("toast", lambda m: "Scrap" in m["text"], since=n)
            self.check("link.give -> toast", t is not None)
            bad = len(self.got)
            await ws.send(json.dumps({"t": "give", "items": [["minecraft:stone 1\nsay hi", 1]], "why": ""}))
            await asyncio.sleep(0.5)
            self.check("link.give refuses anything but an item id", not self.seen("toast", since=bad))

            # every chest from the sheets lands and its give syntax works
            quests = json.load(open(os.path.join(ROOT, "sheets/quests.json")))["rows"]
            for q in quests:
                n = len(self.got)
                await ws.send(json.dumps({"t": "quest", "id": q["id"]}))
                t = await self.wait_for("toast", lambda m, q=q: m["text"] == q["message"], since=n)
                self.check(f"link.quest {q['id']} -> chest + toast", t is not None)
                await asyncio.sleep(0.5)
            n = len(self.got)
            await ws.send(json.dumps({"t": "quest", "id": quests[0]["id"]}))
            await asyncio.sleep(1.0)
            self.check("a quest chest lands only once", not self.seen("toast", since=n))

            # a house next to the player -> perk within PERK_SYNC_SECONDS
            n = len(self.got)
            for c in ["setblock 3 64 3 minecraft:red_bed", "setblock 5 64 3 minecraft:oak_door", "setblock 3 67 3 minecraft:oak_planks"]:
                await ws.send(json.dumps({"t": "cmd", "c": c}))
            p = await self.wait_for("perks", lambda m: "home_sweet_home" in m["on"], since=n, timeout=CONST["PERK_SYNC_SECONDS"] + 6)
            self.check("builds.house -> link.perks home_sweet_home", p is not None, str(p))

            # a Minecraft screen: inventory opens, the cursor moves and clicks, escape closes it
            n = len(self.got)
            await ws.send(json.dumps({"t": "key", "k": "inventory", "down": True}))
            await ws.send(json.dumps({"t": "key", "k": "inventory", "down": False}))
            o = await self.wait_for("screen", lambda m: m["open"], since=n, timeout=5)
            self.check("link.key inventory -> link.screen open", o is not None)
            for _ in range(10):
                await ws.send(json.dumps({"t": "cursor", "dx": 12, "dy": 6, "b": [False, False]}))
                await asyncio.sleep(0.05)
            await ws.send(json.dumps({"t": "cursor", "dx": 0, "dy": 0, "b": [True, False]}))
            await ws.send(json.dumps({"t": "cursor", "dx": 0, "dy": 0, "b": [False, False]}))
            await asyncio.sleep(1.0)
            self.grab("inventory")
            n = len(self.got)
            await ws.send(json.dumps({"t": "key", "k": "escape", "down": True}))
            c = await self.wait_for("screen", lambda m: not m["open"], since=n, timeout=5)
            self.check("link.key escape -> link.screen closed", c is not None)

            # look at the builds and take frames
            self.pitch = 20.0
            self.yaw = 225.0
            await asyncio.sleep(1.5)
            self.grab("build_mode")
            self.build = False
            await asyncio.sleep(1.0)
            flags = self.grab("explore_mode")
            self.check("overlay flag off outside build mode", flags is not None and not flags & 8, str(flags))

        ok = all(r[1] for r in self.results)
        print(f"fake_newvegas: {sum(r[1] for r in self.results)}/{len(self.results)} passed")
        return ok

    def grab(self, name):
        """Read the newest frame slot and composite it over a fake Mojave (sky + sand + a 'New Vegas' pillar in front)."""
        try:
            f = open(SHM, "rb")
        except OSError as e:
            self.check(f"frame {name}", False, str(e))
            return None
        with f:
            m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            magic, ver, header, slots = struct.unpack_from("<4i", m, 0)
            stride, = struct.unpack_from("<q", m, 16)
            latest, = struct.unpack_from("<i", m, 40)
            if magic != 0x5450434D or latest < 0:
                self.check(f"frame {name}", False, "no frame published")
                return None
            d = 256 + 128 * latest
            seq, mcf, hostf = struct.unpack_from("<3q", m, d)
            w, h = struct.unpack_from("<2i", m, d + 24)
            near, far, fov = struct.unpack_from("<3f", m, d + 32)
            flags, = struct.unpack_from("<i", m, d + 44)
            base = header + stride * latest
            n = w * h * 4
            color = np.frombuffer(m, np.uint8, n, base).reshape(h, w, 4)[::-1].astype(np.float32) / 255
            depth = np.frombuffer(m, np.float32, w * h, base + n).reshape(h, w)[::-1]
            overlay = np.frombuffer(m, np.uint8, n, base + 2 * n).reshape(h, w, 4)[::-1].astype(np.float32) / 255
        # fake New Vegas picture: sky over sand, and a pillar 3 blocks away in the middle (depth known)
        yy = np.broadcast_to(np.linspace(0, 1, h)[:, None], (h, w))
        nv = np.zeros((h, w, 3), np.float32)
        nv[:] = np.where((yy < 0.35)[..., None], np.array([0.55, 0.7, 0.9], np.float32), np.array([0.8, 0.68, 0.45], np.float32))
        # Minecraft depth is reversed-Z (flag 4): 1 = near. Linear distance d = near / depth for an infinite reversed projection
        with np.errstate(divide="ignore"):
            mc_dist = np.where(depth > 0, near / np.maximum(depth, 1e-9), np.inf)
        pillar = np.zeros((h, w), bool)
        pillar[:, int(w * 0.45):int(w * 0.5)] = True
        nv_dist = np.where(pillar, 3.0, np.inf)
        nv[pillar] = [0.35, 0.3, 0.28]
        a = color[..., 3:4]
        show = (mc_dist < nv_dist)[..., None]
        out = np.where(show, color[..., :3] + nv * (1 - a), nv)
        if flags & 8:
            oa = overlay[..., 3:4]
            out = overlay[..., :3] + out * (1 - oa)
        path = os.path.join(self.out, f"frame_{name}.png")
        Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save(path)
        cover = float((color[..., 3] > 0).mean())
        self.check(f"frame {name}: {w}x{h}, host frame {hostf}, fov {fov:.0f}, flags {flags}, Minecraft covers {cover:.1%}", w > 0 and hostf > 0, path)
        return flags


def main():
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else "."
    os.makedirs(out, exist_ok=True)
    ok = asyncio.run(Run(out).go())
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
