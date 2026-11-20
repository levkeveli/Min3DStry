#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

find_host_cc() {
  if [[ -n "${CC:-}" ]]; then
    command -v "$CC"
  elif command -v cc >/dev/null 2>&1; then
    echo "cc"
  elif command -v gcc >/dev/null 2>&1; then
    echo "gcc"
  elif command -v clang >/dev/null 2>&1; then
    echo "clang"
  else
    return 1
  fi
}

find_host_python() {
  local candidate
  local -a candidates=(python3 python)
  if [[ -n "${PYTHON:-}" ]]; then
    candidates=("$PYTHON")
  fi
  for candidate in "${candidates[@]}"; do
    if "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo 'Python 3.10+ is required (set PYTHON to its executable if needed).' >&2
  return 1
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
    if host_python="$(find_host_python)"; then
      "$host_python" --version
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
    host_python="$(find_host_python)" || exit 1
    host_cc="$(find_host_cc)" || { echo 'test: no host C compiler found (install cc/gcc/clang or set CC).' >&2; exit 1; }
    for file in README.md AGENTS.md CONTRIBUTING.md SECURITY.md docs/architecture.md docs/research/java-to-native.md docs/development/automation-policy.md docs/rendering/citro2d-prototype.md docs/rendering/minimap.md docs/testing/parity-harness.md docs/hardware/deployment-and-logs.md docs/hardware/memory-budget.md docs/audio/ndsp-mixer.md docs/audio/asset-conversion.md docs/audio/azahar-evidence.md docs/net/upstream-protocol.md docs/porting/campaign-progression.md docs/port-capabilities.json docs/progress-gauge.svg site/index.html .github/scripts/pr_automation.py .github/scripts/test_pr_automation.py .github/workflows/ai-pr-review.yml .github/workflows/upstream-arm11-smoke.yml Makefile include/audio/audio_mixer.h src/audio/audio_mixer.c tests/audio/test_audio_mixer.c include/audio/audio_registry.h src/audio/audio_registry.c tests/audio/test_audio_registry.c include/audio/audio_events.h src/audio/audio_events.c tests/audio/test_audio_events.c include/audio/audio_ambient.h src/audio/audio_ambient.c tests/audio/test_audio_ambient.c include/net/mindustry_net.h src/net/mindustry_net.c tests/net/test_mindustry_net.c include/render/scene_2d.h src/render/scene_2d.c tests/render/test_scene_2d.c include/render/minimap.h src/render/minimap.c tests/render/test_minimap.c include/sim/parity_scenario.h src/sim/parity_scenario.c tests/sim/test_parity_harness.c tests/parity/starter_copper_defense_v1.json include/platform/memory_budget.h src/platform/memory_budget.c tests/platform/test_memory_budget.c research/upstream-runtime/host/UpstreamSaveBaseline.java research/upstream-runtime/host/UpstreamAudioInventory.java research/upstream-runtime/host/UpstreamProgressionBaseline.java research/upstream-runtime/host/UpstreamCombatBaseline.java research/upstream-runtime/host/UpstreamNetSessionBaseline.java research/upstream-runtime/host/UpstreamCampaignPersistenceBaseline.java tools/verify_azahar.py tools/check_parity.py tools/device_deploy.py tools/port_progress.py tools/build_audio_assets.py tools/verify_upstream_net.py tests/tools/test_verify_azahar.py tests/tools/test_check_parity.py tests/tools/test_device_deploy.py tests/tools/test_port_progress.py tests/tools/test_audio_assets.py audio/upstream-audio-map.json audio/upstream-audio-triggers.json; do
      test -s "$file" || { echo "Missing or empty: $file" >&2; exit 1; }
    done
    "$host_python" -m py_compile .github/scripts/pr_automation.py .github/scripts/test_pr_automation.py research/java-to-native/budget_model.py research/java-to-native/run_poc.py tools/verify_azahar.py tools/check_parity.py tools/device_deploy.py tools/port_progress.py tools/build_audio_assets.py tools/verify_upstream_net.py tests/tools/test_verify_azahar.py tests/tools/test_check_parity.py tests/tools/test_device_deploy.py tests/tools/test_port_progress.py tests/tools/test_audio_assets.py
    "$host_python" tools/port_progress.py --check
    "$host_python" -m unittest discover -s .github/scripts -p 'test_*.py'
    "$host_python" -m unittest discover -s tests/tools -p 'test_*.py'

    mkdir -p build/host
    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/render/scene_2d.c src/render/factory_demo.c src/sim/parity_scenario.c \
      tests/render/test_scene_2d.c -lm -o build/host/test_scene_2d
    ./build/host/test_scene_2d

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/render/minimap.c tests/render/test_minimap.c -o build/host/test_minimap
    ./build/host/test_minimap

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/platform/hardware_profile.c tests/platform/test_hardware_profile.c -o build/host/test_hardware_profile
    ./build/host/test_hardware_profile

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/platform/memory_budget.c tests/platform/test_memory_budget.c -o build/host/test_memory_budget
    ./build/host/test_memory_budget

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/audio/audio_mixer.c tests/audio/test_audio_mixer.c -lm -o build/host/test_audio_mixer
    ./build/host/test_audio_mixer

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/audio/audio_mixer.c src/audio/audio_registry.c \
      tests/audio/test_audio_registry.c -o build/host/test_audio_registry
    ./build/host/test_audio_registry

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/audio/audio_mixer.c src/audio/audio_registry.c src/audio/audio_events.c \
      tests/audio/test_audio_events.c -o build/host/test_audio_events
    ./build/host/test_audio_events

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/audio/audio_mixer.c src/audio/audio_registry.c src/audio/audio_events.c \
      src/audio/audio_ambient.c tests/audio/test_audio_ambient.c \
      -o build/host/test_audio_ambient
    ./build/host/test_audio_ambient

    "$host_cc" -std=c11 -Wall -Wextra -Werror -Iinclude \
      src/net/mindustry_net.c tests/net/test_mindustry_net.c -o build/host/test_mindustry_net
    ./build/host/test_mindustry_net

    "$host_cc" -std=c11 -Wall -Wextra -Werror -O0 -Iinclude \
      src/sim/parity_scenario.c tests/sim/test_parity_harness.c -o build/host/test_parity_harness_o0
    ./build/host/test_parity_harness_o0
    "$host_cc" -std=c11 -Wall -Wextra -Werror -O2 -Iinclude \
      src/sim/parity_scenario.c tests/sim/test_parity_harness.c -o build/host/test_parity_harness_o2
    ./build/host/test_parity_harness_o2
    "$host_python" tools/check_parity.py \
      --fixture tests/parity/starter_copper_defense_v1.json \
      --binary build/host/test_parity_harness_o0 \
      --binary build/host/test_parity_harness_o2

    "$host_python" research/java-to-native/run_poc.py --require-compiler
    git diff --check HEAD
    echo 'All host and bootstrap checks passed.'
    ;;
  emu-test)
    test -s build/min3dstry.3dsx || { echo 'emu-test: build/min3dstry.3dsx missing; run ./dev.sh build first.' >&2; exit 2; }
    if [[ -z "${AZAHAR_BIN:-}" ]] && ! command -v azahar >/dev/null 2>&1 && ! command -v azahar.exe >/dev/null 2>&1; then
      echo 'emu-test: no 3DS emulator (azahar) is installed.' >&2
      exit 2
    fi
    host_python="$(find_host_python)" || exit 1
    "$host_python" tools/verify_azahar.py "${@:2}"
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
  poc)
    host_python="$(find_host_python)" || exit 1
    "$host_python" research/java-to-native/run_poc.py --require-compiler
    ;;
  status)
    git status --short --branch
    ;;
  device)
    host_python="$(find_host_python)" || exit 1
    "$host_python" tools/device_deploy.py "${@:2}"
    ;;
  package)
    echo 'package: release packaging is pending.' >&2
    exit 2
    ;;
  *)
    echo 'Usage: ./dev.sh doctor|build|test|emu-test|poc|run|device|package|status' >&2
    exit 2
    ;;
esac
