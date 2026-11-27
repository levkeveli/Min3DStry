# Offline Single-Player Ground Zero Vertical Slice

**Status:** Emulator-tested (`new3ds` and `old3ds` Azahar profiles); physical 3DS hardware verification remains pending (`#43`).

This vertical slice boots and runs unmodified upstream Mindustry `v160.8` **Ground Zero** (`assets/maps/serpulo/groundZero.msav`, `256x256`) offline directly on the Nintendo 3DS ARM11 runtime with zero PC server connection.

## Architecture

- [`UpstreamSinglePlayerSession.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerSession.java) (`-Pprobe=singleplayer-sim`): Pure-Java session lifecycle initializing `Vars`, `Pathfinder`, `ControlPathfinder`, `AvoidanceProcess`, `FogControl`, `GlobalVars` (adapted to guard `control.sound` when headless), `BlockIndexer`, `WaveSpawner`, and `BaseRegistry` without `AsyncCore`, plus a deterministic headless Ground Zero parity self-test (load map -> move -> mine copper -> build conveyor & wall -> deconstruct -> pause/resume -> flowfield & Wave 1 -> `.msav` save & reload -> terrain fingerprint).
- [`UpstreamSinglePlayerApp.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerApp.java) (`-Pprobe=singleplayer`): Graphical 3DS runtime entry point. Loads RomFS/SD localization and Ground Zero save/map before initializing sprite atlases, unit/bullet drawables, and stock bitmap/TTF fonts (`UpstreamTerrainRenderProbe`) to bound peak TeaVM heap usage, then runs a 60 Hz fixed-step loop with `Thread.yield()` so TeaVM's cooperative scheduler services `Pathfinder`.
- [`UpstreamSinglePlayerInput.java`](../../research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerInput.java), [`m3ds_input.h`](../../research/upstream-runtime/arm11/m3ds_input.h), and [`m3ds_input.c`](../../research/upstream-runtime/arm11/m3ds_input.c) provide the standalone `libctru` HID bridge. Bits `0..19` match `m3ds_live_net_input` for Circle Pad, D-Pad, A, R, and B. Bits `20..22` report X, Y, and SELECT presses. Bits `23` and `25` report held L and X. The poll returns `-1` on exit.
- [`WorldReflectionPolicy.java`](../../research/upstream-runtime/src/world/java/port/WorldReflectionPolicy.java): Registers 0-argument constructors for `arc.struct.{IntMap,IntSet,ObjectIntMap,ObjectMap,ObjectSet,StringMap}` so Arc `Json` restores populated `GameStats` (`placedBlockCount`, etc.) and `Rules` fields when reloading `.msav` saves.
- [`prepare_terrain_assets.py`](../../research/upstream-runtime/arm11/prepare_terrain_assets.py) (`--singleplayer`) & [`build.sh`](../../research/upstream-runtime/arm11/build.sh) (`--singleplayer`): Stages `maps/groundZero.msav` and `sprites.aatls` alongside bundles, `world-atlas`, `block_colors.png`, menu icons, and stock fonts, then links the ARM11 `.elf`/`.3dsx` (default `-Os`).

## Ground Zero save and reload

The offline runtime caches only the stock flowfields it creates. Cache keys
retain the original team, cost and field IDs, and the game still uses the
upstream code for creating and updating flowfields. This removes the nested
cache access that threw during map decode in the optimized C replay.

