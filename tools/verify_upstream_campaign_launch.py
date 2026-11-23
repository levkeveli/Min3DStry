#!/usr/bin/env python3
"""Replay stock campaign starters or a numbered-sector departure in Azahar."""

from __future__ import annotations

import argparse
import re
import shutil
import struct
import tempfile
import time
from pathlib import Path

import verify_upstream_singleplayer as sp
from campaign_settings_fixture import stock_console

NUMBERED_GENERATION_SECONDS = 330

STARTERS = {
    "serpulo": ("sector-serpulo-170.msav", "Ground_Zero", 256),
    "erekir": ("sector-erekir-10.msav", "The_Onset", 512),
}


def write_replay(path: Path, model: str, planet: str, manual_cargo: bool = False,
                 in_game_index: int | None = None) -> None:
    windows = [(0, 24, 1 << 8)]
    confirm, end = 50, 120
    if in_game_index is not None:
        windows = [(37, 39, 1), (76, 78, (1 << 8) | sp.KEY_SELECT), (91, 93, 1 << 1),
                   (102, 104, (1 << 8) | sp.KEY_SELECT)]
        windows.extend((118 + i * 4, 120 + i * 4, 1 << 7) for i in range(in_game_index))
        select = 118 + in_game_index * 4
        windows.append((select, select + 2, 1))
        if manual_cargo:
            windows.extend([(select + 6, select + 8, 1 << 10), (select + 11, select + 13, 1 << 11),
                            (select + 16, select + 18, (1 << 8) | (1 << 4)), (select + 21, select + 23, 1 << 1)])
        confirm, end = select + 26, select + 110
    if planet == "numbered":
        # Azahar pauses at the end of the movie, including while the original generator is running.
        end = confirm + NUMBERED_GENERATION_SECONDS
    windows.extend([(confirm, confirm + 2, 1), (end, end + 2, sp.KEY_START)])
    if planet == "erekir":
        windows.append((43, 45, 1 << 7))
    if manual_cargo and in_game_index is None:
        windows.extend([(30, 32, 1 << 10), (35, 37, 1 << 11), (40, 42, (1 << 8) | (1 << 4)), (45, 47, 1 << 1)])
    frames = sp.CTM_SAMPLES_PER_SECOND * (end + 10)
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, sp.CTM_REVISION, 1780000000,
                         0x4D334453, b"Min3DStry Campaign Launch", 0, frames, 0)
    idle = struct.pack("<B6x", 1)
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            second = frame // sp.CTM_SAMPLES_PER_SECOND
            buttons = next((mask for start, end, mask in windows if start <= second < end), 0)
            pad = struct.pack("<BHhh", 0, buttons, 0, 0)
            movie.write(pad + (pad if buttons else idle))
            if model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(pad)


