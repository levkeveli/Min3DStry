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
and that the original factory consumes the recipe items and produces the
selected Crawler. ARM11 interaction and save/reload verification are pending.

## Limits

- The palette is a bounded prototype, not the final upstream layout; not every
  catalog page or configurable block has an automated replay.
- Physical touch hardware and the full catalog remain open with #27/#43.
