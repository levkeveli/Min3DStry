#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITARM:?Set DEVKITARM with devkitPro's 3dsvars.sh}"
: "${DEVKITPRO:?Set DEVKITPRO with devkitPro's 3dsvars.sh}"
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <TeaVM generated C directory> <new output directory>" >&2
    exit 2
fi
generated="$1"
output="$2"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$root/research/upstream-runtime/arm11/prepare.py" "$generated" "$output" --embed

flags=(-std=gnu11 -g -Os -fwrapv -D__3DS__ -mword-relocations
    -march=armv6k -mtune=mpcore -mfpu=vfp -mfloat-abi=hard -mtp=soft
    -I"$output/nativeinclude" -I"$DEVKITPRO/libctru/include"
    -I"$root/research/upstream-runtime/arm11")
"$DEVKITARM/bin/arm-none-eabi-gcc" "${flags[@]}" \
    -c "$output/all.c" -o "$output/all.o"
"$DEVKITARM/bin/arm-none-eabi-gcc" "${flags[@]}" -I"$root/include" \
    -c "$root/research/upstream-runtime/arm11/m3ds_save_rows.c" \
    -o "$output/m3ds_save_rows.o"
"$DEVKITARM/bin/arm-none-eabi-ar" rcs "$output/libm3dsloader.a" \
    "$output/all.o" "$output/m3ds_save_rows.o"
ls -l "$output/libm3dsloader.a"
