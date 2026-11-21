# Upstream content registry baseline

[Issue #55](https://github.com/levkeveli/Min3DStry/issues/55) uses this JVM
reference to verify the order and IDs of content registered by upstream
Mindustry v160.8. Run from a scratch directory with the pinned official
`server-release.jar` described in the [runtime probe](../README.md).

```sh
java -cp /path/to/server-release.jar /path/to/UpstreamContentBaseline.java > registry.log
grep '^UPSTREAM_CONTENT' registry.log > registry.tsv
sha256sum registry.tsv
```

Each record contains the prefix, content type, numeric ID, and internal name,
separated by tabs. The name is `-` when the upstream entry has no mappable name.
The pinned JAR emits 922 records, including 447 blocks, 22 items, 11 liquids,
and 69 units. The SHA-256 of the records with LF line endings and a final
newline is `4bf0187404d8be39036dcda871423de4fee9278e0bae6f220831839496d6895e`.

The probe runs the upstream base registration step before content
initialization. Use its output to check registration order and names. Gameplay
behavior and map loading require separate checks.

The pinned upstream `Content` constructor assigns the ID as it registers each
entry. Before printing a record, the probe checks that the ID is the next
index for its type and that `ContentLoader.getByID` resolves it to the same object.

## Building factories

From a scratch directory, use the original server JAR for a JVM reference.
This probe initializes content and constructs one building per block.

```sh
java -cp /path/to/server-release.jar /path/to/UpstreamBuildingBaseline.java > factories-reference.log
grep '^UPSTREAM_BUILDING' factories-reference.log > factories-reference.tsv
```

From the runtime probe directory, run the same check against the adapted class.

```sh
gradle -PupstreamCheckout=/path/to/Mindustry -PreferenceJar=/path/to/server-release.jar \
    -ParcCheckout=/path/to/Arc \
    -Pprobe=content runProbeJvm runBuildingProbeJvm > factories-adapted.log
grep '^UPSTREAM_BUILDING' factories-adapted.log > factories-adapted.tsv
cmp /path/to/factories-reference.tsv factories-adapted.tsv
```

The official JVM and the JVM with the adapted class matched all 447 factory
records. Each record contains the block ID, internal block name, and produced
building class after the prefix. The SHA-256 with LF line endings and a final
newline is `fac17ffd5c22970b9a53382bd977ec9a21df05379ee9badb2861faeb9fc95a52`.

The content mode extracts `Block.java` and its compile-time annotations from
the pinned Mindustry Git revision. In `Block.initBuilding`, the adapted class
checks for an empty simple name to identify anonymous subclasses. The
[TeaVM reflection policy](https://www.teavm.org/docs/runtime/compiler-extensions.html#reflectionpolicy)
keeps the single-argument inner building constructors available to the native
lookup. The generated dependency JAR omits the original `Block.class` so the
TeaVM compiler resolves the adapted class. Both generated artifacts stay in
the build directory.

Use `-Pprobe=content generateC` with `-Dorg.gradle.jvmargs=-Xmx3g` to reproduce
the compiler check. It now passes the anonymous class lookup during analysis.
The [resource probe](../README.md#read-pinned-arc-resources) documents the
adaptation used by content mode for Arc's classpath lookup. C generation now
also uses the fixed executor substitutions described below. Native building
factory parity is tracked in #55.

## Fixed executor

Use `-Pprobe=executor`, `-ParcCheckout=/path/to/Arc`, and the standard JVM and
TeaVM C generation commands to check an executor created by upstream
`arc.util.Threads.executor`. The JVM uses its JDK executor. TeaVM uses the
port's fixed executor through its [substitution policy](https://www.teavm.org/docs/runtime/compiler-extensions.html#substitutionpolicy).
The test verifies task results, propagated exceptions, timed waits,
cancellation, and shutdown in each environment. The generated native Windows
program and the [devkitARM build](https://github.com/levkeveli/Min3DStry/actions/runs/44770447356)
matched the JVM, including both console profiles in Azahar 2126.2.

The generated Arc `OS.java` reports one execution lane for the cooperative
TeaVM C scheduler, avoiding the unsupported JVM processor-count call. The
fixed executor uses managed Java threads and drains its queue as work arrives.
Tasks can yield at TeaVM suspension points. Contended monitors in code that
TeaVM compiles as synchronous are still a runtime limitation. Native
parallel execution, dynamic pool variants, and the complete JDK concurrency
API are outside this probe.

## Initialize content and instantiate building factories

Use `-Pprobe=content-init`, `-ParcCheckout=/path/to/Arc`, and
`-Dorg.gradle.jvmargs=-Xmx3g` with the runtime build commands. The driver calls
upstream `ContentLoader.init` with a headless `Settings` and `GameState`,
checks RGBA writes and copies through Arc Pixmap, and instantiates the factory
for each of the 447 registered blocks. Compare its `UPSTREAM_BUILDING` records
with the factory reference above.

The build applies a patch to the pinned Arc Pixmap. It uses Arc's existing
direct-buffer allocation and Java PNG decoder, with its Java 16 buffer-copy
path. It explicitly clears blank direct buffers because TeaVM C allocates them
through `malloc`. Image decoding and GPU uploads require separate tests.

For Windows x64, run
`python3 arm11/fix_reflection_lifetime.py build/generated/teavm/c` before
compiling the generated C. The ARM11 build runs the same fix automatically.
TeaVM 0.16.0 emitted three building types' reflection descriptors as local
compound literals. The fix gives them static storage so upstream factory
discovery can read them after class initialization.

The fix also roots the receiver and arguments of reflective constructors
across their allocations. It pins the new instance while the constructor
runs. The C emitter treated the reflective invocation as an intrinsic that
could skip garbage collection bookkeeping. In a separate Windows C test,
forcing collection inside a reflective constructor reproduced the lost
instance, and the patched program returned the expected class and payload.

The [ARM11 build](https://github.com/levkeveli/Min3DStry/actions/runs/44778519202)
completed initialization, passed the RGBA copy check, and matched all 447
ordered JVM factory records in both Azahar 2126.2 console profiles. Both
emulator records produced the factory reference SHA-256 above. Physical
hardware and map loading still require verification.

## Save writer baseline

Issue #28 needs the exact upstream writer output as a reference. With Java 17,
the pinned server JAR and the original Glacier map, run:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamSaveBaseline.java \
    /path/to/glacier.msav
```

Expected stable prefix:

```text
UPSTREAM_SAVE header=MSAV version=13 bytes=<n>
```

The byte count can vary between runs; the `MSAV` header and save version are
the contract. This is a host-only reference. It does not read or write a
Min3DStry format and is not a 3DS test.

## Save resilience checks

[Issue #42](https://github.com/levkeveli/Min3DStry/issues/42) adds host checks
for malformed, unsupported and interrupted saves. They use only the pinned
server JAR, the original Glacier map and upstream `SaveIO`:

```sh
javac -cp /path/to/server-release.jar -d /tmp/save-resilience \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamSaveResilienceProbe.java
java -cp /path/to/server-release.jar:/tmp/save-resilience \
    UpstreamSaveResilienceProbe /path/to/glacier.msav
```

Expected records:

```text
UPSTREAM_SAVE_RESILIENCE valid=ok
UPSTREAM_SAVE_RESILIENCE truncated=rejected
UPSTREAM_SAVE_RESILIENCE bad_header=rejected
UPSTREAM_SAVE_RESILIENCE unknown_version=rejected
UPSTREAM_SAVE_RESILIENCE backup=recovered
MIN3DSTRY_SAVE_POLICY existing=protected
MIN3DSTRY_SAVE_POLICY interrupted=rejected
UPSTREAM_SAVE_RESILIENCE_PASS
```

The probe writes to a temporary directory, loads the valid save back through
`SaveIO.load`, rejects a truncated save at full-load time, rejects bad headers
and unknown versions, and verifies that `SaveIO`'s backup file is used when the
main slot is corrupt. `SaveIO.isSaveValid` only reads metadata, so a truncated
save can still report valid metadata; the full load is the authoritative check.
The `UPSTREAM_SAVE_RESILIENCE` records cover upstream behavior: the backup
record uses `SaveIO.save` to create the sibling backup, then
`SaveIO.isSaveValid`/`SaveIO.load` to recover from a corrupt main slot.
`MIN3DSTRY_SAVE_POLICY` records are port-side checks, not upstream claims: the
interrupted-write check writes to a sibling `.tmp` file, validates it, and only
then moves it into place; an injected failure must leave neither a final save
nor a temporary save. The existing-save check verifies that the port policy
never replaces a valid file. This is a host-only check, not a Min3DStry format
and not a 3DS result; the ARM11 round-trip probe still writes a new output only
and validates it by reloading.

## Audio asset inventory

Issue #29 needs the exact pinned sound/music set before conversion. The server
JAR does not contain these files, so scan the pinned Git checkout:

```sh
java /path/to/Min3DStry/research/upstream-runtime/host/UpstreamAudioInventory.java \
    /path/to/Mindustry
```

Expected pinned v160.8 baseline (paths and per-file hashes are printed above
the summaries):

```text
UPSTREAM_AUDIO kind=music files=16 bytes=28493699 manifest_sha256=d637f9d895868758d6a749ef445fd2912fe0e3d768050372618a276ae4d8cacd
UPSTREAM_AUDIO kind=sounds files=205 bytes=7093890 manifest_sha256=8a848916f9b95b3e45f2de2b9def2081c6b0778e0548e36697268f48057cdcbd
UPSTREAM_AUDIO total_files=221 total_bytes=35587589 manifest_sha256=f16c7f1c163d04b9843390146aafc2197cb6f6a3ad8db30e1872c7b1853be5fc
```

This is an inventory only: no decode, conversion, RomFS packing or audio
playback is claimed. Asset provenance/licensing still needs per-file review
before distribution; see the [asset pipeline](../../../docs/rendering/asset-pipeline.md)
and [audio plan](../../../docs/audio/ndsp-mixer.md).

## Sector and tech-tree baseline

To read the pinned campaign structure through real upstream classes:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamProgressionBaseline.java
```

It initializes `ContentLoader.createBaseContent()` (which loads
`SerpuloTechTree`, `ErekirTechTree` and `SectorPresets`) and emits:

```text
UPSTREAM_TECH type=... id=... name=... depth=... parent=... root=... requirements=item:amount,...
UPSTREAM_SECTOR id=... name=... planet=... sector=... capture=... difficulty=... last=...
UPSTREAM_PROGRESSION tech_nodes=375 tech_roots=2 sectors=95 manifest_sha256=4a61e8b260eb4f698ed128eb6654e2fa61f295f64d29b44c7638c7b8dda2bd4a
```

The manifest is SHA-256 over sorted records. Upstream may print preset warnings
to stderr (for example presets without an assigned sector); those are not part
of the manifest. This is reference metadata only: no progression rules, UI,
save format or game state are implemented here.

## Unit and bullet metadata baseline

To read the pinned combat catalog through upstream classes:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamCombatBaseline.java
```

It emits `UPSTREAM_UNIT` records (ID, name, health, armor, speed, hit size,
flying, range), `UPSTREAM_BULLET` records (ID, type, damage, speed, lifetime,
hit size, pierce, splash damage, collision), and:

```text
UPSTREAM_COMBAT units=69 bullets=202 manifest_sha256=e35013e20c50cbe83169794e898e281898c717f1f373e9b84796090470a76882
```

Reference only: no units, bullets, balance, AI or combat loop are implemented
or changed. The manifest is deterministic over the pinned content order.

## Multiplayer session baseline

To exercise an end-to-end loopback session (`#44`) against the unmodified pinned
v160.8 `server-release.jar` (UDP discovery, build `159` `clientOutdated` kick,
two full connect -> `WorldStream` -> `NetworkIO.loadWorld` -> `ConnectConfirm` ->
live LZ4 `StateSnapshot` -> disconnect/rejoin sessions, and C11 `mindustry_net`
LZ4 decompression cross-verification):

```sh
python3 tools/verify_upstream_net.py --server-jar /path/to/server-release.jar
```

Look for `UPSTREAM_NET_PASS sessions=2 mismatch=clientOutdated` and
`DECODE_PKT_OK ... compression=1`. Live ARM11 TeaVM sockets and physical 3DS
Wi-Fi multiplayer remain tracked on `#44`.

## Campaign and tech-tree persistence baseline

To exercise pinned v160.8 `Core.settings` (`settings.bin`), `TechTree.TechNode`,
`UnlockableContent`, `Sector`/`SectorInfo`, `Objectives` prerequisites,
`sector-<planet.name>-<sector.id>.msav` slot naming/resolution (`Saves.getSectorFile(sector)`
and `SaveSlot` attachment — world `.msav` read/write round-trips are covered by
`UpstreamWorldRoundTripProbe.java`, and malformed/interrupted save handling by
`UpstreamSaveResilienceProbe.java`), save and cold-reload round-trips, `settings_backup.bin`
backup recovery, and malformed settings resilience:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamCampaignPersistenceBaseline.java
```

Look for `UPSTREAM_CAMPAIGN_PASS tech_nodes=375 sectors=95`. See
[the campaign progression persistence design](../../../docs/porting/campaign-progression.md)
for upstream key schemas and remaining ARM11 runtime work (`#41`).

## Production, power, and liquid simulation baseline

To exercise pinned `v160.8` production blocks (`GenericCrafter`, `Pump`,
`ConsumeGenerator`, `Battery`, `PowerNode`, `Conduit`, `LiquidRouter`,
`LiquidTank`), starvation, power-graph disconnect/reconnect, capacity clamping,
and repeated-run update-ordering determinism against `server-release.jar`:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamProductionBaseline.java
```

Look for `UPSTREAM_PRODUCTION_CATALOG blocks=12` and
`UPSTREAM_PRODUCTION_ORDER runs=2 ticks=2693`. See
[the production, power, and liquid design](../../../docs/porting/production-power-liquids.md)
for pinned scenario details and remaining ARM11 work (`#39`).

## Factory and logistics simulation baseline

To exercise pinned `v160.8` distribution and drilling blocks (`Conveyor`,
`Junction`, `Router`, `Sorter`, `OverflowGate`, `BufferedItemBridge`, `Drill`,
`CoreBlock`), orthogonal junction crossings, sorter filtering, overflow
saturation, mechanical-drill delivery timing, and repeated-run determinism
against `server-release.jar`:

```sh
java -cp /path/to/server-release.jar \
    /path/to/Min3DStry/research/upstream-runtime/host/UpstreamFactoryBaseline.java
```

Look for `UPSTREAM_FACTORY_CATALOG blocks=16` and
`UPSTREAM_FACTORY_ORDER runs=2 ticks=720`. See
[the factory and logistics baseline design](../../../docs/porting/factory-logistics.md)
for pinned scenarios, TeaVM C `-O0`/`-O2` parity, and remaining ARM11 memory
work (`#26`).
