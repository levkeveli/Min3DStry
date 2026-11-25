# Upstream audio asset conversion

Status: **host-tested conversion + RomFS registry + emulator-exercised event layer**. Issue: [#29](https://github.com/levkeveli/Min3DStry/issues/29).
The app loads real PCM and routes UI/wave events; research unlock and audible
DSP playback remain unverified.

## Scope

The 3DS NDSP mixer consumes PCM16 clips. This pipeline converts selected
**original pinned v160.8 assets** to mono PCM16 at 22,050 Hz and records
source/output hashes. It does not decode at runtime, and it does not invent a
game save, network or asset format.

The current map covers nine UI/combat/build/ambient events as the first conversion slice:

| Logical ID | Upstream source | Source SHA-256 prefix |
|---|---|---|
| `ui.button` | `core/assets/sounds/ui/uiButton.ogg` | `f0ab7d4f…` |
| `ui.unlock` | `core/assets/sounds/ui/uiUnlock.ogg` | `eb7f0ceb…` |
| `wave.spawn` | `core/assets/sounds/ui/waveSpawn.ogg` | `736d27bd…` |
| `shoot.duo` | `core/assets/sounds/shoot/shootDuo.ogg` | `90704730…` |
| `enemy.kill` | `core/assets/sounds/explosions/unitExplode1.ogg` | `43a3c313…` |
| `conveyor.loop` | `core/assets/sounds/loops/loopConveyor.ogg` | `8f784c38…` |
| `drill.loop` | `core/assets/sounds/loops/loopDrill.ogg` | `eb96521e…` |
| `block.place` | `core/assets/sounds/block/blockPlace1.ogg` | `ea30ff4b…` |
| `block.break` | `core/assets/sounds/block/blockBreak1.ogg` | `b3cb45fc…` |

The logical IDs map to pinned upstream trigger sites in
`audio/upstream-audio-triggers.json`:

| Logical ID | Upstream trigger file | Symbol |
|---|---|---|
| `ui.button` | `core/src/mindustry/core/UI.java` | `Sounds.uiButton.play()` |
| `ui.unlock` | `core/src/mindustry/ui/dialogs/ResearchDialog.java` | `Sounds.uiUnlock.play()` |
| `wave.spawn` | `core/src/mindustry/core/Control.java` | `Sounds.waveSpawn.play()` |
| `shoot.duo` | `core/src/mindustry/content/Blocks.java` | `shootSound = Sounds.shootDuo;` |
| `enemy.kill` | `core/src/mindustry/type/UnitType.java` | `Sounds.unitExplode1` |
| `conveyor.loop` | `core/src/mindustry/world/blocks/distribution/Conveyor.java` | `ambientSound = Sounds.loopConveyor;` |
| `drill.loop` | `core/src/mindustry/world/blocks/production/Drill.java` | `ambientSound = Sounds.loopDrill;` |
| `block.place` | `core/src/mindustry/world/Block.java` | `Sounds.blockPlace1;` |
| `block.break` | `core/src/mindustry/world/Block.java` | `Sounds.blockBreak1;` |

The converter verifies each symbol still exists in the pinned checkout and
records the file/line in the manifest and generated index. `audio_events.h`
uses the same logical IDs at runtime; the trigger file/line remains provenance
metadata rather than a claim that the 3DS research/wave systems are complete.

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
AUDIO_CONVERT id=block.break frames=17681 bytes=35362 rate=22050 channels=1
AUDIO_CONVERT id=block.place frames=10456 bytes=20912 rate=22050 channels=1
AUDIO_CONVERT id=conveyor.loop frames=22496 bytes=44992 rate=22050 channels=1
AUDIO_CONVERT id=drill.loop frames=117181 bytes=234362 rate=22050 channels=1
AUDIO_CONVERT id=enemy.kill frames=21633 bytes=43266 rate=22050 channels=1
AUDIO_CONVERT id=shoot.duo frames=8099 bytes=16198 rate=22050 channels=1
AUDIO_CONVERT id=ui.button frames=38176 bytes=76352 rate=22050 channels=1
AUDIO_CONVERT id=ui.unlock frames=32252 bytes=64504 rate=22050 channels=1
AUDIO_CONVERT id=wave.spawn frames=55530 bytes=111060 rate=22050 channels=1
AUDIO_ASSETS files=9 bytes=647008 manifest=build/audio-upstream/audio-manifest.json
AUDIO_ROMFS files=9 index=build/romfs-audio/audio-index.h
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

That target converts the nine assets, copies them to `build/romfs/audio/`,
generates `build/3ds/audio-index.h`, and writes
`build/3ds/build-config.h` with `#define M3DS_AUDIO_ROMFS 1` (the app includes
that header). Builds without `UPSTREAM_MINDUSTRY` write `0` and remove stale
RomFS audio. The ROMFS-enabled build registers the real `ui.button` clip for
the A/Y path and logs `AUDIO_ROMFS status=...` over the debug channel.

`include/audio/audio_events.h` maps the pinned logical IDs to
`M3dsAudioEvent` values and plays the registered clip through the mixer:
`UI_BUTTON` is used by the A/Y controls, `WAVE_SPAWN` is requested when the
interactive demo spawns a deterministic wave at tick 5 through the existing
`M3DS_PINPUT_SPAWN_WAVE` input, `SHOOT_DUO` is requested when the demo's
Duo-style turret fires, `ENEMY_KILL` is requested when a wave enemy dies,
`CONVEYOR_LOOP` and `DRILL_LOOP` are requested as looping startup ambients,
`BLOCK_PLACE`/`BLOCK_BREAK` fire when the demo places and removes a wall,
and `RESEARCH_UNLOCK` is available for the research runtime once it exists.
The app logs `AUDIO_EVENT <id> ... clip=... voice=...`; Azahar requires
resolved clips for ROMFS builds, while `voice=0` is expected when DSP firmware
is unavailable. No research system is wired yet.

The current harness starts conveyor/drill loops globally at boot and stops or
restarts them with the A pause toggle through `audio_ambient.h`. Upstream plays
block ambients positionally near the camera, so positional ambient mixing is
still pending.

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
- `tests/audio/test_audio_events.c` checks the pinned ID mapping and that each
  event plays the matching registry clip; invalid events and missing registries
  are rejected without starting a voice.
- `tests/audio/test_audio_ambient.c` covers global loop start/stop, loop flags,
  repeated/invalid-handle stops and null arguments.
- `./dev.sh test` includes both test suites. The `Audio conversion` CI job
  converts the pinned nine assets with ffmpeg, asserts the output manifest,
  PCM files, `audio/` layout and `audio-index.h`, then compiles the generated
  index against the registry header.
- `./dev.sh emu-test` requires the deterministic wave, Duo-fire and enemy-kill
  event logs for both models. ROMFS builds additionally require resolved
  conveyor/drill loop and block place/break clips, proving the events reached
  the registry even when DSP firmware is absent.

## Full-build integration

The `3DS full build` workflow now builds this audio RomFS on the host with
`ffmpeg`, copies the generated `audio-index.h` into the Docker build, and
builds the `.3dsx` with `UPSTREAM_MINDUSTRY` pointing at the pinned upstream
checkout. The workflow runs `tools/validate_audio_romfs.py build/romfs` before
and after packaging, asserts every manifest entry exists at the recorded size,
requires at least 9 clips, logs the final `.3dsx` byte count, and rejects an
artifact at or above 24 MiB. The audio files are still build outputs and are
not committed.

The workflow then runs a separate `Verify artifact in Azahar` job on
GitHub-hosted Ubuntu. It downloads the uploaded `.3dsx`, installs the pinned
Azahar `2126.2` AppImage, and runs `tools/verify_azahar.py` on the New 3DS and
Original 3DS profiles. A boot failure, parity mismatch, missing audio RomFS
clips, or unresolved `wave.spawn` event fails the workflow. The job uploads
`build/emu-verify/report.json` and both screenshots as `emu-verify-report`
even when verification fails. The build job also publishes the artifact
SHA-256; `tools/check_azahar_report.py` compares it with the report and the
downloaded file, so a stale or swapped artifact cannot pass as verified.

Local reproduction (from the repo root, with ffmpeg and Python 3):

```bash
python3 tools/build_audio_assets.py \
  --upstream /path/to/Mindustry \
  --map audio/upstream-audio-map.json \
  --triggers audio/upstream-audio-triggers.json \
  --out build/audio-romfs --romfs-out build/romfs
python3 tools/validate_audio_romfs.py build/romfs
mkdir -p build/3ds
cp build/romfs/audio-index.h build/3ds/audio-index.h
make PYTHON=python3 UPSTREAM_MINDUSTRY=/path/to/Mindustry build/min3dstry.3dsx
```

Audible DSP playback and music quality remain **unverified** until a physical
listening run is recorded; the CI only proves packaging and manifest
integrity.

## Licensing and limitations

- Upstream audio is GPLv3. The conversion output is not committed; distribution
  must include the applicable GPLv3 notices, corresponding source and a
  per-file asset provenance audit.
- No ADPCM/streaming conversion is implemented here. `ui.button` is wired to
  the A/Y controls, `wave.spawn`/`shoot.duo`/`enemy.kill` are requested by the
  deterministic demo wave and turret, `conveyor.loop`/`drill.loop` start as
  looping ambients and `block.place`/`block.break` follow the demo wall action, and `ui.unlock` still has no research caller.
- Physical New 3DS DSP playback and save/network audio integration still need
  verification for these clips. The `AUDIO_ROMFS` debug line and Azahar runs can show loader
  state, not audible output quality.
- ffmpeg is an external build-time executable and is not distributed with the
  project.
