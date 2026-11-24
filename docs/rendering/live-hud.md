# Live ARM11 status HUD

Status: **Emulator-tested** on Azahar `2126.2` New and Original 3DS profiles.

## Scope

The live upstream renderer draws variable stock status rows above the campaign
objective panel:

- the pinned wave row: `wave.cap` (`[accent]Wave {0}/{1}`) when a win wave is
  pending, otherwise the `wave` string (`[accent]Wave {0}`);
- the pinned `wave.enemy`/`wave.enemies` string only when enemies remain;
- the stock `wave.enemycore`/`wave.enemycores` row when waves are off and
  attack mode is active;
- the pinned `wave.waiting`/`wave.waveInProgress`/`waiting` string when wave
  rules are active, using the stock `HudFragment` timer formatting;
- the pinned `sector.curcapture` row (`Sector Captured`) when wave rules are
off and a campaign sector is active;
- timed stock `sector.capture.current`, `wave.guardianwarn` and `coreattack`
  notification rows;
- the local unit's stock `stat.health`/`stat.ammo` values;
- the original copper item glyph and the local core's copper count;
- the local core's non-zero item list as stock item icons with counts, wrapped
  into up to three columns clear of the minimap;
- a stock-style pause overlay with `paused`, the campaign planet/sector and
  first objective when available. A sector with a tech tree gets Resume
  (`back`), Research, Load Game, Save Game, Settings and Quit; otherwise the
  menu is Resume/Save/Quit. Load reloads the active campaign slot only (there
  is no save picker) and resets HUD caches; saved-state equality is not
  asserted yet. Settings toggles the stock `setting.coreitems`,
  `setting.minimap`, `setting.showweather` and `setting.showperformance`
  displays. The stock quit confirmation is included, and tapping X
  opens/closes and freezes simulation;
- the stock guardian bar (`state.teams.bosses`) above the bottom edge: one
  boss emoji per guardian (six at most, then `+`), the localized `guardian`
  text and a total-health fill, shown only while wave rules are active;
- a fullscreen minimap view on `R`: the same stock sampled texture over a dark
  backdrop, scaled from 240 px to 960 px with circle-pad/d-pad panning and
  X/Y zoom (1x to 4x), closed with `R` or `B`;
- a live minimap rendered from a 64x64 sample at 56 px in the top-right
  corner, using the stock `MinimapRenderer` color path (`Block.minimapColor`,
  floor/overlay fallback through `MapIO.colorFor`, darkness and liquid/shadow
  edge tints), with the local player marked at the nearest sampled cell and
  living non-fogged units drawn as their team-colored blips; a dark inset frame
  surrounds the map, refreshed every 60 frames.

The status rows use the v160.8 font and inline item glyph pipeline shared with
the objective HUD. Static rows share one stock-style dark panel and are drawn
without per-row backgrounds; a temporary notification keeps its own panel.
Rows are rebuilt only when the rendered status text changes, then submitted
through `drawObjectivePlain`/`drawObjective` before the objective rows.

The minimap is a native citro2d texture. TeaVM samples the live world into an
RGBA8 grid using the stock minimap color path. The ARM11 renderer reverses the
world Y axis as the stock minimap does and converts the pixels to the PICA200's
ABGR byte order and 8-by-8 Morton layout before uploading them through an
aligned buffer. Uploads happen once at startup and every 60 rendered frames. The
texture draw rebinds the top screen because the build palette leaves the bottom
screen selected. The minimap translation unit also compiles without
`M3DS_WORLD_ATLAS`; its static upload buffer is 64 KiB.

## Selected building bars

The friendly building under the handheld cursor shows its stock bars below the
status rows in the base `Block.setBars` order (health, power, items, then
liquids); subclass-specific extra bars remain open:

- health (`stat.health`, `Pal.health`) for every building;
- power for blocks with `consPower`, using the stock power label/fraction;
- items (`bar.items`) for configurable item blocks;
- the dynamic `addLiquidBar` bar, then each keyed consumer/generator liquid bar,
  using the stock liquid label, fraction and color.

Enemy bars stay hidden. The bars reuse the original `bar`/`bar-top` atlas
patches and animate their fill. The host building reference checks health,
power, item, dynamic liquid and keyed liquid states; the **live draw path is
pending** the next emulator batch.

## Verification

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
waits for the renderer marker after the client joins. Both profiles report:

```text
UPSTREAM_STATUS_HUD wave=1 enemies=0 copper=200 rows=5
```

The same runs complete the stock-server combat check and report the packet
and destroy markers documented in [live-combat.md](../gameplay/live-combat.md).

Host row-text coverage (all ten branches, including capture and enemy cores):

```sh
gradle -Pprobe=status-hud runProbeJvm \
  --args=/path/to/romfs/bundles/bundle
```

