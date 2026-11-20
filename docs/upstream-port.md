# Upstream Mindustry port plan

The release target is the complete upstream Mindustry game on Original and New Nintendo 3DS/2DS. Preserve upstream gameplay rules, progression, items, blocks, units, sprites, audio, maps and levels, save semantics, and sessions with the matching, unmodified Java PC game. Adapt upstream code and content with file-level provenance and applicable license notices. The 3DS presentation and controls can differ where the hardware requires it while preserving gameplay behavior.

## Pinned reference

- Mindustry v8 build 160.8 at commit [`19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0). The release includes the desktop and headless server JARs. [Official release](https://github.com/Anuken/Mindustry/releases/tag/v160.8)
- The pinned source's `gradle.properties` records Arc dependency revision `7445105cd2`. Keep dependency versions, import steps, original paths, and applicable notices with ported code or assets. [Pinned source license](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/LICENSE)

## Implementation order

1. Establish the upstream desktop and headless server reference. Map the source boundaries for platform services, simulation, content loading, saving, and networking. Keep the existing 3DS prototype as a platform test harness.
2. Adapt the upstream source and content in verifiable slices. The compatibility inventory covers every upstream content family, campaign and map, and gameplay subsystem. Record upstream paths, test cases, and supported state for each slice. Preserve IDs and serialized behavior needed for maps, saves, and network state.
3. Verify multiplayer with at least one unmodified v160.8 Java PC client and one 3DS client in the same upstream-compatible session. Check join, initial state, ongoing gameplay and synchronization, disconnect/rejoin, and incompatible-version handling. Extend the test matrix to cover the supported multiplayer modes.
4. After the full functional and content baseline is in place, optimize for at least 24 FPS (a 41.67 ms frame budget) on both physical 3DS generations, within each console's actual application-memory budget. Record a representative map, camera and workload, sustained frame times, and memory usage on each target. Performance changes must pass the same gameplay, content, save, and multiplayer checks. Record feasibility measurements during porting so hardware assumptions can be checked early.

## First upstream reference check

The official v160.8 `server-release.jar` started locally under Java 17, reported `Mindustry 8-release official / build 160.8`, and exited cleanly after the `version` and `exit` commands. This establishes the upstream version for subsequent compatibility checks.

Run this from a scratch directory with the official server JAR and Java 17 on `PATH`.

```sh
printf 'version\nexit\n' | java -jar server-release.jar
```

The initial source boundaries are `desktop/src/mindustry/desktop/DesktopLauncher.java` for the desktop platform, `server/src/mindustry/server/ServerLauncher.java` for the headless reference, `core/src/mindustry/io/SaveIO.java` for save entry points, and `core/src/mindustry/core/NetClient.java`, `NetServer.java`, and `core/src/mindustry/net/ArcNetProvider.java` for multiplayer.

The [upstream source runtime probe](../research/upstream-runtime/README.md) tracks the first test of compiling original Mindustry source to native code. See [issue #49](https://github.com/levkeveli/Min3DStry/issues/49).
