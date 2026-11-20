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
stops at the executor, future, and processor-count APIs reached during static
initialization. Native registry and factory parity are tracked in #55.

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
