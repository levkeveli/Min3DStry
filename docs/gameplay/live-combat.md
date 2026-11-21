# Live stock-server combat slice (Issue #30)

Status: **Emulator-tested** on Azahar `2126.2` New and Original 3DS profiles.
The ARM11 client receives the upstream combat entities and the stock v160.8
server applies turret damage; the port does not yet run its own combat
simulation or synchronize bullets inside the live app.

## Check

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
performs the following against the official `server-release.jar` running
`host Glacier attack`:

1. Before the client joins, spawns a wave-team Dagger at tile `(60, 26)`
   (outside the Duo's range) and broadcasts it with `Units.notifyUnitSpawn`.
2. Waits for the ARM11 client to report `UPSTREAM_LIVE_CONTROLS_READY`.
3. Places a player Duo at `(76, 26)` with one copper (two ammo) through stock
   server JS.
4. Spawns a second Dagger **after** the client has joined and broadcasts it
   with `Units.notifyUnitSpawn`, exercising the client's
   `UnitSpawnCallPacket` path.
5. Asserts the post-join id differs from the pre-join id and prints both
   (`UPSTREAM_COMBAT_IDS spawn=<id> post=<id>`).
6. Waits for the client to receive and draw the exact post-join id
   (`UPSTREAM_LIVE_ENEMY type=dagger id=<post-id>`).
7. Queries the stock server for that id after one second: the Duo must have
   spent ammo and the Dagger must be below its 150 health (or destroyed).

## Observed

Both profiles exited 0:

```text
UPSTREAM_COMBAT_IDS spawn=212 post=270
UPSTREAM_LIVE_ENEMY type=dagger id=270 x=632.0 y=208.0
Stock Duo fired; Dagger health=-1, ammo 2->0.
old3ds: stationary verification ended by emulator kill; accepted the server's (error) departure
old3ds: UPSTREAM_LIVE_CONTROLS_READY unit=259 x=648.0 y=208.0

UPSTREAM_COMBAT_IDS spawn=212 post=271
UPSTREAM_LIVE_ENEMY type=dagger id=271 x=632.0 y=208.0
Stock Duo fired; Dagger health=-1, ammo 2->0.
new3ds: stationary verification ended by emulator kill; accepted the server's (error) departure
new3ds: UPSTREAM_LIVE_CONTROLS_READY unit=266 x=648.0 y=208.0
```

The post-join ids differ from the pre-join id, and the pre-join Dagger is out
of Duo range, so the destroyed Dagger is unambiguously the one delivered by
`UnitSpawnCallPacket`.

## Limitations

- Mindustry's stock network protocol does not synchronize bullets; clients
  simulate them. No bullet rendering/telemetry is claimed here, and only the
  combatants and server damage are verified.
- The port's own turret/wave/pathfinding simulation is verified separately by
  the ARM11 `combat-ticks` and `wave-routing` probes, not inside
  `min3dstry.3dsx`.
- Physical Old/New 3DS verification remains open with #43.
