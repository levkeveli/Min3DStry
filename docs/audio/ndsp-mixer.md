# NDSP Audio Mixer Prototype

Status: **Experimental** (`host-tested` via mock audio backend in `./dev.sh test`; `.3dsx` compiled via `./dev.sh build`; missing-`dspfirm.cdc` startup/teardown fallback verified across Old & New 3DS modes in Azahar `2126.2`; live hardware NDSP playback with DSP firmware pending physical 3DS verification).

## Architecture

- **Platform-independent mixer interface ([audio_mixer.h](../../include/audio/audio_mixer.h), [audio_mixer.c](../../src/audio/audio_mixer.c)):** Pure C11 mixer (`<math.h>`-free integer synthesis and linear mixing logic) supporting:
  - Separate `SFX` and `MUSIC` buses (`M3DS_AUDIO_BUS_SFX`, `M3DS_AUDIO_BUS_MUSIC`), master volume, mute (`KEY_X` in prototype), and global pause/resume.
  - Up to `M3DS_AUDIO_MAX_CLIPS` (`16`) registered PCM16 clips (`1` or `2` channels, `4000..48000 Hz`) and `M3DS_AUDIO_MAX_VOICES` (`8`) active channels.
  - Deterministic priority + age voice stealing (lowest priority `<= incoming.priority`, breaking ties by oldest `start_seq`; drops strictly lower-priority incoming requests without glitches).
  - Linear stereo balance panning and stale-handle protection via nonzero monotonic `uint32_t` handles.
- **3DS `libctru` NDSP backend ([ndsp_backend.h](../../src/platform/3ds/ndsp_backend.h), [ndsp_backend.c](../../src/platform/3ds/ndsp_backend.c)):**
  - Initializes NDSP in stereo mode with soft clipping and `NDSP_INTERP_LINEAR` on channels `0..7`.
  - Copies PCM16 clip buffers into DSP-accessible linear memory (`linearAlloc`) and flushes the data cache (`DSP_FlushDataCache`).
  - Tracks active buffers via `ndspWaveBuf.status` (`NDSP_WBUF_QUEUED` / `NDSP_WBUF_PLAYING`) so newly queued clips are not prematurely evicted before the first DSP interrupt.
  - Clears and resets all NDSP channels **before** calling `linearFree` and `ndspExit` during shutdown so DSP DMA cannot access freed memory.

## Missing DSP Firmware Fallback & Old 3DS Memory Footprint

- **Graceful fallback:** On clean homebrew setups or emulators lacking `sdmc:/3ds/dspfirm.cdc`, `ndspInit()` fails (returning `0xd880a7fa` in Azahar `2126.2`). The backend sets `ndsp_ready = false` and transitions the mixer to `M3DS_AUDIO_STATE_FALLBACK_NO_DSP`. No `ndspChn*` functions, `linearAlloc` uploads, or `ndspExit()` calls occur in fallback mode, preventing shutdown crashes or hangs.
- **Old 3DS memory budget:**
  - The current 2-tone prototype synthesizes two original mono PCM16 test tones (`880 Hz` / `80 ms` delivery chime = `1,764` frames; `520 Hz` / `35 ms` UI click = `772` frames), totaling **`5,072` bytes (< 5 KiB)** when uploaded to linear RAM—and **`0` bytes** when `state != ready`.
  - Mixing `8` of the `24` hardware NDSP channels while capping resident clips at `M3DS_AUDIO_MAX_CLIP_SAMPLES` (`22050 * 6` frames per slot) bounds even full-capacity `16`-slot stereo PCM16 saturation to `~8.1 MiB` (`16 * 132,300 * 2 * 2` bytes) within the `64 MiB` Original 3DS `APPMEMTYPE 0` budget; longer music tracks will stream via double-buffered `ndspWaveBuf` chunks.

## Upstream Mindustry v160.8 Audio Integration & Provenance Plan

- **Scope note:** The two built-in prototype tones in `src/platform/3ds/main_3ds.c` are **original procedural test fixtures** (`m3ds_audio_synth_tone_pcm16`) used to exercise mixer registration and delivery/UI event triggers. No upstream Mindustry audio parity is claimed yet.
- **Follow-up slice on [#29](https://github.com/levkeveli/Min3DStry/issues/29):** Per [the upstream port plan](../upstream-port.md), upstream audio adaptation will target the pinned reference release [`Anuken/Mindustry@19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0) (GPLv3):
  - Map upstream trigger registries (`core/src/mindustry/gen/Sounds.java`, `Musics.java`, and sound/music bindings in world/block/UI systems) to `M3dsAudioMixer` buses and priority tiers.
  - Decode upstream `.ogg` assets (`core/assets/sounds/`, `core/assets/music/`) offline into RomFS PCM16/ADPCM streams with file-level provenance and GPLv3 attribution, plus streaming double-buffered `ndspWaveBuf` support for longer music tracks without exceeding Old 3DS linear RAM.
  - First conversion slice: [upstream audio asset conversion](asset-conversion.md) maps seven UI/combat/ambient IDs to mono PCM16, verifies their pinned upstream trigger symbols, and generates a RomFS PCM/index. `include/audio/audio_registry.h` loads the index into the mixer; `include/audio/audio_events.h` routes the logical events, with `ui.button` on A/Y, `wave.spawn`/`shoot.duo`/`enemy.kill` from gameplay, and looping conveyor/drill ambients. Azahar verifies resolved `AUDIO_EVENT` clips. Research unlock and DSP playback remain open.

## Verification Record

- **Host Mock Suite (`./dev.sh test`):** Passed `tests/audio/test_audio_mixer.c` (`test_audio_mixer: all assertions passed.`) covering `NULL`/disabled init, `FALLBACK_NO_DSP` zero-hardware-call lifecycle, clip validation, mono/stereo unregister cleanup, panning, bus/master/mute/pause propagation, 8-voice priority/oldest stealing and drops, completion harvesting, and deterministic tone synthesis. `tests/audio/test_audio_registry.c` covers RomFS index loading, ID lookup, buffer-capacity/I/O rollback and the stdio PCM reader; `tests/audio/test_audio_events.c` verifies the pinned event-to-clip mapping and voice selection. Python parser tests in `tests/tools/test_verify_azahar.py` and `tests/tools/test_device_deploy.py` also pass.
- **ARM11 Build (`./dev.sh build`):** Compiled `src/audio/audio_mixer.c` and `src/platform/3ds/ndsp_backend.c` with `arm-none-eabi-gcc 16.1.0` (`-Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Azahar `2126.2` (`./dev.sh emu-test`):** Passed in both `new3ds` (`New 3DS XL, 804 MHz`, `31.13 FPS`) and `old3ds` (`Original 3DS XL, 268 MHz`, `31.24 FPS`):
  - Both modes logged `[MIN3DSTRY] AUDIO backend=ndsp state=fallback_no_dsp rc=0xd880a7fa rate=22050 voices=0/8 clips=2`.
  - Parity remained bit-identical (`trace_hash=0x53a483c7`), frame telemetry stayed bounded (`R:427 C:1 Sw:1 Drop:0`), and inspected top/bottom PNG screenshots confirmed textured rendering alongside bottom-screen `Audio: fallback_no_dsp (ON)` and `X: Toggle Mute`.
- **Physical 3DS Hardware Audio (`dspfirm.cdc` present):** **Not yet tested**; live NDSP PCM playback requires deploying onto physical Original and New 3DS consoles with dumped `sdmc:/3ds/dspfirm.cdc`, confirming `state=ready rc=0x00000000`, and verifying stereo PCM16 `ndspWaveBuf.nsamples` per-channel frame duration on hardware.
