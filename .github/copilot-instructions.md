# Copilot Instructions — Min3DStry

- **1:1 Upstream Port Mandate:** Min3DStry is a **1:1 port of upstream Mindustry (v8 build `160.8`, commit `19a0f40d44514f4802a8b79770456e7e32e34da0`, Arc `7445105cd2`)** to Original and New Nintendo 3DS/2DS — **NOT a simplified clone, demake, or custom mini-game**.
- Read `AGENTS.md`, `docs/upstream-port.md`, and `docs/architecture.md` before making changes.
- Preserve 1:1 upstream gameplay rules, full content catalog, original assets, `.msav` saves/maps, and multiplayer interoperability with unmodified Java PC v160.8.
- Treat `src/render/scene_2d.c`, `src/render/factory_demo.c`, and `src/sim/parity_scenario.c` as **3DS platform/hardware verification harnesses only**; do not build a bespoke partial Mindustry clone on top of them.
- Only 3DS hardware/platform adapters (Citro2D/Citro3D, NDSP audio, dual-screen layout/input mapping, `libctru` OS/FS/network/threading/memory) should differ where the hardware requires it.
