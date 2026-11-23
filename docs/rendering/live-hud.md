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
- a stock-style pause overlay with `paused`, Resume (`back`), Save Game and
  Quit rows, plus a Research row when the sector has a tech tree; the stock
  quit confirmation is included, and tapping X opens/closes it and freezes
  simulation;
- a live minimap rendered from a 64x64 sample at 56 px in the top-right
  corner, using the stock `MinimapRenderer` color path (`Block.minimapColor`,
  floor/overlay fallback through `MapIO.colorFor`, darkness and liquid/shadow
  edge tints), with the local player marked at the nearest sampled cell and
  refreshed every 60 frames.

The status rows use the v160.8 font and inline item glyph pipeline shared with
the objective HUD. They are rebuilt only when the rendered status text changes,
then submitted through `drawObjective` before the objective rows.

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
positive core count, in content order) and the stock `UI.formatAmount`
suffixes (`k`/`mil`/`b`). `UpstreamCoreItems` is covered by the
status-hud host probe:

```text
UPSTREAM_CORE_ITEMS rows=2 ok
UPSTREAM_PAUSE_MENU cases=9 ok
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
transitions are host-covered. The following graphical checks are queued for
the next emulator batch and are **not verified** yet:

- Tap X: `UPSTREAM_PAUSE_MENU open=true`, simulation freezes.
- D-pad up/down wraps the rows; A on Resume closes and resumes.
- In a campaign sector, A on Research opens the existing research screen and
  returns to play.
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
- The minimap is the always-on 56 px slice drawn from a 64x64 sample:
  fullscreen/zoom, unit blips beyond the local player, and the full stock
  minimap panel layout remain open under #40.
- The item list is icon-and-count only, like the stock core display, but not
  its complete panel/theme layout. More than 18 rows fall back to the overflow
  log rather than a scrollable table.
- The pause overlay covers Resume/Save Game/Quit, plus the campaign Research
  entry. Stock Settings, planet map, load and multiplayer buttons are not
  ported yet.
- Additional notifications and the full stock layout remain open under #40.
- Physical Old/New 3DS verification remains open with #43.
