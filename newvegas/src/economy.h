// The supply line, New Vegas's half: salvage junk into Minecraft items, report finished quests, apply perks.
// Form IDs come from osl_forms.ini (the launcher resolves sheet names against the player's FalloutNV.esm).
#pragma once
#include <string>
#include <vector>
#include "nvse_api.h"

namespace economy
{
void Init(NVSEScriptInterface *script, NVSEConsoleInterface *console, NVSESerializationInterface *serialization,
	PluginHandle handle, const char *formsIniPath);
/// Salvage key: take the junk, return the "give" message for Minecraft ("" when there was none).
std::string Salvage();
/// Every QUEST_POLL_SECONDS: quests newly seen completed ("quest" messages). `resend`: all completed ones.
std::vector<std::string> PollQuests(bool resend);
/// Minecraft's "perks" list: apply what's new in this save.
void OnPerks(const std::vector<std::string> &on);
/// Daily perks: give today's items if a new in-game day began.
void Tick();
/// A form named in the sheets, resolved by the launcher ("MISC", "Scrap Metal"), or null.
TESForm *FindForm(const char *type, const char *name);
}  // namespace economy
