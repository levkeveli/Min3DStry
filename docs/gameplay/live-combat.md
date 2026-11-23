# Live stock-server combat slice (Issue #30)

Status: **Emulator-tested** on Azahar `2126.2` New and Original 3DS profiles.
The ARM11 client receives the upstream combat entities. The stock v160.8
server applies damage, and the handheld advances the original weapon
mounts and draws their projectiles from local input and synchronized
unit state.

## Aim and fire in the unified client

Hold **X** and use the D-pad or Circle Pad to aim a mobile unit.
Release **X** to move again. **L** fires in the last aim direction.
A controlled turret aims directly with either pad. The original
player snapshot carries the aim point and trigger state to the
unmodified v160.8 server, which runs the stock weapon simulation.

```bash
python3 tools/verify_stock_live_weapons.py \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-live-weapons
```


Both Original and New 3DS profiles in Azahar 2126.2 passed the live
replay. The stock server saw the same Gamma stand still while aiming
east with the D-pad and north with the Circle Pad. Each shot happened
after releasing X. The server recorded the correct projectile direction,
a newer snapshot clearing each trigger release, and eastward movement
after firing. The same ROM passed the offline weapon replay on both
profiles and the Original 3DS offline and live turret-possession replay.
[ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45108952293)

## Live projectile drawing

The live viewport advances the original weapon mounts, delayed shots,
bullet positions, collisions, trails and lifetimes. Projectile
textures and colors come from the stock atlas and `BulletType` draw
methods. Scripted bullet creation goes through the stock RPC handler.

The JVM reference checks the Gamma burst, atlas submissions, a remote
Dagger's shot, and cleanup after firing stops. The emulator replay adds
a server-driven Dagger to the same Glacier session as the handheld
Gamma. It captures each projectile outside its owner and compares
the original colors against the idle frame.

The projectile replay passed on both Original and New 3DS profiles in
Azahar 2126.2. The captured Dagger and Gamma shots matched their stock
colors outside the unit and core sprites. The original v160.8 server
confirmed that the Dagger and the player fired. The handheld projectile
group emptied after the trigger release. [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/45111459522)

```bash
python3 tools/verify_stock_live_weapons.py --projectiles \
  --rom /path/to/unified/upstream-probe.3dsx \
  --server /path/to/server-release.jar \
  --output build/stock-live-projectiles
```

## Check

`tools/verify_upstream_live_net.py --combat --controls --terrain-atlas ...`
performs the following against the official `server-release.jar` running
`host Glacier sandbox`:

1. Before the client joins, removes wave-team units, all turrets and the
   non-core enemy base so stock block snapshots cannot starve control packets.
2. Waits for the ARM11 client to report `UPSTREAM_LIVE_CONTROLS_READY`.
3. Raises the joined player unit's health so the Dagger cannot end the session,
   clears the test corridor and places a player Duo at tile `(76, 26)` with ten
   copper (20 ammo).
4. Spawns a 9 HP wave-team Dagger **after** the client has joined at tile
   `(79, 26)` and broadcasts it with `Units.notifyUnitSpawn`.
5. Waits for the client's packet-specific `UnitSpawnCallPacket` marker
   (`UPSTREAM_LIVE_UNIT_SPAWN id=<id>`), then its draw marker
   (`UPSTREAM_LIVE_ENEMY type=dagger id=<id>`).
6. Polls the stock server: the Duo must spend at least one copper and the
   Dagger must be destroyed. One Duo shot deals 9 damage, matching the 9 HP
   fixture exactly.
7. Re-sends the same `UnitDestroyCallPacket` reliably (the stock server sends
   it unreliably) and waits for the client's `UPSTREAM_LIVE_UNIT_DESTROY`
   marker. Dead wave units are also swept when a lost packet leaves zero
   health in `Groups.unit`.

## Observed

Both profiles passed with the same entity id and packet trace:

```text
UPSTREAM_LIVE_UNIT_SPAWN id=217
UPSTREAM_LIVE_ENEMY type=dagger id=217 x=632.0 y=208.0
UPSTREAM_LIVE_UNIT_DESTROY id=217
Stock Duo destroyed the post-join Dagger; ammo 20->19.
old3ds: stationary verification ended by emulator kill; accepted the server's (error) departure
old3ds: UPSTREAM_LIVE_NET_WORLD join=1 map=Glacier bytes=11556 team=1 fingerprint=1a8d0534631f2ab0
old3ds: UPSTREAM_LIVE_CONTROLS_READY unit=214 x=648.0 y=208.0

UPSTREAM_LIVE_UNIT_SPAWN id=217
UPSTREAM_LIVE_ENEMY type=dagger id=217 x=632.0 y=208.0
UPSTREAM_LIVE_UNIT_DESTROY id=217
Stock Duo destroyed the post-join Dagger; ammo 20->19.
new3ds: stationary verification ended by emulator kill; accepted the server's (error) departure
new3ds: UPSTREAM_LIVE_NET_WORLD join=1 map=Glacier bytes=11505 team=1 fingerprint=2745c6ce8172a1a4
new3ds: UPSTREAM_LIVE_CONTROLS_READY unit=214 x=648.0 y=208.0
```

The spawn marker is emitted from the `UnitSpawnCallPacket` branch itself, not
from an entity snapshot. The spawn and destroy ids match the server's returned
Dagger id, and the Duo spent exactly one copper.

## Limitations

- The verification covers the Gamma and Dagger weapon sets. Other weapon
  effects, turret projectile presentation and physical play remain open.
- The port's own turret/wave/pathfinding simulation is verified separately by
  the ARM11 `combat-ticks` and `wave-routing` probes, not inside
  `min3dstry.3dsx`.
- The test hosts `Glacier sandbox` and clears the enemy base to isolate the
  Duo; it does not claim a full attack-map session.
- Physical Old/New 3DS verification remains open with #43.
