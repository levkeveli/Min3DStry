# NDSP Audio Mixer Prototype

Status: **Experimental** (`emulator-tested` for the RomFS registry, event routing, mixer state and missing-DSP fallback on both Azahar models; audible NDSP playback still requires physical hardware).

## Architecture

- **Platform-independent mixer interface ([audio_mixer.h](../../include/audio/audio_mixer.h), [audio_mixer.c](../../src/audio/audio_mixer.c)):** Pure C11 mixer (`<math.h>`-free integer synthesis and linear mixing logic) supporting:
  - Separate `SFX` and `MUSIC` buses (`M3DS_AUDIO_BUS_SFX`, `M3DS_AUDIO_BUS_MUSIC`), master volume, mute (`KEY_X` in prototype), and global plus per-voice pause/resume (`m3ds_audio_mixer_set_voice_paused`).
  - Up to `M3DS_AUDIO_MAX_CLIPS` (`16`) registered PCM16 clips (`1` or `2` channels, `4000..48000 Hz`) and `M3DS_AUDIO_MAX_VOICES` (`8`) active channels.
  - Deterministic priority + age voice stealing (lowest priority `<= incoming.priority`, breaking ties by oldest `start_seq`; drops strictly lower-priority incoming requests without glitches).
  - Live per-voice stereo mix/pan (`m3ds_audio_mixer_set_voice_mix`), playback pitch scaling (`M3dsAudioPlayParams.pitch`, `m3ds_audio_mixer_set_voice_pitch`, `[0.25x..4.0x]`), activity query (`m3ds_audio_mixer_voice_is_active`), and stale-handle protection via nonzero monotonic `uint32_t` handles.
- **3DS `libctru` NDSP backend ([ndsp_backend.h](../../src/platform/3ds/ndsp_backend.h), [ndsp_backend.c](../../src/platform/3ds/ndsp_backend.c)):**
  - Initializes NDSP in stereo mode with soft clipping and `NDSP_INTERP_LINEAR` on channels `0..7`.
  - Copies PCM16 clip buffers into DSP-accessible linear memory (`linearAlloc`) and flushes the data cache (`DSP_FlushDataCache`).
  - Updates per-channel sample rate via `ndspChnSetRate` (`voice_set_rate`) to honor `M3dsAudioPlayParams.pitch` and live pitch changes.
  - Tracks active buffers via `ndspWaveBuf.status` (`NDSP_WBUF_QUEUED` / `NDSP_WBUF_PLAYING`) so newly queued clips are not prematurely evicted before the first DSP interrupt.
  - Clears and resets all NDSP channels **before** calling `linearFree` and `ndspExit` during shutdown so DSP DMA cannot access freed memory.

## Upstream Voice Control & Sound-ID Mapping

