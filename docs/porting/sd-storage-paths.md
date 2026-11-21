# SD storage path contract

Status: **host-tested atomic storage + Azahar save-slot/prefix verification**. Settings/save payloads are still owned by upstream Arc/SaveIO; Java filesystem wiring remains open under [#41](https://github.com/levkeveli/Min3DStry/issues/41).

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

When the primary slot is missing or invalid, the probe falls back to
`<name>-backup.msav`, matching `SaveIO.isSaveValid`/`backupFileFor`. This is
the same backup name upstream uses for interrupted or corrupt saves.

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

`include/platform/msav_inflate.h` decodes the prefix of the zlib stream
(RFC 1950/1951), so the `MSAV` magic, big-endian version and the early metadata
string map (`mapname`, `wave`) are available without inflating a whole world.
It handles stored, fixed-Huffman and dynamic-Huffman DEFLATE blocks and stops
as soon as the requested prefix is produced. Malformed headers, FDICT streams,
and truncated input fail closed.

The app also inflates the complete first sector slot into a 64 KiB buffer and
logs:

```text
[MIN3DSTRY] SECTOR_INFLATE planet=serpulo id=170 raw=11072 bytes=45955 status=ok
```

This proves the full compressed container is readable and inflatable on ARM11.
`include/platform/msav_world.h` then walks the region lengths and reads the
world dimensions from the map region:

```text
[MIN3DSTRY] SECTOR_WORLD version=13 width=150 height=250 status=ok
```

World parsing/loading is still separate work; no world or tile buffer is
allocated. The map region's floor/block records and entity chunk lengths are
walked and validated, and the map bytes are hashed:

```text
[MIN3DSTRY] SECTOR_TILES floors=37500 blocks=2768 data=0 entities=2 map=37500 entity_region=26 hash=bec356915bd24165
```

Both Azahar profiles produce the same hash. Entity bytes are bounds-checked but
not decoded. The entity region is also walked: mapping entries, team counts,
team-block counts and world entity chunk headers are validated and hashed.

```text
[MIN3DSTRY] SECTOR_ENTITIES mapping=0 teams=2 team_blocks=0 records=0 partial=0 hash=33a31f23a5cfb868
```

Saves with non-empty team-block payloads are marked `partial=1` because their
TypeIO configs require the content mapping.

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

- Route `Core.settings`/Arc `Fi` reads and writes to these paths on 3DS SDFS.
- Preserve upstream atomic/backup behavior and corrupted-store fallback.
- Integrate sector `.msav` slots and campaign/tech-tree UI.
- Verify full round-trips in Azahar and on physical Old/New 3DS.
