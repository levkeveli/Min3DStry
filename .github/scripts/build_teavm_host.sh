#!/usr/bin/env bash
set -euo pipefail
source_file=$1
binary=$2
shift 2
objects=$(mktemp -d)
trap 'rm -rf "$objects"' EXIT
python3 research/upstream-runtime/arm11/compile_teavm.py "$source_file" "$objects/units" "$@"
# The launchers used here accept the same command for compile and link.
"$@" "$objects"/units/*.o -lm -lpthread -o "$binary"
