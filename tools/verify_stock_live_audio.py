"""Verify stock v160.8 sound routing through the unified ARM11 live client."""

import argparse
import re
from pathlib import Path

from verify_stock_live_weapons import (
    BUTTONS, CIRCLE, DURATIONS, create_fixture, prepare_server, read_stock_weapons, replay, verify_controls,
)
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import wait_for


AUDIO_WORLD = (
    '(() => { const rules = Vars.state.rules; '
    'const tracks = new Packages.arc.struct.Seq(); '
    'tracks.add(new Packages.mindustry.audio.MusicContainer("game1")); '
    'rules.alwaysPlayMusic = false; rules.disableMusic = true; rules.musicVolume = 0.875; '
    'rules.ambientMusic = tracks; rules.darkMusic = tracks; '
    'for (let x=74; x<=77; x++) for(let y=29; y<=30; y++) { '
    'const tile=Vars.world.tile(x,y); if(tile.build!=null) throw "Occupied drill fixture"; '
    'tile.setFloor(Blocks.stone); } '
    'Vars.world.tile(74,29).setBlock(Blocks.container,rules.defaultTeam); '
    'const tile=Vars.world.tile(76,29); tile.setOverlay(Blocks.oreCopper); '
    'tile.setBlock(Blocks.mechanicalDrill,rules.defaultTeam); '
    'let reported=false; Events.run(Packages.mindustry.game.EventType.Trigger.update, () => { '
    'if (!reported && tile.build != null && tile.build.shouldAmbientSound()) { '
    'reported=true; print("UPSTREAM_STOCK_DRILL_ACTIVE id=" + Sounds.getSoundId(tile.build.getAmbientSound()) '
    '+ " efficiency=" + tile.build.efficiency); } }); return "UPSTREAM_AUDIO_WORLD_READY"; })()'
)
AUDIO_PACKETS = (
    '(() => { const p=Groups.player.find(boolf(v=>String(v.name)==="Min3DStry ARM11")); '
    'const u=p.unit(), rules=Vars.state.rules; '
    'Call.sound(p.con,Sounds.uiButton,0.625,1.125,-0.5); '
    'Call.soundAt(p.con,Sounds.waveSpawn,u.x+24,u.y,0.5,1.25); '
    'Call.soundAt(p.con,Sounds.wind2,u.x-24,u.y,0.75,0.875); '
    'rules.alwaysPlayMusic=true; rules.musicVolume=0.375; '
    'Call.setRules(p.con,rules); Call.setRule(p.con,"disableMusic","false"); '
    'return "UPSTREAM_AUDIO_PACKETS " + [Sounds.uiButton,Sounds.waveSpawn,Sounds.wind2]'
    '.map(s=>Sounds.getSoundId(s)).join(","); })()'
)
WEATHER_PACKETS = (
    '(() => { Call.createWeather(Weathers.rain, 1, 600, 0, 0); '
    'Call.createWeather(Weathers.snow, 1, 600, 0, 0); '
    'Call.createWeather(Weathers.sandstorm, 1, 600, 0, 0); '
    'return "UPSTREAM_STOCK_WEATHER_SENT"; })()'
)


