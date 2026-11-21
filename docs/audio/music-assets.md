# Upstream music asset staging

Status: **host-tested mapping, Ogg/Vorbis header validation, and RomFS staging**. Issue: [#29](https://github.com/levkeveli/Min3DStry/issues/29).
Runtime Tremor (`libvorbisidec`) decoding, `M3dsAudioStream` playback integration, and physical 3DS DSP verification remain open under `#29`.

## Rationale & scope

Unlike short sound effects converted to mono PCM16 by [`tools/build_audio_assets.py`](../../tools/build_audio_assets.py) ([asset-conversion.md](asset-conversion.md)), the **16** pinned upstream Mindustry `v160.8` music tracks (`core/assets/music/*.ogg`, `19a0f40d44514f4802a8b79770456e7e32e34da0`) are all **44,100 Hz stereo** Vorbis-in-Ogg files totaling **28,493,699 bytes** (~27.17 MiB). Pre-decoding all 16 tracks into uncompressed 44.1 kHz stereo PCM16 would exceed `380 MiB` in RomFS, whereas staging the unmodified `.ogg` streams preserves 1:1 original content within the RomFS budget for fixed-point streaming decode via devkitPro's `tremor` (`libvorbisidec.a` + `libogg.a`, already present in the pinned `devkitpro/devkitarm` 3DS portlibs) into double-buffered 3DS NDSP DMA slots.

[`audio/upstream-music-map.json`](../../audio/upstream-music-map.json) pins all `16` tracks in exact `Musics.load()` alphabetical order (`index` `0..15`), and [`tools/stage_music_assets.py`](../../tools/stage_music_assets.py) validates upstream provenance, Ogg/Vorbis I identification headers, and trigger call sites before copying unmodified `.ogg` files into RomFS.

## Pinned `v160.8` music catalog (`Musics.load()` order)

Measured against pinned `Anuken/Mindustry@19a0f40d44514f4802a8b79770456e7e32e34da0`:

| Index | ID | Upstream source | Format | Bytes | Source SHA-256 prefix | Upstream trigger reference |
|---|---|---|---|---:|---|---|
| `0` | `boss1` | `core/assets/music/boss1.ogg` | 44,100 Hz, 2 ch | `2,566,868` | `ace3b69200b25625…` | `core/src/mindustry/audio/SoundControl.java:96` (`Musics.boss1`) |
| `1` | `boss2` | `core/assets/music/boss2.ogg` | 44,100 Hz, 2 ch | `2,705,612` | `0c697a8b3e292e43…` | `core/src/mindustry/audio/SoundControl.java:96` (`Musics.boss2`) |
| `2` | `editor` | `core/assets/music/editor.ogg` | 44,100 Hz, 2 ch | `1,539,857` | `204e2c8b6fc78f13…` | `core/src/mindustry/audio/SoundControl.java:161` (`Musics.editor`) |
| `3` | `fine` | `core/assets/music/fine.ogg` | 44,100 Hz, 2 ch | `1,819,566` | `a5153d804aa91e61…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.fine`) |
| `4` | `game1` | `core/assets/music/game1.ogg` | 44,100 Hz, 2 ch | `1,281,219` | `e9e43cdad01597b4…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.game1`) |
| `5` | `game2` | `core/assets/music/game2.ogg` | 44,100 Hz, 2 ch | `1,329,376` | `ed382f98f2d5c356…` | `core/src/mindustry/audio/SoundControl.java:95` (`Musics.game2`) |
| `6` | `game3` | `core/assets/music/game3.ogg` | 44,100 Hz, 2 ch | `1,855,682` | `b04791a90b2142c1…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.game3`) |
| `7` | `game4` | `core/assets/music/game4.ogg` | 44,100 Hz, 2 ch | `2,531,904` | `113c231cc231b5bb…` | `core/src/mindustry/audio/SoundControl.java:95` (`Musics.game4`) |
| `8` | `game5` | `core/assets/music/game5.ogg` | 44,100 Hz, 2 ch | `1,975,980` | `8aa50eb40778ce52…` | `core/src/mindustry/audio/SoundControl.java:95` (`Musics.game5`) |
| `9` | `game6` | `core/assets/music/game6.ogg` | 44,100 Hz, 2 ch | `1,649,639` | `a60ec8489cff2803…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.game6`) |
| `10` | `game7` | `core/assets/music/game7.ogg` | 44,100 Hz, 2 ch | `2,011,976` | `2850d6a342ae3e27…` | `core/src/mindustry/audio/SoundControl.java:95` (`Musics.game7`) |
| `11` | `game8` | `core/assets/music/game8.ogg` | 44,100 Hz, 2 ch | `2,134,796` | `f005d0344edfae6d…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.game8`) |
| `12` | `game9` | `core/assets/music/game9.ogg` | 44,100 Hz, 2 ch | `2,593,090` | `1213455e43af6100…` | `core/src/mindustry/audio/SoundControl.java:94` (`Musics.game9`) |
| `13` | `land` | `core/assets/music/land.ogg` | 44,100 Hz, 2 ch | `343,172` | `d5a0878ed8e6731b…` | `core/src/mindustry/world/blocks/storage/CoreBlock.java:58` (`Musics.land`) |
| `14` | `launch` | `core/assets/music/launch.ogg` | 44,100 Hz, 2 ch | `1,169,439` | `fb5035bdfa0ec11d…` | `core/src/mindustry/type/Planet.java:143` (`Musics.launch`) |
| `15` | `menu` | `core/assets/music/menu.ogg` | 44,100 Hz, 2 ch | `985,523` | `d7644a99481a8795…` | `core/src/mindustry/audio/SoundControl.java:163` (`Musics.menu`) |

