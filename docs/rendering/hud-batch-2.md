# HUD emulator batch 2

Status: **PARTIAL — remaining checks are explicitly marked NOT VERIFIED.**

Build: local integration of the HUD stack after #524/#526/#527/#529/
#535/#542/#544/#546/#548, plus the #547 (pause settings) and #549 (minimap,
weather and performance toggles) branches. Device: Azahar `2126.2`, New 3DS,
`sector-serpulo-15`. The pause run replayed a CTM button movie, so the
transitions below exercise real X/A/B/d-pad input rather than a fixture state.

Artifacts: [pause navigation](evidence/hud-batch-2-pause-navigation-new3ds.png),
[settings row](evidence/hud-batch-2-settings-row-new3ds.png),
[research](evidence/hud-batch-2-research-new3ds.png),
[batch fixture pause](evidence/hud-batch-2-pause-new3ds.png),
[settings](evidence/hud-batch-2-settings-new3ds.png),
[load](evidence/hud-batch-2-load-new3ds.png),
[toggles](evidence/hud-batch-2-toggles-new3ds.png),
[UPSTREAM log](evidence/hud-batch-2-new3ds.log) and
[input log](evidence/hud-batch-2-pause-new3ds.log).

| Task | Result | Evidence |
| --- | --- | --- |
| Pause menu six rows (Resume/Research/Load/Save/Settings/Quit) | **PASS** | pause screenshot; rows fit after spacing fix |
| Pause X/A/B/D-pad transitions | **PASS** | CTM run: `open=true options=6`, two cancels, selection wrapped; [navigation frame](evidence/hud-batch-2-pause-navigation-new3ds.png) shows `> Research`, [settings frame](evidence/hud-batch-2-settings-row-new3ds.png) shows `> Settings` |
| Pause Research screen | **PASS** | A on Research logged `UPSTREAM_CAMPAIGN_RESEARCH_OPEN tree=serpulo visible=14`; [research frame](evidence/hud-batch-2-research-new3ds.png) shows `Research 1 / 14` and `B Back`; B resumed play |
| Settings submenu (4 toggles + Back) | **PASS** | settings screenshot; Back visible after spacing fix; CTM run opened it and toggled core items off/on (`index=0 value=false`, `index=0 value=true`) |
| Load Game re-entry | **PASS (partial)** | `UPSTREAM_BATCH_LOAD ok`, no `UPSTREAM_SP_ERROR`; full saved-state restore not asserted |
| Shared status panel | **PASS** | health/ammo/wave/timer/copper/FPS share one panel |
| Core Items toggle | **PASS** | item list hidden with `coreitems=false`; CTM toggle off/on |
| Minimap toggle | **PASS** | minimap hidden in toggles screenshot |
| Performance metrics | **PASS** | `FPS 2` row inside the status panel |
| Save Game | **PASS** | `UPSTREAM_PAUSE_MENU save=true` |
| Quit confirmation | **PASS** | A on Quit then A on confirmation logged `quit=true` and exited |
| Weather toggle | **NOT VERIFIED** | no active weather in the fixture |
| First objective in pause title | **NOT VERIFIED** | planet/sector visible; no objective row in this sector state |
| Depleted-item retention | **NOT VERIFIED** | host tracker cases only |
| Core-marker renderer orientation | **NOT VERIFIED** | host sampling only; Y orientation needs device/emulator visual check |

Fixes found during the batch: six-row pause spacing, Settings row spacing/Back,
FPS folded into the shared status panel, and source validation for the batch
fixture (no production `hud-batch.txt` hook). The CTM movie and log capture are
kept outside the production source; only these results and screenshots are
committed.
