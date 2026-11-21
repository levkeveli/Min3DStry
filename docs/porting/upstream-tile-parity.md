# Upstream `MapIO`/`SaveIO` tile-state parity

The upstream v160.8 world-save probe now hashes every loaded tile with the same
`M3dsTileState` byte layout and FNV-1a sequence used by
`src/platform/msav_world.c`: floor, overlay, block, x, y, flags, three zero
padding bytes, and the building index (`0xffffffff` when absent).

```text
UPSTREAM_TILE_STATE tiles=37500 buildings=2 hash=e35397041df8e5fe
```

This matches the native `SECTOR_TILE_STATE` row exactly:

```text
SECTOR_TILE_STATE tiles=37500 buildings=2 linked=2 hash=e35397041df8e5fe status=ok
```

Verified on the JVM with the real `glacier-v13.msav`. The probe asserts the
values in `UpstreamWorldLoadProbe.reportTileState`, so any divergence fails the
probe.

## Reproduce

```sh
gradle -Pprobe=world-save \
    -PupstreamCheckout=/path/to/Mindustry \
    -PreferenceJar=/path/to/server-release.jar \
    -ParcCheckout=/path/to/Arc \
    runProbeJvm "-PprobeArgs=/path/to/glacier.msav /path/to/glacier-v13.msav"
```

The remaining step is to feed these upstream tile rows into the live app's
world arrays and remove the native parser from the runtime path.
