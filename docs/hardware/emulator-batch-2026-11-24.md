# Emulator validation batch — 2026-11-24

Status: **PASS** on pinned Azahar `2126.2` New 3DS and Original 3DS profiles.
This is emulator and CI evidence. It is **not** physical-hardware or audible-DSP
evidence.

## Artifact

| Field | Value |
| --- | --- |
| Merge `main` | `fdc9750ad4e5e88c6868bd96594994becbaf4e06` |
| Build run | [45329331858](https://github.com/levkeveli/Min3DStry/actions/runs/45329331858) |
| Artifact | `min3dstry.3dsx` |
| Bytes | `17,157,444` |
| SHA-256 | `630efbca79e1977214c7437cc1ee70e68ebe775fee5151c1ccb5cd9af9e3045a` |

The build logged `main_stack: bytes=2400 limit=4096`,
`audio_romfs: OK entries=9 bytes=647008`, and `artifact_bytes=17157444`. The
verify job reported `AZAHAR_PROVENANCE_OK` with the same SHA-256 as the build
output.

## Verification

```text
[PASS] new3ds: New 3DS XL | Parity=0x53a483c7 | Audio=fallback_no_dsp | FPS=59.62 | Sectors=0
[PASS] old3ds: Original 3DS XL | Parity=0x53a483c7 | Audio=fallback_no_dsp | FPS=59.01 | Sectors=0
```

Both profiles loaded nine clips (`647,008` bytes) and resolved `wave.spawn` to
clip `8`. `Sectors=0` means no sector save was staged on the CI virtual SD; the
sector loader path is unchanged. `fallback_no_dsp` is expected in Azahar.

## Tasks covered

| # | Task | PR | Result |
| --- | --- | --- | --- |
| 1 | Live pause-overlay parity evidence | #571 | PASS — ledger tests, gauge `72.15%` |
| 2 | Exact artifact-SHA provenance gate | #574 | PASS — `AZAHAR_PROVENANCE_OK` |
| 3 | Persist the 2026-11-23 batch | #573 | PASS |
| 4 | Drain the stock server log | #572 | PASS |
| 5 | Record the post-batch provenance check | #578 | PASS |
| 6 | Revert #569 to restore live boot | #579 | PASS — live smoke both profiles |
| 7 | Cancel superseded same-ref runs | #575 | PASS |
| 8 | Retry stock-session world-stream races | #581 | PASS — 2 unit tests + live smoke |
| 9 | Retry Gradle on registry `429` | #582 | PASS — unified + live smoke |
| 10 | Final batch validation | — | PASS |

## Live-boot regression (#569)

The merged `main` at `07ba81ee` could not pass the live multiplayer smoke: the
unified client exited immediately after `UPSTREAM_BOOT mode=join`, before
`UPSTREAM_INTERFACE_GRAPHICS` or `UPSTREAM_LIVE_CONTROLS_READY`. It reproduced
locally with the downloaded artifact.

Local A/B, same Azahar and stock server:

| Artifact | Source | Result |
| --- | --- | --- |
| #572 | before #568 | **PASS** both profiles |
| #568 | reconnect/keepalive | **PASS** both profiles |
| `07ba81ee` | merged #569 | **FAIL** both profiles at boot |

#569's own PR smoke and #568's PR smoke each passed; the merged build did not.
The merged ROM was also ~184 KiB smaller, pointing at a merge/build interaction
rather than a plain source conflict. #579 reverted only #569 and restored the
live smoke. The feature should be re-landed against the merged base with a live
boot check, not only a PR-branch check.

## CI hardening

- #581 retries the stock session baseline only for known transient
  world-stream races; real protocol failures still fail immediately.
- #582 retries unified Gradle generation only on HTTP `429` or
  `Could not resolve`, up to three attempts; other failures fail immediately.
- #575 stops a stale same-ref run from holding the queue. Manual
  `workflow_dispatch` runs use a per-run group and are not canceled by pushes.

## Post-batch CI gates

These gates landed after the artifact above and apply to the **next** batch:

- The unified smoke runs `runBuildingLayersJvm` with the staged Glacier map,
  `sprites.aatls` and `block_colors.png`. It fails unless the live draw
  registry keeps POWER 26, DISTRIBUTION 28, LIQUID 21 and MATERIAL 52 blocks.
  Bridge Conduit and Phase Conduit are asserted as `Category.liquid`.
- The offline Ground Zero CTM starts at 40s because hosted
  `UPSTREAM_SP_READY` now arrives around 32s; the job also retries that replay
  once before failing.
- Full builds still require `AZAHAR_PROVENANCE_OK` for the exact uploaded
  artifact SHA-256.

The counts and timing above are not claimed for the `630efbca` artifact. They
are verified against the merged code in [batch 2](emulator-batch-2026-11-24b.md).

## Not covered

- Physical New or Original 3DS boot, DSP audio, thermal/FPS behavior.
- Cross-platform multiplayer.
- Gameplay content beyond the deterministic parity scenario and live smoke.

No hardware deployment or NetLoader probe was performed for this batch.
