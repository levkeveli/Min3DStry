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
cancellation, and shutdown in each environment.

The generated Arc `OS.java` reports one execution lane for the cooperative
TeaVM C scheduler, avoiding the unsupported JVM processor-count call. The
fixed executor uses managed Java threads and drains its queue as work arrives.
Tasks can yield at TeaVM suspension points. Contended monitors in code that
TeaVM compiles as synchronous are still a runtime limitation. Native
parallel execution, dynamic pool variants, and the complete JDK concurrency
API are outside this probe.
