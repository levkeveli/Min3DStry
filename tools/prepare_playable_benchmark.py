#!/usr/bin/env python3
"""Generate a stock Ground Zero workload save for the playable New 3DS benchmark."""

import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console

from verify_upstream_live_net import SERVER_SHA256

WORKLOAD = """
    Vars.logic.reset();
    Vars.world.loadSector(Packages.mindustry.content.SectorPresets.groundZero.sector);
    const rules = Vars.state.rules;
    rules.sector = null;
    rules.waves = true;
    rules.waveTimer = true;
    rules.initialWaveSpacing = 120;
    rules.waveSpacing = 600;
    rules.dropZoneRadius = 0;
    const group = new Packages.mindustry.game.SpawnGroup(UnitTypes.dagger);
    group.unitAmount = 80;
    group.max = 80;
    rules.spawns.clear();
    rules.spawns.add(group);

    const world = Vars.world, team = rules.defaultTeam;
    if (world.width() !== 256 || world.height() !== 256
        || team.core().tile.x !== 129 || team.core().tile.y !== 55) {
        throw new Error("Unexpected stock Ground Zero layout");
    }
    const points = Vars.spawner.getSpawns().copy();
    for (let i = 0; i < points.size; i++) points.get(i).setOverlay(Blocks.air);
    function place(x, y, block) {
        const tile = world.tile(x, y);
        tile.setFloor(Blocks.stone);
        tile.setOverlay(Blocks.air);
        tile.setBlock(block, team, 0);
        return tile;
    }
    place(129, 70, Blocks.air).setOverlay(Blocks.spawn);
    for (let x = 123; x <= 136; x++) {
        place(x, 61, Blocks.duo);
        place(x, 60, Blocks.itemSource).build.outputItem = Items.copper;
    }
    for (let y = 49; y <= 52; y++) {
        place(120, y, Blocks.itemSource).build.outputItem = Items.copper;
        for (let x = 121; x <= 138; x++) place(x, y, Blocks.conveyor);
        place(139, y, Blocks.itemVoid);
    }
    Vars.logic.play();
    const save = new Packages.arc.files.Fi("singleplayer-save.msav");
    SaveIO.write(save);
    SaveIO.load(save);
    if (Vars.state.rules.sector != null || Vars.state.rules.spawns.first().unitAmount !== 80
        || Vars.world.tile(129, 70).overlay() !== Blocks.spawn
        || Vars.world.tile(124, 61).build.block !== Blocks.duo
        || Vars.world.tile(124, 60).build.outputItem !== Items.copper
        || Vars.world.tile(130, 49).build.block !== Blocks.conveyor) {
        throw new Error("Stock server could not reopen the benchmark workload");
    }
    return "STOCK_PLAYABLE_BENCH_SAVE";
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=Path, required=True, help="New directory for the save and ROM settings flag")
    args = parser.parse_args()
    with args.server.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SERVER_SHA256:
            parser.error("Expected the official Mindustry v160.8 server-release.jar")
    if args.output.exists():
        parser.error(f"Output already exists: {args.output}")
    with tempfile.TemporaryDirectory(prefix="m3ds-playable-benchmark-") as tmp:
        root = Path(tmp)
        stock_console(args.java, args.server, root, " ".join(WORKLOAD.splitlines()), r"STOCK_PLAYABLE_BENCH_SAVE")
        args.output.mkdir(parents=True)
        save = args.output / "singleplayer-save.msav"
        shutil.copyfile(root / save.name, save)
        (args.output / "benchmark.txt").write_bytes(b"")
        with save.open("rb") as stream:
            print(f"STOCK_PLAYABLE_BENCH_SAVE sha256={hashlib.file_digest(stream, 'sha256').hexdigest()}")


if __name__ == "__main__":
    main()
