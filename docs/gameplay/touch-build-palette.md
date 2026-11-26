# Touch build palette (Issue #27)

Status: **Emulator-tested prototype** on Azahar `2126.2` New and Original 3DS
profiles. The live ARM11 client drives the stock v160.8 server's build queue;
full upstream catalog/layout parity remains open.

## Scope

- Bottom-screen palette with Conveyor, Copper Wall, Rotate and Remove, plus a
  16 x 12 grid aligned to the top-screen viewport.
- The stock `PlacementFragment` visibility/unlock/order logic builds the
  catalog; page/category navigation and block/item icons come from the
  original atlas.
- Touch input is replayed through Azahar CTM movies, so placement, rotation,
  removal, catalog paging and Sorter configuration are emulator-input-tested.

## Verified

- Conveyor and Copper Wall placed on Glacier at the selected cells; removal
  refunded three copper and kept the adjacent Conveyor.
- Catalog page 2 placed the original Router; copper `196 -> 193`.
- Sorter configuration received Copper and Lead through the stock
  `Building.configureAny` path and cleared correctly.
- The German bundle rendered translated rows such as `Phasen-Förderband`,
  `Überlauftor` and `Kupfer`.
- The ARM11 client drew the constructed sprites after each stock-server
  acknowledgement.

## Stock schematics

Copy Mindustry v160.8 `.msch` files into
`sd:/3ds/min3dstry/schematics/`. Tap **Schem** on the left edge of the
build grid and choose a file. Tap a grid cell to position it. The top
screen marks every planned block and shows which placements
the game's build rules would reject.

Press **R** to rotate the preview, **A** to queue valid plans, or
**B** to cancel. The original game reads the
schematic, rotates relative configurations, applies unlock and world
rules, and builds through the normal queue. Maps and servers can
disable schematic placement.
[Pinned schematic implementation](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/core/src/mindustry/game/Schematics.java)

The stock JVM test covers a three-block schematic with two linked
Bridge Conveyors and a Sorter configured for Lead. It checks invalid
files, disabled schematics, cancellation, and the relative link and
item configuration after rotation.

```bash
python3 tools/verify_stock_schematics.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-schematics --live
```


The replay passed offline and in stock v160.8 Java multiplayer on
both Original and New 3DS profiles in Azahar 2126.2. The original
release verified the built blocks, rotated bridge link, Lead filter,
and spent construction materials. It also reopened both ARM11 saves
and verified the same results.
[ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45103432419)

To create a file, tap **Schem**, choose **Capture**, and tap two
opposite corners on the build grid. The top screen marks the
selection. Press **A** to open the naming keyboard or **B** to cancel. The original
capture routine crops the selection and preserves visible buildings,
rotations, and configuration. The picker shows the name stored inside the
schematic. New captures use the next free `Schematic-N.msch` filename and
can be imported into the unmodified Java game.

Entering an existing schematic name opens a replacement prompt. Confirm in
the prompt to replace the layout, or cancel to keep the saved file.
Replacement preserves the existing description and labels.

To change a saved schematic's metadata, tap **Edit** and choose a row.
The keyboard opens with the current name and then the current description.
Confirm both fields to save the changes to the same file. Cancel either
prompt to keep the previous metadata.

The **Share** mode uses `sd:/3ds/min3dstry/schematics/clipboard.txt` in
place of a system clipboard. For import, save the text from Mindustry's
**Copy to Clipboard** action in that file, then tap **Share** and
**Import**. The original clipboard reader decodes the text and the
picker saves a new `.msch` file. For export, tap a schematic row while
in **Share** mode. Copy the resulting text file into the original
game's clipboard import. Export replaces the text file. The saved
schematic keeps its original layout and metadata. From the title
screen, **Database → Schematics** also offers **Import** at the end of
the library and **Export** in each schematic's details. Add
`--clipboard` to the schematic replay above to verify both the title
library and in-game sharing. The replay requires the exported text
to replace the Workshop-tagged input and uses unmodified Java
v160.8 to check its contents.

