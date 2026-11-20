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
adaptation used by content mode for Arc's classpath lookup. Native registry and
factory parity are tracked in #55.
