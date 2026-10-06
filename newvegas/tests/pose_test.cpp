// Native tests for the parts of the plugin that don't need the game: camera conversion and the JSON helpers.
// g++ -std=c++20 -I../src pose_test.cpp ../src/msg.cpp -o pose_test && ./pose_test
#include <cassert>
#include <cmath>
#include <cstdio>
#include "msg.h"
#include "pose.h"

static int failures = 0;
static void check(bool ok, const char *what)
{
	std::printf("%s %s\n", ok ? "PASS" : "FAIL", what);
	failures += !ok;
}
static bool near(double a, double b, double eps = 1e-3) { return std::fabs(a - b) < eps; }

int main()
{
	const double pos[3] = {700.0, -1400.0, 140.0};
	const double t = std::tan(35.0 * 3.14159265358979 / 180.0);
	pose::Mc m;
	const double north[3] = {0, 1, 0};
	check(pose::FromCamera(pos, north, t, -t, 70.0, 64.0, m) && near(m.yaw, 180.0) && near(m.pitch, 0.0), "facing north = Minecraft yaw 180");
	check(near(m.x, 10.0) && near(m.y, 66.0) && near(m.z, 20.0), "position: x east, z up -> y, y north -> -z");
	check(near(m.fov, 70.0), "vertical FOV from the frustum");
	const double east[3] = {1, 0, 0};
	pose::FromCamera(pos, east, t, -t, 70.0, 64.0, m);
	check(near(m.yaw, -90.0), "facing east = Minecraft yaw -90");
	const double south[3] = {0, -1, 0};
	pose::FromCamera(pos, south, t, -t, 70.0, 64.0, m);
	check(near(std::fabs(m.yaw), 0.0), "facing south = Minecraft yaw 0");
	const double west[3] = {-1, 0, 0};
	pose::FromCamera(pos, west, t, -t, 70.0, 64.0, m);
	check(near(m.yaw, 90.0), "facing west = Minecraft yaw 90");
	const double down30[3] = {0, std::cos(0.5235988), -std::sin(0.5235988)};
	pose::FromCamera(pos, down30, t, -t, 70.0, 64.0, m);
	check(near(m.pitch, 30.0), "looking 30 degrees down = Minecraft pitch 30");
	const double zero[3] = {0, 0, 0};
	check(!pose::FromCamera(pos, zero, t, -t, 70.0, 64.0, m), "no direction: no pose");

	check(msg::Str("{\"t\":\"toast\",\"text\":\"Doc \\\"Mitchell\\\"\\n\"}", "text") == "Doc \"Mitchell\"\n", "msg::Str unescapes");
	check(msg::Bool("{\"t\":\"screen\",\"open\":true}", "open") && !msg::Bool("{\"open\": false}", "open"), "msg::Bool");
	auto list = msg::StrList("{\"t\":\"perks\",\"on\":[\"homestead\", \"home_sweet_home\"]}", "on");
	check(list.size() == 2 && list[1] == "home_sweet_home", "msg::StrList");
	check(msg::StrList("{\"t\":\"perks\",\"on\":[]}", "on").empty(), "msg::StrList empty");
	check(msg::Quote("a\"b\\c\n") == "\"a\\\"b\\\\c\\u000a\"", "msg::Quote escapes");
	std::printf("%s (%d failures)\n", failures ? "FAILED" : "all passed", failures);
	return failures ? 1 : 0;
}
