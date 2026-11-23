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

The JVM checks use the stock Dagger controller for formation, queued
moves, and an attack order. A stock Glacier fixture verifies unit
movement and the original Ground Factory rally point across a save.

The [ARM11 command build](https://github.com/levkeveli/Min3DStry/actions/runs/45090780591)
passed on Azahar 2126.2 Original and New 3DS profiles. Stock Java
verified that both Daggers moved and that the Ground Factory had the
chosen rally point in both offline saves and both live sessions.

```bash
python3 tools/verify_stock_command_orders.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-command-orders --live
```

## Factory world graphics

The graphical client calls the pinned `UnitFactoryBuild.draw` for the
factory base, rotated output, top, and produced payload. The 3DS adapter
keeps the progress, accent color, phase, and scan line from the original
`Drawf.construct`. It applies the alpha and color equations from
`core/assets/shaders/unitbuild.frag` to the original atlas pixels and
splits the rotating quad at the shader's stripe boundaries.

The JVM rendering reference checks the idle layers, construction
parameters, scan line, and output payload with Crawler. The host C
reference covers the shader equations and texture layout.

The emulator verifier creates four Glacier saves with the unmodified
v160.8 server. It compares the empty footprint, idle factory,
in-progress construction, and produced payload.

On Azahar 2126.2, the [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45074830155)
drew all four states on both the Original and New 3DS profiles. Each
profile recorded 729 changed pixels from terrain to idle in the factory
footprint, 105 from idle to construction, and 99 from idle to payload.
Both construction replays logged the original shader adapter. This replay
used offline saves. Physical hardware verification remains open in #43.

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
