# Campaign Progression & Tech-Tree Persistence Baseline (Issue #41)

**Verification status:** Host baselines plus a real Arc `Settings`/`settings.bin` round-trip probe verified on ARM11 in Azahar New/Old profiles. Campaign UI, live sector slots and gameplay integration (`campaign-sectors` capability) remain **Planned** under [#41](https://github.com/levkeveli/Min3DStry/issues/41).

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
UPSTREAM_CAMPAIGN_SECTOR_INFO path=live key=serpulo-s-170-info captured=ok wave=7 items=250:75 reload=ok blocks=447
```

The probe writes a real `SectorInfo` through `Settings.putJson`, reloads a fresh
`Settings` from `settings.bin`, and verifies `wasCaptured`, `wave`,
`storageCapacity`, `name`, and the copper/lead `ItemSeq`. TeaVM needs
`SectorInfoReflectionPolicy` to expose `SectorInfo` fields to Arc's JSON
reader; `bestCoreType` is nulled for this scalar/items round-trip. On 3DS the
probe targets live `/3ds/min3dstry` and restores or removes the prior settings.
Azahar `2126.2` New and Old profiles both reported `path=live`.

## Remaining Scope for Issue #41 (3DS Hardware / ARM11 Runtime)

The host-verified path contract is recorded in [sd-storage-paths.md](sd-storage-paths.md); it does not write settings or sector saves yet. Once the ARM11 TeaVM runtime bring-up integrates `Core.files` and `Core.settings` onto SD card storage under `/3ds/min3dstry/`:

1. Route `Core.settings` data directory (`settings.bin`, `settings_backup.bin`, `settings_backups/`) and sector `.msav` slots (`sector-<planet>-<id>.msav`) to deterministic SD card paths compatible with PC `v160.8` exports/imports.
2. Verify `JsonIO.json` serialization, atomic/backup `settings.bin` writes on 3DS FAT/SDFS (`libctru`), and full sector launch/capture/tech-tree round-trips in Azahar (Old 3DS & New 3DS) before advancing `campaign-sectors` in `docs/port-capabilities.json` beyond `planned`.
