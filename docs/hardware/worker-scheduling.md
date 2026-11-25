# Deterministic worker scheduling

Status: **Hardware-verified** for checksum determinism with 2 and 4 workers on physical Original 3DS XL and New 3DS XL ([Old 3DS evidence](evidence/old3ds-xl-a4a9a96.md), [New 3DS evidence](evidence/new3ds-xl-de3e249.md)). Both runs reported `match=1 checksum=0x4bb2b98d`. The recorded timings do not show parallel speedup (`parallel_us` exceeded `serial_us`), so this page claims determinism, not speedup.

Issue: [#37](https://github.com/levkeveli/Min3DStry/issues/37)  
Capability: `threading-scheduling`.

## Purpose

This slice evaluates whether independent work can use extra 3DS cores without
changing simulation results. It does **not** parallelize `m3ds_parity_step` or
reorder gameplay ticks. The simulation tick stays single-threaded; the worker
pool is limited to independent, disjoint work such as grid scans or rendering
preparation.

## Determinism contract

[`include/platform/worker_pool.h`](../../include/platform/worker_pool.h) exposes
`M3dsWorkerPool`. A run is split into fixed `chunk_size` ranges. Every callback
receives one disjoint `[begin, end)` range and must:

- write only that range (or per-chunk output slots);
- avoid shared mutable state and atomics;
- return without depending on execution order.

Results are combined in chunk order after all workers join. Changing the worker
count therefore cannot change the chunk boundaries or the final checksum. Jobs
that violate the disjoint-range rule are outside the API contract.

## Implementation

- [`src/platform/worker_pool.c`](../../src/platform/worker_pool.c):
  - host builds use POSIX `pthread_create`/`pthread_join`;
  - 3DS builds use `libctru` `threadCreate`/`threadJoin` with a 16 KiB stack per
    worker and any-core affinity;
  - `workers=0` or `1` runs the serial fallback;
  - `M3DS_WORKER_MAX` is `4`; each lane takes a strided range covering all
    chunks, and a failed thread creation falls back to serial work for the
    remaining lanes;
  - `m3ds_worker_pool_active_workers()` reports the number of lanes that
    actually ran, so telemetry cannot claim a multi-worker run after a silent
    fallback.
- [`src/platform/worker_benchmark.c`](../../src/platform/worker_benchmark.c):
  - hashes a deterministic `200 x 200` read-only grid (`40,000` items, `40`
    chunks of `1024`);
  - runs the same workload serially and with the requested workers;
  - compares the ordered chunk checksums.
- [`src/platform/3ds/main_3ds.c`](../../src/platform/3ds/main_3ds.c) runs the
  benchmark for 2 and 4 workers at boot and logs one `[MIN3DSTRY] WORKERS`
  record per configuration. The canonical parity trace is unchanged.

## Verification record

- **Host (`./dev.sh test`):**
  - `tests/platform/test_worker_pool.c` checks serial, 2-worker and 4-worker
    results, exact one-call-per-chunk behavior, empty input, invalid arguments,
    and worker clamping.
  - `tests/platform/test_worker_benchmark.c` checks that 1, 2 and 4 workers all
    produce checksum `0x4bb2b98d`, with `match=1` and identical serial/parallel
    checksums.
- **Azahar 2126.2 (`./dev.sh emu-test`):** both profiles passed the existing
  parity/audio/frame assertions and logged:

  ```text
  [MIN3DSTRY] WORKERS workers=2 active=2 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=1348 parallel_us=1406
  [MIN3DSTRY] WORKERS workers=4 active=4 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=1348 parallel_us=1492
  ```

  Both profiles passed with parity `0x53a483c7`. Azahar FPS varied with host
  load, and the worker timings were identical across emulated profiles. They are
  not hardware evidence; the meaningful result is that both worker counts
  produce the serial checksum.
- **Physical New 3DS:** record wall-clock and frame timing on
  hardware before enabling workers for any frame-critical path.

## Result and limitations

The emulator does **not** show a benefit from parallel execution for this
workload (1406–1495 µs with workers versus 1348 µs serial). The single-threaded
path remains the default, matching the issue's requirement to retain it where no
benefit is measured.

Limitations:

- The benchmark is read-only; it does not prove safe parallel mutation of the
  simulation or rendering state.
- There is no persistent pool or work stealing; threads are created per run, so
  their creation cost is included.
- Azahar timing is not representative of ARM11 hardware, and the app memory
  arena does not account for worker stacks yet.
- The upstream TeaVM runtime has its own managed-thread executor; this C pool is
  a platform-harness evaluation, not a replacement for it.