The [hosted replay](https://github.com/levkeveli/Min3DStry/actions/runs/45338932257)
matched JVM and generated C at both `-O0` and `-Os`. It exercised mining,
building, deconstruction, pause, an enemy wave and save/reload, with the same
final world fingerprint in all three builds. This run used the host runtime.

## Weather in the graphical campaign

The graphical campaign uses the v160.8 weather entities, particle and debris
draw routines, and original fog and sandstorm noise textures. `SaveIO` restores
active weather from a stock `.msav` and saves it again through the existing
offline session. The simulation advances weather on its fixed step. The weather
overlay follows the original renderer's alpha fade while the game is paused.
Regenerate the RomFS assets with `prepare_terrain_assets.py --singleplayer` to
include both weather textures.

Run the JVM regression with the usual pinned runtime Gradle properties and
`-Pprobe=singleplayer runOfflineWeatherJvm --args="clear.msav weather.msav sprites.aatls roundtrip.msav"`.
Use two stock v160.8 Ground Zero saves with no weather in the first and exactly
rain, sandstorm, and fog at opacity 0.75 in the second. The regression checks one
simulation tick, the save round trip, particles, and both repeating textures.

To create those fixtures, copy `assets/maps/serpulo/groundZero.msav` from the
v160.8 asset archive into `config/maps/groundZero.msav` in an isolated stock
server directory. Start the v160.8 `server-release.jar`, enter
`host Ground_Zero survival`, and run the following as one server-console command.

```text
js (() => { const save = Packages.mindustry.io.SaveIO; const Fi = Packages.arc.files.Fi; save.write(Fi.get("clear.msav")); const kinds = [Weathers.rain, Weathers.sandstorm, Weathers.fog]; for (let i = 0; i < kinds.length; i++) { const state = kinds[i].create(1, 3600); state.opacity = 0.75; state.windVector.setZero(); } save.write(Fi.get("weather.msav")); })()
```

For graphical verification, pass that directory to
`python3 tools/verify_upstream_singleplayer.py --rom upstream-probe.3dsx --weather-fixtures /path/to/fixtures`.
The verifier boots both saves on each Azahar profile, checks that both stock
textures load with active weather, and compares world and touch-screen samples.

The [ARM11 build from source commit `8e91f20`](https://github.com/levkeveli/Min3DStry/actions/runs/45024372522)
passed on Azahar 2126.2 in the Old and New 3DS profiles. In both profiles the
weather changed 110 of 126 sampled world pixels and zero of 40 sampled touch
pixels. Physical console testing remains pending.

The [current unified ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45133245996)
repeated the clear and active-weather replays on both profiles. Both loaded
the original noise textures and changed 111 of 126 world samples and zero
of 40 touch samples. The unmodified Java v160.8 server reopened the JVM
round-trip save and confirmed all three original weather states were active.

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
to exercise sole-save resume, graphical selection, and save-back in Azahar's
New 3DS profile.

On pause-menu Load, the port drops old player and UI references before the
stock save decoder runs. The upstream reset also clears the retired building
and floor indexes and the backing slots of the temporary block lists. The
live world rebuilds its indexes during the normal WorldLoad event. If decode
fails, the exit path leaves the selected save and backup available for recovery.

For the dense Serpulo 242 replay, generate fixtures from the same stock Java
server and use the matching emulator verifier flag.

```sh
python3 tools/campaign_resume_fixtures.py --dense --server server-release.jar --output build/dense-fixtures
python3 tools/verify_upstream_campaign_resume.py --dense --rom upstream-probe.3dsx --fixtures build/dense-fixtures --server server-release.jar
```

The dense run uses Ground Zero as the first slot and checks that the second
slot loads and reloads at 600×600, draws a frame, and saves with its original
backup. The server readback confirms the saved sector ID, dimensions, and
gameplay patch. Keep the dense and standard fixture sets in separate directories.

Campaign saves write the upstream stream to a same-directory temporary file
and check its stock metadata before moving the old primary to the standard
backup. A failed temporary write leaves both existing files intact. If a
replacement stops after rotating the backup, the campaign menu can reopen
the remaining backup and write the next save to the original slot name.
Pass `--backup-only` to the campaign resume verifier to exercise that scenario.
The [New 3DS replay at `f28cf81`](https://github.com/levkeveli/Min3DStry/actions/runs/45724376512/artifacts/15075271195)
started with a backup-only Frozen Forest slot, loaded and reloaded the
original 200 by 200 world, then wrote a new primary while preserving the
backup. Unmodified v160.8 Java reopened the replacement and checked sector 64.

When a campaign primary passes the metadata check but fails while loading the
world, the next save preserves the backup that actually loaded. The recovered
world is staged and installed in the primary slot. Pass `--truncated-primary`
with `--server` to replay this case and have stock Java reopen the replacement.

Pass `--dense-resave` with `--dense --server server-release.jar` to check both
a manual save after reload and a later exit save. An [instrumented 56 MiB
diagnostic ROM](https://github.com/levkeveli/Min3DStry/actions/runs/45711354495)
completed the dense reload and saved with the original fixture backup.
The [two-save replay](https://github.com/levkeveli/Min3DStry/actions/runs/45716873959/artifacts/15070698663)
reused that exact ROM. It verified each backup against its predecessor, and
the unmodified v160.8 server reopened both 600×600 saves with the original
embedded patch. The release-build heap and physical New 3DS performance remain open.

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
stock last save display name or planet sector preference determines the
initial row when it matches an available save. The original Saves class
stores the display name under `last-sector-save`, including renamed saves.
[Pinned stock implementation](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/core/src/mindustry/game/Saves.java#L259-L264)

Saving the sector updates the original
Mindustry settings keys through `SaveIO` and flushes `Settings` to the same
SD directory. The offline interface uses the saved PC language preference
when available. A nonempty SD `locale.txt` overrides the displayed language
for that launch and preserves the PC setting. Arc manages the settings backup.
The graphical 3DS binaries with offline support reserve a 512 KiB native
stack for campaign saves with nested objective markers. This adds 256 KiB to
the static budget of builds that used the earlier 256 KiB stack.

The [ARM11 build from `c42a78b`](https://github.com/levkeveli/Min3DStry/actions/runs/45042352829)
opened Ground Zero, highlighted the stock last save in the two-sector menu,
and saved back to the selected slot on both Azahar 2126.2 profiles. The
stock v160.8 server reopened all four save and settings pairs. It found the
original partial research value, content unlock, custom sector name, origin,
and unrelated copied preferences, with the correct last sector for each run.

The [unified ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45118296332)
repeated both scenarios on the Original and New 3DS Azahar profiles.
The two-sector menu highlighted Frozen Forest from the copied PC setting,
and the app used the saved German language. Stock Java reopened the four
written save and settings pairs with the expected sector, core, research
and preferences. The replay verified both the original save and settings
backups.

Use the stock v160.8 server to add `settings.bin` to the sector fixture directory.

```sh
python3 tools/campaign_settings_fixture.py --server server-release.jar --create-in /path/to/fixtures
```

Run Gradle with the pinned runtime properties and
`-Pprobe=singleplayer runGraphicalCampaignSettingsJvm --args="/path/to/fixtures /path/to/new-scratch-dir"`.
It checks unlocks and partial research at content startup, the stock sector
metadata, renamed-slot selection, language selection, the settings backup, and the research update
after saving.
Passing the same fixtures to `tools/verify_upstream_campaign_resume.py`
checks the initial menu selection and saves a copy of the native settings for
each run. The replay uses a separate virtual SD for every model and scenario. The stock reader can reopen the paired files with
`python3 tools/campaign_settings_fixture.py --server server-release.jar --check /path/to/new3ds-single-settings.bin --save /path/to/new3ds-sector-serpulo-170.msav --sector 170 --after-play`
and `--sector 64` without `--after-play` for the two-slot run. The replay
allows the active sector’s stock production average to decay while idle.

## Campaign research

Press **L + SELECT** in a campaign sector to open its research tree. Use
D-pad up/down to browse the original tech nodes and **A** to view a node.
The detail screen shows upstream objectives, the amount already contributed,
and resources available for each requirement. Use **A** to contribute and
**B** to return to the list or close the screen.

Research follows the original v160.8 objective and unlock rules. It consumes
resources from other active bases on the same planet before spending from
the live core. The JVM reference checks that a remote base with 300 copper
pays exactly 100 copper for Junction, leaves the active core untouched, and
updates the current menu's available total. Partial research and unlocks
use the original Arc settings
keys, and the offline app saves the active sector after a contribution.

The JVM reference opens stock Ground Zero and Onset saves. It checks the
Frozen Forest objective, persists a 3-of-5 copper contribution to Conveyor,
and tests completion across live and remote inventories. On Erekir it checks
the original planet objective and the effective four-beryllium Duct cost. The
unmodified v160.8 server reopened the resulting Serpulo and Erekir saves and
settings. The integrated JVM progression test researches Junction and Router,
captures Ground Zero through the stock Logic event, and automatically
unlocks and launches the original Frozen Forest. The stock Java server
reopened both saved sectors with the same capture and unlock settings.

The [ARM11 build from `428ae40`](https://github.com/levkeveli/Min3DStry/actions/runs/45050806782)
opened the research screen on both Old and New 3DS profiles in Azahar. The
controller replay partially researched Conveyor in Ground Zero and unlocked
Duct in Onset on each profile. The unmodified v160.8 server reopened all
four sector saves and settings pairs and confirmed the inventories and
research progress. This build predates the merged live capture integration.

The [current ARM11 campaign build](https://github.com/levkeveli/Min3DStry/actions/runs/45130577809)
repeated the four controller cases on the merged launch runtime. Stock
v160.8 reopened each result and read back the expected research and core
inventory. Both profiles also replayed Ground Zero capture, Router research,
and Frozen Forest launch and save. Stock v160.8 reopened both sectors from
each replay. The verifiers keep their virtual SDs under the output directory.

The [integrated ARM11 build from `522c8ca`](https://github.com/levkeveli/Min3DStry/actions/runs/45058012737)
passed a continuous progression replay on both Azahar profiles. A stock
final-wave Ground Zero save reached capture through the upstream simulation.
The replay researched Router with controller input, unlocked Frozen Forest,
selected it for launch, rendered the original 200×200 world, and saved.
The stock server reopened both saves and confirmed the capture, research,
unlock, and launch origin on each profile.

Reproduce that replay with the integrated campaign ROM and a stock checkpoint.

```sh
python3 tools/campaign_research_fixture.py --server server-release.jar --create-progression-in /path/to/checkpoint
python3 tools/verify_upstream_campaign_progression.py --rom upstream-probe.3dsx --fixture /path/to/checkpoint --server server-release.jar
```

Generate fixtures with the stock server and copy the original
`bundle.properties`, `planets/serpulo.json`, and staged campaign `maps/`
directory into the fixture directory for the JVM reference. Use the regular pinned runtime Gradle properties.

```sh
python3 tools/campaign_research_fixture.py --server server-release.jar --create-in /path/to/fixtures
gradle -Pprobe=singleplayer runGraphicalCampaignResearchJvm --args="/path/to/fixtures /path/to/new-scratch-dir"
python3 tools/campaign_research_fixture.py --server server-release.jar --check-progression /path/to/new-scratch-dir/serpulo
python3 tools/verify_upstream_campaign_research.py --rom upstream-probe.3dsx --fixtures /path/to/fixtures --server server-release.jar
```

The New 3DS research tree uses the original positions and branches from
v160.8 `ResearchDialog` and `BranchTreeLayout`. The D-pad or Circle Pad
selects a node and centers it on the top screen. A or the Info touch button
opens the objectives and research requirements. Zoom with the C-Stick
or the touch buttons. Y opens the Core Database and closes the research
screen, matching the stock research menu. Unlocking a node reveals
its descendants in their original positions. The stock JVM reference
compares the complete Serpulo and Erekir layouts, and the emulator verifier
captures the tree before examining the node details and stock save readback.
The [New 3DS replay at `072bc13`](https://github.com/levkeveli/Min3DStry/actions/runs/45742351182/artifacts/15083329350)
partially researched Conveyor on Serpulo and unlocked Duct on Erekir.
Stock Java reopened both saves and checked their inventory and research
settings. The globe replay zoomed to 90%, switched from Serpulo to Erekir
and back, and contributed to Conveyor without rewriting the stored sector.
The replay checks that the original root icons render inside their nodes.

## Serpulo background production and exports

The offline session advances the original universe through
`Logic.update()`. The TeaVM reflection policy includes `CampaignRules`
so Arc can restore persistent planet options, including legacy launch pads,
from the stock settings file.

The stock v160.8 fixture opens Ground Zero, produces copper in Frozen
Forest, and keeps Cratered Battleground frozen under attack. One campaign
turn delivers 30 copper to the active core, caps the producing sector at
100 copper, floors its consumed lead at zero, and leaves the frozen
sector at 11 copper. The port JVM runs the same scenario through 7,200
ordinary simulation ticks. Stock Java reopens the resulting save and
settings and verifies the turn, active core, remote inventories, and
legacy launch pad setting. The generated TeaVM C reference produced the same
result on the host and stock Java reopened its output. The Linux smoke job
runs that same reference and stock readback. The graphical replay checks
the same turn from the campaign menu. It saves at intervals and waits
for the first checkpoint with the imported copper so render speed
does not determine when the check runs. The two remote sector map
files must stay byte-identical.

```sh
python3 tools/campaign_background_fixture.py --server server-release.jar --create /path/to/fixture
# In research/upstream-runtime, with the pinned Mindustry and Arc Gradle properties:
gradle -Pprobe=unified runBackgroundCampaignTurnJvm --args="/path/to/fixture /path/to/assets /path/to/assets/bundle /path/to/new-output"
python3 tools/campaign_background_fixture.py --server server-release.jar --check /path/to/new-output
```

`tools/verify_upstream_campaign_background.py` accepts the unified ARM11
ROM and the unmodified v160.8 server JAR. It creates a separate virtual
SD per emulator profile and reopens each resulting save with stock Java.

The dedicated ARM11 `campaign-background` probe runs the same original
simulation ticks independently of graphical frame pacing. Generate it
with `-Pprobe=campaign-background generateC` and compile the output with
`arm11/build.sh <generated-c> <output> --campaign-background`, which uses
the same native stack size as the graphical game. Run
`tools/verify_arm11_background_turn.py --rom upstream-probe.3dsx --server server-release.jar --assets /path/to/prepared-assets --output /path/to/new-output`.
Use maps, planets, and the base bundle staged from the pinned original
assets. Both emulated console profiles produce a result directory for
stock Java readback.

The [dedicated ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45181223120)
passed one complete stock turn on both Azahar 2126.2 profiles. Stock Java
reopened the resulting settings and save with 37 copper in the active
core, 100 copper and zero lead at the producer, and 11 copper at the
attacked sector. Both remote map files remained byte-identical.

The [unified graphical build](https://github.com/levkeveli/Min3DStry/actions/runs/45177492172)
passed the same stock Java readback on both Azahar profiles after opening
Ground Zero from the campaign menu. The verifier captured the rendered
world, saved the completed turn through SELECT, and checked both
unchanged remote world files. The build also includes the campaign retry
collector fix from #340.

## New campaign

Hold **R** while launching the standalone graphical .3dsx to open the
campaign selector. The selector includes a new Ground Zero or Onset
campaign when that planet does not already have a starter save. Use D-pad
up/down to select and **A** to start. **B** or **START** exits the selector.
New games use the original sector generator and write the original
campaign .msav and Arc settings files in the same locations as resumed games.
The renderer loads each visible unit’s original atlas regions and weapon art
when it first encounters that unit type.

The [ARM11 build from `2c8f6e7`](https://github.com/levkeveli/Min3DStry/actions/runs/45043634996)
showed the controlled unit and saved successfully in Ground Zero and Onset
on both Azahar profiles. The stock v160.8 server reopened all four saves
and settings pairs.

## Campaign map assets

The offline package stages the 78 Serpulo maps and 17 Erekir maps from the
pinned v160.8 assets archive. It preserves their paths under
`maps/serpulo/` and `maps/erekir/`, including the original Serpulo hidden
map directory. The graphical app mounts these paths before Mindustry creates
its `FileMapGenerator` instances. The existing `maps/groundZero.msav`
path remains available for the standalone Ground Zero mode.

The host task `runGraphicalCampaignPresetMapsJvm` takes a staged assets
directory with `maps/`, `planets/` and `bundle.properties`, and a new
scratch directory. It loads the Ground Zero and Onset starters through
the original world generator, then checks the sector IDs, starter cores,
launch event, and the saved worlds. The stock v160.8 server reopened both
JVM-generated saves with the same world size, core, and rules as saves
from a fresh stock launch. The JVM reference also checks the stock numbered
landing rule. The test seeds the last wave with no enemies and lets the
upstream game loop capture Ground Zero. It then upgrades to a Core Foundation,
checks the original default launch schematic and the maximum cargo preference,
and verifies that the origin pays for the schematic and the loaded cargo.
The new Serpulo sector opens with 23 copper and Ground Zero resumes with the
remaining inventory. The stock server reopens the new sector and the still
captured origin, and confirms that the cargo was deducted from the old base.

The campaign selector includes open launch destinations when a stock landing
candidate exists. Choose a destination to review the selected schematic, its
origin, and the total items that will be debited. Copy stock Java `.msch`
files to `/3ds/min3dstry/schematics/` to use valid custom launch schematics.
Use D-pad left/right to choose a schematic and up/down to review additional
items. **X** switches between maximum cargo and a manually selected loadout.
With maximum cargo off, **Y** opens the cargo editor. Use D-pad up/down to
select an item and left/right to change its quantity. Hold **R** to adjust
one item at a time. **A** or **B** returns to the summary. There, **A**
confirms an affordable launch and **B** cancels. The saved cargo changes
when the launch is confirmed.

During an active campaign, hold **R** and press **SELECT** to save the
current sector and open the campaign selector. **B** returns to the current
sector. Selecting another sector resumes its save or starts its launch
sequence in the same app. Regular **SELECT** saves, and **START** exits play.

Use `python3 tools/verify_upstream_campaign_launch.py --rom upstream-probe.3dsx`
to replay the fresh campaign menu in Azahar New 3DS mode. The verifier
captures the menu and starting worlds, then collects the resulting saves and
settings from an empty test SD directory. Pass `--server server-release.jar`
to compare those outputs with a fresh stock Java launch.
Pass `--departure /path/to/reference-output/departure` to replay the funded
numbered-sector launch fixture produced by the JVM task. With `--server` it
also verifies the saved cargo and origin with stock Java and compares the
generated floor, overlay, and block IDs with a fresh stock launch. Add `--manual-cargo`
to exercise the editor and transfer 8 copper while leaving 15 in Ground Zero.
Use `--in-game` to start at Ground Zero, open and cancel the selector,
then reopen it and depart without restarting the app.
The in-game replay checks the selected destination against the original
generator's eligible-sector catalog in the JVM fixture.
The game shows the destination and the upstream loading label while terrain
generation runs. Numbered-sector movies allow extra time for the original
procedural generator before sending the exit input.

The [ARM11 build from `24338a2`](https://github.com/levkeveli/Min3DStry/actions/runs/45048947159)
rendered the numbered-sector launch dialog in the New 3DS profile and
accepted the 8-copper edit. With the longer controller movie, the stock
432×432 sector 15 generator exhausted its 32 MiB TeaVM heap before the world
opened. GDB stopped at a 7,784,656-byte `PQueue` allocation in the upstream
Serpulo generator's A* path search. Arc's visited-tile bitset uses Java's
masked shift counts, while the generated C lookup did not mask the index.
ARM11 staging restores those masks so positions above 63 can be found
without repeatedly adding the same tiles. The JVM reference completed the
original launch and the stock PC server reopened both saves and matched the
destination terrain.

The [ARM11 build from `5aecd95`](https://github.com/levkeveli/Min3DStry/actions/runs/45053996180)
generated 432×432 sector 15 at the original 32 MiB managed limit on both
Azahar profiles. The controller edited the loadout to 8 copper. The
unmodified v160.8 server reopened both initial saves and matched the CRC
across floor, overlay, and block IDs for all tiles against a fresh stock
launch using the same schematic. It confirmed the selected schematic,
destination cargo, and 15 copper remaining on the origin. Both profiles
completed the exit save and PC readback. In separate in-game replays on both
Azahar profiles, the controller opened, canceled, and reopened the selector
from captured Ground Zero and launched eligible sector 63 at 430×430. The
stock v160.8 server reopened both exit saves and confirmed the original
terrain, 8 copper at the destination, and 15 remaining in the captured origin.

The [current unified ARM11 replay](https://github.com/levkeveli/Min3DStry/actions/runs/45119739751)
launched Ground Zero and The Onset from fresh data directories and reopened
all four save/settings pairs in stock Java v160.8. The same ROM generated
and saved 432×432 Serpulo sector 15 with 23 copper on both Azahar profiles.
Stock Java reopened both exit saves and matched the original terrain,
departure origin and cargo.

The build's ELF LOAD segments plus the logged managed limit, linear heap,
and stack total 64.58 MiB. This exceeds the 64 MiB Original 3DS application
budget even before other runtime allocations. Physical memory and frame-rate
verification remain open.

## Campaign defeat and retry

If your last core is destroyed, the game saves the defeated sector and
shows its original localized loss message. Press **A** to continue to the
campaign selector or **START** to exit. A defeated starter sector can be
retried from its original deployment. Other sectors use the same landing
and cargo rules as a new launch.

Retry follows Mindustry v160.8's clear-sector rules. Under the default
campaign configuration, Ground Zero retains the original spawn, surviving
derelict buildings and rebuild plans. The Onset regenerates without carrying
those buildings over. The retry writes to the same original sector slot.

With the prepared campaign assets and pinned runtime Gradle properties, use
`-Pprobe=singleplayer runGraphicalCampaignDefeatJvm --args="/path/to/assets /path/to/new-scratch-dir"`
to generate and reload starter saves with hostile units attacking a weakened core. The
reference lets the original simulation cause the loss, then retries both sectors. Run
`python3 tools/verify_upstream_campaign_defeat.py --server server-release.jar --reference /path/to/new-scratch-dir`
to reopen the defeated and recovered saves and settings in the unmodified PC
server. The server checks the core, retry attempt, and surviving derelict
state. Add `--rom upstream-probe.3dsx` to replay the loss and retry with
controller input on both Azahar profiles before running the same PC
readback. The [ARM11 build from `3de099b`](https://github.com/levkeveli/Min3DStry/actions/runs/45055970330)
passed in Original and New 3DS profiles in Azahar 2126.2. Hostile units
destroyed the last core in Ground Zero and The Onset. The controller retried
each sector and saved the original 256×256 and 512×512 worlds, respectively.
Stock v160.8 reopened the defeated and recovered saves for all four replays
and verified the persisted sector state.

The later unified build exposed a collection bug when a retry rebuilt the
upstream Pathfinder cache. TeaVM C's multi-dimensional allocator kept its
partially built arrays only in C locals. ARM11 staging now registers each
parent with the collector and applies the write barrier as it adds children.
The campaign replay exercises this path without altering the upstream
pathfinding or save format.
The logic smoke job runs `arm11/check_multi_array_roots.py` on its
generated TeaVM C to force collection between child allocations and
verify that all child arrays survive the next collection.

The [ARM11 build with the collector fix](https://github.com/levkeveli/Min3DStry/actions/runs/45173897060)
passed defeat, retry, and stock Java readback for Ground Zero and The Onset
on both Azahar profiles. The same verifier reopens the defeated save
before checking the recovered save and its campaign settings.
The [integrated build](https://github.com/levkeveli/Min3DStry/actions/runs/45177492172)
also passed Ground Zero on the New 3DS profile and The Onset on the
Original 3DS profile with the current campaign research and offline
audio code. Stock Java reopened all four resulting checkpoints.

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

`SELECT` saves a new Custom Game to
`/3ds/min3dstry/custom-game-save.msav` using upstream `SaveIO`.
Select **Load Game** at the top of the Custom Game list to resume that save.
Copy non-campaign PC saves into `/3ds/min3dstry/saves/` to select them by
name from the same menu. The menu uses the stock save name from settings
when present and the filename otherwise. `SELECT` writes the active save
back to its own slot and retains the upstream backup. Map exports still
belong under `/3ds/min3dstry/maps/`.

`tools/verify_stock_custom_save_slots.py` creates Glacier and Ancient
Caldera saves with unmodified Java v160.8. It selects each on ARM11, saves
Glacier, checks the original backup and the untouched Caldera file, then
reopens the resulting Glacier save in stock Java.
The [unified ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45185562919)
passed this replay on both Azahar 2126.2 profiles. The stock Arc sort put
the newer Caldera save first. The replay then saved Glacier and verified
that both the Caldera and the campaign files stayed byte-identical.

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
pathfinder grids, and sprite atlas. The startup log reports the managed
limit so device budget reports can account for it. In the preceding 24 MiB
build, the Old 3DS
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
| Tap `X` | Toggle simulation pause or resume on release |
| Hold `X` and use Circle Pad / D-Pad | Set the aim direction without moving |
| Hold `L` | Fire the current unit's original weapons |
| `Y` | Trigger next enemy wave (`UPSTREAM_SP_WAVE`) |
| `SELECT` | Save `.msav` snapshot to `/3ds/min3dstry/singleplayer-save.msav` (`UPSTREAM_SP_SAVED`) |
| `START` | Exit application cleanly |

## Stock player combat

The offline app sets aim and the trigger through the original
`Unit.aim` and `Unit.controlWeapons` methods. The pinned simulation
handles reload, recoil, delayed shots, projectiles, and damage.
The chosen aim direction stays active when the aim button is released,
so players can move while firing. A quick X tap pauses even with the
movement control held. Hold X and a direction for a quarter second
to switch to aiming.

The stock Glacier replay uses Gamma to damage an opposing Fortress.
The fixture applies the original disarmed and unmoving statuses to keep
the target in place. The unmodified Java server checks the saved unit
health after the controller replay.

The replay checks that the Fortress takes damage and that the stock
weapon mounts schedule no further shots after L is released.
The precise damage depends on the number of simulation ticks that
occur during the replay.

On the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45091376386),
both Azahar 2126.2 profiles paused during D-pad movement and
resumed at the same simulation tick. The two shots recorded at trigger
release were still the only shots at save time. Stock Java reopened
both saves and read the Fortress at 896 of 900 health.

```bash
python3 tools/verify_offline_stock_weapons.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-stock-weapons
```

Run the JVM reference with a stock Glacier fixture from the verifier
and a scratch save path.

```bash
bash /path/to/Mindustry/gradlew -p research/upstream-runtime \
  -PupstreamCheckout=/path/to/Mindustry -ParcCheckout=/path/to/Arc \
  -PreferenceJar=/path/to/server-release.jar -Pprobe=unified \
  runOfflineWeaponsJvm "--args=/path/to/stock-fixture/config/saves/weapons.msav /tmp/weapons-jvm.msav"
```

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

## Original units and projectiles

The offline renderer uses the unit types and weapon effects initialized from
the v160.8 content registry. It loads each visible unit's art from the
original atlas, including a unit drawn as a payload, and loads each visible
projectile before invoking its upstream draw routine.

The combined [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45116142229)
drew Elude, Stell, Scepter, Mega and its carried Flare when both emulator
profiles reopened the stock Java Glacier save. The same ROM passed the
live roster replay with Elude, the Anthicus missile and Mega carrying
Horizon on both profiles.

The [ARM11 test build](https://github.com/levkeveli/Min3DStry/actions/runs/45066925380)
includes the stock textured-quad renderer from #219. On both Azahar 2126.2
profiles it drew Elude, Mega carrying Flare, and hostile Stell and Scepter
from a Glacier save written and reopened by the unmodified v160.8 Java server.
The verification uses a separate virtual SD directory per profile and saves
the stock server log, emulator logs, and screenshots.

```bash
python3 tools/verify_offline_unit_roster.py \
  --rom /path/to/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-unit-roster
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

The verifier distinguishes the two stock pause rules. A pause-enabled world
must log `UPSTREAM_SP_PAUSE paused=true` and `paused=false`. A world with
`rules.pauseDisabled` must log `UPSTREAM_SP_PAUSE disabled=true`; the pause
menu still opens and closes, but the simulation keeps running. Menu traffic
alone no longer satisfies the pause milestone, so a broken pause path cannot
pass just by drawing the overlay.

## Original floor and wall extractors

The offline and live building passes use the v160.8 renderers for all six
rotary and burst drills, both plasma bores, and both cliff crushers. The
original draw code uses the stock atlas and the ore, rotation, and wall
targets supplied by the world.

The JVM reference covers all ten definitions. It checks the original base,
the floor ore or wall target, changes in the working draw state, and the
stock beam texture for both plasma bores.

The stock server writes and reopens two Glacier saves with the same ore
and walls. The populated save adds a Laser Drill, Impact Drill, Plasma
Bore, and Cliff Crusher without moving the original core. The replay
compares those four sprites offline and after joining the unmodified
v160.8 server.

On the Original and New 3DS Azahar profiles, all 25 samples changed at
each extractor in the offline and live views from the
[ARM11 test build](https://github.com/levkeveli/Min3DStry/actions/runs/45114638541).
The previous Original 3DS build changed zero samples at those placements.
The working-state draw checks ran on the JVM.

```bash
python3 tools/verify_stock_extractors.py \
  --rom /path/to/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-extractors
```

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

### Original material buildings in Java multiplayer

The live client uses the same material-building classification and upstream
draw calls. The building state comes from the stock v160.8 server. The JVM
live draw reference checks the 49 stock material buildings outside the
already supported cores.

The live replay loads the same two Glacier saves on an unmodified v160.8
server. It joins each save through the unified client, holds the same
viewport on both runs, and compares the captured pixels at the seven
stock buildings. The logs and images are retained for each console profile.
Both Original and New 3DS profiles changed all 25 sample pixels at each
placement with the [ARM11 replay build](https://github.com/levkeveli/Min3DStry/actions/runs/45113545813).
The previous build left all 25 pixels unchanged at the smelter, compressor,
electrolyzer and separator on the Original 3DS profile.

```bash
python3 tools/verify_live_material_buildings.py \
  --rom /path/to/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/live-material-buildings
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

## Original item distribution graphics

The offline and live renderers use the stock v160.8 distribution drawers.
The original world atlas supplies carried item sprites. Item bridges and
duct bridges draw their stock spans from the original link state. Cargo
loaders use the same construction shader adapter as original unit factories.

The offline and live JVM tests exercise all 28 distribution buildings,
including the sandbox item source and void. The offline reference also
checks carried copper, the bridge spans, and the Manifold construction
overlay. The ARM11 verifier
uses stock Glacier saves with seven representative distribution buildings,
a loaded Plastanium Conveyor, and linked Item and Duct Bridges.

```bash
python3 tools/verify_offline_distribution.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-distribution --live
```

Both Azahar profiles drew all seven buildings, the carried stack, and the
two bridge spans in the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45081828980).
The same checks passed after each profile joined the unmodified Java v160.8
server using the active save.

## Original liquid buildings

The offline and live renderers draw the original v160.8 liquid catalog
through the stock building drawers. Pumps, conduits, junctions, tanks,
routers, and bridges use their upstream sprite layers. The drawers read
liquid type, fill, and link state from the original simulation and saves.
The network renderer keeps stock conduit fill interpolation.

The JVM references exercise all 21 stock liquid buildings. The offline
reference also checks filled water and nitrogen states and linked bridge
spans. The ARM11 verifier replays an original Glacier save with eight
representative Serpulo and Erekir placements, filled tanks, a configured
sandbox source, and both kinds of bridge link.

```bash
python3 tools/verify_offline_liquid_buildings.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-liquid-buildings --live
```

The [ARM11 replay](https://github.com/levkeveli/Min3DStry/actions/runs/45083291894)
drew all eight buildings on both Azahar profiles. Each profile showed
water and nitrogen in the tanks, the configured source, and both bridge
spans when loading the save offline and after joining the unmodified
Java v160.8 server.

## Erekir heat and neoplasm

The same world pass now draws the original heat conductors. Their stock
rotation, heat-input layer, and additive heat-output layer use the pinned
Mindustry atlas. The upstream simulation supplies the heat and consumption
state.

`tools/verify_erekir_heat_fixture.py --create` takes the original
`maps/erekir/onset.msav` from v160.8. It creates cold and powered saves in
stock Java using the neoplasia reactor, beam nodes, heat redirector, carbide
crucible, and reinforced liquid tank. The reactor produces 60 heat, of which
the three-tile contact transfers 36. Stock Java and `runErekirHeatJvm`
verify production, the effect of rotating the redirector, recovery after
water starvation, and the original pressure event when the outlet fills.
The port JVM also checks that the destroyed reactor deposits neoplasm
puddles. Use
`--check` to reopen the port-written save in stock Java. With a unified
ARM11 build, use `--fixture heat-warm.msav --rom upstream-probe.3dsx`.
The warm checkpoint starts with an active reactor and empty output
buffers. The replay checks the cold and active redirector pixels and
requires that the port has produced carbide and neoplasm by the time
it saves. Stock Java checks the save immediately on load.

The [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45137803532)
showed the active heat layers on both Azahar console profiles. Stock
Mindustry v160.8 reopened the two ARM11 saves and read back the original
reactor heat, carbide, and neoplasm.

### Erekir ducts

`UpstreamErekirDuctProbe` runs the original duct, armored duct, duct router and
duct unloader blocks inside the port's offline tick loop on the stock
`maps/erekir/onset.msav`. It asserts the pinned speed, capacity, armored and
bridge-replacement flags, keeps the overflow/underflow polarity, and checks
that a two-duct line still delivers 20 copper items by tick 10, that the
armored duct takes its input through the facing edge, and that a sorted duct
router forwards its item into the facing duct.

```
./gradlew -p research/upstream-runtime -Pprobe=erekir-ducts \
  -Pglacier=/path/to/onset.msav runErekirDuctsJvm
UPSTREAM_EREKIR_DUCTS duct=4 armored=4 unloader=4 router=4 arrival=10 delivered=20 armoredArrival=10 armoredDelivered=8 bridge=duct-bridge overflow=normal underflow=inverted sort=copper routerDelivered=1
```

The unified-build smoke job runs the same probe and greps that marker, so the
stock duct family stays covered without a handheld run.

## Original liquid puddles

The world renderer submits upstream puddle drawing with the original
atlas. The stock layer order keeps water below buildings and draws the
neoplasm cell overlay above a solid block. The shared pass works with
offline saves and entity snapshots from a stock Java server.

Run `tools/verify_offline_stock_puddles.py` with the original server JAR
and a unified ARM11 ROM to compare clear and active Glacier saves.
Run `tools/verify_upstream_live_net.py` with
`--controls --verify-puddles --terrain-atlas /path/to/sprites.aatls`
to check the same water and neoplasm colors over a stock network session.

The [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45140847819)
passed offline saves and live entity snapshots on both Azahar profiles.
Both profiles showed 81 changed water pixels. The neoplasm samples
included the area around the solid wall and passed in both modes.

## Original turret graphics

The offline and live world passes use the original v160.8 turret drawers
for Serpulo and Erekir. The drawers use the stock atlas and existing
turret state for heading, recoil, heat, charge, and ammunition or liquid
layers. The port continues to use upstream targeting and ammo rules.

The offline JVM reference exercises idle and active draw commands for
all 29 turret definitions. The live renderer reference draws the same
catalog through its own building predicate. The ARM11 replay loads
Glacier saves produced by the unmodified Java v160.8 server and checks
Wave, Ripple, Parallax, Segment, Breach, and Sublimate at two headings.

The [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45079715312)
drew all six turrets on both Azahar profiles. All six headings changed
the sampled turret pixels. Add `--live` to load the same fixture on the
unmodified Java server and check the sprites after joining from Azahar.
The live replay tests the stock snapshot and draw path. The JVM reference
tests active draw states including recoil, charge, and the Parallax beam.

```bash
python3 tools/verify_offline_turret_buildings.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/offline-turret-buildings
```

## Original Canvas and Logic Displays

Offline games draw the v160.8 Canvas and Logic Display blocks in the original
world pass. The app uses the original logic ID table and Logic Display font
from RomFS. The original offline simulation runs the saved processors, and
world markers can sample the same framebuffer as a visible or offscreen
display.

Run `tools/verify_stock_offline_logic.py` with a unified ARM11 ROM and the
stock v160.8 server JAR. The verifier creates an original Glacier save with
a Canvas, a processor, normal and tiled displays, and a linked texture marker.
It checks that the program resolves the original Copper ID, draws the
displays and marker, and changes a Canvas pixel. The stock server reopens
the handheld save and checks the processor links, marker and Canvas data.

The [combined ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45129961401)
passed this replay on the Original and New 3DS profiles in Azahar. Stock
Mindustry v160.8 reopened both saves and verified the updated Canvas pixel,
three processor links and the display-linked marker.

### Renderer timing

Create an empty `sd:/3ds/min3dstry/frame-perf.txt` before launching the playable
ROM. The renderer emits `UPSTREAM_FRAME_PERF` once per 16 displayed frames.
Each record gives the first and last viewport origins, sums of elapsed guest
microseconds, texture cache misses and loads, and the encoded bytes consumed
by atlas imports. Remove the flag to disable measurements.

Compare a stationary window after the cache warms up with a panning window.
Divide each time by `frames` for the window mean. Terrain and bottom-screen
times cover CPU submission and any waits inside those sections. Initial frame
begin and mid-frame recycle times measure blocking calls, while atlas loading
covers opening and importing the asset. These intervals can overlap with
terrain time. Frame time spans renderer begin to submission and excludes game
simulation before renderer begin. Azahar guest timings help locate the cost.
Physical console frame rates require a separate capture.

On the unified ARM11 build from `cd37f9ed`, both Azahar profiles repeatedly
imported 12 atlas fragments and recycled the render pass once per frame after
the Ground Zero viewport settled at `121,49`. A representative 16-frame window
averaged about 233 ms per renderer frame, of which 213 ms was spent importing
atlas fragments. A window panning from `117,49` to `109,49` imported 125
fragments across 16 frames and averaged 159 ms per renderer frame. These are
guest timings from the emulator with the same stock Ground Zero save.
