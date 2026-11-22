#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITARM:?Set DEVKITARM with devkitPro's 3dsvars.sh}"
: "${DEVKITPRO:?Set DEVKITPRO with devkitPro's 3dsvars.sh}"
if [[ $# -lt 2 || $# -gt 4 ]]; then
    echo "Usage: $0 <TeaVM generated C directory> <new output directory> [--network|--singleplayer|--unified|--save-rows] [terrain assets directory]" >&2
    exit 2
fi
arm11_opt="${M3DS_ARM11_OPT:--Os}"
case "$arm11_opt" in
    -O0|-O1|-O2|-O3|-Os|-Og) ;;
    *)
        echo "Unsupported M3DS_ARM11_OPT: $arm11_opt (use -O0, -O1, -O2, -O3, -Os, or -Og)" >&2
        exit 2
        ;;
esac
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
output="$2"
python3 "$root/prepare.py" "$1" "$output"
shift 2
native_network=()
native_input=()
title="Min3DStry Terrain"
subtitle="Mindustry v160.8 Glacier"
mode=${1:-}
if [[ $mode == --network || $mode == --unified ]]; then
    title="Min3DStry"
    repo="$root/../../.."
    native_network=(-I"$root" -I"$repo/include" -I"$repo/src/platform/3ds"
        "$root/m3ds_net_bridge.c" "$repo/src/net/mindustry_net.c" "$repo/src/platform/3ds/net_3ds.c")
fi
if [[ $mode == --singleplayer || $mode == --unified ]]; then
    title="Min3DStry"
    subtitle="Mindustry v160.8 Ground Zero"
    native_input=(-I"$root" "$root/m3ds_input.c")
fi
if [[ $mode == --unified ]]; then
    subtitle="Mindustry v160.8"
    native_input+=("$root/m3ds_server_address.c")
fi
if [[ $mode == --network || $mode == --singleplayer || $mode == --unified ]]; then
    shift
fi
if [[ $mode == --unified && $# == 0 ]]; then
    echo "Unified builds require the terrain assets directory" >&2
    exit 2
fi
save_rows_flags=()
save_rows_sources=()
if [[ ${1:-} == --save-rows ]]; then
    repo="$root/../../.."
    save_rows_flags=(-I"$root" -I"$repo/include")
    save_rows_sources=("$root/m3ds_save_rows.c"
        "$repo/src/platform/world_rows.c" "$repo/src/platform/msav_world.c")
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
    if [[ ${#native_input[@]} -gt 0 && ! -d "$1/maps/default" ]]; then
        echo "Prepare singleplayer terrain assets with --singleplayer to include the original Custom Game maps" >&2
        exit 2
    fi
    if [[ ${#native_input[@]} -gt 0 && ! -s "$1/planets/serpulo.json" ]]; then
        echo "Prepare singleplayer terrain assets with --singleplayer to include the original planet data" >&2
        exit 2
    fi
    if [[ ( ${#native_network[@]} -gt 0 || ${#native_input[@]} -gt 0 ) &&
          ( ! -f "$1/bundles/bundle.properties" || ! -d "$1/world-atlas" || ! -f "$1/block_colors.png" ) ]]; then
        echo "Prepare multiplayer or singleplayer terrain assets to include the original bundles, atlas and block palette" >&2
        exit 2
    fi
    if [[ ${#native_input[@]} -gt 0 && ( ! -f "$1/sprites.aatls" || ! -f "$1/maps/groundZero.msav" ) ]]; then
        echo "Prepare singleplayer terrain assets with --singleplayer to include sprites.aatls and maps/groundZero.msav" >&2
        exit 2
    fi
    terrain_flags=(-DM3DS_TERRAIN_RENDERER -I"$root" -I"$1")
    terrain_sources=("$root/m3ds_terrain_renderer.c")
    terrain_libraries=(-lcitro2d -lcitro3d)
    mkdir "$output/romfs"
    cp "$1/sprites.t3x" "$output/romfs/terrain.t3x"
    if [[ -f "$1/sprites.aatls" ]]; then
        cp "$1/sprites.aatls" "$output/romfs/sprites.aatls"
    fi
    if [[ -d "$1/maps" ]]; then
        cp -R "$1/maps" "$output/romfs/maps"
    fi
    if [[ -d "$1/planets" ]]; then
        cp -R "$1/planets" "$output/romfs/planets"
    fi
    if [[ -d "$1/bundles" ]]; then
        cp -R "$1/bundles" "$output/romfs/bundles"
    fi
    if [[ -f "$1/block_colors.png" ]]; then
        cp "$1/block_colors.png" "$output/romfs/block_colors.png"
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
        cp -R "$1/world-atlas" "$output/romfs/world-atlas"
    fi
    "$DEVKITPRO/tools/bin/smdhtool" --create "$title" "$subtitle" \
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
"$DEVKITARM/bin/arm-none-eabi-gcc" -std=gnu11 -g "$arm11_opt" -fwrapv -D__3DS__ -mword-relocations \
    -march=armv6k -mtune=mpcore -mfpu=vfp -mfloat-abi=hard -mtp=soft \
    -I"$output/nativeinclude" -I"$DEVKITPRO/libctru/include" -L"$DEVKITPRO/libctru/lib" \
    "${terrain_flags[@]}" "${save_rows_flags[@]}" "${pointer_flags[@]}" \
    -specs=3dsx.specs "$output/all.c" "${native_network[@]}" "${native_input[@]}" \
    "${terrain_sources[@]}" "${save_rows_sources[@]}" \
    "${terrain_libraries[@]}" -lctru -lm -o "$output/upstream-probe.elf"
"$DEVKITPRO/tools/bin/3dsxtool" "$output/upstream-probe.elf" "$output/upstream-probe.3dsx" "${package_flags[@]}"
