# Physical Hardware Deployment and Log Streaming

Status: **Host-tested** (CLI command generation, SD staging + SHA-256 manifest, loopback TCP capture, and provenance log parsing tested in `./dev.sh test`); **Physical Old/New 3DS Hardware: not yet tested** (pending real console run).

## Purpose

Issue #6 provides a repeatable deployment and telemetry capture workflow in [`tools/device_deploy.py`](../../tools/device_deploy.py) (`./dev.sh device`) for sending `build/min3dstry.3dsx` to physical Original and New 3DS/2DS hardware and extracting `[MIN3DSTRY] HW:`, `[MIN3DSTRY] PARITY`, and `[MIN3DSTRY] FRAME` telemetry.

## Procedures

### 1. Wi-Fi Deployment & Log Streaming (`3dslink`)

1. Build the 3DS binary: `./dev.sh build`
2. Open Homebrew Launcher on the 3DS and press `Y` to start **NetLoader**.
3. Stream the `.3dsx` and collect `svcOutputDebugString` telemetry:
   ```bash
   ./dev.sh device deploy --ip <3DS_IP> --stream-logs --log-out build/device-logs/run.log
   ```
   You can also set `M3DS_DEVICE_IP=<3DS_IP>` instead of passing `--ip`, or inspect the generated command with `--dry-run`.

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

## Verification Status

- **Host unit tests (`tests/tools/test_device_deploy.py`):** **Host-tested** via `./dev.sh test` (`3dslink` command builder, SD staging + SHA-256 manifest, loopback TCP log capture, and provenance Markdown formatter).
- **Physical Original & New 3DS/2DS Hardware:** **Not yet tested** — real `3dslink` wireless deployment and console hardware telemetry must be captured and recorded before marking hardware-verified.
