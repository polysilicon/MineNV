# Mojavecraft (MineNV): mod log

Fallout: New Vegas x Minecraft: Java Edition mashup for Melty. Solo first, built so a shared Minecraft world can
be added later.

## Decisions (with the user, 2026-10-06)
- Listing: title "Mojavecraft", tagline "Steve's hotbar in the Courier's hands: Minecraft blocks, built into New Vegas." (user picked option 3).
- Idea: two linked worlds (option 3 of 4).
- Link: Minecraft building pays off in New Vegas; New Vegas quests unlock Minecraft loot (option 4).
- Solo now, friends later (option 4). v1 scope: the full two-way version (option 4).
- License: MIT, remixes allowed on Melty (user picked option 1).
- The user asked for Minecraft drawn **inside** New Vegas like the other Minecraft mashups, then chose
  **"Minecraft in the Mojave"** (option 2): Steve's hotbar and hand in the Courier's view, Minecraft blocks placed
  and broken on Mojave ground. You cannot dig into New Vegas terrain, so the economy runs the other way:
  - New Vegas junk -> Minecraft items (Salvage key) [sheets/loot.json]
  - Minecraft builds in the Mojave (farm, house, beacon) -> Courier perks [sheets/builds.json, perks.json]
  - New Vegas quests -> Fallout-themed chests next to the player in Minecraft [sheets/quests.json, chests.json]
- Repository: `polysilicon/MineNV` (chosen by the user). `melty.json` at its root is the install recipe.

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

## Test 1 on the user's PC (0.1.0, 2026-10-06)
- New Vegas plugin loaded (B showed the build-mode message), but "Minecraft is linked." never appeared: Minecraft
  was not listening. Most likely cause: on the first Play, Prism's sign-in window opened behind New Vegas, so
  Minecraft never started. No logs received yet.
- 0.1.1: the launcher starts Prism and waits for Minecraft's link before starting New Vegas (15 min before the first
  sign-in, else 3); Minecraft logs "OSL ready" once its world is open and Melty's setup waits for that line; the
  plugin says on screen when Minecraft isn't connected or drawing fails; README.txt in both zips.
- Melty install reports (0.1.0 went live: 250 installs): 8 uninstalls stuck on Prism\archive.dll (EPERM) = Prism/
  Minecraft still running in the background. 0.1.1 also quits hidden Minecraft when New Vegas never links in 10 min.

## Test 2 on the user's PC (0.1.1, 2026-10-08)
- Minecraft music audible (Minecraft runs), still no "Minecraft is linked.".
- Wine test harness (newvegas/tests/fakenv.cpp, run_fakenv.sh): loads the REAL plugin DLL with fake xNVSE interfaces,
  fake game memory at the hooks.json addresses (the exe image spans 0x400000-0x1400000 like FalloutNV.exe), code
  stubs at PickObject/terrain/MenuMode/QueueUIMessage, a real Direct3D 9 device (wined3d), simulated key presses.
  Findings, all fixed in 0.1.2:
  1. the plugin links fine, but "Minecraft is linked." was queued while New Vegas could still be in its main menu
     (the player singleton exists there) and never shown again -> now shown once out of menus;
  2. the composited picture was upside down -> rows flipped on upload (not in the shader);
  3. Wine's HLSL compiler rejects ternaries in SM3 -> shader uses only float maths;
  4. salvage message shown twice -> only Minecraft's confirmation;
  5. Minecraft's music muted (block sounds kept).
- Link failures are now logged with their reason; osl.log gets a status line every 30 s.

## Test 3 on the user's PC (0.1.2, 2026-10-08): osl.log received
- Linked within 8 s, 20 forms resolved, worldspace 000DA726, build mode toggled, BUT camera frames sent = 0 and
  drawn = 0: the user's xNVSE is 6.3.5 (06030050) and it never sent kMessage_OnFramePresent, which the plugin relied
  on for the camera and the composite.
