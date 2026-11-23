# Min3DStry

Min3DStry is an experimental **1:1 port of upstream Mindustry (v8 build 160.8)** for Original and New Nintendo 3DS/2DS — **not a simplified clone or demake**. The target is the complete upstream game and its original content, maps, saves, and multiplayer against the unmodified Java PC release. The C components currently in the repository are 3DS platform/hardware test harnesses, while upstream Mindustry source bring-up on ARM11 lives in [research/upstream-runtime/](research/upstream-runtime/README.md). See the [upstream port plan](docs/upstream-port.md) for 1:1 compatibility and verification requirements.

![Min3DStry Port Completion Gauge](docs/progress-gauge.svg)

*Overall port completion is computed directly from [docs/port-capabilities.json](docs/port-capabilities.json) (`planned=0%`, `experimental=15%`, `host-tested=30%`, `emulator-tested=55%`, `hardware-verified=85%`, `complete=100%`) and checked in CI via `python3 tools/port_progress.py --check`.*

![Min3DStry Mechanics & Feature Parity Gauge](docs/mechanics-gauge.svg)

*Granular Mindustry v160.8 gameplay parity is computed from [docs/mechanics-parity.json](docs/mechanics-parity.json) (`missing=0%`, `harness-only=15%`, `probe-arm11=40%`, `partial-arm11=60%`, `live-arm11=85%`, `complete=100%`) and checked in CI via `python3 tools/mechanics_parity.py --check`.*

Run `./dev.sh doctor` for environment diagnostics, `./dev.sh test` for host unit and bootstrap checks, `./dev.sh build` to build `build/min3dstry.3dsx` (via local devkitARM or Docker `devkitpro/devkitarm:latest`), `./dev.sh emu-test` for automated Azahar verification across both New 3DS and Original 3DS modes, and `./dev.sh device` for `3dslink` / SD deployment and TCP log capture. See [Contributing](CONTRIBUTING.md), the [architecture notes](docs/architecture.md) and the [Java-to-native feasibility study](docs/research/java-to-native.md) before taking a task.

- [Project dashboard](https://levkeveli.github.io/Min3DStry/)
- [Roadmap and tasks](https://github.com/levkeveli/Min3DStry/issues)
- [Offline campaign and Custom Game](docs/gameplay/offline-singleplayer.md) (Emulator-tested on Old & New 3DS in Azahar `2126.2`; physical hardware pending `#43`)
- [Stock map editor and .msav export](docs/ui/stock-map-editor.md) (Emulator-tested on Original and New 3DS profiles)
- [Embedded TeaVM save loader](docs/porting/embed-loader.md) (Default `min3dstry.3dsx` startup path linked with `libm3dsloader.a`)
- [Unified offline and multiplayer build](research/upstream-runtime/README.md) (stock v160.8 server joins and Azahar soundtrack decoding and routing)
- [Hardware deployment & benchmark-report](docs/hardware/deployment-and-logs.md) (Wi-Fi deployment and log capture verified on Original and New 3DS XL. [Original 3DS XL evidence](docs/hardware/evidence/old3ds-xl-a4a9a96.md))
- [Citro2D platform test harness](docs/rendering/citro2d-prototype.md) (Host-tested, `.3dsx` built, and Azahar emulator-tested; physical hardware pending)

## Current development status

The gauges above use the checked-in capability and gameplay ledgers. The gameplay ledger now tracks fresh Serpulo and Erekir campaign starts, the combined offline/server client, and the stock effects, ambience, and soundtrack in its Join path. Azahar replay covers those paths. The live audio replay used the missing-DSP fallback, so physical listening and the sustained 24 FPS hardware target remain open. See the [hardware test queue](docs/hardware-test-queue.md) for the remaining physical checks.

The **Mechanics & Feature Parity Gauge** weights each v160.8 feature by its current verification stage. The [mechanics ledger](docs/mechanics-parity.json) records the evidence and open work for each feature.

The [bootstrap backlog](https://github.com/levkeveli/Min3DStry/issues?q=is%3Aissue+is%3Aopen+label%3Astatus%3Abacklog) covers port feasibility, hardware detection, rendering, assets, host testing, device deployment and gameplay parity. Agents work autonomously by claiming `status:ready` issues directly (commenting with branch/agent metadata, swapping `status:ready` to `status:in-progress`), running `./dev.sh test`, `./dev.sh build`, and `./dev.sh emu-test`, and opening same-repo PRs with `Closes #N` for CI + `AI review` auto-merge.
