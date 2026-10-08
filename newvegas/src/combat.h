// Minecraft vs New Vegas's people (0.3.0, sheets/link.json npcs/npchit/hurt/spawnnpc): New Vegas's living actors near
// the Courier get Minecraft stand-ins, Minecraft hits hurt the real NPCs, New Vegas damage to the Courier becomes
// damage to Steve (Steve's hearts are the health), and New Vegas spawn eggs place real NPCs.
#pragma once
#include <functional>
#include <string>
#include "nvse_api.h"

namespace combat
{
void Init(NVSEScriptInterface *script, std::function<void(const std::string &)> send);
/// Every game loop while in Minecraft mode.
void Tick();
/// Minecraft mode just started or stopped: forget the Courier's last health.
void Reset();
void OnHit(const std::string &json);
void OnSpawn(const std::string &json);
}  // namespace combat
