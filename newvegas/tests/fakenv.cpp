// fakenv.exe: a stand-in for Fallout: New Vegas 1.4.0.525 that loads the real OverworldSupplyLine.dll (32-bit, run
// under Wine on Linux or on Windows). It provides fake xNVSE interfaces and fake game memory at the addresses in
// sheets/hooks.json (player, cell, worldspace, camera, renderer, input), code stubs at the game functions the plugin
// calls (ray cast, terrain height, menu mode, corner messages), a real Direct3D 9 device with a depth buffer holding a
// "New Vegas" pillar, and it saves what was shown (after the plugin composited Minecraft) as BMP files.
//
// fakenv.exe <plugin dll> <shared memory file or ""> <out dir> [frames]
#include <windows.h>
#include <d3d9.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#include "../src/nvse_api.h"
#include "../src/sheets_gen.h"

static FILE *g_log = stdout;
// Like FalloutNV.exe, this image spans 0x400000..0x1400000, so New Vegas's fixed addresses are inside it (Wine maps
// other things there otherwise).
static volatile uint8_t g_reserve[0x1000000];
#define LOG(...) (std::fprintf(g_log, __VA_ARGS__), std::fprintf(g_log, "\n"), std::fflush(g_log))

// ---------- fake game memory ----------
static uint8_t *Page(uintptr_t addr, size_t size, DWORD prot = PAGE_READWRITE)
{
	uintptr_t base = addr & ~uintptr_t(0xFFFF);
	size_t len = ((addr + size + 0xFFFF) & ~uintptr_t(0xFFFF)) - base;
	void *p = VirtualAlloc(reinterpret_cast<void *>(base), len, MEM_RESERVE | MEM_COMMIT, prot);
	if (!p)
	{
		MEMORY_BASIC_INFORMATION mi;
		VirtualQuery(reinterpret_cast<void *>(addr), &mi, sizeof mi);
		if (mi.State == MEM_COMMIT)
			return reinterpret_cast<uint8_t *>(addr);
		LOG("FAIL could not map 0x%08X (error %lu)", unsigned(addr), GetLastError());
		ExitProcess(2);
	}
	return reinterpret_cast<uint8_t *>(addr);
}
template <typename T> static void Put(uintptr_t addr, T v) { std::memcpy(reinterpret_cast<void *>(addr), &v, sizeof v); }
static void Jump(uintptr_t addr, void *target)
{
	Page(addr, 16, PAGE_EXECUTE_READWRITE);
	DWORD old;
	VirtualProtect(reinterpret_cast<void *>(addr), 16, PAGE_EXECUTE_READWRITE, &old);
	uint8_t *p = reinterpret_cast<uint8_t *>(addr);
	p[0] = 0xB8;  // mov eax, target
	std::memcpy(p + 1, &target, 4);
	p[5] = 0xFF;  // jmp eax
	p[6] = 0xE0;
}

static uint8_t g_player[0x800], g_cell[0x100], g_worldspace[0x40], g_sceneGraph[0x100], g_camera[0x120], g_renderer[0xB80],
	g_input[0x1C10], g_tes[0x40];
static const float kGroundZ = 0.0f;  // New Vegas ground height (units): Minecraft y 64
static std::vector<std::string> g_messages;
static bool g_menuMode = false;

static void *__fastcall FakePick(void *tes, void *, float *rc, bool)
{
	// rc: pos0 (16 bytes), pos1 (16 bytes) in Havok units; hit fraction at +0x40
	float z0 = rc[2] / 0.142857f, z1 = rc[6] / 0.142857f;
	if (!(z0 >= kGroundZ && z1 <= kGroundZ))
		return nullptr;
	rc[0x40 / 4] = (z0 - kGroundZ) / (z0 - z1);
	return g_tes;  // any non-null "hit object"
}
static bool __fastcall FakeTerrain(void *, void *, float *, float *out)
{
	*out = kGroundZ;
	return true;
}
static bool __cdecl FakeMenuMode() { return g_menuMode; }
static bool __cdecl FakeMessage(const char *text, UInt32, const char *, const char *, float, UInt8)
{
	LOG("MESSAGE: %s", text);
	g_messages.push_back(text);
	return true;
}

