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
| Compressed header version (native) | save-slot probe + `msav_inflate` | Host + Azahar | `MIN3DSTRY SAVE_SLOT present=1 valid=1 compressed=1 version=13 bytes=11072`; HUD `Save: v13 11072B` |
| Save writer baseline | host `UpstreamSaveBaseline` | Host/JVM | `UPSTREAM_SAVE header=MSAV version=13` |

Rows marked `JVM + Azahar` run in Azahar `2126.2` on both New and Old 3DS
profiles; host rows run on the JVM only. Physical Old/New 3DS validation remains
part of `physical-hardware-parity`.
