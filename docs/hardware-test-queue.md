# Hardware test queue

Status: **Partially verified.** Physical records exist for both models:
[Original 3DS XL](hardware/evidence/old3ds-xl-a4a9a96.md) (smoke) and
[New 3DS XL](hardware/evidence/new3ds-xl-de3e249.md) (sector-170, 12/12 strict).
The cards below remain open; FPS, the five-minute soak/interactions, audible
audio, live gameplay and cross-play are not claimed.

Latest evidenced builds: New `de3e249` (2026-11-22) and Old `a4a9a96`
(2026-11-22). Record the exact commit and ROM SHA-256 with every run.
The procedure and evidence schema are in
[verification-runbook.md](hardware/verification-runbook.md) and
[deployment-and-logs.md](hardware/deployment-and-logs.md).

## Handoff protocol

1. Build from a merged `main` commit, record `git rev-parse HEAD`, and hash the
   `.3dsx`.
2. Deploy with `./dev.sh device deploy --rom ... --ip <IP> --stream-logs` or
   stage the ROM on SD.
3. Run the card's scenario and capture the raw `[MIN3DSTRY]` log.
4. Ingest with `python3 tools/hw_evidence.py ... --strict --out
   docs/hardware/evidence/<target>-<sha>.md`.
5. Attach the evidence file, raw log, console details and screenshots to a PR
   or to issue #43. Then remove `"hardware_test": "HARDWARE_TEST_PENDING"` from
   the proven capabilities in `docs/port-capabilities.json`, update their
   `stage`, and regenerate the gauge.
6. If a run fails, attach the log and file a defect; do not change the ledger
   stage.

Capabilities still marked `HARDWARE_TEST_PENDING`:

| Card | Capabilities |
| --- | --- |
| HWT-01 | `citro2d-scene-prototype`, `sprite-atlas-pipeline`, `world-sprite-renderer`, `lighting-effects-camera` |
| HWT-02 | `world-blocks-logistics`, `production-power-liquids`, `combat-waves-ai`, `touch-build-palette`, `hud-menus-minimap` |
| HWT-03 | `campaign-sectors` |
| HWT-04 | `ndsp-sfx-music` |
| HWT-05 | `crossplay-net-protocol` |
| HWT-06 | `physical-hardware-parity` and the `>= 24 FPS` target |

Cleared by the `de3e249` New 3DS record: `arm11-toolchain-build`,
`memory-budget-runtime`, `threading-scheduling` and `msav-map-save-compat`.
The `a4a9a96` Original 3DS record also backs the first three. Preserve those
evidence files and do not re-queue the cleared capabilities unless the
evidence is invalidated.

`old-new-3ds-probe` remains **hardware-verified** with both
`old3ds-xl-a4a9a96.md` and `new3ds-xl-de3e249.md`.

## HWT-01 — Boot, storage, parity, memory and render baseline

Covers the ARM11 build/probe, Old/New hardware detection, 64 MiB allocator,
worker scheduling, Citro2D scene, sprite atlas, world renderer, zoom/lighting
and `.msav` round-trip.

**Build**

```bash
./dev.sh build
sha256sum build/min3dstry.3dsx
git rev-parse HEAD
```

**Setup**

```text
sd:/3ds/min3dstry/min3dstry.3dsx
sd:/3ds/min3dstry/sector-serpulo-170.msav   # tests/fixtures/glacier-v13.msav
```

**Steps**

1. Deploy and stream logs to `build/device-logs/hw-<date>-<model>.log`.
2. Boot with the canonical sector fixture; let the embedded loader and parity
   scenario run.
3. Pan/zoom, toggle lighting, pause/resume, open/close the menu.
4. Run for at least 5 minutes, then START to quit.

**Expected**

