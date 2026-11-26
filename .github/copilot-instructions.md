# Copilot Instructions — Min3DStry

- **1:1 Upstream Port Mandate:** Min3DStry is a **1:1 port of upstream Mindustry (v8 build `160.8`, commit `19a0f40d44514f4802a8b79770456e7e32e34da0`, Arc `7445105cd2`)** to New Nintendo 3DS/2DS — **NOT a simplified clone, demake, or custom mini-game**.
- Use New Nintendo 3DS/2DS for development, emulator checks, hardware validation, and release. Preserve Original-model evidence as history.
- Read `AGENTS.md`, `docs/upstream-port.md`, and `docs/architecture.md` before making changes.
- Preserve 1:1 upstream gameplay rules, full content catalog, original assets, `.msav` saves/maps, and multiplayer interoperability with unmodified Java PC v160.8.
- Treat `src/render/scene_2d.c`, `src/render/factory_demo.c`, and `src/sim/parity_scenario.c` as **3DS platform/hardware verification harnesses only**; do not build a bespoke partial Mindustry clone on top of them.
- Upstream Mindustry/Arc code and 3DS platform adapters (Citro2D/Citro3D, NDSP audio, dual-screen layout/input mapping, `libctru` OS/FS/network/threading/memory) may be modified where necessary for 3DS compatibility and performance, provided technical parity with original Mindustry v160.8 (gameplay rules, content IDs, `.msav` maps/saves, and cross-platform Java PC multiplayer) is preserved.
