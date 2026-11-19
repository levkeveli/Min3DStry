#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

find_host_cc() {
  if command -v cc >/dev/null 2>&1; then
    echo "cc"
  elif command -v gcc >/dev/null 2>&1; then
    echo "gcc"
  elif command -v clang >/dev/null 2>&1; then
    echo "clang"
  else
    return 1
  fi
}

has_native_devkitarm() {
  if [[ -n "${DEVKITARM:-}" && -f "${DEVKITARM}/3ds_rules" ]]; then
    return 0
  fi
  if [[ -f "/opt/devkitpro/3dsvars.sh" ]]; then
    return 0
  fi
  return 1
}

has_docker_devkitarm() {
  command -v docker >/dev/null 2>&1 &&
    docker info >/dev/null 2>&1 &&
    docker image inspect devkitpro/devkitarm:latest >/dev/null 2>&1
}

case "${1:-}" in
  doctor)
    git --version
    bash --version | head -n 1
    if command -v python3 >/dev/null 2>&1; then
      python3 --version
    else
      echo 'python3 unavailable on PATH.'
    fi
    if host_cc="$(find_host_cc)"; then
      "$host_cc" --version | head -n 1
    else
      echo 'Host C compiler unavailable on PATH.'
    fi
    if command -v arm-none-eabi-gcc >/dev/null 2>&1; then
      arm-none-eabi-gcc --version | head -n 1
    elif has_docker_devkitarm; then
      echo 'ARM toolchain: available via Docker (devkitpro/devkitarm:latest).'
    else
      echo 'ARM toolchain unavailable on PATH (and no local devkitpro/devkitarm:latest Docker image).'
    fi
    if command -v azahar >/dev/null 2>&1; then
      echo "Emulator: $(command -v azahar)"
    elif command -v flatpak >/dev/null 2>&1 && flatpak info org.azahar_emu.Azahar >/dev/null 2>&1; then
      echo 'Emulator: org.azahar_emu.Azahar (Flatpak)'
    else
      echo '3DS emulator (azahar) not installed.'
    fi
    ;;
  build)
    if has_native_devkitarm; then
      if [[ -z "${DEVKITARM:-}" && -f "/opt/devkitpro/3dsvars.sh" ]]; then
        # shellcheck disable=SC1091
        source /opt/devkitpro/3dsvars.sh
      fi
      make
    elif has_docker_devkitarm; then
      docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work devkitpro/devkitarm:latest \
        bash -lc 'source /opt/devkitpro/3dsvars.sh && make'
    else
      echo 'build: devkitARM is not installed locally and devkitpro/devkitarm:latest is unavailable in Docker.' >&2
      exit 2
    fi
    ;;
  test)
    bash -n dev.sh
    for file in README.md AGENTS.md CONTRIBUTING.md SECURITY.md docs/architecture.md docs/development/automation-policy.md docs/rendering/citro2d-prototype.md site/index.html .github/scripts/pr_automation.py .github/scripts/test_pr_automation.py .github/workflows/ai-pr-review.yml Makefile include/render/scene_2d.h src/render/scene_2d.c tests/render/test_scene_2d.c; do
      test -s "$file" || { echo "Missing or empty: $file" >&2; exit 1; }
    done
    python3 -m py_compile .github/scripts/pr_automation.py .github/scripts/test_pr_automation.py
    python3 -m unittest discover -s .github/scripts -p 'test_*.py'

    host_cc="$(find_host_cc)" || { echo 'test: no host C compiler (cc/gcc/clang) found.' >&2; exit 1; }
    mkdir -p build/host
    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/render/scene_2d.c tests/render/test_scene_2d.c -lm -o build/host/test_scene_2d
    ./build/host/test_scene_2d

    git diff --check HEAD
    echo 'All host and bootstrap checks passed.'
    ;;
  run)
    test -s build/min3dstry.3dsx || { echo 'run: build/min3dstry.3dsx missing; run ./dev.sh build first.' >&2; exit 2; }
    if command -v azahar >/dev/null 2>&1; then
      exec azahar build/min3dstry.3dsx
    elif command -v flatpak >/dev/null 2>&1 && flatpak info org.azahar_emu.Azahar >/dev/null 2>&1; then
      exec flatpak run org.azahar_emu.Azahar build/min3dstry.3dsx
    else
      echo 'run: no 3DS emulator (azahar) is installed.' >&2
      exit 2
    fi
    ;;
  status)
    git status --short --branch
    ;;
  device|package)
    echo "${1}: hardware deployment and packaging are pending." >&2
    exit 2
    ;;
  *)
    echo 'Usage: ./dev.sh doctor|build|test|run|device|package|status' >&2
    exit 2
    ;;
esac