- 0.1.3: the plugin hooks IDirect3DDevice9::Present (vtable slot 17) itself and ignores xNVSE's present message once
  hooked; nothing is drawn in menus/Pip-Boy/loading. Wine harness with NVSE=old (6.3.5, no present message): 807
  camera frames sent and 807 composited in 30 s, picture correct.

## Test 4 on the user's PC (0.1.3, 2026-10-08)
- Present hook installed, linked, 596 camera frames sent, shared memory opened, but drawn = 0: the frame copy never
  completed. Likely: copying three full-screen layers through a fresh 32-bit view each frame is slower than Minecraft
  rewriting its 3-slot ring, so the "slot rewritten while copying" check dropped every frame.
- 0.1.4: Minecraft renders at most 1600x900 (scaled up when drawn, colour filtered); a rewritten slot is still shown;
  every failure in the copy (mapping, textures, lock, device state, state block) is logged once with its reason.

## Direction (user, 2026-10-08): option 3, "Minecraft played inside the New Vegas world"
SkyCraft-style: Minecraft's physics move the player, all input goes to Minecraft (chat/commands too), Minecraft's
HUD replaces New Vegas's, New Vegas NPCs fought with Minecraft weapons. Plan: 0.2.0 takeover (input, physics,
camera, HUD, collision field), 0.3.0 combat, later digging into New Vegas terrain.

## 0.2.0 Minecraft mode (2026-10-08)
- sheets/keymap.json: 99 keys, DirectInput -> SDL scancode (Minecraft 26.3 InputConstants are SDL scancodes);
  owner newvegas: Esc, Tab, `, B, R, J. link.json: takeover, in, mv, btn, wheel, txt, me, say.
- Minecraft: InputBridge (SkyCraft's approach) replays keys/mouse/text into KeyboardHandler/MouseHandler;
  InputConstantsMixin answers isKeyDown from forwarded keys; Takeover turns physics on, puts Steve at the Courier's
  feet, publishes "me" every frame; Minecraft's own camera is used (FOV still New Vegas's).
- New Vegas: takeover.cpp disables New Vegas's controls (DisablePlayerControlsAlt) and Minecraft's keys
  (DisableKey), tcl, hides the HUD (SetUIFloat HUDMainMenu/visible 0, re-applied), moves the Courier with a compiled
  UDF (SetPos X/Y/Z, SetAngle X/Z), R activates GetCrosshairRef.
- Harness: walking (W) moved Steve ~16 blocks and the Courier followed; T + typed "/say hello from the Mojave" ran;
  found and fixed: New Vegas-owned keys swallowed letters while typing, two keymap rows on one Windows key typed
  "//", and command feedback was off. Not yet in the real game: mouse look, HUD hiding, tcl, SetPos smoothness.

## Test 5 on the user's PC (0.2.0, 2026-10-08)
- Works: Minecraft's HUD shows, commands work.
- Broken, fixed in 0.2.1:
  1. mouse buttons: Minecraft 26.3 numbers them like SDL (InputConstants MOUSE_BUTTON_LEFT 1, MIDDLE 2, RIGHT 3);
     the link sent 0/1/2, so right click was Minecraft's left (dragging in the inventory, swinging instead of
     placing) and left click did nothing. Mapped in InputBridge.sdlButton; tested: right click placed a plank at
     0,64,1, holding left broke it.
  2. scroll wheel put New Vegas in third person (DisableKey 264/265 didn't stop it): every frame in Minecraft mode,
     ToggleFirstPerson(0x950110) when is3rdPerson (+0x64A).
  3. New Vegas's HUD still showed (SetUIFloat HUDMainMenu/visible had no effect): its scene node is hidden every
     frame (HUDMainMenu 0x11D96C0 -> tile +0x04 -> node +0x2C, NiAVObject flags +0x30 bit 0).
  4. the Courier's first-person arms showed: node1stPerson (+0x694) hidden every frame.
- Not yet confirmed: whether placed blocks are drawn in the real game (may have been only the button bug; if not,
  bDepthTest=0 in osl.ini draws Minecraft on top regardless of New Vegas's depth).
