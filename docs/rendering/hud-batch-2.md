# HUD emulator batch 2

Status: **PARTIAL — remaining checks are explicitly marked NOT VERIFIED.**

## Build and scenario

| Field | Value |
| --- | --- |
| Artifact | `upstream-probe.3dsx`, 201,807,952 bytes |
| SHA-256 | `2a8ec2bbaf3f2f0af0c956207c79812e146130434d3c4606f610531ee2127e9f` |
| Source | local `test/hud-batch-2` integration harness (tip `51b9672`), including the then-uncommitted #549 settings-row/FPS-fold working tree change |
| Built | 2026-11-23 22:48 local, not a CI artifact |
| Emulator | Azahar `2126.2` (`HEAD-219d743`), New 3DS profile, Windows host |
| Scenario | campaign slot `sector-serpulo-15.msav` (map `Editor_Playtesting`, 150x250, wave 1, copper 0) |

The artifact is the test-only harness build with the `hud-batch.txt` fixture
hook; that hook is not part of the production source. The startup fixture run
(`perf=1,nominimap=1,noitems=1`) and the CTM input runs below are separate
boots. The CTM runs exercise real X/A/B/d-pad events.

Artifacts: [pause navigation](evidence/hud-batch-2-pause-navigation-new3ds.png),
[settings row](evidence/hud-batch-2-settings-row-new3ds.png),
[research](evidence/hud-batch-2-research-new3ds.png),
[after Load](evidence/hud-batch-2-after-load-new3ds.png),
[toggles off/FPS on](evidence/hud-batch-2-toggles-off-new3ds.png),
[batch fixture pause](evidence/hud-batch-2-pause-new3ds.png),
[settings](evidence/hud-batch-2-settings-new3ds.png),
[batch fixture load](evidence/hud-batch-2-load-new3ds.png),
[batch fixture toggles](evidence/hud-batch-2-toggles-new3ds.png),
[UPSTREAM fixture log](evidence/hud-batch-2-new3ds.log),
[pause input log](evidence/hud-batch-2-pause-new3ds.log) and
[Load/toggle log](evidence/hud-batch-2-load-toggles-new3ds.log).

## Results

| Task | Result | Evidence |
| --- | --- | --- |
| Pause menu six rows (Resume/Research/Load/Save/Settings/Quit) | **PASS** | pause screenshot; rows fit after spacing fix |
| Pause X/A/B/D-pad transitions | **PASS** | CTM run: `open=true options=6`, two cancels, selection wrapped; [navigation frame](evidence/hud-batch-2-pause-navigation-new3ds.png) shows `> Research`, [settings frame](evidence/hud-batch-2-settings-row-new3ds.png) shows `> Settings` |
| Pause Research screen | **PASS** | A on Research logged `UPSTREAM_CAMPAIGN_RESEARCH_OPEN tree=serpulo visible=14`; [research frame](evidence/hud-batch-2-research-new3ds.png) shows `Research 1 / 14` and `B Back`; B resumed play |
| Settings submenu (4 toggles + Back) | **PASS** | settings screenshot; Back visible after spacing fix; CTM runs toggled every row (`index=0..3`) |
| Load Game re-entry | **PASS (partial)** | A on Load logged `load=true`, then a second `UPSTREAM_SP_LOADED source=campaign map=Editor_Playtesting size=150x250 wave=1`; [after-load frame](evidence/hud-batch-2-after-load-new3ds.png). Full saved-state equality and minimap re-upload are not asserted (the minimap marker is one-shot). The map name is the save's own name, not Ground Zero |
| Shared status panel | **PASS** | health/ammo/wave/timer/copper share one panel; FPS joins it when enabled |
| Core Items toggle | **PASS** | CTM toggled `index=0 value=false` then `value=true`; [fixture screenshot](evidence/hud-batch-2-settings-new3ds.png) shows the four rows |
| Minimap toggle | **PASS** | CTM toggled `index=1 value=false`; [toggles-off frame](evidence/hud-batch-2-toggles-off-new3ds.png) has no top-right minimap |
| Performance metrics | **PASS** | CTM toggled `index=3 value=true`; the same frame shows `FPS 2` in the shared status panel |
| Save Game | **PASS** | `UPSTREAM_PAUSE_MENU save=true` |
| Quit confirmation | **PASS** | A on Quit then A on confirmation logged `quit=true` and exited |
| Weather toggle | **PARTIAL** | CTM toggled `index=2 value=false` (setting off); the fixture has no active weather, so the hidden overlay itself is **NOT VERIFIED** |
| First objective in pause title | **NOT VERIFIED** | planet/sector visible; no objective row in this sector state |
| Depleted-item retention | **NOT VERIFIED** | host tracker cases only |
| Core-marker renderer orientation | **NOT VERIFIED** | host sampling only; Y orientation needs device/emulator visual check |

The fixture run that produced [UPSTREAM fixture log](evidence/hud-batch-2-new3ds.log)
started with `perf=1,nominimap=1,noitems=1`, so its screenshots show that
startup state; they are not evidence of an exercised toggle. The CTM run above
is the toggle evidence.

Fixes found during the batch: six-row pause spacing, Settings row spacing/Back,
FPS folded into the shared status panel, and source validation for the batch
fixture (no production `hud-batch.txt` hook). The CTM movies and capture scripts
are kept outside the production source; only these results and screenshots are
committed.
