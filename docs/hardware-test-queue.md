# Hardware test queue

Status: **Partially verified.** Physical records exist for both models:
[Original 3DS XL](hardware/evidence/old3ds-xl-a4a9a96.md) (smoke) and
[New 3DS XL](hardware/evidence/new3ds-xl-de3e249.md) (sector-170, 12/12 strict).
A newer New 3DS run (`a328c70`) passed 12/12 and ran for ~10 minutes
(132 `BENCH` windows, `fps_min=29.92`, `fps_max=59.84`, `p95_max=33.50 ms`),
covering the five-minute soak plus pan/zoom, lighting, pause/resume and menu
input. The operator confirmed the scene, controls, minimap and HUD worked. Still
not claimed: full upstream campaign gameplay performance, audible upstream
sound effects/music (the newest emulator build resolves nine clips, but physical
DSP output is unverified), live build-UI gameplay, cross-play, and the Old 3DS
sector-loader/FPS run. The operator has
**only the New 3DS**; HWT-06's Old 3DS requirement and
`physical-hardware-parity` are blocked on Original 3DS hardware. The operator
also found that the visible touch legend was outside the hit zones; the
coordinates themselves are correct (`TOUCH action=menu x=288 y=47`). The labels
are now drawn inside the zones by the #364 fix; physical retest is pending.

Latest evidenced builds: New `a328c70` (2026-11-22, FPS PASS), New `de3e249`
(2026-11-22), and Old `a4a9a96` (2026-11-22). Record the exact commit and ROM
SHA-256 with every run.

Latest emulator-verified build for the next hardware run: `8f683450`
(2026-11-24, batch 2), ROM SHA-256
`6c4d25602a7da31d63b5d5ef7170f69d302f4ff1d10413300d124ae977d8fec9`. It
resolves nine audio clips and `wave.spawn` on both Azahar profiles, passes
`AZAHAR_PROVENANCE_OK`, and asserts the stock POWER/DISTRIBUTION/LIQUID/MATERIAL
draw registry, but has not been run on hardware. See the
[2026-11-24 emulator batch 2](hardware/emulator-batch-2026-11-24b.md) and the
[first batch](hardware/emulator-batch-2026-11-24.md).
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
| HWT-01 | _none for New 3DS — the render capabilities are cleared by `a328c70`; Old 3DS render rerun remains part of HWT-06_ |
| HWT-02 | `world-blocks-logistics`, `production-power-liquids`, `combat-waves-ai`, `touch-build-palette` |
| HWT-03 | `campaign-sectors` |
| HWT-04 | `ndsp-sfx-music` |
| HWT-05 | `crossplay-net-protocol` |
| HWT-06 | `physical-hardware-parity`; New 3DS harness FPS + 10-minute soak are verified, **blocked on Original 3DS hardware** for the Old 3DS half |

Cleared by the `de3e249` New 3DS record: `arm11-toolchain-build`,
`memory-budget-runtime`, `threading-scheduling` and `msav-map-save-compat`.
The `a4a9a96` Original 3DS record also backs the first three. The `a328c70`
New 3DS run passed 12/12 over ~10 minutes (132 `BENCH` windows,
`fps_min=29.92`, `fps_max=59.84`, `p95_max=33.50`) and clears the HWT-01 render
capabilities `citro2d-scene-prototype`, `sprite-atlas-pipeline`,
`world-sprite-renderer` and `lighting-effects-camera`, plus `hud-menus-minimap`
(HUD/menus/minimap were visually confirmed) and the New 3DS harness FPS +
input-soak half of HWT-06. HWT-06 remains **blocked on Original 3DS hardware**
(the operator has only the New 3DS) and on full campaign gameplay. Audio
upstream clips/music, live build-UI gameplay and cross-play remain open.
Preserve those evidence files and do not re-queue cleared capabilities unless
the evidence is invalidated.

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

