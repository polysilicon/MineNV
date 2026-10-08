// Minecraft mode (0.2.0): Minecraft plays, New Vegas follows. Every Minecraft key (sheets/keymap.json), the mouse
// and typed text go to Minecraft; Minecraft's physics move Steve and the Courier is put where he stands, looking
// where he looks; New Vegas's HUD is hidden. Esc, Tab (Pip-Boy), the console key, B (switch) and R (activate) stay
// with New Vegas.
#pragma once
#include <functional>
#include <string>
#include "nvse_api.h"

namespace takeover
{
void Init(NVSEScriptInterface *script, NVSEConsoleInterface *console, NVSETogglePlayerControlsInterface *controls,
	std::function<void(const std::string &)> send);
bool On();
/// Switch Minecraft mode on (Steve starts at the Courier's feet) or off (New Vegas controls the Courier again).
void Set(bool on);
/// Every game loop while on: forward input, keep the HUD hidden, move the Courier to Steve.
void Tick(bool screenOpen, bool active);
/// Minecraft's "me": where Steve is (worldspace coordinates in blocks) and where he looks.
void OnMe(const std::string &json);
/// A line for the player: Minecraft chat while on, New Vegas's corner otherwise.
void Say(const std::string &text);
}  // namespace takeover
