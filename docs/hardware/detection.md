# Hardware Detection (Original & New 3DS/2DS)

Min3DStry exposes a platform-independent hardware profile in [include/platform/hardware_profile.h](../../include/platform/hardware_profile.h) and [src/platform/hardware_profile.c](../../src/platform/hardware_profile.c), backed by `libctru` in [src/platform/3ds/hardware_probe_3ds.c](../../src/platform/3ds/hardware_probe_3ds.c).

## Detected Fields

- **Console model** (`M3dsConsoleModel`): `Original 3DS`, `Original 3DS XL`, `New 3DS`, `Original 2DS`, `New 3DS XL`, `New 2DS XL` (from `CFGU_GetSystemModel`), or `Unknown 3DS`.
- **Generation flag** (`is_new_3ds`): combines `APT_CheckNew3DS` and model classification.
- **Extra controls** (`has_c_stick_and_zl_zr`): enabled on New 3DS/2DS models.
- **CPU / L2 speedup** (`cpu_clock_mhz`, `l2_cache_enabled`): calls `osSetSpeedupEnable(true)` on New 3DS/2DS (804 MHz + L2 cache) while keeping 268 MHz on Original 3DS/2DS.
- **Application memory** (`app_mem_bytes`): queried via `osGetMemRegionSize(MEMREGION_APPLICATION)`.
- **FIRM version** (`os_version_major`, `os_version_minor`, `os_version_build`): queried via `osGetFirmVersion()`.

## Verification Status

- **Host unit tests** (`tests/platform/test_hardware_profile.c`): **Host-tested** via `./dev.sh test` (model classification, Old/New 3DS invariants, formatting, null/bounds guards).
- **3DS ARM11 build**: **3DS-build-tested** via `./dev.sh build` (`min3dstry.3dsx`).
- **Emulator (`emulator-tested`, Azahar `2126.2`)**: Verified automatically via `./dev.sh emu-test`:
  - `is_new_3ds=true`: `New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:96 MiB, FIRM:2.58.0)`
  - `is_new_3ds=false`: `Original 3DS XL (O3DS, 268 MHz, L2:off, AppMem:96 MiB, FIRM:2.58.0)`
  - Note: Azahar HLE logs `ConfigureNew3DSCPU` (`ptm:sysm`) as unimplemented and reports `AppMem:96 MiB` under both emulated profiles.
- **Physical Hardware**: **Not yet tested** — awaiting real Old/New 3DS hardware runs (tracked under Issue #6).
