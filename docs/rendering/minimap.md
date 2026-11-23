# Top-screen minimap prototype

Status: **Experimental** (`host-tested`; rendered in Azahar screenshots; upstream HUD/menu parity pending).

## Scope

`include/render/minimap.h` and `src/render/minimap.c` draw a bounded minimap
overlay from `M3dsBoundedScene` tiles:

- one rect per `36 x 24` scene cell, colored for stone, ore, conveyor, wall,
  core and turret;
- a border/background panel;
- the current camera viewport rectangle;
- live enemy blips plus core HP and stored-copper bars when a
  `M3dsParityState` is supplied.

The overlay is drawn after `m3ds_scene_render` on the top screen at
`(296, 8)` with size `96 x 64`. It is a visual prototype, not the upstream
HUD/minimap layout and not a gameplay input surface.

## Tests

- `tests/render/test_minimap.c` uses a mock backend to assert the panel, one
  cell per tile, camera outline lines, color classification for core/turret/ore,
  enemy blips, HP/copper bars and null/argument handling.
- `./dev.sh test` runs the minimap test.
- `./dev.sh emu-test` screenshots include the overlay on both models; the
  existing telemetry/parity assertions are unchanged.

## Limitations

- No unit sprites, zoom levels, fog of war, panning or touch interaction.
- Enemy blips are prototype rects; HP/copper bars are fixed-scale.
- Rect colors are prototype constants, not upstream atlas sprites.
- HUD/menu parity and the rest of issue #40 remain open.

## Live HUD minimap

The live singleplayer HUD samples the world through `UpstreamMinimap` and
draws a 64x64 texture at 56 px with a dark inset frame. It uses the stock
`Block.minimapColor`/`MapIO.colorFor` color path, team-colored unit blips and
local-core markers, and a white player marker whose 3-cell tip follows the
unit facing. Uploads use the PICA200 ABGR/Morton layout described in
[live-hud.md](live-hud.md). Emulator marker checks remain pending.
