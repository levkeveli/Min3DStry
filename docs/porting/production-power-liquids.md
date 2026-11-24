# Production, Power Graph & Liquid Flow Baseline (Issue #39)

**Verification status:** Emulator-tested across pinned upstream Mindustry `v160.8` JVM, TeaVM C `-O0` / `-O2`, and Azahar `2126.2` New/Old 3DS profiles (`-Pprobe=production-ticks`); `production-power-liquids` in [port-capabilities.json](../port-capabilities.json) is **Emulator-tested** under [#39](https://github.com/levkeveli/Min3DStry/issues/39). Physical 3DS memory/frame-time verification remains open.

## 1:1 Upstream Mandate

Per [AGENTS.md](../../AGENTS.md) and [the upstream port plan](../upstream-port.md), all factory recipes, liquid transport, power graph redistribution, battery buffering, and update ordering must execute unmodified upstream Mindustry `v160.8` logic:

- **Pinned Mindustry revision:** `19a0f40d44514f4802a8b79770456e7e32e34da0` (`v160.8`)
- **Pinned Arc revision:** `7445105cd2683daf5b9ac7033ebb07265d3e814d`
- **Verified `server-release.jar` SHA-256:** `faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`

## Host Verification Scope (`UpstreamProductionBaseline.java`)

[UpstreamProductionBaseline.java](../../research/upstream-runtime/host/UpstreamProductionBaseline.java) compiles and runs directly against the unmodified `v160.8` `server-release.jar` in a scratch headless `24x24` world (`Vars.world.loadGenerator`) driven at fixed `1f / 60f` tick deltas via `Vars.logic.update()`:

1. **Pinned Catalog & Consumers (`UPSTREAM_PRODUCTION_CATALOG`):**
   - Verifies block IDs, dimensions, item/liquid capacities, `Pump.pumpAmount`, `PowerGenerator.powerProduction`, `PowerNode` range/max links, `GenericCrafter` craft times and outputs, and `ConsumePower` / `ConsumeItems` / `ConsumeLiquid` / `ConsumeItemFilter` / `ConsumeLiquidFilter` consumer descriptors across 12 representative blocks: `mechanicalPump`, `rotaryPump`, `conduit`, `liquidRouter`, `liquidTank`, `combustionGenerator`, `steamGenerator`, `powerNode`, `battery`, `graphitePress`, `siliconSmelter`, and `cultivator`.
2. **Power & Item Starvation (`UPSTREAM_PRODUCTION_STARVED`):**
   - Places `coreShard` at `(18, 12)`, `mechanicalPump` on `Blocks.water` at `(8, 12)` -> `conduit` at `(9, 12)` -> `cultivator` (`size=2`) at `(10, 12)`, `battery` at `(10, 11)`, `combustionGenerator` at `(10, 10)` and `(9, 11)`, and `graphitePress` (`size=2`) at `(14, 12)`.
   - Steps `60` ticks with empty fuel stores and empty `graphitePress` input: water accumulates in the cultivator (`waterMilli=4641`), while `cultivator.power.status == 0f`, `pods == 0`, `graphite == 0`, and both crafters keep `progress == 0f`.
3. **Recipes, Power Graph Split & Pause (`UPSTREAM_PRODUCTION_DISCONNECT`):**
   - Feeds `2` `Items.coal` into `graphitePress` (crafts `1` `Items.graphite` at tick `90` and halts with `0` remaining coal) and fuels both combustion generators with coal (`cultivator` crafts `1` `Items.sporePod` at tick `199`).
   - Removes `battery` (`batteryTile.remove()`), confirming the `PowerGraph` splits (`generatorA.build.power.graph != crafter.power.graph`), and steps `60` ticks with zero power satisfaction (`power.status == 0f`, `progress` frozen, `pods == 1`).
4. **Power Reconnect & Capacity Clamping (`UPSTREAM_PRODUCTION_FULL`):**
   - Restores `battery` (`batteryTile.setBlock(...)`), confirming the `PowerGraph` merges (`generatorA.build.power.graph == crafter.power.graph`), refills generators through capacity `pods=10` (`filled=2254` ticks after reconnect), and steps `120` further ticks asserting `pods == 10`, `progress` frozen, `water <= liquidCapacity`, and `!crafter.shouldConsume()`.
5. **Deterministic Update Ordering (`UPSTREAM_PRODUCTION_ORDER`):**
   - Runs the entire 4-stage `2,693`-tick simulation twice in independent fresh `24x24` worlds and asserts an identical 64-bit FNV-1a trace hash over per-tick water, spore pod, graphite, generator coal, and power status values.

### Observed Pinned Output

```text
UPSTREAM_PRODUCTION_CATALOG blocks=12 manifest_sha256=29d41b2000392e726c81fae375b0acf903b68c987bb0e4955e15646712da0996
UPSTREAM_PRODUCTION_STARVED ticks=60 waterMilli=4641 pods=0 graphite=0
UPSTREAM_PRODUCTION_DISCONNECT graphiteTick=90 first=199 paused=60 pods=1
UPSTREAM_PRODUCTION_FULL filled=2254 held=120 pods=10 waterCapOk=true
UPSTREAM_PRODUCTION_ORDER runs=2 ticks=2693 trace_hash=0x596d5082ce819d54
```

## TeaVM C `-O0` vs `-O2` & ARM11 Probe (`-Pprobe=production-ticks`)

[UpstreamProductionTickProbe.java](../../research/upstream-runtime/src/gameplay/java/UpstreamProductionTickProbe.java) compiles the upstream Glacier scenario through `UpstreamSimulationWorld.play`, exercising unmodified `mechanicalPump`, `conduit`, `cultivator`, `battery`, and dual `combustionGenerator` updates through TeaVM 0.16.0 C generation:

- **JVM reference (`runProbeJvm`):**
  ```text
  UPSTREAM_PRODUCTION_STARVED ticks=60 waterMilli=4641 pods=0
  UPSTREAM_PRODUCTION_DISCONNECT first=199 paused=60 pods=1
  UPSTREAM_PRODUCTION_FULL filled=2254 held=120 pods=10
  ```
- **Generated TeaVM C on Linux host (`cc -std=gnu11 -O0 -fwrapv` vs `-O2 -fwrapv` after `arm11/fix_reflection_lifetime.py`):** Both optimization levels produced identical `UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`, `UPSTREAM_PRODUCTION_STARVED`, `UPSTREAM_PRODUCTION_DISCONNECT`, and `UPSTREAM_PRODUCTION_FULL` records matching JVM.
- **ARM11 build (`arm11/build.sh` with pinned `devkitpro/devkitarm` `-O2`):** Produced a `21,897,852`-byte `upstream-probe.3dsx` and `114,039,440`-byte ELF (`37,781,504` bytes / `36.03 MiB` page-rounded ELF LOAD segments; `64.28 MiB` subtotal with `24 MiB` managed heap, `4 MiB` linear heap, and `256 KiB` native stack, exceeding the `64 MiB` Original 3DS AppMem budget before GC metadata).

### Azahar ARM11 execution

A local `-O0` build of the same probe booted in Azahar `2126.2` on both
console profiles and emitted the documented trace unchanged:

```text
UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c
UPSTREAM_PRODUCTION_STARVED ticks=60 waterMilli=4641 pods=0
UPSTREAM_PRODUCTION_DISCONNECT first=199 paused=60 pods=1
UPSTREAM_PRODUCTION_FULL filled=2254 held=120 pods=10
```

`UPSTREAM_MEMORY` reported `appHeap=83,783,680` on New 3DS and
`54,423,552` on Original 3DS, each with a 4 MiB linear heap and 256 KiB
native stack. The top-screen probe console and the corresponding
`svcOutputDebugString` records were inspected on both profiles.

### Live building rendering

The live ARM11 draw set includes `Category.liquid`, which covers the original
Bridge Conduit and Phase Conduit (`LiquidBridge` blocks). The host LIQUID
registry renders every liquid-category block, and the reference asserts both
stock bridge categories explicitly so a future predicate change cannot silently
drop them. The dedicated liquid HUD remains open.

## Remaining Scope for Issue #39

- Reduce the ARM11 footprint within the Old 3DS XL (`64 MiB` AppMem) budget.
- Verify production, power and liquid execution on physical Old/New 3DS XL hardware.
