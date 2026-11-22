#!/usr/bin/env bash
# Print the host C compiler for generated TeaVM C.
# GCC 13 can ICE with "SSA corruption" in TypeIO.c on large generated
# translation units, so require clang instead of silently falling back to it.
set -euo pipefail
if command -v clang >/dev/null 2>&1; then
    printf '%s\n' clang
else
    echo "cc_host.sh: clang is required for generated TeaVM C; GCC 13 hits a TypeIO.c SSA ICE" >&2
    exit 1
fi
