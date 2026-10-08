// Overworld Supply Line: the New Vegas half (xNVSE plugin).
// Each shown frame: send New Vegas's camera to Minecraft and draw Minecraft's newest frame into the picture.
// Each game loop: ground under the Courier, keys, salvage, quests, daily perks.
#include <windows.h>
#include <d3d9.h>
#include <cmath>
#include <cstdio>
#include <string>
#include <unordered_set>
#include <vector>
#include "compositor.h"
#include "economy.h"
#include "game.h"
#include "log.h"
#include "msg.h"
#include "nvse_api.h"
#include "pose.h"
#include "sheets_gen.h"
#include "ws.h"

namespace
{
constexpr const char *kModName = "OverworldSupplyLine";
PluginHandle g_handle = 0;
NVSEMessagingInterface *g_messaging = nullptr;
NVSEConsoleInterface *g_console = nullptr;
NVSETogglePlayerControlsInterface *g_controls = nullptr;
WsClient g_link;
int g_linkGeneration = 0;
std::string g_dir;  // Data/NVSE/Plugins/
compositor::Settings g_settings;

// state
bool g_build = false;
bool g_screenOpen = false;
UInt32 g_worldspace = 0;
long long g_frame = 0;
int g_viewW = 0, g_viewH = 0;
bool g_keyDown[512] = {};
DWORD g_lastQuestPoll = 0, g_lastTick = 0;
DWORD g_firstLoop = 0, g_lastLinkHint = 0;
bool g_compositorWarned = false;
bool g_linkAnnounced = false;
std::string g_lastLinkError;

// ground columns already sent, keyed by block x/z
std::unordered_set<long long> g_probed;
int g_lastBX = INT32_MIN, g_lastBZ = INT32_MIN;

long long Key(int x, int z)
{
	return (static_cast<long long>(x) << 32) ^ static_cast<unsigned>(z);
}

void Send(const std::string &m)
{
	if (g_link.connected())
		g_link.send(m);
}

void Script(const char *line)
{
	if (g_console)
		g_console->RunScriptLine2(line, nullptr, true);
}

// ---- input ----

bool Foreground()
{
	return GetForegroundWindow() == static_cast<HWND>(game::GameWindow());
}

bool Pressed(int code)
{
	int vk = code == 256 ? VK_LBUTTON : code == 257 ? VK_RBUTTON : code == 258 ? VK_MBUTTON
		: static_cast<int>(MapVirtualKeyA(code, MAPVK_VSC_TO_VK_EX));
	return vk && (GetAsyncKeyState(vk) & 0x8000) != 0;
}

void SetBuild(bool on)
{
	if (on == g_build)
		return;
	g_build = on;
	// New Vegas ignores the keys Minecraft uses (number keys are its hotkeys) and doesn't fire or aim
	for (const sheets::Key &k : sheets::KEYS)
		if (k.buildOnly && k.code < 256)
		{
			char line[48];
			std::snprintf(line, sizeof line, "%s %d", on ? "DisableKey" : "EnableKey", k.code);
			Script(line);
		}
	UInt32 flags = NVSETogglePlayerControlsInterface::kFlag_Attacking | NVSETogglePlayerControlsInterface::kFlag_AimingOrBlocking;
	if (g_controls)
		on ? g_controls->DisablePlayerControlsAlt(flags, kModName) : g_controls->EnablePlayerControlsAlt(flags, kModName);
	game::Message(on ? "Build mode: left click breaks, right click places, 1-9 pick a block, I opens Minecraft's inventory. B to leave."
					: "Build mode off.");
	logf("build mode %s", on ? "on" : "off");
}

void SetScreen(bool open)
{
	if (open == g_screenOpen)
		return;
	g_screenOpen = open;
	UInt32 flags = NVSETogglePlayerControlsInterface::kFlag_Movement | NVSETogglePlayerControlsInterface::kFlag_Looking
		| NVSETogglePlayerControlsInterface::kFlag_Pipboy | NVSETogglePlayerControlsInterface::kFlag_Fighting
		| NVSETogglePlayerControlsInterface::kFlag_POV | NVSETogglePlayerControlsInterface::kFlag_Jumping;
	if (g_controls)
		open ? g_controls->DisablePlayerControlsAlt(flags, kModName) : g_controls->EnablePlayerControlsAlt(flags, kModName);
	Script(open ? "DisableKey 1" : "EnableKey 1");  // Esc closes Minecraft's screen, not New Vegas's pause menu
}

void PollKeys(bool outdoors)
{
	bool active = Foreground() && !game::MenuMode();
	for (const sheets::Key &k : sheets::KEYS)
	{
		bool down = active && Pressed(k.code);
		bool was = g_keyDown[k.code];
		g_keyDown[k.code] = down;
		if (down == was)
			continue;
		std::string a = k.action;
		if (a == "toggle_build")
		{
			if (down && !g_screenOpen)
			{
				if (!g_link.connected() && !g_build)
					game::Message("Minecraft isn't connected yet, so build mode can't start. It may still be starting: if a Prism Launcher window is open, Alt-Tab to it and sign in.");
				else if (outdoors || g_build)
					SetBuild(!g_build);
				else
					game::Message("Minecraft blocks can only be built outdoors.");
			}
		}
		else if (a == "salvage")
		{
			if (down && !g_screenOpen)
			{
				if (!g_link.connected())
					game::Message("Minecraft isn't running yet: salvage when it is.");
				else
				{
					std::string give = economy::Salvage();
					if (!give.empty())
						Send(give);
				}
			}
		}
		else if (!k.buildOnly || g_build)
		{
			if (a.rfind("key:", 0) == 0)
			{
				std::string name = a.substr(4);
				if (g_screenOpen && name == "inventory")
					name = "escape";  // I again closes the inventory
				if (g_screenOpen && (name == "attack" || name == "use"))
					continue;  // inside a screen the mouse buttons go with the cursor
				Send("{\"t\":\"key\",\"k\":\"" + name + "\",\"down\":" + (down ? "true" : "false") + "}");
			}
			else if (a.rfind("slot:", 0) == 0 && down && !g_screenOpen)
				Send("{\"t\":\"slot\",\"n\":" + a.substr(5) + "}");
		}
	}

	if (g_screenOpen)
	{
		bool esc = active && Pressed(1);
		if (esc && !g_keyDown[1])
			Send("{\"t\":\"key\",\"k\":\"escape\",\"down\":true}");
		g_keyDown[1] = esc;
		int dx = 0, dy = 0;
		game::MouseDelta(dx, dy);
		char m[128];
		std::snprintf(m, sizeof m, "{\"t\":\"cursor\",\"dx\":%d,\"dy\":%d,\"b\":[%s,%s]}", dx, dy,
			active && Pressed(256) ? "true" : "false", active && Pressed(257) ? "true" : "false");
		Send(m);
	}
}

// ---- ground ----

void ResetGround()
{
	g_probed.clear();
	g_lastBX = g_lastBZ = INT32_MIN;
	Send("{\"t\":\"clear\"}");
}

void ProbeGround()
{
	game::Vec3 p;
	if (!game::PlayerPosition(p))
		return;
	const float u = sheets::UNITS_PER_BLOCK;
	int bx = int(std::floor(p.x / u)), bz = int(std::floor(-p.y / u));
	if (g_lastBX != INT32_MIN && (std::abs(bx - g_lastBX) > 64 || std::abs(bz - g_lastBZ) > 64))
		ResetGround();  // fast travel or a door: start over
	g_lastBX = bx;
	g_lastBZ = bz;

	std::string cols;
	int budget = sheets::GROUND_PROBES_PER_FRAME;
	const int r = sheets::GROUND_RADIUS;
	// nearest rings first
	for (int ring = 0; ring <= r && budget > 0; ring++)
		for (int dx = -ring; dx <= ring && budget > 0; dx++)
			for (int dz = -ring; dz <= ring && budget > 0; dz++)
			{
				if (std::max(std::abs(dx), std::abs(dz)) != ring || g_probed.count(Key(bx + dx, bz + dz)))
					continue;
				g_probed.insert(Key(bx + dx, bz + dz));
				budget--;
				game::Vec3 from = {(bx + dx + 0.5f) * u, -(bz + dz + 0.5f) * u, p.z + 20.0f * u};
				float z;
				if (!game::GroundBelow(from, 60.0f * u, z))
					continue;
				int top = int(std::lround(z / u + sheets::Y_OFFSET)) - 1;
				char c[64];
				std::snprintf(c, sizeof c, "%s%d,%d,%d,%d", cols.empty() ? "" : ",", bx + dx, bz + dz, top - 2, top);
				cols += c;
			}
	if (!cols.empty())
		Send("{\"t\":\"ground\",\"c\":[" + cols + "]}");
}

// ---- camera ----

void SendCamera()
{
	game::Camera cam;
	game::Vec3 feet;
	if (!game::MainCamera(cam) || !game::PlayerPosition(feet))
		return;
	const float u = sheets::UNITS_PER_BLOCK;
	const double pos[3] = {cam.pos.x, cam.pos.y, cam.pos.z}, fwd[3] = {cam.forward.x, cam.forward.y, cam.forward.z};
	pose::Mc mc;
	if (!pose::FromCamera(pos, fwd, cam.frustumTop, cam.frustumBottom, u, sheets::Y_OFFSET, mc))
		return;
	double px, py, pz;
	pose::ToBlocks(feet.x, feet.y, feet.z, u, sheets::Y_OFFSET, px, py, pz);
	compositor::SetHostPlanes(cam.nearPlane, cam.farPlane);

	char m[512];
	std::snprintf(m, sizeof m,
		"{\"t\":\"cam\",\"f\":%lld,\"p\":[%.4f,%.4f,%.4f],\"r\":[%.3f,%.3f,0],\"fov\":%.3f,\"fp\":true,"
		"\"pl\":[%.4f,%.4f,%.4f],\"h\":%.3f,\"ws\":%u,\"build\":%s}",
		++g_frame, mc.x, mc.y, mc.z, mc.yaw, mc.pitch, mc.fov, px, py, pz, mc.yaw, g_worldspace, g_build ? "true" : "false");
	Send(m);
}

void SendView(bool force)
{
	RECT rc;
	HWND wnd = static_cast<HWND>(game::GameWindow());
	if (!wnd || !GetClientRect(wnd, &rc))
		return;
	int w = rc.right - rc.left, h = rc.bottom - rc.top;
	if (w <= 0 || h <= 0 || (!force && w == g_viewW && h == g_viewH))
		return;
	g_viewW = w;
	g_viewH = h;
	Send("{\"t\":\"view\",\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
}

// ---- link ----

void HandleLink()
{
	bool fresh = g_link.generation() != g_linkGeneration;
	std::string err = g_link.lastError();
	if (err != g_lastLinkError)
	{
		g_lastLinkError = err;
		if (!err.empty())
			logf("link: %s", err.c_str());
	}
	if (g_link.connected() && !g_linkAnnounced && !game::MenuMode())
	{
		g_linkAnnounced = true;
		game::Message("Minecraft is linked. Press B outdoors for build mode, J to salvage junk.");
	}
	if (!g_link.connected())
		g_linkAnnounced = false;
	if (fresh && g_link.connected())
	{
		g_linkGeneration = g_link.generation();
		logf("link: connected to Minecraft (generation %d)", g_linkGeneration);
		SendView(true);
		ResetGround();
		for (const std::string &q : economy::PollQuests(true))
			Send(q);
	}
	std::string m;
	while (g_link.poll(m))
	{
		std::string t = msg::Str(m, "t");
		if (t == "perks")
			economy::OnPerks(msg::StrList(m, "on"));
		else if (t == "toast")
			game::Message(msg::Str(m, "text").c_str());
		else if (t == "screen")
			SetScreen(msg::Bool(m, "open"));
		else if (t == "hello")
			logf("link: %s", m.c_str());
	}
}

void MainLoop()
{
	if (!game::Player())
		return;
	HandleLink();
	DWORD nowTick = GetTickCount();
	if (!g_firstLoop)
		g_firstLoop = nowTick;
	if (!g_link.connected() && nowTick - g_firstLoop > 15000 && (!g_lastLinkHint || nowTick - g_lastLinkHint > 120000) && !game::MenuMode())
	{
		g_lastLinkHint = nowTick;
		game::Message("Mojavecraft: Minecraft isn't running. If a Prism Launcher window is open, sign in there; otherwise restart from Melty.");
		logf("link: Minecraft not reachable on 127.0.0.1:%d", sheets::LINK_PORT);
	}
	if (!g_compositorWarned && compositor::Status()[0])
	{
		g_compositorWarned = true;
		std::string m = std::string("Mojavecraft can't draw Minecraft: ") + compositor::Status();
		game::Message(m.c_str());
	}
	UInt32 ws = game::ExteriorWorldspace();
	bool outdoors = ws != 0;
	if (ws != g_worldspace)
	{
		if (!outdoors && g_build)
			SetBuild(false);
		g_worldspace = ws;
		ResetGround();
	}
	PollKeys(outdoors);
	static DWORD lastStatus = 0;
	if (nowTick - lastStatus > 30000)
	{
		lastStatus = nowTick;
		logf("status: linked=%d worldspace=%08X camera frames sent=%lld frames shared=%d drawn=%lld build=%d",
			g_link.connected() ? 1 : 0, g_worldspace, g_frame, compositor::SharedOpen() ? 1 : 0, compositor::Draws(), g_build ? 1 : 0);
	}
	if (!g_link.connected())
		return;
	if (outdoors)
		ProbeGround();
	DWORD now = GetTickCount();
	if (now - g_lastQuestPoll > DWORD(sheets::QUEST_POLL_SECONDS) * 1000)
	{
		g_lastQuestPoll = now;
		for (const std::string &q : economy::PollQuests(false))
			Send(q);
	}
	if (now - g_lastTick > 5000)
	{
		g_lastTick = now;
		economy::Tick();
	}
}

void FramePresent(bool loadingScreen)
{
	bool show = !loadingScreen && g_worldspace != 0 && g_link.connected() && game::Player();
	if (show)
	{
		SendView(false);
		SendCamera();
	}
	compositor::Present(game::Device(), show, g_settings);
}

void OnMessage(NVSEMessagingInterface::Message *m)
{
	switch (m->type)
	{
	case NVSEMessagingInterface::kMessage_MainGameLoop:
		MainLoop();
		break;
	case NVSEMessagingInterface::kMessage_OnFramePresent:
		FramePresent(m->data && *static_cast<int *>(m->data) != 0);
		break;
	case NVSEMessagingInterface::kMessage_PostLoadGame:
	case NVSEMessagingInterface::kMessage_NewGame:
		g_build = false;
		g_screenOpen = false;
		g_worldspace = 0;
		break;
	case NVSEMessagingInterface::kMessage_ExitGame:
	case NVSEMessagingInterface::kMessage_ExitGame_Console:
		compositor::Shutdown();
		break;
	default:
		break;
	}
}

std::string PluginDir()
{
	char path[MAX_PATH] = {};
	HMODULE self = nullptr;
	GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
		reinterpret_cast<LPCSTR>(&PluginDir), &self);
	GetModuleFileNameA(self, path, MAX_PATH);
	std::string s(path);
	return s.substr(0, s.find_last_of("\\/") + 1);
}
}  // namespace

