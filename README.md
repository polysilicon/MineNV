# Mojavecraft

Steve's hotbar in the Courier's hands: Minecraft blocks, built into New Vegas. A Fallout: New Vegas x Minecraft: Java Edition mashup (repository: MineNV; internal name Overworld Supply Line).

You play New Vegas. Minecraft runs hidden next to it, follows New Vegas's camera, and its picture is drawn inside
New Vegas, so Minecraft blocks stand in the Mojave and New Vegas's own ground, rocks and roofs hold them up.

- **Build mode (B):** Steve's hand, hotbar and crosshair appear in the Courier's view. Left click breaks, right
  click places, 1-9 pick a block, I opens Minecraft's inventory (with a cursor). Outdoors only.
- **Salvage (J):** New Vegas junk becomes Minecraft items. Scrap metal becomes stone bricks and iron, tin cans become
  sandstone, Wonderglue becomes slime balls, and so on (14 kinds of junk).
- **Builds unlock Courier perks:** a farm (8 planted farmland) sends fresh food every in-game day, a house (bed,
  door and a roof) gives +25 carry weight, and a lit beacon brings 100 caps a day.
- **Quests drop Minecraft chests:** finishing Ain't That a Kick in the Head, Back in the Saddle, Ghost Town Gunfight
  and They Went That-a-Way lands a Fallout-themed chest next to you, once per world.
- **A starter kit:** a crafting table, planks, torches and wooden tools in your Minecraft inventory on the first
  start.
- **Single player.** Each New Vegas worldspace (the Mojave, the Strip, Freeside...) has its own part of the
  Minecraft world, and your builds are saved in your Minecraft world.

## Playing
Install it from Melty and press Play. You need Fallout: New Vegas (1.4.0.525, Steam or GOG) and a Microsoft account
that owns Minecraft: Java Edition.
- The first Play opens Prism Launcher: sign in with your Microsoft account. Prism downloads Minecraft 26.3 and Java
  once (about 1 GB). Melty installs xNVSE for New Vegas.
- After that, Play starts Minecraft hidden and New Vegas through xNVSE. "Minecraft is linked." appears in New Vegas
  when both are connected. Minecraft quits by itself after New Vegas closes.
- Logs: `Data/NVSE/Plugins/osl.log` (New Vegas side), `osl-launch.log` in Melty's folder for the mashup (the
  launcher), and Prism's instance `logs/latest.log` (Minecraft).

## How it is made
The design lives in JSON sheets in `sheets/` (link messages, game hooks, loot, builds, perks, quests, chests, keys,
constants, versions). `tools/preflight.py` checks every cell and cross-reference; `tools/gen.py` generates code from
the rows for all three parts:
- `minecraft/`: the Fabric mod (Java 25, Minecraft 26.3). It's based on universal-modder's minecraft-gta5-passthrough
  example (MIT): camera, invisible ground, frames to shared memory. On top of that it adds the economy (salvage
  deliveries, quest chests, build detection).
- `newvegas/`: the xNVSE plugin (32-bit C++). It sends the camera, probes the ground, routes keys, salvages, checks
  quests, applies perks, and draws Minecraft's frame into New Vegas's picture with Direct3D 9.
- `launcher/` (Go): `osl-launch.exe`. It finds the sheets' items and quests in your own `FalloutNV.esm`, starts the
  bundled Minecraft, then starts New Vegas through xNVSE.

Tests: `cd minecraft && ./gradlew test` (build detection), `python3 tools/fake_newvegas.py` against
`./gradlew runClient` (the whole link and the frame layout, 18 checks), `cd launcher && go test`,
`newvegas/tests/pose_test.cpp` (camera conversion). Package: `python3 tools/package.py`.

Nothing from Bethesda's or Mojang's files is shipped. Prism downloads Minecraft with your own account, and the
launcher reads New Vegas data from your own copy.
