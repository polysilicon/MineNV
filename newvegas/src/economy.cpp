#include "economy.h"
#include "game.h"
#include "log.h"
#include "msg.h"
#include "sheets_gen.h"
#include <windows.h>
#include <cmath>
#include <cstdio>
#include <map>
#include <set>
#include <string>

namespace
{
NVSEScriptInterface *g_script = nullptr;
NVSEConsoleInterface *g_console = nullptr;
NVSESerializationInterface *g_ser = nullptr;
std::map<std::string, UInt32> g_forms;  // "MISC|Scrap Metal" -> form ID
std::set<std::string> g_questsSent;
constexpr UInt32 kCaps = 0x0000000F;  // Caps001

// per save (co-save record 'OSLS')
struct SaveState
{
	UInt32 passiveApplied = 0;  // bit per perks row
	UInt32 dailyUnlocked = 0;   // bit per perks row
	int lastDay[16] = {};
};
SaveState g_save;
constexpr UInt32 kRecord = 'OSLS', kRecordVersion = 1;
static_assert(sizeof(sheets::PERKS) / sizeof(sheets::PERKS[0]) <= 16, "perk bits");

Script *g_count = nullptr, *g_remove = nullptr, *g_add = nullptr, *g_quest = nullptr, *g_days = nullptr;
std::map<std::string, Script *> g_modav;

bool Compile()
{
	if (g_count)
		return true;
	if (!g_script || !game::Player())
		return false;
	g_count = g_script->CompileScript("Begin Function {ref rItem}\nSetFunctionValue (GetItemCount rItem)\nEnd");
	g_remove = g_script->CompileScript("Begin Function {ref rItem, int iCount}\nRemoveItem rItem iCount 1\nEnd");
	g_add = g_script->CompileScript("Begin Function {ref rItem, int iCount}\nAddItem rItem iCount 1\nEnd");
	g_quest = g_script->CompileScript("Begin Function {ref rQuest}\nSetFunctionValue (GetQuestCompleted rQuest)\nEnd");
	g_days = g_script->CompileScript("Begin Function {}\nSetFunctionValue GameDaysPassed\nEnd");
	for (const sheets::Perk &p : sheets::PERKS)
		if (p.actorValue)
		{
			char text[256];
			std::snprintf(text, sizeof text, "Begin Function {}\nModAV %s %d\nEnd", p.actorValue, p.avAmount);
			g_modav[p.id] = g_script->CompileScript(text);
		}
	logf("economy: scripts %s", g_count && g_remove && g_add && g_quest && g_days ? "compiled" : "FAILED to compile");
	return g_count != nullptr;
}

double Call(Script *s, TESForm *arg)
{
	NVSEElement result{};
	if (!s || !g_script->CallFunction(s, game::Player(), nullptr, &result, arg ? 1 : 0, arg))
		return 0.0;
	return result.type == 1 ? result.num : 0.0;
}

void CallCount(Script *s, TESForm *form, int count)
{
	if (s)
		g_script->CallFunctionAlt(s, game::Player(), 2, form, reinterpret_cast<void *>(intptr_t(count)));
}

TESForm *Form(const char *type, const char *name)
{
	if (std::string(name) == "Caps001")
		return game::LookupForm(kCaps);
	auto it = g_forms.find(std::string(type) + "|" + name);
	return it == g_forms.end() ? nullptr : game::LookupForm(it->second);
}

void LoadForms(const char *path)
{
	FILE *f = std::fopen(path, "r");
	if (!f)
	{
		logf("economy: %s missing: salvage, quest chests and daily items are off (run through the launcher)", path);
		return;
	}
	char line[512];
	while (std::fgets(line, sizeof line, f))
	{
		std::string s(line);
		while (!s.empty() && (s.back() == '\n' || s.back() == '\r'))
			s.pop_back();
		size_t eq = s.rfind('=');
		if (s.empty() || s[0] == ';' || eq == std::string::npos)
			continue;
		g_forms[s.substr(0, eq)] = std::strtoul(s.c_str() + eq + 1, nullptr, 16);
	}
	std::fclose(f);
	logf("economy: %u forms from %s", unsigned(g_forms.size()), path);
}

int Today()
{
	return int(std::floor(Call(g_days, nullptr)));
}

void OnSave(void *)
{
	g_ser->WriteRecord(kRecord, kRecordVersion, &g_save, sizeof g_save);
}

void OnLoad(void *)
{
	g_save = SaveState();
	g_questsSent.clear();
	UInt32 type, version, length;
	while (g_ser->GetNextRecordInfo(&type, &version, &length))
		if (type == kRecord && version == kRecordVersion && length == sizeof g_save)
			g_ser->ReadRecordData(&g_save, sizeof g_save);
}

void OnNewGame(void *)
{
	g_save = SaveState();
	g_questsSent.clear();
}
}  // namespace

