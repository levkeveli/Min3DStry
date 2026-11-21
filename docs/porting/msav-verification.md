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
| Full save zlib inflate (native) | save-slot read + `msav_inflate` | Host + Azahar | `SECTOR_INFLATE planet=serpulo id=170 raw=11072 bytes=45955 limit=8388608 status=ok` |
| Inflated world header (native) | `msav_world` | Host + Azahar | `SECTOR_WORLD version=13 width=150 height=250 status=ok` |
| Map tile/entity walk (native) | `msav_world` | Host + Azahar | `SECTOR_TILES floors=37500 blocks=2768 data=0 entities=2 map=37500 entity_region=26 hash=bec356915bd24165` |
| Entity region walk (native) | `msav_world` | Host + Azahar | `SECTOR_ENTITIES mapping=0 teams=2 team_blocks=0 records=0 partial=0 hash=33a31f23a5cfb868` |
| World tile allocation (native) | `msav_world` | Host + Azahar | `SECTOR_ALLOC tiles=37500 bytes=1312830 hash=783fbd80e415dcd7 status=ok`; HUD `World: 150x250 build=2` |
| Per-tile state (native) | `msav_world` | Host + Azahar | `SECTOR_TILE_STATE tiles=37500 buildings=2 linked=2 hash=e35397041df8e5fe status=ok` |
| Save-world preview (native) | `save_world_preview` | Host + Azahar | 24×16 top-screen tile preview sampled from the resident arrays; 384 rects/frame, `World: 150x250 build=2` HUD |
| Building entity state (native) | `msav_world` | Host + Azahar | `SECTOR_BUILDINGS count=2 bytes=44 team=1 rot=0 health=6000 enabled=1 modules=9 items=0 item0=0:0 power=0:0.0 liquid=0:0:0.00 extra=8 partial=0 trunc=0 hash=4d6f71eda0848e18 status=ok` |
| Upstream decoder parity | `save-decode` + `msav_world` | JVM + Azahar | `UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680`; native `SECTOR_PARITY buildings=2 hash=3a375fd4f81c5680` |
| Save writer baseline | host `UpstreamSaveBaseline` | Host/JVM | `UPSTREAM_SAVE header=MSAV version=13` |

Rows marked `JVM + Azahar` run in Azahar `2126.2` on both New and Old 3DS
profiles; host rows run on the JVM only. Physical Old/New 3DS validation remains
part of `physical-hardware-parity`.

## Native reader scope and replacement plan

`src/platform/msav_world.c` is a verification-only reader. It is not wired into
gameplay, simulation, networking, or save writing; its only consumers are the
startup diagnostic, the bottom-HUD summary, and the top-screen preview. The
shipping loader must run the pinned upstream `SaveIO`/`MapIO` code through the
ARM11 TeaVM runtime. Once that loader decodes this fixture, delete the native
reader and preview. Until then no gameplay capability may claim parity from
these hashes.

`UpstreamSaveDecodeBaseline` (`-Pprobe=save-decode`) loads the same `.msav`
through upstream v160.8 `SaveIO` and prints base fields plus item, power-link,
and liquid values. The native `SECTOR_PARITY` hash is computed over the same
field sequence, so the two outputs must match:

```text
UPSTREAM_SAVE_BUILDING x=81 y=26 block=341 team=1 rot=0 health=6000 enabled=1 items=0 ... hash=b529451197a6e504
UPSTREAM_SAVE_STATE buildings=2 hash=3a375fd4f81c5680
SECTOR_PARITY buildings=2 hash=3a375fd4f81c5680 status=ok
```

Known limits of the native reader: the whole inflated stream must fit within
8 MiB, configured-block `data` records (7-byte payload) and populated entity
records are not exercised by this fixture, and item display storage is capped at
eight stacks while the parity hash still covers every stack read.

The real save is checked in at `tests/fixtures/glacier-v13.msav` together with
the upstream decode in `tests/fixtures/glacier-v13-decoded.txt`.
`tests/platform/test_msav_world.c` inflates and decodes it on the host and
asserts dimensions, building fields, per-building hashes, and the aggregate
`3a375fd4f81c5680`, so `./dev.sh test` covers the SaveIO parity without needing
Java or the upstream checkout.
