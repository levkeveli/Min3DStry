# Emulator Old/New 3DS profile verification — 2026-11-25

This record keeps an Azahar 2126.2 New 3DS and Original 3DS profile run for
comparison. These are emulator results, not physical-console measurements, and
they do not satisfy the physical New 3DS acceptance gate.

## Artifact

| Field | Value |
| --- | --- |
| Merge `main` | `857041e4210e877a51795d97cc7be1ce7242affb` |
| CI run | [45669561525](https://github.com/levkeveli/Min3DStry/actions/runs/45669561525) |
| Verify job | [139405416585](https://github.com/levkeveli/Min3DStry/actions/runs/45669561525/job/139405416585) |
| Artifact | `min3dstry.3dsx`, 17,310,372 bytes |
| SHA-256 | `7adb4ee8edaa4f7800c9226729016517ebdad10510d7737c5bc0a6632881aae9` |
| Emulator | Azahar `2126.2`, FIRM 2.58.0, Mesa llvmpipe |
| Application memory | `96 MiB` for both emulator profiles (emulator-only setting; **not** the Original 3DS 64 MiB budget) |

Raw report: [emulator-857041e-report.json](evidence/emulator-857041e-report.json).
The two retained profile screenshots are byte-identical because the verifier
captured the same deterministic parity boot frame before profile-specific
state; they are not independent profile evidence. The report JSON is the
authoritative per-profile record.

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
frames. Both used the emulator's 96 MiB application-memory setting, so the
Original 3DS result does **not** demonstrate fit within the 64 MiB O3DS budget.
`Audio=fallback_no_dsp` means the emulator did not expose NDSP hardware;
audible DSP output is not claimed by this record.

## Scope

This record keeps the current-main Azahar Old/New profile results alongside the
physical evidence set. It does not change a capability or mechanic stage, does
not replace a physical-console claim in release notes, and does not cover
audible DSP output, physical SD/SoC timing, thermal behavior, the Original 3DS
64 MiB budget, or physical New 3DS acceptance for the 100% gauge.
