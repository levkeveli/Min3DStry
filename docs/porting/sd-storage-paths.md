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
[MIN3DSTRY] SETTINGS primary=1 backup=0 primary_bytes=204 backup_bytes=0
```

The round-trip writes, reads back and deletes a four-byte
`storage-probe.bin` through `sdmc:`; it never touches `settings.bin` or sector
saves. The `SETTINGS` line reports whether `settings.bin` and
`settings_backup.bin` exist and their byte sizes without parsing either file.
The Azahar verifier requires the STORAGE lines on both models.

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
[MIN3DSTRY] SECTOR_INFLATE planet=serpulo id=170 raw=11072 bytes=45955 limit=8388608 status=ok
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

The app next allocates flat floor/overlay/block/flag arrays for the whole map,
fills them from the validated records, hashes the arrays, and frees them:

```text
[MIN3DSTRY] SECTOR_ALLOC tiles=37500 bytes=1312830 hash=783fbd80e415dcd7 status=ok
```

Both Azahar profiles produce the same allocation hash.

Center-building entity chunks are copied into raw building records (tile index,
revision, payload) and linked from the tile array. The common upstream
`Building.readBase` fields are decoded: health, rotation, team, enabled flag,
module mask, item count, and the remaining building-specific bytes:

```text
[MIN3DSTRY] SECTOR_BUILDINGS count=2 bytes=44 team=1 rot=0 health=6000 enabled=1 modules=9 items=0 item0=0:0 power=0:0.0 liquid=0:0:0.00 extra=8 partial=0 trunc=0 hash=4d6f71eda0848e18 status=ok
```

Both profiles produce the same building hash. The item module yields up to eight
stacks, and the power/liquid modules yield link count/status and the first
liquid id/amount. The validated world arrays stay resident and the HUD shows:

```text
World: 150x250 build=2
```

A combined per-tile state array now carries floor/overlay/block ids, `x`/`y`,
flags, the building index, and a direct linked building pointer:

```text
[MIN3DSTRY] SECTOR_TILE_STATE tiles=37500 buildings=2 linked=2 hash=e35397041df8e5fe status=ok
```

A 24×16 top-screen preview now samples the resident tile arrays and draws one
rect per cell (384 rects/frame), while the HUD shows `World: 150x250 build=2`.
This is a runtime visualization of the loaded save, not an upstream minimap.

The same save decoded by upstream v160.8 `SaveIO` on the JVM
(`-Pprobe=save-decode`) and by the native reader must produce the same
building-state hash:

```text
UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
SECTOR_PARITY buildings=2 hash=3a375fd4f81c5680 status=ok
```

The native reader is verification-only and must be replaced by the upstream
`SaveIO`/`MapIO` loader before gameplay can depend on it. Timer/disabler modules
and the building-specific payload are preserved but not decoded yet.

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
Sectors: 2
 erekir-64 v 16B
 serpulo-170 Glacier w1
```

The HUD lists up to four catalog rows.

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
  backup fallback, settings status sizes, atomic sector write/read, probe
  round-trip and cleanup.
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
