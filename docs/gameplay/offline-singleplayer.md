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

Copy original `sector-*.msav` files into `/3ds/min3dstry/saves/`, following
the PC `saves/` layout. Loose slots in `/3ds/min3dstry/` are also supported.
Discovery reads the original save metadata and prefers the `saves/` copy
when both directories contain the same sector. It scans each directory's top
level and skips backup entries. The title and planet come from the original
sector data, including the v160.8 Serpulo preset assignments loaded from
`planets/serpulo.json` before content initialization.

A single sector opens immediately. With more than one, the top screen lists
the sectors most recently saved first. D-pad up/down changes the selection,
**A** opens it, and **START** exits the selector. A valid zero-based index in
`/3ds/min3dstry/campaign-select.txt` can select a row for automation. Selection logs include the save filename and
the original sector name.

The app loads the sector through `SaveIO.load(file, world.makeSectorContext(sector))`.
The **SELECT** save button and **START** exit write to the selected file with
`SaveIO.save`, which keeps the previous save at the original backup path.
Selecting a row does not write a sector save.

For verification, create sector fixtures with the stock v160.8 server in a
temporary directory. Run the following as one server-console command, first
with `frozenForest` and then with `groundZero` so Ground Zero sorts first.

```text
js (() => { const sector = Packages.mindustry.content.SectorPresets.groundZero.sector; Vars.logic.reset(); Vars.world.loadSector(sector); Vars.logic.play(); const file = Packages.arc.files.Fi.get("sector-" + sector.planet.name + "-" + sector.id + ".msav"); Packages.mindustry.io.SaveIO.write(file); return file.name(); })()
```

The resulting files are `sector-serpulo-170.msav` and `sector-serpulo-64.msav`.
Copy the original `bundle.properties` and `planets/serpulo.json` alongside the
fixtures to run `-Pprobe=singleplayer runCampaignResumeJvm --args="/path/to/fixtures /path/to/new-scratch-dir"`
with the normal pinned runtime Gradle properties. Use
`python3 tools/verify_upstream_campaign_resume.py --rom upstream-probe.3dsx --fixtures /path/to/fixtures`
to exercise sole-save resume, graphical selection, and save-back on both
Azahar profiles.

