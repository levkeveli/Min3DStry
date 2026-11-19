#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

case "${1:-}" in
  doctor)
    git --version
    bash --version | head -n 1
    if command -v arm-none-eabi-gcc >/dev/null; then
      arm-none-eabi-gcc --version | head -n 1
    else
      echo 'ARM toolchain unavailable on PATH.'
    fi
    ;;
  test)
    bash -n dev.sh
    for file in README.md AGENTS.md CONTRIBUTING.md SECURITY.md docs/architecture.md docs/development/automation-policy.md site/index.html; do
      test -s "$file" || { echo "Missing or empty: $file" >&2; exit 1; }
    done
    git diff --check HEAD
    echo 'Bootstrap checks passed.'
    ;;
  status)
    git status --short --branch
    ;;
  build|run|device|package)
    echo "${1}: the native project and toolchain integration are pending." >&2
    exit 2
    ;;
  *)
    echo 'Usage: ./dev.sh doctor|build|test|run|device|package|status' >&2
    exit 2
    ;;
esac
