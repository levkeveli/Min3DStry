# Top-screen minimap prototype

Status: **Experimental** (`host-tested`; rendered in Azahar screenshots; upstream HUD/menu parity pending).

## Scope

`include/render/minimap.h` and `src/render/minimap.c` draw a bounded minimap
overlay from `M3dsBoundedScene` tiles:

- one rect per `36 x 24` scene cell, colored for stone, ore, conveyor, wall,
  core and turret;
- a border/background panel;
- the current camera viewport rectangle.

The overlay is drawn after `m3ds_scene_render` on the top screen at
`(296, 8)` with size `96 x 64`. It is a visual prototype, not the upstream
HUD/minimap layout and not a gameplay input surface.

## Tests

- `tests/render/test_minimap.c` uses a mock backend to assert the panel, one
  cell per tile, camera outline lines, color classification for core/turret/ore
  and null/argument handling.
- `./dev.sh test` runs the minimap test.
- `./dev.sh emu-test` screenshots include the overlay on both models; the
  existing telemetry/parity assertions are unchanged.

## Limitations

- No unit icons, zoom levels, fog of war, panning or touch interaction.
- Rect colors are prototype constants, not upstream atlas sprites.
- HUD/menu parity and the rest of issue #40 remain open.
