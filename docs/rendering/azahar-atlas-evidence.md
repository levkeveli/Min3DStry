# Azahar sprite-atlas evidence

Status: recorded from a local `./dev.sh emu-test` run. This covers on-console
atlas loading and textured rendering in Azahar; physical hardware frame time is
still pending.

## Build

```sh
./dev.sh build
./dev.sh emu-test
```

The Makefile builds the fixture atlas with `tools/build_sprite_atlas.py` and
packs `sprites.t3x` into RomFS.

## Observed results

Both `new3ds` and `old3ds` modes passed:

```text
[MIN3DSTRY] ATLAS loaded
[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 hash=0x53a483c7 ...
```

- Report field `atlas_loaded: true` for both models.
- Top-screen screenshots contain 23 distinct colors with textured terrain and
  the minimap overlay.
- Parity remained `0x53a483c7`.
- New 3DS: `59.85 FPS`; Old 3DS: `59.91 FPS` in the recorded run.
- `audio=fallback_no_dsp` as expected without DSP firmware.

## Interpretation

- `sprites.t3x` loads successfully from RomFS on both emulated models.
- The sprite atlas is used by the textured scene path, not only generated on
  the host.
- Frame-time and physical Old/New 3DS verification remain hardware items.
