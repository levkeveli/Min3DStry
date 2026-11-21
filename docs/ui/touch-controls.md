# Bottom-screen touch controls

Status: **Experimental** (`host-tested`; visible HUD labels are emulator-tested,
but Azahar cannot inject touch input into the standalone app, so the touch
paths are covered by host geometry tests).

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

The module deliberately returns an action rather than touching game state.
`src/platform/3ds/main_3ds.c` converts the action into the same synthetic
`KEY_SELECT`, `KEY_L`, `KEY_R` or held D-pad input that the existing button
path already handles, so there is one implementation of camera and menu
behavior. A touch press logs `TOUCH action=<id> x=<px> y=<py>`.

## Tests

- `tests/ui/test_touch_controls.c` asserts every region center, all
  half-open boundaries, out-of-screen coordinates, action IDs and pan
  classification.
- `./dev.sh test` compiles and runs the test on the host.

## Verification

`./dev.sh build` and `./dev.sh emu-test` check the app still boots and renders
the HUD with the new `Touch:` label. The emulator harness does not synthesize
touch samples, so the touch-to-key mapping is host-tested only; physical
testing is tracked with the other hardware work.

## Limitations

- The bottom screen remains a text console; there is no graphical button art
  yet, and the labels describe rather than draw the regions.
- A held touch does not slide between regions; lift and press again.
- Pause-menu rows are still selected with the D-pad and `A`, not by tapping.
- Upstream HUD/menu art and full touch parity remain open on #40.
