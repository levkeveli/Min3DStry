# Stock title menu shell

The unified Java runtime opens its title menu on ordinary startup. It uses the
live Core Database and the existing Campaign, Join Game, Custom Game, Load
Game and Editor routes. Settings uses the same saved HUD preferences as the
pause menu. About shows the upstream links and the complete credits packaged
from the pinned release. Hold X to join directly, L for Custom Game, R for
Campaign, or L and R together for the Editor.

To launch the offline session directly, pass `--direct` through the Homebrew
Launcher or set `sd:/3ds/min3dstry/startup.txt` to `direct`. Holding Y opens
the title even when the SD preference requests direct startup.

The [New 3DS offline replay at `c225586`](https://github.com/levkeveli/Min3DStry/actions/runs/45727286685/artifacts/15076074292)
opened the database, reopened the saved HUD setting, rendered 200 stock
contributors, opened a stock schematic, and loaded and exited a Ground Zero
save. The [multiplayer replay](https://github.com/levkeveli/Min3DStry/actions/runs/45727286685/artifacts/15076517911)
retained the stock German locale and joined and left an unmodified v160.8
Java server from the title's Join Game route.

## Stock settings

Settings in the shared title/pause routes (the offline and unified runtimes use the same menu code) lists the five handheld HUD toggles, then the stock
Game, Graphics and Sound categories. Each category is driven by
`UpstreamStockSettings`, the mobile-applicable subset of
`SettingsMenuDialog.addSettings()` at the pinned revision: checks toggle with A,
sliders step with Left/Right using the stock ranges and formatting (`%`, `x`,
seconds), and every change is written through `arc.Settings` and saved. The
category list, keys and defaults are asserted on the host and the marker is
grep-enforced in the smoke job:

```
UPSTREAM_STOCK_SETTINGS entries=35 checks=26 sliders=9 labels=ok source=ok format=ok clamp=ok toggle=ok
```

The [offline replay](https://github.com/levkeveli/Min3DStry/actions/runs/45727286685/artifacts/15076074292)
also reopens a saved HUD setting on Azahar, covering the handheld persistence
path. Mod handling is not part of this shell: v160.8 loads mods as JVM code via
`Mods.platform.loadJar` (a `URLClassLoader`), which the TeaVM/ARM11 runtime
cannot execute.

## Unified ARM11 Core Database

Open the Core Database from the offline pause menu with Y or from the unified
title route. It reads the pinned v160.8 content registry and localized assets,
including stock icons, unlock state, Serpulo and Erekir filters, search and
entry details. The grouped replay on both 3DS emulator profiles opened Copper
details, switched the All tab to Erekir and returned cleanly. The host reference
also checks all 389 visible stock entries for a supported stat display path.
Physical console verification is pending.

[ARM11 offline replay and screenshots](https://github.com/levkeveli/Min3DStry/actions/runs/45439198209/artifacts/14945967038)

## Historical native harness

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
| Database -> Schematics | opens the read-only list of 210 pinned base-part `.msch` files |
| Database -> Core Database | opens the generated items/blocks catalog (D-pad scrolls, L/R switches Items/Blocks, B returns) |
| Database -> About | opens the About page (upstream version, source commit, GPL-3.0, port status) |
| About -> Back | returns to the Database submenu (B from Database returns to the title) |
| Quit | exits the app |
| Join Game / Custom Game / Load Game / Editor / Mods | rendered as `-` (unavailable); log `MAIN_MENU unavailable=<key>` |

The settings rows are the port's live audio/lighting/camera controls, not upstream's tabbed settings
dialog. The title, Play submenu and labels match the pinned upstream source; the rest of the upstream
menu tree is intentionally visible but disabled so the gap is obvious rather than silently missing.

### Core Database browser

`include/ui/content_catalog.h` and the generated `include/ui/content_catalog_data.h` expose the pinned
v160.8 bundle entries (`item.<id>.name`, `item.<id>.description`, `block.<id>.name`,
`block.<id>.description`) plus each block's upstream `Category` from `Blocks.java`: **22 items** and
**258 blocks**. Blocks without a `description` entry (parallax, cliffs, boulders, floor variants) are
environment-only and excluded. The console view shows 12 rows with wrap-around scrolling; L/R switches
Items/Blocks; X cycles the block-category filter (`All`, `distribution`, `production`, …); A opens a
wrapped description detail showing the category (A or B returns); B leaves the catalog.

Data and header are reproducible:

```bash
python3 tools/gen_content_catalog.py --bundle /path/to/bundle.properties
python3 tools/gen_content_catalog.py --check   # also runs in ./dev.sh test
```

Upstream category tabs, icons, descriptions and unlock state are not modelled yet.

### Schematics (read-only)

`include/ui/schematics_catalog.h` and the generated `include/ui/schematics_catalog_data.h` list the
**210** pinned base-part schematics from the `core/assets/baseparts` `.msch` files at `19a0f40d4451`.
The generator decompresses each `.msch` (magic `msch`, version byte, zlib payload), reads
`width/height`, tags, block count and block-type names, and stores the first five types as a summary.

The list shows the upstream file name (truncated to the console width) with wrap-around scrolling; A
opens a detail page with dimensions, block count and the block summary; B returns. **L/R** switches
between the built-in table and user files scanned from `sd:/3ds/min3dstry/schematics/`: the same
metadata parser runs on each `*.msch` (bounded to 64 files and 64 KiB per file, malformed files
skipped), and the empty state points at the expected path. It is read-only: importing into the game,
exporting or editing schematics is not implemented.

```bash
python3 tools/gen_schematics_catalog.py --dir /path/to/core/assets/baseparts
python3 tools/gen_schematics_catalog.py --check   # also runs in ./dev.sh test
```

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
MAIN_MENU filter=distribution count=28
```

`Copper` shows "Used in all types of construction and ammunition."; `Additive Reconstructor` shows
"Upgrades inputted units to the second tier." Descriptions are the pinned bundle prose with formatting
tags and literal newlines normalised for the console. The block detail adds `Category: distribution`
(from `Blocks.java`), and the filtered Blocks list shows `Blocks / distribution` with its own count.

Schematics navigation on both profiles:

```text
MAIN_MENU screen=schematics selected=<file> detail=0
MAIN_MENU screen=schematics selected=<file> detail=1
```

The list shows `Schematics n/210` with the upstream file names; the detail page shows `WxH, N blocks`
and the first block types (for example `2x2, 1 blocks` / `power-node-large`).

With two fixtures staged at `sd:/3ds/min3dstry/schematics/` (`a single large power node.msch`,
`battery node.msch`), both profiles report:

```text
MAIN_MENU source=sd count=2
MAIN_MENU screen=schematics selected=battery node detail=1
```

and render `Schematics 2/2`, `Source: SD card`, `battery node`, `5x3, 2 blocks`,
`battery-large, power-node-large`.

### Historical harness gaps

- Port the real upstream Scene2D `MenuFragment` rendering instead of the console shell.
- Schematic import/export and RTS unit selection/command.
- Database category tabs, icons, descriptions and unlock state (the current browser lists the pinned
  bundle names only).
- The About page is a static port page, not the upstream links/credits dialog.
- Upstream settings dialog (graphics/audio/game/controls tabs) instead of the live-control subset.

## New 3DS local server browser

Open Play, then Join Game. The browser sends the pinned Arc discovery
datagram to the local IPv4 broadcast on the stock server port and to the
Mindustry multicast group. During the three-second scan, Back returns to
Play. The results list keeps one response per address. Select a server to
view the stock advertisement's map, mode, player count and version, then
select Join Game. Address opens the manual keyboard and Refresh scans again.

libctru handles the sockets, while the pinned Mindustry NetworkIO reader
decodes the same bytes as the Java PC client. The loopback host reference
checks a real stock v160.8 server reply, duplicate suppression, malformed
packet rejection and unsigned port handling. The New 3DS smoke replay uses
a stock Glacier session on port 6567 and removes the endpoint file so the
join must use the discovered server. Physical LAN verification is pending.

In the [New 3DS Azahar replay at `0eb9015`](https://github.com/levkeveli/Min3DStry/actions/runs/45727812457/artifacts/15076928162),
the German Join Game route discovered one stock v160.8 Glacier session.
The details screen showed the stock mode, player count, version and
advertised address. The server log recorded the same client connecting and
disconnecting after the gameplay replay.

The [integrated replay at `879dd83`](https://github.com/levkeveli/Min3DStry/actions/runs/46044023907/artifacts/15222303071)
repeated the German title route and the stock server's movement, wave, and
leave checks. The details capture shows Glacier, Attack, build 160, and the
advertised port 6567.
