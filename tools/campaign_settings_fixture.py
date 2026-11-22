#!/usr/bin/env python3
"""Create and check graphical campaign settings fixtures with the stock v160.8 server."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

from verify_upstream_live_net import stop, wait_for


def stock_console(java: str, server: Path, root: Path, script: str, marker: str) -> None:
    log_path = root / "server.log"
    with log_path.open("w", encoding="utf-8") as log:
        proc = subprocess.Popen([java, "-jar", str(server.resolve())], cwd=root, stdin=subprocess.PIPE,
                                stdout=log, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
        try:
            wait_for(log_path, proc, r"Server loaded\.", 30)
            proc.stdin.write(f'js (() => {{ {script} }})()\n')
            proc.stdin.flush()
            wait_for(log_path, proc, marker, 30)
        finally:
            stop(proc)


def create_settings(java: str, server: Path, fixtures: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-settings-") as temp:
        root = Path(temp)
        script = """
            const settings = Packages.arc.Core.settings;
            const presets = Packages.mindustry.content.SectorPresets;
            const items = Packages.mindustry.content.Items;
            const zero = presets.groundZero.sector, forest = presets.frozenForest.sector;
            settings.put("conveyor-unlocked", Packages.java.lang.Boolean.TRUE);
            settings.put("req-conveyor-copper", new Packages.java.lang.Integer(7));
            settings.put("campaign-fixture-text", "preserved");
            settings.put("campaign-fixture-int", new Packages.java.lang.Integer(47));
            settings.put("save-sector-serpulo-170-name", "Stock Ground Zero");
            settings.put("last-sector-save", "sector-serpulo-64");
            zero.info.name = "Landing Data";
            zero.info.attempts = 3;
            zero.info.wasCaptured = true;
            zero.info.origin = forest;
            zero.info.resources.add(items.lead);
            zero.info.items.add(items.copper, 19);
            const stat = new Packages.mindustry.game.SectorInfo.ExportStat();
            stat.mean = 2.5;
            zero.info.rawProduction.put(items.copper, stat);
            zero.info.importCooldownTimers.put(items.lead, 3.5);
            zero.saveInfo();
            forest.planet.setLastSector(forest);
            settings.forceSave();
            return "UPSTREAM_PC_SETTINGS_WRITTEN";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_SETTINGS_WRITTEN")
        shutil.copyfile(root / "config/settings.bin", fixtures / "settings.bin")


def check_settings(java: str, server: Path, settings: Path, sector_id: int, research: int,
                   after_play: bool) -> None:
    expected_name = "Stock Ground Zero" if sector_id == 170 else "sector-serpulo-64"
    # The stock moving average decays in an idle sector during the replay.
    production_ok = "production.mean >= 0 && production.mean <= 2.5" if after_play else "production.mean === 2.5"
    with tempfile.TemporaryDirectory(prefix="m3ds-read-stock-settings-") as temp:
        root = Path(temp)
        target = root / "config/settings.bin"
        target.parent.mkdir()
        shutil.copyfile(settings, target)
        script = f"""
            const settings = Packages.arc.Core.settings;
            const presets = Packages.mindustry.content.SectorPresets;
            const items = Packages.mindustry.content.Items;
            const zero = presets.groundZero.sector, forest = presets.frozenForest.sector;
            zero.loadInfo();
            const info = zero.info, production = info.rawProduction.get(items.copper);
            if (settings.getInt("req-conveyor-copper", -1) !== {research} ||
                !settings.getBool("conveyor-unlocked") ||
                settings.getInt("serpulo-last-sector", -1) !== {sector_id} ||
                String(settings.getString("last-sector-save", "")) !== "{expected_name}" ||
                String(settings.getString("campaign-fixture-text", "")) !== "preserved" ||
                settings.getInt("campaign-fixture-int", -1) !== 47 ||
                String(info.name) !== "Landing Data" || info.attempts !== 3 || !info.wasCaptured ||
                !info.origin.equals(forest) || !info.resources.contains(items.lead) ||
                production == null || !({production_ok}) ||
                info.importCooldownTimers.get(items.lead, -1) !== 3.5) {{
                throw new Error("PC could not restore campaign settings");
            }}
            return "UPSTREAM_PC_SETTINGS_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_SETTINGS_REOPENED")
    print(f"[PASS] stock v160.8 reopened {settings.name}, last sector {sector_id}, research {research}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", required=True, type=Path)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--create-in", type=Path, help="Existing directory with stock sector fixtures")
    operation.add_argument("--check", type=Path, help="Check a settings.bin saved by the port")
    parser.add_argument("--sector", type=int, choices=(64, 170), default=170)
    parser.add_argument("--research", type=int, default=7)
    parser.add_argument("--after-play", action="store_true", help="The fixture sector ran its stock production averages")
    args = parser.parse_args()
    if args.create_in:
        create_settings(args.java, args.server, args.create_in)
    else:
        check_settings(args.java, args.server, args.check, args.sector, args.research, args.after_play)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
