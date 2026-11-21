# SD storage path contract

Status: **host-tested atomic storage + Azahar save-slot/prefix and Java filesystem verification**. Settings/save payloads are still owned by upstream Arc/SaveIO; the live campaign settings/UI flow remains open under [#41](https://github.com/levkeveli/Min3DStry/issues/41).

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

## Save-slot header probe

`include/platform/save_slot.h` inspects the upstream `.msav` container without
rewriting it. Upstream `SaveIO` wraps the `MSAV` + version stream in zlib, so
the probe reports three cases:

- raw `MSAV`: `valid=true` plus the big-endian version (upstream
  `DataOutputStream.writeInt`);
- zlib wrapper (`0x78 0x9c` and a valid zlib header check): `compressed=true`;
  the first eight decoded bytes are checked, so the `MSAV` magic and version are
  reported without inflating the whole world;
- anything else: `present=true, valid=false`.

`main_3ds.c` probes `/3ds/min3dstry/sector-serpulo-170.msav` once at startup and
logs:

```text
[MIN3DSTRY] SAVE_SLOT present=1 valid=1 compressed=1 version=13 bytes=11072 path=sdmc:/3ds/min3dstry/...
[MIN3DSTRY] SAVE_META map=Glacier wave=1
```

The bottom HUD shows `Save: v13 <bytes>B` for a raw header or a decoded zlib
wrapper, `Save: zlib <bytes>B` when the wrapper is valid but its prefix cannot
be decoded, `Save: invalid <bytes>B`, or `Save: none`, plus
`Map: <name> wave=<n>` when the upstream metadata map parses.

### Compressed header prefix

`include/platform/msav_inflate.h` decodes only the prefix of the zlib stream
(RFC 1950/1951), so the `MSAV` magic, big-endian version and the early metadata
string map (`mapname`, `wave`) are available without inflating a whole world.
It handles stored, fixed-Huffman and dynamic-Huffman DEFLATE blocks and stops
as soon as the requested prefix is produced. Malformed headers, FDICT streams,
and truncated input fail closed.

## SD sector catalog

`include/platform/sector_catalog.h` scans `/3ds/min3dstry` for upstream
`sector-<planet>-<id>.msav` names and probes each container with the save-slot
header logic. Entries are sorted by planet and sector id, duplicate names are
not invented, malformed names and unrelated files are ignored, and the scan
never writes or deletes anything. A missing directory yields an empty catalog.
When the `.msav` metadata prefix parses, the entry also carries the upstream
`mapname` and `wave`.

Startup logs and HUD:

```text
[MIN3DSTRY] SECTOR_CATALOG count=1
[MIN3DSTRY] SECTOR_SLOT planet=serpulo id=170 valid=1 compressed=1 bytes=11072 map=Glacier wave=1
```

```text
Sectors: 1
Sector serpulo-170 Glacier w1
```

The list is catalog plumbing, not campaign loading: selecting, launching and
capturing sectors still needs the live campaign UI.

## TeaVM Java filesystem

The ARM11 `file-metadata` probe exercises `java.io.File`,
`RandomAccessFile` and `File.renameTo` through the native TeaVM file adapter.
`m3ds_file.c` now implements the required `teavm_file_rename` entry point by
renaming on the staged filesystem and returning TeaVM's boolean result. Both
Azahar profiles report:

```text
UPSTREAM_FILE_METADATA size=8550 header=789cbd3a last=108 seek=ok paths=ok
UPSTREAM_FILE_RENAME bytes=4 rename=ok
```

The probe renames a temporary four-byte file in `/3ds/min3dstry/tmp`, verifies
that the source is gone, the target has the expected length and content, and
then removes it. This is the entry point used by upstream save/settings backup
rotation; payload formats remain upstream-owned.

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
- `tests/platform/test_sector_catalog.c`: sorted SD catalog, capacity limits,
  malformed names, unrelated files, raw/zlib headers and missing directories.
- `./dev.sh test` runs both tests.
- `./dev.sh emu-test --expect-sector-catalog 2` verifies the staged sector
  catalog plus STORAGE paths and the SD probe round-trip.

## Remaining work

- Route the live `Core.settings`/Arc `Fi` instance to these paths on 3DS SDFS.
- Preserve upstream atomic/backup behavior and corrupted-store fallback.
- Integrate sector `.msav` slots and campaign/tech-tree UI.
- Verify full round-trips in Azahar and on physical Old/New 3DS.
