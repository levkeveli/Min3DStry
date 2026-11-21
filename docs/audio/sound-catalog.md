# Upstream sound catalog

Stage the original Mindustry v160.8 sound effects from the pinned desktop
and server JARs. The host reference calls the generated `Sounds.load()` and
round-trips each original numeric ID through `Sounds.getSoundId()`.
The packer verifies both release hashes, copies the matching Ogg bytes
unchanged and writes the catalog under `audio/sound-catalog.json`.
[Pinned release](https://github.com/Anuken/Mindustry/releases/tag/v160.8)

```sh
python3 tools/stage_sound_catalog.py \
  --server /path/to/server-release.jar \
  --desktop /path/to/Mindustry.jar \
  --out /path/to/new-sound-romfs
```

The manifest records the original source path, RomFS path, SHA-256,
channel count, sample rate, and final Vorbis frame count for every sound ID.
The 205 files occupy 7,093,890 bytes as Ogg. At their original rates and
channel counts they represent 112,507,430 bytes of PCM16.
The runtime loading and playback work is tracked separately.
[#250](https://github.com/levkeveli/Min3DStry/issues/250)
