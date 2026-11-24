# Combat, Turrets, Enemy Waves & Flowfield Pathfinding Baseline (Issue #30)

**Verification status:** Host-verified across pinned upstream Mindustry `v160.8` JVM (`UpstreamCombatBaseline.java`, `UpstreamCombatSimulationBaseline.java`, `-Pprobe=combat-ticks`, and `-Pprobe=wave-routing`) and TeaVM C `-O0` / `-O2` (clang-preferred, GCC fallback with `-fno-tree-coalesce-vars`; `-std=gnu11 -fwrapv -ffp-contract=off`) for `combat-ticks` and `wave-routing`. The `Probe wave-routing` CI job asserts all six trace lines and the probe exit status at both optimization levels. ARM11 `.3dsx`/`.elf` are compiled and both gameplay probes executed on Azahar `2126.2` New and Old profiles. `combat-waves-ai` in [port-capabilities.json](../port-capabilities.json) is **Emulator-tested** under [#30](https://github.com/levkeveli/Min3DStry/issues/30); the separate stock-server live slice is tracked in [live-combat.md](../gameplay/live-combat.md), while full in-app combat simulation, Old 3DS memory budget optimization, and physical hardware verification remain open.

## 1:1 Upstream Mandate

Per [AGENTS.md](../../AGENTS.md) and [the upstream port plan](../upstream-port.md), all unit stats, bullet trajectories, turret reload/ammo/power rules, wave spawning, `Pathfinder` flowfields, and `GroundAI` navigation execute unmodified upstream Mindustry `v160.8` logic:

- **Pinned Mindustry revision:** `19a0f40d44514f4802a8b79770456e7e32e34da0` (`v160.8`)
- **Pinned Arc revision:** `7445105cd2683daf5b9ac7033ebb07265d3e814d`
- **Verified `server-release.jar` SHA-256:** `faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`

## Host Catalog & Simulation Suite

1. **Unit & Bullet Catalog ([UpstreamCombatBaseline.java](../../research/upstream-runtime/host/UpstreamCombatBaseline.java)):**
   - Verifies all `69` `UnitType` definitions and `202` `BulletType` definitions (`id`, health, armor, speed, hitSize, flying, range, damage, lifetime, pierce, splashDamage, collision flags) with deterministic SHA-256 manifest digest:
     `UPSTREAM_COMBAT units=69 bullets=202 manifest_sha256=e35013e20c50cbe83169794e898e281898c717f1f373e9b84796090470a76882`
2. **Turret Catalog, Multi-Turret Combat & Flowfield Rerouting ([UpstreamCombatSimulationBaseline.java](../../research/upstream-runtime/host/UpstreamCombatSimulationBaseline.java)):**
   - Runs directly against unmodified `server-release.jar` in a scratch `32x32` world at fixed 60 Hz (`Time.setDeltaProvider(() -> 1f)`) with `Vars.state.rules.attackMode = true`, `Pathfinder`, `ControlPathfinder`, and `AsyncCore` active:
   - **Turret Catalog (`UPSTREAM_COMBAT_TURRETS`):** Verifies IDs, turret subclasses, sizes, health, ranges, reloads, maxAmmo, target masks, and point-defense/tractor damages across `18` defense turrets (Serpulo and Erekir: `duo`, `scatter`, `scorch`, `hail`, `wave`, `lancer`, `arc`, `parallax`, `swarmer`, `salvo`, `segment`, `tsunami`, `fuse`, `ripple`, `cyclone`, `foreshadow`, `spectre`, `meltdown`).
   - **Multi-Turret Combat (`UPSTREAM_COMBAT_DUEL`):**
     - `Blocks.duo` fed copper by `Blocks.conveyor` delivers at tick `29`, fires at tick `49`, hits at tick `59`, and destroys a stationary `UnitTypes.dagger` at tick `379` (`duo=29/49/59/379`).
     - `Blocks.hail` loaded with `Items.graphite` fires artillery onto two adjacent stationary Daggers (`(18, 22)` and `(18, 23)`), damaging both in one impact via `splashDamageRadius` (`hailSplash=2@80`).
     - `Blocks.lancer` wired to a charged `Blocks.battery` charges and fires its piercing laser, damaging a stationary Dagger while draining battery storage (`lancerHit=121`).
   - **Flowfield Pathfinding & Dynamic Obstacle Rerouting (`UPSTREAM_COMBAT_PATHFINDING`):**
     - Converges `Team.crux` `Pathfinder.costGround` / `Pathfinder.fieldCore` weights toward `Blocks.coreShard` at `(6, 16)` (`openHash=231555f2432a19e1`), places a 5-tile vertical `Blocks.copperWall` barrier (`x=16, y=14..18`), calls `Vars.pathfinder.updateTile(...)`, waits for `completeWeights` convergence (`blockedHash=2f7e63891b7ab039`), and steps a live `GroundAI` `UnitTypes.dagger` spawned at `(20, 16)` for `360` ticks, confirming lateral rerouting around the barrier (`end=15,18 maxDy=4 dist=112->72`).
   - **Update-Order Determinism (`UPSTREAM_COMBAT_ORDER`):**
     - Replays the entire `940`-tick multi-phase scenario twice in independent fresh worlds and verifies an identical 64-bit FNV-1a trace hash (`trace_hash=0x30f5f9a444d020c3`).

### Observed Pinned Host Output

```text
UPSTREAM_COMBAT units=69 bullets=202 manifest_sha256=e35013e20c50cbe83169794e898e281898c717f1f373e9b84796090470a76882
UPSTREAM_COMBAT_TURRETS blocks=18 manifest_sha256=064627fbce664cc8525f47c3cd0721af9195572fc47e4e09f6ee91ba8579062b
UPSTREAM_COMBAT_DUEL duo=29/49/59/379 hailSplash=2@80 lancerHit=121
UPSTREAM_COMBAT_PATHFINDING openHash=231555f2432a19e1 blockedHash=2f7e63891b7ab039 start=20,16 end=15,18 maxDy=4 dist=112->72
UPSTREAM_COMBAT_ORDER runs=2 ticks=940 trace_hash=0x30f5f9a444d020c3
```

## TeaVM C `-O0` vs `-O2` & ARM11 Probes

Both gameplay probes under [research/upstream-runtime/src/gameplay/java/](../../research/upstream-runtime/src/gameplay/java/) run the unmodified Glacier scenario (`UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`). `combat-ticks` emits identical traces across pinned `v160.8` JVM and Linux host TeaVM C compiled with `clang` using `-std=gnu11 -fwrapv -ffp-contract=off` at both `-O0` and `-O2` (after `arm11/fix_reflection_lifetime.py`). `wave-routing` matches on JVM, host TeaVM C and ARM11/Azahar. The `Probe wave-routing` job passed on verified head `7a6657d`; the final #297 merge added only current main:

1. **`-Pprobe=combat-ticks` ([UpstreamCombatTickProbe.java](../../research/upstream-runtime/src/gameplay/java/UpstreamCombatTickProbe.java)):**
   ```text
   UPSTREAM_COMBAT delivery=29 shot=49 hit=62 destroyed=382 supplied=20
   UPSTREAM_WAVE next=2 groundSpawns=0 enemies=1 units=gamma=1
   ```
   - **ARM11 build (`arm11/build.sh` with pinned `devkitpro/devkitarm` `-O2`, point-in-time local measurement; CI validates non-empty `.3dsx`/`.elf` outputs):** Built `upstream-probe.3dsx` (`21,893,900` bytes) and `upstream-probe.elf` (`113,685,180` bytes; `37,437,440` bytes / `35.70 MiB` page-rounded ELF LOAD segments; `63.95 MiB` subtotal with `24 MiB` managed heap, `4 MiB` linear heap, and `256 KiB` native stack before GC bookkeeping and runtime heap allocations, exceeding practical Original 3DS `64 MiB` AppMem headroom).
2. **`-Pprobe=wave-routing` ([UpstreamWaveRoutingProbe.java](../../research/upstream-runtime/src/gameplay/java/UpstreamWaveRoutingProbe.java)):**
   ```text
   UPSTREAM_WAVE_SETUP core=81,26 enemyCore=81,224 groundSpawns=1 fieldHash=629058db4926d64e
   UPSTREAM_WAVE wave=1 spawned=dagger=1 groundSpawned=1 first=79,218->80,172 distance=1533->1165 min=1165 alive=1/1 ammo=30 nextWave=2
   UPSTREAM_WAVE wave=2 spawned=dagger=1,gamma=2 groundSpawned=1 first=82,217->80,171 distance=1524->1159 min=1159 alive=1/1 ammo=30 nextWave=3
   UPSTREAM_WAVE wave=3 spawned=dagger=2 groundSpawned=2 first=79,219->80,173 distance=1545->1177 min=1177 alive=2/2 ammo=30 nextWave=4
   UPSTREAM_WAVE wave=4 spawned=dagger=2 groundSpawned=2 first=79,219->80,173 distance=1546->1177 min=1177 alive=2/2 ammo=29 nextWave=5
   ```
   - The current probes are verified on the pinned JVM reference, host TeaVM C `-O0`/`-O2`, and both ARM11 Azahar profiles. Earlier Azahar `2126.2` runs for [combat-ticks](../../research/upstream-runtime/README.md#exercise-upstream-combat-and-the-first-glacier-wave) and [wave-routing](../../research/upstream-runtime/README.md#exercise-upstream-waves-and-ground-routing) matched these traces; the runtime evidence below is from the merged probes.

### Azahar `2126.2` runtime evidence

Both probes were rebuilt with the pinned devkitARM image and run on both 3DS
profiles:

```text
UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c
UPSTREAM_COMBAT delivery=29 shot=49 hit=62 destroyed=382 supplied=20
UPSTREAM_WAVE next=2 groundSpawns=0 enemies=1 units=gamma=1
```

```text
UPSTREAM_WAVE_SETUP core=81,26 enemyCore=81,224 groundSpawns=1 fieldHash=629058db4926d64e
UPSTREAM_WAVE wave=1 spawned=dagger=1 groundSpawned=1 first=79,218->80,172 distance=1533->1165 min=1165 alive=1/1 ammo=30 nextWave=2
UPSTREAM_WAVE wave=2 spawned=dagger=1,gamma=2 groundSpawned=1 first=82,217->80,171 distance=1524->1159 min=1159 alive=1/1 ammo=30 nextWave=3
UPSTREAM_WAVE wave=3 spawned=dagger=2 groundSpawned=2 first=79,219->80,173 distance=1545->1177 min=1177 alive=2/2 ammo=30 nextWave=4
UPSTREAM_WAVE wave=4 spawned=dagger=2 groundSpawned=2 first=79,219->80,173 distance=1546->1177 min=1177 alive=2/2 ammo=29 nextWave=5
```

Both Azahar profiles matched the JVM trace exactly. Those captures verify the
trace. The host-C step checks the six trace lines and process exit status at
`-O0` and `-O2` using the pinned upstream Glacier map. It also fails on a
listener-disposal error. The standalone combat-ticks clang replay was a manual
check.

The standalone host probe exits after printing the trace without firing
`ResetEvent`; dispatching that reset into TeaVM's monitor/event queue after
the trace can deadlock the host C build. It also disposes and removes
registered application listeners through the probe-local
`ProbeListenerShutdown` helper before returning, so Arc listener fibers cannot
resume during TeaVM shutdown. The host C replay passes `--host-exit` to terminate
after flushing the trace. The default ARM11 invocation preserves the console
return path, and the JVM returns normally. Host-C checks go
through `.github/scripts/cc_host.sh`, which prefers `clang`, otherwise uses
GCC with `-fno-tree-coalesce-vars` when the compiler accepts it, and fails
with a clear diagnostic if neither compiler exists. The flag avoids a GCC 13
`rewrite_out_of_ssa` internal compiler error in `TypeIO.c` that the larger
generated translation unit can trigger.

## Remaining Scope for Issue #30

- Integrate the upstream turret, wave spawner, unit AI, and pathfinder loop into the live interactive `min3dstry.3dsx` session (currently standalone probe artifacts).
- Reduce the ARM11 ELF/heap footprint within the Original 3DS `64 MiB` AppMem budget and benchmark >= 24 FPS execution on physical Old and New 3DS hardware.

## Offline flowfield search storage

The offline runtime tracks which tiles were visited in the current stock
search with lazily allocated bitset pages. It clears the visit state when
upstream starts another generation. Frontier entries retain upstream FIFO
order using small chunks instead of one eagerly sized array.

The campaign archive reference compares the frontier with Arc's original
queue. It also checks visit state across word, page and directory-group
boundaries, resets and stock generation rollover. The original terrain costs
and flowfield update rules continue to drive gameplay.

Packed terrain costs use fixed-size chunks to fit the ARM11 collector's allocation limits.
Working and completed fields share immutable chunks until the
pathfinder changes a working chunk. Publishing and resetting a working
field preserve the completed values visible to units and base generation.
The pathfinder reference run by the campaign archive checks writes, resets,
and publication across page boundaries and in the partial trailing chunk.
