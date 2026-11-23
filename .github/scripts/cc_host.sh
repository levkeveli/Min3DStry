#!/usr/bin/env bash
# Compile generated TeaVM C. Prefer clang; GCC 13 needs
# -fno-tree-coalesce-vars to avoid an SSA ICE in TypeIO.c on large units.
set -euo pipefail
if command -v clang >/dev/null 2>&1; then
    exec clang "$@"
fi
if ! command -v cc >/dev/null 2>&1; then
    echo "cc_host.sh: neither clang nor cc is available on PATH" >&2
    exit 127
fi
if cc -fno-tree-coalesce-vars -E -x c /dev/null >/dev/null 2>&1; then
    exec cc -fno-tree-coalesce-vars "$@"
fi
exec cc "$@"
