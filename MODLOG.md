# Overworld Supply Line: mod log

Fallout: New Vegas x Minecraft: Java Edition mashup for Melty. Solo first, built so a shared Minecraft world can
be added later.

## Decisions (with the user, 2026-10-06)
- Idea: two linked worlds (option 3 of 4).
- Link: Minecraft building pays off in New Vegas; New Vegas quests unlock Minecraft loot (option 4).
- Solo now, friends later (option 4). v1 scope: the full two-way version (option 4).
- The user asked for Minecraft drawn **inside** New Vegas like the other Minecraft mashups, then chose
  **"Minecraft in the Mojave"** (option 2): Steve's hotbar and hand in the Courier's view, Minecraft blocks placed
  and broken on Mojave ground. You cannot dig into New Vegas terrain, so the economy runs the other way:
  - New Vegas junk -> Minecraft items (Salvage key) [sheets/loot.json]
  - Minecraft builds in the Mojave (farm, house, beacon) -> Courier perks [sheets/builds.json, perks.json]
  - New Vegas quests -> Fallout-themed chests next to the player in Minecraft [sheets/quests.json, chests.json]
- New repository `polysilicon/overworld-supply-line` (user creates it; the Claude app couldn't: 403).

## Melty read-up (2026-10-06)
- fallout-new-vegas: Melty installs xNVSE 6.4.9; launch `{game}/nvse_loader.exe`. 0 live mashups.
- minecraft-java: no loader Melty installs -> bundle a portable Prism Launcher with the instance (as SkyCraft does:
  `setup` runs Prism once for sign-in/download, then the game starts).
- No anti-cheat on either game. Nothing like this mashup on Melty. SkyCraft (Skyrim + Minecraft) is the closest.
- Recipe fields available: `together` (processes alongside the game), `setup` (first-run step with a done file),
  `settings` (lines Melty writes before each Play), `multiplayer`.

## Route
- Base: universal-modder `examples/minecraft-gta5-passthrough/mc` (MIT): Fabric mod, Minecraft 26.3, Fabric
  Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25. WebSocket on 127.0.0.1:25599 for camera/ground/input, shared
  memory for colour + depth + overlay frames. Compiles here (JDK 25 from Adoptium in /opt/jdk25).
- New Vegas side: xNVSE plugin (32-bit C++), written new. Camera from the SceneGraph's NiCamera, ground from
  TES::PickObject ray casts, input via xNVSE IsKeyPressed/DisableKey, economy via CompileScript/CallFunction,
  composite in IDirect3DDevice9::Present (depth test by writing oDepth against the bound depth surface).
- Item and quest form IDs are resolved on the player's PC from their own FalloutNV.esm by name (the wikis with
  form-ID tables are blocked from this sandbox, and guessing IDs is unsafe).

## Source facts checked
- New Vegas 1.4.0.525 addresses (JIP LN source): PlayerCharacter** 0x11DEA3C, TES** 0x11DEA10,
  SceneGraph** 0x11DEB7C (camera at +0xAC), NiAVObject world rotate +0x68 / translate +0x8C, NiCamera frustum
  +0xDC, NiDX9Renderer** 0x11F4748 (device at +0x288), TES::PickObject 0x458440, TES::GetTerrainHeight 0x4572E0,
  PlayerCharacter::worldFOV +0x670.
- xNVSE key codes: DirectInput scancodes, mouse buttons 256+, wheel 264/265. `IsKeyPressed code 2` reads raw
  state even for disabled keys.

## Sandbox notes
- Maven Central rate-limits this container (429): `~/.gradle/init.d/mirror.gradle` puts Google's Maven Central
  mirror first (local only, not in the repo).
- github.com web/API is blocked here (403); anonymous git clones of public repos work.