**Total:** `16` tracks, **`28,493,699` bytes** (`~27.17 MiB`).

## Reproduce

With a pinned checkout of `Anuken/Mindustry` at commit `19a0f40d44514f4802a8b79770456e7e32e34da0` (pure Python 3 stdlib; no `ffmpeg` or third-party packages required):

```sh
python3 tools/stage_music_assets.py \
    --upstream /path/to/Mindustry \
    --out build/music-upstream \
    --romfs-out build/romfs-music
```

To verify Ogg/Vorbis headers, contiguous indices, git commit pin, and trigger symbols without copying files:

```sh
python3 tools/stage_music_assets.py \
    --upstream /path/to/Mindustry \
    --verify-only
```

## Generated RomFS layout

When `--romfs-out DIR` is passed, `tools/stage_music_assets.py` writes:

- `DIR/audio/music/<id>.ogg` — bit-exact copy verified against `source_sha256`.
- `DIR/audio/music-manifest.json` — full per-track provenance, Ogg/Vorbis sample rate, channel count, nominal bitrate, file size, SHA-256 digests, and upstream trigger file/line.
- `DIR/music-index.h` — self-contained C11 header defining `M3DS_MUSIC_INDEX_COUNT` (`16U`), `M3DS_MUSIC_TOTAL_BYTES` (`28493699U`), `M3dsMusicIndexEntry`, and `m3ds_music_index[]` mapping each track index to `"romfs:/audio/music/<id>.ogg"`.

## Verification

- `tests/tools/test_stage_music_assets.py` (discovered automatically by `./dev.sh test`) verifies the committed 16-entry `audio/upstream-music-map.json`, contiguous `Musics.load()` ordering, pure-Python OggS BOS + Vorbis I identification header parsing/rejection cases, path-escape & trigger validation, bit-exact `--out` / `--romfs-out` staging, `music-index.h` output, and `--verify-only`.
- Staging against the pinned local `19a0f40d44514f4802a8b79770456e7e32e34da0` checkout produced `MUSIC_ASSETS files=16 bytes=28493699`, passed `--verify-only`, and compiled + executed the generated `music-index.h` under `cc -std=c11 -Wall -Wextra -Werror`.

## Licensing and remaining work

- Upstream `.ogg` files are GPLv3 and are **not** committed to this repository; distribution packages built from staged assets must include corresponding GPLv3 source/notices and provenance.
- Connecting `libvorbisidec` (Tremor) decoding to `M3dsAudioStream`, wiring `--romfs-out` into the `Makefile` / `SoundControl` bridge, and verifying audible hardware DSP music playback remain open under `#29`.
