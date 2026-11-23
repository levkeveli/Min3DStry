# Hardware evidence — New 3DS XL

- Commit: `a328c70`
- Build record: `commit=a328c7013643 dirty=0`
- Profile: `new3ds`
- Scenario: `sector170`
- Captured: `2026-11-22T22:17:59Z`
- Model: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:2.58.0)
- ROM: `build/hw-test-a328c70/min3dstry.3dsx` (sha256 `4830239a03db5935fba779ef83d1914b5a2607f08c1efd668c3877a8a5d4ee5a`, 10387172 bytes)
- Verdict: **PASS** (12/12 required checks passed, 184 records)

| Check | Expected | Observed | Result |
| --- | --- | --- | --- |
| `build.commit` | commit=<sha> dirty=0 (matches --commit) | commit=a328c7013643 dirty=0 | PASS |
| `hardware.profile` | N3DS AppMem:124 MiB | N3DS AppMem:124 MiB | PASS |
| `storage.roundtrip` | status=ok | ok | PASS |
| `save.slot` | present=1 valid=1 version=13 | present=1 valid=1 version=13 | PASS |
| `loader.sector` | available=1 loaded=1 tiles=37500 buildings=2 | available=1 loaded=1 tiles=37500 buildings=2 | PASS |
| `loader.alloc` | hash=783fbd80e415dcd7 | 783fbd80e415dcd7 | PASS |
| `loader.tile_state` | linked=2 hash=e35397041df8e5fe | linked=2 hash=e35397041df8e5fe | PASS |
| `loader.save_parity` | hash=3a375fd4f81c5680 | 3a375fd4f81c5680 | PASS |
| `sim.parity` | scenario=starter_copper_defense_v1 hash=0x53a483c7 | scenario=starter_copper_defense_v1 hash=0x53a483c7 | PASS |
| `workers.checksum` | match=1 checksum=0x4bb2b98d | match=1 checksum=0x4bb2b98d | PASS |
| `frame.drops` | >=1 FRAME record, max drop=0 | frames=1 max_drop=0 | PASS |
| `memory.budget` | status=ok failures=0 | status=ok failures=0 | PASS |
| `audio.state` | ready (hardware with dspfirm) or fallback_no_dsp | ready | PASS |

## Operator confirmation

The operator tested the combined HWT-01/HWT-06 checklist and reported that the
factory scene, pan/zoom, lighting toggle, pause/resume and menus all worked.
The minimap was visible in the top-right and the HUD rows were correct.
Touch zones responded (15 `TOUCH` actions in the log), but the visible
`Touch: pad / zoom / menu` legend is not drawn inside the hit zones; pressing
the legend does nothing while pressing the top-right zone does open the menu.
That is a UI-label defect, tracked separately; the coordinate mapping itself
is correct.

Audio is only **partially** verified: the operator heard the UI "ding" tone,
so the NDSP output path is audible. However, every `AUDIO_EVENT` resolved to
`clip=-1 voice=0`, and the log shows only 2 registered clips. Upstream sound
effects and music are **not verified**, so `ndsp-sfx-music` remains
`emulator-tested`.

## FPS interpretation

The raw log contains 132 `BENCH` windows over the ~10-minute session.
`frame_rate` is evaluated on the **minimum** window (`fps_min=29.92`,
`p95_max=33.50 ms`), not the final `59.84` FPS window. The sampler exercises
the `factory_demo` harness, not full campaign gameplay; campaign performance
remains HWT-02/HWT-06 work.

## Notes

- `audio.state`: informational: fallback_no_dsp means sd:/3ds/dspfirm.cdc is unavailable

## Captured records

