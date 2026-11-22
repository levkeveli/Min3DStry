#!/usr/bin/env bash
# Print the host C compiler for generated TeaVM C.
# Prefer clang when available: GCC 13 can ICE with "SSA corruption" in
# TypeIO.c on large generated translation units.
set -euo pipefail
if command -v clang >/dev/null 2>&1; then
    printf '%s\n' clang
else
    printf '%s\n' cc
fi
