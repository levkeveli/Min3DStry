# Upstream Audio Registry, Content Bindings & Positional Playback Baseline (Issue #29)

**Verification status:** Host-verified across pinned upstream Mindustry `v160.8` JVM (`UpstreamAudioBaseline.java` and `-Pprobe=audio-ticks`) and TeaVM C `-O0` / `-O2` (`cc -std=gnu11 -fwrapv -ffp-contract=off`), with ARM11 `.3dsx`/`.elf` compiled; `ndsp-sfx-music` in [port-capabilities.json](../port-capabilities.json) remains **Emulator-tested** under [#29](https://github.com/levkeveli/Min3DStry/issues/29) pending physical 3DS DSP playback verification. The merged Join path has [separate live Azahar evidence](../../research/upstream-runtime/README.md#stock-sound-effects-and-soundtrack).

## 1:1 Upstream Mandate

Per [AGENTS.md](../../AGENTS.md) and [the upstream port plan](../upstream-port.md), audio IDs, content sound/music assignments, Arc distance attenuation/panning, `SoundLoop` fading, and block ambient gating execute unmodified upstream Mindustry `v160.8` logic:

- **Pinned Mindustry revision:** `19a0f40d44514f4802a8b79770456e7e32e34da0` (`v160.8`)
- **Pinned Arc revision:** `7445105cd2683daf5b9ac7033ebb07265d3e814d`
- **Verified `server-release.jar` SHA-256:** `faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`

## Host Audio Registry & Content Binding Suite ([UpstreamAudioBaseline.java](../../research/upstream-runtime/host/UpstreamAudioBaseline.java))

1. **Asset Descriptor & ID Registry (`UPSTREAM_AUDIO_REGISTRY`):**
   - Drives unmodified `mindustry.gen.Sounds.load()` and `mindustry.gen.Musics.load()` through a recording `AssetManager` before `ContentLoader.createBaseContent()`.
   - Verifies all `205` registered `Sound` descriptors (`Sounds.getSound(0..204)` round-tripping through `Sounds.getSoundId`, `7` `sounds/ui/` entries, and `Sounds.getSoundId(Sounds.none) == -1` / `Sounds.unset == -1`) and all `16` `Musics` slots (`boss1`, `boss2`, `editor`, `fine`, `game1..game9`, `land`, `launch`, `menu`).
2. **Content Audio Bindings (`UPSTREAM_AUDIO_BINDINGS`):**
   - Initializes `Vars.content` (`447` blocks, `70` registered `UnitType` definitions, `202` bullet definitions) after `Sounds.load()` and hashes all `Block` (`placeSound`, `breakSound`, `destroySound`, `ambientSound`, `ambientSoundVolume`, `configureSound`, turret `shootSound`/`chargeSound`/`loopSound`), `UnitType` (`deathSound`, `wreckSound`, `loopSound`, `stepSound`, `tankMoveSound`, `moveSound`, `mineSound`, weapon `shootSound`/`chargeSound`), and `BulletType` (`shootSound`, `hitSound`, `despawnSound`, `healSound`) bindings.
   - Parses [upstream-audio-map.json](../../audio/upstream-audio-map.json) directly (verifying `upstream_commit`, rejecting missing/duplicate/unrecognized `"id"` entries, stripping `"core/assets/"` from `"source"` to resolve loaded `Sounds` descriptors, and asserting exact object identity with runtime `Sounds.*` and `ContentLoader` bindings):
     - `ui.button` -> `Sounds.uiButton`
     - `ui.unlock` -> `Sounds.uiUnlock`
     - `wave.spawn` -> `Sounds.waveSpawn`
     - `shoot.duo` -> `((ItemTurret) Blocks.duo).shootSound == Sounds.shootDuo`
     - `enemy.kill` -> `UnitTypes.dagger.deathSound == Sounds.unitExplode1`
     - `conveyor.loop` -> `Blocks.conveyor.ambientSound == Sounds.loopConveyor`
     - `drill.loop` -> `Blocks.mechanicalDrill.ambientSound == Sounds.loopDrill`
     - `block.place` -> `Blocks.conveyor.placeSound == Sounds.blockPlace1`
     - `block.break` -> `Blocks.conveyor.breakSound == Sounds.blockBreak1`
3. **Positional Attenuation, Stereo Panning & `SoundLoop` (`UPSTREAM_AUDIO_POSITIONAL`):**
   - Configures a `400x240` `Core.camera` with `Core.app` reporting `ApplicationType.desktop` (preserving `Vars.headless = true` while activating Arc `Sound.calcFalloff`, `Sound.calcPan`, and `Sound.calcVolume`).
   - Verifies center/left/right pan clamping (`panNeg=-0.500`, `panPos=0.900`), distance culling (`farCulled=true` at `(5000, 5000)` below `0.005f`), and `SoundLoop` fade-in (verifying initial zero volume on loop start, strict monotonic progression including the step-11 midpoint `0.5 * 0.019f`, and measuring exact steps to full target volume `loopFadeIn=21`) and fade-out (`loopFadeOut=20` updates to voice stop).
4. **Deterministic Simulation Audio Trace (`UPSTREAM_AUDIO_SIMULATION` & `UPSTREAM_AUDIO_ORDER`):**
   - Runs a `420`-tick `32x32` fixed-`60 Hz` world with copper conveyor -> `Duo`, `Hail` (graphite) artillery, `MechanicalDrill` on `oreCopper`, `Conveyor.clogHeat` ambient gating at tick `400`, and `SoundLoop` updates twice from clean state (`30` events per run, `clogGated=21`, `trace_hash=0xd6a9ce67062c9b00`).

### Observed Pinned Host Output

```text
UPSTREAM_AUDIO_REGISTRY sounds=205 musics=16 uiSounds=7 manifest_sha256=31b068d1493bf9f2ccf1c25d9646bba987bef82b3724317ee0eaf2ebf931790f
UPSTREAM_AUDIO_BINDINGS blocks=447 units=70 bullets=202 mapped=9 manifest_sha256=aff8c864f64176ae24daa083eb936c25ec6ddddf20871a5fd8fbf4ab8ce11cf4
UPSTREAM_AUDIO_POSITIONAL panNeg=-0.500 panPos=0.900 farCulled=true loopFadeIn=21 loopFadeOut=20
UPSTREAM_AUDIO_SIMULATION ticks=420 events=30 duoShots=19@tick49(pan=-0.145,vol=1.000) hail=4/4 unitExplode1=1@tick199(pan=0.120,vol=1.000) loops=conv:399,drill:420 clogGated=21
UPSTREAM_AUDIO_ORDER runs=2 ticks=420 events=30 trace_hash=0xd6a9ce67062c9b00
```

## TeaVM C `-O0` vs `-O2` & ARM11 Probe (`-Pprobe=audio-ticks`)

[UpstreamAudioTickProbe.java](../../research/upstream-runtime/src/gameplay/java/UpstreamAudioTickProbe.java) loads the full `205`-sound / `16`-music registry prior to `ContentLoader` on unmodified Glacier (`UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c`), runs the `Duo` + `Conveyor` + `MechanicalDrill` + stationary `Dagger` scenario for `420` ticks, and produces identical output across pinned `v160.8` JVM (`runProbeJvm`) and Linux host TeaVM C compiled with `cc -std=gnu11 -fwrapv -ffp-contract=off` at both `-O0` and `-O2` (after `arm11/fix_reflection_lifetime.py`):

```text
UPSTREAM_WORLD_FINGERPRINT 2e7b9f883994de6c
UPSTREAM_AUDIO_REGISTRY sounds=205 musics=16 duo=136 death=192 convLoop=63 drillLoop=67
UPSTREAM_AUDIO_AMBIENT convTicks=399 drillTicks=420 loops=1,1 duoFirstPanMilli=-335 deathPanMilli=-520
UPSTREAM_AUDIO_COMBAT shots=17 firstShot=49 deathTick=382 destroyed=382 ammo=23 events=20 trace=8dd46207043810fc
```

- **Note on frame-rate deduplication:** Upstream `arc.audio.Sound.play` checks wall-clock `Time.timeSinceMillis(lastTimePlayed) <= minInterval` (`16 ms`), which would depend on host execution speed in an unthrottled headless loop. `RecordingSound` records events keyed by deterministic simulation tick so JVM, TeaVM C `-O0`, and TeaVM C `-O2` remain comparable bit-for-bit.
- **ARM11 build (`arm11/build.sh` with pinned `devkitpro/devkitarm` `-O2`, point-in-time local measurement; CI validates non-empty `.3dsx`/`.elf` outputs):** Built `upstream-probe.3dsx` (`22,277,392` bytes) and `upstream-probe.elf` (`116,275,184` bytes; `38,936,576` bytes / `37.13 MiB` page-rounded ELF LOAD segments; `65.38 MiB` subtotal including `24 MiB` managed heap, `4 MiB` linear heap, and `256 KiB` native stack before GC metadata and runtime allocations).

## Remaining Scope for Issue #29

- Extend the live Arc audio adapter to offline gameplay.
- Verify live NDSP playback (`state=ready rc=0x00000000`) and stereo `ndspWaveBuf.nsamples` timing on physical Original and New 3DS hardware with `sdmc:/3ds/dspfirm.cdc`.
