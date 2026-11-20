# Upstream audio asset conversion

Status: **host-tested conversion + RomFS registry loader**. Issue: [#29](https://github.com/levkeveli/Min3DStry/issues/29).
The app can load real `ui.button` PCM when built with `UPSTREAM_MINDUSTRY`;
research/wave trigger callbacks and physical DSP playback are not verified yet.

## Scope

The 3DS NDSP mixer consumes PCM16 clips. This pipeline converts selected
**original pinned v160.8 assets** to mono PCM16 at 22,050 Hz and records
source/output hashes. It does not decode at runtime, and it does not invent a
game save, network or asset format.

The current map covers three UI/wave events as the first conversion slice:

| Logical ID | Upstream source | Source SHA-256 prefix |
|---|---|---|
| `ui.button` | `core/assets/sounds/ui/uiButton.ogg` | `f0ab7d4f…` |
| `ui.unlock` | `core/assets/sounds/ui/uiUnlock.ogg` | `eb7f0ceb…` |
| `wave.spawn` | `core/assets/sounds/ui/waveSpawn.ogg` | `736d27bd…` |

The logical IDs map to pinned upstream trigger sites in
`audio/upstream-audio-triggers.json`:

| Logical ID | Upstream trigger file | Symbol |
|---|---|---|
| `ui.button` | `core/src/mindustry/core/UI.java` | `Sounds.uiButton.play()` |
| `ui.unlock` | `core/src/mindustry/ui/dialogs/ResearchDialog.java` | `Sounds.uiUnlock.play()` |
| `wave.spawn` | `core/src/mindustry/core/Control.java` | `Sounds.waveSpawn.play()` |

The converter verifies each symbol still exists in the pinned checkout and
records the file/line in the manifest and generated index. This is a reference
map, not yet a runtime hook into the 3DS input/research/wave systems.

## Reproduce

The upstream checkout must be the pinned commit
`19a0f40d44514f4802a8b79770456e7e32e34da0`. Install ffmpeg (a build-time tool
only; it is not linked into the ROM).

```sh
python3 tools/build_audio_assets.py \
    --upstream /path/to/Mindustry \
    --map audio/upstream-audio-map.json \
    --triggers audio/upstream-audio-triggers.json \
    --out build/audio-upstream \
    --romfs-out build/romfs-audio
```

The output directories are build artifacts and are not committed. They contain
`*.pcm`, `audio-manifest.json`, and a RomFS-ready `audio-index.h`:

```text
AUDIO_CONVERT id=ui.button frames=38176 bytes=76352 rate=22050 channels=1
AUDIO_CONVERT id=ui.unlock frames=32252 bytes=64504 rate=22050 channels=1
AUDIO_CONVERT id=wave.spawn frames=55530 bytes=111060 rate=22050 channels=1
AUDIO_ASSETS files=3 bytes=251916 manifest=build/audio-upstream/audio-manifest.json
AUDIO_ROMFS files=3 index=build/romfs-audio/audio-index.h
```

The manifest records the upstream commit, ffmpeg version, source path/hash,
output path/hash, channels, rate, frames, bytes and trigger file/line. When git
is available, the converter also verifies that the checkout's `HEAD` equals
`upstream_commit` before writing any output.

## RomFS layout and runtime loading

`--romfs-out DIR` writes `DIR/audio-index.h` and `DIR/audio/` containing the PCM
files plus `audio-manifest.json`. The generated index includes
`audio/audio_registry.h` and defines `m3ds_audio_index[]`,
`M3DS_AUDIO_CLIP_COUNT` and `M3DS_AUDIO_TOTAL_BYTES`, so paths and sizes stay
consistent with what was packed.

`include/audio/audio_registry.h` exposes a bounded loader:

```c
M3dsAudioRegistry registry;
M3dsAudioRegistryStatus status = m3ds_audio_registry_load(
    &registry, &mixer, m3ds_audio_index, M3DS_AUDIO_CLIP_COUNT,
    m3ds_audio_read_file_stdio, NULL, pcm_storage, sizeof(pcm_storage));
int clip = m3ds_audio_registry_find(&registry, "ui.button");
```

The loader validates every index entry, rejects duplicate IDs and path/rate/
frame problems, checks the fixed PCM buffer, reads exactly the expected bytes,
and rolls back all clips if any step fails. `m3ds_audio_read_file_stdio` works on
host paths and on libctru `romfs:/audio/...` paths after `romfsInit()`.

Build the app with the real clips by pointing the Makefile at a pinned checkout:

```sh
make UPSTREAM_MINDUSTRY=/path/to/Mindustry
```

That target converts the three assets, copies them to `build/romfs/audio/`,
generates `build/3ds/audio-index.h`, and compiles with `-DM3DS_AUDIO_ROMFS`.
The ROMFS-enabled build registers the real `ui.button` clip for the A/Y
controls and logs `AUDIO_ROMFS status=...` over the debug channel. Builds
without `UPSTREAM_MINDUSTRY` keep the synthetic tone path. The `ui.unlock` and
`wave.spawn` clips are packed and loaded but have no gameplay trigger yet.

## Tests

- `tests/tools/test_audio_assets.py` validates duplicate IDs, path escapes,
  missing sources, invalid channels/rates, source hashing, map parsing,
  trigger-symbol/file validation and RomFS index generation (including the
  `audio/` layout and shared registry include).
- If ffmpeg is available, an integration test converts a synthetic 8 kHz WAV
  and checks PCM size, frame count, trigger metadata and manifest provenance.
  Without ffmpeg the integration case is skipped; the validation tests still run.
- `tests/audio/test_audio_registry.c` covers successful load/find, playable
  clip IDs, buffer-capacity rollback, I/O and short-read rollback, invalid
  entries, duplicate IDs, mixer rejection and the stdio reader (including
  missing/oversized files).
- `./dev.sh test` includes both test suites. The `Audio conversion` CI job
  converts the pinned three assets with ffmpeg, asserts the output manifest,
  PCM files, `audio/` layout and `audio-index.h`, then compiles the generated
  index against the registry header.

## Licensing and limitations

- Upstream audio is GPLv3. The conversion output is not committed; distribution
  must include the applicable GPLv3 notices, corresponding source and a
  per-file asset provenance audit.
- No ADPCM/streaming conversion or research/wave trigger wiring is implemented
  here. Only `ui.button` is used by the ROMFS-enabled app; `ui.unlock` and
  `wave.spawn` are loaded but not triggered.
- No physical Old/New 3DS DSP playback or save/network audio integration has
  been verified. The `AUDIO_ROMFS` debug line and Azahar runs can show loader
  state, not audible output quality.
- ffmpeg is an external build-time executable and is not distributed with the
  project.