- **Per-voice control:** `m3ds_audio_mixer_set_voice_mix`, `m3ds_audio_mixer_set_voice_pitch`, `m3ds_audio_mixer_set_voice_paused`, and `m3ds_audio_mixer_voice_is_active` provide the C mixer primitives required by Arc `Core.audio` (`set`, `setPitch`, `setPaused`, `isPlaying`) and Mindustry `SoundLoop` fade/pan updates without resetting playing `ndspWaveBuf` voices.
- **Pinned `v160.8` `Sounds` ID & spatial routing ([audio_events.h](../../include/audio/audio_events.h), [audio_events.c](../../src/audio/audio_events.c)):** Maps the `9` RomFS-converted events to pinned Mindustry `v160.8` `Sounds.getSoundId(...)` indices (`block.break=11`, `block.place=26`, `conveyor.loop=63`, `drill.loop=67`, `shoot.duo=136`, `ui.button=185`, `ui.unlock=189`, `enemy.kill=192`, `wave.spawn=198`) and implements Arc `Sound.calcFalloff`, `Sound.calcPan`, and `Sound.calcVolume` distance attenuation and stereo panning.
- **Verification scope:** Verified by host C11 mock tests (`./dev.sh test`: `test_audio_mixer`, `test_audio_events`) and ARM11 compilation (`./dev.sh build`); live upstream-runtime wiring and audible hardware playback with `dspfirm.cdc` remain pending on [#29](https://github.com/levkeveli/Min3DStry/issues/29).

## Missing DSP Firmware Fallback & Old 3DS Memory Footprint

- **Graceful fallback:** On clean homebrew setups or emulators lacking `sdmc:/3ds/dspfirm.cdc`, `ndspInit()` fails (returning `0xd880a7fa` in Azahar `2126.2`). The backend sets `ndsp_ready = false` and transitions the mixer to `M3DS_AUDIO_STATE_FALLBACK_NO_DSP`. No `ndspChn*` functions, `linearAlloc` uploads, or `ndspExit()` calls occur in fallback mode, preventing shutdown crashes or hangs.
- **Old 3DS memory budget:**
  - The current 2-tone prototype synthesizes two original mono PCM16 test tones (`880 Hz` / `80 ms` delivery chime = `1,764` frames; `520 Hz` / `35 ms` UI click = `772` frames), totaling **`5,072` bytes (< 5 KiB)** when uploaded to linear RAM—and **`0` bytes** when `state != ready`.
  - Mixing `8` of the `24` hardware NDSP channels while capping resident clips at `M3DS_AUDIO_MAX_CLIP_SAMPLES` (`22050 * 6` frames per slot) bounds registry clip storage to `~8.1 MiB` (`16 * 132,300 * 2 * 2` bytes) within the `64 MiB` Original 3DS `APPMEMTYPE 0` budget. The backend uploads each registered clip once into linear RAM (currently `647,008` bytes for nine clips); voices reference those uploaded buffers rather than duplicating them. Longer music tracks will stream via double-buffered `ndspWaveBuf` chunks.

## Upstream Mindustry v160.8 Audio Integration & Provenance Plan

- **Scope note:** The two built-in prototype tones in `src/platform/3ds/main_3ds.c` are **original procedural test fixtures** (`m3ds_audio_synth_tone_pcm16`) used to exercise mixer registration and delivery/UI event triggers. No upstream Mindustry audio parity is claimed yet.
- **Follow-up slice on [#29](https://github.com/levkeveli/Min3DStry/issues/29):** Per [the upstream port plan](../upstream-port.md), upstream audio adaptation will target the pinned reference release [`Anuken/Mindustry@19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0) (GPLv3):
  - Map upstream trigger registries (`core/src/mindustry/gen/Sounds.java`, `Musics.java`, and sound/music bindings in world/block/UI systems) to `M3dsAudioMixer` buses and priority tiers.
  - Decode upstream `.ogg` assets (`core/assets/sounds/`, `core/assets/music/`) offline into RomFS PCM16/ADPCM streams with file-level provenance and GPLv3 attribution, plus streaming double-buffered `ndspWaveBuf` support for longer music tracks without exceeding Old 3DS linear RAM.
  - First conversion slice: [upstream audio asset conversion](asset-conversion.md) maps nine UI/combat/build/ambient IDs to mono PCM16, verifies their pinned upstream trigger symbols, and generates a RomFS PCM/index. `include/audio/audio_registry.h` loads the index into the mixer; `include/audio/audio_events.h` routes the logical events, with `ui.button` on A/Y, `wave.spawn`/`shoot.duo`/`enemy.kill` from gameplay, `block.place`/`block.break` from the demo wall action, and looping conveyor/drill ambients. Azahar verifies resolved `AUDIO_EVENT` clips. Research unlock and DSP playback remain open.
  - Upstream runtime binding & `-Pprobe=audio-ticks` parity slice: [upstream audio runtime bindings](../porting/audio-runtime-bindings.md) verifies all `205` `Sounds` IDs and `16` `Musics` slots via `Sounds.load()` / `Musics.load()`, catalogs content audio bindings across all `447` blocks / `70` units / `202` bullets, and matches positional falloff/pan, `SoundLoop`, conveyor/drill ambient gating, and combat audio traces identically across pinned JVM and TeaVM C `-O0`/`-O2` (with ARM11 `.3dsx`/`.elf` compiled; live upstream-to-NDSP bridge and physical DSP hardware playback remain open on `#29`).

## Verification Record

- **Host Mock Suite (`./dev.sh test`):** Passed `tests/audio/test_audio_mixer.c` (`test_audio_mixer: all assertions passed.`) covering `NULL`/disabled init, `FALLBACK_NO_DSP` zero-hardware-call lifecycle, clip validation, mono/stereo unregister cleanup, panning, bus/master/mute/pause propagation, 8-voice priority/oldest stealing and drops, completion harvesting, and deterministic tone synthesis. `tests/audio/test_audio_registry.c` covers RomFS index loading, ID lookup, buffer-capacity/I/O rollback and the stdio PCM reader; `tests/audio/test_audio_events.c` verifies the pinned event-to-clip mapping and voice selection; `tests/audio/test_audio_ambient.c` covers global loop start/stop and invalid-handle safety. Python parser tests in `tests/tools/test_verify_azahar.py` and `tests/tools/test_device_deploy.py` also pass.
- **ARM11 Build (`./dev.sh build`):** Compiled `src/audio/audio_mixer.c` and `src/platform/3ds/ndsp_backend.c` with `arm-none-eabi-gcc 16.1.0` (`-Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Azahar `2126.2` (`./dev.sh emu-test`):** Passed in both `new3ds` (`New 3DS XL, 804 MHz`, `58.80 FPS`) and `old3ds` (`Original 3DS XL, 268 MHz`, `59.64 FPS`):
  - Both modes logged `[MIN3DSTRY] AUDIO backend=ndsp state=fallback_no_dsp rc=0xd880a7fa rate=22050 voices=0/8 clips=10` and `[MIN3DSTRY] AUDIO_ROMFS status=ok clips=9 bytes=647008 ui_clip=6`.
  - Events resolved to clips: `conveyor.loop clip=2`, `drill.loop clip=3`, `block.place clip=1`, `block.break clip=0`, `wave.spawn clip=8`, `shoot.duo clip=5`, `enemy.kill clip=4`; all `voice=0` under the missing-DSP fallback.
  - Parity remained `0x53a483c7`, and inspected top/bottom PNG screenshots confirmed textured rendering alongside bottom-screen `Audio: fallback_no_dsp`.
  - Full transcript excerpt: [azahar-evidence.md](azahar-evidence.md).
- **Capability stage (`emulator-tested`):** Azahar runs load nine packed clips, resolve every wired event to a clip, exercise fallback startup/teardown, and preserve parity on both models. This stage does not claim audible output; that remains gated on physical hardware with DSP firmware.
- **Physical 3DS Hardware Audio (`dspfirm.cdc` present):** **Not yet tested**; live NDSP PCM playback requires deploying onto physical Original and New 3DS consoles with dumped `sdmc:/3ds/dspfirm.cdc`, confirming `state=ready rc=0x00000000`, and verifying stereo PCM16 `ndspWaveBuf.nsamples` per-channel frame duration on hardware.
