#!/usr/bin/env python3
"""Verify the stock offline soundtrack and drill ambience on ARM11."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console
from verify_upstream_campaign_launch import write_input_replay
import verify_upstream_singleplayer as sp


SLOT = "sector-serpulo-170.msav"
FIXTURE = """
    Vars.logic.reset();
    const sector=Packages.mindustry.content.SectorPresets.groundZero.sector;
    Vars.world.loadSector(sector);
    Vars.logic.play();
    const rules=Vars.state.rules;
    rules.waves=false;
    rules.alwaysPlayMusic=true;
    const tracks=new Packages.arc.struct.Seq();
    tracks.add(new Packages.mindustry.audio.MusicContainer('game1'));
    rules.ambientMusic=tracks;
    rules.darkMusic=tracks;
    for(let x=131;x<=132;x++){
        for(let y=55;y<=56;y++) Vars.world.tile(x,y).setOverlay(Blocks.oreCopper);
    }
    const tile=Vars.world.tile(131,55);
    tile.setBlock(Blocks.mechanicalDrill,rules.defaultTeam);
    const file=Packages.arc.files.Fi.get('sector-serpulo-170.msav');
    const saves=Packages.mindustry.io.SaveIO;
    saves.write(file);
    saves.load(file,Vars.world.makeSectorContext(sector));
    if(Vars.world.build(131,55).block!==Blocks.mechanicalDrill
       || Vars.world.tile(131,55).overlay()!==Blocks.oreCopper
       || !Vars.state.rules.alwaysPlayMusic) throw 'Invalid stock audio fixture';
    return 'STOCK_OFFLINE_AUDIO_FIXTURE';
"""


def create_fixture(java, server, destination):
    with tempfile.TemporaryDirectory(prefix="m3ds-offline-audio-") as temp:
        root = Path(temp)
        stock_console(java, server, root, " ".join(FIXTURE.splitlines()), "STOCK_OFFLINE_AUDIO_FIXTURE")
        shutil.copyfile(root / SLOT, destination)


def replay(args, model, azahar, config, log, fixture):
    output = args.output / model
    sd = (output / "virtual-sd").resolve()
    saves = sd / "3ds/min3dstry/saves"
    saves.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    shutil.copyfile(fixture, saves / SLOT)
    movie = output / "stock-offline-audio.ctm"
    write_input_replay(movie, model, [(100, 102, sp.KEY_START)], 100, b"Min3DStry Offline Audio")
    original = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(sp.az.patch_qt_config_text((original[config] or b"").decode("utf-8"), {
            "Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"},
        }), encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_BOOT mode=offline\b", 80)
            sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_AUDIO_OPEN sounds=205\b", 70)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign\b", 80)
            sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_MUSIC_START id=4 name=game1\b", 95)
            sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_AUDIO_PLAY id=67 .*\bloop=1\b", 95)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_MUSIC_CLOSE track=-1 decoderOpen=0\b", 15)
            print(f"[PASS] {model}: stock offline music and drill loop reached the audio backend", flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / "azahar.log").write_bytes(log.read_bytes())
    finally:
        for path, data in original.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.output / SLOT
    create_fixture(args.java, args.server, fixture)
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("new3ds",):
        replay(args, model, azahar, config, log, fixture)


if __name__ == "__main__":
    main()