> `a328c70` exercised the scripted factory, waves, pause menu and main menu,
> and the operator confirmed the HUD and minimap. It did not provide user block
> placement, palette/rotation or config. The visible labels are now drawn
> inside their hit zones with a host invariant test; physical retest is tracked
> by #364. HWT-02 remains open for the live build-UI/palette flow.

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
  `wave.guardianwarn` text, and the boss health bar appears above the bottom
  edge with the guardian text and a fill that follows the boss health.
- The top-right minimap shows the sector terrain, marks the local player with a
  white dot, and follows the player as they move.
- `R` opens the fullscreen map over the HUD; the circle pad/d-pad pans it, `X`
  and `Y` zoom out/in between 1x and 4x, and `B` or `R` closes it without
  moving the player.
- The core item list shows one icon-and-count row per stocked item and wraps
  into columns without covering the minimap.
- With `Show Player Position` enabled, the player's tile coordinates appear
  under the minimap and change as the player moves; all six settings rows and
  Back fit on the top screen.
- Selecting a friendly liquid building shows the stock dynamic and keyed
  consumer/generator liquid bars with their liquid names and colors below the
  power bar; friendly buildings also show the stock health bar, and configurable
  item blocks show the item bar.
- X opens the pause overlay; D-pad + A resume/save/quit, and the quit
  confirmation path saves and exits without corrupting the sector slot.
- Damaging a friendly core shows the stock `< Core is under attack! >` row.

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

Covers `ndsp-sfx-music`. **Setup:** Use the
[audio-enabled unified ARM11 build](../research/upstream-runtime/README.md#stock-sound-effects-and-soundtrack)
with `sd:/3ds/dspfirm.cdc` present and a stock v160.8 server.

**Steps:** Use Join to connect to the stock server. Listen for the UI and
positional effects, an active drill's ambience, and the stock soundtrack.
Run for two minutes and disconnect.

**Expected:** `UPSTREAM_LIVE_AUDIO_OPEN` reports `state=ready`, the soundtrack
starts with `mode=ndsp`, and playback is audible without crackling or hangs.
Record `fallback_no_dsp` separately if the DSP firmware is unavailable.

**Evidence:** log, dspfirm presence note, model/firmware, audio build SHA, and
a short listening report. Physical audio cannot be proven from Azahar.

> `a328c70` logged `AUDIO backend=ndsp state=ready` with only 2 registered
> clips; the operator heard the UI "ding", but all seven `AUDIO_EVENT`s resolved
> to `clip=-1 voice=0`. The 2026-11-24 batch-2 emulator build (`8f683450`,
> SHA-256 `6c4d2560…`) resolves nine clips and `wave.spawn` on both Azahar
> profiles, but Azahar still reports `fallback_no_dsp`. Audible upstream effects
> and music on physical DSP remain **NOT VERIFIED**, so `ndsp-sfx-music` stays
> `emulator-tested`. Use this build for the retest.

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

> New 3DS `a328c70` satisfies the harness FPS and soak half: strict 12/12 over
> ~10 minutes, 132 `BENCH` windows, `fps_min=29.92`, `fps_max=59.84`,
> `p95_max=33.50`, with the sector loader and parity intact. The Old 3DS half
> is **BLOCKED**: the operator has only the New 3DS, so
> `physical-hardware-parity` cannot be completed until an Original 3DS is
> available. The newer `6c4d2560` build (`8f683450`) has not been retested on
> hardware; the `a328c70` FPS numbers still describe the older build. Full
> campaign gameplay remains open on both models.

## Current exclusions

- Physical runs need the owner to open Homebrew Launcher -> **Y** (NetLoader)
  and confirm the console IP. Never use a connect-and-close TCP check against
  port 17491; hbmenu expects the 3dslink protocol and reports `namelen`. Use
  the real deploy path (`tools/hw_capture.py` or `./dev.sh device deploy`).
- Capture rewards UI and the unified 1:1 release build remain implementation
  work, not hardware queue items.
- Azahar results never satisfy these cards.
