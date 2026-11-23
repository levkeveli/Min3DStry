# NDSP Audio Mixer Prototype

Status: **Experimental** (`emulator-tested` for the RomFS registry, event routing, mixer state and missing-DSP fallback on both Azahar models; NDSP initializes with `state=ready` on physical Original/New 3DS and the New 3DS operator heard the UI tone, but physical listening for the live upstream effects and soundtrack remains open).

The merged ARM11 Join path uses this mixer for stock sound effects and
building ambience and streams the stock soundtrack through Tremor. The
stock-server replay ran on both Azahar profiles and decoded the original
`game1` track. See the [live audio build and replay](../../research/upstream-runtime/README.md#stock-sound-effects-and-soundtrack) for the test and its missing-DSP limitation.

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
- **Verification scope:** Verified by host C11 mock tests (`./dev.sh test`: `test_audio_mixer`, `test_audio_events`) and ARM11 compilation (`./dev.sh build`); the live audio path has separate emulator evidence and audible hardware playback with `dspfirm.cdc` remains pending on [#29](https://github.com/levkeveli/Min3DStry/issues/29).

## Missing DSP Firmware Fallback & Old 3DS Memory Footprint

- **Graceful fallback:** On clean homebrew setups or emulators lacking `sdmc:/3ds/dspfirm.cdc`, `ndspInit()` fails (returning `0xd880a7fa` in Azahar `2126.2`). The backend sets `ndsp_ready = false` and transitions the mixer to `M3DS_AUDIO_STATE_FALLBACK_NO_DSP`. No `ndspChn*` functions, `linearAlloc` uploads, or `ndspExit()` calls occur in fallback mode, preventing shutdown crashes or hangs.
- **Old 3DS memory budget:**
  - The current 2-tone prototype synthesizes two original mono PCM16 test tones (`880 Hz` / `80 ms` delivery chime = `1,764` frames; `520 Hz` / `35 ms` UI click = `772` frames), totaling **`5,072` bytes (< 5 KiB)** when uploaded to linear RAM—and **`0` bytes** when `state != ready`.
  - Mixing `8` of the `24` hardware NDSP channels while capping resident clips at `M3DS_AUDIO_MAX_CLIP_SAMPLES` (`22050 * 6` frames per slot) bounds registry clip storage to `~8.1 MiB` (`16 * 132,300 * 2 * 2` bytes) within the `64 MiB` Original 3DS `APPMEMTYPE 0` budget. The backend uploads each registered clip once into linear RAM (currently `647,008` bytes for nine clips); voices reference those uploaded buffers rather than duplicating them. The live adapter uses a 640 KiB PCM cache and double-buffered streams.

## Upstream audio assets and tests

- The built-in delivery and UI tones are procedural fixtures in the C harness.
  [Harness asset conversion](asset-conversion.md) adds nine pinned upstream
  clips with their original event IDs.
- The live ARM11 pack keeps all 205 stock effect IDs and 16 original tracks.
  [Stock sound catalogue](sound-catalog.md) · [Runtime bindings](../porting/audio-runtime-bindings.md)
- The live stock-server replay exercises Arc gain, pan, pitch and position,
  building ambience, and a soundtrack rule. It decodes the original track in
  both emulator profiles. [Live audio replay](../../research/upstream-runtime/README.md#stock-sound-effects-and-soundtrack)

## Verification Record

- **Host Mock Suite (`./dev.sh test`):** Passed `tests/audio/test_audio_mixer.c` (`test_audio_mixer: all assertions passed.`) covering `NULL`/disabled init, `FALLBACK_NO_DSP` zero-hardware-call lifecycle, clip validation, mono/stereo unregister cleanup, panning, bus/master/mute/pause propagation, 8-voice priority/oldest stealing and drops, completion harvesting, and deterministic tone synthesis. `tests/audio/test_audio_registry.c` covers RomFS index loading, ID lookup, buffer-capacity/I/O rollback and the stdio PCM reader; `tests/audio/test_audio_events.c` verifies the pinned event-to-clip mapping and voice selection; `tests/audio/test_audio_ambient.c` covers global loop start/stop and invalid-handle safety. Python parser tests in `tests/tools/test_verify_azahar.py` and `tests/tools/test_device_deploy.py` also pass.
- **ARM11 Build (`./dev.sh build`):** Compiled `src/audio/audio_mixer.c` and `src/platform/3ds/ndsp_backend.c` with `arm-none-eabi-gcc 16.1.0` (`-Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Azahar `2126.2` (`./dev.sh emu-test`):** Passed in both `new3ds` (`New 3DS XL, 804 MHz`, `58.80 FPS`) and `old3ds` (`Original 3DS XL, 268 MHz`, `59.64 FPS`):
  - Both modes logged `[MIN3DSTRY] AUDIO backend=ndsp state=fallback_no_dsp rc=0xd880a7fa rate=22050 voices=0/8 clips=10` and `[MIN3DSTRY] AUDIO_ROMFS status=ok clips=9 bytes=647008 ui_clip=6`.
  - Events resolved to clips: `conveyor.loop clip=2`, `drill.loop clip=3`, `block.place clip=1`, `block.break clip=0`, `wave.spawn clip=8`, `shoot.duo clip=5`, `enemy.kill clip=4`; all `voice=0` under the missing-DSP fallback.
  - Parity remained `0x53a483c7`, and inspected top/bottom PNG screenshots confirmed textured rendering alongside bottom-screen `Audio: fallback_no_dsp`.
  - Full transcript excerpt: [azahar-evidence.md](azahar-evidence.md).
- **Capability stage (`emulator-tested`):** Azahar runs load nine packed clips, resolve every wired event to a clip, exercise fallback startup/teardown, and preserve parity on both models. This stage does not claim audible output; that remains gated on physical hardware with DSP firmware.
- **Physical 3DS Hardware Audio (`dspfirm.cdc` present):** **Partially verified.** The New 3DS XL (`de3e249`) and Original 3DS XL (`a4a9a96`) runs logged `AUDIO backend=ndsp state=ready rc=0x00000000 rate=22050`; the earlier runs loaded 9-10 clips, while the full-build `a328c70` run loaded only 2 and all seven `AUDIO_EVENT`s resolved to `clip=-1`. The operator heard the UI tone on `a328c70`, so the NDSP output path is audible, but upstream effects/music, mute and crackling remain unverified. `ndsp-sfx-music` stays `emulator-tested` ([New combined](../hardware/evidence/new3ds-xl-a328c70.md), [Old](../hardware/evidence/old3ds-xl-a4a9a96.md)).


## Live multiplayer weather

The graphical client decodes the pinned weather packet and advances the
server-assigned `WeatherState` records from stock entity snapshots. Original
`SoundControl` loop and fade logic routes weather ambience through the ARM11
audio backend.

The [unified replay build](https://github.com/levkeveli/Min3DStry/actions/runs/45138219018)
requested the original rain, snow, and wind sounds on both Azahar profiles
while connected to an unmodified v160.8 server. Both runs used the no-DSP
fallback. Audible NDSP playback remains pending on physical hardware.
