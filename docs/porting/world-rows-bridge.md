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
flat hash        783fbd80e415dcd7
tile-state hash  e35397041df8e5fe
building hash    3a375fd4f81c5680
building 0       b529451197a6e504
building 1       ebc4778e833eb8e5
```

This proves the bridge preserves the decoded world exactly. The remaining step
is to replace the native parser in `main_3ds.c` with an upstream producer that
fills these rows in-process.
