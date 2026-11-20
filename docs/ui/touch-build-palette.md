# Touch build palette prototype

Status: **Experimental** (`host-tested`; console-rendered bottom-screen palette/grid; Azahar cannot inject touch, physical hardware interaction pending).

## Scope

`include/input/build_palette.h` and `src/input/build_palette.c` map bottom-screen
touches to the existing parity build inputs:

- bottom strip: five slots (Drill, Conveyor, Turret, Wall, Remove);
- remaining area: `16 x 12` map cells, one touch per placement/removal;
- selected choice is shown on the bottom console;
- an ASCII grid of the current parity state is printed above the palette.

The app applies `M3DS_PINPUT_PLACE_BLOCK` / `REMOVE_BLOCK` to the interactive
factory state and requests the pinned block place/break audio events when the
input succeeds. This is a prototype input surface, not the upstream touch UI.

## Tests

- `tests/input/test_build_palette.c` covers slot selection, map-cell mapping,
  remove mode, invalid margins/sizes, block mapping and ASCII grid formatting.
- `./dev.sh test` runs the palette test.
- `./dev.sh emu-test` verifies the console-rendered palette/grid does not break
  rendering, parity or telemetry; Azahar has no touch injection, so the touch
  path itself is host-tested only.

## Limitations

- Console text grid, not the upstream sprite/menu palette.
- No drag placement, rotation preview, multi-touch or zoom.
- Touch placement changes only the interactive demo state; it does not alter
  the canonical parity fixture hash.
- Full `touch-build-palette` parity and physical touch verification remain open.