```text
UPSTREAM_STATUS_ROWS cases=10 ok
UPSTREAM_UNIT_ROWS cases=3 ok
UPSTREAM_NOTIFICATION cases=2 ok
UPSTREAM_GUARDIAN_WARNING cases=2 ok
```

While the wave timer is counting down, the status text changes once per second,
so `UPSTREAM_STATUS_HUD` is also emitted once per second in timer-on runs.

The minimap has an emulator check on both Azahar profiles:

```sh
python tools/verify_minimap_azahar.py --model new3ds
python tools/verify_minimap_azahar.py --model old3ds
```

```text
UPSTREAM_MINIMAP size=64 hash=103bf4ec4480ebb4 cell=34,6
[PASS] minimap model=new3ds distinct=20 cell=(34, 6) marker=white size=400x480 PNG=build/emu-verify/minimap-new3ds.png
[PASS] minimap model=old3ds distinct=20 cell=(34, 6) marker=white size=400x480 PNG=build/emu-verify/minimap-old3ds.png
```

The verifier selects a sector through `campaign-select.txt`, waits for the
singleplayer load and minimap upload markers, captures the frame, and rejects a
flat minimap region (fewer than four distinct sampled colors). It also parses
the reported player cell and requires a white marker pixel at the corresponding
top-screen position, which catches a wrong texture origin or byte order. It
still does not prove per-tile color agreement; the captured terrain pattern
should be inspected as well.

The core item list uses the pinned `CoreItemsDisplay` filter (items with a
positive core count, in content order) and the stock `UI.formatAmount`
suffixes (`k`/`mil`/`b`). `UpstreamCoreItems` is covered by the
status-hud host probe:

```text
UPSTREAM_CORE_ITEM_FORMAT text=12k cases=9 ok
UPSTREAM_CORE_ITEMS rows=2 ok
UPSTREAM_MINIMAP_VIEW cases=5 ok
UPSTREAM_PAUSE_MENU cases=22 ok
UPSTREAM_BOSS_BAR cases=4 ok
UPSTREAM_CORE_ATTACK text="< Core is under attack! >" ok
```

The boss bar covers the stock `HudFragment` summary: no bar without bosses or
wave rules, three bosses at `2500/3000` health, and the six-emoji cap with the
`+` marker. Its on-device guardian-wave rendering is **not verified** yet; the
host probe only checks the collected text and health fraction.

The fullscreen view state covers the open/close flag, the 1x..4x zoom clamps,
pan clamping and reset. Its on-device rendering, pan and zoom are **not
verified** yet; the host probe checks the view math only.

The Azahar verifier can seed a deterministic core inventory through
`/3ds/min3dstry/hud-items.txt` and assert that every requested row was drawn:

```sh
python tools/verify_minimap_azahar.py --model new3ds \
  --rom research/upstream-runtime/build/singleplayer-items/upstream-probe.3dsx \
  --items copper=200,lead=75,metaglass=64,graphite=120,sand=300,coal=90,\
titanium=150,thorium=40,scrap=220,silicon=30,plastanium=18,\
phase-fabric=12,surge-alloy=9,spore-pod=25,pyratite=50
```

```text
[PASS] minimap model=new3ds distinct=20 cell=(34, 6) marker=white items=15 drawn=15 columns=3
```

The Original 3DS profile passed the same path with three seeded items
(`items=3 drawn=3 columns=1`). The fixture is removed after the run.

The pause overlay's selection, wrap, save, quit-confirmation and cancel
transitions are host-covered. Offline worlds with the stock `pauseDisabled`
rule keep simulating while the menu is open. The overlay uses the localized
Menu title in that case. The Ground Zero host probe checks both the normal
pause and resume behavior and continued simulation with this rule enabled.
The pause transitions were exercised on Azahar 2126.2 New 3DS by replaying a
CTM button movie against the integration build (real X/A/B/d-pad events, not a
fixture). The log recorded `open=true options=6`, two `open=false` cancels, the
research open, both core-items toggles, `save=true`, and `quit=true`. The
captured frames show `> Research` and `> Settings` selected after d-pad moves
and the stock research list (`Research 1 / 14`, `B Back`) after A on Research.
See [HUD batch 2](hud-batch-2.md) and
[pause log](evidence/hud-batch-2-pause-new3ds.log).

Verified on the emulator:

- X opens the pause menu (`UPSTREAM_PAUSE_MENU open=true`) and freezes play.
- D-pad moves the selected row, including wrapping; A on Resume closes and
  resumes.
- In a campaign sector, A on Research opens the stock research screen and B
  returns to play.
- A on Settings opens Display Core Items / Show Minimap / Show Weather /
  Display Performance Metrics / Back; CTM runs toggled all four rows
  (`UPSTREAM_SETTINGS index=0..3`). The core-items and minimap rows changed the
  visible HUD, and the performance row added the `FPS` status row.
