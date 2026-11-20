# Min3DStry Citro2D Rendering Prototype

Status: **Experimental** (`host-tested`, `3ds-build-tested`, `emulator-tested` via Azahar `2126.2`; `old3ds-hardware-tested` / `new3ds-hardware-tested`: pending).

## Purpose

This prototype implements Issue #3 by rendering a bounded 2D Mindustry-inspired tile scene (`36 x 24` tiles at `16 px`, centered on a `400 x 240` top-screen viewport) using `libctru` and `citro2d` on the PICA200 GPU, while keeping all scene simulation, culling, and draw-order batching behind a host-testable C11 interface.

## Architecture

- [`include/render/scene_2d.h`](../../include/render/scene_2d.h) & [`src/render/scene_2d.c`](../../src/render/scene_2d.c):
  - Platform-independent `M3dsBoundedScene` state (`camera_x`, `camera_y`, `tick`, `paused`, tile grid) and `M3dsRenderBackend` callback table (`clear`, `draw_rect`, `draw_line`, `draw_triangle`, `draw_circle`).
  - **Viewport culling:** primitives outside `[0, viewport.width] x [0, viewport.height]` are skipped before hitting the backend (`culled_primitives` counter).
  - **Citro2D batching rule:** non-circular primitives (terrain tiles, conveyor beds/chevrons, walls, core, turret bodies/barrels/scan lines) are emitted in Pass 1 before any circular primitives (ore nodes, transported copper items, turret rings, core pulse) in Pass 2 so Citro2D performs at most one circle-mode state switch per frame.
  - **Bounded primitive budget:** capped at `M3DS_SCENE_MAX_PRIMITIVES` (`1024`) to keep command buffer usage predictable on Original and New 3DS.
- [`src/platform/3ds/citro2d_backend.c`](../../src/platform/3ds/citro2d_backend.c):
  - Translates `M3dsRenderBackend` calls into `C2D_TargetClear`, `C2D_SceneBegin`, `C2D_DrawRectSolid`, `C2D_DrawLine`, `C2D_DrawTriangle`, and `C2D_DrawCircleSolid`.
- [`src/platform/3ds/main_3ds.c`](../../src/platform/3ds/main_3ds.c):
  - Initializes `gfxInitDefault()`, `C3D_Init`, `C2D_Init`, `C2D_Prepare`, top-screen `C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT)`, and bottom-screen debug console (`consoleInit(GFX_BOTTOM, NULL)`).
  - Supports D-Pad / Circle Pad camera pan, `A` pause/resume, `Y` camera reset, and `START` exit, while printing CPU%, GPU%, CmdBuf%, draw counts, culled counts, and state switches.

## Build and Test

- Host unit tests (runs in CI and locally with `cc`/`gcc`/`clang`):
  ```bash
  ./dev.sh test
  ```
  Compiles and runs [`tests/render/test_scene_2d.c`](../../tests/render/test_scene_2d.c) against a recording mock backend to verify deterministic stepping, camera clamping, off-screen culling, zero non-circle-after-circle state switches, and primitive budget bounds.
- 3DS `.3dsx` build (uses local `DEVKITARM` or `devkitpro/devkitarm:latest` Docker image):
  ```bash
  ./dev.sh build
  ```
  Produces `build/min3dstry.3dsx`, `build/min3dstry.elf`, and `build/min3dstry.smdh`.
- Autonomous emulator verification (boots `build/min3dstry.3dsx` in Azahar under both `new3ds` and `old3ds` modes via [`tools/verify_azahar.py`](../../tools/verify_azahar.py), captures `svcOutputDebugString` telemetry + UDP RPC perf stats + screenshots, and validates non-blank top/bottom frames):
  ```bash
  ./dev.sh emu-test
  ```
  Produces `build/emu-verify/new3ds.png`, `build/emu-verify/old3ds.png`, and `build/emu-verify/report.json`.

## Verification Record

- **Host test (`host-tested`):** `./dev.sh test` passed (`test_scene_2d: all assertions passed.`, `test_hardware_profile: all assertions passed.`, 8 Python PR automation tests, and 5 `test_verify_azahar.py` tests).
- **3DS ROM build (`3ds-build-tested`):** `./dev.sh build` compiled cleanly with `-Wall -Wextra -Werror` under `arm-none-eabi-gcc (devkitARM) 16.1.0` to produce `build/min3dstry.3dsx`.
- **Emulator (`emulator-tested`, Azahar `2126.2`):** `./dev.sh emu-test` passed on Windows 11 with OpenGL `4.3.0 - Build 32.0.101.9175` (`Intel(R) Arc(TM) Graphics`):
  - **Emulated New 3DS (`is_new_3ds=true`):** `New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:96 MiB, FIRM:2.58.0)`; at tick 30, `cam=(88.0, 72.0)`, `draw=R:456, L:4, T:17, C:30`, `cull=456`, `sw=1`, `drop=0`, `cpu=3.51%`, `gpu=1.50%`, `cmdbuf=0.26%`; measured `system_fps=31.16`, `game_fps=24.93` (`42.17%` speed with `instant_debug_log` active). Screenshot (`400x480`, `top_distinct_colors=13`, `bottom_distinct_colors=2`) visually verified: top screen shows terrain, ore, animated conveyor items, walls, turrets, and core; bottom console shows legible stats.
  - **Emulated Original 3DS (`is_new_3ds=false`):** `Original 3DS XL (O3DS, 268 MHz, L2:off, AppMem:96 MiB, FIRM:2.58.0)`; identical scene counts (`R:456, L:4, T:17, C:30`, `cull=456`, `sw=1`, `drop=0`, `cpu=3.51%`, `gpu=1.50%`, `cmdbuf=0.26%`); measured `system_fps=31.48`, `game_fps=31.48` (`52.62%` speed with `instant_debug_log` active). Screenshot (`400x480`) visually verified.
- **Original / New 3DS Hardware (`not-tested`):** Transfer `build/min3dstry.3dsx` to `/3ds/min3dstry.3dsx` and record model, firmware, commit SHA, FPS/CPU%/GPU%/CmdBuf% once physical hardware deployment (Issue #6) runs.
