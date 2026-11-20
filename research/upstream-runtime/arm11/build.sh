#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITARM:?Set DEVKITARM with devkitPro's 3dsvars.sh}"
: "${DEVKITPRO:?Set DEVKITPRO with devkitPro's 3dsvars.sh}"
if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <TeaVM generated C directory> <new output directory> [terrain assets directory]" >&2
    exit 2
fi
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 "$root/prepare.py" "$1" "$2"
flags=()
sources=()
libraries=()
package_flags=()
if [[ $# -eq 3 ]]; then
    flags=(-DM3DS_TERRAIN_RENDERER -I"$root" -I"$3")
    sources=("$root/m3ds_terrain_renderer.c")
    libraries=(-lcitro2d -lcitro3d)
    mkdir "$2/romfs"
    cp "$3/sprites.t3x" "$2/romfs/terrain.t3x"
    "$DEVKITPRO/tools/bin/smdhtool" --create "Min3DStry Terrain" "Mindustry v160.8 Glacier" \
        "Min3DStry Contributors" "$DEVKITPRO/libctru/default_icon.png" "$2/upstream-probe.smdh"
    package_flags=(--smdh="$2/upstream-probe.smdh" --romfs="$2/romfs")
fi
"$DEVKITARM/bin/arm-none-eabi-gcc" -std=gnu11 -g -O2 -fwrapv -D__3DS__ -mword-relocations \
    -march=armv6k -mtune=mpcore -mfpu=vfp -mfloat-abi=hard -mtp=soft \
    -I"$2/nativeinclude" -I"$DEVKITPRO/libctru/include" -L"$DEVKITPRO/libctru/lib" \
    "${flags[@]}" -specs=3dsx.specs "$2/all.c" "${sources[@]}" "${libraries[@]}" \
    -lctru -lm -o "$2/upstream-probe.elf"
"$DEVKITPRO/tools/bin/3dsxtool" "$2/upstream-probe.elf" "$2/upstream-probe.3dsx" "${package_flags[@]}"
