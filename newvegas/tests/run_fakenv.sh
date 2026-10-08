#!/bin/bash
# Build fakenv.exe (a stand-in for New Vegas that loads the real plugin DLL) and run it under Wine against the
# Minecraft dev client (cd minecraft && OSL_DEV_NO_SRGB=true ./gradlew runClient on an X display with GLX).
# Frames the plugin composited are saved to $OUT as BMP; the plugin's own log is $OUT/osl.log.
set -euo pipefail
cd "$(dirname "$0")/.."
X=${XWIN:-/opt/xwin}
OUT=${OUT:-$(mktemp -d)}
F=(--target=i686-pc-windows-msvc /nologo /O1 /EHsc /std:c++20 /MT -D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH -DWIN32_LEAN_AND_MEAN
  -DNOMINMAX -Wno-multichar -imsvc "$X/crt/include" -imsvc "$X/sdk/include/ucrt" -imsvc "$X/sdk/include/um" -imsvc "$X/sdk/include/shared")
bash build.sh >/dev/null
clang-cl "${F[@]}" /c tests/fakenv.cpp /Fobuild/fakenv.obj
lld-link /nologo /machine:x86 /subsystem:console /base:0x400000 /fixed /largeaddressaware /out:build/fakenv.exe build/fakenv.obj \
  /libpath:"$X/crt/lib/x86" /libpath:"$X/sdk/lib/um/x86" /libpath:"$X/sdk/lib/ucrt/x86" d3d9.lib user32.lib kernel32.lib
cp build/fakenv.exe build/OverworldSupplyLine.dll "$OUT/"
printf 'MISC|Scrap Metal=00031944\nMISC|Tin Can=00012345\n' > "$OUT/osl_forms.ini"
cd "$OUT"
WINEDEBUG=-all wine fakenv.exe "Z:$OUT/OverworldSupplyLine.dll" 'Z:\dev\shm\OSLFrame' "Z:$OUT" "${FRAMES:-420}" "${NVSE:-new}"
echo "frames and osl.log in $OUT"
