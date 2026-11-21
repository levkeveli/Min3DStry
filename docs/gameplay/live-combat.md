# Live stock-server combat slice (Issue #30)

Status: **Experimental** on Azahar `2126.2` New and Original 3DS profiles.
The ARM11 client receives the upstream combat entities and the stock v160.8
server applies turret damage; the port does not yet run its own combat
simulation or synchronize bullets inside the live app.

## Check

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
performs the following against the official `server-release.jar` running
`host Glacier attack`:

1. Before the client joins, spawns a wave-team decoy Dagger at tile `(60, 26)`
   so the initial world snapshot contains an enemy, far away from the turret.
2. Waits for the ARM11 client to report `UPSTREAM_LIVE_CONTROLS_READY`.
3. Removes the decoy on the server so it cannot consume the Duo's two copper
   shots.
4. Places a player Duo at `(76, 26)` with one copper (two ammo) through stock
   server JS.
5. Spawns a Dagger **after** the client has joined at `(78, 26)` and sends the
   stock `UnitSpawnCallPacket` through `Vars.net.send(packet, true)` so the
   transport is deterministic; the client must list that exact id in
   `UPSTREAM_LIVE_UNITS ... ids=<id>:dagger:2`.
6. Queries the stock server for that id after one second and requires:
   `found=1`, `0 < health < 150`, position within three tiles of `(624, 208)`,
   ammo below the initial value, and the removed decoy reported as `pre=-1`.

## Observed

Both profiles exited 0:

```text
UPSTREAM_LIVE_UNITS n=5 bullets=0 ids=212:dagger:2,...,527:dagger:2
Stock Duo fired; Dagger health=132, ammo 2->0.
new3ds: stationary run ended by emulator kill; accepted the server's (error) departure

UPSTREAM_LIVE_UNITS n=5 bullets=0 ids=212:dagger:2,...,465:dagger:2
Stock Duo fired; Dagger health=132, ammo 2->0.
old3ds: stationary run ended by emulator kill; accepted the server's (error) departure
```

The client decodes `UnitSpawnCallPacket` in both snapshot and controls loops.
The post-join id in `UPSTREAM_LIVE_UNITS` is the evidence for that path; an
`UPSTREAM_LIVE_ENEMY` line can still refer to the pre-join decoy, which is why
the verifier matches the exact server id instead of the first rendered Dagger.

## Limitations

- Mindustry's stock network protocol does not synchronize bullets; clients
  simulate them. No bullet rendering/telemetry is claimed here, and only the
  combatants and server damage are verified.
- The verifier sends the stock spawn packet reliably to avoid UDP flakiness;
  the stock server normally sends it unreliably.
- The port's own turret/wave/pathfinding simulation is verified separately by
  the ARM11 `combat-ticks` and `wave-routing` probes, not inside
  `min3dstry.3dsx`.
- Physical Old/New 3DS verification remains open with #43.
