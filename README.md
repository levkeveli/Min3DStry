# Min3DStry

Experimental project to bring full Mindustry to Original and New Nintendo 3DS/2DS as native homebrew. This repository currently contains project infrastructure. Gameplay, hardware support, map and save compatibility, and multiplayer remain unverified.

Run `./dev.sh doctor` for environment diagnostics, `./dev.sh test` for host unit and bootstrap checks, and `./dev.sh build` to build `build/min3dstry.3dsx` (via local devkitARM or Docker `devkitpro/devkitarm:latest`). See [Contributing](CONTRIBUTING.md) and the [architecture notes](docs/architecture.md) before taking a task.

- [Project dashboard](https://levkeveli.github.io/Min3DStry/)
- [Roadmap and tasks](https://github.com/levkeveli/Min3DStry/issues)
- [Citro2D rendering prototype](docs/rendering/citro2d-prototype.md) (Experimental: host-tested & `.3dsx` build verified; emulator/hardware pending)

## Current development status

The [bootstrap backlog](https://github.com/levkeveli/Min3DStry/issues?q=is%3Aissue+is%3Aopen+label%3Astatus%3Abacklog) covers port feasibility, hardware detection, rendering, assets, host testing, device deployment and gameplay parity. Ask a maintainer to assign an issue before starting work while task leasing is pending.
