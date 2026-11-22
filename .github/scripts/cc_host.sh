#!/usr/bin/env bash
# Compile generated TeaVM C. Prefer clang; GCC 13 needs
# -fno-tree-coalesce-vars to avoid an SSA ICE in TypeIO.c on large units.
set -euo pipefail
if command -v clang >/dev/null 2>&1; then
    exec clang "$@"
fi
if cc --version 2>&1 | grep -qi 'gcc'; then
    exec cc -fno-tree-coalesce-vars "$@"
fi
exec cc "$@"
