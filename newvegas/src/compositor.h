// Draws Minecraft's newest frame (shared memory FRAME_SHM, written by minecraft/FrameExporter.java) into New Vegas's
// picture right before it is shown: the world layer depth-tested against New Vegas's depth buffer, then the overlay
// (hand, hotbar, screens) on top.
#pragma once
#include <cstdint>

struct IDirect3DDevice9;

namespace compositor
{
struct Settings
{
	bool depthTest = true;       // write Minecraft's depth and let the GPU test it against New Vegas's
	float depthBias = 2.0f;      // game units: blocks resting on the ground still show
};

/// New Vegas's camera planes for this frame (from NiCamera's frustum), in game units.
void SetHostPlanes(float nearPlane, float farPlane);
/// Called at kMessage_OnFramePresent. `show`: the Courier is outdoors and Minecraft is attached.
void Present(IDirect3DDevice9 *device, bool show, const Settings &settings);
void Shutdown();
/// Last error, for the log ("" when fine).
const char *Status();
}  // namespace compositor
