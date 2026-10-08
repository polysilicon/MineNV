#include "takeover.h"
#include <windows.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "game.h"
#include "log.h"
#include "msg.h"
#include "sheets_gen.h"

namespace
{
constexpr const char *kModName = "OverworldSupplyLine";
constexpr int kActivateKey = 19;  // R: New Vegas activate (keymap.json owner newvegas)
constexpr int kEscKey = 1;
constexpr int kConsoleKey = 41;
NVSEScriptInterface *g_script = nullptr;
NVSEConsoleInterface *g_console = nullptr;
NVSETogglePlayerControlsInterface *g_controls = nullptr;
std::function<void(const std::string &)> g_send;
bool g_on = false;
bool g_held[256] = {};
bool g_buttons[3] = {};
bool g_activateHeld = false, g_escHeld = false;
DWORD g_lastHud = 0;
Script *g_follow = nullptr, *g_activate = nullptr;

// latest "me"
bool g_haveMe = false;
float g_feet[3] = {}, g_yaw = 0, g_pitch = 0;

constexpr UInt32 kControls = NVSETogglePlayerControlsInterface::kFlag_Movement | NVSETogglePlayerControlsInterface::kFlag_Looking
	| NVSETogglePlayerControlsInterface::kFlag_Fighting | NVSETogglePlayerControlsInterface::kFlag_POV
	| NVSETogglePlayerControlsInterface::kFlag_Sneaking | NVSETogglePlayerControlsInterface::kFlag_Attacking
	| NVSETogglePlayerControlsInterface::kFlag_EnterVATS | NVSETogglePlayerControlsInterface::kFlag_Jumping
	| NVSETogglePlayerControlsInterface::kFlag_AimingOrBlocking;

void Run(const char *line)
{
	if (g_console)
		g_console->RunScriptLine2(line, nullptr, true);
}

int Vk(int dik)
{
	if (dik == 256) return VK_LBUTTON;
	if (dik == 257) return VK_RBUTTON;
	if (dik == 258) return VK_MBUTTON;
	UINT sc = dik >= 0x80 ? (0xE000u | UINT(dik & 0x7F)) : UINT(dik);
	return int(MapVirtualKeyA(sc, MAPVK_VSC_TO_VK_EX));
}

/// Two DirectInput codes can land on one Windows key (e.g. '/' and numpad '/' on some layouts): only the first counts.
bool Duplicate(int dik)
{
	static int firstFor[256] = {};
	static bool built = false;
	if (!built)
	{
		built = true;
		for (const sheets::KeyMap &k : sheets::KEYMAP)
		{
			int vk = Vk(k.dik);
			if (vk > 0 && vk < 256 && !firstFor[vk])
				firstFor[vk] = k.dik;
		}
	}
	int vk = Vk(dik);
	return vk > 0 && vk < 256 && firstFor[vk] != dik;
}

bool Down(int dik)
{
	int vk = Vk(dik);
	return vk && (GetAsyncKeyState(vk) & 0x8000) != 0;
}

void* F(float f)
{
	void *p = nullptr;
	std::memcpy(&p, &f, sizeof f);
	return p;
}

void Compile()
{
	if (g_follow || !g_script)
		return;
	g_follow = g_script->CompileScript(
		"Begin Function {float fx, float fy, float fz, float fax, float faz}\n"
		"SetPos X fx\nSetPos Y fy\nSetPos Z fz\nSetAngle X fax\nSetAngle Z faz\nEnd");
	g_activate = g_script->CompileScript(
		"Begin Function {}\nref rMe = GetSelf\nref rTarget = GetCrosshairRef\nif rTarget\nrTarget.Activate rMe 1\nendif\nEnd");
	logf("takeover: scripts %s", g_follow && g_activate ? "compiled" : "FAILED to compile");
}

void KeysForNewVegas(bool disable)
{
	char line[48];
	for (const sheets::KeyMap &k : sheets::KEYMAP)
		if (k.minecraft || k.dik == kActivateKey)
		{
			std::snprintf(line, sizeof line, "%s %d", disable ? "DisableKey" : "EnableKey", k.dik);
			Run(line);
		}
	for (int code : {256, 257, 258, 264, 265})
	{
		std::snprintf(line, sizeof line, "%s %d", disable ? "DisableKey" : "EnableKey", code);
		Run(line);
	}
}

void ReleaseAll()
{
	for (const sheets::KeyMap &k : sheets::KEYMAP)
		if (g_held[k.dik])
		{
			g_held[k.dik] = false;
			g_send("{\"t\":\"in\",\"k\":" + std::to_string(k.sdl) + ",\"d\":false}");
		}
	for (int b = 0; b < 3; b++)
		if (g_buttons[b])
		{
			g_buttons[b] = false;
			g_send("{\"t\":\"btn\",\"b\":" + std::to_string(b) + ",\"d\":false}");
		}
}

std::string Typed(int dik)
{
	// the keys that change characters, from the hardware state (GetKeyboardState can lag behind)
	BYTE state[256] = {};
	for (int vk : {VK_SHIFT, VK_LSHIFT, VK_RSHIFT, VK_CONTROL, VK_LCONTROL, VK_RCONTROL, VK_MENU, VK_LMENU, VK_RMENU})
		state[vk] = (GetAsyncKeyState(vk) & 0x8000) ? 0x80 : 0;
	state[VK_CAPITAL] = (GetKeyState(VK_CAPITAL) & 1) ? 1 : 0;
	wchar_t buf[8];
	int vk = Vk(dik);
	int n = ToUnicode(UINT(vk), UINT(dik & 0x7F), state, buf, 8, 0);
	if (n <= 0)
		return "";
	std::string out;
	for (int i = 0; i < n; i++)
	{
		unsigned c = buf[i];
		if (c < 0x20 || c == 0x7F)
			continue;  // Enter, Backspace, Tab: Minecraft gets those as keys
		if (c < 0x80)
			out += char(c);
		else if (c < 0x800)
		{
			out += char(0xC0 | (c >> 6));
			out += char(0x80 | (c & 0x3F));
		}
		else
		{
			out += char(0xE0 | (c >> 12));
			out += char(0x80 | ((c >> 6) & 0x3F));
			out += char(0x80 | (c & 0x3F));
		}
	}
	return out;
}
}  // namespace

