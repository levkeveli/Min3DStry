"""Shared stock Glacier placements and screenshot checks for liquid puddles."""

import verify_azahar as az


SETUP = (
    "const a=Vars.world.tile(75,24), b=Vars.world.tile(85,24); "
    "if(a.build!==null || b.build!==null) throw 'Occupied puddle fixture'; "
    "a.setFloor(Blocks.stone.asFloor()); b.setFloor(Blocks.stone.asFloor()); "
    "b.setBlock(Blocks.berylliumWall,Vars.state.rules.defaultTeam); "
    "Vars.state.rules.waves=false; Vars.state.rules.fog=false;"
)
DEPOSIT = (
    "const puddles=Packages.mindustry.entities.Puddles; "
    "puddles.deposit(Vars.world.tile(75,24),Liquids.water,70); "
    "puddles.deposit(Vars.world.tile(85,24),Liquids.neoplasm,70);"
)


def changed_colors(before, after):
    # Stock Glacier starts with viewport (73, 20) at twenty pixels per tile.
    water = tuple((x, y) for x in range(82, 99, 2) for y in range(142, 159, 2))
    # The solid wall covers the center. Sample around its edges as well as its face.
    neoplasm = tuple((x, y) for x in range(270, 311, 2) for y in range(130, 173, 2))
    points = water + neoplasm
    cold = az.inspect_png(before, points)["samples"]
    active = az.inspect_png(after, points)["samples"]

    def changed(point):
        return max(abs(a - b) for a, b in zip(cold[point], active[point])) >= 24

    blue = sum(changed(p) and active[p][2] > active[p][0] + 20 for p in water)
    red = sum(changed(p) and active[p][0] > active[p][2] + 45 for p in neoplasm)
    return blue, red
