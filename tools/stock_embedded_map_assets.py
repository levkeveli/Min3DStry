#!/usr/bin/env python3
"""Write an embedded map asset with the stock game and verify a port save."""

import argparse
from pathlib import Path
import shutil
import tempfile
import zipfile

from campaign_settings_fixture import stock_console


MARKER = "STOCK_EMBEDDED_MAP_ASSET"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--readback", action="store_true")
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="m3ds-embedded-map-") as temporary:
        root = Path(temporary)
        if args.readback:
            shutil.copyfile(args.directory / "roundtrip.msav", root / "roundtrip.msav")
            shutil.copytree(args.directory / "profile/assetCache", root / "assetCache")
            shutil.copyfile(args.directory / "expected.png", root / "expected.png")
            script = """
                const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
                Vars.assetCacheDirectory = Fi.get('assetCache');
                Vars.assetCache = new Packages.mindustry.mod.DataAssetCache();
                Vars.assetCache.load();
                SaveIO.load(Fi.get('roundtrip.msav'));
                const image = Vars.state.data.getImages().first();
                if (image.path !== 'fixture.png' || !image.isCached()
                    || !Packages.java.util.Arrays.equals(image.getCacheFile().readBytes(), Fi.get('expected.png').readBytes()))
                    throw 'Stock Java lost the cached image';
            """
        else:
            with zipfile.ZipFile(args.server) as archive:
                (root / "glacier.msav").write_bytes(archive.read("maps/default/glacier.msav"))
                (root / "expected.png").write_bytes(archive.read("icons/icon.png"))
            script = """
                const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
                Vars.world.loadMap(Packages.mindustry.io.MapIO.createMap(Fi.get('glacier.msav'), false));
                Vars.logic.play();
                const image = new Packages.mindustry.mod.data.ImageAsset();
                image.readOverride('fixture.png', Fi.get('expected.png'));
                Vars.state.data.load(Packages.arc.struct.Seq.with(image));
                const options = new Packages.mindustry.io.SaveOptions();
                options.embedAssets = true;
                options.extraTags = Vars.state.map.tags;
                SaveIO.save(Fi.get('embedded.msav'), options);
            """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()) + f" return '{MARKER}';", MARKER)
        if not args.readback:
            for name in ("embedded.msav", "expected.png"):
                shutil.copyfile(root / name, args.directory / name)
    print(f"{MARKER} {'stock-readback' if args.readback else 'stock-source'}=ok")


if __name__ == "__main__":
    main()