namespace takeover
{
void Init(NVSEScriptInterface *script, NVSEConsoleInterface *console, NVSETogglePlayerControlsInterface *controls,
	std::function<void(const std::string &)> send)
{
	g_script = script;
	g_console = console;
	g_controls = controls;
	g_send = std::move(send);
}

bool On()
{
	return g_on;
}

void Say(const std::string &text)
{
	if (g_on)
		g_send("{\"t\":\"say\",\"text\":" + msg::Quote(text) + "}");
	else
		game::Message(text.c_str());
}

void Set(bool on)
{
	if (on == g_on)
		return;
	Compile();
	if (on)
	{
		game::Vec3 p;
		if (!game::PlayerPosition(p))
			return;
		const float u = sheets::UNITS_PER_BLOCK;
		double yaw = 180.0;
		game::Camera cam;
		if (game::MainCamera(cam))
			yaw = std::atan2(-double(cam.forward.x), -double(cam.forward.y)) * 180.0 / 3.14159265358979;  // as pose.h
		char m[256];
		std::snprintf(m, sizeof m, "{\"t\":\"takeover\",\"on\":true,\"feet\":[%.4f,%.4f,%.4f],\"yaw\":%.2f}", p.x / u,
			p.z / u + sheets::Y_OFFSET, -p.y / u, yaw);
		g_send(m);
		g_haveMe = false;
		if (g_controls)
			g_controls->DisablePlayerControlsAlt(kControls, kModName);
		KeysForNewVegas(true);
		Run("tcl");  // New Vegas's gravity and collision would fight Minecraft's physics
		Run("SetUIFloat \"HUDMainMenu/visible\" 0");
		g_on = true;
		logf("takeover: Minecraft mode on");
		Say("Minecraft mode: you play as Steve. E inventory, T chat, / commands, R uses New Vegas doors and people, Tab Pip-Boy, B back to New Vegas controls.");
	}
	else
	{
		ReleaseAll();
		g_send("{\"t\":\"takeover\",\"on\":false}");
		if (g_controls)
			g_controls->EnablePlayerControlsAlt(kControls, kModName);
		KeysForNewVegas(false);
		Run("tcl");
		Run("SetUIFloat \"HUDMainMenu/visible\" 1");
		g_on = false;
		logf("takeover: Minecraft mode off");
		game::Message("New Vegas controls. B for Minecraft mode.");
	}
}

void OnMe(const std::string &json)
{
	// {"t":"me","f":[x,y,z],"e":eye,"r":[yaw,pitch],"ground":bool}
	size_t f = json.find("\"f\":["), r = json.find("\"r\":[");
	if (f == std::string::npos || r == std::string::npos)
		return;
	const char *s = json.c_str() + f + 5;
	char *end;
	for (int i = 0; i < 3; i++)
	{
		g_feet[i] = std::strtof(s, &end);
		s = end + 1;
	}
	s = json.c_str() + r + 5;
	g_yaw = std::strtof(s, &end);
	g_pitch = std::strtof(end + 1, &end);
	g_haveMe = true;
}

void Tick(bool screenOpen, bool active)
{
	if (!g_on)
		return;
	DWORD now = GetTickCount();
	if (now - g_lastHud > 1000)
	{
		g_lastHud = now;
		Run("SetUIFloat \"HUDMainMenu/visible\" 0");  // menus put it back
	}

	// the Courier stands where Steve stands and looks where he looks
	if (g_haveMe && g_follow)
	{
		const float u = sheets::UNITS_PER_BLOCK;
		float nx = g_feet[0] * u, ny = -g_feet[2] * u, nz = (g_feet[1] - sheets::Y_OFFSET) * u;
		float heading = std::fmod(g_yaw - 180.0f + 720.0f, 360.0f);  // Minecraft yaw 180 = north = New Vegas heading 0
		g_script->CallFunctionAlt(g_follow, reinterpret_cast<TESObjectREFR *>(game::Player()), 5, F(nx), F(ny), F(nz), F(g_pitch), F(heading));
	}

	if (!active)
	{
		ReleaseAll();
		return;
	}

	for (const sheets::KeyMap &k : sheets::KEYMAP)
	{
		// with a Minecraft screen open (chat, signs, inventory) New Vegas's own keys type too, except Esc and the console
		bool forward = (k.minecraft || (screenOpen && k.dik != kEscKey && k.dik != kConsoleKey)) && !Duplicate(k.dik);
		if (!forward)
		{
			if (g_held[k.dik])
			{
				g_held[k.dik] = false;
				g_send("{\"t\":\"in\",\"k\":" + std::to_string(k.sdl) + ",\"d\":false}");
			}
			continue;
		}
		bool d = Down(k.dik);
		if (d == g_held[k.dik])
			continue;
		g_held[k.dik] = d;
		g_send("{\"t\":\"in\",\"k\":" + std::to_string(k.sdl) + ",\"d\":" + (d ? "true" : "false") + "}");
		if (d && screenOpen)
		{
			std::string t = Typed(k.dik);
			if (!t.empty())
				g_send("{\"t\":\"txt\",\"c\":" + msg::Quote(t) + "}");
		}
	}

	// Esc closes a Minecraft screen (New Vegas keeps it otherwise, for its pause menu)
	bool esc = Down(kEscKey);
	if (esc && !g_escHeld && screenOpen)
	{
		g_send("{\"t\":\"in\",\"k\":41,\"d\":true}");
		g_send("{\"t\":\"in\",\"k\":41,\"d\":false}");
	}
	g_escHeld = esc;

	// R: New Vegas activate (doors, people, containers) on what the Courier looks at
	bool act = Down(kActivateKey);
	if (act && !g_activateHeld && !screenOpen && g_activate)
		g_script->CallFunctionAlt(g_activate, reinterpret_cast<TESObjectREFR *>(game::Player()), 0);
	g_activateHeld = act;

	int dx = 0, dy = 0;
	game::MouseDelta(dx, dy);
	if (dx || dy)
		g_send("{\"t\":\"mv\",\"dx\":" + std::to_string(dx) + ",\"dy\":" + std::to_string(dy) + "}");
	for (int b = 0; b < 3; b++)
	{
		bool d = Down(256 + b);
		if (d != g_buttons[b])
		{
			g_buttons[b] = d;
			g_send("{\"t\":\"btn\",\"b\":" + std::to_string(b) + ",\"d\":" + (d ? "true" : "false") + "}");
		}
	}
	int wheel = game::MouseWheel();
	if (wheel)
		g_send("{\"t\":\"wheel\",\"d\":" + std::to_string(wheel > 0 ? 1 : -1) + "}");
}
}  // namespace takeover