// ---------- fake xNVSE ----------
static NVSEMessagingInterface::EventCallback g_listener = nullptr;
static bool FakeRegisterListener(PluginHandle, const char *sender, NVSEMessagingInterface::EventCallback cb)
{
	LOG("plugin registered a listener for %s", sender);
	g_listener = cb;
	return true;
}
static void *FakeQuery(UInt32 id);
static PluginHandle FakeHandle() { return 7; }
static bool FakeRunScriptLine2(const char *line, TESObjectREFR *, bool)
{
	LOG("script line: %s", line);
	return true;
}
struct FakeScript { std::string text; };
static Script *FakeCompile(const char *text)
{
	return reinterpret_cast<Script *>(new FakeScript{text});
}
static std::map<UInt32, int> g_inventory;  // form id -> count
static bool FakeCall(Script *s, TESObjectREFR *, TESObjectREFR *, NVSEElement *result, UInt8 n, ...)
{
	const std::string &t = reinterpret_cast<FakeScript *>(s)->text;
	va_list a;
	va_start(a, n);
	void *arg = n ? va_arg(a, void *) : nullptr;
	va_end(a);
	double v = 0;
	if (t.find("GetItemCount") != std::string::npos && arg)
		v = g_inventory[*reinterpret_cast<UInt32 *>(static_cast<uint8_t *>(arg) + 0xC)];
	else if (t.find("GetQuestCompleted") != std::string::npos)
		v = 1;  // every quest done
	else if (t.find("GameDaysPassed") != std::string::npos)
		v = 3.5;
	if (result)
	{
		result->num = v;
		result->type = 1;
	}
	return true;
}
static bool FakeCallAlt(Script *s, TESObjectREFR *, UInt8 n, ...)
{
	va_list a;
	va_start(a, n);
	void *form = n > 0 ? va_arg(a, void *) : nullptr;
	int count = n > 1 ? int(reinterpret_cast<intptr_t>(va_arg(a, void *))) : 0;
	va_end(a);
	const std::string &t = reinterpret_cast<FakeScript *>(s)->text;
	UInt32 id = form ? *reinterpret_cast<UInt32 *>(static_cast<uint8_t *>(form) + 0xC) : 0;
	LOG("script call: %s form %08X count %d", t.substr(t.find('\n') + 1, t.find('\n', t.find('\n') + 1) - t.find('\n') - 1).c_str(), id, count);
	if (t.find("RemoveItem") != std::string::npos)
		g_inventory[id] -= count;
	return true;
}
static void __fastcall FakeDisable(UInt32 f, const char *m) { LOG("controls disabled %08X by %s", f, m); }
static void __fastcall FakeEnable(UInt32 f, const char *m) { LOG("controls enabled %08X by %s", f, m); }
static NVSEMessagingInterface g_messaging = {4, FakeRegisterListener, nullptr};
static NVSEConsoleInterface g_console = {3, nullptr, FakeRunScriptLine2};
static NVSEScriptInterface g_script = {FakeCall, nullptr, nullptr, nullptr, FakeCallAlt, FakeCompile, nullptr};
static NVSETogglePlayerControlsInterface g_controls = {FakeDisable, FakeEnable};
static void *FakeQuery(UInt32 id)
{
	switch (id)
	{
	case kInterface_Messaging: return &g_messaging;
	case kInterface_Console: return &g_console;
	case kInterface_Script: return &g_script;
	case kInterface_PlayerControls: return &g_controls;
	default: return nullptr;  // no serialization in the fake
	}
}

