# Architecture

Target native ARM11 homebrew with devkitPro, libctru and Citro2D/Citro3D. Evaluate Java-to-native options before committing to a portability strategy. Keep graphics, audio, input and platform services behind interfaces so the same game simulation and networking rules can run on both 3DS generations and in host-side tests.

Current prototypes:
- [Citro2D bounded rendering prototype](rendering/citro2d-prototype.md) (`M3dsRenderBackend` + `M3dsBoundedScene`, host-tested and 3DS-built via `./dev.sh build`, emulator/hardware verification pending).

Track toolchain builds, emulator boot, physical hardware boot, gameplay parity and upstream multiplayer independently. Record hardware model, build SHA and test scenario alongside measurements. Audit upstream and dependency licenses before importing code or assets.

## Portability research

The Java-to-native evaluation (issue #1) compares JVM hosting, ahead-of-time Java, transpilers and native reimplementation against the documented 3DS limits, and includes a reproducible host/ARM11 proof of concept. See [Java-to-native feasibility](research/java-to-native.md) for the recommendation, memory and cycle budgets, determinism/networking analysis and license audit. Its evidence is host-tested and cross-compiled only; it makes no emulator or hardware claim.
