#!/usr/bin/env python3
"""Pack the original atlas regions drawn by the Glacier terrain viewport."""

import argparse
import csv
from pathlib import Path
import subprocess
import sys
import tempfile


REGIONS = (
    "ice-edge", "ice1", "ice2", "ice3", "ore-coal1", "ore-coal2", "ore-coal3",
    "ore-copper1", "ore-copper2", "ore-copper3", "stone1", "stone2", "stone3",
)
CONVERTER = Path(__file__).resolve().parents[3] / "tools" / "build_upstream_sprite_atlas.py"
GLACIER_INVENTORY = Path(__file__).with_name("glacier-terrain.txt")


def write_layout(staged: Path, regions: tuple[str, ...]) -> None:
    entries = {}
    page_indices = {}
    with (staged / "regions.tsv").open(encoding="utf-8", newline="") as inventory:
        for row in csv.DictReader(inventory, delimiter="\t"):
            entries[row["name"]] = row
            page_indices.setdefault(row["page"], len(page_indices))

    lines = ['#include "sprites.h"', '', 'static const ArcAtlasRegion terrain_regions[] = {']
    for name in regions:
        row = entries[name]
        position = [str(page_indices[row["page"]])]
        position.extend(str(int(row[key])) for key in ("left", "top", "width", "height"))
        position.append(f"sprites_{name.replace('-', '_')}_idx")
        lines.append("    { " + ", ".join(position) + " },")
    lines.append("};")
    (staged / "terrain_layout.h").write_text("\n".join(lines) + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--tex3ds", default="tex3ds")
    parser.add_argument("--multiplayer", action="store_true", help="pack Glacier and upstream multiplayer unit sprites")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    if output.exists():
        parser.error(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    regions = REGIONS
    if args.multiplayer:
        surveyed = GLACIER_INVENTORY.read_text(encoding="ascii").splitlines()
        conveyors = (f"conveyor-{variant}-{frame}" for variant in range(5) for frame in range(4))
        regions = tuple(dict.fromkeys((*surveyed, "gamma", "dagger", "copper-wall", "router", *conveyors)))
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staged = Path(tmp) / "terrain"
        subprocess.run([sys.executable, str(CONVERTER), "--assets", str(args.assets),
                        "--server", str(args.server), "--output-dir", str(staged),
                        "--java", args.java, "--tex3ds", args.tex3ds, *regions], check=True)
        write_layout(staged, regions)
        staged.rename(output)


if __name__ == "__main__":
    main()
