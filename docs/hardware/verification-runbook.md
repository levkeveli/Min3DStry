# Physical verification runbook

Status: **procedure**. Queued handoff cards: [hardware-test-queue.md](../hardware-test-queue.md). This page defines how a physical Old/New 3DS run becomes checked evidence.
It does not claim any hardware result by itself; the capability ledger only moves when a captured log
passes [`tools/hw_evidence.py`](../../tools/hw_evidence.py) and is attached to a PR.

Emulator-only validation batches are recorded separately:
[2026-11-23](emulator-batch-2026-11-23.md),
[2026-11-24](emulator-batch-2026-11-24.md) and
[2026-11-24 batch 2](emulator-batch-2026-11-24b.md) (ten tasks each, both
Azahar profiles). These records do not prove physical-hardware behavior.

Captured so far: [Original 3DS XL smoke](evidence/old3ds-xl-a4a9a96.md),
[New 3DS XL sector run](evidence/new3ds-xl-de3e249.md), and
[New 3DS XL combined run](evidence/new3ds-xl-a328c70.md). The `a328c70` run
passed 12/12 over ~10 minutes (132 `BENCH` windows, `fps_min=29.92`,
`fps_max=59.84`, `p95_max=33.50`) and the operator confirmed the scene,
pan/zoom, lighting, pause/resume, HUD and minimap. The five-minute soak and the
New 3DS `factory_demo` FPS target are verified. Still **not verified**: full
campaign gameplay FPS, the Old 3DS run (the operator has only the New 3DS, so
HWT-06 is blocked on Original 3DS hardware), upstream audio clips/music (the
operator heard the UI tone, but all seven `AUDIO_EVENT`s were `clip=-1`), and
the touch-label layout (the zones respond, but the visible legend is outside
them; issue #364).

## What a run can prove

| Evidence in the log | Capability it supports |
| --- | --- |
| `BUILD commit=<sha> dirty=0` | provenance for every row below (exact binary identity) |
| `HW:` tag, clock, L2, application memory, FIRM | `old-new-3ds-probe`, `memory-budget-runtime` |
| `STORAGE_ROUNDTRIP status=ok`, `SAVE_SLOT`, settings | `msav-map-save-compat`, `sd-storage` paths |
| `SECTOR_LOADER`/`SECTOR_ALLOC`/`SECTOR_TILE_STATE`/`SECTOR_PARITY` | embedded upstream `SaveIO` loader, `world-rows-bridge` |
| `PARITY ... hash=0x53a483c7` | `parity-test-harness` |
| `WORKERS ... match=1` | `threading-scheduling` |
| `FRAME ... drop=0` | draw/cull telemetry only; visual correctness and FPS need screenshots and a `BENCH` record |
| `BENCH fps=... p95_ms=...` | `>= 24 FPS` target (`physical-hardware-parity`) |
| `AUDIO backend=ndsp state=ready` | NDSP init and asset load only; audible output needs the HWT-04 listening report |
| Pad/touch interactions you perform | `touch-build-palette`, `hud-menus-minimap` |

Emulator output is **not** hardware evidence. `./dev.sh emu-test` stays the fast pre-flight.

## 1. Build and record provenance

Use a build from a merged `main` commit (CI `3DS full build` artifact, or a local `./dev.sh build`).
Record the full SHA and the `.3dsx` SHA-256; the ingest tool hashes the ROM for you:

```bash
sha256sum build/min3dstry.3dsx
git rev-parse HEAD
```

The binary logs the commit it was built from (`BUILD commit=<sha> dirty=<0|1>`). The Makefile prefers
`M3DS_BUILD_COMMIT` (exported by `dev.sh build`), then `git rev-parse`, then a git-free reader
(`tools/git_head_sha.sh`) so container builds without a git package still get a real SHA. A build from
a dirty tree logs `dirty=1` and cannot be used as evidence.

For audio coverage build with the upstream assets:

```bash
UPSTREAM_MINDUSTRY=/path/to/Mindustry ./dev.sh build
```

## 2. Prepare the SD card

```text
sd:/3ds/min3dstry/min3dstry.3dsx      (optional if deploying over NetLoader)
sd:/3ds/min3dstry/min3dstry.smdh      (optional)
sd:/3ds/min3dstry/sector-serpulo-170.msav   (required for the canonical scenario)
```

`sector-serpulo-170.msav` is [`tests/fixtures/glacier-v13.msav`](../../tests/fixtures/glacier-v13.msav),
a real v160.8 save. Without it the loader logs `loaded=0` and the canonical checks cannot pass.
Keep `sd:/3ds/dspfirm.cdc` present if you want NDSP audio rather than the fallback backend.

## 3. Deploy and capture

1. Homebrew Launcher → **Y** (NetLoader) and note the console IP.
2. From the repo:

```bash
./dev.sh device deploy \
  --rom build/min3dstry.3dsx \
  --ip <3DS_IP> \
  --stream-logs \
  --log-out build/device-logs/hw-$(date +%Y%m%d-%H%M).log
```

`deploy` passes `m3ds-stream-logs`, so the app connects back to `3dslink` on TCP 17491 and streams its
records. Add devkitPro to `PATH` (or set `THREESLINK_BIN`) if `3dslink` is not found.

SD-only launches do not stream; they only give screenshots. Prefer NetLoader for evidence runs.

## 4. Exercise the app

- Boot: both screens render; bottom screen shows the console/HUD.
- Camera: D-pad pan, L/R zoom, Y reset, touch arrows and ±.
- Lighting: B toggles.
- Pause: A pauses/resumes; SELECT opens the menu; D-pad/touch select; A confirms; SELECT closes.
- Touch: bottom-screen palette rows.
- Audio build: UI click, turret shot, block place/break audible; X mutes.
- Stability: 5+ minutes, then START to quit.

Note anything that crashes, hangs, glitches, or lags; the log's last line is the useful clue.

## 5. Ingest the log

```bash
python3 tools/hw_evidence.py \
  --input build/device-logs/hw-YYYYMMDD-HHMM.log \
  --rom build/min3dstry.3dsx \
  --commit "$(git rev-parse HEAD)" \
  --target "New 3DS XL" \
  --profile new3ds \
  --scenario sector170 \
  --strict \
  --out docs/hardware/evidence/new3ds-<short-sha>.md
```

Use `--profile old3ds` on an Original 3DS/2DS. `--scenario smoke` drops the save-specific checks for a
console without the fixture. `--format json` emits the same data for automation; without `--out` the
record goes to stdout.

The tool exits `0` only when every required check matches and `1` otherwise (with `--strict`); `2`
means the log or ROM path is missing. The logged `BUILD` commit must match `--commit`, and `dirty=1`
fails: evidence has to come from a clean checkout of the commit you name. Builds older than the
provenance change have no `BUILD` record and need to be rebuilt. The record also carries the ROM
SHA-256 and every raw captured line, so a reviewer can re-run the tool on the same file.

## 6. Expected canonical values

| Record | New 3DS | Original 3DS |
| --- | --- | --- |
| `BUILD` | `commit=<12-char sha> dirty=0` | same |
| `HW:` tag / AppMem | `N3DS`, `124 MiB` | `O3DS`, `64 MiB` |
| `STORAGE_ROUNDTRIP` | `status=ok` | `status=ok` |
| `SAVE_SLOT` | `present=1 valid=1 version=13` | same |
| `SECTOR_LOADER` | `available=1 loaded=1 tiles=37500 buildings=2` | same |
| `SECTOR_ALLOC` | `hash=783fbd80e415dcd7` | same |
| `SECTOR_TILE_STATE` | `linked=2 hash=e35397041df8e5fe` | same |
| `SECTOR_PARITY` | `hash=3a375fd4f81c5680` | same |
| `PARITY` | `scenario=starter_copper_defense_v1 hash=0x53a483c7` | same |
| `WORKERS` | `match=1 checksum=0x4bb2b98d` | same |
| `FRAME` | `drop=0` | `drop=0` |
| `AUDIO` | `state=ready` with dspfirm | `state=ready` with dspfirm |

`AppMem` values come from the detected application-memory region, not the installed RAM. The match is
exact on purpose: Azahar reports `96 MiB` for both profiles, so an emulator capture can never pass as
hardware evidence. A physical console reporting a different value is a finding to investigate, not
something to relabel.

## 7. Attach and promote

Open a PR (or comment on #43) with:

- the generated `docs/hardware/evidence/<target>-<sha>.md`,
- the raw captured log under `docs/hardware/evidence/` or linked in the PR,
- the console details (model, region, firmware, Luma version, SD, dspfirm present),
- any screenshots.

Then update `docs/port-capabilities.json` for the specific capabilities the log proves, and regenerate
the gauge with `python3 tools/port_progress.py --write-svg`. Keep the evidence file in the same PR so
the stage change is reviewable.

## Limitations to state in the report

- Run results apply to the exact commit and `.3dsx` SHA-256 you captured.
- Temperature/thermal throttling and SD quality affect frame numbers; note them.
- `fallback_no_dsp` means the console lacks `sd:/3ds/dspfirm.cdc`; it is not a code regression.
- Cross-platform multiplayer is not verifiable until the live client networking path lands.
- One console per generation is enough for parity, but not for multiplayer or region coverage.
