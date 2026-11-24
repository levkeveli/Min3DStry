# Campaign Persistence Verification Matrix

Each row maps a claimed `campaign-sectors` capability to a merged research
probe and its host/Azahar marker. JVM runs report `path=tmp`; ARM11/Azahar runs
report `path=live` and restore live settings or delete probe slots.

| Capability | Probe | Verified marker |
| --- | --- | --- |
| Arc settings primitives and campaign keys | `campaign-settings` | `UPSTREAM_CAMPAIGN_SETTINGS path=live bytes=112 int=12345 string=ok campaign=unlocked:conveyor,research:50 reload=ok` |
| Real content unlock state | `campaign-content` | `UPSTREAM_CAMPAIGN_CONTENT path=live lockedBefore=ok conveyorUnlocked=true key=conveyor-unlocked research=50 reload=ok blocks=447` |
| Serpulo tech-tree requirements | `campaign-research` | `UPSTREAM_CAMPAIGN_RESEARCH path=live root=serpulo node=conveyor copper=50 lockedBefore=ok reload=ok blocks=447` |
| Sector slot `.msav` round-trip | `campaign-sector` | `UPSTREAM_CAMPAIGN_SECTOR slot=sector-serpulo-170.msav bytes=11072 version=13 marker=ok` |
| Sector slot metadata tags | `campaign-slot-meta` | `UPSTREAM_CAMPAIGN_SLOT_META slot=sector-serpulo-15.msav bytes=11034 version=13 preset=groundZero wave=1 size=150x250 meta=ok` |
| Full `SectorInfo` JSON lifecycle | `campaign-sector-info` | `UPSTREAM_CAMPAIGN_SECTOR_INFO path=live key=serpulo-s-170-info api=ok captured=ok wave=7 items=250:75 core=coreShard resource=copper-wall reload=ok clear=ok blocks=447` |
| Planet last-sector routing | `campaign-planet` | `UPSTREAM_CAMPAIGN_PLANET path=live planet=serpulo sector=86 preset=frozenForest lastSector=86 reload=ok sectors=272` |
| Sector play/capture transitions | `campaign-sector-state` | `UPSTREAM_CAMPAIGN_SECTOR_STATE sector=15 groundZero=ok played=ok captured=ok waves=locked gameOver=ok menu=ok` |
| Sector capture state/persistence | `campaign-capture` | `UPSTREAM_CAMPAIGN_CAPTURE_REWARDS copper=250 lead=75 core=core-nucleus storage=13000` / `UPSTREAM_CAMPAIGN_CAPTURE sector=15 captured=ok waves=off attack=off event=ok persisted=ok` |
| Sector save launch | `campaign-launch` | `UPSTREAM_CAMPAIGN_LAUNCH slot=sector-serpulo-15.msav bytes=11036 version=13 sector=15 wave=5 world=150x250 launch=ok` |
| Upstream `Saves.load()` + sector launch on live SD path | `campaign-saves-load` | `UPSTREAM_CAMPAIGN_SAVES_LOAD path=live slots=2 selected=sector-serpulo-15.msav bytes=11036 load=ok launch=ok` |

The native path/storage contract is covered by `sd_paths` and
`settings_store` host tests. The read-only SD sector catalog
(`include/platform/sector_catalog.h`) is covered by
`tests/platform/test_sector_catalog.c` and by Azahar New/Old runs:
with raw `sector-serpulo-170.msav` and zlib `sector-erekir-64.msav` staged in
`sdmc:/3ds/min3dstry`, both profiles report `SECTOR_CATALOG count=2`, the two
sorted `SECTOR_SLOT` lines, and the matching bottom-HUD `Sectors:`/`Sector`
lines. Upstream `Saves.load()` now runs on the live path. Live campaign
UI/launch dialogs, capture rewards, and physical Old/New 3DS verification
remain open.

## Stock campaign map archive

`tools/verify_stock_campaign_archive.py --assets ASSETS_JAR --stage STAGING_DIR`
runs the ARM11 campaign map packer and compares every output map byte with
the official v160.8 archive. It checks 78 Serpulo maps, including 46
numbered sectors, and 17 Erekir maps.

Pass the staging directory and a new save directory to
`runStockCampaignArchiveJvm` with `-Pprobe=singleplayer`. The test reads
all 95 map headers, checks their preset associations, and decodes all 95
worlds through the upstream sector loader. The replay checks each sector
and its world dimensions across 17,662,472 tiles. It also resaves the
600 by 600 Littoral Shipyard and Serpulo 242 worlds. The stock patch paths
and contents survive a save and reload on the JVM. The unmodified v160.8 Java
server also reopens both saves through `verify_stock_campaign_archive.py
--check-saves <profile> --server <server-release.jar>` and checks the sector
IDs, dimensions and patch counts. Handheld loading and frame rate for these large maps
require separate measurements.

The ARM11 single-player heap exposes at least 2 MiB of existing garbage
collector scratch space from startup. TeaVM counts that scratch separately
from the managed cap. A 600×600 tile array can fill the stock mark queue
while the managed heap is still growing. The handheld allocator already
backs the maximum scratch reservation for the 32 MiB managed heap.
The [aligned Linux run](https://github.com/levkeveli/Min3DStry/actions/runs/45349275468)
confirmed first-power updates and 60 ticks in all five dense-sector fixtures
with that scratch floor. Physical-device measurements are pending.

The unified campaign reference checks that a logic processor with no matching
units leaves its binding cursor unallocated. It also verifies that successive
bindings use the stock round-robin order and that a catalog-size change
resets that cursor.
