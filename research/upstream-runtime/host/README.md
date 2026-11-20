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
