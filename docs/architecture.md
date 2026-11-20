# Architecture

**Min3DStry is a 1:1 port of upstream Mindustry (v8 build `160.8`) to Original and New Nintendo 3DS/2DS — not a custom clone or demake.** Target ARM11 homebrew on Original and New 3DS/2DS, adapting real pinned upstream Mindustry/Arc source code and content while keeping 3DS services behind platform interfaces. Upstream code may be modified where needed for ARM11/TeaVM compatibility, 3DS memory/performance budgets, and 3DS platform adapters as long as the game stays technically original Mindustry v160.8 (including `.msav` compatibility and cross-platform Java PC multiplayer). The [upstream port plan](upstream-port.md) defines the reference release and 1:1 compatibility requirements.

## Upstream v160.8 ARM11 Bring-Up

- [Upstream source runtime & resource probes](../research/upstream-runtime/README.md): Pinned Mindustry v160.8 + Arc `7445105cd2` compiled with TeaVM C against `libctru` adapters (tested in Azahar for `LogicOp`, `MapIO` Glacier metadata, Arc `Fi` resources, and JVM content registry baseline).

## 3DS Platform & Hardware Test Harnesses (NOT the Game)

> **Note for agents:** The native C prototypes below validate 3DS GPU rendering, audio, hardware detection, host testing, and tooling. **Do NOT expand them into a bespoke simplified Mindustry clone**; full gameplay, content, and simulation come from upstream Mindustry v160.8.

- [Citro2D bounded rendering prototype](rendering/citro2d-prototype.md) (`M3dsRenderBackend` + `M3dsBoundedScene`, host-tested, 3DS-built via `./dev.sh build`, and emulator-tested in Azahar `2126.2` via `./dev.sh emu-test`; physical hardware verification pending).
- [Hardware detection profile](hardware/detection.md) (`M3dsHardwareProfile` + `libctru` probe, host-tested, 3DS-built, and emulator-tested across Old/New 3DS modes in Azahar `2126.2`; physical Old/New 3DS hardware verification pending).
- [Memory budget](hardware/memory-budget.md) (`M3dsMemoryBudget` bounded arena derived from the detected application-memory region, host-tested and Azahar-tested; the upstream TeaVM heap budget remains separate).
- [Gameplay parity harness](testing/parity-harness.md) (`M3dsParityState` + canonical scenario `starter_copper_defense_v1`, matching the fixture on host `-O0`/`-O2`, Azahar Old/New 3DS ARM11, and a physical New 3DS XL).
- [Hardware deployment & log streaming](hardware/deployment-and-logs.md) (`./dev.sh device` / `tools/device_deploy.py`, host-tested for SD staging and log tooling, hardware-tested for Wi-Fi deployment and log streaming on a New 3DS XL).
- [NDSP audio mixer prototype](audio/ndsp-mixer.md) (`M3dsAudioMixer` + `libctru` NDSP backend, host-tested via mock backend, 3DS-built via `./dev.sh build`, and missing-`dspfirm.cdc` fallback verified in Azahar `2126.2`; physical hardware playback with DSP firmware pending). [Upstream audio asset conversion](audio/asset-conversion.md) provides a pinned PCM16/provenance slice and a bounded [audio registry](../include/audio/audio_registry.h) that loads the generated RomFS index into the mixer; `make UPSTREAM_MINDUSTRY=...` packs the clips and the app logs `AUDIO_ROMFS status=ok clips=9 bytes=647008` in Azahar. [Audio events](../include/audio/audio_events.h) map nine logical audio IDs to mixer playback; UI click, a deterministic demo wave, Duo fire, enemy death, block place/break and looping conveyor/drill ambients are wired and Azahar asserts resolved clips, while research unlock remains pending.
- [Upstream v160.8 network framing & handshake](net/upstream-protocol.md) (`mindustry_net` Issue #31 wire-exact framing/handshake shim & test oracle paired with the reusable `libctru` `socInit` socket adapter `net_3ds.c`, host unit-tested, live-verified against unmodified `server-release.jar` v160.8, and 3DS-built via `./dev.sh build`; full `#44` TeaVM `NetClient` wiring and on-console Wi-Fi session pending).

Track toolchain builds, emulator boot, physical hardware boot, gameplay parity and upstream multiplayer independently. Record hardware model, build SHA and test scenario alongside measurements. Audit upstream and dependency licenses before importing code or assets.

## Portability research

The Java-to-native evaluation (issue #1) compares JVM hosting, ahead-of-time Java, transpilers and native reimplementation against the documented 3DS limits, and includes a reproducible host/ARM11 proof of concept. See [Java-to-native feasibility](research/java-to-native.md) for the recommendation, memory and cycle budgets, determinism/networking analysis and license audit. Its evidence is host-tested and cross-compiled only; it makes no emulator or hardware claim.
