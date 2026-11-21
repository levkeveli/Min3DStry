# Upstream `.msav` Map & Save Verification

This matrix maps the `msav-map-save-compat` capability to merged probes and the
markers they emit on the JVM and/or ARM11/Azahar.

| Area | Probe | Platform | Verified marker |
| --- | --- | --- | --- |
| Map metadata and map load | `map`, `world` | JVM + Azahar | `UPSTREAM_MAP name=Glacier width=150 height=250 version=7`; `UPSTREAM_WORLD width=150 height=250 tiles=37500 buildings=2` |
| v13 save load | `world-save` | JVM + Azahar | `UPSTREAM_SAVE version=13 marker=ok` |
| v13 save write + reload | `world-roundtrip` | JVM + Azahar | `UPSTREAM_SAVE_ROUNDTRIP version=13 marker=ok` |
| Sector `.msav` write + reload | `campaign-sector` | JVM + Azahar | `UPSTREAM_CAMPAIGN_SECTOR slot=sector-serpulo-170.msav bytes=11072 version=13 marker=ok` |
| Sector slot metadata tags | `campaign-slot-meta` | JVM + Azahar | `UPSTREAM_CAMPAIGN_SLOT_META slot=sector-serpulo-15.msav ... preset=groundZero wave=1 size=150x250 meta=ok` |
| Sector save launch | `campaign-launch` | JVM + Azahar | `UPSTREAM_CAMPAIGN_LAUNCH slot=sector-serpulo-15.msav ... sector=15 wave=5 world=150x250 launch=ok` |
| Save rejection/backup resilience | host `UpstreamSaveResilienceProbe` | Host/JVM | `UPSTREAM_SAVE_RESILIENCE_PASS`, `MIN3DSTRY_SAVE_POLICY existing=protected interrupted=rejected` |
| Compressed header version (native) | save-slot probe + `msav_inflate` | Host + Azahar | `MIN3DSTRY SAVE_SLOT present=1 valid=1 compressed=1 version=13 bytes=11072`; `SAVE_META map=Glacier wave=1`; HUD `Save: v13 11072B`, `Map: Glacier wave=1` |
| Save-slot backup fallback | save-slot probe | Host + Azahar | invalid primary + valid `*-backup.msav` -> `SAVE_SLOT present=1 valid=1 version=13`; HUD `Save: v13` |
| Full save zlib inflate (native) | save-slot read + `msav_inflate` | Host + Azahar | `SECTOR_INFLATE planet=serpulo id=170 raw=11072 bytes=45955 status=ok` |
| Inflated world header (native) | `msav_world` | Host + Azahar | `SECTOR_WORLD version=13 width=150 height=250 status=ok` |
| Map tile/entity walk (native) | `msav_world` | Host + Azahar | `SECTOR_TILES floors=37500 blocks=2768 data=0 entities=2 map=37500 entity_region=26 hash=bec356915bd24165` |
| Entity region walk (native) | `msav_world` | Host + Azahar | `SECTOR_ENTITIES mapping=0 teams=2 team_blocks=0 records=0 partial=0 hash=33a31f23a5cfb868` |
| World tile allocation (native) | `msav_world` | Host + Azahar | `SECTOR_ALLOC tiles=37500 bytes=262500 hash=783fbd80e415dcd7 status=ok`; HUD `World: 150x250 build=2` |
| Building entity state (native) | `msav_world` | Host + Azahar | `SECTOR_BUILDINGS count=2 bytes=44 team=1 rot=0 health=6000 enabled=1 modules=9 items=0 item0=0:0 power=0:0.0 liquid=0:0:0.00 extra=8 partial=0 trunc=0 hash=5b2a6cb142f2813a status=ok` |
| Save writer baseline | host `UpstreamSaveBaseline` | Host/JVM | `UPSTREAM_SAVE header=MSAV version=13` |

Rows marked `JVM + Azahar` run in Azahar `2126.2` on both New and Old 3DS
profiles; host rows run on the JVM only. Physical Old/New 3DS validation remains
part of `physical-hardware-parity`.
