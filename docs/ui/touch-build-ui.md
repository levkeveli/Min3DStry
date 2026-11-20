# Bottom-Screen Touch Build UI

Status: **Experimental** (`host-tested` for touch hit-testing, state machine, live placement/removal, and mock rendering in `./dev.sh test`; `.3dsx` compiled via `./dev.sh build`; top-screen placement cursor and `320x240` bottom-screen palette visually verified across Original & New 3DS modes in Azahar `2126.2`; live resistive touchscreen event injection and physical 3DS verification pending).

## Architecture

- **Platform-independent UI & placement state machine ([touch_build_ui.h](../../include/ui/touch_build_ui.h), [touch_build_ui.c](../../src/ui/touch_build_ui.c)):** Pure C11 module with zero OS or system-font dependencies. Tracks `selected_block` (`M3DS_PBLOCK_CONVEYOR`, `M3DS_PBLOCK_DRILL`, `M3DS_PBLOCK_WALL`, `M3DS_PBLOCK_TURRET`), `rotation` (`0..3`), `deconstruct_mode`, grid cursor `(cursor_x, cursor_y)` inside the `16x12` world, rising-edge touch state, and action counters.
- **Zero-system-font bounded renderer (`m3ds_touch_ui_render`):** Renders the entire `320 x 240` UI directly through `M3dsRenderBackend` (`draw_rect` + `draw_triangle`), using an embedded RLE-packed `5x7` bitmap font that merges contiguous horizontal bits per scanline into single rectangles. Keeps draw calls bounded under `M3DS_UI_MAX_PRIMITIVES` (`1536`, `budget_dropped == 0`) with zero `draw_circle` state switches and zero reliance on Nintendo's shared system font archive.
- **Top-screen cursor & tile sync ([scene_2d.c](../../src/render/scene_2d.c), [factory_demo.c](../../src/render/factory_demo.c)):** Renders a `2px` hollow frame (`z = 0.45f`) and directional arrow (Build mode) or `X` indicator (Deconstruct mode) during Pass 1 of `m3ds_scene_render`, preserving the single Pass 1 -> Pass 2 circle batch switch (`state_switches <= 1`). Supports all 4 conveyor orientations (`M3DS_TILE_CONVEYOR_RIGHT/DOWN/LEFT/UP`) synced live via `m3ds_factory_demo_sync_tile`.

## Dual-Screen Layout & 3DS Controls

- **Top Screen (`400 x 240`):** Factory world + textured atlas (`romfs:/sprites.t3x`) + gold/amber (`build`) or crimson (`deconstruct`) tile cursor with valid/invalid feedback.
- **Bottom Screen (`320 x 240`):**
  - Header strip: title + live copper counter (`CU:<n>`) + simulation tick (`T:<n>`).
  - Row 1 (4 cards): `CONV` (`1C`), `DRIL` (`4C`), `WALL` (`2C`), `TURR` (`6C`) with geometric block icons and gold selection outline.
  - Row 2 (3 controls): `ROT:<R|D|L|U>` (clockwise rotation), `BUILD/DECON MODE`, and `PLACE/DEL` (`VALID` emerald/crimson highlight when `m3ds_touch_ui_can_apply` holds).
  - Row 3 (3 utilities): `RUN/PAUSED`, `SOUND/MUTED`, and `CAM RESET`.
  - Footer strip: cursor coordinates `CUR:(xx,yy)`, physical button hints, and console tag (`N3DS` / `O3DS`).
- **Physical 3DS Controls ([main_3ds.c](../../src/platform/3ds/main_3ds.c)):**
  - **Bottom Touchscreen:** Direct tap hit-testing across all `10` buttons (rising-edge triggered so holding does not repeat-fire).
  - **D-Pad:** Move world placement cursor (`cursor_x`, `cursor_y`) one tile per press.
  - **Circle Pad:** Pan top-screen camera smoothly.
  - **L / R:** Rotate placement direction CCW / CW.
  - **B:** Place selected block or deconstruct block at cursor.
  - **A / Y / X / START:** Pause simulation / reset camera / toggle audio mute / exit.

## Upstream Mindustry v160.8 Scope Note

- **Scope boundary:** This first slice for [#27](https://github.com/levkeveli/Min3DStry/issues/27) provides the core dual-screen palette, cursor, rotation, and deconstruction UI wired to `M3dsParityState`.
- **Follow-up on [#27](https://github.com/levkeveli/Min3DStry/issues/27):** Per [the upstream port plan](../upstream-port.md), adapting the full Mindustry v160.8 ([`Anuken/Mindustry@19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0), GPLv3) block catalog, placement categories, configuration popups (sorters, unloaders, item bridges, unit factories), and drag-line conveyor placement remains open on `#27`.

## Verification Record

- **Host Mock Suite (`./dev.sh test`):** Passed `tests/ui/test_touch_build_ui.c` (`test_touch_build_ui: all assertions passed.`) covering `320x240` non-overlapping button rects, corner/margin hit-testing, rising-edge/hold touch transitions, rotation wrapping, cursor clamping, copper cost & ore-only drill validation, 50% refund deconstruction, core removal rejection, top-screen Pass 1 cursor batching invariants, and bottom-screen mock rendering (`drawn_rects > 100`, `state_switches == 0`, `budget_dropped == 0`), plus `tests/tools/test_verify_azahar.py` and `tests/tools/test_device_deploy.py` parser tests.
- **ARM11 Build (`./dev.sh build`):** Compiled `src/ui/touch_build_ui.c`, `src/render/scene_2d.c`, `src/render/factory_demo.c`, and `src/platform/3ds/main_3ds.c` with `arm-none-eabi-gcc 16.1.0` (`-Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Azahar `2126.2` (`./dev.sh emu-test`):** Passed in both `new3ds` (`New 3DS XL, 804 MHz`, `29.0 FPS`, `CPU 12.38%`, `GPU 3.0%`, `CmdBuf 1.12%`) and `old3ds` (`Original 3DS XL, 268 MHz`, `30.05 FPS`, `CPU 12.37%`, `GPU 2.99%`, `CmdBuf 1.12%`):
  - Both modes emitted `[MIN3DSTRY] UI block=conveyor rot=0 mode=build cursor=(8,4) buttons=10`.
  - Parity (`0x53a483c7`), audio (`fallback_no_dsp`), and top-screen batching (`R:431 T:1 C:1 Sw:1 Drop:0`) held across both modes.
  - Visual inspection of `build/emu-verify/new3ds.png` and `build/emu-verify/old3ds.png` (`top_distinct_colors=10`, `bottom_distinct_colors=16`) confirmed the gold cursor + arrow above the conveyor belt on the top screen and the legible 10-button palette on the bottom screen (`CU:5 T:111` / `CU:3 T:78`, `PLACE VALID`, `CUR:(08,04)`).
- **Physical 3DS Resistive Touch Verification:** **Not yet tested**; real hardware tap responsiveness, stylus feel, and interactive block placement on physical Original and New 3DS consoles remain pending.
