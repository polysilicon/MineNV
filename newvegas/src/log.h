// The plugin's log: Data/NVSE/Plugins/osl.log (first lines of every session; also mirrored to OutputDebugString).
#pragma once
void logOpen(const char *path);
void logf(const char *fmt, ...);
