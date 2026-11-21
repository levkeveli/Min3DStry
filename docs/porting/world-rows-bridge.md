# World rows bridge

`include/platform/world_rows.h` defines the neutral row stream that the upstream
v160.8 `SaveIO`/`MapIO` loader will fill:

- `M3dsWorldTileRow`: floor, overlay, block, x/y, flags, building index.
- `M3dsWorldBuildingRow`: tile index, block, team, rotation, enabled, modules,
  health, item stacks, power links/status, first liquid, extra bytes, and the
  upstream per-building state hash.

`m3ds_world_rows_alloc` validates and copies those rows into the resident
`M3dsWorldTiles` arrays, computes the same flat-array, tile-state, and
building-state hashes as `msav_world.c`, and does not parse `.msav` bytes.

The host test decodes the real `tests/fixtures/glacier-v13.msav`, converts the
result to rows, rebuilds the world through the bridge, and asserts:

```text
flat hash         783fbd80e415dcd7
tile-state hash   e35397041df8e5fe
save-state hash   3a375fd4f81c5680
building 0        b529451197a6e504
building 1        ebc4778e833eb8e5
```

`M3dsWorldTiles.building_hash` stays zero for bridged worlds because the row
stream does not carry raw entity payload bytes; `save_state_hash` is the
decoded-building aggregate and matches `SECTOR_PARITY`.

This proves the bridge preserves the decoded world exactly. The remaining step
is to replace the native parser in `main_3ds.c` with an upstream producer that
fills these rows in-process.
