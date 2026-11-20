# Sector progression prototype

Status: **host-tested design prototype**. Not integrated with the 3DS build or
the upstream `.msav` save format yet. Issue: [#41](https://github.com/levkeveli/Min3DStry/issues/41).

## Scope

This slice defines the state model and failure rules for campaign sector
unlocks and tech research before the upstream world/save foundation is ready.
It deliberately uses a small original fixture, not a claim of upstream campaign
parity.

| Sector | Prerequisite | Copper cost | Initial state |
|---|---|---:|---|
| 1 `Landing Site` | none | 0 | unlocked |
| 2 `Copper Basin` | sector 1 completed | 40 | locked |
| 3 `Frozen Outpost` | sector 2 completed | 80 | locked |

| Tech | Prerequisite | Copper cost |
|---|---|---:|
| 1 `Conveyors` | sector 1 completed | 30 |
| 2 `Drills` | sector 2 completed | 60 |

Rules:

- Completing a sector requires it to be unlocked, its prerequisite sector to be
  completed and enough copper; completion deducts the cost and unlocks direct
  dependants.
- Research requires its prerequisite sector to be completed and enough copper;
  it deducts the cost and is idempotent (`already`).
- Locked/invalid/insufficient operations change neither copper nor state.
- Copper addition rejects `uint32` overflow.

## Save format v1

`m3ds_progress_serialize` writes a deterministic little-endian buffer; this is
an internal prototype format, **not** a `.msav` or upstream-compatible save.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `M3PR` |
| 4 | 2 | format version (`1`) |
| 6 | 2 | sector count |
| 8 | 2 | tech count |
| 10 | 2 | reserved (0) |
| 12 | 4 | copper |
| 16 | 12 × sectors | id, prerequisite, cost, flags, reserved |
| … | 12 × tech | id, prerequisite sector, cost, flags, reserved |
| end | 4 | FNV-1a checksum over preceding bytes |

`m3ds_progress_deserialize` validates magic, version, counts, exact size,
checksum, flag bits, duplicate/self prerequisites and dangling prerequisite
references before replacing the destination state. Invalid input returns
`invalid`/`io` and leaves the destination untouched.

## Upstream adaptation

Later slices must map the real pinned v160.8 structures rather than this
fixture:

- `core/src/mindustry/game/Sector.java` and `SectorPreset` for sector state,
  difficulty and capture rules.
- `mindustry.content.tech.TechTree` / tech node content for prerequisites and
  research costs.
- `mindustry.io.SaveIO` and the versioned save readers/writers for persistent
  storage. This prototype's `M3PR` buffer must not be presented as `.msav`
  compatibility.

## Verification

- `./dev.sh test` compiles and runs `tests/sim/test_sector_progress.c` with
  `-Wall -Wextra -Werror`; all assertions pass.
- Covered: initial lock state, sector prerequisites, copper deduction,
  insufficient funds, duplicate completion/research, research gates, overflow,
  null guards, serialization round-trip, deterministic bytes, and rejection of
  bad magic/version/checksum/truncated/oversized input.

Limitations:

- No 3DS UI, upstream sector content, real save integration, `.msav`
  compatibility, cross-platform campaign testing or physical hardware evidence.
- The fixture is original prototype data; upstream names/costs/tech ordering
  must be audited before claiming parity.