- `HW:` reports the correct model/AppMem (`O3DS` `64 MiB`, `N3DS` `124 MiB`).
- `STORAGE_ROUNDTRIP status=ok`, `SAVE_SLOT present=1 valid=1 version=13`.
- `SECTOR_LOADER available=1 loaded=1 tiles=37500 buildings=2`.
- `SECTOR_ALLOC hash=783fbd80e415dcd7`,
  `SECTOR_TILE_STATE linked=2 hash=e35397041df8e5fe`,
  `SECTOR_PARITY hash=3a375fd4f81c5680`.
- `PARITY scenario=starter_copper_defense_v1 hash=0x53a483c7`.
- `WORKERS match=1 checksum=0x4bb2b98d`.
- `FRAME ... drop=0`; no crash or hang.

**Evidence**

Strict ingest:

```bash
python3 tools/hw_evidence.py \
  --input build/device-logs/hw-<date>-<model>.log \
  --rom build/min3dstry.3dsx \
  --commit "$(git rev-parse HEAD)" \
  --target "New 3DS XL" --profile new3ds --scenario sector170 \
  --strict --out docs/hardware/evidence/new3ds-<short-sha>.md
```

Repeat with `--profile old3ds --target "Original 3DS"`. Attach both generated
reports, raw logs, screenshots and console details.

## HWT-02 — Live gameplay, build UI and HUD

Covers drills/conveyors/routers/core storage, crafters/power/liquids, turrets
and waves, touch palette/rotation, HUD/menus/minimap.

**Build/setup:** same ROM as HWT-01, plus a Ground Zero map/save.

**Steps**

1. Start a world, mine copper, place a conveyor, router, drill and turret.
2. Feed the turret; trigger a wave (Y on the offline app) and confirm units are
   destroyed.
3. Use the bottom-screen palette and rotation; open the block catalog/config,
   pause menu and minimap.
4. Record screenshots and let the run last at least 5 minutes.

**Expected**

- Buildings appear/rotate/remove with no rendering artifacts.
- `UPSTREAM_SP_BUILT`, `UPSTREAM_SP_REMOVED`, `UPSTREAM_SP_WAVE` and HUD wave
  counters update.
- No crash/hang; `FRAME ... drop=0`; touch and D-pad input remain responsive.
- With a captured sector loaded, the status HUD shows the stock
  `Sector Captured` row.
- On a boss wave, the guardian-warning notification appears with the stock
  `wave.guardianwarn` text.

**Evidence:** raw log, screenshots of HUD/palette/minimap, frame timings and a
short note on any glitches.

## HWT-03 — Campaign sector menu, launch, capture and save-back

Covers `campaign-sectors`: tech tree/sector persistence, selection/launch,
capture state and `.msav` save-back.

**Build/setup**

```bash
# Graphical single-player ROM (commands in gameplay/offline-singleplayer.md)
bash research/upstream-runtime/arm11/build.sh \
  research/upstream-runtime/build/generated/teavm/c \
  research/upstream-runtime/build/probe-3ds-singleplayer \
  --singleplayer build/terrain-sp

# Capture probe ROM
gradle -PupstreamCheckout=... -PreferenceJar=... -ParcCheckout=... \
  -Pprobe=campaign-capture generateC
M3DS_RELAX_POINTER_TYPES=1 bash research/upstream-runtime/arm11/build.sh \
  research/upstream-runtime/build/generated/teavm/c \
  research/upstream-runtime/build/probe-3ds-campaign-capture
```

Stage at least two top-level `sector-serpulo-*.msav` files in
`/3ds/min3dstry` (for example 15 and 86) plus `glacier.msav` for the probe.

**Steps**

1. Boot the single-player ROM; confirm the menu lists the staged slots.
2. D-pad to a slot and press A; confirm launch, then START to exit.
3. Reboot and confirm the selected slot was written back.
4. Run the capture probe; confirm the capture marker and persisted state.

**Expected**

