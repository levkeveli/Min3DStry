"""Compare empty and populated ARM11 frames from the stock Java server."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import BUILDINGS, create_fixtures
from verify_offline_power_buildings import verify_live
from verify_upstream_singleplayer import az


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16669)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    azahar = az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    empty, stock = create_fixtures(args.output / "stock-server", args.java, args.server, args.port)
    for model in ("new3ds",):
        output = args.output / model
        baseline = verify_live(args, model, azahar, empty, None, output / "empty")
        counts = verify_live(args, model, azahar, stock, baseline, output / "stock", BUILDINGS)
        for name, changed in counts.items():
            print(f"UPSTREAM_LIVE_MATERIAL_PIXELS model={model} block={name} changed={changed}/25", flush=True)


if __name__ == "__main__":
    main()
