#!/usr/bin/env bash
set -euo pipefail

# Stage pinned upstream Mindustry v160.8 source, Arc checkout, Glacier fixture,
# and/or server-release.jar into "$RUNNER_TEMP", preferring a verified local
# M3DS_UPSTREAM_CACHE directory when present.

MINDUSTRY_SHA="19a0f40d44514f4802a8b79770456e7e32e34da0"
ARC_SHA="7445105cd2683daf5b9ac7033ebb07265d3e814d"
SERVER_JAR_SHA256="faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"

need_mindustry=0
need_arc=0
need_server_jar=0
need_glacier=0

for arg in "$@"; do
  case "$arg" in
    --mindustry) need_mindustry=1 ;;
    --arc) need_arc=1 ;;
    --server-jar) need_server_jar=1 ;;
    --glacier)
      need_glacier=1
      need_mindustry=1
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
cache_dir="${M3DS_UPSTREAM_CACHE:-}"

if [ "$need_mindustry" = "1" ]; then
  dest="$RUNNER_TEMP/Mindustry"
  rm -rf "$dest"
  if [ -n "$cache_dir" ] && [ -d "$cache_dir/Mindustry-v160.8/.git" ] && \
     [ "$(git -C "$cache_dir/Mindustry-v160.8" rev-parse HEAD 2>/dev/null || true)" = "$MINDUSTRY_SHA" ]; then
    cp -a "$cache_dir/Mindustry-v160.8" "$dest"
  else
    git clone --depth 1 --branch v160.8 \
      https://github.com/Anuken/Mindustry.git "$dest"
  fi
  test "$(git -C "$dest" rev-parse HEAD)" = "$MINDUSTRY_SHA"
fi

if [ "$need_glacier" = "1" ]; then
  git -C "$RUNNER_TEMP/Mindustry" show \
    HEAD:core/assets/maps/default/glacier.msav > "$RUNNER_TEMP/glacier.msav"
  test -s "$RUNNER_TEMP/glacier.msav"
fi

if [ "$need_server_jar" = "1" ]; then
  dest="$RUNNER_TEMP/server-release.jar"
  if [ -n "$cache_dir" ] && [ -f "$cache_dir/server-release-v160.8.jar" ] && \
     echo "$SERVER_JAR_SHA256  $cache_dir/server-release-v160.8.jar" | sha256sum -c - >/dev/null 2>&1; then
    cp -f "$cache_dir/server-release-v160.8.jar" "$dest"
  else
    curl --fail --silent --show-error --location \
      https://github.com/Anuken/Mindustry/releases/download/v160.8/server-release.jar \
      -o "$dest"
  fi
  echo "$SERVER_JAR_SHA256  $dest" | sha256sum -c -
fi

if [ "$need_arc" = "1" ]; then
  dest="$RUNNER_TEMP/Arc"
  rm -rf "$dest"
  if [ -n "$cache_dir" ] && [ -d "$cache_dir/Arc-7445105c/.git" ] && \
     [ "$(git -C "$cache_dir/Arc-7445105c" rev-parse HEAD 2>/dev/null || true)" = "$ARC_SHA" ]; then
    cp -a "$cache_dir/Arc-7445105c" "$dest"
  else
    git clone --filter=blob:none --no-checkout \
      https://github.com/Anuken/Arc.git "$dest"
    git -C "$dest" fetch --depth 1 origin "$ARC_SHA"
    git -C "$dest" checkout --detach FETCH_HEAD
  fi
  test "$(git -C "$dest" rev-parse HEAD)" = "$ARC_SHA"
fi
