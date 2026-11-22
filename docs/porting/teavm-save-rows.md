# TeaVM `@Import` world-row producer

`UpstreamSaveRowsProbe` (`-Pprobe=save-rows`) loads the real v13 save through
the pinned v160.8 `SaveIO`, then streams every tile and building into the
native row arrays with TeaVM `@Import` calls:

```c
m3ds_save_rows_begin(width, height, tiles, buildings)
m3ds_save_rows_tile(...)
m3ds_save_rows_building(...)
m3ds_save_rows_end()
```

`m3ds_save_rows_end` calls `m3ds_world_rows_alloc` and emits the native hashes:

```text
UPSTREAM_ROWS flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680 building=9a691300c548b8fb buildings=2
UPSTREAM_SAVE_ROWS status=0
```

The flat, tile-state, and save-state hashes match the native reader exactly.
`building` differs because the row stream intentionally omits raw entity
payload bytes; the decoded per-building state hash is carried in `save`.

Verified on Azahar with the real `glacier-v13.msav`:

```text
New 3DS: UPSTREAM_MEMORY appHeap=100663296 linearHeap=4194304 stack=262144
         UPSTREAM_ROWS flat=783fbd80e415dcd7 tile=e35397041df8e5fe save=3a375fd4f81c5680 building=9a691300c548b8fb buildings=2
         UPSTREAM_SAVE_ROWS status=0
Old 3DS: UPSTREAM_MEMORY appHeap=84443136 linearHeap=4194304 stack=262144
         same rows/status
```

## Reproduce

```sh
gradle -Pprobe=save-rows \
    -PupstreamCheckout=/path/to/Mindustry \
    -PreferenceJar=/path/to/server-release.jar \
    -ParcCheckout=/path/to/Arc generateC

bash arm11/build.sh build/generated/teavm/c build/probe-save-rows --save-rows
```

Install `glacier-v13.msav` at `/3ds/min3dstry` and boot the probe in Azahar with
`Debug.Emulated:Debug` logging enabled.

The remaining step is to call this producer from the main app's startup path and
remove `m3ds_msav_alloc_world` from the runtime path.
