# Azahar audio evidence

Status: recorded from a local `./dev.sh emu-test` run on the ROMFS audio build.
This is emulator evidence for loading, event routing and fallback lifecycle. It
does not claim audible output.

## Build

```sh
UPSTREAM_MINDUSTRY=/path/to/pinned/Mindustry ./dev.sh build
./dev.sh emu-test
```

Pinned upstream commit:
`19a0f40d44514f4802a8b79770456e7e32e34da0`.

Azahar: `2126.2`.

## Observed results

Both `new3ds` and `old3ds` modes passed:

```text
[MIN3DSTRY] AUDIO backend=ndsp state=fallback_no_dsp rc=0xd880a7fa rate=22050 voices=0/8 clips=10
[MIN3DSTRY] AUDIO_ROMFS status=ok clips=9 bytes=647008 ui_clip=6
[MIN3DSTRY] AUDIO_EVENT conveyor.loop clip=2 voice=0
[MIN3DSTRY] AUDIO_EVENT drill.loop clip=3 voice=0
[MIN3DSTRY] AUDIO_EVENT wave.spawn spawned=2 clip=8 voice=0
[MIN3DSTRY] AUDIO_EVENT shoot.duo fired=3 clip=5 voice=0
[MIN3DSTRY] AUDIO_EVENT enemy.kill killed=1 clip=4 voice=0
[MIN3DSTRY] AUDIO_EVENT block.place clip=1 voice=0
[MIN3DSTRY] AUDIO_EVENT block.break clip=0 voice=0
```

- Parity: `0x53a483c7` on both models.
- New 3DS: `58.80 FPS`; Old 3DS: `59.64 FPS` in the recorded run.
- `voice=0` is expected in Azahar because DSP firmware is unavailable; it means
  no mixer voice was started, not that the event failed to resolve a clip.

## Interpretation

- The nine packed clips load from RomFS.
- Each wired event resolves to the expected registry clip.
- The NDSP backend stays in `fallback_no_dsp` and shuts down cleanly.
- Audible DSP playback, loop timing and mixing quality remain hardware-only
  validation items.

## Unified offline Mindustry v160.8

The [unified ARM11 artifact](https://github.com/levkeveli/Min3DStry/actions/runs/45174715056)
has ROM SHA-256
`447c35abecf39d3ba288d3aa74790b0f08c8e80da98f7b37e245fadab37aa8a5`.
`tools/verify_offline_stock_audio.py` recorded the following lines on
both Azahar 2126.2 profiles with the same stock Ground Zero save.

```text
UPSTREAM_LIVE_AUDIO_OPEN sounds=205 clips=194 pcmBytes=0 state=fallback_no_dsp rc=0xd880a7fa
UPSTREAM_LIVE_MUSIC_START id=4 name=game1 rate=44100 channels=2 frames=4096 mode=decode-only volume=1.000 loop=0
UPSTREAM_LIVE_AUDIO_PLAY id=67 volume=0.013 pitch=1.000 pan=0.375 voice=0 pcmBytes=468724 loop=1
UPSTREAM_SP_EXIT
UPSTREAM_LIVE_MUSIC_CLOSE track=-1 decoderOpen=0
```
