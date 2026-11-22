# Embedded TeaVM save loader

The main app can link the upstream `save-rows` producer as an optional in-process
loader. When present, startup tries the upstream loader before the
verification-only native parser.

- `include/platform/teavm_world_loader.h` declares
  `m3ds_teavm_world_loader_available()` and
  `m3ds_teavm_world_load(path, M3dsWorldTiles*)`.
- `src/platform/teavm_world_loader_stub.c` provides weak fallbacks so the app
  still builds without the generated runtime.
- `research/upstream-runtime/arm11/m3ds_save_rows.c` provides the strong
  implementations when embedded: it passes the save path through a native
  getter, runs `m3ds_teavm_main(0, NULL)`, and moves the allocated world into the
  caller.
- `arm11/prepare.py --embed` stages the generated C without the probe main or
  the probe's heap symbols.
- `tools/build_teavm_loader.sh` builds `libm3dsloader.a` from the generated C
  plus `m3ds_save_rows.c`.
- The Makefile links the loader when `TEAVM_LOADER_DIR` points at that library
  and defines `M3DS_TEAVM_LOADER_EMBEDDED`.

The save path must use the TeaVM/3DS form (`/3ds/min3dstry/...`), not
`sdmc:/3ds/...`.

## Evidence

`tests/platform/embed_loader_smoke.c` links against `libm3dsloader.a` and calls
the public loader API. Built with the pinned devkitARM toolchain and booted in
Azahar with the real `glacier-v13.msav`:

```text
New 3DS valid:
  UPSTREAM_ROWS flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680 building=9a691300c548b8fb buildings=2
  UPSTREAM_HEAP used=20534480 free=72496
  UPSTREAM_SAVE_ROWS status=0
  EMBED_LOADER missing=0 ok=1 verified=1 version=13 tiles=37500 buildings=2 flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680

Old 3DS valid:
  same rows/status/loaded values
  UPSTREAM_HEAP used=20535816 free=71160

New/Old 3DS missing save:
  UPSTREAM_SAVE_ROWS error=arc.util.ArcRuntimeException: Error reading file: /3ds/min3dstry/does-not-exist.msav (absolute)
  EMBED_LOADER missing=1 ok=0 verified=1
```

The smoke harness builds two variants: one loads the real fixture and asserts
the tile/building hashes and `version == 13`; the other loads a missing path and
asserts the loader returns false without aborting. The Java entry catches
load/extraction failures, reports them, and clears `Vars.world` after extracting
rows so the TeaVM world is not retained alongside the native copy. The save
version is propagated through `m3ds_save_rows_set_version`.

`UPSTREAM_HEAP` records newlib heap usage after the load. Azahar reports
`used=20,535,816 free=71,160` for the linked app on both profiles; the New test
reported `used=20,534,480 free=72,496`. Clearing `Vars.world` removes the live
Java world references, but the TeaVM managed heap is not returned to libctru.
The Old 3DS application-memory ceiling is lower than New 3DS, and Azahar does
not enforce the physical ceiling, so this remains an emulator-only measurement.

The app call site in `main_3ds.c` logs
`SECTOR_LOADER source=teavm available=1 loaded=1` when the embedded loader is
linked, and falls back to the native parser when it is not.

## Reproduce

```sh
# One-shot: transpile the pinned SaveIO producer and pack the loader archive.
UPSTREAM_MINDUSTRY=/path/to/Mindustry \
MINDUSTRY_SERVER_JAR=/path/to/server-release.jar \
ARC_CHECKOUT=/path/to/Arc \
GRADLE_BIN=/path/to/gradle \
./dev.sh loader

# Default app build: generates the loader when missing (or M3DS_REBUILD_LOADER=1)
# and links it. Requires a native devkitARM environment.
UPSTREAM_MINDUSTRY=/path/to/Mindustry ./dev.sh build
```

`build_teavm_loader` sources `/opt/devkitpro/3dsvars.sh` when `DEVKITARM` is not
already exported, so the archive is always produced with the ARM toolchain
rather than the host compiler. A loader-generation failure aborts `./dev.sh
build` instead of linking a stale archive, and the Docker fallback warns when
`UPSTREAM_MINDUSTRY` is set but no archive exists (Docker cannot generate one).

The Makefile normalizes the path to an absolute value with `override`, and the
loader/stub selection is written into `build-config.h` so toggling rebuilds the
stub object. CI asserts `arm-none-eabi-nm build/min3dstry.elf` reports the
strong `T m3ds_teavm_world_load` symbol (not the weak `W` stub) and compiles
both smoke variants.

The remaining step is to make this linked build the only shipped path and
delete the native parser from the startup path.
