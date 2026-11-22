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
  same rows/heap/status/loaded values

New/Old 3DS missing save:
  UPSTREAM_SAVE_ROWS error=arc.util.ArcRuntimeException: Error reading file: /3ds/min3dstry/does-not-exist.msav (absolute)
  EMBED_LOADER missing=1 ok=0 verified=1
```

The smoke harness builds two variants: one loads the real fixture and asserts
the hashes; the other loads a missing path and asserts the loader returns false
without aborting. The Java entry catches load/extraction failures, reports
them, and clears `Vars.world` after extracting rows so the TeaVM world is not
retained alongside the native copy. The save version is propagated through
`m3ds_save_rows_set_version`. `UPSTREAM_HEAP` records newlib heap usage after
the load (about 20.5 MiB used for the Glacier fixture).

The app call site in `main_3ds.c` logs
`SECTOR_LOADER source=teavm available=1 loaded=1` when the embedded loader is
linked, and falls back to the native parser when it is not.

## Reproduce

```sh
UPSTREAM_MINDUSTRY=/path/to/Mindustry \
MINDUSTRY_SERVER_JAR=/path/to/server-release.jar \
ARC_CHECKOUT=/path/to/Arc \
GRADLE_BIN=/path/to/gradle \
./dev.sh loader

./dev.sh build   # auto-builds/links when UPSTREAM_MINDUSTRY is set
```

`dev.sh build` passes an absolute `TEAVM_LOADER_DIR` to make, and the Makefile
also normalizes a relative path. `M3DS_REBUILD_LOADER=1` forces regeneration.
CI now runs `make TEAVM_LOADER_DIR=...` in the pinned container.

The remaining step is to make this linked build the only shipped path and
delete the native parser from the startup path.
