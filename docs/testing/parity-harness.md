# Gameplay Parity Harness

Status: **Experimental** (`host-tested` at `-O0`/`-O2`; `emulator-tested` verification recorded via `./dev.sh emu-test`; upstream Java Mindustry bytecode parity pending Phase 4 simulation porting).

## Purpose

Issue #7 establishes a deterministic, integer-only simulation harness (`include/sim/parity_scenario.h` and `src/sim/parity_scenario.c`) plus reference snapshot fixture (`tests/parity/starter_copper_defense_v1.json`) and comparison runner (`tools/check_parity.py`) so host builds and 3DS ARM11 binaries can be verified bit-for-bit against identical canonical inputs.

## Canonical Scenario (`starter_copper_defense_v1`)

- **Grid:** `16 x 12` tiles, core at `(12, 5)` starting with `40` copper and `200` HP, copper ore at `(2, 5)` and `(2, 6)`.
- **Inputs:**
  - Tick `0`: place two drills feeding copper conveyors into the core (`(2..11, 5)`) and into a turret (`(2..9, 6)`).
  - Tick `12` & `15`: place and remove a wall at `(10, 3)` (verifies placement cost and 50% refund).
  - Tick `80` & `180`: spawn two waves of 2 enemies moving toward the core through turret range.
  - Ticks `140..160`: toggle pause on/off to verify world state freezes cleanly while paused.
- **Snapshots & Hashing:** Every tick mixes the full grid, enemy list, and counters via endian-independent integer FNV-1a (`m3ds_parity_hash_state`) into `trace_hash`, with full snapshots captured at ticks `0, 60, 120, 180, 240`.

## Scope Note

This harness validates cross-target determinism (`x86_64`/`-O0`/`-O2` vs ARM11 New/Old 3DS) using a bounded integer scenario kernel. Full upstream Java Mindustry block/wave rule parity will layer additional scenario fixtures on top of this harness as Phase 4 simulation systems are implemented.

## Verification Record

- **Host (`-O0` & `-O2`):** `./dev.sh test` compiles `tests/sim/test_parity_harness.c` at both `-O0` and `-O2`, checks input guards and pause invariance, and verifies via `tools/check_parity.py` that both match `tests/parity/starter_copper_defense_v1.json` (`trace_hash=0x53a483c7`, final tick 240: `copper=22`, `delivered=12`, `killed=2`, `hp=160`).
- **Azahar Emulator (`new3ds` & `old3ds`):** `src/platform/3ds/main_3ds.c` runs `m3ds_parity_run_canonical` at boot and emits `[MIN3DSTRY] PARITY ...` via `svcOutputDebugString`; `tools/verify_azahar.py` asserts an exact match against `tests/parity/starter_copper_defense_v1.json` on both emulated 3DS generations.
- **Physical New 3DS XL:** The [Netloader capture](../hardware/deployment-and-logs.md#verification-status) from build `07e94ed` reported the expected hash `0x53a483c7` and all four expected counters at tick 240.
