// New Vegas camera -> Minecraft camera. Pure maths (tested by tests/pose_test.cpp without the game).
// New Vegas: X east, Y north, Z up, game units. Minecraft: x east, y up, z south, blocks.
#pragma once
#include <algorithm>
#include <cmath>

namespace pose
{
struct Mc
{
	double x, y, z;      // eye position
	double yaw, pitch;   // Minecraft degrees: yaw 0 faces +Z (south), pitch positive looks down
	double fov;          // vertical, degrees
};

inline void ToBlocks(double nx, double ny, double nz, double unitsPerBlock, double yOffset, double &x, double &y, double &z)
{
	x = nx / unitsPerBlock;
	y = nz / unitsPerBlock + yOffset;
	z = -ny / unitsPerBlock;
}

/// forward: New Vegas look direction; top/bottom: the camera frustum's tangents (NiFrustum, perspective).
inline bool FromCamera(const double pos[3], const double forward[3], double top, double bottom, double unitsPerBlock,
	double yOffset, Mc &out)
{
	double fx = forward[0], fy = forward[1], fz = forward[2];
	double len = std::sqrt(fx * fx + fy * fy + fz * fz);
	if (len < 1e-6 || !(top > bottom))
		return false;
	fx /= len;
	fy /= len;
	fz /= len;
	const double deg = 180.0 / 3.14159265358979323846;
	ToBlocks(pos[0], pos[1], pos[2], unitsPerBlock, yOffset, out.x, out.y, out.z);
	out.pitch = std::asin(std::clamp(-fz, -1.0, 1.0)) * deg;
	out.yaw = std::atan2(-fx, -fy) * deg;
	if (out.yaw <= -180.0)
		out.yaw += 360.0;  // (-180, 180]: due north is 180, never -180
	out.fov = (std::atan(top) - std::atan(bottom)) * deg;
	return true;
}
}  // namespace pose