The [ARM11 build from `1390790`](https://github.com/levkeveli/Min3DStry/actions/runs/45035851013)
resumed a lone Ground Zero slot from `saves/` and used the D-pad to select a
loose Frozen Forest slot from the two-sector menu on both Azahar 2126.2
profiles. Saving preserved the original files as backups. The unmodified
v160.8 PC server opened all four new saves and confirmed the same preset,
sector ID, and dimensions. The standard Ground Zero movement, mining,
building, pause, and save/reload replay also passed on both profiles with
no sector files staged. After integration with the merged Custom Game port,
the campaign resume JVM reference and the repository host suite passed
against the combined tree. Physical console verification remains open.

## Campaign settings

Copy the PC `settings.bin` into `/3ds/min3dstry/` alongside the `saves/`
directory to carry over campaign research, unlocks, sector information, and
the last-sector selection. The graphical app loads the file through Arc
`Settings` before initializing Mindustry content. In the sector menu, the
stock last save name or planet sector preference determines the initial row
when it matches an available save. Saving the sector updates the original
Mindustry settings keys through `SaveIO` and flushes `Settings` to the same
SD directory. The offline interface uses the saved PC language preference
when available. A nonempty SD `locale.txt` overrides the displayed language
for that launch and preserves the PC setting. Arc manages the settings backup.
The offline executable
reserves a 512 KiB native stack for the nested upstream serializer used by
campaign saves with objective markers.

The [ARM11 build from `c42a78b`](https://github.com/levkeveli/Min3DStry/actions/runs/45042352829)
opened Ground Zero, highlighted the stock last save in the two-sector menu,
and saved back to the selected slot on both Azahar 2126.2 profiles. The
stock v160.8 server reopened all four save and settings pairs. It found the
original partial research value, content unlock, custom sector name, origin,
and unrelated copied preferences, with the correct last sector for each run.

Use the stock v160.8 server to add `settings.bin` to the sector fixture directory.

```sh
python3 tools/campaign_settings_fixture.py --server server-release.jar --create-in /path/to/fixtures
```

Run Gradle with the pinned runtime properties and
`-Pprobe=singleplayer runGraphicalCampaignSettingsJvm --args="/path/to/fixtures /path/to/new-scratch-dir"`.
It checks unlocks and partial research at content startup, the stock sector
metadata, language selection, the settings backup, and the research update
after saving.
Passing the same fixtures to `tools/verify_upstream_campaign_resume.py`
checks the initial menu selection and saves a copy of the native settings for
each run. The stock reader can check those files with
`python3 tools/campaign_settings_fixture.py --server server-release.jar --check /path/to/new3ds-single-settings.bin --sector 170 --after-play`
and `--sector 64` without `--after-play` for the two-slot run. The replay
allows the active sector’s stock production average to decay while idle.

## Custom Game

Hold **L** while launching the standalone graphical `.3dsx` to open the
Custom Game map list. Use D-pad up/down to select a map, left/right to select
an upstream mode, and **A** to start. **B** or **START** exits the selector.
The roster includes all 18 maps listed by the pinned v160.8 Custom Game menu.
Place compatible `.msav` map exports in `/3ds/min3dstry/maps/` to list them
alongside the bundled maps. The selector uses the names from each map's stock
metadata and the stock map ordering. It initializes the text renderer first
and loads the world sprite atlas after the map. If a mode is unavailable on a
map, startup uses the first valid mode
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
Use `--map archipelago` to exercise the original 500×500 map separately.

The [ARM11 build from `431c7e8`](https://github.com/levkeveli/Min3DStry/actions/runs/45030664788)
started Ancient Caldera in Sandbox, saved, and resumed it on both Azahar
2126.2 profiles. An unmodified v160.8 server loaded both native saves and
reported the same map size, planet, infinite resources, waves, and wave-timer
settings as its own Ancient Caldera Sandbox session.

The graphical app reserves a 32 MiB TeaVM heap for the decoded world,
pathfinder grids, and sprite atlas. In the preceding 24 MiB build, the Old 3DS
debugger caught Archipelago's 1,000,016-byte pathfinder allocation after
collection left only 40 bytes free. The
[ARM11 build from `fc3881c`](https://github.com/levkeveli/Min3DStry/actions/runs/45032641678)
started the original 500×500 Archipelago in Sandbox, saved, and resumed it on
both Azahar profiles. The unmodified v160.8 server opened both native saves and
matched the same Sandbox flags, planet, and dimensions. The same build also
passed the Ancient Caldera replay and stock server readback, listed and opened
an SD-imported Glacier beside the 18 bundled maps, and passed the regular
Ground Zero movement, mining, building, pause, and save/reload replay on both
profiles. Physical console memory use and frame-rate targets remain open.

## 3DS Controls

| Input | Action |
| --- | --- |
| Circle Pad / D-Pad | Move player `Alpha` unit and pan centered viewport |
| Touch grid (`16x12`) | Place / break / configure / select mine ore at tile |
| Touch `Mine` (`x >= 288, 80 <= y < 112`) | Toggle ore mining target selection |
| Touch `List` / `Config` / Bottom bar | Open block catalog (`UpstreamBuildMenu`), block config (`UpstreamBuildingConfig`), shortcuts, `Rotate`, or `Remove` |
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

## Original material buildings

The offline renderer draws stock crafters, separators, pumps, liquid
routers and tanks, and item storage using the upstream building passes
and v160.8 atlas. Their original recipes and save data use the existing
upstream simulation and serialization.
The offline session initializes cache layers before registering content so
stock blocks retain the correct layer for their dynamic draw calls.

The JVM reference exercises all 49 definitions in these families outside
the already supported cores. Each definition must draw a stock atlas
sprite both at rest and with active draw state. Run it with the pinned
Glacier map, atlas metadata, and block colors from the official assets.

```bash
bash /path/to/Mindustry/gradlew -p research/upstream-runtime \
  -PupstreamCheckout=/path/to/Mindustry -ParcCheckout=/path/to/Arc \
  -PreferenceJar=/path/to/server-release.jar -Pprobe=singleplayer \
  runOfflineMaterialsJvm \
  --args="/path/to/glacier.msav /path/to/sprites.aatls /path/to/block_colors.png"
```

The emulator verifier uses an unmodified v160.8 server to write two Glacier
saves with identical terrain. The populated save adds a silicon smelter,
plastanium compressor, vault, liquid tank, mechanical pump, electrolyzer,
and separator. It reopens that save on the stock server, then checks
that each structure changes the expected portion of the top screen
on both Azahar profiles. The verifier uses isolated virtual SD directories
and retains the saves, screenshots, and logs.
All 25 sample points per building differed from the empty scene on both
profiles with the [ARM11 test build](https://github.com/levkeveli/Min3DStry/actions/runs/45069415082).

```bash
python3 tools/verify_offline_material_buildings.py \
  --rom /path/to/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-material-buildings
```

## Original power buildings

The offline and multiplayer world draw passes use the pinned power blocks'
own sprites, layered drawers, and connection geometry. The renderer
also records the upstream revealed-building flags for visible tiles,
which the stock Erekir beam nodes use to decide whether to draw their link.

The same JVM reference uses the original atlas to exercise idle and active
draws for the 25 v160.8 power-category definitions. It also verifies that
two linked stock beam nodes submit exactly one beam segment.

```bash
python3 tools/verify_offline_power_buildings.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-power-buildings
```

The replay uses stock Glacier saves to compare an empty area, Serpulo
and Erekir power buildings with a connected beam, and a heated Thorium
Reactor. On Azahar 2126.2, the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45076601292)
produced matching results on Original and New 3DS profiles. Each profile
changed all 25 sample pixels at each of the seven placements, 15 of 25
along the beam, and all 25 at the heated reactor.

Add `--live` to replay the same stock save against the unmodified v160.8
Java server. The client refreshes the beam caches using the original tile
change rules, since the server snapshot does not carry cached beam endpoints.
Both Azahar profiles joined and drew the beam with 15 of 25 sample pixels
changed in the [live ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45078491985).
The world lighting pass is tracked in #38.
