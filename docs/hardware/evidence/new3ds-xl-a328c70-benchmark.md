- **Target:** New 3DS XL (`commit a328c70`)
- **Hardware probe:** `New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:2.58.0)`
- **Parity trace:** `starter_copper_defense_v1` `hash=0x53a483c7` (copper=22, delivered=12, killed=2, hp=160)
- **Audio:** backend=ndsp state=ready rc=0x00000000 rate=22050 voices=0/8 clips=2
- **Frame telemetry:** tick=30 R:428 L:2 T:0 C:4 Cull:448 Sw:1 Drop:0 CPU=7.76% GPU=10.82% CmdBuf=1.11%
- **Arena memory:** app=124MiB used=553728/1048576B peak=553728B failures=0 status=ok
- **Benchmark:** `fps_min=29.92 fps_max=59.84` (windows=132, p95_max=33.50ms, scenario=factory_demo)
- **FPS rule:** `frame_rate` uses the minimum of all `BENCH` windows; the sampler runs the `factory_demo` harness, not upstream campaign gameplay.
- **Budget profile:** `new3ds` (New 3DS/2DS (APPMEMTYPE 6))
- **Overall verdict:** `PASS`

| Check | Status | Detail |
|---|---|---|
| `hw_profile` | `PASS` | matched N3DS in HW line |
| `memory_budget` | `PASS` | used=553728/1048576 peak=553728 failures=0 status=ok |
| `upstream_memory_footprint` | `SKIPPED` | no UPSTREAM_MEMORY record or --load-bytes |
| `frame_rate` | `PASS` | windows=132 fps_min=29.92 fps_max=59.84 (min 24.00), p95_max=33.50 (limit 41.67) |
| `parity_hash` | `PASS` | expected=0x53a483c7, actual=0x53a483c7 |
