# Architecture

Target native ARM11 homebrew with devkitPro, libctru and Citro2D/Citro3D. Evaluate Java-to-native options before committing to a portability strategy. Keep graphics, audio, input and platform services behind interfaces so the same game simulation and networking rules can run on both 3DS generations and in host-side tests.

Current prototypes:
- [Citro2D bounded rendering prototype](rendering/citro2d-prototype.md) (`M3dsRenderBackend` + `M3dsBoundedScene`, host-tested, 3DS-built via `./dev.sh build`, and emulator-tested in Azahar `2126.2` via `./dev.sh emu-test`; physical hardware verification pending).
- [Hardware detection profile](hardware/detection.md) (`M3dsHardwareProfile` + `libctru` probe, host-tested, 3DS-built, and emulator-tested across Old/New 3DS modes in Azahar `2126.2`; physical Old/New 3DS hardware verification pending).
- [Gameplay parity harness](testing/parity-harness.md) (`M3dsParityState` + canonical scenario `starter_copper_defense_v1`, matching the fixture on host `-O0`/`-O2`, Azahar Old/New 3DS ARM11, and a physical New 3DS XL).
- [Hardware deployment & log streaming](hardware/deployment-and-logs.md) (`./dev.sh device` / `tools/device_deploy.py`, host-tested for SD staging and log tooling, hardware-tested for Wi-Fi deployment and log streaming on a New 3DS XL).

Track toolchain builds, emulator boot, physical hardware boot, gameplay parity and upstream multiplayer independently. Record hardware model, build SHA and test scenario alongside measurements. Audit upstream and dependency licenses before importing code or assets.

## Portability research

The Java-to-native evaluation (issue #1) compares JVM hosting, ahead-of-time Java, transpilers and native reimplementation against the documented 3DS limits, and includes a reproducible host/ARM11 proof of concept. See [Java-to-native feasibility](research/java-to-native.md) for the recommendation, memory and cycle budgets, determinism/networking analysis and license audit. Its evidence is host-tested and cross-compiled only; it makes no emulator or hardware claim.
