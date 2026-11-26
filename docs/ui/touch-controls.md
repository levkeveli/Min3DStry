# Bottom-screen touch controls

Status: **Emulator-tested prototype** (host geometry tests plus Azahar `2126.2`
New and Original 3DS boot with the HUD; Azahar cannot inject touch input into
the standalone app, so the touch paths remain host-tested. Visible labels are
now drawn inside the hit zones; physical label retest is tracked by #364.)

## Scope

`include/ui/touch_controls.h` maps raw bottom-screen touch pixels to the
existing HUD actions:

| Region | Screen rectangle (pixels) | Action |
| --- | --- | --- |
| Menu | `248..312 x 8..48` | same as `SELECT`: toggle the pause menu |
| Zoom in | `248..312 x 64..104` | same as `L` |
| Zoom out | `248..312 x 120..160` | same as `R` |
| Pan up | `48..80 x 120..152` | hold to pan up |
| Pan down | `48..80 x 168..200` | hold to pan down |
| Pan left | `16..48 x 140..180` | hold to pan left |
| Pan right | `80..112 x 140..180` | hold to pan right |

The text-console labels are drawn inside those zones using 8×8 px cells:

| Label | Console row / column | Cell centre | Action |
| --- | --- | --- | --- |
| `MENU` | `2 / 32` | `(252, 12)` | menu |
| `ZOOM+` | `9 / 32` | `(252, 68)` | zoom in |
| `ZOOM-` | `16 / 32` | `(252, 124)` | zoom out |
| `^` | `19 / 7` | `(52, 148)` | pan up |
| `v` | `25 / 7` | `(52, 196)` | pan down |
| `<` | `19 / 3` | `(20, 148)` | pan left |
| `>` | `19 / 13` | `(100, 148)` | pan right |

`tests/ui/test_touch_controls.c` asserts every label cell centre maps back to
its own action, so a future zone or label change cannot silently separate them.

The module deliberately returns an action rather than touching game state.
`src/platform/3ds/main_3ds.c` converts the action into the same synthetic
`KEY_SELECT`, `KEY_L`, `KEY_R` or held D-pad input that the existing button
path already handles, so there is one implementation of camera and menu
behavior. A touch press logs `TOUCH action=<id> x=<px> y=<py>`.

When the pause menu is open, touching a menu row selects and activates it.
The menu renders rows 16..18 of the 8 px console starting at `y=120`, so
`y=120..143` maps to the three actions; the row logic also accepts the
D-pad/A path. Row taps log `TOUCH action=menu.row row=<n>` and synthesize
`KEY_A`, reusing the existing action handler.

## Tests

- `tests/ui/test_touch_controls.c` asserts every region center, all
  half-open boundaries, out-of-screen coordinates, action IDs and pan
  classification. It also asserts every visible label cell centre maps to the
  matching touch action.
- `./dev.sh test` compiles and runs the test on the host.

## Verification

`./dev.sh build` and `./dev.sh emu-test` check the app still boots and renders
the HUD with the new `Touch:` label. The emulator harness does not synthesize
touch samples, so the touch-to-key mapping is host-tested only; physical
testing is tracked with the other hardware work.

## Limitations

- The bottom screen remains a text console; there is no graphical button art
  yet, but the text labels are drawn inside the hit zones. The down-arrow
  label can overlap the fourth sector row when four slots are listed.
- A held touch does not slide between regions; lift and press again.
- The pause-menu rows are text; tapping them works, but there is no graphical
  button art yet.
- Upstream HUD/menu art and full touch parity remain open on #40.
