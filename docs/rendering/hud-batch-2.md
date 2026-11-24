# HUD emulator batch 2

Status: **PARTIAL — pending checks explicitly marked NOT VERIFIED.**

Build: local integration of the HUD stack on main after #524/#526/#527/#529/
#535/#542/#544/#546/#548, with #547 (pause settings) and #549 (minimap/weather/
performance toggles). Device: Azahar `2126.2`, New 3DS, `sector-serpulo-15`.

Artifacts: [pause](evidence/hud-batch-2-pause-new3ds.png),
[settings](evidence/hud-batch-2-settings-new3ds.png),
[load](evidence/hud-batch-2-load-new3ds.png),
[toggles](evidence/hud-batch-2-toggles-new3ds.png),
[UPSTREAM log](evidence/hud-batch-2-new3ds.log).

| Task | Result | Evidence |
| --- | --- | --- |
| Pause menu six rows (Resume/Research/Load/Save/Settings/Quit) | **PASS** | pause screenshot; rows fit after spacing fix |
| Settings submenu (4 toggles + Back) | **PASS** | settings screenshot; Back visible after spacing fix |
| Load Game re-entry | **PASS (partial)** | `UPSTREAM_BATCH_LOAD ok`, no `UPSTREAM_SP_ERROR`; full saved-state restore not asserted |
| Shared status panel | **PASS** | health/ammo/wave/timer/copper/FPS share one panel |
| Core Items toggle | **PASS** | item list hidden with `coreitems=false` |
| Minimap toggle | **PASS** | minimap hidden in toggles screenshot |
| Performance metrics | **PASS** | `FPS 2` row inside the status panel |
| Weather toggle | **NOT VERIFIED** | no active weather in the fixture |
| Pause X/A/B/D-pad transitions | **NOT VERIFIED** | fixture opens menus; no button injection |
| Research screen from pause | **NOT VERIFIED** | not opened in batch |
| Depleted-item retention | **NOT VERIFIED** | host tracker cases only |
| Core-marker renderer orientation | **NOT VERIFIED** | host sampling only; Y orientation needs device/emulator visual check |

Fixes found during the batch: six-row pause spacing, Settings row spacing/Back,
FPS folded into the shared status panel, and source validation for the batch
fixture (no production `hud-batch.txt` hook).
