#!/usr/bin/env python3
"""Compare the packaged campaign maps with the pinned Mindustry asset archive."""

import argparse
import hashlib
import shutil
from pathlib import Path
import sys
import tempfile
import zipfile

from campaign_settings_fixture import stock_console
from verify_upstream_live_net import SERVER_SHA256


ARM11 = Path(__file__).resolve().parents[1] / "research/upstream-runtime/arm11"
sys.path.insert(0, str(ARM11))
from prepare_terrain_assets import pack_campaign_maps, pack_planet_data  # noqa: E402


ASSETS_SHA256 = "2728b63d309c27a405744c8b5440d90a65387a41e2c4339b630a239f79f6bd17"
PREFIX = "assets/maps/"


def check_saves(java, server, saves=None, *, dense_save=None):
    with server.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != SERVER_SHA256:
            raise ValueError("Expected the official v160.8 server-release.jar")
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-campaign-readback-") as temporary:
        root = Path(temporary)
        (root / "config").mkdir()
        cases = {242: 1} if dense_save is not None else {204: 2, 242: 1}
        if saves is not None:
            shutil.copyfile(saves / "settings.bin", root / "config/settings.bin")
        for sector in cases:
            name = f"sector-serpulo-{sector}.msav"
            shutil.copyfile(dense_save if dense_save is not None else saves / "saves" / name, root / name)
        script = '''
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            const serpulo = Packages.mindustry.content.Planets.serpulo;
            serpulo.loadRules();
            function check(id, patches) {
                const sector = serpulo.sectors.get(id);
                const file = Fi.get('sector-serpulo-' + id + '.msav');
                sector.loadInfo();
                if (SaveIO.getMeta(file).rules.sector.id !== id) throw 'Incorrect saved sector ID';
                SaveIO.load(file, Vars.world.makeSectorContext(sector));
                if (Vars.state.rules.sector.id !== id || Vars.world.width() !== 600 ||
                    Vars.world.height() !== 600 || Vars.state.data.getPatches().size !== patches)
                    throw 'Stock Java read an incorrect sector, size or patch count for ' + id;
            }
        '''
        sector_list = ",".join(str(sector) for sector in cases)
        result = f"STOCK_CAMPAIGN_ARCHIVE_READBACK sectors={sector_list} dimensions=600x600 patches={sum(cases.values())}"
        script += " ".join(f"check({sector}, {patches});" for sector, patches in cases.items())
        script += f" return '{result}';"
        stock_console(java, server, root, " ".join(script.splitlines()), result)
    print(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--stage", type=Path, help="A new scratch directory for the packer output")
    parser.add_argument("--server", type=Path, help="Original v160.8 server jar for the save readback")
    parser.add_argument("--check-saves", type=Path, help="Scratch profile written by the JVM campaign reference")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if args.check_saves is not None:
        if args.server is None or args.assets is not None or args.stage is not None:
            parser.error("--check-saves requires --server and runs separately from archive staging")
        check_saves(args.java, args.server, args.check_saves)
        return
    if args.assets is None or args.stage is None:
        parser.error("Archive staging requires --assets and --stage")
    if args.stage.exists():
        parser.error(f"Choose a new staging directory: {args.stage}")
    with args.assets.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != ASSETS_SHA256:
            parser.error("Expected the official v160.8 assets.jar")
    args.stage.mkdir(parents=True)
    maps = args.stage / "maps"
    with zipfile.ZipFile(args.assets) as archive:
        pack_campaign_maps(archive, maps)
        pack_planet_data(archive, args.stage / "planets")
        (args.stage / "bundle.properties").write_bytes(archive.read("assets/bundles/bundle.properties"))
        expected = {
            name.removeprefix(PREFIX): archive.read(name)
            for name in archive.namelist()
            if name.endswith(".msav") and name.startswith((PREFIX + "serpulo/", PREFIX + "erekir/"))
        }
    actual = {file.relative_to(maps).as_posix(): file for file in maps.rglob("*.msav")}
    if set(expected) != set(actual):
        raise RuntimeError(f"Campaign map list differs: missing={sorted(expected.keys() - actual.keys())}, "
                           f"extra={sorted(actual.keys() - expected.keys())}")
    for name, payload in expected.items():
        if actual[name].read_bytes() != payload:
            raise RuntimeError(f"Campaign map bytes differ: {name}")
    hidden = sum(name.startswith("serpulo/hidden/") for name in actual)
    serpulo = sum(name.startswith("serpulo/") for name in actual)
    print(f"STOCK_CAMPAIGN_ARCHIVE total={len(actual)} serpulo={serpulo} hidden={hidden} erekir={len(actual) - serpulo}")


if __name__ == "__main__":
    main()