```text
[MIN3DSTRY] BUILD commit=a328c7013643 dirty=0
[MIN3DSTRY] HW: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:2.58.0)
[MIN3DSTRY] STORAGE root=/3ds/min3dstry settings=/3ds/min3dstry/settings.bin sector=/3ds/min3dstry/sector-serpulo-170.msav
[MIN3DSTRY] STORAGE_ROUNDTRIP status=ok bytes=4 root=sdmc:/3ds/min3dstry
[MIN3DSTRY] SETTINGS primary=0 backup=0 primary_bytes=0 backup_bytes=0
[MIN3DSTRY] SAVE_SLOT present=1 valid=1 compressed=1 version=13 bytes=11072 path=sdmc:/3ds/min3dstry/sector-serpulo-170.msav
[MIN3DSTRY] SAVE_META map=Glacier wave=1
[MIN3DSTRY] SECTOR_CATALOG count=1
[MIN3DSTRY] SECTOR_SLOT planet=serpulo id=170 valid=1 compressed=1 bytes=11072 map=Glacier wave=1
[MIN3DSTRY] SECTOR_LOADER source=teavm available=1 loaded=1 path=/3ds/min3dstry/sector-serpulo-170.msav tiles=37500 buildings=2
[MIN3DSTRY] SECTOR_ALLOC tiles=37500 bytes=1312788 hash=783fbd80e415dcd7 status=loaded
[MIN3DSTRY] SECTOR_TILE_STATE tiles=37500 buildings=2 linked=2 hash=e35397041df8e5fe status=loaded
[MIN3DSTRY] SECTOR_PARITY buildings=2 hash=3a375fd4f81c5680 verified=0 status=loaded
[MIN3DSTRY] MEM app=124MiB reserve=16MiB budget=1048576 used=553728 peak=553728 remaining=494848 world=480000/524288 textures=8192/262144 transient=65536/262144 failures=0 status=ok
[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160
[MIN3DSTRY] WORKERS workers=2 active=2 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=843 parallel_us=1061
[MIN3DSTRY] WORKERS workers=4 active=4 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=842 parallel_us=1318
[MIN3DSTRY] VIEW zoom=1.00 lighting=1
[MIN3DSTRY] ATLAS loaded
[MIN3DSTRY] AUDIO backend=ndsp state=ready rc=0x00000000 rate=22050 voices=0/8 clips=2
[MIN3DSTRY] MENU state=closed actions=3
[MIN3DSTRY] MAIN_MENU screen=title selected=play items=6
[MIN3DSTRY] AUDIO_EVENT wave.spawn spawned=2 clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT shoot.duo fired=1 clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT block.place clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT block.break clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT shoot.duo fired=2 clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT shoot.duo fired=3 clip=-1 voice=0
[MIN3DSTRY] AUDIO_EVENT enemy.kill killed=1 clip=-1 voice=0
[MIN3DSTRY] FRAME tick=30 paused=0 cam=(88.0,72.0) draw=R:428,L:2,T:0,C:4 cull=448 sw=1 drop=0 cpu=7.76% gpu=10.82% cmdbuf=1.11%
[MIN3DSTRY] FACTORY tick=60 delivered=2 inventory=11 hash=0xa2e66af9
[MIN3DSTRY] BENCH fps=29.92 avg_ms=33.43 p95_ms=33.50 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=29.92 avg_ms=33.43 p95_ms=33.49 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=29.92 avg_ms=33.43 p95_ms=33.48 frames=256 scenario=factory_demo
[MIN3DSTRY] MAIN_MENU screen=play selected=campaign
[MIN3DSTRY] BENCH fps=29.92 avg_ms=33.43 p95_ms=33.48 frames=256 scenario=factory_demo
[MIN3DSTRY] MAIN_MENU action=play.campaign
[MIN3DSTRY] BENCH fps=44.79 avg_ms=22.33 p95_ms=33.46 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.84 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.83 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.86 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.84 avg_ms=16.71 p95_ms=16.86 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.84 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.83 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] MENU state=open selected=resume
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.83 frames=256 scenario=factory_demo
[MIN3DSTRY] MENU state=closed selected=resume
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.83 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.in x=283 y=102
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.in x=301 y=96
[MIN3DSTRY] TOUCH action=zoom.out x=299 y=126
[MIN3DSTRY] TOUCH action=zoom.out x=297 y=123
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.out x=301 y=129
[MIN3DSTRY] TOUCH action=zoom.in x=301 y=86
[MIN3DSTRY] TOUCH action=zoom.in x=302 y=81
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.82 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.in x=300 y=77
[MIN3DSTRY] TOUCH action=zoom.in x=298 y=77
[MIN3DSTRY] TOUCH action=zoom.in x=292 y=85
[MIN3DSTRY] TOUCH action=zoom.in x=290 y=83
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.in x=286 y=74
[MIN3DSTRY] TOUCH action=menu x=288 y=47
[MIN3DSTRY] MENU state=open selected=resume
[MIN3DSTRY] TOUCH action=zoom.in x=281 y=78
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] TOUCH action=zoom.in x=260 y=71
[MIN3DSTRY] MENU action=resume
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.76 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.76 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.76 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.81 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.77 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.80 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.79 frames=256 scenario=factory_demo
[MIN3DSTRY] BENCH fps=59.83 avg_ms=16.71 p95_ms=16.78 frames=256 scenario=factory_demo
```
