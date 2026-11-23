#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
# The Azahar AppImage bundles Qt but not the X11/GL runtime it links against.
# GitHub-hosted Ubuntu lacks libGLX/libOpenGL/libEGL and the Qt xcb plugin
# dependencies, which makes AppRun exit 127 before the emulator starts.
sudo apt-get install -y --no-install-recommends \
  xvfb xauth mesa-vulkan-drivers libvulkan1 libpulse0 \
  libglx0 libopengl0 libegl1 libgles2 libxcb-glx0 \
  libxkbcommon-x11-0 libxcb-cursor0 libxcb-icccm4 libxcb-image0 \
  libxcb-keysyms1 libxcb-render-util0 libxcb-shape0 libxcb-xinerama0 \
  libxcb-xfixes0
asset="$RUNNER_TEMP/azahar.AppImage"
curl --fail --silent --show-error --location --retry 3 \
  https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar.AppImage -o "$asset"
printf '%s  %s\n' af037f8b775198f636b14bcfe63fb401b02246ebfefe1025bb905ce042556960 "$asset" | sha256sum --check
chmod +x "$asset"
mkdir -p "$RUNNER_TEMP/smoke-config" "$RUNNER_TEMP/smoke-data" "$RUNNER_TEMP/smoke-azahar"
# AppRun execs the emulator directly, so stopping a replay releases its RPC
# socket before the next boot. Extract-and-run leaves a launcher parent.
(cd "$RUNNER_TEMP/smoke-azahar" && "$asset" --appimage-extract >/dev/null)
{
  printf 'AZAHAR_BIN=%s\n' "$RUNNER_TEMP/smoke-azahar/squashfs-root/AppRun"
  printf 'XDG_CONFIG_HOME=%s\n' "$RUNNER_TEMP/smoke-config"
  printf 'XDG_DATA_HOME=%s\n' "$RUNNER_TEMP/smoke-data"
  printf 'LIBGL_ALWAYS_SOFTWARE=1\n'
} >> "$GITHUB_ENV"
