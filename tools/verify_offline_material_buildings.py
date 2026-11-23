"""Compare ARM11 renders of stock Glacier saves with and without material buildings."""

import argparse
import shutil
import subprocess
import time
from pathlib import Path

import verify_upstream_singleplayer as singleplayer
from verify_upstream_live_net import stop, wait_for

# Stock Glacier spawn is (81, 26). Keep the core and status panel clear.
PUMP_POSITION = (84, 31)
BUILDINGS = (
    ("siliconSmelter", "silicon-smelter", 75, 23, 2),
    ("plastaniumCompressor", "plastanium-compressor", 80, 22, 2),
    ("vault", "vault", 86, 23, 3),
    ("liquidTank", "liquid-tank", 86, 29, 3),
    ("mechanicalPump", "mechanical-pump", *PUMP_POSITION, 1),
    ("electrolyzer", "electrolyzer", 80, 30, 3),
    ("separator", "separator", 86, 26, 2),
)


def create_fixtures(output, java, server_jar, port):
    output.mkdir()
    log = output / "server.log"
    with log.open("wb") as console:
        server = subprocess.Popen(
            [java, "-jar", str(server_jar.resolve())], cwd=output, stdin=subprocess.PIPE,
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
                'js (() => { Vars.state.rules.waves = false; '
                f'Vars.world.tile({PUMP_POSITION[0]},{PUMP_POSITION[1]}).setFloor(Blocks.water.asFloor()); '
                'return "MATERIAL_TERRAIN_READY"; })()',
                "MATERIAL_TERRAIN_READY",
            )
            run("save material-empty", r"Saved to .*material-empty\.msav")
            placements = " ".join(
                f"Vars.world.tile({x},{y}).setBlock(Blocks.{field}, team, 0);"
                for field, _, x, y, _ in BUILDINGS
            )
            run(
                'js (() => { const team = Vars.state.rules.defaultTeam; ' + placements
                + ' const tank = Vars.world.tile(86,29).build; tank.liquids.add(Liquids.water, 500); '
                'const press = Vars.world.tile(80,22).build; press.items.add(Items.titanium, 8); '
                'press.liquids.add(Liquids.oil, 10); return "MATERIAL_BUILDINGS_READY"; })()',
                "MATERIAL_BUILDINGS_READY",
            )
            run("save material-stock", r"Saved to .*material-stock\.msav")
            run("stop", r"Stopped server")
            run("load material-stock", r"Save loaded\.")
            checks = " ".join(
                f'if (Vars.world.tile({x},{y}).block() !== Blocks.{field}) throw "Missing {name}";'
                for field, name, x, y, _ in BUILDINGS
            )
            run(
                'js (() => { const c = Vars.state.rules.defaultTeam.core(); '
                'if (!c || c.tileX() != 81 || c.tileY() != 26) throw "Stock core was replaced"; '
                + checks + ' return "MATERIAL_STOCK_SAVE_REOPENED"; })()',
                "MATERIAL_STOCK_SAVE_REOPENED",
            )
        finally:
            stop(server)
    fixtures = output / "config/saves"
    return fixtures / "material-empty.msav", fixtures / "material-stock.msav"


def capture(output, model, save, rom, azahar, reference=None, buildings=BUILDINGS, min_changed=12):
    sd = (output / "sdmc").resolve()
    sd_save = sd / "3ds/min3dstry/singleplayer-save.msav"
    sd_save.parent.mkdir(parents=True)
    shutil.copyfile(save, sd_save)

    config, log = singleplayer.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    config.parent.mkdir(parents=True, exist_ok=True)
    try:
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(singleplayer.az.patch_qt_config_text(initial, isolated), encoding="utf-8")
        proc, rpc = singleplayer._launch_azahar(model, rom, azahar, config, log)
        try:
            singleplayer._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME .*left=73 bottom=20 sprites=1 wave=1", 120)
            deadline = time.monotonic() + 20
            image = None
            while time.monotonic() < deadline:
                try:
                    frame = rpc.screenshot()
                    if singleplayer.az.inspect_png(frame)["top_distinct_colors"] >= 8:
                        image = frame
                        if reference is None or min(changed_pixels(reference, image, buildings).values()) >= min_changed:
                            break
                except (RuntimeError, TimeoutError):
                    if proc.poll() is not None or "UPSTREAM_SP_ERROR" in log.read_text(encoding="utf-8"):
                        raise
                time.sleep(0.2)
            if image is None:
                raise AssertionError("No completed stock Glacier frame")
            (output / "world.png").write_bytes(image)
            return image
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


def changed_pixels(empty, stock, buildings=BUILDINGS):
    areas = {}
    for _, name, x, y, size in buildings:
        # The top viewport spans x=40..359 at 20 pixels per tile.
        offset = 10 if size % 2 == 0 else 0
        screen_x = 40 + (x - 73) * 20 + 10 + offset
        screen_y = 240 - ((y - 20) * 20 + 10 + offset)
        areas[name] = tuple((screen_x + dx, screen_y + dy) for dx in range(-6, 7, 3) for dy in range(-6, 7, 3))
    points = tuple(point for area in areas.values() for point in area)
    ground = singleplayer.az.inspect_png(empty, points)["samples"]
    visible = singleplayer.az.inspect_png(stock, points)["samples"]
    return {name: sum(ground[point] != visible[point] for point in area) for name, area in areas.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16587)
    parser.add_argument("--output", type=Path, required=True, help="A new output directory")
    args = parser.parse_args()

    azahar = singleplayer.az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    empty, stock = create_fixtures(args.output / "stock-server", args.java, args.server, args.port)
    for model in ("old3ds", "new3ds"):
        before = capture(args.output / model / "empty", model, empty, args.rom, azahar)
        after = capture(args.output / model / "stock", model, stock, args.rom, azahar, reference=before)
        counts = changed_pixels(before, after)
        for name, changed in counts.items():
            print(f"UPSTREAM_OFFLINE_MATERIAL_PIXELS model={model} block={name} changed={changed}/25")
        if min(counts.values()) < 12:
            raise AssertionError(f"{model}: material buildings did not cover their expected sample points")


if __name__ == "__main__":
    main()
