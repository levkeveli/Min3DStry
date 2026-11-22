# Offline Single-Player Ground Zero Vertical Slice

**Status:** Emulator-tested (`new3ds` and `old3ds` Azahar profiles); physical 3DS hardware verification remains pending (`#43`).

This vertical slice boots and runs unmodified upstream Mindustry `v160.8` **Ground Zero** (`assets/maps/serpulo/groundZero.msav`, `256x256`) offline directly on the Nintendo 3DS ARM11 runtime with zero PC server connection.

## Architecture

- [`UpstreamSinglePlayerSession.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerSession.java) (`-Pprobe=singleplayer-sim`): Pure-Java session lifecycle initializing `Vars`, `Pathfinder`, `ControlPathfinder`, `AvoidanceProcess`, `FogControl`, `GlobalVars` (adapted to guard `control.sound` when headless), `BlockIndexer`, `WaveSpawner`, and `BaseRegistry` without `AsyncCore`, plus a deterministic headless Ground Zero parity self-test (load map -> move -> mine copper -> build conveyor & wall -> deconstruct -> pause/resume -> flowfield & Wave 1 -> `.msav` save & reload -> terrain fingerprint).
- [`UpstreamSinglePlayerApp.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerApp.java) (`-Pprobe=singleplayer`): Graphical 3DS runtime entry point. Loads RomFS/SD localization and Ground Zero save/map before initializing sprite atlases, unit/bullet drawables, and stock bitmap/TTF fonts (`UpstreamTerrainRenderProbe`) to bound peak TeaVM heap usage, then runs a 60 Hz fixed-step loop with `Thread.yield()` so TeaVM's cooperative scheduler services `Pathfinder`.
- [`UpstreamSinglePlayerInput.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerInput.java), [`m3ds_input.h`](../../research/upstream-runtime/arm11/m3ds_input.h), and [`m3ds_input.c`](../../research/upstream-runtime/arm11/m3ds_input.c): Standalone `libctru` HID bridge (`m3ds_input_poll()` / `m3ds_input_touch()`). Bits `0..19` match `m3ds_live_net_input` (`Circle Pad`, `D-Pad`, `A`, `R`, `B`), with bit `20` = `KEY_X` (Pause/Resume), bit `21` = `KEY_Y` (Trigger wave), bit `22` = `KEY_SELECT` (Save `.msav`), and `-1` on `!aptMainLoop()` or `KEY_START` exit.
- [`WorldReflectionPolicy.java`](../../research/upstream-runtime/src/world/java/port/WorldReflectionPolicy.java): Registers 0-argument constructors for `arc.struct.{IntMap,IntSet,ObjectIntMap,ObjectMap,ObjectSet,StringMap}` so Arc `Json` restores populated `GameStats` (`placedBlockCount`, etc.) and `Rules` fields when reloading `.msav` saves.
- [`prepare_terrain_assets.py`](../../research/upstream-runtime/arm11/prepare_terrain_assets.py) (`--singleplayer`) & [`build.sh`](../../research/upstream-runtime/arm11/build.sh) (`--singleplayer`): Stages `maps/groundZero.msav` and `sprites.aatls` alongside bundles, `world-atlas`, `block_colors.png`, menu icons, and stock fonts, then links the ARM11 `.elf`/`.3dsx` (default `-Os`).

## 3DS Controls

| Input | Action |
| --- | --- |
| Circle Pad / D-Pad | Move player `Alpha` unit and pan centered viewport |
| Touch grid (`16x12`) | Place / break / configure / select mine ore at tile |
| Touch `Mine` (`x >= 288, 80 <= y < 112`) | Toggle ore mining target selection |
| Touch `List` / `Config` / Bottom bar | Open block catalog (`UpstreamBuildMenu`), block config (`UpstreamItemConfig`), shortcuts, `Rotate`, or `Remove` |
| `A` / `R` / `B` | Place at cursor / rotate block / toggle remove mode |
| `X` | Toggle simulation Pause / Resume (`UPSTREAM_SP_PAUSE`) |
| `Y` | Trigger next enemy wave (`UPSTREAM_SP_WAVE`) |
| `SELECT` | Save `.msav` snapshot to `/3ds/min3dstry/singleplayer-save.msav` (`UPSTREAM_SP_SAVED`) |
| `START` | Exit application cleanly |

## Build & Verification Commands

### 1. Host JVM vs TeaVM C (`-O0` and `-Os`) Deterministic Parity

```bash
bash /path/to/Mindustry/gradlew -p research/upstream-runtime --no-daemon \
  -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc \
  -Pprobe=singleplayer-sim \
  runProbeJvm "--args=/path/to/groundZero.msav /tmp/sp-save.msav" generateC
```

After running `fix_reflection_lifetime` and `fix_constructor_roots` on the generated `build/generated/teavm/c` output and compiling `all.c` with `cc -std=gnu11 -O0/-Os -fwrapv -ffp-contract=off`, JVM, `-O0`, and `-Os` all emit bit-identical traces:

```text
UPSTREAM_SP_LOADED source=map map=Ground_Zero size=256x256 wave=1 copper=0
UPSTREAM_SP_SIM_PASS world=256x256 mine=200:20 build=conveyor+wall deconstruct=air pause=295 wave=2 enemies=1 copper=16 reload=ok fingerprint=9d0956b4bcb0a6f5
```

### 2. Graphical ARM11 `-Os` Build & Azahar Verification

CI (`singleplayer-baseline` in `.github/workflows/upstream-arm11-smoke.yml`) builds and verifies the headless `singleplayer-sim` parity probe and its ARM11 `.3dsx`; building and verifying the graphical `singleplayer` `.3dsx` (which packs official game sprites/bundles/maps from `assets-v160.8.jar`) runs manually via the commands below:

```bash
python3 research/upstream-runtime/arm11/prepare_terrain_assets.py \
  --assets /path/to/assets-v160.8.jar \
  --server /path/to/server-release.jar \
  --output-dir build/terrain-sp \
  --singleplayer

bash /path/to/Mindustry/gradlew -p research/upstream-runtime --no-daemon \
  -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc \
  -Pprobe=singleplayer \
  generateC

bash research/upstream-runtime/arm11/build.sh \
  research/upstream-runtime/build/generated/teavm/c \
  research/upstream-runtime/build/probe-3ds-singleplayer \
  --singleplayer build/terrain-sp

python3 tools/verify_upstream_singleplayer.py \
  --rom research/upstream-runtime/build/probe-3ds-singleplayer/upstream-probe.3dsx
```

Unit tests covering `verify_upstream_singleplayer.py` CTM generation, SD save path resolution, and telemetry parsing run via:

```bash
python3 -m unittest -v tests/tools/test_verify_upstream_singleplayer.py
```

## Measured Build & Emulator Metrics

### ARM11 `-Os` ELF Breakdown (`upstream-probe.elf`)

- Strict pointer checking (`M3DS_RELAX_POINTER_TYPES` unset / `0`) passed cleanly.
- `.text`: `12,490,656` bytes (`11.91 MiB`)
- `.rodata`: `281,848` bytes (`0.27 MiB`)
- `.data`: `4,059,844` bytes (`3.87 MiB`)
- `.bss`: `138,840` bytes (`0.13 MiB`)
- Page-rounded `LOAD`: `16,977,920` bytes (`16.19 MiB`)
- Static Old 3DS subtotal (`LOAD + 24 MiB TeaVM heap + 4 MiB linearHeap + 256 KiB stack`): `46,600,192` bytes (`44.44 MiB`), well within the 64 MiB Old 3DS application budget.
- Packaged `.3dsx`: `178,294,356` bytes (`170.03 MiB`, including embedded RomFS assets and debug symbols).

### Azahar `2126.2` Results (`tools/verify_upstream_singleplayer.py`)

Both profiles executed Boot 1 (load `groundZero.msav`, select Mine + copper ore `(129, 49)`, place `conveyor` at `(131, 55)`, pan via Circle Pad, toggle Pause/Resume, trigger Wave 1, save `/3ds/min3dstry/singleplayer-save.msav`, exit cleanly) followed by Boot 2 (cold relaunch with no CTM loading `source=save` with matching wave & copper count and active mining/HUD rendering):

- **`new3ds` (`is_new_3ds=true`):** `UPSTREAM_MEMORY appHeap=108834816 linearHeap=4194304 stack=262144`, `saved=wave:2,copper:29,bytes:18700`, `reloaded=wave:2,copper:29`, `game_fps=4.72` (unoptimized headless emulator baseline).
- **`old3ds` (`is_new_3ds=false`):** `UPSTREAM_MEMORY appHeap=79474688 linearHeap=4194304 stack=262144` (~`75.8 MiB` emulator-reported `envGetHeapSize()` region, not the physical retail Original 3DS `64 MiB` `APPMEMTYPE 0` limit against which the `44.44 MiB` static subtotal is budgeted), `saved=wave:2,copper:29,bytes:18685`, `reloaded=wave:2,copper:29`, `game_fps=3.48` (unoptimized headless emulator baseline).
