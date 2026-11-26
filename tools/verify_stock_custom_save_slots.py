#!/usr/bin/env python3
"""Select two unmodified v160.8 saves on ARM11 and reopen the written slot in Java."""

import argparse
from pathlib import Path
import re
import shutil
import tempfile

from campaign_settings_fixture import stock_console
from verify_upstream_campaign_launch import write_input_replay
import verify_upstream_singleplayer as sp


STOCK = """
const Fi=Packages.arc.files.Fi, MapIO=Packages.mindustry.io.MapIO, SaveIO=Packages.mindustry.io.SaveIO;
const settings=Packages.arc.Core.settings;
const items=Packages.mindustry.content.Items;
const mode=Packages.mindustry.game.Gamemode;
"""


def create(args, directory):
    directory.mkdir(parents=True)
    for name in ("glacier.msav", "caldera.msav"):
        shutil.copyfile(args.maps / name, directory / name)
    script = STOCK + """
        const save=(mapFile,name,gamemode,copper)=>{
            Vars.logic.reset();
            const map=MapIO.createMap(Fi.get(mapFile),false);
            const rules=map.applyRules(gamemode);
            Vars.world.loadMap(map,rules);
            rules.retainContentFields(Vars.state.rules);
            Vars.state.rules=rules;
            Vars.logic.play();
            Vars.state.rules.defaultTeam.core().items.set(items.copper,copper);
            const file=Fi.get('saves/'+name);
            SaveIO.write(file);
            return SaveIO.getMeta(file).timestamp;
        };
        const first=save('glacier.msav','0.msav',mode.sandbox,17);
        const second=save('caldera.msav','Caldera Test.msav',mode.survival,41);
        if(second<=first) throw 'Unexpected stock slot sort order';
        Vars.logic.reset();
        Vars.world.loadSector(Packages.mindustry.content.SectorPresets.groundZero.sector);
        Vars.logic.play();
        SaveIO.write(Fi.get('saves/sector-serpulo-170.msav'));
        settings.put('save-0-name','Glacier Test');
        settings.forceSave();
        return 'STOCK_CUSTOM_SLOTS_WRITTEN';
    """
    stock_console(args.java, args.server, directory, " ".join(script.splitlines()), "STOCK_CUSTOM_SLOTS_WRITTEN")


def readback(args, save):
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-slot-readback-") as temp:
        root = Path(temp)
        shutil.copyfile(save, root / "saved.msav")
        script = STOCK + """
            SaveIO.load(Fi.get('saved.msav'));
            const rules=Vars.state.rules;
            if(String(Vars.state.map.name())!=='Glacier' || !rules.infiniteResources || rules.sector!=null
              || rules.defaultTeam.core().items.get(items.copper)!==17) throw 'Incorrect port slot contents';
            return 'STOCK_GLACIER_SLOT_REOPENED';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_GLACIER_SLOT_REOPENED")


def replay(args, model, fixture, azahar, config, log):
    output = args.output / model
    sd = (output / "virtual-sd").resolve()
    directory = sd / "3ds/min3dstry"
    shutil.copytree(fixture / "saves", directory / "saves")
    shutil.copyfile(fixture / "config/settings.bin", directory / "settings.bin")
    saves = directory / "saves"
    shutil.copyfile(fixture / "caldera.msav", saves / "map-export.msav")
    shutil.copyfile(saves / "0.msav", saves / "0.msav-backup.msav")
    (saves / "invalid.msav").write_text("invalid save", encoding="utf-8")

    original = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((original[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        for name, map_name, copper, save in (("Caldera Test.msav", "Ancient_Caldera", 41, False),
                                             ("0.msav", "Glacier", 17, True)):
            movie = output / (map_name + ".ctm")
            windows = [(0, 24, 1 << 9), (42, 44, 1), (105, 107, sp.KEY_START)]
            if save:
                windows.extend(((32, 34, 1 << 7), (88, 90, sp.KEY_SELECT)))
            write_input_replay(movie, model, windows, 105, b"Min3DStry Stock Saves")
            proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
            try:
                sp._wait_for_log(log, proc, r"UPSTREAM_CUSTOM_GAME_MENU maps=18 saves=2\b", 85)
                sp._wait_for_log(log, proc, rf"UPSTREAM_CUSTOM_GAME_SELECTED source=save file={re.escape(name)}\b", 85)
                sp._wait_for_log(log, proc, rf"UPSTREAM_SP_LOADED source=customgame-save map={map_name} .*\bcopper={copper}\b", 85)
                if save:
                    sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED path=.*/saves/0\.msav .*\bcopper=17\b", 90)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 110)
            finally:
                rpc.close()
                sp._stop_proc(proc, azahar)
                (output / (map_name + ".log")).write_bytes(log.read_bytes())
        for other in ("Caldera Test.msav", "sector-serpulo-170.msav"):
            if (saves / other).read_bytes() != (fixture / "saves" / other).read_bytes():
                raise AssertionError(f"Saving Glacier changed the unrelated stock save {other}")
        original_glacier = (fixture / "saves/0.msav").read_bytes()
        if (saves / "0.msav").read_bytes() == original_glacier:
            raise AssertionError("The selected stock Glacier save was not rewritten")
        if (saves / "0.msav-backup.msav").read_bytes() != original_glacier:
            raise AssertionError("The selected slot did not retain its original backup bytes")
        readback(args, saves / "0.msav")
        print(f"[PASS] {model}: both PC saves opened and stock Java reopened the selected write", flush=True)
    finally:
        for path, data in original.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--maps", type=Path, required=True, help="original default map directory")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture = args.output / "fixture"
    create(args, fixture)
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("new3ds",):
        replay(args, model, fixture, azahar, config, log)


if __name__ == "__main__":
    main()