// ---------- forms (for salvage) ----------
struct Form { uint8_t pad[0xC]; UInt32 refID; };
static void MakeFormMap(const std::map<UInt32, int> &forms)
{
	// NiTPointerMap: +4 bucket count, +8 bucket array; entries: next, key, value
	static uint8_t map[16];
	static void *buckets[37] = {};
	for (auto &[id, count] : forms)
	{
		Form *f = new Form{};
		f->refID = id;
		void **e = new void *[3]{buckets[id % 37], reinterpret_cast<void *>(uintptr_t(id)), f};
		buckets[id % 37] = e;
		g_inventory[id] = count;
	}
	Put<UInt32>(uintptr_t(map) + 4, 37);
	Put<void *>(uintptr_t(map) + 8, buckets);
	Page(sheets::ADDR_FORM_MAP, 4);
	Put<void *>(sheets::ADDR_FORM_MAP, map);
}

// ---------- a scene with depth ----------
struct V { float x, y, z, rhw; DWORD c; };
static void DrawScene(IDirect3DDevice9 *d, int w, int h)
{
	d->Clear(0, nullptr, D3DCLEAR_TARGET | D3DCLEAR_ZBUFFER, D3DCOLOR_XRGB(140, 180, 230), 1.0f, 0);
	d->BeginScene();
	d->SetFVF(D3DFVF_XYZRHW | D3DFVF_DIFFUSE);
	d->SetRenderState(D3DRS_ZENABLE, TRUE);
	d->SetRenderState(D3DRS_ZWRITEENABLE, TRUE);
	d->SetRenderState(D3DRS_ZFUNC, D3DCMP_LESSEQUAL);
	d->SetRenderState(D3DRS_LIGHTING, FALSE);
	d->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE);
	auto quad = [&](float x0, float y0, float x1, float y1, float z, DWORD c) {
		V v[4] = {{x0, y0, z, 1, c}, {x1, y0, z, 1, c}, {x0, y1, z, 1, c}, {x1, y1, z, 1, c}};
		d->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, v, sizeof(V));
	};
	// sand below the horizon: far away (depth 0.9999), a dark "New Vegas" pillar very near (depth of ~3 blocks)
	quad(0, h * 0.35f, float(w), float(h), 0.9999f, D3DCOLOR_XRGB(205, 175, 115));
	const float n = 10.0f, f = 300000.0f, zPillar = 3.0f * 70.0f;
	float dPillar = (f / (f - n)) * (1.0f - n / zPillar);
	quad(w * 0.45f, 0, w * 0.5f, float(h), dPillar, D3DCOLOR_XRGB(90, 77, 72));
	d->EndScene();
}

static void SaveBmp(IDirect3DDevice9 *d, const std::string &path)
{
	IDirect3DSurface9 *bb = nullptr, *sys = nullptr;
	d->GetBackBuffer(0, 0, D3DBACKBUFFER_TYPE_MONO, &bb);
	D3DSURFACE_DESC desc;
	bb->GetDesc(&desc);
	d->CreateOffscreenPlainSurface(desc.Width, desc.Height, desc.Format, D3DPOOL_SYSTEMMEM, &sys, nullptr);
	d->GetRenderTargetData(bb, sys);
	D3DLOCKED_RECT r;
	sys->LockRect(&r, nullptr, D3DLOCK_READONLY);
	FILE *f = std::fopen(path.c_str(), "wb");
	int w = desc.Width, h = desc.Height, row = w * 3, pad = (4 - row % 4) % 4;
	BITMAPFILEHEADER fh = {0x4D42, DWORD(sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER) + (row + pad) * h), 0, 0,
		sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER)};
	BITMAPINFOHEADER ih = {sizeof ih, w, h, 1, 24};
	std::fwrite(&fh, sizeof fh, 1, f);
	std::fwrite(&ih, sizeof ih, 1, f);
	for (int y = h - 1; y >= 0; y--)
	{
		const uint8_t *src = static_cast<uint8_t *>(r.pBits) + y * r.Pitch;
		for (int x = 0; x < w; x++)
			std::fwrite(src + x * 4, 3, 1, f);
		static const uint8_t zero[3] = {};
		std::fwrite(zero, pad, 1, f);
	}
	std::fclose(f);
	sys->UnlockRect();
	sys->Release();
	bb->Release();
	LOG("saved %s", path.c_str());
}