def verify(model: str, planet: str, rom: Path, azahar: str, config: Path, log: Path, output: Path,
           departure: Path | None = None, manual_cargo: bool = False,
           in_game: bool = False) -> tuple[int, int] | None:
    if departure:
        sector_id, size = (int(value) for value in (departure / "destination.txt").read_text().split())
        name, world = f"sector-serpulo-{sector_id}.msav", f"Serpulo;_Sector_{sector_id}"
        if in_game:
            destinations = {int(row[0]): int(row[1]) for row in
                            (line.split() for line in (departure / "destinations.txt").read_text().splitlines())}
    else:
        name, world, size = STARTERS[planet]
    prefix = f"{model}-{planet}"
    sd = (output / "virtual-sd" / prefix).resolve()
    root = sd / "3ds/min3dstry"
    root.mkdir(parents=True)
    saves = root / "saves"
    settings = root / "settings.bin"
    override = root / "campaign-select.txt"
    custom_name = "campaign-test.msch"
    staged_schematic = root / "schematics" / custom_name
    custom_source = departure / "schematics" / custom_name if departure else None
    has_custom = custom_source is not None and custom_source.is_file()
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    replay = output / f"{prefix}.ctm"
    row = int((departure / "campaign-select.txt").read_text()) if in_game else None
    write_replay(replay, model, planet, manual_cargo, row)
    staged_saves = []
    try:
        if departure:
            saves.mkdir(parents=True, exist_ok=True)
            for starter, _, _ in STARTERS.values():
                slot = saves / starter
                staged_saves.append(slot)
                shutil.copyfile(departure / "saves" / starter, slot)
            shutil.copyfile(departure / "settings.bin", settings)
            if not in_game:
                shutil.copyfile(departure / "campaign-select.txt", override)
            if has_custom:
                staged_schematic.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(custom_source, staged_schematic)
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
        try:
            menu_marker = (rf"UPSTREAM_CAMPAIGN_LOADOUT sector={sector_id} origin=170 affordable=true\b"
                           if departure else r"UPSTREAM_CAMPAIGN_MENU slots=2\b")
            if in_game:
                menu = r"UPSTREAM_CAMPAIGN_WORLD_MENU sector=170\b"
                sp._wait_for_log(log, proc, rf"{menu}(?s:.*?)UPSTREAM_CAMPAIGN_CANCEL(?s:.*?){menu}", 170)
            if in_game:
                launch = r"UPSTREAM_CAMPAIGN_LOADOUT sector=(\d+) origin=170 affordable=true\b"
                text = sp._wait_for_log(log, proc, launch, 120)
                selected = re.search(launch, text)
                assert selected is not None
                sector_id = int(selected.group(1))
                name, world = f"sector-serpulo-{sector_id}.msav", f"Serpulo;_Sector_{sector_id}"
                if sector_id not in destinations:
                    raise AssertionError(f"{prefix}: sector {sector_id} was not an eligible stock destination")
                size = destinations[sector_id]
            else:
                sp._wait_for_log(log, proc, menu_marker, 120)
            time.sleep(2)
            menu = rpc.screenshot()
            if sp.az.inspect_png(menu)["top_distinct_colors"] < 8:
                raise AssertionError(f"{prefix}: the starter selector did not render")
            (output / f"{prefix}-menu.png").write_bytes(menu)
            if manual_cargo:
                sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_CARGO item=copper amount=8\b", 70)
            loaded = rf"UPSTREAM_SP_LOADED source=campaign-new map={re.escape(world)} size={size}x{size}\b"
            sp._wait_for_log(log, proc, loaded, 600 if departure else 150)
            sp._wait_for_log(log, proc, rf"{loaded}(?s:.*?)UPSTREAM_SP_FRAME .*\bsprites=[1-9]\d*\b", 120)
            screenshot = rpc.screenshot()
            if sp.az.inspect_png(screenshot)["top_distinct_colors"] < 8:
                raise AssertionError(f"{prefix}: the starter world did not render")
            (output / f"{prefix}.png").write_bytes(screenshot)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={re.escape(name)}\b", 480 if departure else 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
            (output / f"{prefix}-{name}").write_bytes((saves / name).read_bytes())
            (output / f"{prefix}-settings.bin").write_bytes(settings.read_bytes())
            origin_slot = saves / "sector-serpulo-170.msav"
            if in_game:
                shutil.copyfile(origin_slot, output / f"{prefix}-origin.msav")
            if departure and any(slot.read_bytes() != (departure / "saves" / slot.name).read_bytes()
                                 for slot in staged_saves if not in_game or slot != origin_slot):
                raise AssertionError(f"{prefix}: departure overwrote a preexisting world save")
            print(f"[PASS] {prefix}: {world} rendered and saved at {size}x{size}", flush=True)
            return (sector_id, size) if departure else None
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / f"{prefix}.log").write_bytes(log.read_bytes())
    finally:
        for path, original in originals.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)


