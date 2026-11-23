# Memory budget

Status: **Hardware-verified** on physical Original 3DS XL (64 MiB) and New 3DS XL (124 MiB); see [Old 3DS evidence](evidence/old3ds-xl-a4a9a96.md) and [New 3DS evidence](evidence/new3ds-xl-de3e249.md). The allocator reported `status=ok failures=0` with `used=553728` bytes on both. Sustained gameplay under the `>= 24 FPS` budget remains open (#43).

Issue: [#36](https://github.com/levkeveli/Min3DStry/issues/36) · Capability: `memory-budget-runtime`.

## Purpose

The 3DS application-memory region is the real memory budget: 64 MiB on a
default Original 3DS, 124 MiB on a New 3DS, and 178 MiB only in the New 3DS
"Dev1" configuration. The harness now has a deterministic, bounded allocator
that:

- derives its usable window from `osGetMemRegionSize(MEMREGION_APPLICATION)`
  and a fixed reserve for code, stacks, GPU command buffers and OS overhead;
- caps world, texture and transient allocations separately;
- fails cleanly without corrupting existing allocations;
- reports peak, remaining and failure telemetry over `svcOutputDebugString`
  and the Netloader log stream.

This is a **platform harness** capability. The upstream Mindustry/Arc TeaVM
runtime does not use this allocator yet; its own heap/GC budget remains a
separate upstream follow-up.

## Model

The C prototype reserves 16 MiB from the detected application region, then
caps its fixed arena at 1 MiB so the probe itself is small and deterministic:

| Item | Value |
|---|---|
| Arena | 1 MiB static buffer, 8-byte alignment |
| Reserve | 16 MiB (code, stacks, command buffers, OS overhead) |
| World cap | 50% of arena (512 KiB at full size) |
| Texture cap | 25% (256 KiB) |
| Transient cap | 25% (256 KiB) |
| World probe | 200 × 200 × 12 bytes = 480,000 bytes |
| Texture probe | 8 KiB (current fixture atlas) |
| Transient probe | 64 KiB |

The allocator is a bump allocator: no `malloc`, no fragmentation, no free
inside a scene. `m3ds_memory_budget_reset` reuses the arena for the next scene
while retaining peak and failure counters. Allocation failure records whether
the arena or a category cap was exhausted and leaves the used/peak state
unchanged.

## API

See [`include/platform/memory_budget.h`](../../include/platform/memory_budget.h)
and [`src/platform/memory_budget.c`](../../src/platform/memory_budget.c).

- `m3ds_memory_budget_available(app_mem_bytes, reserve_bytes)`
- `m3ds_memory_budget_init(budget, arena, capacity, world, textures, transient)`
- `m3ds_memory_budget_alloc(budget, category, bytes)`
- `m3ds_memory_budget_reset(budget)`
- `m3ds_memory_budget_remaining` / `..._category_remaining`
- `m3ds_memory_budget_format` for the one-line diagnostic

Telemetry format:

```text
[MIN3DSTRY] MEM app=<MiB>MiB reserve=<MiB>MiB budget=<bytes> used=<bytes> peak=<bytes> remaining=<bytes> world=<used>/<limit> textures=<used>/<limit> transient=<used>/<limit> failures=<count> status=<state>
```

## Verification

- **Host (`./dev.sh test`):** `tests/platform/test_memory_budget.c` covers 64 MiB
  application-budget math, alignment, category caps, arena exhaustion, clean
  recovery after reset, invalid inputs and the diagnostic format.
- **Azahar 2126.2 (`./dev.sh emu-test`):** both `new3ds` and `old3ds` profiles
  emit the MEM record and the verifier checks `status=ok`, `failures=0`,
  `remaining == budget - used`, per-category cap compliance and the existing
  parity/audio/frame telemetry. Both profiles reported:
  `app=96MiB reserve=16MiB budget=1048576 used=553728 peak=553728
  remaining=494848 world=480000/524288 textures=8192/262144
  transient=65536/262144 failures=0 status=ok`.
- **Physical Original/New 3DS:** not-tested. Record the same MEM line with the
  actual application-memory value when hardware deployment runs.

Limitations:

- The 1 MiB arena is a deliberate probe cap, not the full 64 MiB budget; the
  app-memory and reserve values are reported so the 64 MiB model is visible.
- Azahar reports `AppMem:96 MiB` for both emulated profiles (HLE behavior), so
  the emulator run does not prove the real 64 MiB/124 MiB split.
- The allocator does not model fragmentation, DMA/VRAM, the TeaVM GC heap or
  texture memory beyond the category reservations.

## Upstream TeaVM runtime `.bss` reduction ([#226](https://github.com/levkeveli/Min3DStry/issues/226))

TeaVM 0.16.0 emits a monolithic static array (`static TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY]`) in `arrayclass.c`. For full upstream Mindustry `v160.8` builds (`TEAVM_DYNAMIC_CLASS_POOL_CAPACITY = 47944`), that static symbol dominated ELF `.bss` and pushed `-O2` ARM11 subtotals above the `64 MiB` Original 3DS `APPMEMTYPE 0` ceiling.

[`research/upstream-runtime/arm11/m3ds_array_class_pool.h`](../../research/upstream-runtime/arm11/m3ds_array_class_pool.h) (staged by [`research/upstream-runtime/arm11/prepare.py`](../../research/upstream-runtime/arm11/prepare.py)) replaces the monolithic array with a lazily allocated, fixed-address `64`-slot chunk pool:

- Chunk `0` stays inline in `.bss` (`24,576` bytes + `3,000`-byte chunk table) so startup and small probes need zero heap allocations.
- Higher chunks allocate on demand (`memalign(8, bytes)` + zero-fill on 3DS) and are never moved (`realloc` is never used).
- Every chunk validates 8-byte alignment, signed-31-bit packed offset bounds (`(end - teavm_beforeClasses) <= 0x7FFFFFF8`, keeping bit 31 clear for TeaVM's GC mark bit), and `TEAVM_UNPACK_CLASS(TEAVM_PACK_CLASS(cls)) == cls`.

Measured with pinned `devkitpro/devkitarm` (`-O2`) on identical generated `audio-ticks` TeaVM C (`TEAVM_DYNAMIC_CLASS_POOL_CAPACITY = 47944`):

| Metric | Baseline static `.bss` | Lazy chunked pool | Delta |
|---|---|---|---|
| ELF `.bss` (`NOBITS`) | `18,462,664` bytes (`17.61 MiB`) | `79,752` bytes (`77.88 KiB`) | `-18,382,912` bytes (`-17.53 MiB`) |
| Page-rounded ELF `LOAD` segments | `38,936,576` bytes (`37.13 MiB`) | `20,590,592` bytes (`19.64 MiB`) | `-18,345,984` bytes (`-17.50 MiB`) |
| Subtotal (`LOAD` + `24 MiB` heap + `4 MiB` linear + `256 KiB` stack) | `65.38 MiB` | `47.89 MiB` | `-17.50 MiB` |

Host unit tests ([`tests/platform/test_array_class_pool.c`](../../tests/platform/test_array_class_pool.c) in `./dev.sh test`) and Linux host TeaVM C execution (`-O0`, `-O2`, and stress-chunked `M3DS_ARRAY_CLASS_CHUNK_SHIFT=2`) pass; Azahar emulator execution and physical Original/New 3DS memory/GC measurements of the reduced `.3dsx` remain separate pending checks.

## Upstream TeaVM sparse call-site compaction ([#233](https://github.com/levkeveli/Min3DStry/issues/233))

TeaVM 0.16.0's non-obfuscated `callsites.c` (`36.86 MB` of C source on `audio-ticks`) emits dense `methodLocations_teavm_callSites`, `callSiteLocations_teavm_callSites`, and `teavm_callSites[207272]`, where `199,179` (`96.1%`) of call sites have `firstHandler = NULL`. [`research/upstream-runtime/arm11/compact_callsites.py`](../../research/upstream-runtime/arm11/compact_callsites.py) and [`research/upstream-runtime/arm11/m3ds_callsites.h`](../../research/upstream-runtime/arm11/m3ds_callsites.h) (invoked by [`research/upstream-runtime/arm11/prepare.py`](../../research/upstream-runtime/arm11/prepare.py)) strip the file/line location tables by default and replace `teavm_callSites` with a sorted sparse `(id -> firstHandler)` binary-search table preserving all `8,093` non-null `try/catch` handler chains:

- **Trade-off & debug opt-out:** Stack traces (`printStack()` / `fillStackTrace()`) omit per-frame source file/line names under default compaction (`location == NULL`); set `M3DS_KEEP_CALLSITE_LOCATIONS=1` when staging/building to retain the full TeaVM location tables verbatim.

Measured with pinned `devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5` (`-O2`) on identical generated `audio-ticks` TeaVM C (comparing `M3DS_KEEP_CALLSITE_LOCATIONS=1` vs. default sparse compaction on base commit `a4136f6`, both using the lazy array class pool):

| Metric | Keep locations (`#226`) | Sparse call sites (`#233`) | Delta |
|---|---|---|---|
| Generated `callsites.c` | `36,857,224` bytes (`35.15 MiB`) | `1,515,250` bytes (`1.45 MiB`) | `-35,341,974` bytes (`-95.9%`) |
| ELF `.data` | `7,728,092` bytes (`7.37 MiB`) | `4,272,572` bytes (`4.07 MiB`) | `-3,455,520` bytes (`-3.30 MiB`) |
| Page-rounded ELF `LOAD` segments | `20,590,592` bytes (`19.64 MiB`) | `17,133,568` bytes (`16.34 MiB`) | `-3,457,024` bytes (`-3.30 MiB`) |
| Packaged `.3dsx` size | `22,321,160` bytes (`21.29 MiB`) | `17,521,408` bytes (`16.71 MiB`) | `-4,799,752` bytes (`-4.58 MiB`) |
| Subtotal (`LOAD` + `24 MiB` heap + `4 MiB` linear + `256 KiB` stack) | `47.89 MiB` | `44.59 MiB` | `-3.30 MiB` |

Host unit tests ([`tests/platform/test_callsites.c`](../../tests/platform/test_callsites.c), [`tests/tools/test_compact_callsites.py`](../../tests/tools/test_compact_callsites.py) in `./dev.sh test`) and compacted `-O2` Linux host execution on `audio-ticks` (`UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`, `trace=8dd46207043810fc`) pass; Azahar emulator runs and physical Original/New 3DS verification of the reduced `.3dsx` remain separate pending checks (`#43`).

## Upstream TeaVM dead string-pool pruning ([#43](https://github.com/levkeveli/Min3DStry/issues/43))

After stripping `callsites.c` location tables, the trailing string constants emitted solely for source file/class/method names become unreferenced everywhere else in the C program. When `M3DS_KEEP_CALLSITE_LOCATIONS != 1`, [`prune_unused_strings`](../../research/upstream-runtime/arm11/compact_callsites.py) (invoked by [`prepare.py`](../../research/upstream-runtime/arm11/prepare.py) after staging all `.c`/`.h` files) scans every non-`strings.{c,h}` source for `TEAVM_GET_STRING` / `TEAVM_GET_STRING_ADDRESS` indices, truncates the unreferenced tail, replaces any interior dead slots with `NULL`, shrinks the `teavm_initStringPool()` loop bound to avoid startup string-interning work, and fails closed without rewriting `strings.c` if any non-constant `teavm_stringPool` reference is detected.

Measured with pinned `devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5` (`-O2`) on identical `audio-ticks` TeaVM C staged atop base commit `a4136f6` (sparse call sites before vs. after `prune_unused_strings`):

| Metric | Sparse call sites (`#233`) | + Dead string pruning (`#43`) | Delta |
|---|---|---|---|
| `teavm_stringPool` entries | `19,586` | `13,263` | `-6,323` (`6,323` fewer `teavm_registerString()` interning entries) |
| Generated `strings.c` | `1,451,892` bytes (`1.38 MiB`) | `948,600` bytes (`0.90 MiB`) | `-503,292` bytes (`-34.7%`) |
| ELF `.data` | `4,272,600` bytes (`4.07 MiB`) | `3,736,800` bytes (`3.56 MiB`) | `-535,800` bytes (`-0.51 MiB`) |
| Page-rounded ELF `LOAD` segments | `17,133,568` bytes (`16.34 MiB`) | `16,596,992` bytes (`15.83 MiB`) | `-536,576` bytes (`-0.51 MiB`) |
| Packaged `.3dsx` size | `17,521,408` bytes (`16.71 MiB`) | `16,960,316` bytes (`16.17 MiB`) | `-561,092` bytes (`-0.54 MiB`) |
| Subtotal (`LOAD` + `24 MiB` heap + `4 MiB` linear + `256 KiB` stack) | `44.59 MiB` | `44.08 MiB` | `-0.51 MiB` |

Host unit tests (`tests/tools/test_compact_callsites.py` in `./dev.sh test`) and compacted + pruned `-O2` Linux host execution on `audio-ticks` (`UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`, `UPSTREAM_AUDIO_REGISTRY sounds=205 musics=16 duo=136 death=192 convLoop=63 drillLoop=67`, `trace=8dd46207043810fc`) pass; Azahar emulator runs and physical Original/New 3DS hardware verification remain separate pending checks (`#43`).

## Upstream TeaVM ARM11 `-Os` size optimization & ELF audit ([#43](https://github.com/levkeveli/Min3DStry/issues/43))

[`build.sh`](../../research/upstream-runtime/arm11/build.sh) now defaults `M3DS_ARM11_OPT` to `-Os` (`arm11_opt="${M3DS_ARM11_OPT:--Os}"`) with an early allowlist check (`-O0`, `-O1`, `-O2`, `-O3`, `-Os`, `-Og`; unsupported values exit `2` before staging files in `prepare.py`). Callers needing `-O2` can override with `M3DS_ARM11_OPT=-O2`.

Measured with pinned `devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5` on base `80f3087` (with lazy array class pool, sparse call sites, and dead string-pool pruning):

### `audio-ticks` (`-O2` vs. `-Os`)

| Metric | `-O2` | `-Os` | Delta |
|---|---|---|---|
| ELF `.text` | `12,623,752` bytes (`12.04 MiB`) | `9,923,904` bytes (`9.46 MiB`) | `-2,699,848` bytes (`-2.57 MiB`, `-21.4%`) |
| ELF `.rodata` | `154,992` bytes (`0.15 MiB`) | `153,424` bytes (`0.15 MiB`) | `-1,568` bytes (`-1.0%`) |
| ELF `.data` | `3,736,772` bytes (`3.56 MiB`) | `3,736,116` bytes (`3.56 MiB`) | `-656` bytes (`-0.0%`) |
| ELF `.bss` | `79,760` bytes (`0.08 MiB`) | `79,768` bytes (`0.08 MiB`) | `+8` bytes (`+0.0%`) |
| Page-rounded ELF `LOAD` segments | `16,596,992` bytes (`15.83 MiB`) | `13,897,728` bytes (`13.25 MiB`) | `-2,699,264` bytes (`-2.57 MiB`, `-16.3%`) |
| Packaged `.3dsx` size | `16,960,624` bytes (`16.17 MiB`) | `14,276,872` bytes (`13.62 MiB`) | `-2,683,752` bytes (`-2.56 MiB`, `-15.8%`) |
| Subtotal (`LOAD` + `24 MiB` heap + `4 MiB` linear + `256 KiB` stack) | `44.08 MiB` | `41.50 MiB` | `-2.57 MiB` |

### `network-render` (`-O2` vs. `-Os`, `--network` + real `v160.8` `assets.jar` `--multiplayer` RomFS)

| Metric | `-O2` | `-Os` | Delta |
|---|---|---|---|
| ELF `.text` | `14,429,064` bytes (`13.76 MiB`) | `11,413,048` bytes (`10.88 MiB`) | `-3,016,016` bytes (`-2.88 MiB`, `-20.9%`) |
| ELF `.rodata` | `172,032` bytes (`0.16 MiB`) | `170,608` bytes (`0.16 MiB`) | `-1,424` bytes (`-0.8%`) |
| ELF `.data` | `3,779,228` bytes (`3.60 MiB`) | `3,778,532` bytes (`3.60 MiB`) | `-696` bytes (`-0.0%`) |
| ELF `.bss` | `138,432` bytes (`0.13 MiB`) | `138,448` bytes (`0.13 MiB`) | `+16` bytes (`+0.0%`) |
| Page-rounded ELF `LOAD` segments | `18,526,208` bytes (`17.67 MiB`) | `15,507,456` bytes (`14.79 MiB`) | `-3,018,752` bytes (`-2.88 MiB`, `-16.3%`) |
| Packaged `.3dsx` size (incl. RomFS world atlas & bundles outside ELF `LOAD`) | `179,609,996` bytes (`171.29 MiB`) | `176,612,796` bytes (`168.43 MiB`) | `-2,997,200` bytes (`-2.86 MiB`, `-1.7%`) |
| Subtotal (`LOAD` + `24 MiB` heap + `4 MiB` linear + `256 KiB` stack) | `45.92 MiB` | `43.04 MiB` | `-2.88 MiB` |

### Remaining `-Os` ELF section and symbol breakdown (`arm-none-eabi-nm --size-sort`)

- **`audio-ticks -Os`:** `T:meth_*` totals `9,455,192` bytes (`9.02 MiB`) and `T:other` totals `470,416` bytes (`0.45 MiB`); `R:other` totals `141,380` bytes (including `teavm_resourceData` at `111,032` bytes); `D` totals `3,715,729` bytes dominated by `__compound_literal.*` (`1,015,124` bytes / `0.97 MiB`), `*_Cls` class descriptors (`mc_Blocks*_Cls` `288,664` B, `mc_UnitTypes*_Cls` `124,964` B, `mc_Fx*_Cls` `101,840` B, `mc_SerpuloTechTree*_Cls` `80,560` B, `mg_Sounds*_Cls` `78,660` B, `mg_EntityMapping*_Cls` `68,020` B, `mg_Call*_Cls` `63,460` B, `mc_ErekirTechTree*_Cls` `54,340` B), `exceptionHandlers_teavm_callSites` (`106,980` B), `teavm_*` (`104,156` B including `teavm_stringPool` at `53,052` B), and `m3ds_sparse_callsites` (`97,116` B). Largest single methods: `meth_mm_ContentParser_readBlockConsumers` (`76,240` B), `meth_mc_Blocks_load` (`75,092` B), `meth_mg_ContentRegions_loadRegions` (`45,040` B), `meth_mg_Call_registerPackets` (`43,376` B).
- **`network-render -Os`:** `T:meth_*` totals `10,352,756` bytes (`9.87 MiB`) and `T:other` totals `1,064,712` bytes (`1.02 MiB`); `R:all` totals `155,386` bytes (including `ft_adobe_glyph_list` at `55,997` B); `D:all` totals `3,759,577` bytes (`*_Cls` `2,365,116` B / `2.26 MiB`, `__compound_literal.*` `996,476` B / `0.95 MiB`, `callsites/m3ds_*` `308,544` B including `exceptionHandlers_teavm_callSites` `159,720` B and `m3ds_sparse_callsites` `148,824` B, `teavm_*` `75,372` B including `teavm_stringPool` `52,292` B, other `D` `14,069` B); `B:all` totals `146,432` bytes. Largest single methods: `meth_mc_Blocks_load` (`75,100` B), `meth_mg_ContentRegions_loadRegions` (`44,448` B), `meth_mg_Call_registerPackets` (`43,372` B), `meth_mm_DataPatcher_assign` (`37,724` B), plus >20 generated entity `meth_mg_*Unit*_read` deserializers at `~28–41 KiB` each (`meth_mg_PayloadUnitLegacyQuad_read` `40,732` B, `meth_mg_PayloadUnitLegacyOct_read` `39,712` B, `meth_mg_PayloadUnit_read` `39,604` B, `meth_mg_MechUnit_read` `37,080` B, `meth_mg_UnitWaterMove_read` `37,076` B, `meth_mg_LegsUnit_read` `37,076` B, `meth_mg_BlockUnitUnit_read` `37,076` B, `meth_mg_UnitEntity_read` `37,072` B, `meth_mg_BuildingTetherPayloadUnit_read` `36,040` B).

Verification: `-Os` Linux host execution on `audio-ticks` (`UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`, `UPSTREAM_AUDIO_REGISTRY sounds=205 musics=16 duo=136 death=192 convLoop=63 drillLoop=67`, `UPSTREAM_AUDIO_AMBIENT convTicks=399 drillTicks=420 loops=1,1 duoFirstPanMilli=-335 deathPanMilli=-520`, `UPSTREAM_AUDIO_COMBAT shots=17 firstShot=49 deathTick=382 destroyed=382 ammo=23 events=20 trace=8dd46207043810fc`), Azahar New 3DS and Original 3DS `-Os` boot on `audio-ticks` (`UPSTREAM_MEMORY appHeap=111915008` on `new3ds`, `UPSTREAM_MEMORY appHeap=82554880` on `old3ds`, `linearHeap=4194304`, `stack=262144` with Mindustry presets initialized cleanly), and `./dev.sh test` pass; `network-render -Os` Azahar boot and physical Original/New 3DS hardware `-Os` vs. `-O2` frame-time and memory measurements remain separate pending checks (`#43`).

## Unified ARM11 code-size experiment

The atlas-resident ROM built from the `6acf24cf` changes and pinned
devkitARM GCC 16.1.0 uses 33,501,184 bytes across page-rounded ELF load
segments. A second build with `-Oz -fno-inline` used 33,566,720 bytes.
Both ROMs used the same generated C, assets, 32 MiB managed heap, 6 MiB
linear heap, and 512 KiB stack. The resulting static subtotals were
70.45 MiB and 70.51 MiB. Both builds reached approximately 60 FPS in the
stationary Ground Zero replay under Azahar 2126.2 on both profiles.

The performance branch keeps `-Os`. Generated C contains coroutine
resume switches in the upstream entity readers and Arc `Reads`
methods. These states are present in the TeaVM output before native
compilation. The 64 MiB Original 3DS memory target remains open.