static void SetCamera(float yawDeg, float pitchDeg)
{
	// New Vegas camera: world rotate (row-major), first column = forward, second = up
	float yaw = yawDeg * 3.14159265f / 180, pitch = pitchDeg * 3.14159265f / 180;
	// forward in New Vegas axes: heading measured from north (+Y) towards east (+X), pitch down positive
	float fx = std::sin(yaw) * std::cos(pitch), fy = std::cos(yaw) * std::cos(pitch), fz = -std::sin(pitch);
	float ux = std::sin(yaw) * std::sin(pitch), uy = std::cos(yaw) * std::sin(pitch), uz = std::cos(pitch);
	float rx = std::cos(yaw), ry = -std::sin(yaw), rz = 0;
	float m[9] = {fx, ux, rx, fy, uy, ry, fz, uz, rz};
	std::memcpy(g_camera + 0x68, m, sizeof m);
	float t[3] = {35.0f, -35.0f, kGroundZ + 1.62f * 70.0f};
	std::memcpy(g_camera + 0x8C, t, sizeof t);
	float tanHalf = std::tan(35.0f * 3.14159265f / 180);
	float fr[7] = {-tanHalf * 16 / 9, tanHalf * 16 / 9, tanHalf, -tanHalf, 10.0f, 300000.0f, 0};
	std::memcpy(g_camera + 0xDC, fr, sizeof fr);
}

LRESULT CALLBACK Wnd(HWND h, UINT m, WPARAM w, LPARAM l) { return DefWindowProcA(h, m, w, l); }

static void Tap(WORD vk)
{
	keybd_event(BYTE(vk), BYTE(MapVirtualKeyA(vk, MAPVK_VK_TO_VSC)), 0, 0);
	Sleep(60);
}
static void Release(WORD vk) { keybd_event(BYTE(vk), BYTE(MapVirtualKeyA(vk, MAPVK_VK_TO_VSC)), KEYEVENTF_KEYUP, 0); }

