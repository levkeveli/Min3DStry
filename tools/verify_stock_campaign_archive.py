#!/usr/bin/env python3
"""Compare the packaged campaign maps with the pinned Mindustry asset archive."""

import argparse
import hashlib
from pathlib import Path
import sys
import zipfile


ARM11 = Path(__file__).resolve().parents[1] / "research/upstream-runtime/arm11"
sys.path.insert(0, str(ARM11))
from prepare_terrain_assets import pack_campaign_maps, pack_planet_data  # noqa: E402


ASSETS_SHA256 = "2728b63d309c27a405744c8b5440d90a65387a41e2c4339b630a239f79f6bd17"
PREFIX = "assets/maps/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True, help="A new scratch directory for the packer output")
    args = parser.parse_args()
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
