// New Vegas 1.4.0.525 memory: everything the plugin reads from the game, by the addresses in sheets/hooks.json.
#pragma once
#include <cstdint>
#include "nvse_api.h"

struct IDirect3DDevice9;

namespace game
{
struct Vec3
{
	float x, y, z;
};

/// The camera New Vegas just rendered with: position (game units), forward/up (unit vectors), frustum.
struct Camera
{
	Vec3 pos;
	Vec3 forward;
	Vec3 up;
	float frustumTop, frustumBottom, nearPlane, farPlane;
};

TESObjectREFR *Player();
bool PlayerPosition(Vec3 &out);
/// Exterior worldspace form ID, or 0 when the Courier is indoors or between cells.
UInt32 ExteriorWorldspace();
bool MainCamera(Camera &out);
/// Straight down from `from`, at most `range` units: the first thing hit (ignores the Courier). Falls back to terrain.
bool GroundBelow(const Vec3 &from, float range, float &zOut);
bool MenuMode();
void Message(const char *text);
TESForm *LookupForm(UInt32 formId);
IDirect3DDevice9 *Device();
void *GameWindow();
/// Mouse movement this frame (DirectInput), for Minecraft's screen cursor.
void MouseDelta(int &dx, int &dy);
}  // namespace game
