#!/usr/bin/env python3
"""Stage stock unit production saves and check their ARM11 construction shaders."""

import argparse
from pathlib import Path
import shutil

from campaign_settings_fixture import stock_console
from verify_upstream_campaign_launch import write_input_replay
import verify_upstream_singleplayer as sp


SCENARIOS = {
    "reconstructor": ("additive-reconstructor", "unitbuild"),
    "assembler": ("tank-assembler", "blockbuild"),
}


def prepare(java, server, source, destination, scenario):
    """Center a stock save's core unit on an existing production fixture."""
    block, _ = SCENARIOS[scenario]
    root = destination.parent
    root.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, root / "source.msav")
    script = f"""
        const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
        const Groups = Packages.mindustry.gen.Groups;
        const load = name => {{
            Vars.logic.reset();
            SaveIO.load(Fi.get(name));
            const build = Groups.build.find(b => String(b.block.name) === '{block}');
            if (build == null || build.progress <= 0) throw 'Expected a stock in-progress {block}';
            return build;
        }};
        const build = load('source.msav'), team = Vars.state.rules.defaultTeam;
        if (build.team !== team) throw 'Expected a friendly production building';
        const existing = () => Groups.unit.find(u => u.team === team && u.isAdded() && u.spawnedByCore);
        let playerUnit = existing();
        if (playerUnit == null) {{
            const core = team.core();
            if (core == null) throw 'Expected a stock starter core';
            playerUnit = core.block.unitType.create(team);
            playerUnit.spawnedByCore = true;
            playerUnit.add();
        }}
        let x = build.x, y = build.y;
        if ('{scenario}' === 'assembler') {{
            const spawn = build.getUnitSpawn();
            x = (build.x + spawn.x) / 2;
            y = (build.y + spawn.y) / 2;
        }}
        playerUnit.set(x, y);
        SaveIO.save(Fi.get('prepared.msav'));
        const reopened = load('prepared.msav');
        const starter = existing();
        if (reopened.progress <= 0 || starter == null || starter.x !== x || starter.y !== y)
            throw 'Java could not restore the centered in-progress production save';
        return 'STOCK_ASSEMBLY_REPLAY_PREPARED';
    """
    stock_console(java, server, root, " ".join(script.splitlines()), "STOCK_ASSEMBLY_REPLAY_PREPARED")
    print(f"[PASS] Stock v160.8 reopened the centered {scenario} fixture", flush=True)


def replay(args, model, scenario, source, azahar, config, log):
    _, shader = SCENARIOS[scenario]
    output = args.output / model / scenario
    sd = (output / "virtual-sd").resolve()
    save = sd / "3ds/min3dstry/singleplayer-save.msav"
    save.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, save)
    movie = output / "production.ctm"
    write_input_replay(movie, model, [(180, 183, sp.KEY_START)], 185, b"Stock unit production")
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=save", 150)
            sp._wait_for_log(log, proc, rf"UPSTREAM_(?:UNIT|BLOCK)_BUILD_SHADER source={shader}.frag", 150)
            (output / "production.png").write_bytes(rpc.screenshot())
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT", 75)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / "production.log").write_bytes(log.read_bytes())
        if save.read_bytes() != source.read_bytes():
            raise AssertionError("The visual replay should leave its input save unchanged")
        print(f"[PASS] {model}: stock {scenario} save reached {shader}.frag", flush=True)
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True, help="stock v160.8 server-release.jar")
    parser.add_argument("--reconstructor", type=Path, help="stock save with an active Additive Reconstructor")
    parser.add_argument("--assembler", type=Path, help="stock save with an active Tank Assembler")
    parser.add_argument("--rom", type=Path, help="launch the prepared emulator replay when supplied")
    parser.add_argument("--azahar")
    parser.add_argument("--model", action="append", choices=("old3ds", "new3ds"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sources = {name: getattr(args, name) for name in SCENARIOS if getattr(args, name) is not None}
    if not sources:
        parser.error("Supply --reconstructor, --assembler, or both")
    fixtures = {name: args.output / "fixtures" / name / "prepared.msav" for name in sources}
    for name, source in sources.items():
        prepare(args.java, args.server, source, fixtures[name], name)
    if args.rom is None:
        return
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("old3ds", "new3ds"):
        for name, fixture in fixtures.items():
            replay(args, model, name, fixture, azahar, config, log)


if __name__ == "__main__":
    main()