def check_stock(java: str, server: Path, save: Path, settings: Path, planet: str) -> None:
    name, _, size = STARTERS[planet]
    sector_id = int(Path(name).stem.rsplit("-", 1)[1])
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-launch-readback-") as temp:
        root = Path(temp)
        shutil.copyfile(save, root / "port.msav")
        (root / "config").mkdir()
        shutil.copyfile(settings, root / "config/settings.bin")
        script = f"""
            const planet = Packages.mindustry.content.Planets.{planet};
            const sector = planet.sectors.get({sector_id});
            const core = Packages.arc.Core, io = Packages.mindustry.io.SaveIO;
            const fi = Packages.arc.files.Fi;
            const port = fi.get("port.msav"), stock = fi.get("stock.msav");
            if (core.settings.getInt("{planet}-last-sector", -1) !== {sector_id} ||
                String(core.settings.getString("last-sector-save", "")) !== "{Path(name).stem}" ||
                !io.getMeta(port).rules.sector.equals(sector)) {{
                throw new Error("Stock campaign slot or last-sector information differs");
            }}
            function digest() {{
                const rules = Vars.state.rules;
                return [Vars.world.width(), Vars.world.height(), rules.sector.id, rules.winWave,
                    rules.attackMode, rules.env, rules.waves, Vars.state.wave,
                    String(rules.defaultTeam.core().block.name)].join(",");
            }}
            Vars.logic.reset();
            Vars.world.loadSector(sector);
            Vars.logic.play();
            const expected = digest();
            io.write(stock);
            Vars.logic.reset();
            io.load(stock, Vars.world.makeSectorContext(sector));
            const reopened = digest();
            Vars.logic.reset();
            io.load(port, Vars.world.makeSectorContext(sector));
            if (!expected.startsWith("{size},{size},{sector_id},") || reopened !== digest()) {{
                throw new Error("Expected " + reopened + ", got " + digest());
            }}
            return "UPSTREAM_PC_FRESH_CAMPAIGN_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_FRESH_CAMPAIGN_REOPENED")
    print(f"[PASS] stock v160.8 reopened {save.name} and its settings", flush=True)


def check_numbered_stock(java: str, server: Path, output: Path, model: str, departure: Path,
                         manual_cargo: bool = False, in_game: bool = False,
                         destination: tuple[int, int] | None = None) -> None:
    cargo, remaining = (8, 15) if manual_cargo else (23, 0)
    sector_id, size = destination or tuple(int(value) for value in (departure / "destination.txt").read_text().split())
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-numbered-readback-") as temp:
        root = Path(temp)
        (root / "config").mkdir()
        shutil.copyfile(output / f"{model}-numbered-settings.bin", root / "config/settings.bin")
        shutil.copyfile(output / f"{model}-numbered-sector-serpulo-{sector_id}.msav", root / "port.msav")
        origin = output / f"{model}-numbered-origin.msav" if in_game else departure / "saves/sector-serpulo-170.msav"
        shutil.copyfile(origin, root / "origin.msav")
        custom_source = departure / "schematics/campaign-test.msch"
        custom_expected = custom_source.is_file()
        if custom_expected:
            shutil.copyfile(custom_source, root / custom_source.name)
        custom_check = """
            if (String(Packages.arc.Core.settings.getString("lastloadout-core-foundation", "")) !== "campaign-test") {
                throw new Error("Expected the selected upstream custom schematic");
            }
        """ if custom_expected else ""
        script = f"""
            {custom_check}
            const sectors = Packages.mindustry.content.Planets.serpulo.sectors;
            const items = Packages.mindustry.content.Items;
            const from = sectors.get(170), to = sectors.get({sector_id});
            const io = Packages.mindustry.io.SaveIO, slot = Packages.arc.files.Fi.get("port.msav");
            function terrainDigest() {{
                const crc = new Packages.java.util.zip.CRC32();
                for (var y = 0; y < Vars.world.height(); y++) {{
                    for (var x = 0; x < Vars.world.width(); x++) {{
                        var tile = Vars.world.tile(x, y);
                        var ids = [tile.floor().id, tile.overlay().id, tile.block().id];
                        for (var i = 0; i < ids.length; i++) {{
                            var id = ids[i];
                            crc.update(id & 255);
                            crc.update(id >>> 8);
                        }}
                    }}
                }}
                return crc.getValue();
            }}
            io.load(slot, Vars.world.makeSectorContext(to));
            if (!to.info.origin.equals(from) || from.info.items.get(items.copper) !== {remaining}
                || Vars.state.rules.defaultTeam.items().get(items.copper) !== {cargo}
                || Packages.arc.Core.settings.getBool("maxresources", true) !== {str(not manual_cargo).lower()}
                || Vars.world.width() !== {size} || Vars.world.height() !== {size}
                || !io.getMeta(slot).rules.sector.equals(to)) {{
                throw new Error("Numbered sector or cargo differs");
            }}
            const actualTerrain = terrainDigest();
            const launchedCore = Vars.state.rules.defaultTeam.core().block;
            const launchItems = Vars.universe.getLaunchResources().copy();
            io.load(Packages.arc.files.Fi.get("origin.msav"), Vars.world.makeSectorContext(from));
            if (!Vars.state.rules.sector.equals(from) || !from.info.wasCaptured || Vars.state.rules.waves
                || Vars.state.rules.defaultTeam.items().get(items.copper) !== {remaining}) {{
                throw new Error("The captured origin did not retain its post-launch inventory");
            }}
            if ({str(custom_expected).lower()}) {{
                const schematic = Packages.mindustry.game.Schematics.read(Packages.arc.files.Fi.get("campaign-test.msch"));
                Vars.universe.updateLoadout(launchedCore, schematic);
            }}
            Vars.universe.updateLaunchResources(launchItems);
            Vars.logic.reset();
            Vars.world.loadSector(to);
            Vars.logic.play();
            const expectedTerrain = terrainDigest();
            if (actualTerrain !== expectedTerrain) {{
                throw new Error("Generated terrain differs. PC " + expectedTerrain + ", 3DS " + actualTerrain);
            }}
            return "UPSTREAM_PC_NUMBERED_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_NUMBERED_REOPENED")
    print(f"[PASS] stock v160.8 reopened {model} sector {sector_id} with matching terrain, origin and cargo", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--server", type=Path, help="Stock v160.8 server for save and settings readback")
    parser.add_argument("--java", default="java")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--planet", choices=STARTERS)
    selection.add_argument("--departure", type=Path, help="JVM reference numbered-sector departure fixture")
    parser.add_argument("--manual-cargo", action="store_true", help="Edit a departure fixture using the cargo controls")
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-launch-emu"))
    parser.add_argument("--in-game", action="store_true", help="Open the selector from the running origin sector")
    args = parser.parse_args()
    if (args.manual_cargo or args.in_game) and not args.departure:
        parser.error("--manual-cargo and --in-game require --departure")
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        if args.departure:
            destination = verify(model, "numbered", args.rom, azahar, config, log, args.output_dir,
                                 args.departure, args.manual_cargo, args.in_game)
            if args.server:
                check_numbered_stock(args.java, args.server, args.output_dir, model,
                                     args.departure, args.manual_cargo, args.in_game, destination)
            continue
        for planet in (args.planet,) if args.planet else STARTERS:
            verify(model, planet, args.rom, azahar, config, log, args.output_dir)
            if args.server:
                name = STARTERS[planet][0]
                check_stock(args.java, args.server, args.output_dir / f"{model}-{planet}-{name}",
                            args.output_dir / f"{model}-{planet}-settings.bin", planet)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
