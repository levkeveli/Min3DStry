# Live stock-server combat slice (Issue #30)

Status: **Experimental / emulator-tested** on Azahar `2126.2` New and Original
3DS profiles. The ARM11 client receives the upstream combat entities and the
stock v160.8 server applies turret damage; the port does not yet run its own
combat simulation inside the live app.

## Check

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
performs the following against the official `server-release.jar` running
`host Glacier attack`:

1. Before the client joins, spawns a wave-team Dagger at tile `(78, 26)` and
   broadcasts it with `Units.notifyUnitSpawn`.
2. Waits for the ARM11 client to report `UPSTREAM_LIVE_CONTROLS_READY`.
3. Places a player Duo at `(76, 26)` with one copper (two ammo) through stock
   server JS.
4. Waits for the client to receive and draw the Dagger
   (`UPSTREAM_LIVE_ENEMY type=dagger ...`).
5. Queries the stock server after one second: the Duo must have spent ammo and
   the Dagger must be below its 150 health (or destroyed).

## Observed

Both profiles exited 0:

```text
UPSTREAM_LIVE_ENEMY type=dagger id=212 x=624.0 y=208.0
Stock Duo fired; Dagger health=132, ammo 2->0.
new3ds: stationary run ended by emulator kill; accepted the server's (error) departure
new3ds: UPSTREAM_LIVE_CONTROLS_READY unit=431 x=648.0 y=208.0

UPSTREAM_LIVE_ENEMY type=dagger id=212 x=624.0 y=208.0
Stock Duo fired; Dagger health=132, ammo 2->0.
old3ds: stationary run ended by emulator kill; accepted the server's (error) departure
old3ds: UPSTREAM_LIVE_CONTROLS_READY unit=484 x=648.0 y=208.0
```

The client now decodes `UnitSpawnCallPacket` in both snapshot and controls
loops. `UPSTREAM_LIVE_BULLETS`/`UPSTREAM_LIVE_TURRETS` telemetry is present for
future client-side combat simulation.

## Limitations

- Mindustry's stock network protocol does not synchronize bullets; clients
  simulate them. The current live client therefore cannot receive a bullet
  list, and only the combatants and server damage are verified.
- The port's own turret/wave/pathfinding simulation is verified separately by
  the ARM11 `combat-ticks` and `wave-routing` probes, not inside
  `min3dstry.3dsx`.
- Physical Old/New 3DS verification remains open with #43.
