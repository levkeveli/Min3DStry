# Memory budget

Status: **Experimental** (`host-tested`; Azahar Old/New 3DS telemetry recorded; physical Original/New 3DS pending).

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
