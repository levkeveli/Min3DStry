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
- timed stock `sector.capture.current` and `wave.guardianwarn` notification rows;
- the local unit's stock `stat.health`/`stat.ammo` values;
- the original copper item glyph and the local core's copper count;
- a 64x64 live minimap in the top-right corner in both offline and online
  play, using the stock camera-relative region and tile colors.

The status rows use the v160.8 font and inline item glyph pipeline shared with
the objective HUD. They are rebuilt only when the rendered status text changes,
then submitted through `drawObjective` before the objective rows.

The minimap is a native citro2d texture. TeaVM samples the live world into an
RGBA8 grid. The ARM11 renderer reverses the world Y axis as the stock minimap
does and converts the pixels to the PICA200's ABGR byte order and 8-by-8
Morton layout before uploading them through an aligned buffer. Uploads happen
on the first frame of each world and then every two rendered frames. The
texture draw rebinds the top screen because the build palette leaves the
bottom screen selected.

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
UPSTREAM_MINIMAP size=64 hash=1bb8ba2afa313b20
[PASS] minimap model=new3ds distinct=11 size=400x480 PNG=build/emu-verify/minimap-new3ds.png
[PASS] minimap model=old3ds distinct=11 size=400x480 PNG=build/emu-verify/minimap-old3ds.png
```

The verifier selects a sector through `campaign-select.txt`, waits for the
singleplayer load and minimap upload markers, captures the frame, and rejects a
flat minimap region (fewer than four distinct sampled colors). Inspect the
captured terrain pattern as well, since the color count cannot detect scrambled
texture tiles.

The separate `runStockMinimapJvm` task takes the original Glacier map and
`block_colors.png`. It checks the 64x64 ARM11 sample against the v160.8
desktop minimap renderer. Controlled tiles cover ore overlays, liquid
edges, floor shadows, darkness and team colors. Both visibility states
are checked for a building subject to fog. The emulator evidence above is
for the initial minimap integration.

The same JVM task checks the camera-relative sample against the original
renderer and compares the projected bounds at both edges and while panning.
It covers portrait, landscape, and maps smaller than the stock minimum zoom.

The live fixture exercises the countdown/waiting path with `enemies=0`. The
singular/plural enemy rows, the `wave.waveInProgress` branch, and the `m:ss`
timer path are copied from v160.8 `HudFragment.makeStatusTable()` but are not
separately exercised by this fixture. The `winWave > 1` guard and the
`isWaitingWave()` placement match that method and `Logic.isWaitingWave()`
exactly.

## Limitations

- This is the wave/timer/copper/minimap slice of the stock HUD, not the
  complete HUD.
- The minimap is the always-on 56 px slice. Fullscreen and zoom, other units,
  the fog overlay and the complete minimap panel remain open under #40.
- Full item list, additional notifications and the full stock layout remain
  open under #40.
- Physical Old/New 3DS verification remains open with #43.
