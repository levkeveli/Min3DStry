# CLAUDE.md — Min3DStry

**Min3DStry is a 1:1 port of upstream Mindustry (pinned v8 build `160.8`, commit `19a0f40d44514f4802a8b79770456e7e32e34da0`, Arc `7445105cd2`) to Original and New Nintendo 3DS/2DS — NOT a simplified clone, demake, fan recreation, or custom mini-game.**

Before planning or editing code, read:
- `AGENTS.md`
- `docs/upstream-port.md`
- `docs/architecture.md`

Key rules for every agent:
- Adapt and run real upstream Mindustry v160.8 code and original content (`research/upstream-runtime/README.md`) with 1:1 gameplay, full content catalog, `.msav` maps/saves, and Java PC v160.8 multiplayer compatibility.
- Existing C demo code (`src/render/scene_2d.c`, `src/render/factory_demo.c`, `src/sim/parity_scenario.c`) is a **3DS hardware/platform verification harness ONLY** — do not expand it into a bespoke from-scratch Mindustry reimplementation or partial block subset.
- You may modify upstream Mindustry/Arc code where necessary for 3DS compatibility and performance (e.g., TeaVM/ARM11 fixes, memory/frame-time optimizations) alongside 3DS platform services (Citro2D/Citro3D GPU rendering, dual-screen UI layout & 3DS controls, NDSP audio, `libctru` OS/FS/network/threading/memory adapters), provided the game stays technically original Mindustry v160.8 (identical gameplay, content IDs, `.msav` maps/saves, and cross-platform PC multiplayer).
