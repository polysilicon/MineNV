#include "log.h"
#include <windows.h>
#include <cstdarg>
#include <cstdio>
#include <mutex>

namespace
{
FILE *g_file = nullptr;
std::mutex g_lock;
}

void logOpen(const char *path)
{
	g_file = std::fopen(path, "w");
}

void logf(const char *fmt, ...)
{
	char buf[1024];
	va_list args;
	va_start(args, fmt);
	std::vsnprintf(buf, sizeof buf, fmt, args);
	va_end(args);
	std::lock_guard<std::mutex> lock(g_lock);
	SYSTEMTIME t;
	GetLocalTime(&t);
	if (g_file)
	{
		std::fprintf(g_file, "[%02d:%02d:%02d] %s\n", t.wHour, t.wMinute, t.wSecond, buf);
		std::fflush(g_file);
	}
	OutputDebugStringA(buf);
}
