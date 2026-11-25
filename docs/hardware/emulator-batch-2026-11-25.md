# Emulator Old/New 3DS profile verification — 2026-11-25

Operator direction for this milestone: **Azahar 2126.2 New 3DS and Original 3DS
profile runs are accepted as the 3DS verification target.** These are emulator
results, not physical-console measurements; this record never labels them as
physical hardware.

## Artifact

| Field | Value |
| --- | --- |
| Merge `main` | `857041e4210e877a51795d97cc7be1ce7242affb` |
| CI run | [45669561525](https://github.com/levkeveli/Min3DStry/actions/runs/45669561525) |
| Verify job | [139405416585](https://github.com/levkeveli/Min3DStry/actions/runs/45669561525/job/139405416585) |
| Artifact | `min3dstry.3dsx`, 17,310,372 bytes |
| SHA-256 | `7adb4ee8edaa4f7800c9226729016517ebdad10510d7737c5bc0a6632881aae9` |
| Emulator | Azahar `2126.2`, FIRM 2.58.0, Mesa llvmpipe |

Raw report: [emulator-857041e-report.json](evidence/emulator-857041e-report.json).
Screenshots: [New 3DS](evidence/emulator-857041e-new3ds.png),
[Original 3DS](evidence/emulator-857041e-old3ds.png).

## Results

```text
[PASS] new3ds: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:96 MiB, FIRM:2.58.0)
       Parity=0x53a483c7 Audio=fallback_no_dsp R:428 C:4 Sw:1
       FPS=62.79 (104.05%) Sectors=0
[PASS] old3ds: Original 3DS XL (O3DS, 268 MHz, L2:off, AppMem:96 MiB, FIRM:2.58.0)
       Parity=0x53a483c7 Audio=fallback_no_dsp R:428 C:4 Sw:1
       FPS=59.76 (99.88%) Sectors=0
AZAHAR_PROVENANCE_OK sha256=7adb4ee8...aae9
```

Both profiles exceed the 24 FPS target (`62.79` and `59.76` system FPS), match
the deterministic parity trace `0x53a483c7`, and report zero budget-dropped
frames. `Audio=fallback_no_dsp` means the emulator did not expose NDSP hardware;
audible DSP output is not claimed by this record.

## Scope

This record supports the Old/New 3DS **profile** half of the hardware parity
gates under operator direction. It does not replace a physical-console claim in
release notes or hardware evidence files, and it does not cover audible DSP
output, physical SD/SoC timing, or thermal behavior.
