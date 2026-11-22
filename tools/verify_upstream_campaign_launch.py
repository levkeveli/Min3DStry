#!/usr/bin/env python3
"""Replay the two stock fresh-campaign starters in Azahar."""

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

STARTERS = {
    "serpulo": ("sector-serpulo-170.msav", "Ground_Zero", 256),
    "erekir": ("sector-erekir-10.msav", "The_Onset", 512),
}


def write_replay(path: Path, model: str, planet: str) -> None:
    windows = [(0, 24, 1 << 8), (50, 52, 1), (120, 122, sp.KEY_START)]
    if planet == "erekir":
        windows.append((43, 45, 1 << 7))
    frames = sp.CTM_SAMPLES_PER_SECOND * 130
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


def verify(model: str, planet: str, rom: Path, azahar: str, config: Path, log: Path, output: Path) -> None:
    name, world, size = STARTERS[planet]
    root = sp.resolve_sd_save_path(log).parent
    saves = root / "saves"
    settings = root / "settings.bin"
    backup = root / "settings_backup.bin"
    settings_backups = root / "settings_backups"
    override = root / "campaign-select.txt"
    if any(root.glob("sector-*.msav")) or any(saves.glob("sector-*.msav")) or any(
            path.exists() for path in (settings, backup, settings_backups, override)):
        raise RuntimeError("Use an Azahar SD directory without campaign saves, settings, or selection overrides")
    initial_config = config.read_bytes() if config.is_file() else None
    had_saves = saves.is_dir()
    prefix = f"{model}-{planet}"
    replay = output / f"{prefix}.ctm"
    write_replay(replay, model, planet)
    try:
        proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_MENU slots=2\b", 120)
            time.sleep(2)
            menu = rpc.screenshot()
            if sp.az.inspect_png(menu)["top_distinct_colors"] < 8:
                raise AssertionError(f"{prefix}: the starter selector did not render")
            (output / f"{prefix}-menu.png").write_bytes(menu)
            sp._wait_for_log(log, proc, rf"UPSTREAM_SP_LOADED source=campaign-new map={world} size={size}x{size}\b", 150)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME ", 120)
            screenshot = rpc.screenshot()
            if sp.az.inspect_png(screenshot)["top_distinct_colors"] < 8:
                raise AssertionError(f"{prefix}: the starter world did not render")
            (output / f"{prefix}.png").write_bytes(screenshot)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={re.escape(name)}\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
            (output / f"{prefix}-{name}").write_bytes((saves / name).read_bytes())
            (output / f"{prefix}-settings.bin").write_bytes(settings.read_bytes())
            print(f"[PASS] {model}: {planet} starter rendered and saved at {size}x{size}", flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / f"{prefix}.log").write_bytes(log.read_bytes())
    finally:
        for starter_name, _, _ in STARTERS.values():
            (saves / starter_name).unlink(missing_ok=True)
            (saves / f"{starter_name}-backup.msav").unlink(missing_ok=True)
        for path in (settings, backup):
            path.unlink(missing_ok=True)
        if settings_backups.is_dir():
            shutil.rmtree(settings_backups)
        if not had_saves and saves.is_dir() and not any(saves.iterdir()):
            saves.rmdir()
        if initial_config is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(initial_config)


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--server", type=Path, help="Stock v160.8 server for save and settings readback")
    parser.add_argument("--java", default="java")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--planet", choices=STARTERS)
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-launch-emu"))
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        for planet in (args.planet,) if args.planet else STARTERS:
            verify(model, planet, args.rom, azahar, config, log, args.output_dir)
            if args.server:
                name = STARTERS[planet][0]
                check_stock(args.java, args.server, args.output_dir / f"{model}-{planet}-{name}",
                            args.output_dir / f"{model}-{planet}-settings.bin", planet)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
