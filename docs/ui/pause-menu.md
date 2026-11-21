# Pause menu HUD prototype

Status: **Experimental** (`host-tested`; 3DS build/emulator boot verified with the
menu closed; menu interaction screenshots pending automated input injection).

Issue: [#40](https://github.com/levkeveli/Min3DStry/issues/40)  
Capability: `hud-menus-minimap`.

## Scope

This slice adds the missing pause-menu state machine and bottom-screen menu HUD
to the platform harness:

- `Select` opens or closes the menu; opening pauses the demo and stops ambient
  loops. Closing restores the previous pause state.
- `Up`/`Down` select Resume, Reset Camera, or Quit; `A` confirms.
- `Resume` unpauses and restarts ambient loops.
- `Reset Camera` recenters the scene without closing the menu.
- `Quit` exits the app cleanly.
- The bottom screen displays the three actions with the current selection.
- `[MIN3DSTRY] MENU` telemetry records open/close and confirmed actions.

The state machine is platform-independent
([`include/ui/pause_menu.h`](../../include/ui/pause_menu.h),
[`src/ui/pause_menu.c`](../../src/ui/pause_menu.c)). It does not add blocks,
invent gameplay rules, or change the canonical parity trace.

## Tests

- `tests/ui/test_pause_menu.c` covers open/close/toggle, selection wrapping,
  action labels/IDs, invalid arguments, and default Resume selection.
- `./dev.sh test` includes the pause-menu suite.
- `./dev.sh build` compiles it into `build/min3dstry.3dsx`.
- `./dev.sh emu-test` boots both profiles with the menu closed and preserves
  parity `0x53a483c7`.

## Limitations

- The menu is a text HUD on the bottom-screen console, not the final upstream
  Mindustry menu art or layout.
- Azahar input injection is not available in `verify_azahar.py`, so the open
  menu screenshot and selected-action traversal remain host-tested until an
  input driver is added.
- Full upstream HUD/menu parity, minimap interaction, and physical 3DS touch
  verification remain open on #40.
