#include "game.h"
#include "sheets_gen.h"
#include <cmath>
#include <cstring>
#include <initializer_list>

namespace
{
template <typename T>
T Read(uintptr_t address)
{
	return *reinterpret_cast<T *>(address);
}

uintptr_t Ptr(uintptr_t address)
{
	return address ? *reinterpret_cast<uintptr_t *>(address) : 0;
}

// TES::PickObject's input/output (0xB0 bytes, 16-aligned): JIP LN's RayCastData.
struct alignas(16) RayCastData
{
	float pos0[4];         // 00 start, Havok units
	float pos1[4];         // 10 end, Havok units
	UInt8 byte20;          // 20
	UInt8 pad21[3];        // 21
	UInt32 layerInfo;      // 24 layer type (low byte) + filter flags + group
	UInt32 unk28[6];       // 28
	float hitFraction;     // 40 0..1 along the ray
	UInt32 unk44[15];      // 44
	void *cdBody;          // 80
	UInt32 unk84[3];       // 84
	float vector90[4];     // 90
	UInt32 unkA0[3];       // A0
	UInt8 byteAC;          // AC
	UInt8 padAD[3];        // AD
};
static_assert(sizeof(RayCastData) == 0xB0, "RayCastData layout");

constexpr float kHavokScale = 0.142857f;  // game units -> Havok (JIP kUnitConv 0x3E124DD2)
constexpr UInt32 kRayLayer = 6;            // JIP GetRayCastPos's default layer

using PickObject_t = void *(__thiscall *)(void *tes, RayCastData *data, bool unk);
using TerrainHeight_t = bool(__thiscall *)(void *tes, float *posXY, float *result);
using QueueUIMessage_t = bool(__cdecl *)(const char *text, UInt32 icon, const char *iconPath, const char *sound, float seconds, UInt8 unk);
using MenuMode_t = bool(__cdecl *)();
}  // namespace

