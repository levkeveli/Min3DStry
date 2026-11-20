#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITARM:?Set DEVKITARM with devkitPro's 3dsvars.sh}"
: "${DEVKITPRO:?Set DEVKITPRO with devkitPro's 3dsvars.sh}"
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <TeaVM generated C directory> <new output directory>" >&2
    exit 2
fi
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 "$root/prepare.py" "$1" "$2"
"$DEVKITARM/bin/arm-none-eabi-gcc" -std=gnu11 -g -O2 -D__3DS__ -mword-relocations \
    -march=armv6k -mtune=mpcore -mfpu=vfp -mfloat-abi=hard -mtp=soft \
    -I"$2/nativeinclude" -I"$DEVKITPRO/libctru/include" -L"$DEVKITPRO/libctru/lib" \
    -specs=3dsx.specs "$2/all.c" -lctru -lm -o "$2/upstream-probe.elf"
"$DEVKITPRO/tools/bin/3dsxtool" "$2/upstream-probe.elf" "$2/upstream-probe.3dsx"
