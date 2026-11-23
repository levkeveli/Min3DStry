# Double-Buffered NDSP Music Streaming

Status: **Emulator-tested in the live Join path.** The unified ARM11 build
packages all 16 stock Vorbis tracks. The stock-server replay on both
Azahar profiles decoded and closed `game1` through the missing-DSP path.
Physical listening and offline soundtrack integration remain open.
[Live replay](../../research/upstream-runtime/README.md#stock-sound-effects-and-soundtrack)

## Architecture

- **Platform-independent pull streamer ([audio_stream.h](../../include/audio/audio_stream.h), [audio_stream.c](../../src/audio/audio_stream.c)):** Pure C11 (`<math.h>`-free) ping-pong streamer supporting:
  - `M3DS_AUDIO_STREAM_BUFFER_COUNT` (`2`) fixed `M3DS_AUDIO_STREAM_CHUNK_FRAMES` (`4096`) chunks (`1` or `2` channels, `4000..48000 Hz`).
  - Pull-based `M3dsAudioStreamSource` (`read_frames` / `rewind` / `close`), plus `m3ds_audio_stream_source_open_stdio` for sequential stdio / `romfs:/` PCM16 reading without holding entire tracks in heap or linear RAM.
  - Strict FIFO completion harvesting, underrun/read-error accounting, natural EOF drain after the last queued chunk finishes, seamless `rewind()` looping with zero-frame infinite-loop protection, live music volume (`0.7f` default matching `M3DS_AUDIO_BUS_MUSIC`), master volume, mute, and pause/resume controls.
  - Pinned Mindustry `v160.8` (`19a0f40d44514f4802a8b79770456e7e32e34da0`) `Musics.load()` catalog mapping all `16` tracks (`boss1`, `boss2`, `editor`, `fine`, `game1..game9`, `land`, `launch`, `menu`).
  - Keep `M3dsAudioStream` (~16 KiB embedded `scratch_pcm`) and decoder source structs (`M3dsVorbisStreamSource` / `M3dsTremorStreamSource`) in static or heap storage at fixed addresses rather than on small ARM11 thread stacks.
- **Host-testable Ogg Vorbis pull-decoder adapter ([audio_vorbis_stream.h](../../include/audio/audio_vorbis_stream.h), [audio_vorbis_stream.c](../../src/audio/audio_vorbis_stream.c)):**
  - Maps short Vorbis decode reads (such as Tremor's 4096-byte page/packet chunks) cleanly to exact `M3dsAudioStreamSource` PCM16 frame counts without allocating large decode buffers.
  - Enforces bitstream continuity, frame alignment on EOF, positive bounded reads, rewind state reset, and idempotent close, plus `m3ds_audio_vorbis_music_romfs_path` resolving `"romfs:/audio/music/<name>.ogg"` for all `16` pinned tracks.
- **3DS `libvorbisidec` (Tremor) decoder source ([tremor_stream_source.h](../../src/platform/3ds/tremor_stream_source.h), [tremor_stream_source.c](../../src/platform/3ds/tremor_stream_source.c)):**
  - Links devkitPro 3DS portlibs (`$(PORTLIBS)` `-lvorbisidec -logg` in `Makefile`) to stream original `44100 Hz` stereo `.ogg` tracks directly from `romfs:/audio/music/<name>.ogg` or host paths via `ov_open` / `ov_read` / `ov_pcm_seek(..., 0)` / `ov_clear` in-place (never shallow-copying `OggVorbis_File`).
- **3DS `libctru` NDSP stream backend ([ndsp_stream_backend.h](../../src/platform/3ds/ndsp_stream_backend.h), [ndsp_stream_backend.c](../../src/platform/3ds/ndsp_stream_backend.c)):**
  - Borrows an initialized `M3dsNdspContext` without calling `ndspInit()` or `ndspExit()`.
  - Reserves dedicated hardware NDSP channel `8` (`M3DS_AUDIO_STREAM_NDSP_CHANNEL`), leaving channels `0..7` exclusively for `M3dsAudioMixer` SFX voices.
  - Allocates exactly `2 * 16 KiB` (**`32 KiB` total**) in DSP-accessible linear RAM (`linearAlloc`), flushes caches with `DSP_FlushDataCache` per submitted chunk, monitors `NDSP_WBUF_FREE` / `NDSP_WBUF_DONE` on alternating wave buffers, and clears/resets channel `8` **before** calling `linearFree` on shutdown.
  - Enters `M3DS_AUDIO_STATE_FALLBACK_NO_DSP` with zero `linearAlloc` or `ndspChn*` calls when `dspfirm.cdc` is absent.

## Verification & Remaining Scope for Issue #29

- **Host Mock & File Suite (`./dev.sh test`):**
  - `tests/audio/test_audio_stream.c` verifies all `16` pinned `v160.8` `Musics` catalog entries, exercises the stream's `FALLBACK_NO_DSP` and `DISABLED` state machine against a mock backend (the hardware `libctru` backend is ARM11-compiled below), `10,000`-frame stereo ping-pong chunk ordering and natural EOF drain, `rewind()` looping, 0-frame guard, underruns, read errors, volume/mute/pause propagation, and sequential `stdio` PCM file streaming.
  - `tests/audio/test_audio_vorbis_stream.c` verifies all `16` `"romfs:/audio/music/<name>.ogg"` paths, invalid initialization leaving caller structs untouched, fragmented odd-byte PCM reassembly across full `10,000`-frame `M3dsAudioStream` playback + `rewind()` loop + EOF drain, mid-stream bitstream switching, negative decoder errors, truncated mid-frame EOFs, and overrun/non-seekable edge cases.
- **ARM11 Build (`./dev.sh build`):** Compiles `src/audio/audio_stream.c`, `src/audio/audio_vorbis_stream.c`, `src/platform/3ds/ndsp_stream_backend.c`, and `src/platform/3ds/tremor_stream_source.c` with pinned `devkitpro/devkitarm` (`-Wall -Wextra -Werror`) and links `-lvorbisidec -logg` into `build/min3dstry.3dsx`.
- **Remaining under [#29](https://github.com/levkeveli/Min3DStry/issues/29):**
  - Wire the live audio adapter into offline gameplay and verify its stock soundtrack transitions.
  - Verify live audible NDSP playback on physical Original and New 3DS hardware with `sdmc:/3ds/dspfirm.cdc`.