The ARM11 build at `e1a88dc` passed the title, offline and multiplayer
share replays in New 3DS Azahar 2126.2. Unmodified Java v160.8 read the
exported text from all three routes. It confirmed in both the offline
save and the live session that rotating and building the schematic
spent materials, linked both Bridge Conveyors, and kept the Sorter's
Lead filter.
[Exact ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45743676841)

The hosted New 3DS replay used `verify_stock_schematic_capture.py --xdotool
--edit` to change both fields. Unmodified Mindustry v160.8 Java reopened
the file and verified the original block layout, bridge link, and Lead filter.
[New 3DS metadata replay](https://github.com/levkeveli/Min3DStry/actions/runs/45309147560)

The capture check selects two linked Bridge Conveyors and a Lead
Sorter. It also checks that a cancelled or empty selection creates no
file, that capture follows the viewer's discovery state, and that an existing
file keeps its contents.

```bash
python3 tools/verify_stock_schematic_capture.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-schematic-capture --live
```


Both Original and New 3DS profiles in Azahar 2126.2 exported the
selection offline and while connected to the original Java server.
The unmodified v160.8 reader verified the exact three buildings, the
Lead selection, and the bridge link. The preexisting file was unchanged
in all four runs.
[ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45106252940)

The [New 3DS naming replay](https://github.com/levkeveli/Min3DStry/actions/runs/45300000652)
entered a name through the 3DS keyboard offline and in a live session.
The stock Java reader reopened both files and verified the bridge and sorter.

## Stock item selectors

Tap **Config**, then a building with a stock item choice. The palette
supports both Sorters, Duct Router, Surge Router, Unloader, Duct Unloader,
Item Source, Unit Cargo Unload Point, and Landing Pad. It uses the
original item order and the selection rules from upstream
`ItemSelection`. The current filter opens at its saved page.

Choosing a row sends the original item through `Building.configure`.
Choosing the selected item again calls the stock clear handler.
Landing Pad availability follows its original game-mode rules.
The JVM reference tests all nine blocks and pages to Beryllium using
Erekir's item catalog.

```bash
python3 tools/verify_stock_item_selectors.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-item-selectors --live
```

The [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45084844883)
passed the replay on both Azahar 2126.2 profiles. It opens a Duct Router
with a saved Beryllium filter highlighted on page 2, clears the filter,
and selects Tungsten on a Duct Unloader. The unmodified v160.8 Java
server verified those final selections in both ARM11 saves and during
live sessions on each profile.

## Power and transport links

Tap **Config**, then tap a power node, item or liquid bridge, mass
driver, or payload mass driver. The grid stays open and highlights
the source. Tap an endpoint to connect or disconnect it using the
original block's team, range, and alignment rules. Tap a power node
again to use its original automatic link or clear action. When a
bridge is linked in one direction, configure the receiving bridge
and tap the sender to reverse it. Tap **Config** or press **B** to
exit link configuration.

The JVM reference covers graph joins and disconnects, enemy and
out-of-range endpoints, and all seven configurable item, liquid,
and payload blocks with manual links. It checks stock bridge reversal
and confirms that link taps leave the construction queue untouched.
The packet reference also round-trips the original packed positions
and relative power link arrays.

```bash
python3 tools/verify_stock_building_links.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-building-links --live
```

Both Original and New 3DS profiles in Azahar 2126.2 passed the replay
offline and in a live v160.8 Java server session. The selected conduit
was highlighted on the touch grid. The stock server verified a joined
power graph, item bridge disconnection, and conduit connection in each
live session and in both offline saves.
[ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45100722586)

## Inventory transfers

Tap **Items** and then a storage building to see its item quantities.
Tap an item row to take what the current unit can carry. The list uses
the original item icons, names, and inventory order. In multiplayer the
client requests the building's stock inventory snapshot when it opens.

Tap **Give** and then a building to deposit from the carried stack.
The stock building decides how much it can accept, including ammunition
capacity. Team rules, transfer range, and deposit cooldown apply in
both offline play and Java multiplayer.

The JVM reference withdraws 70 copper into a Gamma and loads the 15
copper a Duo can accept. It also checks incompatible carried items,
hostile and distant storage, and the stock deposit rules.

```bash
python3 tools/verify_stock_inventory_transfer.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-inventory-transfer --live
```

The [ARM11 replay](https://github.com/levkeveli/Min3DStry/actions/runs/45087989910)
passed on Original and New 3DS profiles in Azahar 2126.2. The stock
v160.8 Java server reopened both offline saves and verified the same
Container contents and Duo ammunition. In both live sessions it also
verified that the player still carried 55 copper after loading the Duo.
Hold-to-withdraw a single item remains open in #27.

## Unit and turret control

Tap **Ctrl**, then tap an allied unit or a controllable building.
The original game checks team, unit availability, and the world's
possession rules. The camera follows the controlled unit. For a
turret, the pad aims without moving the building and **L** fires
using its stock ammunition, reload, and targeting behavior.
Tap **Ctrl** and then **Back** or press **A** to release control
through Mindustry's normal core-unit return or respawn path.

The JVM reference transfers from Gamma to Dagger to Duo. It checks
that possession rejects an enemy and honors disabled possession,
that the Duo's original ammunition damages the target without moving
the turret, and that release returns to the core unit.

```bash
python3 tools/verify_stock_possession.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-possession --live
```

The replay passed offline and against the unmodified v160.8 Java
server on both Original and New 3DS profiles in Azahar 2126.2.
The Duo held its northward aim after the pad was released, consumed
original copper ammunition, and returned control to Gamma. The Java
server checked the persisted turret state in both offline saves and
checked the same behavior during both multiplayer sessions.
[ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45098568264)

## Tactical orders

Tap **Command** on the right edge of the grid. Tap friendly units to
select or deselect them. The top screen outlines selected units using
the original selection shape. Tap open ground for a move order or an
enemy for an attack order. The pinned game handles pathfinding, field
formations, target capabilities, and AI.

Press **R** in Command mode to toggle queued orders. The Command label
turns red while queuing. A tap on a commandable building switches the
selection to buildings. Tap a location to set the stock rally point.
Tap **Command** again or press **B** to leave the mode.

With units selected, **Type** lists the original unit commands and
**Stance** lists the stances currently available to those units.
The command highlights reflect all selected units. The stance
highlights follow the last selected unit, as in the original menu.
Choices only affect compatible units. For example, choosing **Mine** for a
mixed Dagger and Mono selection switches the Mono's controller
and makes its mining stances available. **Cancel Orders** calls
the original clear handler. Press **B** to dismiss an open menu.

The ARM11 package uses the pinned v160.8 icon font for the stock
command and stance symbols. Ore stances use their original item
atlas regions. The menu uses the stock localized row names.

The [ARM11 stance build](https://github.com/levkeveli/Min3DStry/actions/runs/45094098264)
passed on both Azahar 2126.2 profiles. The stock Java server confirmed
the Mono's copper mining stance and the Dagger's Hold Fire stance in
both offline saves and both live multiplayer sessions.

The JVM checks use the stock Dagger controller for formation, queued
moves, and an attack order. A stock Glacier fixture verifies unit
movement and the original Ground Factory rally point across a save.

The [ARM11 command build](https://github.com/levkeveli/Min3DStry/actions/runs/45090780591)
passed on Azahar 2126.2 Original and New 3DS profiles. Stock Java
verified that both Daggers moved and that the Ground Factory had the
chosen rally point in both offline saves and both live sessions.

The [schematic sharing build](https://github.com/levkeveli/Min3DStry/actions/runs/45743676841)
also passed the command replay in New 3DS mode. Pressing R queued the
move for both Daggers. Unmodified Java v160.8 confirmed their movement
and the stock factory rally point in the offline save and live session.

```bash
python3 tools/verify_stock_command_orders.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-command-orders --live
```

```bash
python3 tools/verify_stock_unit_stances.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-unit-stances --live
```

## Factory world graphics

The graphical client calls the pinned `UnitFactoryBuild.draw` for the
factory base, rotated output, top, and produced payload. The 3DS adapter
keeps the progress, accent color, phase, and scan line from the original
`Drawf.construct`. It applies the alpha and color equations from
`core/assets/shaders/unitbuild.frag` to the original atlas pixels and
splits the rotating quad at the shader's stripe boundaries.

The JVM rendering reference checks the idle layers, construction
parameters, scan line, and output payload with Crawler. It also checks that
the live client's animation warms up and fades using the factory efficiency
from the stock snapshot. The factory's progress, plan, and payload stay
server-authoritative. The host C reference covers the shader equations and
texture layout.

The emulator verifier creates four Glacier saves with the unmodified
v160.8 server. It compares the empty footprint, idle factory,
in-progress construction, and produced payload.

On Azahar 2126.2, the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45074830155)
drew all four states on both the Original and New 3DS profiles. Each
profile recorded 729 changed pixels from terrain to idle in the factory
footprint, 105 from idle to construction, and 99 from idle to payload.
Both construction replays logged the original shader adapter. This replay
used offline saves. Pass `--live` to join an unmodified Java server hosting
each of the same four stock saves. The construction fixture keeps progress
at its halfway point so the verifier can check the shader and scan line
after the handheld joins. The [live ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45156108781)
passed all four states on both Azahar profiles against unmodified Java.
Each profile recorded 729 changed pixels from terrain to idle, 116 from
idle to construction, and 99 from idle to payload. Both live
construction captures logged the stock shader. Physical hardware
verification remains open in #43.

```bash
python3 tools/verify_upstream_factory_graphics.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/factory-graphics
```

## Evidence

The verified traces and screenshot descriptions live in
[`research/upstream-runtime/README.md`](../../research/upstream-runtime/README.md)
under the touch build/catalog sections; the automated paths are
`tools/verify_upstream_live_net.py --touch-build` / `--touch-config` with
`--controls-replay`. `research/upstream-runtime/src/network-live/host/UpstreamBuildControlsReference.java`
checks the JVM-side selection/rotation/removal decisions.

## Unit factory plans

Tap **Config**, then a configurable unit factory in the world grid.
The menu uses the factory's original production plans, stock names, and
unit icons. It omits locked or banned units. Selecting a row sends the
existing stock plan index through `Building.configure`. Selecting the
active row again clears the plan using the original configuration handler.

The JVM touch reference verifies that a banned first plan leaves the
second plan's index intact. It then verifies that selection resets progress
and that the original factory consumes its recipe items and produces the
selected Crawler. A stock JVM save round trip preserves the selected plan
and the produced payload.

On both Azahar 2126.2 profiles, the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45072409295)
selected Crawler in the original Glacier world and produced it offline.
The unmodified Java server reopened each console save and verified the
factory plan, the Crawler payload, and the consumed ingredients.
The same verifier accepts `--live` to send the touch selection from the
ARM11 client and check the selected plan and production on the stock
server. The live fixture uses an original Power Source to keep the factory
powered during connection and menu navigation.
See [Factory world graphics](#factory-world-graphics) for the drawing
passes and their replay.

```bash
python3 tools/verify_upstream_factory_plans.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/factory-plans
```

## Limits

- The palette is a bounded prototype, not the final upstream layout; not every
  catalog page or configurable block has an automated replay.
- Physical touch hardware and the full catalog remain open with #27/#43.
