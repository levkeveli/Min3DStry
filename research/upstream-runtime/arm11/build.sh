#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITARM:?Set DEVKITARM with devkitPro's 3dsvars.sh}"
: "${DEVKITPRO:?Set DEVKITPRO with devkitPro's 3dsvars.sh}"
if [[ $# -lt 2 || $# -gt 4 ]]; then
    echo "Usage: $0 <TeaVM generated C directory> <new output directory> [--network] [terrain assets directory]" >&2
    exit 2
fi
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
output="$2"
python3 "$root/prepare.py" "$1" "$output"
shift 2
native_network=()
title="Min3DStry Terrain"
if [[ ${1:-} == --network ]]; then
    title="Min3DStry"
    repo="$root/../../.."
    native_network=(-I"$root" -I"$repo/include" -I"$repo/src/platform/3ds"
        "$root/m3ds_net_bridge.c" "$root/m3ds_live_audio.c"
        "$repo/src/net/mindustry_net.c" "$repo/src/platform/3ds/net_3ds.c")
    shift
fi
terrain_flags=()
terrain_sources=()
terrain_libraries=()
package_flags=()
if [[ $# -gt 0 ]]; then
    if [[ $# -ne 1 || ! -d "$1" ]]; then
        echo "Expected one terrain assets directory" >&2
        exit 2
    fi
    if [[ ${#native_network[@]} -gt 0 &&
          ( ! -f "$1/bundles/bundle.properties" || ! -d "$1/world-atlas"
            || ! -f "$1/block_colors.png" || ! -f "$1/logicids.dat" || ! -f "$1/logic-font.dat" ) ]]; then
        echo "Prepare multiplayer terrain assets with --multiplayer to include the original bundles, atlas, block palette, logic IDs and font" >&2
        exit 2
    fi
    terrain_flags=(-DM3DS_TERRAIN_RENDERER -I"$root" -I"$1")
    terrain_sources=("$root/m3ds_terrain_renderer.c")
    terrain_libraries=(-lcitro2d -lcitro3d)
    mkdir "$output/romfs"
    if [[ ${#native_network[@]} -gt 0 && -f "$1/sound-index.h" && -d "$1/audio" ]]; then
        terrain_flags+=(-DM3DS_LIVE_AUDIO)
        terrain_sources+=("$repo/src/audio/audio_mixer.c" "$repo/src/audio/audio_registry.c"
            "$repo/src/platform/3ds/ndsp_backend.c")
        cp -R "$1/audio" "$output/romfs/audio"
    fi
    cp "$1/sprites.t3x" "$output/romfs/terrain.t3x"
    if [[ -d "$1/bundles" ]]; then
        cp -R "$1/bundles" "$output/romfs/bundles"
    fi
    if [[ -f "$1/block_colors.png" ]]; then
        cp "$1/block_colors.png" "$output/romfs/block_colors.png"
    fi
    if [[ -f "$1/logicids.dat" ]]; then
        cp "$1/logicids.dat" "$output/romfs/logicids.dat"
    fi
    if [[ -f "$1/logic-font.dat" ]]; then
        cp "$1/logic-font.dat" "$output/romfs/logic-font.dat"
    fi
    if [[ -d "$1/catalog" ]]; then
        cp -R "$1/catalog" "$output/romfs/catalog"
    fi
    if [[ -d "$1/fonts" && -d "$1/icons" ]]; then
        cp -R "$1/fonts" "$1/icons" "$output/romfs/"
        portlibs="$DEVKITPRO/portlibs/3ds"
        terrain_flags+=(-DM3DS_STOCK_FONT -I"$portlibs/include/freetype2" -I"$portlibs/include" -L"$portlibs/lib")
        terrain_sources+=("$root/m3ds_stock_font.c" "$root/m3ds_stock_text.c")
        terrain_libraries+=(-lfreetype -lpng -lbz2 -lz)
    fi
    if [[ -d "$1/world-atlas" ]]; then
        terrain_flags+=(-DM3DS_WORLD_ATLAS)
        "$DEVKITPRO/tools/bin/picasso" -o "$output/m3ds_quad.shbin" "$root/m3ds_quad.v.pica"
        "$DEVKITPRO/tools/bin/bin2s" -a 4 -H "$output/nativeinclude/m3ds_quad_shbin.h" \
            "$output/m3ds_quad.shbin" > "$output/m3ds_quad_shader.s"
        terrain_sources+=("$output/m3ds_quad_shader.s")
        cp -R "$1/world-atlas" "$output/romfs/world-atlas"
    fi
    "$DEVKITPRO/tools/bin/smdhtool" --create "$title" "Mindustry v160.8 Glacier" \
        "Min3DStry Contributors" "$DEVKITPRO/libctru/default_icon.png" "$output/upstream-probe.smdh"
    package_flags=(--smdh="$output/upstream-probe.smdh" --romfs="$output/romfs")
fi
# TODO: remove once TeaVM 0.16 C generates matching object-reference casts.
# Scoped opt-in: only probes that need it set M3DS_RELAX_POINTER_TYPES=1.
# Never set it globally; unrelated probes must keep the pointer-type diagnostic.
pointer_flags=()
if [[ "${M3DS_RELAX_POINTER_TYPES:-0}" == "1" ]]; then
    pointer_flags=(-Wno-error=incompatible-pointer-types)
fi
"$DEVKITARM/bin/arm-none-eabi-gcc" -std=gnu11 -g -O2 -fwrapv -D__3DS__ -mword-relocations \
    -march=armv6k -mtune=mpcore -mfpu=vfp -mfloat-abi=hard -mtp=soft \
    -I"$output/nativeinclude" -I"$DEVKITPRO/libctru/include" -L"$DEVKITPRO/libctru/lib" \
    "${terrain_flags[@]}" "${pointer_flags[@]}" -specs=3dsx.specs "$output/all.c" "${native_network[@]}" \
    "${terrain_sources[@]}" "${terrain_libraries[@]}" -lctru -lm -o "$output/upstream-probe.elf"
"$DEVKITPRO/tools/bin/3dsxtool" "$output/upstream-probe.elf" "$output/upstream-probe.3dsx" "${package_flags[@]}"
