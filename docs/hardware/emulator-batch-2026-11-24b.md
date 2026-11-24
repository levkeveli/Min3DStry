# Emulator validation batch 2 — 2026-11-24

Status: **PASS** on pinned Azahar `2126.2` New 3DS and Original 3DS profiles.
This is emulator and CI evidence. It is **not** physical-hardware or audible-DSP
evidence. It is the batch that first exercises the
[post-batch gates](emulator-batch-2026-11-24.md#post-batch-ci-gates) from the
previous batch.

## Artifact

| Field | Value |
| --- | --- |
| Merge `main` | `8f6834507265f63238acc394613697ea5e72f22f` |
| Build run | [45387701644](https://github.com/levkeveli/Min3DStry/actions/runs/45387701644) |
| Artifact | `min3dstry.3dsx` |
| Bytes | `17,125,220` |
| SHA-256 | `6c4d25602a7da31d63b5d5ef7170f69d302f4ff1d10413300d124ae977d8fec9` |

The build logged `main_stack: bytes=2400 limit=4096`,
`audio_romfs: OK entries=9 bytes=647008`, and `artifact_bytes=17125220`. The
verify job reported `AZAHAR_PROVENANCE_OK` with the same SHA-256 as the build
output. The ROM is 32,224 bytes smaller than the previous batch's `630efbca`
artifact; parity, audio inventory, stack headroom and both-profile boot match,
so this is not the early-exit pattern that invalidated #569.

## Verification

CI, from the run above:

```text
[PASS] new3ds: Parity=0x53a483c7 | Audio=fallback_no_dsp | R:428 C:4 Sw:1 | FPS=58.89 | Sectors=0
[PASS] old3ds: Parity=0x53a483c7 | Audio=fallback_no_dsp | R:428 C:4 Sw:1 | FPS=59.51 | Sectors=0
AZAHAR_PROVENANCE_OK sha256=6c4d2560…fec9
```

Local replay of the downloaded artifact with the same pinned Azahar
(`2126.2 | HEAD-219d743`, Windows host):

```text
[PASS] new3ds: New 3DS XL (N3DS, 804 MHz, AppMem:96 MiB) | Parity=0x53a483c7 | FPS=60.23 (100.76%)
[PASS] old3ds: Original 3DS XL (O3DS, 268 MHz, AppMem:96 MiB) | Parity=0x53a483c7 | FPS=46.94 (81.87%)
```

Both profiles loaded nine clips (`647,008` bytes), resolved `wave.spawn` to clip
`8`, saved/reloaded storage, stayed at `R:428 C:4 Sw:1`, and used
`553,728 / 1,048,576` bytes of the runtime budget with `failures=0`. The
screenshots show the stock main menu, minimap and scene; `fallback_no_dsp` is
expected in Azahar. The Old 3DS FPS figure is lower than CI on this host; it is
still above the `>= 24 FPS` target.

## Tasks covered

| # | Task | PR | Result |
| --- | --- | --- | --- |
| 1 | Liquid Bridge / Bridge Conduit / Phase Conduit draw coverage | #613 | PASS — `LIQUID=21`, category assertions |
| 2 | Complete POWER family draw coverage | #613 | PASS — `POWER=26` |
| 3 | Unified smoke `runBuildingLayersJvm` registry gate | #613 | PASS — gate ran in smoke |
| 4 | Offline CTM 40s start and bounded retry | #613 | PASS — offline job first-attempt pass |
| 5 | Pin exact registry counts | #628 | PASS — counts asserted, not just printed |
| 6 | Document the registry gate and readiness timing | #629 | PASS — docs-only |
| 7 | Live weather, particles and puddle ledger | #631 | PASS — gauge `73.05%` |
| 8 | Post-batch CI evidence record | #636 | PASS — docs-only |
| 9 | This batch record | — | PASS — docs-only; host suite |
| 10 | Final batch validation (build + both profiles) | — | PASS — run `45387701644` + local replay |

## Registry and offline gates on merged code

The unified smoke on the merged branch head (`bb8f93e`, the tree merged as
`8f683450`) printed and asserted:

```text
UPSTREAM_STOCK_LIVE_TURRET_DRAW tested=29
UPSTREAM_STOCK_LIVE_POWER_DRAW tested=26
UPSTREAM_STOCK_LIVE_DISTRIBUTION_DRAW tested=28
UPSTREAM_STOCK_LIVE_LIQUID_DRAW tested=21
UPSTREAM_STOCK_LIVE_MATERIAL_DRAW tested=52
```

The offline Ground Zero job passed on its **first** attempt with the 40s CTM
start; no retry was consumed:

```text
[PASS] new3ds: saved=wave:2,copper:13,bytes:18278 reloaded=wave:2,copper:13
[PASS] old3ds: saved=wave:2,copper:14,bytes:18276 reloaded=wave:2,copper:14
```

The one-item difference between profiles is the known input-timing variance in
the replay, not a save-format difference; both saved and reloaded identical
values.

## Not covered

- Physical New or Original 3DS boot, DSP audio, thermal/FPS behavior.
- Cross-platform multiplayer.
- Gameplay content beyond the deterministic parity scenario and live smoke.
- The live build-UI palette and full campaign gameplay.

No hardware deployment or NetLoader probe was performed for this batch.
