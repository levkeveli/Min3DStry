# Live ARM11 status HUD

Status: **Emulator-tested** on Azahar `2126.2` New and Original 3DS profiles.

## Scope

The live upstream renderer draws variable stock status rows above the campaign
objective panel:

- the pinned wave row: `wave.cap` (`[accent]Wave {0}/{1}`) when a win wave is
  pending, otherwise the `wave` string (`[accent]Wave {0}`);
- the pinned `wave.enemy`/`wave.enemies` string only when enemies remain;
- the pinned `wave.waiting`/`wave.waveInProgress`/`waiting` string when wave
  rules are active, using the stock `HudFragment` timer formatting;
- the original copper item glyph and the local core's copper count.

The status rows use the v160.8 font and inline item glyph pipeline shared with
the objective HUD. They are rebuilt only when the rendered status text changes,
then submitted through `drawObjective` before the objective rows.

## Verification

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
waits for the renderer marker after the client joins. Both profiles report:

```text
UPSTREAM_STATUS_HUD wave=1 enemies=0 copper=200 rows=3
```

The same runs complete the stock-server combat check and report the packet
and destroy markers documented in [live-combat.md](../gameplay/live-combat.md).

## Limitations

- This is the wave/timer/copper slice of the stock HUD, not the complete HUD.
- Health, ammo, full item list, minimap, notifications and the full stock
  layout remain open under #40.
- Physical Old/New 3DS verification remains open with #43.
