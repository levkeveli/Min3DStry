# Campaign Progression & Tech-Tree Persistence Baseline (Issue #41)

**Verification status:** Host-verified against pinned upstream Mindustry `v160.8`; 3DS ARM11 runtime integration (`campaign-sectors` capability) remains **Planned** under [#41](https://github.com/levkeveli/Min3DStry/issues/41) until TeaVM ARM11 filesystem/`settings.bin` and campaign UI wiring land.

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

## Remaining Scope for Issue #41 (3DS Hardware / ARM11 Runtime)

Once the ARM11 TeaVM runtime bring-up integrates `Core.files` and `Core.settings` onto SD card storage under `/3ds/min3dstry/`:

1. Route `Core.settings` data directory (`settings.bin`, `settings_backup.bin`, `settings_backups/`) and sector `.msav` slots (`sector-<planet>-<id>.msav`) to deterministic SD card paths compatible with PC `v160.8` exports/imports.
2. Verify `JsonIO.json` serialization, atomic/backup `settings.bin` writes on 3DS FAT/SDFS (`libctru`), and full sector launch/capture/tech-tree round-trips in Azahar (Old 3DS & New 3DS) before advancing `campaign-sectors` in `docs/port-capabilities.json` beyond `planned`.
