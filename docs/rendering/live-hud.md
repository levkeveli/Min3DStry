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
  (`back`), Research, Load Game, Save Game and Quit; otherwise the menu is
  Resume/Save/Quit. Load reloads the active slot only (there is no save picker)
  and resets HUD caches; this path is **not verified** yet. The stock quit
  confirmation is included, and tapping X opens/closes and freezes simulation;
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
positive core count, in content order). `UpstreamCoreItems` is covered by the
status-hud host probe:

```text
UPSTREAM_CORE_ITEMS rows=2 ok
UPSTREAM_PAUSE_MENU cases=13 ok
UPSTREAM_CORE_ATTACK text="< Core is under attack! >" ok
```

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
The following graphical checks are queued for
the next emulator batch and are **not verified** yet:

- Tap X: `UPSTREAM_PAUSE_MENU open=true`, simulation freezes.
- D-pad up/down wraps the rows; A on Resume closes and resumes.
- In a campaign sector, A on Research opens the existing research screen and
  returns to play.
- A on Load Game reloads the active save, restores the saved world and
  re-uploads the minimap. **Not verified** graphically yet.
- In a campaign sector, the overlay shows the planet/sector and first objective
  above the options. **Not verified** graphically yet.
- A on Save Game emits `UPSTREAM_PAUSE_MENU save=true`.
- A on Quit, then A on the stock confirmation, exits with `quit=true`.
- B or X cancels the menu, and the HUD/minimap remain readable behind it.
- A damaged friendly core raises the stock `coreattack` row. This path is
  wired to `Trigger.teamCoreDamage` but is **not verified** graphically yet.

The live fixture exercises the countdown/waiting path with `enemies=0`. The
singular/plural enemy rows, the `wave.waveInProgress` branch, and the `m:ss`
timer path are copied from v160.8 `HudFragment.makeStatusTable()` but are not
separately exercised by this fixture. The `winWave > 1` guard and the
`isWaitingWave()` placement match that method and `Logic.isWaitingWave()`
exactly.

## Limitations

- This is the wave/timer/copper/minimap slice of the stock HUD, not the
  complete HUD.
- The minimap is the always-on 56 px slice drawn from a 64x64 sample with a
  dark inset frame. Fullscreen/zoom, unit icons beyond the local player, and the
  full stock minimap panel layout remain open under #40.
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