namespace economy
{
void Init(NVSEScriptInterface *script, NVSEConsoleInterface *console, NVSESerializationInterface *serialization,
	PluginHandle handle, const char *formsIniPath)
{
	g_script = script;
	g_console = console;
	g_ser = serialization;
	if (g_ser)
	{
		g_ser->SetSaveCallback(handle, OnSave);
		g_ser->SetLoadCallback(handle, OnLoad);
		g_ser->SetNewGameCallback(handle, OnNewGame);
	}
	LoadForms(formsIniPath);
}

std::string Salvage()
{
	if (!Compile())
		return "";
	std::string items, what;
	int kinds = 0;
	for (const sheets::Loot &row : sheets::LOOT)
	{
		TESForm *form = Form("MISC", row.nvName);
		int n = form ? int(Call(g_count, form)) : 0;
		if (n <= 0)
			continue;
		CallCount(g_remove, form, n);
		for (int g = 0; g < row.giveCount; g++)
			items += (items.empty() ? "[" : ",[") + msg::Quote(row.gives[g].item) + "," + std::to_string(row.gives[g].count * n) + "]";
		char part[96];
		std::snprintf(part, sizeof part, "%s%d %s", what.empty() ? "" : ", ", n, row.nvName);
		what += part;
		kinds++;
	}
	if (!kinds)
	{
		game::Message("Nothing to salvage. Junk like scrap metal, tin cans and Wonderglue becomes Minecraft blocks.");
		return "";
	}
	std::string text = "Salvaged for Minecraft: " + what;  // shown when Minecraft confirms (its toast)
	logf("salvage: %s", what.c_str());
	return "{\"t\":\"give\",\"items\":[" + items + "],\"why\":" + msg::Quote(text) + "}";
}

std::vector<std::string> PollQuests(bool resend)
{
	std::vector<std::string> out;
	if (!Compile())
		return out;
	if (resend)
		g_questsSent.clear();
	for (const sheets::Quest &q : sheets::QUESTS)
	{
		if (g_questsSent.count(q.id))
			continue;
		TESForm *form = Form("QUST", q.nvName);
		if (form && Call(g_quest, form) != 0.0)
		{
			g_questsSent.insert(q.id);
			out.push_back(std::string("{\"t\":\"quest\",\"id\":") + msg::Quote(q.id) + "}");
		}
	}
	return out;
}

void OnPerks(const std::vector<std::string> &on)
{
	if (!Compile())
		return;
	for (size_t i = 0; i < sizeof(sheets::PERKS) / sizeof(sheets::PERKS[0]); i++)
	{
		const sheets::Perk &p = sheets::PERKS[i];
		bool unlocked = false;
		for (const std::string &id : on)
			unlocked |= id == p.id;
		if (!unlocked)
			continue;
		if (p.daily)
		{
			if (!(g_save.dailyUnlocked & (1u << i)))
			{
				g_save.dailyUnlocked |= 1u << i;
				g_save.lastDay[i] = Today() - 1;  // the first delivery comes right away
				logf("perk %s unlocked (daily)", p.id);
			}
		}
		else if (!(g_save.passiveApplied & (1u << i)))
		{
			g_save.passiveApplied |= 1u << i;
			auto it = g_modav.find(p.id);
			if (it != g_modav.end() && it->second)
				g_script->CallFunctionAlt(it->second, game::Player(), 0);
			game::Message(p.message);
			logf("perk %s applied", p.id);
		}
	}
}

TESForm *FindForm(const char *type, const char *name)
{
	return Form(type, name);
}

void Tick()
{
	if (!g_save.dailyUnlocked || !Compile())
		return;
	int today = Today();
	for (size_t i = 0; i < sizeof(sheets::PERKS) / sizeof(sheets::PERKS[0]); i++)
	{
		const sheets::Perk &p = sheets::PERKS[i];
		if (!(g_save.dailyUnlocked & (1u << i)) || today <= g_save.lastDay[i])
			continue;
		g_save.lastDay[i] = today;
		for (int k = 0; k < p.itemCount; k++)
			if (TESForm *form = Form("ALCH", p.items[k].nvName))
				CallCount(g_add, form, p.items[k].count);
		game::Message(p.message);
		logf("perk %s: day %d delivery", p.id, today);
	}
}
}  // namespace economy
