# Physical Hardware Deployment and Log Streaming

Status: **Host-tested** for SD staging and log tooling. **Hardware-tested on Original and New 3DS XL** for Wi-Fi deployment and log streaming.

## Purpose

Issue #6 provides a repeatable deployment and telemetry capture workflow in [`tools/device_deploy.py`](../../tools/device_deploy.py) (`./dev.sh device`) for sending `build/min3dstry.3dsx` to physical Original and New 3DS/2DS hardware and extracting `[MIN3DSTRY] HW:`, `[MIN3DSTRY] PARITY`, and `[MIN3DSTRY] FRAME` telemetry.

## Procedures

### 1. Wi-Fi Deployment & Log Streaming (`3dslink`)

1. Build the 3DS binary: `./dev.sh build`
2. Open Homebrew Launcher on the 3DS and press `Y` to start **NetLoader**.
3. Send the `.3dsx` and collect the diagnostic records over TCP:
   ```bash
   ./dev.sh device deploy --ip <3DS_IP> --stream-logs --log-out build/device-logs/run.log
   ```
   You can also set `M3DS_DEVICE_IP=<3DS_IP>` instead of passing `--ip`, or inspect the generated command with `--dry-run`.
   Streaming uses `3dslink -s` and TCP port 17491 on the PC. The app keeps rendering the bottom-screen console and also emits the records to the debugger for Azahar. `deploy` mirrors the output line by line into `--log-out` (flushed as it arrives), and `--stream-seconds N` stops the server automatically (exit 0 for a deliberate bounded stop, even with continuous output). Pressing START closes the app, but `3dslink -s` keeps listening, so use the bounded option (or Ctrl+C) to finish the log.
   The deploy command passes `m3ds-stream-logs` after the ROM path. Homebrew Launcher documents that trailing arguments are forwarded to the launched app, and `3dslink` 0.6.3 serializes them after the app path. [Homebrew Launcher Netloader](https://github.com/devkitPro/3ds-hbmenu#netloader) · [3dslink 0.6.3 argument handling](https://github.com/devkitPro/3dslink/blob/c2df7362a156282bc2fb55370740ad670ca5a8a5/host/src/main.c#L599-L606)

### Direct log capture without `3dslink -s`

For long sessions use [`tools/hw_capture.py`](../../tools/hw_capture.py): it binds TCP 17491, runs
`3dslink` **without** `-s` (upload only) with the `m3ds-stream-logs` argument, and writes the app's raw
stream to disk as it arrives. This avoids `3dslink`'s stdout buffering and never probes NetLoader with a
bare TCP connect — hbmenu expects the 3dslink protocol and reports `[namelen:...]` on a connect-and-close.

```bash
python3 tools/hw_capture.py --rom build/min3dstry.3dsx --ip 192.168.1.122 \
  --seconds 330 --log-out build/device-logs/hw-<commit>-<timestamp>.log
```

`3dslink` is resolved from `--3dslink`, `THREEDSLINK_BIN`/`THREESLINK_BIN`, `PATH`, then a local devkitPro
install (`DEVKITPRO`, `~/devkitPro`, `C:/devkitPro`). Pass `--3dslink` or set the env var if the tool lives
elsewhere.

### 2. SD / FTPD Staging with SHA-256 Verification

Copy `build/min3dstry.3dsx` and `build/min3dstry.smdh` to an SD card mount or staging folder (`/3ds/min3dstry/`):
```bash
./dev.sh device sd-stage --sd-root /path/to/sd --commit "$(git rev-parse --short HEAD)"
```
This writes `min3dstry.sha256.json` alongside the staged files so transfers can be verified byte-for-byte.

### 3. Standalone TCP Log Capture & Provenance Formatting

Listen on a host TCP port for streamed diagnostic output and format a Markdown snippet for verification docs:
```bash
./dev.sh device capture-log --port 4000 --out build/device-logs/capture.log
./dev.sh device parse-log --input build/device-logs/capture.log --commit "$(git rev-parse --short HEAD)" --target "New 3DS XL"
```

### 4. Hardware Budget & Performance Benchmark Report (`#43`)

Validate streamed Original or New 3DS logs against the target hardware model (`O3DS` `64 MiB` vs `N3DS` `124 MiB`), the `>= 24 FPS` / frame-time target (`[MIN3DSTRY] BENCH fps=... p95_ms=...`), `UPSTREAM_MEMORY` / `[MIN3DSTRY] MEM` bounds, and parity trace hash:
```bash
./dev.sh device benchmark-report --input build/device-logs/capture.log \
  --commit "$(git rev-parse --short HEAD)" --target "Original 3DS" \
  --profile old3ds --min-fps 24 --expected-hash 0x53a483c7 --strict
```
Use `--format json` for structured metrics or `--load-bytes <elf_load_bytes>` to enforce the full `LOAD + linearHeap + stack + 24 MiB` managed-heap budget against `64 MiB` / `124 MiB`.

### 5. Checked Hardware Evidence (`./dev.sh device evidence`)

Turn a captured log into a reviewed record with [`tools/hw_evidence.py`](../../tools/hw_evidence.py):
it verifies the canonical sector-170 values (loader/tile/save hashes, simulation parity, worker
checksum, frame drops, memory budget), checks the model/app-memory profile, matches the app's
`[MIN3DSTRY] BUILD commit=<sha> dirty=<0|1>` record against `--commit` (a dirty or mismatched build
fails), hashes the `.3dsx`, and emits Markdown or JSON. `--strict` exits non-zero on any required
mismatch.

```bash
./dev.sh device evidence --input build/device-logs/capture.log \
  --rom build/min3dstry.3dsx --commit "$(git rev-parse HEAD)" \
  --target "New 3DS XL" --profile new3ds --strict \
  --out docs/hardware/evidence/new3ds-$(git rev-parse --short HEAD).md
```

The full procedure, expected values per generation, and how a result promotes a capability live in
[the physical verification runbook](verification-runbook.md). Emulator output never counts as hardware
evidence; `./dev.sh emu-test` stays the pre-flight.

## Verification Status

- **Host unit tests (`tests/tools/test_device_deploy.py`):** **Host-tested** via `./dev.sh test` (`3dslink` command builder, SD staging + SHA-256 manifest, loopback TCP log capture, and provenance Markdown formatter).
- **Hardware evidence ingest (`tests/tools/test_hw_evidence.py`):** **Host-tested** for valid New/Old profiles, corrupt-save failure, wrong hashes, frame drops, missing records, strict exit codes, and the `device evidence` wrapper.
- **Build provenance (`tests/tools/test_git_head_sha.py`):** the app logs `BUILD commit=<sha> dirty=<0|1>`; `dev.sh build` exports the short SHA and the Makefile falls back to `tools/git_head_sha.sh` when a container has no git binary. Host-tested for loose refs, packed refs, detached HEAD, and linked worktrees.
- **Physical New 3DS XL:** On 2026-11-22, clean build `de3e249` was sent via `3dslink` 0.6.3 over Wi-Fi. The run logged the hardware profile (804 MHz, L2 enabled, 124 MiB application memory, FIRM 2.58.0), the canonical 240-tick scenario hash (`0x53a483c7`), the sector loader hashes, and 2/4-worker checksums. The raw log and strict evidence report are committed beside this page. [New 3DS XL evidence](evidence/new3ds-xl-de3e249.md)
- **Earlier New 3DS run:** build `07e94ed` (2026-11-20) also reported the N3DS profile and parity hash, but no raw log was retained, so it is historical context rather than ingestible evidence.
- **Physical Original 3DS XL:** On 2026-11-22, clean main build `a4a9a96` booted after a Wi-Fi transfer and streamed telemetry that passed all seven smoke checks. The operator confirmed START, camera panning, and audible sound. The run covered the default hardware harness. [Original 3DS XL evidence](evidence/old3ds-xl-a4a9a96.md)
