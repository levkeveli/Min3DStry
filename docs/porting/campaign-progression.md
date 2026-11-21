# Campaign Progression & Tech-Tree Persistence Baseline (Issue #41)

**Verification status:** **Emulator-tested**. Host baselines and real Arc `Settings`/`settings.bin`, campaign keys, sector slot, sector/content/research/launch/planet state, slot metadata and save probes have run on ARM11 in Azahar New/Old profiles. A native read-only SD sector catalog now lists upstream `sector-<planet>-<id>.msav` slots in the HUD. Campaign UI, sector selection/launch and gameplay integration remain open under [#41](https://github.com/levkeveli/Min3DStry/issues/41).

## 1:1 Upstream Mandate

Per [AGENTS.md](../../AGENTS.md) and [the upstream port plan](../upstream-port.md), Min3DStry preserves exact 1:1 compatibility with unmodified Java PC Mindustry `v160.8` rather than introducing custom save schemas or progression subsets:

- **Pinned Mindustry revision:** `19a0f40d44514f4802a8b79770456e7e32e34da0` (`v160.8`)
- **Pinned Arc revision:** `7445105cd2683daf5b9ac7033ebb07265d3e814d`
- **Verified `server-release.jar` SHA-256:** `faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`

All campaign unlocks, research progress, sector metadata, and sector world saves MUST use upstream's native `Core.settings` (`settings.bin`), `JsonIO.json` serialization, and `.msav` slot naming.

## Upstream Persistence Key Schemas

Verified directly against `mindustry` and `arc` classes in pinned `v160.8`:

| State | `Core.settings` / File Key | Upstream Mechanism |
| --- | --- | --- |
| Content unlock flag | `<content.name>-unlocked` (`boolean`) | Read at `UnlockableContent.java:94`; written by `unlock()` (`251-259`), `quietUnlock()` (`262-267`), and `clearUnlock()` (`292-297`). |
| Tech-node research progress | `req-<content.name>-<item.name>` (`int`) | Loaded in `TechTree.TechNode.setupRequirements` (`TechTree.java:172-180`); saved by `TechNode.save()`; zeroed and saved by `TechNode.reset()`. |
| Sector metadata (`SectorInfo`) | `<planet.name>-s-<sector.id>-info` (JSON) | Serialized/deserialized in `Sector.saveInfo()` (`101-103`) and `Sector.loadInfo()` (`105-117`) via `Core.settings.putJson` / `getJson(..., SectorInfo::new)`; cleared by `Sector.clearInfo()` (`120-123`). Migrates legacy `Blocks.water` entries to `Liquids.water`. |
| Sector world save slot naming | `sector-<planet.name>-<sector.id>.msav` | Slot path resolved via `Saves.getSectorFile(sector)` (`Saves.java:249-251`); baseline checks slot naming/resolution and `SaveSlot` attachment only (full `.msav` read/write round-trips are verified separately by `UpstreamSaveBaseline.java`). |
| Settings store & backups | `settings.bin`, `settings_backup.bin` (plus `settings_backups/` in `arc.Settings`) | Handled by `arc.Settings.loadValues()` (`Core.settings`); baseline exercises `settings_backup.bin` recovery and corrupt-all fallback. |

### Critical Upstream Initialization & Remapping Details

1. **Mandatory `JsonIO.json` binding:** `Vars.loadSettings()` binds `Core.settings.setJson(JsonIO.json)` before `Sector.saveInfo()` or `Sector.loadInfo()` runs. Calling `SectorInfo` JSON serialization without `JsonIO.json` causes recursive reflection over `Block`/`TechNode` references and triggers a `StackOverflowError`. On headless/server harness runs where desktop resource bundles (`bundles/bundle`) are absent, initialize `Core.settings.setJson(JsonIO.json)` and `Core.settings.load()` directly.
2. **Sector preset ID remapping (`SectorPreset.initialize()`):** `SectorPreset` preserves `originalPosition` (`15` for `SectorPresets.groundZero`, `86` for `SectorPresets.frozenForest`) while remapping `sector` via `planet.getData().presets` (`SectorPreset.java:81-102`). Under pinned `v160.8` Serpulo (`375` tech nodes across `2` roots, `95` sectors):
   - `groundZero`: `originalPosition = 15` -> Serpulo `sector.id = 170` (`alwaysUnlocked = true`).
   - `frozenForest`: `originalPosition = 86` -> Serpulo `sector.id = 64` (`parent = groundZero`; unlock objectives (`SerpuloTechTree.java:450-455`) include `Objectives.SectorComplete(groundZero)`, `Objectives.Research(junction)`, and `Objectives.Research(router)`).
3. **Backup & corruption resilience (`arc.Settings.loadValues()`):**
   - A clean `settings.bin` load copies the file to `settings_backup.bin`.
   - If `settings.bin` is truncated or corrupt, `loadValues()` falls back to `settings_backups/` and `settings_backup.bin`.
   - If both `settings.bin` and all backups are corrupt or absent, `loadValues()` catches the failure and initializes empty defaults rather than crashing startup.

## Host Verification Baseline

Two host harnesses in [research/upstream-runtime/host/](../../research/upstream-runtime/host/README.md) run in CI (`progression-baseline` job in [upstream-arm11-smoke.yml](../../.github/workflows/upstream-arm11-smoke.yml)) against the pinned `v160.8` `server-release.jar`:

- **`UpstreamProgressionBaseline.java`** verifies the deterministic full catalog counts and SHA-256 manifest:
  ```text
  UPSTREAM_PROGRESSION tech_nodes=375 tech_roots=2 sectors=95 manifest_sha256=4a61e8b260eb4f698ed128eb6654e2fa61f295f64d29b44c7638c7b8dda2bd4a
  ```
- **`UpstreamCampaignPersistenceBaseline.java`** verifies initial locked/unlocked state, sector `.msav` slot naming/resolution (`sector-serpulo-170.msav` via `Saves.getSectorFile(sector)` and `SaveSlot` attachment, rather than a full `.msav` world save round-trip covered by `UpstreamSaveBaseline.java`), real `settings.bin` round-trip persistence (unlocks, partial `TechNode` item requirements `50/100`, captured `SectorInfo`, and `frozenForest` objective completion), legacy `Blocks.water -> Liquids.water` migration, `TechNode.reset()`, `settings_backup.bin` recovery from corrupt `settings.bin`, and clean default fallback when all backups are corrupt:
  ```text
  UPSTREAM_CAMPAIGN_INIT root=groundZero(id=170,unlocked=true) frozenForest(id=64,locked=true,objComplete=false) conveyorLocked=true junctionLocked=true routerLocked=true
  UPSTREAM_CAMPAIGN_RELOAD settings_bytes=204 conveyorUnlocked=true junctionUnlocked=true routerLocked=true routerProgress=50/100 groundZeroCaptured=true frozenForestObjComplete=true
  UPSTREAM_CAMPAIGN_RESILIENCE waterMigrated=true resetOk=true backupRecovered=true corruptHandled=true
  UPSTREAM_CAMPAIGN_PASS tech_nodes=375 sectors=95
  ```

## ARM11 Arc Settings Round-Trip

`-Pprobe=campaign-settings` compiles the real Arc `Settings` class and drives
`settings.bin` through the TeaVM/libctru file adapter:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-settings runProbeJvm
bash research/upstream-runtime/arm11/build.sh \
  build/generated/teavm/c build/probe-3ds-campaign-settings
```

JVM and ARM11/Azahar both emit:

```text
UPSTREAM_CAMPAIGN_SETTINGS path=live bytes=112 int=12345 string=ok campaign=unlocked:conveyor,research:50 reload=ok
```

On 3DS the probe uses the live `/3ds/min3dstry` directory, snapshots any
existing `settings.bin`/`settings_backup.bin`/`settings_backups`, reruns the
round-trip (including upstream `conveyor-unlocked` and `req-conveyor-copper` keys), then restores or removes the saved state. On the JVM it uses a
temporary directory and reports `path=tmp`. It does not leave probe values in
live settings. Azahar `2126.2` verified `path=live` on both New and Old 3DS
profiles; the `.3dsx` build uses the pinned devkitARM image.

## ARM11 Sector Slot Round-Trip

`-Pprobe=campaign-sector` reuses the upstream world save/load path and writes
the real sector slot name `sector-serpulo-170.msav` on the target filesystem,
then reloads it:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-sector \
  runProbeJvm "--args=/path/glacier.msav /path/glacier-v13.msav /path/sector-serpulo-170.msav"
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_SECTOR slot=sector-serpulo-170.msav bytes=11072 version=13 marker=ok
```

The probe deletes the slot after verifying it; Azahar `2126.2` New and Old
profiles both reported the line and left no `sector-serpulo-170.msav` behind.
The `.3dsx` is built by the `world-roundtrip` CI job's pinned devkitARM step.

## ARM11 Campaign Content Unlock State

`-Pprobe=campaign-content` loads upstream content with campaign unlock keys
already present in `settings.bin`, then checks the real `UnlockableContent`
state:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-content runProbeJvm
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_CONTENT path=live lockedBefore=ok conveyorUnlocked=true key=conveyor-unlocked research=50 reload=ok blocks=447
```

The probe first runs the upstream `ContentLoader` (`createBaseContent`/`init`)
with empty settings and verifies conveyor is locked, writes the upstream
`conveyor-unlocked` and `req-conveyor-copper` keys through Arc `Settings`, then
reloads a fresh `Settings` instance from `settings.bin` and rebuilds content.
It verifies `Blocks.conveyor.unlocked()` plus the research value only after the
reload. On 3DS it targets the live `/3ds/min3dstry` directory and restores or
removes the prior settings state.
Azahar `2126.2` New and Old profiles both reported `path=live ... blocks=447`
and left no `settings.bin` behind.

## ARM11 Research Requirement State

`-Pprobe=campaign-research` reloads the upstream research keys from
`settings.bin` and verifies the Serpulo tech tree:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-research runProbeJvm
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_RESEARCH path=live root=serpulo node=conveyor copper=50 lockedBefore=ok reload=ok blocks=447
```

The probe loads content once with empty settings (copper requirement `0`),
persists `req-conveyor-copper=50`, reloads a fresh `Settings` from
`settings.bin`, rebuilds content, and asserts the conveyor `TechNode` is under
the Serpulo root with `finishedRequirements` copper `50`. On 3DS it targets the
live `/3ds/min3dstry` directory and restores or removes the prior settings
state. Azahar `2126.2` New and Old profiles both reported `path=live`.

## ARM11 Campaign Slot Metadata

`-Pprobe=campaign-slot-meta` writes the real ground-zero sector slot and
reloads its upstream metadata:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-slot-meta \
  runProbeJvm "--args=/path/glacier.msav /path/sector-serpulo-15.msav"
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_SLOT_META slot=sector-serpulo-15.msav bytes=11034 version=13 preset=groundZero wave=1 size=150x250 meta=ok
```

The probe sets `Vars.state.rules.sector` to `SectorPresets.groundZero.sector`,
writes `sector-serpulo-15.msav` through `SaveIO`, and verifies the `MSAV`
header/version plus `SaveMeta` tags (`sectorPreset=groundZero`, `wave=1`,
`width=150`, `height=250`) and `SaveIO.isSaveValid`. On 3DS it targets the live
`/3ds/min3dstry` directory and deletes the slot after verification. Azahar
`2126.2` New and Old profiles both reported the marker.

## ARM11 SectorInfo JSON Round-Trip

`-Pprobe=campaign-sector-info` persists and reloads the upstream
`serpulo-s-170-info` settings key with a TeaVM `ReflectionPolicy`:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-sector-info runProbeJvm
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_SECTOR_INFO path=live key=serpulo-s-170-info api=ok captured=ok wave=7 items=250:75 core=coreShard resource=copper-wall reload=ok clear=ok blocks=447
```

The probe sets `Sector.info` for ground-zero sector 170 and calls the real
upstream `Sector.saveInfo()`, reloads a fresh `Settings` from `settings.bin`,
then calls `Sector.loadInfo()` and verifies `wasCaptured`, `wave`,
`storageCapacity`, `name`, the copper/lead `ItemSeq`, the `coreShard` core
block, and the copper-wall resource. It also exercises `Sector.setName()` and
`Sector.clearInfo()`, asserting the settings key and struct reset. `Core.settings` is bound to `JsonIO.json`
so the block/resource fields use the upstream content serializers. TeaVM needs
`SectorInfoReflectionPolicy` to expose `SectorInfo` fields to Arc's JSON
reader; `bestCoreType` is nulled for this scalar/items round-trip. On 3DS the
probe targets live `/3ds/min3dstry` and restores or removes the prior settings.
Azahar `2126.2` New and Old profiles both reported `path=live`.

## ARM11 Planet Last-Sector Routing

`-Pprobe=campaign-planet` verifies the upstream planet last-sector setting:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-planet runProbeJvm
```

JVM reports:

```text
UPSTREAM_CAMPAIGN_PLANET path=tmp planet=serpulo sector=86 preset=frozenForest lastSector=86 reload=ok sectors=272
```

ARM11/Azahar reports the same marker with the live path:

```text
UPSTREAM_CAMPAIGN_PLANET path=live planet=serpulo sector=86 preset=frozenForest lastSector=86 reload=ok sectors=272
```

The probe initializes the real `Planets.serpulo` sectors and `SectorPresets`,
calls `Planets.serpulo.setLastSector` for frozen forest (86, deliberately not
the start sector), saves `settings.bin`, reloads a fresh `Settings`, and
asserts `getLastSector()` resolves sector 86 with the frozen-forest preset. On
3DS it targets live `/3ds/min3dstry` and restores/removes the prior settings.
Azahar `2126.2` New and Old profiles both reported `path=live`.

## ARM11 Campaign Sector State Transitions

`-Pprobe=campaign-sector-state` exercises the upstream sector play/capture
booleans without touching saves:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-sector-state runProbeJvm
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_SECTOR_STATE sector=15 groundZero=ok played=ok captured=ok waves=locked gameOver=ok menu=ok
```

The probe puts ground zero into `GameState.State.playing` with waves and attack
mode disabled, asserts `Sector.isBeingPlayed()`/`isCaptured()`, then re-enables
waves and asserts the sector is no longer captured, marks the game over and
asserts it is no longer being played, resets game-over, and verifies the menu
transition alone ends play state. No settings or save files are
written. Azahar `2126.2` New and Old profiles both reported the marker.

## ARM11 Campaign Sector Launch

`-Pprobe=campaign-launch` launches a real sector save through the upstream
sector save context:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-launch \
  runProbeJvm "--args=/path/glacier.msav /path/sector-serpulo-15.msav"
```

JVM and ARM11/Azahar report:

```text
UPSTREAM_CAMPAIGN_LAUNCH slot=sector-serpulo-15.msav bytes=11036 version=13 sector=15 wave=5 world=150x250 launch=ok
```

The probe loads Glacier, sets `Rules.sector` to ground zero (15) and
`state.wave=5`, writes the sector slot through `SaveIO`, then calls
`SaveIO.load(slot, world.makeSectorContext(sector))`. It asserts the launched
state restores the sector, wave 5, and the 150x250 world. On 3DS it targets the
live `/3ds/min3dstry` directory and deletes the slot. Azahar `2126.2` New and
Old profiles both reported `launch=ok`.

## ARM11 Sector Slot Path API

`-Pprobe=campaign-saves` verifies the upstream `Saves` slot path builder:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-saves runProbeJvm
```

JVM reports:

```text
UPSTREAM_CAMPAIGN_SAVES path=tmp sector=sector-serpulo-15.msav parent=m3ds-campaign-saves extension=msav saves=ok
```

ARM11/Azahar reports the live path:

```text
UPSTREAM_CAMPAIGN_SAVES path=live sector=sector-serpulo-15.msav parent=min3dstry extension=msav saves=ok
```

The probe supplies a minimal Arc `Files`/`AssetManager` for headless use, sets
`Vars.saveDirectory`, constructs the real `Saves` object, and calls
`Saves.getSectorFile(SectorPresets.groundZero.sector)`. It asserts the
`sector-serpulo-15.msav` name and parent directory. Azahar `2126.2` New and Old
profiles both reported `saves=ok`.

## Native SD Sector Catalog

`include/platform/sector_catalog.h` scans the live `/3ds/min3dstry` directory
for upstream `sector-<planet>-<id>.msav` names, validates each container through
`m3ds_save_slot_probe`, and keeps the result sorted by planet then sector id.
Each slot also carries the decoded upstream map name and wave when the save
metadata prefix parses. The scan is read-only: it never creates, rewrites or
deletes a slot. A missing directory is an empty catalog, not an error.

`main_3ds.c` reports the catalog at startup and in the bottom HUD:

```text
[MIN3DSTRY] SECTOR_CATALOG count=1
[MIN3DSTRY] SECTOR_SLOT planet=serpulo id=170 valid=1 compressed=1 bytes=11072 map=Glacier wave=1
```

```text
Sectors: 1
Sector serpulo-170 Glacier w1
```

`tests/platform/test_sector_catalog.c` covers sorting, capacity limits,
malformed names, non-slot files, compressed and raw headers, and missing
directories. `./dev.sh test` runs it. With `sector-serpulo-170.msav` (raw
`MSAV` v13) and `sector-erekir-64.msav` (zlib wrapper) staged in Azahar's
`sdmc:/3ds/min3dstry`, `./dev.sh emu-test --expect-sector-catalog 2` verified
`count=2` and the HUD lines on both New 3DS (804 MHz) and Old 3DS (268 MHz)
profiles.

The upstream `campaign-slot-discovery` probe below validates `SaveIO`-style slot
enumeration and validation; the native catalog presents the discovered slots to
the HUD without adding a new save format.

## ARM11 Save-Slot Discovery

`-Pprobe=campaign-slot-discovery` writes a sector slot into a probe directory
and discovers it with upstream `SaveIO` validation:

```sh
gradle -PupstreamCheckout=/path/to/Mindustry \
  -PreferenceJar=/path/to/server-release.jar \
  -ParcCheckout=/path/to/Arc -Pprobe=campaign-slot-discovery \
  runProbeJvm "--args=/path/slot-base /path/glacier.msav"
```

JVM reports:

```text
UPSTREAM_CAMPAIGN_SLOT_DISCOVERY path=tmp slots=1 file=sector-serpulo-15.msav bytes=11035 discovery=ok
```

ARM11/Azahar reports the live path:

```text
UPSTREAM_CAMPAIGN_SLOT_DISCOVERY path=live slots=1 file=sector-serpulo-15.msav bytes=11036 discovery=ok
```

The probe loads Glacier, writes `sector-serpulo-15.msav` into
`/3ds/min3dstry/tmp/slot-discovery`, lists the directory, filters `*.msav`
non-backup files through `SaveIO.isSaveValid`, and asserts exactly one slot.
The one-byte `bytes` difference is the compressed stream size produced by the
JVM versus TeaVM C deflate implementation; both files pass upstream `SaveIO`
validation and reload/launch on their platform.

The ARM11 build sets `M3DS_RELAX_POINTER_TYPES=1` because TeaVM C 0.16 emits
pointer-typed object references that GCC otherwise rejects; locally use:

```sh
M3DS_RELAX_POINTER_TYPES=1 bash arm11/build.sh \
  build/generated/teavm/c build/probe-3ds-campaign-slot-discovery
```

The runtime result is verified on both Azahar profiles. The opt-in is tracked
for removal once the TeaVM object-reference cast issue is fixed.

## Remaining Scope for Issue #41 (3DS Hardware / ARM11 Runtime)

The capability is marked `emulator-tested` because the probes in [campaign-verification.md](campaign-verification.md) verify upstream settings, sector `.msav` slots, `SectorInfo` JSON, tech-tree requirements, planet last-sector routing, sector play/capture transitions, and sector-save launch on ARM11/Azahar. The host-verified path contract is recorded in [sd-storage-paths.md](sd-storage-paths.md). The live runtime still needs to consume these paths end-to-end:

1. Route the live `Core.files`/`Core.settings` instance (not only research probes) to `/3ds/min3dstry/` so `settings.bin`, `settings_backup.bin`, `settings_backups/`, and `sector-<planet>-<id>.msav` use the verified contract.
2. Build the live campaign launch/capture UI flow, sector selection and capture rewards on top of the verified persistence/routing APIs and the read-only SD sector catalog.
3. Advance beyond `emulator-tested` only after those live flows and physical Old/New 3DS verification.
