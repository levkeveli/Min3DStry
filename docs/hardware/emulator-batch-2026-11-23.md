# Emulator validation batch — 2026-11-23

Status: **PASS** on the pinned Azahar `2126.2` New 3DS and Original 3DS profiles.
This is emulator evidence for packaging, boot, parity, audio routing and stack
placement. It is **not** physical-hardware or audible-DSP evidence.

## Artifact

| Field | Value |
| --- | --- |
| Merge `main` | `71ddb4b1f9a19fff7077363b27a4ca035fbda5ed` |
| Build run | [45266029706](https://github.com/levkeveli/Min3DStry/actions/runs/45266029706) |
| Artifact | `min3dstry.3dsx` |
| Bytes | `17,207,932` |
| SHA-256 | `5bdf7d2fa8db88be88661e966e454d49e6d125010090f2abafc8f87afd3b5d18` |

The build logged `main_stack: bytes=2400 limit=4096`,
`audio_romfs: OK entries=9 bytes=647008`, and `artifact_bytes=17207932`.

## Verification

`tools/verify_azahar.py --rom <artifact> --models new3ds,old3ds --timeout 180`
reported:

```text
[PASS] new3ds: New 3DS XL | Parity=0x53a483c7 | Audio=fallback_no_dsp | FPS=58.96 (100.29%) | Sectors=1
[PASS] old3ds: Original 3DS XL | Parity=0x53a483c7 | Audio=fallback_no_dsp | FPS=59.14 (100.39%) | Sectors=1
```

Both profiles loaded `audio_romfs status=ok clips=9 bytes=647008`, resolved
`wave.spawn` to clip `8`, and kept `Sectors=1`. `report.json` recorded the
artifact path, size and SHA-256.

`fallback_no_dsp` is expected in Azahar. The batch proves RomFS loading, event
routing and the loader stack budget; audible DSP playback and mixing quality
remain hardware-only checks.

## Tasks covered

| # | Task | PR | Result |
| --- | --- | --- | --- |
| 1 | Upstream audio RomFS packaging | #509 | PASS — 9 clips, `wave.spawn/8` |
| 2 | Loader stack placement | #537 | PASS — boots both profiles, parity `0x53a483c7` |
| 3 | `main()` stack guard | #540 | PASS — `2400` bytes / `4096` limit |
| 4 | GitHub-hosted full-build fallback | #541 | PASS — hosted build and artifact |
| 5 | Direct-target Makefile object dependency | #545 | PASS — recursive `all` build packaged |
| 6 | Logic processor/display ledger | #552 | PASS — gauge `71.45%`, ledger tests |
| 7 | Hosted ARM11 smoke fallback + Azahar runtime deps | #554 | PASS — hosted unified/offline/live smoke |
| 8 | Official artifact evidence | #555 | PASS — SHA/size/Azahar results recorded |
| 9 | Full-build Azahar verification job | #556 | PASS — `Verify artifact in Azahar` job |
| 10 | Artifact SHA in verification reports | #557 | PASS — 11 tool tests, batch report SHA |

All ten PRs merged. `main` now runs the pinned Azahar verification as part of
every full build, so future artifacts are gated on boot, parity, audio RomFS and
event routing before they are treated as evidence.

## Not covered

- Physical New or Original 3DS boot, DSP audio, thermal/FPS behavior.
- Cross-platform multiplayer.
- Gameplay content beyond the deterministic parity scenario.

No hardware deployment or NetLoader probe was performed for this batch.