def verify_log(path, model, weather=False):
    text = path.read_text(encoding="utf-8", errors="replace")

    def require(pattern):
        match = re.search(pattern, text)
        if match is None:
            raise AssertionError(f"{model}: missing audio evidence for {pattern}")
        return match

    dsp = require(r"UPSTREAM_LIVE_AUDIO_OPEN sounds=205 clips=194 pcmBytes=0 state=(ready|fallback_no_dsp)")[1]
    for pattern in (
        r"UPSTREAM_LIVE_AUDIO_PLAY id=117 volume=[0-9.]+ pitch=[0-9.]+ pan=[-0-9.]+ voice=(\d+)",
        r"UPSTREAM_LIVE_AUDIO_PLAY id=185 volume=0\.625 pitch=1\.125 pan=-0\.500 voice=(\d+)",
        r"UPSTREAM_LIVE_AUDIO_PLAY id=198 volume=[0-9.]+ pitch=1\.250 pan=0\.[1-9]\d* voice=(\d+)",
        r"UPSTREAM_LIVE_STREAM_PLAY id=200 volume=0\.750 pitch=0\.875 pan=-0\.312 voice=(\d+) frames=393474\b",
        r"UPSTREAM_LIVE_AUDIO_PLAY id=67 volume=[0-9.]+ pitch=1\.000 pan=-[0-9.]+ voice=(\d+) pcmBytes=\d+ loop=1",
    ):
        if (int(require(pattern)[1]) > 0) != (dsp == "ready"):
            raise AssertionError(f"{model}: submitted voice disagrees with the DSP state")
    frames, mode = ("8192", "ndsp") if dsp == "ready" else ("4096", "decode-only")
    require(rf"UPSTREAM_LIVE_MUSIC_START id=4 name=game1 rate=44100 channels=2 frames={frames} "
            rf"mode={mode} volume=0\.375 loop=0\b")
    require(r"UPSTREAM_LIVE_MUSIC_CLOSE track=-1 decoderOpen=0\b")
    require(r"UPSTREAM_LIVE_AUDIO_CLOSE requests=[1-9]\d* started=\d+ dropped=\d+")
    resident = int(require(r"UPSTREAM_LIVE_AUDIO_CACHE .*pcmBytes=([1-9]\d*)")[1])
    if resident > 640 * 1024 or "UPSTREAM_LIVE_MUSIC_ERROR" in text:
        raise AssertionError(f"{model}: audio cache or decoder state was invalid")
    if weather:
        for name, sound_id, frames, max_gain in (
            ("rain", 105, 195235, 0.25),
            ("sandstorm", 199, None, 0.8),
            ("snowing", 202, 602477, 1.0),
        ):
            received = require(rf"UPSTREAM_LIVE_WEATHER name={name}\b")
            route = "STREAM_PLAY" if frames is not None else "AUDIO_PLAY"
            buffer = f"frames={frames}" if frames is not None else r"pcmBytes=\d+"
            played = require(rf"UPSTREAM_LIVE_{route} id={sound_id} volume=([0-9.]+) pitch=1\.000 "
                             rf"pan=0\.000 voice=(\d+) {buffer} loop=1\b")
            if (played.start() < received.start() or not 0.01 <= float(played[1]) <= max_gain
                    or (int(played[2]) > 0) != (dsp == "ready")):
                raise AssertionError(f"{model}: invalid {name} loop routing")
            print(f"{model}: stock {name}. {played[0]}", flush=True)
    print(f"UPSTREAM_STOCK_LIVE_AUDIO model={model} effects=ok stream=ok ambience=ok music={mode}", flush=True)


def verify_model(args, model, fixture):
    def prepare(server, log):
        nonlocal stock
        stock = (server, log)
        prepare_server(server, log)
        stock_command(server, log, "js " + AUDIO_WORLD, "UPSTREAM_AUDIO_WORLD_READY")
        wait_for(log, server, r"UPSTREAM_STOCK_DRILL_ACTIVE id=67 efficiency=1\b", 10)

    def verify_input(log, proc, rpc, output):
        stock_command(*stock, "js " + AUDIO_PACKETS, r"UPSTREAM_AUDIO_PACKETS 185,198,200\b")
        if args.weather:
            stock_command(*stock, "js " + WEATHER_PACKETS, r"UPSTREAM_STOCK_WEATHER_SENT\b")
        verify_controls(log, proc, rpc, output)

    stock = None
    replay(args, model, fixture, True, taps={}, buttons=BUTTONS, button_durations=DURATIONS, circle=CIRCLE,
           prepare_server=prepare, verify_input=verify_input, read_stock=read_stock_weapons)
    verify_log(args.output / model / "live/azahar.log", model, args.weather)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", choices=("old3ds", "new3ds"), action="append")
    parser.add_argument("--port", type=int, default=16674)
    parser.add_argument("--weather", action="store_true", help="Check original rain, snow and sandstorm loops")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("old3ds", "new3ds"):
        verify_model(args, model, fixture)


if __name__ == "__main__":
    main()
