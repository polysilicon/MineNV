#!/bin/bash
# Cross-compile the plugin on Linux: clang-cl + lld-link against Microsoft's CRT and Windows SDK fetched by xwin
# (cargo install xwin; xwin --accept-license --arch x86 splat --output /opt/xwin). Windows/CI uses CMakeLists.txt.
set -euo pipefail
cd "$(dirname "$0")"
X=${XWIN:-/opt/xwin}
mkdir -p build
FLAGS=(--target=i686-pc-windows-msvc /nologo /O2 /EHsc /std:c++20 /MT /W3 /DWIN32_LEAN_AND_MEAN /DNOMINMAX
  -D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH -D_CRT_SECURE_NO_WARNINGS -Wno-multichar
  -imsvc "$X/crt/include" -imsvc "$X/sdk/include/ucrt" -imsvc "$X/sdk/include/um" -imsvc "$X/sdk/include/shared")
OBJS=()
for f in src/*.cpp; do
  o=build/$(basename "${f%.cpp}").obj
  clang-cl "${FLAGS[@]}" /c "$f" /Fo"$o"
  OBJS+=("$o")
done
lld-link /nologo /dll /machine:x86 /out:build/OverworldSupplyLine.dll "${OBJS[@]}" \
  /libpath:"$X/crt/lib/x86" /libpath:"$X/sdk/lib/um/x86" /libpath:"$X/sdk/lib/ucrt/x86" \
  ws2_32.lib d3d9.lib user32.lib kernel32.lib
echo "built build/OverworldSupplyLine.dll"
