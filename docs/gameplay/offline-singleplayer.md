# Offline Single-Player Ground Zero Vertical Slice

**Status:** Emulator-tested (`new3ds` and `old3ds` Azahar profiles); physical 3DS hardware verification remains pending (`#43`).

This vertical slice boots and runs unmodified upstream Mindustry `v160.8` **Ground Zero** (`assets/maps/serpulo/groundZero.msav`, `256x256`) offline directly on the Nintendo 3DS ARM11 runtime with zero PC server connection.

## Architecture

- [`UpstreamSinglePlayerSession.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerSession.java) (`-Pprobe=singleplayer-sim`): Pure-Java session lifecycle initializing `Vars`, `Pathfinder`, `ControlPathfinder`, `AvoidanceProcess`, `FogControl`, `GlobalVars` (adapted to guard `control.sound` when headless), `BlockIndexer`, `WaveSpawner`, and `BaseRegistry` without `AsyncCore`, plus a deterministic headless Ground Zero parity self-test (load map -> move -> mine copper -> build conveyor & wall -> deconstruct -> pause/resume -> flowfield & Wave 1 -> `.msav` save & reload -> terrain fingerprint).
- [`UpstreamSinglePlayerApp.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerApp.java) (`-Pprobe=singleplayer`): Graphical 3DS runtime entry point. Loads RomFS/SD localization and Ground Zero save/map before initializing sprite atlases, unit/bullet drawables, and stock bitmap/TTF fonts (`UpstreamTerrainRenderProbe`) to bound peak TeaVM heap usage, then runs a 60 Hz fixed-step loop with `Thread.yield()` so TeaVM's cooperative scheduler services `Pathfinder`.
- [`UpstreamSinglePlayerInput.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerInput.java), [`m3ds_input.h`](../../research/upstream-runtime/arm11/m3ds_input.h), and [`m3ds_input.c`](../../research/upstream-runtime/arm11/m3ds_input.c): Standalone `libctru` HID bridge (`m3ds_input_poll()` / `m3ds_input_touch()`). Bits `0..19` match `m3ds_live_net_input` (`Circle Pad`, `D-Pad`, `A`, `R`, `B`), with bit `20` = `KEY_X` (Pause/Resume), bit `21` = `KEY_Y` (Trigger wave), bit `22` = `KEY_SELECT` (Save `.msav`), and `-1` on `!aptMainLoop()` or `KEY_START` exit.
- [`WorldReflectionPolicy.java`](../../research/upstream-runtime/src/world/java/port/WorldReflectionPolicy.java): Registers 0-argument constructors for `arc.struct.{IntMap,IntSet,ObjectIntMap,ObjectMap,ObjectSet,StringMap}` so Arc `Json` restores populated `GameStats` (`placedBlockCount`, etc.) and `Rules` fields when reloading `.msav` saves.
- [`prepare_terrain_assets.py`](../../research/upstream-runtime/arm11/prepare_terrain_assets.py) (`--singleplayer`) & [`build.sh`](../../research/upstream-runtime/arm11/build.sh) (`--singleplayer`): Stages `maps/groundZero.msav` and `sprites.aatls` alongside bundles, `world-atlas`, `block_colors.png`, menu icons, and stock fonts, then links the ARM11 `.elf`/`.3dsx` (default `-Os`).

## Campaign sector selection

When `/3ds/min3dstry` contains more than one `sector-*.msav` slot, the app
loads `Saves.load()` through the live `Core.settings`/`Core.files` path before
starting the world. It lists one top-level row per discovered `sector-*.msav`
file, matching `Saves.getSectorFile()`; nested probe or backup files are
ignored:

```text
UPSTREAM_CAMPAIGN_MENU slots=2
```

D-pad up/down changes the selection; A launches the selected slot through
`SaveIO.load(file, Vars.world.makeSectorContext(sector))`. The sector context is
taken from the slot's decoded save metadata (`SaveSlot.getSector()`) and falls
back to a planet-aware `sector-<planet>-<id>.msav` match. START cancels the menu:

```text
UPSTREAM_CAMPAIGN_CANCEL
UPSTREAM_SP_EXIT
```

For automated runs, a file `/3ds/min3dstry/campaign-select.txt` containing a
zero-based slot index selects without input:

```text
UPSTREAM_CAMPAIGN_SELECTED slot=sector-serpulo-86.msav source=auto
UPSTREAM_SP_LOADED source=campaign map=Editor_Playtesting size=150x250 wave=1 copper=0
```

Verified on Azahar `2126.2` New and Old profiles with two staged sector files.
A-button selection was verified by injecting Azahar 2126.2's keyboard A mapping
on both New and Old profiles; the auto-select hook remains for automation.

On START exit, a selected sector slot is written back through `SaveIO.write`,
but only when the slot actually loaded, so a failed load cannot overwrite it:

```text
UPSTREAM_CAMPAIGN_SAVED slot=sector-serpulo-15.msav
UPSTREAM_SP_EXIT
```

Save-back and START cancel were verified on both profiles.

## Custom Game

Hold **L** while launching the standalone graphical `.3dsx` to open the
Custom Game map list. Use D-pad up/down to select a map, left/right to select
an upstream mode, and **A** to start. **B** or **START** exits the selector.
The roster includes all 18 maps listed by the pinned v160.8 Custom Game menu.
Place compatible `.msav` map exports in `/3ds/min3dstry/maps/` to list them
alongside the bundled maps. The selector uses the names from each map's stock
metadata. If a mode is unavailable on a map, startup uses the first valid mode
in the upstream mode order.

`SELECT` saves the current Custom Game to
`/3ds/min3dstry/custom-game-save.msav` using upstream `SaveIO`. The separate
file preserves the ordinary single-player and campaign slots.
Select **Load Game** at the top of the Custom Game list to resume that save.

The JVM regression uses an assets directory staged with
`prepare_terrain_assets.py --singleplayer`. Pass its `maps/default` directory,
a fixture directory with one valid exported Ground Zero map, a new output file,
and the `bundles/bundle` resource base from those assets to
`-Pprobe=singleplayer runCustomGameJvm --args="/path/to/maps/default /path/to/user-maps /path/to/roundtrip.msav /path/to/bundles/bundle"`
along with the pinned runtime Gradle properties. It checks and launches every
stock map, launches Glacier in Sandbox, and reloads the resulting `.msav`.

Run `python3 tools/verify_upstream_custom_game.py --rom upstream-probe.3dsx`
with Azahar 2126.2 and a virtual SD without user maps to replay the selector,
start Ancient Caldera in Sandbox, and save and resume the game on both console
profiles. The verifier retains the native test saves alongside screenshots and
logs so they can also be opened in the stock v160.8 game.

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
