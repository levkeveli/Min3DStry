# SD storage path contract

Status: **host-tested atomic storage + Azahar probe round-trip**. Settings/save payloads are still owned by upstream Arc/SaveIO; Java filesystem wiring remains open under [#41](https://github.com/levkeveli/Min3DStry/issues/41).

## Paths

All campaign persistence paths are rooted at `/3ds/min3dstry` and use the
upstream names:

| State | Path |
|---|---|
| Settings | `/3ds/min3dstry/settings.bin` |
| Settings backup | `/3ds/min3dstry/settings_backup.bin` |
| Settings backup directory | `/3ds/min3dstry/settings_backups` |
| Serpulo ground-zero sector | `/3ds/min3dstry/sector-serpulo-170.msav` |
| Erekir sector example | `/3ds/min3dstry/sector-erekir-64.msav` |

`include/platform/sd_paths.h` validates root/components, rejects absolute
names, traversal (`..`), separators in components and truncated buffers, and
builds the exact upstream file names. This is path plumbing, not a new save or
settings format.

## Runtime evidence

`main_3ds.c` logs:

```text
[MIN3DSTRY] STORAGE root=/3ds/min3dstry settings=/3ds/min3dstry/settings.bin sector=/3ds/min3dstry/sector-serpulo-170.msav
[MIN3DSTRY] STORAGE_ROUNDTRIP status=ok bytes=4 root=sdmc:/3ds/min3dstry
```

The round-trip writes, reads back and deletes a four-byte
`storage-probe.bin` through `sdmc:`; it never touches `settings.bin` or sector
saves. The Azahar verifier requires both lines on both models.

## Atomic storage

`include/platform/settings_store.h` stores opaque bytes:

- `m3ds_settings_store_write/read`: temp file + rename, with the previous
  `settings.bin` moved to `settings_backup.bin`; reads fall back to the backup.
- `m3ds_sector_store_write/read`: temp file + rename for
  `sector-<planet>-<id>.msav`.
- `m3ds_storage_probe_roundtrip`: target-filesystem probe with cleanup.

No content format is parsed or invented here.

## Tests

- `tests/platform/test_sd_paths.c`: settings/backup/backups/sector paths,
  trailing-slash roots, id formatting, traversal/absolute/separator/truncation
  rejection.
- `tests/platform/test_settings_store.c`: primary/backup settings round-trips,
  backup fallback, atomic sector write/read, probe round-trip and cleanup.
- `./dev.sh test` runs both tests.
- `./dev.sh emu-test` verifies STORAGE paths and the SD probe round-trip.

## Remaining work

- Route `Core.settings`/Arc `Fi` reads and writes to these paths on 3DS SDFS.
- Preserve upstream atomic/backup behavior and corrupted-store fallback.
- Integrate sector `.msav` slots and campaign/tech-tree UI.
- Verify full round-trips in Azahar and on physical Old/New 3DS.