- A on Load Game re-loads the campaign save: `load=true`, then a second
  `UPSTREAM_SP_LOADED source=campaign`.
- A on Save Game emits `UPSTREAM_PAUSE_MENU save=true`.
- A on Quit, then A on the confirmation, emits `quit=true` and exits.
- B or X cancels the menu; the HUD and minimap remain readable behind it.
- A damaged friendly core raises the stock `coreattack` row (batch 1).

Still **not verified** graphically:

- Load Game saved-state equality and the minimap re-upload (the marker is
  one-shot and the second upload is not logged).
- The overlay's first objective line above the options (planet/sector is
  visible).
- Hiding an active weather overlay: the weather setting toggles
  (`index=2 value=false`), but the batch fixture had no weather.
- Depleted used-item retention and the core-marker orientation.

The live fixture exercises the countdown/waiting path with `enemies=0`. The
singular/plural enemy rows, the `wave.waveInProgress` branch, and the `m:ss`
timer path are copied from v160.8 `HudFragment.makeStatusTable()` but are not
separately exercised by this fixture. The `winWave > 1` guard and the
`isWaitingWave()` placement match that method and `Logic.isWaitingWave()`
exactly.

## Limitations

- This is the wave/timer/copper/minimap slice of the stock HUD, not the
  complete HUD.
- The minimap is a 64x64 sample with a dark inset frame, drawn at 56 px and
  expanded by the fullscreen `R` view. Unit icons beyond the local player and
  the full stock minimap panel layout remain open under #40.
- The item list is icon-and-count only, like the stock core display, but not
  its complete panel/theme layout. More than 18 rows fall back to the overflow
  log rather than a scrollable table.
- The pause overlay covers Resume/Save Game/Quit, plus campaign Research and
  active-slot Load. Stock Settings, planet map, save-picker load and multiplayer
  buttons are not ported yet.
- Additional notifications and the full stock layout remain open under #40.
- Physical Old/New 3DS verification remains open with #43.

## Emulator batch 1 (2026-11-23)

Build: integration branch `test/hud-batch-1` at `870907b` (includes #529,
#532, #533, #534, #535 and the #542 draw-order fix; #526/#527 merged).
Device: Azahar 2126.2 New 3DS, sector-serpulo-15, seed `copper=12345,
lead=1234, silicon=30`, batch fixture `move=1, wave=1, core=1`, and a separate
`pause=1` run. Retained artifacts: [gameplay](evidence/hud-batch-1-gameplay-new3ds.png),
[pause](evidence/hud-batch-1-pause-new3ds.png) and
[UPSTREAM log](evidence/hud-batch-1-new3ds.log).

| Task | Result | Evidence |
| --- | --- | --- |
| Pause overlay rendering/context | **PASS** | [pause screenshot](evidence/hud-batch-1-pause-new3ds.png): `< Paused >`, `Serpulo / Ground Zero`, Back/Research/Save Game/Quit; no HUD overlap |
| Pause X/A/B/D-pad transitions | **NOT VERIFIED** | Host state tests only; no button injection in the batch |
| Pause Research screen | **NOT VERIFIED** | Not opened in the batch |
| Core-attack notification | **PASS** | [gameplay screenshot](evidence/hud-batch-1-gameplay-new3ds.png): full `< Core is under attack! >` row |
| Core item amount suffixes | **PASS** | [gameplay screenshot](evidence/hud-batch-1-gameplay-new3ds.png): `12k`, `1.2k`, `30` |
| Depleted used-item retention | **NOT VERIFIED** | Host tracker cases only |
| Minimap unit blips | **PASS** | [gameplay screenshot](evidence/hud-batch-1-gameplay-new3ds.png) red enemy pixel; host `UPSTREAM_MINIMAP_UNITS` |
| Minimap player marker | **PASS** | `UPSTREAM_MINIMAP ... cell=(34, 6)` in [log](evidence/hud-batch-1-new3ds.log); white marker in gameplay screenshot |
| Minimap core marker | **NOT VERIFIED** | Player spawn overlaps the core in the fixture; host check skipped |
| Singleplayer-sim build guard | **PASS** | `-Pprobe=singleplayer-sim compileJava` `BUILD SUCCESSFUL` |
| Item-list/notification draw order | **PASS** | [gameplay screenshot](evidence/hud-batch-1-gameplay-new3ds.png): full notification above items |
| Pause overlay spacing/labels | **PASS** | [pause screenshot](evidence/hud-batch-1-pause-new3ds.png): no literal tags or status-row overlap |

Fixes from the batch: pause-overlay placement/label stripping (#535) and
notification-above-item-list draw order (#542).