extern "C"
{
__declspec(dllexport) bool NVSEPlugin_Query(const NVSEInterface *nvse, PluginInfo *info)
{
	info->infoVersion = PluginInfo::kInfoVersion;
	info->name = kModName;
	info->version = 1;
	return !nvse->isEditor && nvse->runtimeVersion == RUNTIME_VERSION_1_4_0_525;
}

__declspec(dllexport) bool NVSEPlugin_Load(NVSEInterface *nvse)
{
	g_dir = PluginDir();
	logOpen((g_dir + "osl.log").c_str());
	logf("Overworld Supply Line plugin loading (xNVSE %08X)", nvse->nvseVersion);
	g_handle = nvse->GetPluginHandle();
	g_messaging = static_cast<NVSEMessagingInterface *>(nvse->QueryInterface(kInterface_Messaging));
	g_console = static_cast<NVSEConsoleInterface *>(nvse->QueryInterface(kInterface_Console));
	g_controls = static_cast<NVSETogglePlayerControlsInterface *>(nvse->QueryInterface(kInterface_PlayerControls));
	auto *script = static_cast<NVSEScriptInterface *>(nvse->QueryInterface(kInterface_Script));
	auto *ser = static_cast<NVSESerializationInterface *>(nvse->QueryInterface(kInterface_Serialization));
	if (!g_messaging || !script)
	{
		logf("xNVSE interfaces missing: not loading");
		return false;
	}
	std::string ini = g_dir + "osl.ini";
	g_settings.depthTest = GetPrivateProfileIntA("Composite", "bDepthTest", 1, ini.c_str()) != 0;
	g_settings.depthBias = float(GetPrivateProfileIntA("Composite", "iDepthBiasUnits", 2, ini.c_str()));
	economy::Init(script, g_console, ser, g_handle, (g_dir + "osl_forms.ini").c_str());
	g_messaging->RegisterListener(g_handle, "NVSE", OnMessage);
	g_link.start("127.0.0.1", sheets::LINK_PORT);
	logf("loaded; linking to Minecraft on 127.0.0.1:%d", sheets::LINK_PORT);
	return true;
}
}
