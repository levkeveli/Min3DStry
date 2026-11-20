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

The logical IDs are mapping keys, not yet wired to gameplay trigger sites.

## Reproduce

The upstream checkout must be the pinned commit
`19a0f40d44514f4802a8b79770456e7e32e34da0`. Install ffmpeg (a build-time tool
only; it is not linked into the ROM).

```sh
python3 tools/build_audio_assets.py \
    --upstream /path/to/Mindustry \
    --map audio/upstream-audio-map.json \
    --out build/audio-upstream
```

The output directory is a build artifact and is not committed. It contains
`*.pcm` and `audio-manifest.json`:

```text
AUDIO_CONVERT id=ui.button frames=38176 bytes=76352 rate=22050 channels=1
AUDIO_CONVERT id=ui.unlock frames=32252 bytes=64504 rate=22050 channels=1
AUDIO_CONVERT id=wave.spawn frames=55530 bytes=111060 rate=22050 channels=1
AUDIO_ASSETS files=3 bytes=251916 manifest=build/audio-upstream/audio-manifest.json
```

The manifest records the upstream commit, ffmpeg version, source path/hash,
output path/hash, channels, rate, frames and bytes.

## Tests

- `tests/tools/test_audio_assets.py` validates duplicate IDs, path escapes,
  missing sources, invalid channels/rates, source hashing and map parsing.
- If ffmpeg is available, an integration test converts a synthetic 8 kHz WAV
  and checks PCM size, frame count and manifest provenance. Without ffmpeg the
  integration case is skipped; the manifest validation tests still run.
- `./dev.sh test` includes the test module. The `Audio conversion` CI job
  converts the pinned three assets with ffmpeg and asserts the output manifest;
  locally the integration case is skipped when ffmpeg is absent.

## Licensing and limitations

- Upstream audio is GPLv3. The conversion output is not committed; distribution
  must include the applicable GPLv3 notices, corresponding source and a
  per-file asset provenance audit.
- No ADPCM/streaming conversion, RomFS packing, runtime trigger mapping, DSP
  playback or physical-device validation is implemented here.
- ffmpeg is an external build-time executable and is not distributed with the
  project.