```text
UPSTREAM_CAMPAIGN_MENU slots=2
UPSTREAM_CAMPAIGN_SELECTED slot=sector-serpulo-15.msav name=Ground Zero
UPSTREAM_SP_LOADED source=campaign map=Editor_Playtesting size=150x250 wave=1 copper=0
UPSTREAM_CAMPAIGN_SAVED slot=sector-serpulo-15.msav
UPSTREAM_SP_EXIT
UPSTREAM_CAMPAIGN_CAPTURED sector=15
UPSTREAM_CAMPAIGN_NOTIFICATION text=Sector Captured
UPSTREAM_CAMPAIGN_CAPTURE_REWARDS copper=250 lead=75 core=core-nucleus storage=13000
UPSTREAM_CAMPAIGN_CAPTURE sector=15 captured=ok waves=off attack=off event=ok persisted=ok
```

**Evidence:** raw logs for both ROMs, slot directory listing, save-back file
hash before/after, and screenshots of the menu and loaded world. The live app
installs the same `UpstreamSectorCapture` handler as the probe, so a live
capture also emits `UPSTREAM_CAMPAIGN_CAPTURED` and persists the reward items.
Capture rewards UI is not implemented yet; this card verifies the state,
items/core metadata and persistence.

## HWT-04 — NDSP audio and DSP firmware

Covers `ndsp-sfx-music`. **Setup:** audio build with upstream assets and
`sd:/3ds/dspfirm.cdc` present.

```bash
UPSTREAM_MINDUSTRY=/path/to/Mindustry ./dev.sh build
```

**Steps:** launch, play a UI click, place/break a block, fire a turret, start
music, toggle mute (X), run for 2 minutes.

**Expected:** `AUDIO backend=ndsp state=ready`, audible effects/music with no
crackling or hangs. If `dspfirm.cdc` is absent, record
`fallback_no_dsp`; that is an environment finding, not a code pass.

**Evidence:** log, dspfirm presence note, model/firmware, audio build SHA, and
a short listening report. Physical audio cannot be proven from Azahar.

## HWT-05 — Cross-play networking

Covers `crossplay-net-protocol`.

**Setup:** host the pinned `v160.8` server, build the `network-render` ROM, and
join from the console; optionally join from a PC client for cross-play.

**Steps:** join, move, place/break a block, fire, receive a wave, disconnect
cleanly. Repeat once on each 3DS generation if available.

**Expected:** `UPSTREAM_LIVE_NET_WORLD join=1`, stable controls, server sees
the console, no desync/crash, clean departure. **Evidence:** server log, console
log, screenshots, map/fingerprint, and the server JAR hash.

## HWT-06 — Physical parity and performance

Covers `physical-hardware-parity`: the umbrella acceptance run on both
generations, including the `>= 24 FPS` target.

**Steps:** run HWT-01/02 on Original and New 3DS; collect `BENCH fps=...
p95_ms=...`, `UPSTREAM_MEMORY`, app-memory profile, frame drops and thermal
notes.

**Expected:** both models pass HWT-01/02, Original 3DS stays within 64 MiB
AppMem, and the benchmark reports `>= 24 FPS`. **Evidence:** strict
`hw_evidence.py` JSON/Markdown for both models, benchmark log lines, and
thermal/SD notes. Only this card can clear `physical-hardware-parity`.

> `BENCH fps=... p95_ms=...` is now emitted every 256 rendered frames
> (`scenario=factory_demo`), and `benchmark-report` fails `frame_rate` when the
> record is missing. The existing `de3e249`/`a4a9a96` logs predate it, so this
> card still needs a fresh run from a build that contains the sampler.

## Current exclusions

- Physical runs need the owner to open Homebrew Launcher -> **Y** (NetLoader)
  and confirm the console IP. Never use a connect-and-close TCP check against
  port 17491; hbmenu expects the 3dslink protocol and reports `namelen`. Use
  the real deploy path (`tools/hw_capture.py` or `./dev.sh device deploy`).
- Capture rewards UI and the unified 1:1 release build remain implementation
  work, not hardware queue items.
- Azahar results never satisfy these cards.
