# Upstream audio asset conversion

Status: **host-tested conversion pipeline**. Issue: [#29](https://github.com/levkeveli/Min3DStry/issues/29).
No 3DS playback or gameplay trigger wiring is claimed yet.

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

## Tests

- `tests/tools/test_audio_assets.py` validates duplicate IDs, path escapes,
  missing sources, invalid channels/rates, source hashing, map parsing,
  trigger-symbol/file validation and RomFS index generation.
- If ffmpeg is available, an integration test converts a synthetic 8 kHz WAV
  and checks PCM size, frame count, trigger metadata and manifest provenance.
  Without ffmpeg the integration case is skipped; the validation tests still run.
- `./dev.sh test` includes the test module. The `Audio conversion` CI job
  converts the pinned three assets with ffmpeg and asserts the output manifest,
  PCM files and `audio-index.h`.

## Licensing and limitations

- Upstream audio is GPLv3. The conversion output is not committed; distribution
  must include the applicable GPLv3 notices, corresponding source and a
  per-file asset provenance audit.
- No ADPCM/streaming conversion, runtime trigger wiring, DSP playback or
  physical-device validation is implemented here. `audio-index.h` is generated
  for a future RomFS loader and is not linked into the 3DS app yet.
- ffmpeg is an external build-time executable and is not distributed with the
  project.