int main(int argc, char **argv)
{
	if (argc < 4)
	{
		std::printf("usage: fakenv <plugin dll> <shared memory file or \"\"> <out dir> [frames]\n");
		return 1;
	}
	g_reserve[0] = 1;
	LOG("image reserve %p..%p, player buffer %p", (void *)g_reserve, (void *)(g_reserve + sizeof g_reserve), (void *)g_player);
	std::string outDir = argv[3];
	int frames = argc > 4 ? std::atoi(argv[4]) : 600;
	// "old": behave like xNVSE 6.3.x, which never sends kMessage_OnFramePresent
	const bool oldNvse = argc > 5 && std::string(argv[5]) == "old";
	const int W = 1280, H = 720;

	// game memory
	Page(sheets::ADDR_PLAYER_SINGLETON, 4); Put<void *>(sheets::ADDR_PLAYER_SINGLETON, g_player);
	Page(sheets::ADDR_TES_SINGLETON, 4); Put<void *>(sheets::ADDR_TES_SINGLETON, g_tes);
	Page(sheets::ADDR_SCENE_GRAPH, 4); Put<void *>(sheets::ADDR_SCENE_GRAPH, g_sceneGraph);
	Page(sheets::ADDR_DX9_RENDERER, 4); Put<void *>(sheets::ADDR_DX9_RENDERER, g_renderer);
	Page(sheets::ADDR_INPUT_GLOBALS, 4); Put<void *>(sheets::ADDR_INPUT_GLOBALS, g_input);
	Put<void *>(uintptr_t(g_sceneGraph) + 0xAC, g_camera);
	float pos[3] = {35.0f, -35.0f, kGroundZ};
	std::memcpy(g_player + 0x30, pos, sizeof pos);
	Put<void *>(uintptr_t(g_player) + 0x40, g_cell);
	Put<void *>(uintptr_t(g_cell) + 0xC0, g_worldspace);
	Put<UInt32>(uintptr_t(g_worldspace) + 0xC, 0x000DA726);
	Jump(sheets::ADDR_TES_PICK_OBJECT, reinterpret_cast<void *>(&FakePick));
	Jump(sheets::ADDR_TES_TERRAIN_HEIGHT, reinterpret_cast<void *>(&FakeTerrain));
	Jump(sheets::ADDR_MENU_MODE, reinterpret_cast<void *>(&FakeMenuMode));
	Jump(sheets::ADDR_QUEUE_UI_MESSAGE, reinterpret_cast<void *>(&FakeMessage));
	SetCamera(225.0f, 20.0f);

	// window + device
	WNDCLASSA wc = {};
	wc.lpfnWndProc = Wnd;
	wc.lpszClassName = "FakeNV";
	RegisterClassA(&wc);
	RECT rc = {0, 0, W, H};
	AdjustWindowRect(&rc, WS_OVERLAPPEDWINDOW, FALSE);
	HWND wnd = CreateWindowA("FakeNV", "Fake New Vegas", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 0, 0, rc.right - rc.left, rc.bottom - rc.top, nullptr, nullptr, nullptr, nullptr);
	SetForegroundWindow(wnd);
	IDirect3D9 *d3d = Direct3DCreate9(D3D_SDK_VERSION);
	D3DPRESENT_PARAMETERS pp = {};
	pp.BackBufferWidth = W;
	pp.BackBufferHeight = H;
	pp.BackBufferFormat = D3DFMT_X8R8G8B8;
	pp.SwapEffect = D3DSWAPEFFECT_COPY;  // the back buffer keeps what was shown, for the screenshots
	pp.Windowed = TRUE;
	pp.EnableAutoDepthStencil = TRUE;
	pp.AutoDepthStencilFormat = D3DFMT_D24S8;
	pp.hDeviceWindow = wnd;
	IDirect3DDevice9 *dev = nullptr;
	HRESULT hr = d3d ? d3d->CreateDevice(D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, wnd, D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, &dev) : E_FAIL;
	if (FAILED(hr))
	{
		LOG("FAIL no Direct3D 9 device (0x%08lX)", hr);
		return 2;
	}
	Put<void *>(uintptr_t(g_renderer) + 0x288, dev);
	Put<HWND>(uintptr_t(g_renderer) + 0x3BC, wnd);

	// Minecraft's shared memory. On Linux the dev client writes /dev/shm/OSLFrame: each frame the newest slot is copied
	// into a real named mapping, which the plugin opens like on Windows.
	HANDLE shmFile = INVALID_HANDLE_VALUE;
	uint8_t *shmView = nullptr;
	if (argv[2][0])
	{
		shmFile = CreateFileA(argv[2], GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
		uint8_t header[4096] = {};
		DWORD got = 0;
		if (shmFile != INVALID_HANDLE_VALUE)
			ReadFile(shmFile, header, sizeof header, &got, nullptr);
		long long stride = 0;
		std::memcpy(&stride, header + 16, 8);
		unsigned long long size = 4096ull + 3ull * stride;
		HANDLE mapping = got == sizeof header ? CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, DWORD(size >> 32), DWORD(size), sheets::FRAME_SHM) : nullptr;
		shmView = mapping ? static_cast<uint8_t *>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0)) : nullptr;
		LOG("shared memory %s (%llu MB) -> %s: %s", argv[2], size >> 20, sheets::FRAME_SHM, shmView ? "ok" : "FAILED");
	}
	auto copyShared = [&]() {
		if (!shmView)
			return;
		uint8_t header[4096];
		DWORD got = 0;
		LARGE_INTEGER at = {};
		SetFilePointerEx(shmFile, at, nullptr, FILE_BEGIN);
		ReadFile(shmFile, header, sizeof header, &got, nullptr);
		int slot;
		std::memcpy(&slot, header + 40, 4);
		if (slot < 0 || slot > 2)
			return;
		long long stride;
		std::memcpy(&stride, header + 16, 8);
		int w, h;
		std::memcpy(&w, header + 256 + 128 * slot + 24, 4);
		std::memcpy(&h, header + 256 + 128 * slot + 28, 4);
		at.QuadPart = 4096 + stride * slot;
		SetFilePointerEx(shmFile, at, nullptr, FILE_BEGIN);
		ReadFile(shmFile, shmView + at.QuadPart, DWORD(w) * h * 12, &got, nullptr);
		std::memcpy(shmView, header, sizeof header);  // header last: the slot is complete when the plugin sees it
	};

	// junk in the Courier's pockets (form ids written to osl_forms.ini next to the plugin by the caller)
	MakeFormMap({{0x00031944, 3}, {0x00012345, 2}});

	// load the plugin like xNVSE does
	HMODULE dll = LoadLibraryA(argv[1]);
	auto query = dll ? reinterpret_cast<bool (*)(const NVSEInterface *, PluginInfo *)>(GetProcAddress(dll, "NVSEPlugin_Query")) : nullptr;
	auto load = dll ? reinterpret_cast<bool (*)(NVSEInterface *)>(GetProcAddress(dll, "NVSEPlugin_Load")) : nullptr;
	if (!query || !load)
	{
		LOG("FAIL plugin %s: exports missing (error %lu)", argv[1], GetLastError());
		return 2;
	}
	NVSEInterface nvse = {oldNvse ? 0x06030050u : 0x06040090u, RUNTIME_VERSION_1_4_0_525, 0, 0, nullptr, nullptr, FakeQuery, FakeHandle};
	PluginInfo info = {};
	LOG("query: %d (%s)", query(&nvse, &info), info.name);
	LOG("load: %d", load(&nvse));

	// the game loop
	auto send = [](UInt32 type, void *data = nullptr, UInt32 len = 0) {
		NVSEMessagingInterface::Message m = {"NVSE", type, len, data};
		if (g_listener)
			g_listener(&m);
	};
	send(NVSEMessagingInterface::kMessage_PostLoadGame);
	for (int i = 0; i < frames; i++)
	{
		MSG msg;
		while (PeekMessageA(&msg, nullptr, 0, 0, PM_REMOVE))
			DispatchMessageA(&msg);
		if (i == 150) Tap('B');
		if (i == 152) Release('B');
		if (i == 200) Tap('2');
		if (i == 202) Release('2');
		if (i == 260) Tap(VK_RBUTTON);
		if (i == 263) Release(VK_RBUTTON);
		if (i == 330) Tap('J');
		if (i == 332) Release('J');
		copyShared();
		send(NVSEMessagingInterface::kMessage_MainGameLoop);
		DrawScene(dev, W, H);
		int loading = 0;
		if (!oldNvse)
			send(NVSEMessagingInterface::kMessage_OnFramePresent, &loading, sizeof loading);
		dev->Present(nullptr, nullptr, nullptr, nullptr);  // the plugin may composite inside Present (its hook)
		if (i == 120 || i == 240 || i == frames - 1)
			SaveBmp(dev, outDir + "\\fakenv_" + std::to_string(i) + ".bmp");
		Sleep(16);
	}
	send(NVSEMessagingInterface::kMessage_ExitGame);
	LOG("done: %u messages", unsigned(g_messages.size()));
	std::fflush(stdout);
	ExitProcess(0);
}
