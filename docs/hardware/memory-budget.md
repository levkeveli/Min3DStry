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
