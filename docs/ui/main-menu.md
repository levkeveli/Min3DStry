# Stock title menu shell

Status: **harness-only**. The navigation shell runs on host and in Azahar; the upstream Scene2D
`MenuFragment`, the core database, schematics and RTS command are not ported.

## Upstream source

- `core/src/mindustry/ui/fragments/MenuFragment.java` at
  [`19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/core/src/mindustry/ui/fragments/MenuFragment.java)
- Labels: `core/assets/bundles/bundle.properties` (`play = Play`, `campaign = Campaign`,
  `joingame = Join Game`, `customgame = Custom Game`, `loadgame = Load Game`,
  `database.button = Database`, `editor = Editor`, `mods = Mods`, `settings = Settings`, `quit = Quit`).

Upstream desktop hierarchy:

```text
Play      -> Campaign, Join Game, Custom Game, Load Game
Database  -> Schematics, Core Database, About
Editor
Mods
Settings
Quit
```

## What this port implements

`include/ui/main_menu.h` / `src/ui/main_menu.c` model that hierarchy as a five-screen state machine
(`title`, `play`, `settings`, `database`, `about`) with the same keys and English labels.

| Branch | Behaviour |
| --- | --- |
| Play -> Campaign | closes the menu and resumes the existing offline sector session (`MAIN_MENU action=play.campaign`) |
| Settings -> Audio | toggles the live mixer mute (`MAIN_MENU action=settings.audio muted=<0\|1>`) |
| Settings -> Lighting | toggles the live scene lighting (`MAIN_MENU action=settings.lighting lighting=<0\|1>`) |
| Settings -> Reset camera | recentres the camera (`MAIN_MENU action=settings.camera reset=1`) |
| Settings -> Back | returns to the title menu |
| Database | opens the upstream `database.button` submenu |
| Database -> Core Database | opens the generated items/blocks catalog (D-pad scrolls, L/R switches Items/Blocks, B returns) |
| Database -> About | opens the About page (upstream version, source commit, GPL-3.0, port status) |
| About -> Back | returns to the Database submenu (B from Database returns to the title) |
| Quit | exits the app |
| Join Game / Custom Game / Load Game / Schematics / Editor / Mods | rendered as `-` (unavailable); log `MAIN_MENU unavailable=<key>` |

The settings rows are the port's live audio/lighting/camera controls, not upstream's tabbed settings
dialog. The title, Play submenu and labels match the pinned upstream source; the rest of the upstream
menu tree is intentionally visible but disabled so the gap is obvious rather than silently missing.

### Core Database browser

`include/ui/content_catalog.h` and the generated `include/ui/content_catalog_data.h` expose the pinned
v160.8 bundle entries (`item.<id>.name`, `item.<id>.description`, `block.<id>.name`,
`block.<id>.description`): **22 items** and **258 blocks**. Blocks without a `description` entry
(parallax, cliffs, boulders, floor variants) are environment-only and excluded. The console view shows
12 rows with wrap-around scrolling; L/R switches Items/Blocks; A opens a wrapped description detail
(A or B returns); B leaves the catalog.

Data and header are reproducible:

```bash
python3 tools/gen_content_catalog.py --bundle /path/to/bundle.properties
python3 tools/gen_content_catalog.py --check   # also runs in ./dev.sh test
```

Upstream category tabs, icons, descriptions and unlock state are not modelled yet.

## Input

- D-pad: move selection (wraps)
- A: confirm
- B: back (Play/Settings -> title; no-op on title)
- START: exit

While the title menu is open the demo world keeps running behind it and gameplay input is ignored.
The menu is drawn on the bottom-screen console; the top screen keeps the scene visible.

## Verification

- `tests/ui/test_main_menu.c` (host): labels/counts, wrapping, Play entry and Campaign exit,
  unavailable branches, settings toggles and back navigation, quit, screen names. `MAIN_MENU PASS`.
- Azahar 2126.2, New 3DS and Old 3DS profiles (build `de3e249` + this change): boot renders the menu and
  logs `MAIN_MENU screen=title selected=play items=6`; scripted D-pad/A/B input produces:

```text
MAIN_MENU screen=settings selected=audio
MAIN_MENU action=settings.audio muted=1
MAIN_MENU action=settings.lighting lighting=0
MAIN_MENU action=settings.camera reset=1
MAIN_MENU screen=title selected=play
MAIN_MENU screen=play selected=campaign
MAIN_MENU action=play.campaign
```

  After Campaign the menu closes and the HUD resumes (`Tick: ... (RUN)`, `Audio: ... (MUTED)`). Old 3DS
  renders the same hierarchy; both runs keep `SECTOR_LOADER loaded=1` and `PARITY hash=0x53a483c7`.

Scripted Database/About navigation on both profiles:

```text
MAIN_MENU screen=database selected=schematics
MAIN_MENU screen=about selected=back
MAIN_MENU screen=database selected=schematics   # B from About
MAIN_MENU screen=title selected=play            # B from Database
```

The About page shows `Mindustry v160.8`, `Upstream: Anuken/Mindustry`, `Commit: 19a0f40d4451`,
`License: GPL-3.0`, and `Port: Min3DStry (in progress)`. Schematics stays visible as `-` and logs
`MAIN_MENU unavailable=<key>`.

Core Database navigation on both profiles:

```text
MAIN_MENU screen=catalog selected=beryllium   # 1/22, Items
MAIN_MENU catalog=Blocks count=258            # L switches category
MAIN_MENU screen=database selected=database   # B returns
```

The catalog renders `Core Database 1/22` (then `1/258` for Blocks), the category name, and 12 rows with
wrap-around scrolling. Screenshots confirm the Items list (`Beryllium … Phase Fabric`), the Blocks list
(`Additive Reconstructor … Basic Assembler Module`), and both detail pages:

```text
MAIN_MENU catalog=Items selected=copper detail=1
MAIN_MENU catalog=Blocks selected=additive-reconstructor detail=1
```

`Copper` shows "Used in all types of construction and ammunition."; `Additive Reconstructor` shows
"Upgrades inputted units to the second tier." Descriptions are the pinned bundle prose with formatting
tags and literal newlines normalised for the console.

## Remaining work

- Port the real upstream Scene2D `MenuFragment` rendering instead of the console shell.
- Schematic copy/paste and RTS unit selection/command.
- Database category tabs, icons, descriptions and unlock state (the current browser lists the pinned
  bundle names only).
- The About page is a static port page, not the upstream links/credits dialog.
- Upstream settings dialog (graphics/audio/game/controls tabs) instead of the live-control subset.
