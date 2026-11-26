# Factory, Logistics, Drill & Core Storage Baseline (Issue #26)

**Verification status:** **Emulator-tested** across pinned upstream Mindustry `v160.8` JVM (`UpstreamFactoryBaseline.java` + `-Pprobe=factory-ticks`), TeaVM C `-O0` / `-O2`, and Azahar `2126.2` New/Old profiles, with live conveyor/router/drill rendering also verified against the stock server. `world-blocks-logistics` in [port-capabilities.json](../port-capabilities.json) is `emulator-tested` under [#26](https://github.com/levkeveli/Min3DStry/issues/26); in-app factory simulation, Old 3DS memory-budget optimization and physical hardware verification remain open.

## 1:1 Upstream Mandate

Per [AGENTS.md](../../AGENTS.md) and [the upstream port plan](../upstream-port.md), all conveyors, junctions, sorters, overflow/underflow gates, bridges, routers, distributors, drills, and core inventories execute unmodified upstream Mindustry `v160.8` logic:

- **Pinned Mindustry revision:** `19a0f40d44514f4802a8b79770456e7e32e34da0` (`v160.8`)
- **Pinned Arc revision:** `7445105cd2683daf5b9ac7033ebb07265d3e814d`
- **Verified `server-release.jar` SHA-256:** `faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`

## Host Logistics & Distribution Suite (`UpstreamFactoryBaseline.java`)

[UpstreamFactoryBaseline.java](../../research/upstream-runtime/host/UpstreamFactoryBaseline.java) compiles and runs directly against the unmodified `v160.8` `server-release.jar` in a scratch `24x24` world (`Vars.world.loadGenerator`) at fixed 60 Hz (`Time.setDeltaProvider(() -> 1f)`):

1. **Pinned Catalog (`UPSTREAM_FACTORY_CATALOG`):**
   - Verifies block IDs, sizes, item capacities, conveyor/junction/bridge/router/overflow speeds, invert flags, drill tiers/times, and core unit types across `16` blocks: `conveyor`, `titaniumConveyor`, `armoredConveyor`, `junction`, `itemBridge`, `router`, `distributor`, `sorter`, `invertedSorter`, `overflowGate`, `underflowGate`, `mechanicalDrill`, `pneumaticDrill`, `coreShard`, `coreFoundation`, and `coreNucleus`.
2. **Conveyor, Junction Crossing, Sorter & Overflow Routing (`UPSTREAM_FACTORY_LOGISTICS`):**
   - **Conveyor -> Core timing:** `Blocks.conveyor` fed `Items.copper` delivers into `Blocks.coreShard` at exact tick `29` (`conveyorArrival=29`, starting core copper `100` -> `101`).
   - **Orthogonal `Junction` crossing:** Eastbound copper conveyor and northbound lead conveyor cross through a shared `Blocks.junction` to separate `Blocks.container` sinks with zero cross-contamination (`junctionOk=true`).
   - **`Sorter` filtering:** `Blocks.sorter` configured to `Items.copper` routes copper straight (`sorterFwd`) and deflects `Items.lead` sideways (`sorterOk=true`).
   - **`OverflowGate` fallback:** Saturated forward dead-end `Blocks.conveyor` (`3` `Items.titanium`, `overflowFwd=3`) spills the 4th item sideways into a `Blocks.container` (`overflowSide=1`).
3. **Mechanical Drill -> Router -> Conveyor -> Core (`UPSTREAM_FACTORY_DRILL`):**
   - Places `Blocks.mechanicalDrill` (`size=2`) over four `Blocks.oreCopper` tiles feeding `Blocks.router` -> `Blocks.conveyor` -> `Blocks.coreShard` across ticks `241..720`, recording exact delivery ticks `464=102,626=103`.
4. **Update-Order Determinism (`UPSTREAM_FACTORY_ORDER`):**
   - Replays the entire `720`-tick multi-lane scenario twice in independent fresh `24x24` worlds and asserts an identical 64-bit FNV-1a trace hash (`trace_hash=0x48f18f35f2c8286d`).

### Observed Pinned Output

```text
UPSTREAM_FACTORY_CATALOG blocks=16 manifest_sha256=d4210b8de1bd4c154eaa00a4ad79af403b5220d425becf218528e77a028b5fce
UPSTREAM_FACTORY_LOGISTICS conveyorArrival=29 junctionOk=true sorterOk=true overflowFwd=3 overflowSide=1
UPSTREAM_FACTORY_DRILL ticks=720 coreCopper=103 arrivals=464=102,626=103
UPSTREAM_FACTORY_ORDER runs=2 ticks=720 trace_hash=0x48f18f35f2c8286d
```

## TeaVM C `-O0` vs `-O2` & ARM11 Build (`-Pprobe=factory-ticks`)

[UpstreamFactoryTickProbe.java](../../research/upstream-runtime/src/gameplay/java/UpstreamFactoryTickProbe.java) loads the pinned Glacier map (`2e7b9f883994de6c`) through `UpstreamSimulationWorld.play`:

- **Pinned JVM + Host TeaVM C (`cc -std=gnu11 -O0/-O2 -fwrapv -ffp-contract=off` after `arm11/fix_reflection_lifetime.py`):** All three matched identically:
  ```text
  UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c
  UPSTREAM_FACTORY updates=240 wave=1 arrival=29 copper=201
  UPSTREAM_DRILL updates=720 wave=1 copper=203 arrivals=464=202,626=203
  ```
- **ARM11 build (`arm11/build.sh` with pinned `devkitpro/devkitarm` `-O2`):** Built `upstream-probe.3dsx` (`22,026,788` bytes) and `upstream-probe.elf` (`114,543,748` bytes; `39,378,944` bytes / `37.55 MiB` page-rounded ELF LOAD segments; `65.80 MiB` subtotal with `24 MiB` managed heap, `4 MiB` linear heap, and `256 KiB` native stack, exceeding the `64 MiB` Original 3DS AppMem budget before GC metadata).

## Remaining Scope for Issue #26

- Verify the full upstream runtime on physical New 3DS at >= 24 FPS within its application-memory budget.
- The historical Original 3DS probe exceeded its `64 MiB` application-memory budget. The `-O2` probe totaled `65.80 MiB`.

## Building item storage

The staged stock ItemModule keeps empty and one-entry inventories inline.
A second occupied item ID allocates an array sized to the full stock
catalog. Subclasses retain the protected item array used by upstream
methods. The unified CI build compares item operations, active flow
sampling, and current and legacy save data with the unmodified v160.8 JAR.
This comparison runs on the JVM host. ARM11 heap measurements and dense
reload are tracked separately in #538.
