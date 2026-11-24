#!/usr/bin/env bash
set -euo pipefail

bash .github/scripts/fetch_upstream_v160.sh --mindustry --server-jar --arc
base=https://github.com/Anuken/Mindustry/releases/download/v160.8
curl --fail --silent --show-error --location --retry 3 "$base/assets.jar" -o "$RUNNER_TEMP/assets.jar"
curl --fail --silent --show-error --location --retry 3 "$base/Mindustry.jar" -o "$RUNNER_TEMP/desktop.jar"
printf '%s  %s\n' \
  2728b63d309c27a405744c8b5440d90a65387a41e2c4339b630a239f79f6bd17 "$RUNNER_TEMP/assets.jar" \
  6dd6da921d5021a84823456e05b477ee8de8511990d40de850b37dd0905152f2 "$RUNNER_TEMP/desktop.jar" |
  sha256sum --check

gradle_log="$RUNNER_TEMP/gradle-generate.log"
for attempt in 1 2 3; do
  if bash "$RUNNER_TEMP/Mindustry/gradlew" -p research/upstream-runtime --no-daemon --console=plain \
      "-PupstreamCheckout=$RUNNER_TEMP/Mindustry" "-PreferenceJar=$RUNNER_TEMP/server-release.jar" \
      "-ParcCheckout=$RUNNER_TEMP/Arc" -Pprobe=unified generateC 2>&1 | tee "$gradle_log"; then
    break
  fi
  if [ "$attempt" -eq 3 ] || ! grep -Eq '429|Could not resolve' "$gradle_log"; then
    exit 1
  fi
  echo "Gradle dependency resolution was rate-limited; retrying (attempt $((attempt + 1))/3)" >&2
  sleep $((attempt * 15))
done

stock_jar="$RUNNER_TEMP/server-release.jar"
liquid_check="$RUNNER_TEMP/liquid-inventory-check"
runtime=research/upstream-runtime
mkdir -p "$liquid_check/reference" "$liquid_check/staged"
javac -cp "$stock_jar" -d "$liquid_check/reference" "$runtime/host/UpstreamLiquidInventoryReference.java"
javac -cp "$stock_jar" -d "$liquid_check/staged" \
  "$runtime/build/upstream-src/unified/mindustry/world/modules/LiquidModule.java"
java -cp "$liquid_check/reference:$stock_jar" UpstreamLiquidInventoryReference > "$liquid_check/stock.log"
java -cp "$liquid_check/reference:$liquid_check/staged:$stock_jar" UpstreamLiquidInventoryReference > "$liquid_check/staged.log"
test "$(wc -l < "$liquid_check/stock.log")" -eq 2100
test "$(wc -l < "$liquid_check/staged.log")" -eq 2100
diff -u "$liquid_check/stock.log" "$liquid_check/staged.log"
printf 'UPSTREAM_STOCK_LIQUID_REFERENCE steps=%s\n' "$(wc -l < "$liquid_check/stock.log")"

history_check="$RUNNER_TEMP/power-history-check"
mkdir -p "$history_check/reference" "$history_check/staged"
javac -cp "$stock_jar" -d "$history_check/reference" "$runtime/host/UpstreamPowerHistoryReference.java"
javac -cp "$stock_jar" -d "$history_check/staged" \
  "$runtime/build/upstream-src/unified/mindustry/world/blocks/power/PowerGraph.java"
java -cp "$history_check/reference:$stock_jar" UpstreamPowerHistoryReference > "$history_check/stock.log"
java -cp "$history_check/reference:$history_check/staged:$stock_jar" UpstreamPowerHistoryReference > "$history_check/staged.log"
test "$(wc -l < "$history_check/stock.log")" -eq 182
diff -u "$history_check/stock.log" "$history_check/staged.log"
echo "UPSTREAM_STOCK_POWER_HISTORY states=182"

export M3DS_SMOKE_DEVKIT_IMAGE=devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5
cat > "$RUNNER_TEMP/smoke-tex3ds" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
exec docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:$PWD" -w "$PWD" \
  "$M3DS_SMOKE_DEVKIT_IMAGE" /opt/devkitpro/tools/bin/tex3ds "$@"
WRAPPER
chmod +x "$RUNNER_TEMP/smoke-tex3ds"
# Asset conversion creates temporary PNGs that must be visible inside Docker.
export TMPDIR="$PWD/build/smoke-scratch"
mkdir -p "$TMPDIR" build/unified-smoke
python3 research/upstream-runtime/arm11/prepare_terrain_assets.py \
  --assets "$RUNNER_TEMP/assets.jar" --server "$RUNNER_TEMP/server-release.jar" \
  --desktop "$RUNNER_TEMP/desktop.jar" --output-dir build/smoke-assets \
  --tex3ds "$RUNNER_TEMP/smoke-tex3ds" --singleplayer --live-audio
python3 tools/verify_live_stock_audio.py build/smoke-assets
bash "$RUNNER_TEMP/Mindustry/gradlew" -p research/upstream-runtime --no-daemon --console=plain \
  "-PupstreamCheckout=$RUNNER_TEMP/Mindustry" "-PreferenceJar=$RUNNER_TEMP/server-release.jar" \
  "-ParcCheckout=$RUNNER_TEMP/Arc" -Pprobe=unified runBuildingLayersJvm \
  --args="--packaged-graphics $PWD/build/smoke-assets/logic-font.dat $PWD/build/smoke-assets/sprites.aatls"

numa=()
[[ -z "${M3DS_DOCKER_CPUSET_CPUS:-}" ]] || numa+=(--cpuset-cpus "$M3DS_DOCKER_CPUSET_CPUS")
[[ -z "${M3DS_DOCKER_CPUSET_MEMS:-}" ]] || numa+=(--cpuset-mems "$M3DS_DOCKER_CPUSET_MEMS")
docker run --rm "${numa[@]}" -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  -e M3DS_RELAX_POINTER_TYPES=1 "$M3DS_SMOKE_DEVKIT_IMAGE" \
  bash research/upstream-runtime/arm11/build.sh research/upstream-runtime/build/generated/teavm/c \
    build/smoke-arm11 --unified build/smoke-assets
cp build/smoke-arm11/upstream-probe.3dsx "$RUNNER_TEMP/server-release.jar" build/unified-smoke/
unzip -p "$RUNNER_TEMP/assets.jar" assets/sprites/sprites.aatls > build/unified-smoke/sprites.aatls

stock_jar="$RUNNER_TEMP/server-release.jar"
item_check="$RUNNER_TEMP/item-inventory-check"
runtime=research/upstream-runtime
mkdir -p "$item_check/reference" "$item_check/staged"
javac -cp "$stock_jar" -d "$item_check/reference" "$runtime/host/UpstreamItemInventoryReference.java"
javac -cp "$stock_jar" -d "$item_check/staged" \
  "$runtime/build/upstream-src/unified/mindustry/world/modules/ItemModule.java"
java -cp "$item_check/reference:$stock_jar" UpstreamItemInventoryReference > "$item_check/stock.log"
java -cp "$item_check/reference:$item_check/staged:$stock_jar" UpstreamItemInventoryReference > "$item_check/staged.log"
test "$(wc -l < "$item_check/stock.log")" -eq 2101
test "$(wc -l < "$item_check/staged.log")" -eq 2101
diff -u "$item_check/stock.log" "$item_check/staged.log"
grep -Fx 'UPSTREAM_STOCK_ITEM_SUBCLASS array=preserved' "$item_check/staged.log"
