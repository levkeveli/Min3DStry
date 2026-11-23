# Upstream `SaveIO` save-state parity

`UpstreamWorldLoadProbe` now reports the decoded building state after loading a
v13 save. The hash sequence matches `MSAV_HASH_SEED`/`mix_hash` in
`src/platform/msav_world.c`, so the upstream loader and the native verification
reader are compared on the same fields: tile position, block id, team,
rotation, health, enabled, item stacks/total, power links/status, and liquids.

```text
UPSTREAM_SAVE_BUILDING x=81 y=26 block=341 team=1 rot=0 health=6000 enabled=1 items=0 item0=-1:0 links=0 status=0.0 liquids=0 liquid0=0:0.00 hash=b529451197a6e504
UPSTREAM_SAVE_BUILDING x=81 y=224 block=341 team=2 rot=0 health=6000 enabled=1 items=0 item0=-1:0 links=0 status=0.0 liquids=0 liquid0=0:0.00 hash=ebc4778e833eb8e5
UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
```

The probe asserts those values for the Glacier v13 fixture, so a divergence
fails the probe itself on the JVM and on ARM11.

## Evidence

Verified with the real `glacier-v13.msav` and pinned v160.8 `SaveIO`:

```text
JVM:     UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
New 3DS: UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
Old 3DS: UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
```

The native `SECTOR_PARITY` output is
`buildings=2 hash=3a375fd4f81c5680` on both Azahar profiles.

## Reproduce

```sh
gradle -Pprobe=world-save \
    -PupstreamCheckout=/path/to/Mindustry \
    -PreferenceJar=/path/to/server-release.jar \
    -ParcCheckout=/path/to/Arc \
    runProbeJvm "-PprobeArgs=/path/to/glacier.msav /path/to/glacier-v13.msav"
```

Build the ARM11 probe with `-Pprobe=world-save generateC` and
`arm11/build.sh`, then place `glacier.msav` and `glacier-v13.msav` in
`/3ds/min3dstry` on the emulated/real SD card.

The native reader remains verification-only; the next step is to make the
upstream loader own the live world instead of comparing two decoders.
## Embedded map assets

The offline session uses the stock SHA-256 asset keys under the
`assetCache` directory alongside saves and settings. When an imported map
embeds a data asset, the original save reader stores it in this cache. A
normal subsequent save preserves the stock hash reference and a new
session can verify and reopen the cached bytes.

`tools/stock_embedded_map_assets.py` writes a Glacier fixture through the
unmodified v160.8 Java server. `runStockEmbeddedAssetJvm` with
`-Pprobe=singleplayer` loads the fixture, saves it and checks a new cache
instance against the original image. Run the fixture tool again with
`--readback` to verify that stock Java reopens the port save and resolves
the same image. Rendering imported images requires separate verification.
