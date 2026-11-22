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
New 3DS: UPSTREAM_ROWS flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680 building=9a691300c548b8fb buildings=2
         UPSTREAM_SAVE_ROWS status=0
         EMBED_LOADER ok=1 tiles=37500 buildings=2 flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680
Old 3DS: same rows/status/loader
```

The app call site in `main_3ds.c` logs
`SECTOR_LOADER source=teavm available=1 loaded=1` when the embedded loader is
linked, and falls back to the native parser when it is not.

## Reproduce

```sh
gradle -Pprobe=save-rows ... generateC
bash tools/build_teavm_loader.sh \
    research/upstream-runtime/build/generated/teavm/c build/teavm-loader
make TEAVM_LOADER_DIR=build/teavm-loader
```

The remaining step is to make the shipped 3DS build always generate and link
this library, then delete the native parser from the startup path.
