"""Make and verify original v160.8 Serpulo background production fixtures."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


CAMPAIGN = """
const preset=Packages.mindustry.content.SectorPresets;
const landing=preset.groundZero.sector, producer=preset.frozenForest.sector,
      frozen=preset.crateredBattleground.sector;
const sectors=[landing,producer,frozen];
const SaveIO=Packages.mindustry.io.SaveIO, Fi=Packages.arc.files.Fi;
const Core=Packages.arc.Core, Settings=Core.settings;
const file=s=>Fi.get('saves/sector-'+s.planet.name+'-'+s.id+'.msav');
"""

VERIFY = """
if(Vars.state.rules.defaultTeam.core().items.get(Items.copper)!==37
   || producer.info.items.get(Items.copper)!==100
   || producer.info.items.get(Items.lead)!==0
   || frozen.info.items.get(Items.copper)!==11 || !frozen.info.waves
   || !landing.planet.campaignRules.legacyLaunchPads) throw 'Unexpected original background campaign state';
"""


def create(java, server, output):
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-background-") as temp:
        root = Path(temp)
        script = CAMPAIGN + """
            Vars.logic.reset();
            if(Core.assets==null) Core.assets=new Packages.arc.assets.AssetManager();
            const saveList=new Packages.mindustry.game.Saves();
            function prepare(sector, ongoingWaves){
                Vars.world.loadSector(sector);
                Vars.logic.play();
                Vars.state.rules.waves=ongoingWaves;
                Vars.state.rules.attackMode=false;
                Vars.state.rules.defaultTeam.core().items.set(Items.copper,7);
                const f=file(sector);
                f.parent().mkdirs();
                SaveIO.write(f);
                sector.save=new Packages.mindustry.game.Saves.SaveSlot(saveList,f);
                sector.save.meta=SaveIO.getMeta(f);
            }
            prepare(producer,false);
            prepare(frozen,true);
            prepare(landing,false);
            const rules=landing.planet.campaignRules;
            rules.legacyLaunchPads=true;
            landing.planet.saveRules();
            for (let i=0;i<sectors.length;i++){
                const s=sectors[i];
                s.info.hasCore=true;
                s.info.storageCapacity=100;
                s.info.waves=s===frozen;
                s.info.attack=false;
            }
            const stat=n=>{const s=new Packages.mindustry.game.SectorInfo.ExportStat(); s.mean=n; return s;};
            producer.info.destination=landing;
            producer.info.items.set(Items.copper,80);
            producer.info.items.set(Items.lead,4);
            producer.info.production.put(Items.copper,stat(0.5));
            producer.info.production.put(Items.lead,stat(-1));
            producer.info.export.put(Items.copper,stat(0.25));
            frozen.info.items.set(Items.copper,11);
            frozen.info.production.put(Items.copper,stat(0.5));
            frozen.info.destination=landing;
            frozen.info.export.put(Items.copper,stat(0.25));
            for (let i=0;i<sectors.length;i++) sectors[i].saveInfo();
            Settings.put('last-sector-save','sector-serpulo-170');
            Settings.forceSave();
            Fi.get('config/settings.bin').copyTo(Fi.get('before.bin'));
            const turn=Vars.universe.turn();
            Vars.universe.runTurn();
            if(Vars.universe.turn()!==turn+1 || landing.info.lastImported.get(Items.copper)!==30 || !frozen.isFrozen()) throw 'Invalid stock turn';
        """ + VERIFY + """
            return 'STOCK_BACKGROUND_TURN live=37 import=30 remote=100 lead=0 frozen=11';
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"STOCK_BACKGROUND_TURN live=37")
        output.mkdir(parents=True)
        shutil.copytree(root / "saves", output / "saves")
        shutil.copyfile(root / "before.bin", output / "settings.bin")
        shutil.copyfile(root / "server.log", output / "stock-reference.log")
    print("Stock Java verified export, storage caps, and an attacked sector staying frozen.")


def check(java, server, output):
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-background-readback-") as temp:
        root = Path(temp)
        (root / "config").mkdir()
        shutil.copyfile(output / "settings.bin", root / "config/settings.bin")
        shutil.copytree(output / "saves", root / "saves")
        script = CAMPAIGN + """
            const turn=Settings.getInt('turn',0);
            if(turn!==1) throw 'The handheld turn count was not saved';
            landing.planet.loadRules();
            for (let i=0;i<sectors.length;i++) sectors[i].loadInfo();
            SaveIO.load(file(landing),Vars.world.makeSectorContext(landing));
        """ + VERIFY + """
            return 'STOCK_BACKGROUND_REOPENED';
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"STOCK_BACKGROUND_REOPENED")
    print("Stock Java reopened the turn, inventories, exports and freeze from the port.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--java", default="java")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--create", type=Path)
    action.add_argument("--check", type=Path)
    args = parser.parse_args()
    if args.create:
        create(args.java, args.server, args.create)
    else:
        check(args.java, args.server, args.check)


if __name__ == "__main__":
    main()
