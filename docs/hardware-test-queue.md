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
sector-loader/FPS run. HWT-01 through HWT-06 require New 3DS evidence for
release acceptance. Original 3DS physical tests are optional for release.
Track Original 3DS fit and sustained gameplay performance as separate
compatibility work. The documented unified build subtotal of 66.49 MiB
exceeds its 64 MiB application budget. See [architecture](architecture.md). The operator
also found that the visible touch legend was outside the hit zones; the
coordinates themselves are correct (`TOUCH action=menu x=288 y=47`). The labels
are now drawn inside the zones by the #364 fix; physical retest is pending.

Latest evidenced builds: New `a328c70` (2026-11-22, FPS PASS), New `de3e249`
(2026-11-22), and Old `a4a9a96` (2026-11-22). Record the exact commit and ROM
SHA-256 with every run.

Latest emulator-verified build for the next hardware run: `99c7fd6b`
(2026-11-25), ROM SHA-256
`afca650c837f3bccd0f35aa17be84efc6acac874b9edeac285c1f87a00658a1c`
(17,310,404 bytes). The
[full build](https://github.com/levkeveli/Min3DStry/actions/runs/45598586204)
passed, and the downloaded artifact passed both Azahar profiles locally
(parity `0x53a483c7`, New 3DS 55.55 FPS, Original 55.31 FPS, nine audio clips),
but it has not been run on hardware. This is the artifact for the first
New 3DS-only acceptance run under
[#663](https://github.com/levkeveli/Min3DStry/pull/663).
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
| HWT-01 | _none for New 3DS — the render capabilities are cleared by `a328c70`; Original 3DS render reruns are optional_ |
| HWT-02 | `world-blocks-logistics`, `production-power-liquids`, `combat-waves-ai`, `touch-build-palette` |
| HWT-03 | `campaign-sectors` |
| HWT-04 | `ndsp-sfx-music` |
| HWT-05 | `crossplay-net-protocol` |
| HWT-06 | `physical-hardware-parity`; New 3DS harness FPS and 10-minute soak are verified. Full playable New 3DS acceptance is pending |

Cleared by the `de3e249` New 3DS record: `arm11-toolchain-build`,
`memory-budget-runtime`, `threading-scheduling` and `msav-map-save-compat`.
The `a4a9a96` Original 3DS record also backs the first three. The `a328c70`
New 3DS run passed 12/12 over ~10 minutes (132 `BENCH` windows,
`fps_min=29.92`, `fps_max=59.84`, `p95_max=33.50`) and clears the HWT-01 render
capabilities `citro2d-scene-prototype`, `sprite-atlas-pipeline`,
`world-sprite-renderer` and `lighting-effects-camera`, plus `hud-menus-minimap`
(HUD/menus/minimap were visually confirmed) and the New 3DS harness FPS +
input soak. HWT-06 still needs full playable campaign evidence on New 3DS. Audio
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

Attach the New 3DS report, raw log, screenshots and console details. For an
optional Original 3DS run, use `--profile old3ds --target "Original 3DS"`.

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
- C-Stick up/down zooms the world 0.5x–2x and the touch grid stays aligned.
- An Illuminator draws its stock additive `circle-shadow` light; full
  light-buffer shadows remain open.
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
> to `clip=-1 voice=0`. The 2026-11-25 emulator build (`99c7fd6b`,
> SHA-256 `afca650c…`) resolves nine clips and `wave.spawn` on both Azahar
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

Covers `physical-hardware-parity`: the physical acceptance run for the full
playable build on New Nintendo 3DS/2DS.

**Steps:** run HWT-01/02 on New 3DS; collect `BENCH fps=...
p95_ms=...`, `UPSTREAM_MEMORY`, app-memory profile, frame drops and thermal
notes.

**Expected:** New 3DS passes HWT-01/02, stays within its application-memory
budget, and sustains `>= 24 FPS` on a representative playable workload.
**Evidence:** strict `hw_evidence.py` JSON/Markdown, benchmark log lines, and
thermal/SD notes. Only this card can clear `physical-hardware-parity`.

> New 3DS `a328c70` satisfies the harness FPS and soak half: strict 12/12 over
> ~10 minutes, 132 `BENCH` windows, `fps_min=29.92`, `fps_max=59.84`,
> `p95_max=33.50`, with the sector loader and parity intact. The newer
> `afca650c` build (`99c7fd6b`) still needs a physical New 3DS run. The
> `a328c70` FPS numbers apply to the earlier harness. Original 3DS physical
> evidence is optional.

## Current exclusions

- Physical runs need the owner to open Homebrew Launcher -> **Y** (NetLoader)
  and confirm the console IP. Never use a connect-and-close TCP check against
  port 17491; hbmenu expects the 3dslink protocol and reports `namelen`. Use
  the real deploy path (`tools/hw_capture.py` or `./dev.sh device deploy`).
- Capture rewards UI and the unified 1:1 release build remain implementation
  work, not hardware queue items.
- Azahar results never satisfy these cards.
