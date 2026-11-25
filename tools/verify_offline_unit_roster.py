"""Verify stock units and carried unit art in the offline ARM11 renderer."""

import argparse
import shutil
import subprocess
import time
from pathlib import Path

import verify_upstream_singleplayer as singleplayer
from verify_upstream_live_net import stop, wait_for


def create_stock_save(output, java, server_jar, port):
    work = output / "stock-server"
    work.mkdir()
    log = work / "server.log"
    with log.open("wb") as console:
        server = subprocess.Popen(
            [java, "-jar", str(server_jar.resolve())], cwd=work, stdin=subprocess.PIPE,
            stdout=console, stderr=subprocess.STDOUT, text=True,
        )

        def run(command, expected):
            server.stdin.write(command + "\n")
            server.stdin.flush()
            wait_for(log, server, expected, 30)

        try:
            wait_for(log, server, r"Server loaded\.", 30)
            run(f"config port {port}\nhost Glacier survival", rf"Opened a server on port {port}\.")
            run(
                'js (() => { const rules = Vars.state.rules, c = rules.defaultTeam.core(); '
                'rules.waves = false; '
                'UnitTypes.elude.spawn(rules.defaultTeam, c.x + 24, c.y + 16); '
                'UnitTypes.stell.spawn(rules.waveTeam, c.x - 32, c.y + 16); '
                'UnitTypes.scepter.spawn(rules.waveTeam, c.x + 32, c.y - 24); '
                'const carrier = UnitTypes.mega.spawn(rules.defaultTeam, c.x - 24, c.y - 16); '
                'carrier.addPayload(new Packages.mindustry.world.blocks.payloads.UnitPayload('
                'UnitTypes.flare.create(rules.defaultTeam))); return "OFFLINE_ROSTER_CREATED"; })()',
                "OFFLINE_ROSTER_CREATED",
            )
            run("save offline-roster", r"Saved to .*offline-roster\.msav")
            run("stop", r"Stopped server")
            run("load offline-roster", r"Save loaded\.")
            run(
                'js (() => { const expected = [UnitTypes.elude, UnitTypes.stell, UnitTypes.scepter, UnitTypes.mega]; '
                'for (let i = 0; i < expected.length; i++) { const type = expected[i]; '
                'if (!Groups.unit.find(boolf(u => u.type === type))) throw "Missing " + type.name; } '
                'const carrier = Groups.unit.find(boolf(u => u.type === UnitTypes.mega)); '
                'if (!carrier.hasPayload() || carrier.payloads.first().unit.type !== UnitTypes.flare) '
                'throw "Missing carried flare"; return "OFFLINE_ROSTER_REOPENED"; })()',
                "OFFLINE_ROSTER_REOPENED",
            )
            return work / "config/saves/offline-roster.msav"
        finally:
            stop(server)


def verify_model(output, model, rom, azahar, save):
    config, log = singleplayer.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log)}
    sd = (output / "sdmc").resolve()
    target_save = sd / "3ds/min3dstry/singleplayer-save.msav"
    target_save.parent.mkdir(parents=True)
    shutil.copyfile(save, target_save)
    config.parent.mkdir(parents=True, exist_ok=True)
    storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    initial = (originals[config] or b"").decode("utf-8")
    try:
        config.write_text(singleplayer.az.patch_qt_config_text(initial, storage), encoding="utf-8")
        proc, rpc = singleplayer._launch_azahar(model, rom, azahar, config, log)
        try:
            singleplayer._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=save map=Glacier\b", 120)
            for unit in ("elude", "stell", "scepter", "mega", "flare"):
                singleplayer._wait_for_log(log, proc, rf"UPSTREAM_SP_UNIT_ART type={unit}\b", 30)
            for enemy in ("stell", "scepter"):
                singleplayer._wait_for_log(log, proc, rf"UPSTREAM_SP_ENEMY type={enemy}\b", 30)
            singleplayer._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME .*sprites=[1-9]\d*\b", 30)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    screen = rpc.screenshot()
                    if singleplayer.az.inspect_png(screen)["top_distinct_colors"] >= 8:
                        (output / "world.png").write_bytes(screen)
                        return
                except (RuntimeError, TimeoutError):
                    if proc.poll() is not None or "UPSTREAM_SP_ERROR" in log.read_text(encoding="utf-8"):
                        raise
                time.sleep(0.2)
            raise AssertionError("The stock offline scene did not render")
        finally:
            rpc.close()
            singleplayer._stop_proc(proc, azahar)
            if log.exists():
                shutil.copyfile(log, output / "azahar.log")
    finally:
        for path, original in originals.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--server", required=True, type=Path, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16586)
    parser.add_argument("--output", required=True, type=Path, help="A new output directory")
    args = parser.parse_args()

    azahar = singleplayer.az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    fixture = create_stock_save(args.output, args.java, args.server, args.port)
    for model in ("new3ds",):
        verify_model(args.output / model, model, args.rom, azahar, fixture)
        print(f"[PASS] {model}: stock Glacier save drew Elude, Stell, Scepter, Mega and carried Flare")


if __name__ == "__main__":
    main()
