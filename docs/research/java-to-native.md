# Java-to-native feasibility

This study records the earlier native prototype. For current implementation direction, use the [upstream port plan](../upstream-port.md), including the pinned source and Java PC compatibility target.

Status: **research complete, implementation not started**. This document answers
[issue #1](https://github.com/levkeveli/Min3DStry/issues/1) and proposes the
portability strategy the project should follow.

Evidence in this change:

| Claim | Evidence label |
|---|---|
| Host simulation builds and is deterministic at `-O0`/`-O2` | `host-tested` |
| Host throughput of the POC kernel | `host-tested` |
| ARM11 (ARMv6K/VFPv2) cross-compile of the POC kernel | cross-compiled only, never executed |
| 3DS emulator or hardware behaviour | `not-tested` |
| Memory and cycle budgets | analytical model from documented hardware, not measured |

Nothing in this document should be read as a claim that the game boots, renders
or runs on a 3DS.

## Summary and recommendation

**Recommended approach: a native C/C++ reimplementation of the game and of the
small part of Arc it needs, built with devkitPro, libctru and Citro2D/Citro3D,
with upstream Mindustry kept as the behavioural reference and content source.**

Do not attempt to run the upstream Java build on the console. There is no Java
17 runtime for ARMv6K, none of the existing small JVMs target libctru, and Arc
requires OpenGL ES 3.0 while the PICA200 is an OpenGL ES 1.1-class GPU. Even if a
JVM were ported, it would have to reimplement Arc's graphics, audio, input and
network backends anyway, so the JVM buys no compatibility that a native port
does not already need.

Concretely:

- The simulation and content rules are portable; the engine backends are not.
- A lean native world state fits comfortably in the 64 MiB Old 3DS application
  region at 200x200 and still fits at 500x500 in the model (see
  [Runtime requirements](#runtime-requirements)). Memory is not the primary
  blocker; CPU budget and rendering are.
- Determinism must be treated as a testable property, not an assumption: the
  port must hash simulation traces and compare them with upstream where the
  rules overlap. Fixed-point maths is **not** drop-in compatible with upstream
  float behaviour.
- GPLv3 applies to the combined work because Mindustry is GPLv3; Arc is
  Apache-2.0 and libctru/Citro3D are zlib-licensed, which are compatible.

## Scope and method

The evaluation looked at:

1. the actual upstream build (Mindustry `build.gradle`, `SaveIO`, the Arc
   framework and its documented renderer requirements);
2. documented 3DS hardware and kernel memory layout (3dbrew) and the libctru API
   that exposes it;
3. every credible way to get Java bytecode or Java source onto an ARM11:
   shipping a JVM, ahead-of-time compilation, source/bytecode transpilers, and
   selective reimplementation;
4. a runnable host POC of the hot loop plus a real ARMv6K cross-compile.

The POC is deliberately small. It proves that the intended data layout and
integer-only simulation core build and produce identical traces on x86_64 at
different optimisation levels, and that the same file compiles for the 3DS
instruction set. It does **not** prove rendering, audio, memory allocator
behaviour or frame rate on hardware.

## Target hardware and OS constraints

| Property | Original 3DS/2DS | New 3DS/2DS | Source |
|---|---|---|---|
| CPU | 2x ARM11 MPCore, 268,111,856 Hz, VFPv2, no NEON | 4x ARM11 MPCore, same base clock, up to 804,335,568 Hz (3x mode), optional 2 MiB L2 | [3dbrew Hardware](https://www.3dbrew.org/wiki/Hardware), [libctru `os.h`](https://github.com/devkitPro/libctru/blob/master/libctru/include/3ds/os.h) |
| Application memory region | 64 MiB (APPMEMTYPE 0, default) | 124 MiB default (type 6), 178 MiB with New3DS Dev1 mode (type 7) | [3dbrew Memory layout](https://www.3dbrew.org/wiki/Memory_layout), [3dbrew exheader](https://www.3dbrew.org/wiki/NCCH/Extended_Header) |
| GPU | DMP PICA200, 268 MHz, OpenGL ES 1.1-class, 6 MiB VRAM | same | [3dbrew Hardware](https://www.3dbrew.org/wiki/Hardware) |
| Displays | 400x240 per eye top (800x240 panel), 320x240 bottom with resistive touch | same | 3dbrew |
| Audio | CEVA TeakLite DSP, 134 MHz, 24 channels, 32728 Hz | same | 3dbrew |
| Storage | SD card, FAT32, 32 GB recommended | same | 3dbrew |

Consequences that shaped the recommendation:

- **No NEON and no ARM integer divide.** Integer division and modulo compile to
  helper routines. Hot loops should prefer power-of-two masks and shifts.
- **Clock and core access are configuration-dependent.** 804 MHz, the extra L2
  cache and access to a third core need New3DS exheader flags (or a
  loader/CFW configuration that provides them). Code must detect what it
  actually got with `osGetApplicationMemType()` and `osGetMemRegionFree()`, and
  may request the speedup with `osSetSpeedupEnable()`, but must never assume a
  New 3DS.
- **VRAM is only 6 MiB and separate from the application region.** A 2048x2048
  RGBA8 atlas alone would be 16 MiB, so the asset pipeline must pack, compress
  (ETC1) or stream sprites instead of copying desktop atlases.
- **The application region includes code, heap, stacks and command buffers.**
  `osGetMemRegionSize(MEMREGION_APPLICATION)` is the real budget on the console,
  not the 128/256 MiB FCRAM headline.

## What upstream Mindustry and Arc require

From the upstream build files and framework documentation:

- Mindustry compiles with **Java 17** (`--release 17`) and Kotlin 2.3.
- Runtime dependencies include **Arc** (`arc-core`, flabel, freetype, g3d, fx,
  arcnet), **Rhino** (mod scripting), and **lz4-java**.
- Arc is an Apache-2.0, libGDX-derived framework. Its README states **OpenGL
  ES 3.0 as a minimum target**; its audio engine is **SoLoud** through JNI, its
  desktop windowing is SDL, and its core uses `sun.misc.Unsafe` and JNI natives.
- Mindustry saves are `MSAV` + version integer + deflate + a versioned binary
  reader/writer chain (`SaveIO`, versions 1-13). Multiplayer is
  **server-authoritative**: the server owns the world, streams world/asset data
  and sends compressed entity snapshots to clients; clients send actions and
  render.

That combination is the crux: a JVM would satisfy Java semantics but still leave
every platform backend unimplemented, and the GPU cannot run Arc's GLES 3.0
renderer.

## Candidate approaches

| # | Approach | Verdict | Why |
|---|---|---|---|
| 1 | Ship an existing JVM (JamVM, Avian, OpenJDK port) and run the upstream jar | **Reject** | No Java 17 class library or ARMv6K/libctru port exists. JamVM and Avian are Java 8-era, unmaintained for this target, and have no 3DS backend. The 64 MiB Old 3DS region would hold the JVM, class library, JIT/interpreter, GC and heap. Arc still needs a new GLES backend. |
| 2 | GraalVM Native Image / ahead-of-time Java | **Reject** | Native Image targets x86_64 and AArch64, not 32-bit ARMv6K bare metal, and brings a large runtime and GC. |
| 3 | RoboVM | **Reject** | Target was iOS/ARM64 and the project is discontinued; no libctru target. |
| 4 | Java-to-C/JS transpilers (TeaVM, j2objc, j2c and similar) | **Reject** | Support a subset of Java (not Java 17), depend on reflection/native libraries, and output JS/WASM/Objective-C with no 3DS runtime. |
| 5 | Port Arc/libGDX to the 3DS and keep Java on top | **Reject** | Requires the JVM from #1 plus a GLES 3.0-class renderer on an ES 1.1-class GPU. Two huge projects for less control than a native port. |
| 6 | **Native C/C++ reimplementation with Citro2D/Citro3D** | **Recommend** | One source tree for both generations, direct control of memory and frame time, no GC, can reuse upstream content formats and rules, and can be validated against upstream with trace hashes. |
| 7 | Thin 3DS client talking to an upstream/PC server | **Fallback / test tool** | Still needs the renderer, input, UI and network stack on the 3DS, and gives no single-player or offline play. Useful later as a compatibility harness, not as the port strategy. |
| 8 | Hybrid: native port + upstream headless server for parity tests | **Recommend as tooling** | Lets CI compare port traces with real upstream simulation on the same inputs without changing gameplay rules. |

The project mission already requires a native full port, so this is a choice of
*how*, not *whether*: reimplement the engine backends while keeping game rules
and data formats as close to upstream as the hardware allows.

### Recommended shape of the port

- **Language:** C++17 (or C11) with a small platform layer. C++ is preferable for
  RAII containers and templates once the data model is fixed; the POC uses C99
  to keep the proof minimal and portable.
- **Platform interfaces:** renderer, audio, input, files, clock, network and
  system info behind headers, with a host backend for tests and a libctru
  backend for both console generations.
- **World storage:** structure-of-arrays / flat records with indices instead of
  pointers, so a world snapshot can be hashed, saved and sent deterministically.
- **Renderer:** Citro2D sprite batching for the world and UI, Citro3D for
  effects; texture atlases packed offline and compressed to fit 6 MiB VRAM.
- **Audio:** NDSP/CSND with decoded OGG/ADPCM assets; do not port SoLoud.
- **Content:** offline converter from upstream sprites, bundles, maps and save
  files into 3DS-friendly formats; keep the upstream JSON/binary formats as the
  interchange and compatibility surface.
- **Reference oracle:** headless upstream Mindustry on a PC in CI to produce
  golden traces for shared simulation rules.

## Proof of concept

Location: `research/java-to-native/`.

Files:

| File | Purpose |
|---|---|
| `sim_kernel.c` | Integer-only simulation kernel: belts, routers, turrets, enemies, 12-byte cell records, trace hashing. Also compiles freestanding for ARM. |
| `budget_model.py` | Transparent memory and cycle model; prints the tables used below. |
| `run_poc.py` | Builds `-O0`/`-O2`, compares traces, measures host throughput, cross-compiles for ARMv6K and validates the ELF, prints the budget summary. |

Reproduce:

```sh
./dev.sh test          # CI path; runs the POC and skips the C part if no compiler
./dev.sh poc           # requires a host C compiler; fails instead of skipping
python3 research/java-to-native/run_poc.py --require-compiler
python3 research/java-to-native/budget_model.py
```

### What was actually run

Local host run on Windows x86_64, GCC 16.2.0 (w64devkit), ARM cross-compiler
GNU Arm Embedded 15.2.1 (xPack):

```text
host=Windows AMD64
compiler_version=cc (GCC) 16.2.0
host_build=pass (-O0 and -O2)
determinism=pass: identical trace hashes and counters at -O0/-O2 and repeated runs
throughput_200x200=host-tested: 23622 ticks/s (127.0 ms for 3000 ticks, hash disabled)
throughput_500x500=host-tested: 3888 ticks/s (1003.0 ms for 3900 ticks, hash disabled)
arm11_cross_compile=pass: ARMv6K/VFPv2 object built (ELF32 little-endian ARM, EABI version 5), 4664 bytes, sha256 6385be23fe66664f... | size: 3508 0 3146688
arm11_compiler_version=arm-none-eabi-gcc.EXE (xPack GNU Arm Embedded GCC x86_64) 15.2.1 20251203
POC PASS
```

The ARM command was:

```sh
arm-none-eabi-gcc -std=c99 -Wall -Wextra -Werror -O2 \
  -march=armv6k -mfpu=vfp -mfloat-abi=hard -DSIM_FREESTANDING \
  -c sim_kernel.c -o sim_kernel_armv6k.o
```

The object is `ELF32`, little-endian, `e_machine=ARM (40)`, EABI version 5,
3,508 bytes of text and no data; the 3,146,688-byte BSS is the POC's static
512x512 cell array (3,145,728 bytes) plus the fixed enemy array (960 bytes). A
shipping port would allocate the world per map instead of statically.

The 96x96, 2,000-tick, seed-7 run produces `trace_hash=0x0b59f745` with
`items_delivered=840` on both builds, which is a convenient regression value.
Host throughput varies between runs; the numbers above are one recorded run.

### What the POC proves and does not prove

Proves:

- the intended cell layout is 12 bytes and the simulation core is
  integer-only, builds cleanly with `-Wall -Wextra -Werror` on host and ARM;
- `-O0` and `-O2` produce identical trace hashes and counters, i.e. the kernel
  has no optimisation-dependent or undefined behaviour in the exercised paths;
- the code compiles for ARMv6K with the VFPv2 ABI, which is the 3DS CPU/FPU
  configuration.

Does **not** prove:

- frame rate or memory use on a 3DS (the host numbers are not scaled; the
  microarchitectures differ);
- rendering, audio, input, filesystem or network behaviour;
- that upstream Mindustry rules are bit-identical; the kernel is a toy model,
  not a ported subsystem;
- anything about emulator or hardware boot.

## Runtime requirements

### Memory (model)

Assumptions and arithmetic are in `research/java-to-native/budget_model.py`.
The model covers native code/data, UI, audio, network, save buffers, stacks,
Citro3D command buffers, world state for a full map, buildings at 25% density,
200 units, effects and a client snapshot buffer. It excludes VRAM (separate) and
does not model fragmentation beyond a 5% allowance.

Generated table (values are model output, not measurements):

| Map | World state | Baseline | +5% allocator | Old 3DS 64 MiB | New 3DS 124 MiB | New 3DS 178 MiB |
|---|---|---|---|---|---|---|
| 96x96 | 2.6 MiB | 20.0 MiB | 23.7 MiB | 37.0% (comfortable) | 19.1% | 13.3% |
| 200x200 | 4.4 MiB | 20.0 MiB | 25.6 MiB | 40.0% (comfortable) | 20.6% | 14.4% |
| 500x500 | 16.6 MiB | 20.0 MiB | 38.4 MiB | 60.0% (comfortable) | 31.0% | 21.6% |

Interpretation:

- A native port has real headroom on both generations, including full-size
  maps. That is an argument **for** a lean native design and **against**
  spending the Old 3DS budget on a JVM, class library and GC.
- The 20 MiB baseline is an estimate dominated by code size, UI/content tables
  and save buffers; it must be replaced by measured `osGetMemRegionFree()`
  values once a build exists.
- VRAM is the tighter resource: 6 MiB must hold both screens' framebuffers and
  all textures, so atlas packing and ETC1/compressed formats are mandatory.

### CPU (model)

3DS application cores are not exclusively available to the game: system
threads, rendering, audio and the OS consume part of the same clock. The table
is therefore an upper bound, computed from the documented clock:

| Clock | Sim rate | Cycles/tick | Per tile @10k | @40k | @100k |
|---|---|---|---|---|---|
| Old 3DS 268 MHz | 60 Hz | 4,468,531 | 446.9 | 111.7 | 44.7 |
| Old 3DS 268 MHz | 30 Hz | 8,937,062 | 893.7 | 223.4 | 89.4 |
| New 3DS 804 MHz | 60 Hz | 13,405,593 | 1,340.6 | 335.1 | 134.1 |
| New 3DS 804 MHz | 30 Hz | 26,811,186 | 2,681.1 | 670.3 | 268.1 |

At 40k tiles (200x200) the Old 3DS gives roughly **112 cycles per tile per
tick** at 60 Hz. That is enough for simple block updates but not for a
desktop-equivalent simulation with thousands of active entities, projectiles,
pathfinding and effects. Performance work must start early:

- fixed-rate simulation with rendering interpolation (30 Hz simulation is an
  option, but it changes feel and must be reviewed);
- spatial partitioning for turrets/units instead of full scans;
- dirty-block updates and sleeping for idle blocks;
- reduced default map size and unit caps on Old 3DS, with identical rules on
  both consoles;
- worker threads only where the second core is actually available and
  synchronisation cost is lower than the work moved.

### Other runtime requirements

- **GPU:** 60 Hz on a 400x240 top screen with Citro2D batching; avoid full-screen
  alpha effects, large textures and per-frame atlas uploads. No hardware
  instancing; keep draw calls low.
- **Audio:** 24 DSP channels at 32,728 Hz are sufficient for effects and one
  music stream, but decoding OGG in real time on the appcore competes with the
  simulation. Prefer streaming ADPCM/PCM or decode on a worker if available.
- **Storage:** maps, saves and assets from `sdmc:/`; save atomically (write
  temp + rename) because SD writes can fail. Budget at least tens of MiB for
  assets; compressed music should be optional.
- **Startup:** avoid loading every asset at boot; lazy-load per campaign sector.
  Upstream's desktop asset set is far larger than a 3DS session needs.
- **Input/UI:** touch on the bottom screen, circle pad/buttons; the two-screen
  layout is a new UI design, not a direct port of the desktop UI.

## Determinism and networking

Mindustry's gameplay is float-based. On ARM11, VFPv2 implements IEEE-754
single/double precision but has no fused multiply-add and no NEON; libm
transcendentals (`sin`, `cos`, `pow`, `atan2`) are not bit-identical across
implementations. Therefore:

- Do not compile the simulation with `-ffast-math`, `-Ofast` or FP contraction
  enabled. Use IEEE semantics and avoid x87 on host builds.
- Any ported rule must be validated against upstream with trace hashes over
  fixed inputs, not by inspection. The POC demonstrates the harness shape:
  deterministic state serialisation plus a 32-bit FNV-style multiply hash per
  tick.
- Fixed-point is safe for new systems where the port owns the rules, but it is
  **not** a drop-in replacement for upstream float behaviour. Changing
  simulation rules or the network protocol is a Tier C decision under
  `docs/development/automation-policy.md`, not something a port can do silently.
- Some upstream behaviour depends on delta time and frame order. A fixed-step
  port must document and test the difference rather than claim parity.

Networking:

- Mindustry multiplayer is server-authoritative. Clients do not need to
  reproduce the server's simulation bit-for-bit to play; they send actions and
  render snapshots. That makes cross-play with PC **plausible** once the ArcNet
  protocol and world stream are ported, even if client-side prediction differs.
- Saves are versioned (`MSAV`, versions 1-13). Start by reading/writing the
  current version and rejecting unknown versions cleanly; older readers are
  follow-up work.
- Multiplayer requires porting the ArcNet transport and protocol (handshake and
  version check, world/asset streams, entity snapshots) plus matching content
  IDs. This is a large subsystem and should be a separate milestone, not part of
  the first playable build.
- The POC does not test networking. No multiplayer or upstream-parity claim is
  made here.

## Licensing and dependency compatibility

Not legal advice; a maintainer/licensing review is required before any release.

| Component | License | Consequence for Min3DStry |
|---|---|---|
| Mindustry code and repository assets | GPLv3 (`LICENSE`) | The port is a derivative work: distribute under GPLv3, provide complete corresponding source, keep notices. Do not add restrictions. |
| Arc | Apache-2.0 | Compatible with GPLv3. If code is copied, keep copyright/NOTICE and state changes. Reimplementation is not restricted. |
| libGDX (Arc's origin) | Apache-2.0 | Same as Arc. |
| Rhino | MPL-2.0 (majority; some files differ) | File-level copyleft, compatible with a GPLv3 work if MPL files keep their notices and source obligations. Audit the exceptions before importing. |
| lz4-java | Apache-2.0 | Compatible; attribution/NOTICE if copied. |
| Kotlin / Gradle build tooling | Apache-2.0 | Build-time only. |
| JLine (upstream server console) | BSD-3-Clause | Not needed by the 3DS client. |
| SoLoud (Arc audio) | zlib/libpng plus liberal third-party code | Not ported; if any code is reused, keep notices. |
| libctru, Citro2D, Citro3D | zlib | Permissive; keep notices. |
| devkitARM (GCC/binutils/newlib) | GPLv3 with GCC runtime exception; newlib permissive | Toolchain only; compiled output is not forced to GPL by the toolchain. |
| Nintendo SDK, firmware, assets | proprietary | Must not be used or redistributed. The project stays homebrew-only. |

Practical rules:

- Importing upstream **sprites, sounds, music, bundles or maps** means auditing
  each asset's provenance, not just the repository LICENSE. If provenance is
  unclear, do not import it; recreate or omit it.
- The 3DS port must ship its own GPLv3 source offer and notices, and must not
  link proprietary libraries. libctru/Citro3D are fine; Nintendo's SDK is not.
- Third-party tools used only in CI (w64devkit, xPack GCC) are not part of the
  distributed work.

## Risks and open questions

1. **CPU on Old 3DS is the main risk.** Full-size maps at 60 Hz may be
   impossible without gameplay-affecting compromises. A hardware benchmark of a
   representative block/entity workload is the next gate.
2. **Rendering architecture.** Citro2D batching is a good fit for 2D sprites,
   but effects, lighting and large atlases need prototypes; PICA200 shader
   limits and 6 MiB VRAM constrain the art direction.
3. **Scope of the reimplementation.** Mindustry is large: blocks, units, logic,
   campaign, mods, UI, net. A first playable slice must be defined explicitly
   (one map, a subset of blocks, no campaign/mod support) before estimating.
4. **Asset licensing provenance** needs a per-asset audit.
5. **New 3DS enhancements** (804 MHz, 178 MiB, extra core/L2) depend on
   exheader/loader configuration; they cannot be assumed and must be detected.
6. **Save/network compatibility** may never reach 100%; the project must publish
   exactly which upstream versions and features are supported.
7. **Upstream drift.** Mindustry is actively developed; pin a reference commit
   and re-run parity tests when updating.

## Follow-ups

- Hardware probe issue: build a minimal libctru app that prints
  `osGetApplicationMemType()`, `osGetMemRegionSize/FREE(MEMREGION_APPLICATION)`,
  CPU speed and core availability on New 3DS. Record measurements from
  physical New 3DS/2DS as `new3ds-hardware-tested`.
- Renderer spike: Citro2D sprite batch with a real upstream sprite atlas packed
  under 6 MiB VRAM, measured draw calls and frame time.
- Simulation parity harness: headless upstream server + port kernel on the same
  scripted scenario, compared by trace hash.
- Save IO prototype: read current `MSAV` version, then map/building round-trip.
- Network prototype: ArcNet handshake and world stream against a local server.
- Asset license audit and content converter.
- Define the first playable milestone and its acceptance tests.

## Sources

- Mindustry repository and build: <https://github.com/Anuken/Mindustry>
  (`build.gradle`, `LICENSE`, `core/src/mindustry/io/SaveIO.java`)
- Arc framework: <https://github.com/Anuken/Arc> (`README.md`, `LICENSE`)
- 3dbrew: [Hardware](https://www.3dbrew.org/wiki/Hardware),
  [Memory layout](https://www.3dbrew.org/wiki/Memory_layout),
  [NCCH extended header](https://www.3dbrew.org/wiki/NCCH/Extended_Header)
- libctru: <https://github.com/devkitPro/libctru>
  (`libctru/include/3ds/os.h`, `svc.h`)
- Citro2D/Citro3D: <https://github.com/devkitPro/citro2d>,
  <https://github.com/devkitPro/citro3d>
- Rhino: <https://github.com/mozilla/rhino>
- lz4-java: <https://github.com/lz4/lz4-java>
- POC sources and generated model: `research/java-to-native/`
