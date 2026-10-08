#include "combat.h"
#include <windows.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "economy.h"
#include "game.h"
#include "log.h"
#include "msg.h"
#include "sheets_gen.h"
#include "takeover.h"

namespace
{
NVSEScriptInterface *g_script = nullptr;
std::function<void(const std::string &)> g_send;
Script *g_damage = nullptr, *g_health = nullptr, *g_restore = nullptr, *g_spawn = nullptr;
DWORD g_lastSend = 0;
double g_lastHealth = -1.0;

void *F(float f)
{
	void *p = nullptr;
	std::memcpy(&p, &f, sizeof f);
	return p;
}

TESObjectREFR *Player()
{
	return reinterpret_cast<TESObjectREFR *>(game::Player());
}

bool Compile()
{
	if (g_damage)
		return true;
	if (!g_script || !game::Player())
		return false;
	g_damage = g_script->CompileScript(
		"Begin Function {ref rActor, float fDmg, int iByPlayer}\nref rMe = GetSelf\nrActor.DamageAV Health fDmg\n"
		"if iByPlayer\nrActor.StartCombat rMe\nendif\nEnd");
	g_health = g_script->CompileScript("Begin Function {}\nSetFunctionValue (GetAV Health)\nEnd");
	g_restore = g_script->CompileScript("Begin Function {float fAmount}\nRestoreAV Health fAmount\nEnd");
	g_spawn = g_script->CompileScript(
		"Begin Function {ref rBase, float fx, float fy, float fz}\nref rNew = PlaceAtMe rBase 1\n"
		"rNew.SetPos X fx\nrNew.SetPos Y fy\nrNew.SetPos Z fz\nEnd");
	logf("combat: scripts %s", g_damage && g_health && g_restore && g_spawn ? "compiled" : "FAILED to compile");
	return g_damage != nullptr;
}

uintptr_t Ptr(uintptr_t a)
{
	return a ? *reinterpret_cast<uintptr_t *>(a) : 0;
}

/// {"t":"npcs","a":[[ref,x,y,z],...]}: New Vegas's living actors near the Courier, feet, in blocks.
void SendActors()
{
	game::Vec3 me;
	uintptr_t player = reinterpret_cast<uintptr_t>(game::Player());
	if (!player || !game::PlayerPosition(me))
		return;
	const float u = sheets::UNITS_PER_BLOCK, range = sheets::NPC_RANGE * u;
	std::string list;
	int n = 0;
	// ProcessManager::highActors: tList<Actor> {data, next}, the first node inline
	for (uintptr_t node = sheets::ADDR_PROCESS_MANAGER + 0x80; node && n < 64; node = Ptr(node + 4))
	{
		uintptr_t actor = Ptr(node);
		if (!actor || actor == player || !Ptr(actor + sheets::ADDR_REF_PARENT_CELL))
			continue;
		UInt32 life = *reinterpret_cast<UInt32 *>(actor + sheets::ADDR_ACTOR_LIFE_STATE);
		if (life == 1 || life == 2)
			continue;  // dying or dead
		const float *p = reinterpret_cast<const float *>(actor + 0x30);
		float dx = p[0] - me.x, dy = p[1] - me.y, dz = p[2] - me.z;
		if (dx * dx + dy * dy + dz * dz > range * range)
			continue;
		UInt32 ref = *reinterpret_cast<UInt32 *>(actor + sheets::ADDR_FORM_REFID);
		char e[96];
		std::snprintf(e, sizeof e, "%s[%u,%.3f,%.3f,%.3f]", n ? "," : "", ref, p[0] / u, p[2] / u + sheets::Y_OFFSET, -p[1] / u);
		list += e;
		n++;
	}
	g_send("{\"t\":\"npcs\",\"a\":[" + list + "]}");
}

/// New Vegas damage to the Courier: Steve takes it, the Courier is healed back (Steve's hearts are the health).
void WatchHealth()
{
	NVSEElement r{};
	if (!g_health || !g_script->CallFunction(g_health, Player(), nullptr, &r, 0) || r.type != 1)
		return;
	double h = r.num;
	if (g_lastHealth >= 0.0 && h < g_lastHealth - 0.01)
	{
		double d = g_lastHealth - h;
		char m[64];
		std::snprintf(m, sizeof m, "{\"t\":\"hurt\",\"d\":%.2f}", d);
		g_send(m);
		g_script->CallFunctionAlt(g_restore, Player(), 1, F(float(d)));
		h = g_lastHealth;
	}
	g_lastHealth = h;
}
}  // namespace

namespace combat
{
void Init(NVSEScriptInterface *script, std::function<void(const std::string &)> send)
{
	g_script = script;
	g_send = std::move(send);
}

void Reset()
{
	g_lastHealth = -1.0;
}

void Tick()
{
	if (!Compile())
		return;
	DWORD now = GetTickCount();
	if (now - g_lastSend >= DWORD(sheets::NPC_SEND_MS))
	{
		g_lastSend = now;
		SendActors();
	}
	WatchHealth();
}

void OnHit(const std::string &json)
{
	// {"t":"npchit","id":ref,"d":minecraftDamage,"by":"player"}
	if (!Compile())
		return;
	size_t i = json.find("\"id\":"), d = json.find("\"d\":");
	if (i == std::string::npos || d == std::string::npos)
		return;
	UInt32 ref = UInt32(std::strtoul(json.c_str() + i + 5, nullptr, 10));
	float dmg = std::strtof(json.c_str() + d + 4, nullptr) * sheets::MC_TO_NV_DAMAGE;
	bool byPlayer = msg::Str(json, "by") == "player";
	TESForm *actor = game::LookupForm(ref);
	if (!actor)
		return;
	g_script->CallFunctionAlt(g_damage, Player(), 3, actor, F(dmg), reinterpret_cast<void *>(intptr_t(byPlayer ? 1 : 0)));
}

void OnSpawn(const std::string &json)
{
	// {"t":"spawnnpc","kind":"ncr_ranger","p":[x,y,z]} (blocks, worldspace coordinates)
	if (!Compile())
		return;
	std::string kind = msg::Str(json, "kind");
	size_t p = json.find("\"p\":[");
	if (p == std::string::npos)
		return;
	char *end;
	float x = std::strtof(json.c_str() + p + 5, &end), y = std::strtof(end + 1, &end), z = std::strtof(end + 1, &end);
	for (const sheets::NpcEgg &egg : sheets::NPC_EGGS)
	{
		if (kind != egg.id)
			continue;
		for (int k = 0; k < egg.nameCount; k++)
			if (TESForm *base = economy::FindForm("NPC_", egg.names[k]))
			{
				const float u = sheets::UNITS_PER_BLOCK;
				g_script->CallFunctionAlt(g_spawn, Player(), 4, base, F(x * u), F(-z * u), F((y - sheets::Y_OFFSET) * u));
				logf("spawn egg: %s (%s)", egg.id, egg.names[k]);
				takeover::Say(std::string(egg.names[k]) + " arrives.");
				return;
			}
		takeover::Say(std::string("Your New Vegas has no ") + egg.names[0] + " (not found in FalloutNV.esm).");
		return;
	}
}
}  // namespace combat
