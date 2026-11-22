# Hardware evidence — Original 3DS XL

- Commit: `a4a9a961f3639ccd95eb4c1539486e86cbe3a9ed`
- Build record: `commit=a4a9a961f363 dirty=0`
- Profile: `old3ds`
- Scenario: `smoke`
- Captured: `2026-11-22T15:51:35Z`
- Model: Original 3DS XL (O3DS, 268 MHz, L2:off, AppMem:64 MiB, FIRM:2.58.0)
- ROM: `build/min3dstry.3dsx` (sha256 `4399b0370a81f9a5e89e5ee4d56f37c50c8fda740d88d4b3c522bb8d2f9985ae`, 365784 bytes)
- Verdict: **PASS** (7/7 required checks passed, 25 records)

| Check | Expected | Observed | Result |
| --- | --- | --- | --- |
| `build.commit` | commit=<sha> dirty=0 (matches --commit) | commit=a4a9a961f363 dirty=0 | PASS |
| `hardware.profile` | O3DS AppMem:64 MiB | O3DS AppMem:64 MiB | PASS |
| `storage.roundtrip` | status=ok | ok | PASS |
| `loader.sector` | available=1 loaded=1 tiles=37500 buildings=2 | missing | INFO |
| `loader.alloc` | hash=783fbd80e415dcd7 | missing | INFO |
| `loader.tile_state` | linked=2 hash=e35397041df8e5fe | missing | INFO |
| `loader.save_parity` | hash=3a375fd4f81c5680 | missing | INFO |
| `sim.parity` | scenario=starter_copper_defense_v1 hash=0x53a483c7 | scenario=starter_copper_defense_v1 hash=0x53a483c7 | PASS |
| `workers.checksum` | match=1 checksum=0x4bb2b98d | match=1 checksum=0x4bb2b98d | PASS |
| `frame.drops` | >=1 FRAME record, max drop=0 | frames=1 max_drop=0 | PASS |
| `memory.budget` | status=ok failures=0 | status=ok failures=0 | PASS |
| `audio.state` | ready (hardware with dspfirm) or fallback_no_dsp | ready | PASS |

## Notes

- `audio.state`: informational: fallback_no_dsp means sd:/3ds/dspfirm.cdc is unavailable

[Raw NetLoader and telemetry log](old3ds-xl-a4a9a96.log)

## Operator observations

- The build booted successfully on the Original 3DS XL.
- START responded, camera panning worked, and sound was audible.
- Some physical buttons are unreliable. Full control coverage and five-minute stability were not recorded.

## Captured records

```text
[MIN3DSTRY] BUILD commit=a4a9a961f363 dirty=0
[MIN3DSTRY] HW: Original 3DS XL (O3DS, 268 MHz, L2:off, AppMem:64 MiB, FIRM:2.58.0)
[MIN3DSTRY] STORAGE root=/3ds/min3dstry settings=/3ds/min3dstry/settings.bin sector=/3ds/min3dstry/sector-serpulo-170.msav
[MIN3DSTRY] STORAGE_ROUNDTRIP status=ok bytes=4 root=sdmc:/3ds/min3dstry
[MIN3DSTRY] SETTINGS primary=0 backup=0 primary_bytes=0 backup_bytes=0
[MIN3DSTRY] SAVE_SLOT present=0 valid=0 compressed=0 version=0 bytes=0 path=sdmc:/3ds/min3dstry/sector-serpulo-170.msav
[MIN3DSTRY] SECTOR_CATALOG count=0
[MIN3DSTRY] MEM app=64MiB reserve=16MiB budget=1048576 used=553728 peak=553728 remaining=494848 world=480000/524288 textures=8192/262144 transient=65536/262144 failures=0 status=ok
[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160
[MIN3DSTRY] WORKERS workers=2 active=2 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=2950 parallel_us=3556
[MIN3DSTRY] WORKERS workers=4 active=4 chunks=40 items=40000 match=1 checksum=0x4bb2b98d serial_us=2901 parallel_us=4335
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
[MIN3DSTRY] FRAME tick=30 paused=0 cam=(88.0,72.0) draw=R:428,L:2,T:0,C:4 cull=448 sw=1 drop=0 cpu=18.07% gpu=12.62% cmdbuf=1.11%
[MIN3DSTRY] FACTORY tick=60 delivered=2 inventory=11 hash=0xa2e66af9
```