namespace game
{
TESObjectREFR *Player()
{
	return reinterpret_cast<TESObjectREFR *>(Ptr(sheets::ADDR_PLAYER_SINGLETON));
}

bool PlayerPosition(Vec3 &out)
{
	uintptr_t p = reinterpret_cast<uintptr_t>(Player());
	if (!p)
		return false;
	out = Read<Vec3>(p + 0x30);  // TESObjectREFR::position
	return true;
}

UInt32 ExteriorWorldspace()
{
	uintptr_t p = reinterpret_cast<uintptr_t>(Player());
	uintptr_t cell = p ? Ptr(p + sheets::ADDR_REF_PARENT_CELL) : 0;
	if (!cell || (Read<UInt8>(cell + sheets::ADDR_CELL_FLAGS) & 1))
		return 0;
	uintptr_t ws = Ptr(cell + sheets::ADDR_CELL_WORLDSPACE);
	return ws ? Read<UInt32>(ws + sheets::ADDR_FORM_REFID) : 0;
}

bool MainCamera(Camera &out)
{
	uintptr_t sceneGraph = Ptr(sheets::ADDR_SCENE_GRAPH);
	uintptr_t cam = sceneGraph ? Ptr(sceneGraph + sheets::ADDR_SCENE_GRAPH_CAMERA) : 0;
	if (!cam)
		return false;
	const float *r = reinterpret_cast<const float *>(cam + sheets::ADDR_CAMERA_WORLD_ROTATE);  // row-major 3x3
	const float *t = reinterpret_cast<const float *>(cam + sheets::ADDR_CAMERA_WORLD_TRANSLATE);
	const float *f = reinterpret_cast<const float *>(cam + sheets::ADDR_CAMERA_FRUSTUM);       // l, r, t, b, n, f
	// a Gamebryo camera looks down its local +X with +Y up: the rotation's first and second columns
	out.pos = {t[0], t[1], t[2]};
	out.forward = {r[0], r[3], r[6]};
	out.up = {r[1], r[4], r[7]};
	out.frustumTop = f[2];
	out.frustumBottom = f[3];
	out.nearPlane = f[4];
	out.farPlane = f[5];
	return std::isfinite(out.pos.x) && out.frustumTop > out.frustumBottom && out.nearPlane > 0.0f;
}

bool GroundBelow(const Vec3 &from, float range, float &zOut)
{
	uintptr_t player = reinterpret_cast<uintptr_t>(Player());
	void *tes = reinterpret_cast<void *>(Ptr(sheets::ADDR_TES_SINGLETON));
	if (!player || !tes)
		return false;

	RayCastData rc;
	std::memset(&rc, 0, sizeof rc);
	rc.pos0[0] = from.x * kHavokScale;
	rc.pos0[1] = from.y * kHavokScale;
	rc.pos0[2] = from.z * kHavokScale;
	rc.pos1[0] = rc.pos0[0];
	rc.pos1[1] = rc.pos0[1];
	rc.pos1[2] = (from.z - range) * kHavokScale;
	rc.hitFraction = 1.0f;
	rc.unk44[0] = 0xFFFFFFFF;  // 44
	rc.unk44[3] = 0xFFFFFFFF;  // 50
	// the Courier's own collision group, so the ray passes through the Courier (JIP: player+0x68 -> ... -> +0x2C)
	uintptr_t chain = Ptr(player + sheets::ADDR_RAY_FILTER_CHAIN);
	for (uintptr_t off : {0x138u, 0x594u, 0x8u})
		chain = chain ? Ptr(chain + off) : 0;
	UInt32 group = chain ? Read<UInt32>(chain + 0x2C) : 0;
	rc.layerInfo = (group & 0xFFFF0000u) | kRayLayer;

	auto pick = reinterpret_cast<PickObject_t>(sheets::ADDR_TES_PICK_OBJECT);
	if (pick(tes, &rc, true) && rc.hitFraction < 1.0f)
	{
		zOut = (rc.pos0[2] + (rc.pos1[2] - rc.pos0[2]) * rc.hitFraction) / kHavokScale;
		return true;
	}

	float xy[2] = {from.x, from.y};
	float z = 0.0f;
	auto terrain = reinterpret_cast<TerrainHeight_t>(sheets::ADDR_TES_TERRAIN_HEIGHT);
	if (terrain(tes, xy, &z) && z <= from.z && z >= from.z - range)
	{
		zOut = z;
		return true;
	}
	return false;
}

bool MenuMode()
{
	return reinterpret_cast<MenuMode_t>(sheets::ADDR_MENU_MODE)();
}

void Message(const char *text)
{
	reinterpret_cast<QueueUIMessage_t>(sheets::ADDR_QUEUE_UI_MESSAGE)(text, 0, nullptr, nullptr, 3.0f, 0);
}

TESForm *LookupForm(UInt32 formId)
{
	// NiTPointerMap: +4 bucket count, +8 buckets; entries: next, key, value
	uintptr_t map = Ptr(sheets::ADDR_FORM_MAP);
	if (!map || !formId)
		return nullptr;
	UInt32 buckets = Read<UInt32>(map + 4);
	uintptr_t entry = buckets ? Ptr(Ptr(map + 8) + 4 * (formId % buckets)) : 0;
	for (; entry; entry = Ptr(entry))
		if (Read<UInt32>(entry + 4) == formId)
			return reinterpret_cast<TESForm *>(Ptr(entry + 8));
	return nullptr;
}

IDirect3DDevice9 *Device()
{
	uintptr_t renderer = Ptr(sheets::ADDR_DX9_RENDERER);
	return renderer ? reinterpret_cast<IDirect3DDevice9 *>(Ptr(renderer + sheets::ADDR_DX9_DEVICE_OFFSET)) : nullptr;
}

void *GameWindow()
{
	uintptr_t renderer = Ptr(sheets::ADDR_DX9_RENDERER);
	return renderer ? reinterpret_cast<void *>(Ptr(renderer + sheets::ADDR_RENDERER_WINDOW)) : nullptr;
}

void MouseDelta(int &dx, int &dy)
{
	uintptr_t input = Ptr(sheets::ADDR_INPUT_GLOBALS);
	dx = input ? Read<int>(input + 0x1B24) : 0;
	dy = input ? Read<int>(input + 0x1B28) : 0;
}
}  // namespace game

namespace game
{
int MouseWheel()
{
	uintptr_t input = Ptr(sheets::ADDR_INPUT_GLOBALS);
	return input ? Read<int>(input + sheets::ADDR_MOUSE_WHEEL) : 0;
}
}  // namespace game

namespace
{
void SetHidden(uintptr_t node, bool hide)
{
	if (!node)
		return;
	uint32_t &flags = *reinterpret_cast<uint32_t *>(node + sheets::ADDR_NIAV_FLAGS);
	flags = hide ? (flags | 1u) : (flags & ~1u);
}
using ToggleFirstPerson_t = bool(__thiscall *)(void *player, bool toggleOn);
}  // namespace

namespace game
{
void HideHud(bool hide)
{
	uintptr_t hud = Ptr(sheets::ADDR_HUD_MENU);
	uintptr_t tile = hud ? Ptr(hud + 0x04) : 0;
	SetHidden(tile ? Ptr(tile + 0x2C) : 0, hide);
}

void HideFirstPersonBody(bool hide)
{
	uintptr_t p = reinterpret_cast<uintptr_t>(Player());
	SetHidden(p ? Ptr(p + sheets::ADDR_PLAYER_NODE_1ST) : 0, hide);
}

void KeepFirstPerson()
{
	uintptr_t p = reinterpret_cast<uintptr_t>(Player());
	if (p && Read<uint8_t>(p + sheets::ADDR_PLAYER_THIRD_PERSON))
		reinterpret_cast<ToggleFirstPerson_t>(sheets::ADDR_TOGGLE_FIRST_PERSON)(reinterpret_cast<void *>(p), true);
}
}  // namespace game
