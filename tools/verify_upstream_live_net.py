#!/usr/bin/env python3
"""Join an official v160.8 loopback server from both Azahar console profiles."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import time
import zipfile

import verify_azahar as az


SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
ATLAS_SHA256 = "fcfb2171eed35b60e7a0d696b63943883a191e7f547ecfef372bd42d72675334"
PLAYER = "Min3DStry ARM11"
NUMBER = r"-?\d+(?:\.\d+)?"
# The unsplit v160.8 core at spawn on both Azahar profiles. These samples
# straddle the source tile seams and stay clear of the player unit.
CORE_PIXELS = {
    (190, 65): (255, 210, 126), (190, 67): (255, 210, 126),
    (231, 75): (152, 154, 164), (232, 75): (152, 154, 164), (233, 75): (176, 185, 192),
    (235, 145): (176, 185, 192), (235, 146): (176, 185, 192),
    (235, 147): (176, 185, 192), (235, 148): (152, 154, 164),
}
# Splitting this 160 px sprite at the 115/45 px atlas boundary can move the
# rasterized seam by one pixel. Require each original color in a 3x3
# neighborhood so a one-pixel boundary shift does not fail the whole frame.
CORE_PIXEL_TOLERANCE = 1


def world_atlas_pixels_match(samples):
    for (x, y), expected in CORE_PIXELS.items():
        if not any(
            samples.get((x + dx, y + dy)) == expected
            for dx in range(-CORE_PIXEL_TOLERANCE, CORE_PIXEL_TOLERANCE + 1)
            for dy in range(-CORE_PIXEL_TOLERANCE, CORE_PIXEL_TOLERANCE + 1)
        ):
            return False
    return True

# Opaque core-shard texels outside the Alpha's shadow at the stock Fortress spawn.
FORTRESS_CORE_PIXELS = {
    (185, 86): (119, 121, 136),
    (191, 94): (176, 185, 192), (227, 126): (176, 185, 192),
    (233, 86): (154, 157, 167), (233, 134): (119, 121, 136),
}

# Opaque core-bastion shell and bare Evoke hull texels from the pinned atlas.
ONSET_SPAWN_PIXELS = {
    (170, 86): (110, 112, 128), (175, 90): (114, 116, 132),
    (225, 90): (147, 149, 160), (234, 145): (119, 121, 136),
    (189, 109): (140, 142, 152), (193, 114): (114, 116, 132),
    (196, 124): (212, 128, 107), (203, 124): (212, 128, 107),
}

MAP_SPAWNS = {
    "Fortress": ("256", "256", "core-shard", "alpha", "116", "73"),
    "Ground Zero": ("256", "256", "core-shard", "alpha", "129", "55"),
    "The Onset": ("512", "512", "core-bastion", "evoke", "276", "107"),
}
MAP_SPAWN_PIXELS = {"Fortress": FORTRESS_CORE_PIXELS, "The Onset": ONSET_SPAWN_PIXELS}

# Original atlas texels from the rotated Duct shell and the Beryllium Wall.
EREKIR_BUILD_PIXELS = {
    "duct": {(263, 126): (110, 112, 128), (276, 134): (110, 112, 128)},
    "beryllium-wall": {(263, 144): (74, 139, 106), (269, 146): (126, 199, 128),
                       (276, 144): (120, 192, 126)},
}

# Opaque RGB from the original wall, drill base and drill top at Glacier spawn.
BUILDING_LAYER_PIXELS = {
    (39, 80): (24, 27, 32),  # Clipping keeps the portion west of the viewport in the margin.
    (42, 65): (200, 165, 143), (52, 80): (200, 165, 143), (46, 90): (177, 135, 104),
    (301, 57): (143, 102, 91), (338, 71): (176, 185, 192), (319, 59): (177, 135, 104),
}


# At 50% health, opaque crack texels tint the stock wall and drill base
# with RGB 51 and alpha 0.4. GPU blending can round by one channel value.
BUILDING_CRACK_PIXELS = {
    (46, 68): (127, 101, 83), (58, 78): (140, 119, 106),
    (313, 41): (126, 131, 136), (300, 58): (106, 82, 75),
}


def write_controls_replay(path, first_move=22, building=False, touch_build=False, touch_config=False,
                          erekir_build=False, mining=None, model="old3ds"):
    """Write the Azahar 2126.2 CTM input for this probe's pad/touch polling."""
    # The pinned Azahar movie header and HID sample definitions use packed little-endian fields.
    revision = bytes.fromhex("219d743a1e698101fe1ab8bef43e6563126c3c27")
    samples_per_second = 234
    # Construction and configuration replays can take longer between input polls.
    needs_settling_time = building or touch_build or touch_config
    circle_at = first_move + (18 if needs_settling_time else 6)
    leave_at = first_move + (44 if needs_settling_time else 16)
    frames = (128 if erekir_build or mining else leave_at + 7) * samples_per_second
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, revision, 1780000000,
                         0x4D334453, b"Min3DStry input verification", 0, frames, 0)
    touch_idle = struct.pack("<B6x", 1)
    taps = (
        (35, (200, 24)), (39, (232, 136)), (43, (232, 136)),
        (48, (120, 24)), (53, (248, 136)), (60, (280, 24)), (65, (248, 136)),
        (70, (40, 24)), (74, (15, 64)), (79, (268, 218)),
        (84, (160, 130)), (89, (264, 136)), (93, (264, 136)),
    ) if touch_build else ()
    if touch_config:
        taps = (
            (35, (15, 64)), (40, (268, 218)), (45, (160, 80)), (50, (232, 136)),
            (58, (305, 64)), (62, (232, 136)), (67, (160, 55)),
            (75, (305, 64)), (79, (232, 136)), (84, (160, 80)),
        )
    if erekir_build:
        taps = ((60, (40, 24)), (76, (216, 152)),
                (91, (120, 24)), (96, (216, 168)))
    if mining == "wall":
        # The panning distance depends on the render cadence. Along the right
        # column, the original deposit occupies a different row at each stop.
        ore_rows = (168, 152, 136, 120, 104)
        taps = ((73, (305, 96)),) + tuple(
            (start + 4 * index, (280, row))
            for start in (80, 106) for index, row in enumerate(ore_rows)
        )
    elif mining == "floor":
        taps = ((58, (305, 96)), (62, (305, 96)), (67, (168, 232)))
    pan_end = 61 if model == "new3ds" else 60
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            seconds = frame // samples_per_second
            if erekir_build:
                buttons = (1 << 8) if 66 <= seconds < 72 else 0  # R, the 3DS rotate shortcut.
            elif mining:
                buttons = 0
            else:
                buttons = (1 << 4) if seconds == first_move else (1 << 3) if seconds == leave_at else 0
            if building and seconds in (35, 37, 39):
                buttons = 1  # A, with releases so a late HID poll can observe another press.
            circle_x = 65 if mining == "wall" and 54 <= seconds < pan_end else 0
            circle_y = 106 if seconds == circle_at and not (erekir_build or mining) else 0
            if mining == "floor" and 88 <= seconds < 94:
                circle_y = 65
            point = next((point for start, point in taps if start <= seconds < start + 2), None)
            pad = struct.pack("<BHhh", 0, buttons, circle_x, circle_y)
            if point:
                # Extra HID polls on New 3DS can fall between pad/touch pairs.
                # Keep the touch held for the whole burst regardless of the poll phase.
                touch = struct.pack("<BHHBx", 1, *point, 1)
                movie.write(touch * 2)
            elif taps and (buttons or circle_x or circle_y):
                movie.write(pad * 2)
            else:
                movie.write(pad + touch_idle)
            if taps and model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(touch if point else pad)


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def wait_for(path, proc, pattern, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(pattern, text):
            return text
        if "UPSTREAM_LIVE_NET_ERROR" in text:
            time.sleep(0.25)  # Preserve the Java stack trace written after the error marker.
            text = path.read_text(encoding="utf-8", errors="replace")
            raise RuntimeError(f"Process stopped before {pattern}:\n{text[-5000:]}")
        if "UPSTREAM_MEMORY managed-heap-exhausted" in text or "UPSTREAM_GC " in text:
            raise RuntimeError(f"ARM11 memory failure before {pattern}:\n{text[-2500:]}")
        if proc.poll() is not None or re.search(r"Core::LogException", text):
            raise RuntimeError(f"Process stopped before {pattern}:\n{text[-2500:]}")
        time.sleep(0.05)
    raise RuntimeError(f"Timed out waiting for {pattern}:\n{text[-2500:]}")


def server_position(server_log, server, marker):
    # Read the stock server's unit and connection without mutating gameplay state.
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        'const u = p.unit(); '
        f'return "{marker} unit=" + u.id + " snapshot=" + p.con.lastReceivedClientSnapshot '
        '+ " x=" + u.x + " y=" + u.y; })()\n'
    )
    server.stdin.flush()
    # A connected player can have a unit while the first input snapshot is still in flight.
    state = re.compile(rf"{marker} unit=(\d+) snapshot=(-1|\d+) x=({NUMBER}) y=({NUMBER})")
    return state.search(wait_for(server_log, server, state, 5))


def inspect_map_spawn(server_log, server):
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        'const c = p.bestCore(); '
        'if (p.unit().type != c.block.unitType) throw "Unexpected core spawn unit"; '
        'return "UPSTREAM_MAP_SPAWN width=" + Vars.world.width() + " height=" + Vars.world.height() '
        '+ " core=" + c.block.name + " unit=" + p.unit().type.name '
        '+ " x=" + c.tileX() + " y=" + c.tileY(); })()\n'
    )
    server.stdin.flush()
    pattern = re.compile(r"UPSTREAM_MAP_SPAWN width=(\d+) height=(\d+) core=(\S+) unit=(\S+) x=(\d+) y=(\d+)")
    record = pattern.search(wait_for(server_log, server, pattern, 5))
    print(record.group(0), flush=True)
    return record.groups()


def verify_map_spawn(log, emulator, map_name, fixture, position):
    width, height, core, unit, tile_x, tile_y = fixture
    wait_for(log, emulator, rf"UPSTREAM_LIVE_NET_WORLD join=1 map={re.escape(map_name)} bytes=", 5)
    wait_for(log, emulator, rf"UPSTREAM_WORLD width={width} height={height} tiles={int(width) * int(height)}\b", 5)
    wait_for(log, emulator, rf"UPSTREAM_WORLD_BUILDING {tile_x} {tile_y} {re.escape(core)} ", 5)
    if map_name in MAP_SPAWNS and fixture != MAP_SPAWNS[map_name]:
        raise RuntimeError(f"Expected the original {map_name} spawn, observed {fixture}")
    if map_name == "The Onset":
        wait_for(log, emulator, r"UPSTREAM_WORLD_KEEPALIVE transport=tcp\b", 5)
        wait_for(log, emulator, r"UPSTREAM_WORLD_OBJECTIVES count=20 dependencies=19 markers=20\b", 5)
        wait_for(log, emulator, r"UPSTREAM_WORLD_ITEM_OBJECTIVE item=beryllium amount=60\b", 5)
    if any(abs(float(observed) - int(tile) * 8) > 4 for observed, tile in zip(position[2:], (tile_x, tile_y))):
        raise RuntimeError(f"Expected controlled unit {unit} near its stock spawn, observed {position}")


def verify_movement(log, emulator, server_log, server, join, timeout):
    movement = re.compile(rf"UPSTREAM_LIVE_NET_MOVEMENT join={join} unit=(\d+) snapshot=(\d+) "
                          rf"fromX=({NUMBER}) x=({NUMBER}) y=({NUMBER})")
    trace = movement.search(wait_for(log, emulator, movement, timeout))
    unit, sequence, start_x, x, y = trace.groups()
    if float(x) - float(start_x) < 32:
        raise RuntimeError("The upstream client unit did not move at least four tiles")
    observed = server_position(server_log, server, f"UPSTREAM_SERVER_MOVEMENT join={join}")
    server_unit, server_sequence, server_x, server_y = observed.groups()
    if (unit != server_unit or int(server_sequence) < int(sequence) - 3
            or abs(float(x) - float(server_x)) > 2 or abs(float(y) - float(server_y)) > 2):
        raise RuntimeError(f"Unexpected server movement: {observed.group(0)} vs {trace.group(0)}")
    print(observed.group(0), flush=True)


def inspect_building(server_log, server, phase, tile_x, tile_y, builder=PLAYER, item="copper"):
    marker = f"UPSTREAM_SERVER_BUILDING phase={phase}"
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        f'const builder = Groups.player.find(boolf(v => String(v.name) === {json.dumps(builder)})); '
        f'const tile = Vars.world.tile({tile_x}, {tile_y}); '
        'const owned = builder != null && tile.build != null && '
        'String(tile.build.lastAccessed) === String(builder.coloredName()); '
        f'return "{marker} block=" + tile.block().name + " team=" + tile.team().id '
        '+ " rotation=" + (tile.build == null ? -1 : tile.build.rotation) '
        f'+ " {item}=" + p.team().core().items.get(Items.{item}) + " builder=" + owned; }})()\n'
    )
    server.stdin.flush()
    check = re.compile(rf"{marker} block=(\S+) team=(\d+) rotation=(-?\d+) {item}=(\d+) builder=(true|false)")
    return check.search(wait_for(server_log, server, check, 5)).groups()


def verify_erekir_construction(log, emulator, server_log, server, timeout, capture, spawn):
    wait_for(log, emulator, r"UPSTREAM_BUILD_SHORTCUTS primary=duct secondary=beryllium-wall\b", timeout)
    for block, y, rotation, balance in (("duct", 107, 1, 15), ("beryllium-wall", 106, 0, 9)):
        touch_plan = re.compile(rf"UPSTREAM_TOUCH_PLAN block={block} x=280 y={y} rotation=(-?\d+)\b")
        plan = touch_plan.search(wait_for(log, emulator, touch_plan, timeout))
        if int(plan[1]) != rotation:
            raise RuntimeError(f"Expected {block} rotation {rotation}, observed {plan[0]}")
        wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD block={block} x=280 y={y} rotation={rotation} team=1\b", timeout)
        result = inspect_building(server_log, server, block, 280, y, item="beryllium")
        if result != (block, "1", str(rotation), str(balance), "true"):
            raise RuntimeError(f"Unexpected stock {block} placement: {result}")
        capture(block, spawn)
    print("The stock server placed both Erekir blocks for seven beryllium.", flush=True)


def inspect_mining(server_log, server, marker, item):
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        f'if (p == null) return "{marker} target=disconnected {item}=0"; '
        'const u = p.unit(), t = u.mineTile, core = p.team().core(); '
        f'return "{marker} target=" + (t == null ? "none" : t.x + "," + t.y) + '
        f'" {item}=" + core.items.get(Items.{item}); }})()\n'
    )
    server.stdin.flush()
    record = re.compile(rf"{marker} target=(none|disconnected|\d+,\d+) {item}=(\d+)")
    target, balance = record.search(wait_for(server_log, server, record, 5)).groups()
    return target, int(balance)


def verify_mining(log, emulator, server_log, server, timeout, capture, mining, item, objectives):
    target = re.compile(rf"UPSTREAM_MINE_TARGET x=(\d+) y=(\d+) item={item}\b")
    x, y = target.search(wait_for(log, emulator, target, timeout)).groups()
    # Let the stock MinerComp advance before reading the selected tile and deposited items.
    time.sleep(3)
    selected, balance = inspect_mining(server_log, server, "UPSTREAM_SERVER_MINING_ACTIVE", item)
    if selected != f"{x},{y}" or balance <= 0:
        raise RuntimeError(f"Expected stock {item} mining at {x},{y}, observed {selected} with {balance} items")
    core_sync = re.compile(rf"UPSTREAM_MINE_CORE_SYNC item={item} count=([1-9]\d*)\b")
    client_count = int(core_sync.search(wait_for(log, emulator, core_sync, 10))[1])
    position = server_position(server_log, server, "UPSTREAM_SERVER_MINING_POSITION").groups()
    capture(f"{mining}-mining", position)
    if mining == "wall":
        wait_for(log, emulator, rf"UPSTREAM_MINE_CANCEL x={x} y={y}\b", timeout)
    else:
        wait_for(log, emulator, rf"UPSTREAM_LIVE_CONTROLS_STOPPED unit={position[0]} input=circle\b", timeout)
        server.stdin.write(
            f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
            f'const u = p.unit(), t = Vars.world.tile({x},{y}); '
            'return "UPSTREAM_SERVER_MINING_RANGE out=" + '
            '(!u.within(t.worldx(), t.worldy(), u.type.mineRange)); })()\n'
        )
        server.stdin.flush()
        wait_for(server_log, server, r"UPSTREAM_SERVER_MINING_RANGE out=true\b", 5)
    time.sleep(2)
    stopped = inspect_mining(server_log, server, "UPSTREAM_SERVER_MINING_STOPPED", item)
    time.sleep(2)
    settled = inspect_mining(server_log, server, "UPSTREAM_SERVER_MINING_SETTLED", item)
    if stopped[0] != "none" or settled != stopped or settled[1] < max(balance, client_count):
        raise RuntimeError(f"Expected mining to stop and inventory to settle, observed {stopped} then {settled}")
    print(f"Stock {mining} mining at ({x}, {y}) delivered {settled[1]} {item} before stopping.", flush=True)
    if objectives:
        verify_onset_objective(log, emulator, server_log, server, settled[1])


def verify_onset_objective(log, emulator, server_log, server, beryllium):
    if beryllium < 60:
        raise RuntimeError(f"Expected at least 60 beryllium for the original objective, got {beryllium}")
    server.stdin.write(
        'js (() => { var rules=Vars.state.rules, all=rules.objectives.all, first=all.get(0), next=all.get(1); '
        'return "UPSTREAM_SERVER_ONSET_OBJECTIVE first=" + first.getClass().getSimpleName() + '
        '" completed=" + first.isCompleted() + " next=" + next.getClass().getSimpleName() + '
        '" eligible=" + next.qualified() + " flags=" + rules.objectiveFlags.size; })()\n'
    )
    server.stdin.flush()
    record = re.compile(r"UPSTREAM_SERVER_ONSET_OBJECTIVE first=ItemObjective completed=true "
                        r"next=BuildCountObjective eligible=true flags=(\d+)\b")
    flags = record.search(wait_for(server_log, server, record, 5))[1]
    match = r"UPSTREAM_OBJECTIVE_COMPLETE index=0 kind=ItemObjective next=1 nextKind=BuildCountObjective"
    wait_for(log, emulator, rf"{match} flags={flags}\b", 5)
    print("The stock server and ARM11 client advanced to Onset objective 1 after 60 beryllium.", flush=True)


def verify_conveyor(log, emulator, server_log, server, timeout, capture, position, desktop_name):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)

    original = inspect_building(server_log, server, "before", tx, ty)
    print(f"Press A to queue a conveyor at ({tx}, {ty}).", flush=True)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD_PLAN x={tx} y={ty} rotation=0\b", timeout)
    completed = re.compile(rf"UPSTREAM_LIVE_BUILD block=conveyor x={tx} y={ty} rotation=0 team=(\d+)")
    accepted = completed.search(wait_for(log, emulator, completed, timeout))
    finished = inspect_building(server_log, server, "after", tx, ty)
    if (original[0] != "air" or finished[:3] != ("conveyor", accepted[1], "0")
            or int(original[3]) - int(finished[3]) != 1 or finished[4] != "true"):
        raise RuntimeError(f"Unexpected stock conveyor or copper cost: {original} -> {finished}")
    wait_for(log, emulator, rf"UPSTREAM_NETWORK_CONVEYOR x={tx} y={ty} rotation=0\b", timeout)
    capture("conveyor", position)
    print(f"Stock server placed the conveyor on team {finished[1]} for one copper.", flush=True)
    if desktop_name:
        print(f"Confirm the conveyor at ({tx}, {ty}) in the stock PC client. Then place another "
              f"conveyor within the 3DS viewport, for example at ({tx}, {ty + 2}).", flush=True)
        pc_build = re.compile(rf"UPSTREAM_LIVE_BUILD block=conveyor (?!x={tx} y={ty} )x=(\d+) y=(\d+) "
                              rf"rotation=([0-3]) team={finished[1]}\b")
        desktop_x, desktop_y, rotation = pc_build.search(wait_for(log, emulator, pc_build, timeout)).groups()
        second_finished = inspect_building(server_log, server, "desktop-after",
                                           int(desktop_x), int(desktop_y), desktop_name)
        if (second_finished[:3] != ("conveyor", finished[1], rotation)
                or int(finished[3]) - int(second_finished[3]) != 1 or second_finished[4] != "true"):
            raise RuntimeError(f"Unexpected PC conveyor builder or copper cost: {finished} -> {second_finished}")
        wait_for(log, emulator, rf"UPSTREAM_NETWORK_CONVEYOR x={desktop_x} y={desktop_y} rotation={rotation}\b", timeout)
        capture("desktop-conveyor", position)
        print(f"The ARM11 client applied and drew the PC conveyor at ({desktop_x}, {desktop_y}).", flush=True)


def wait_for_touch_pixels(capture, phase, position, expected):
    # The packet handler logs completion before the graphics backend presents the resulting frame.
    deadline = time.monotonic() + 5
    while True:
        pixels = capture(phase, position)
        if pixels == expected:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Expected {phase} pixels {expected}, got {pixels}")
        time.sleep(0.1)


def verify_touch_build(log, emulator, server_log, server, timeout, capture, position, ground_pixels):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)
    print("Tap Rotate, the grid, Copper Wall, the adjacent grid cell, then Remove and the wall.", flush=True)
    original = inspect_building(server_log, server, "touch-before", tx, ty)
    if original[0] != "air":
        raise RuntimeError(f"Expected an empty Glacier conveyor cell: {original}")
    copper = int(original[3])
    for block, tile_x, rotation, cost in (("conveyor", tx, 1, 1), ("copper-wall", tx + 1, 0, 6)):
        wait_for(log, emulator, rf"UPSTREAM_TOUCH_PLAN block={block} x={tile_x} y={ty} rotation={rotation}\b", timeout)
        wait_for(log, emulator,
                 rf"UPSTREAM_LIVE_BUILD block={block} x={tile_x} y={ty} rotation={rotation} team=1\b", timeout)
        copper -= cost
        result = inspect_building(server_log, server, f"touch-{block}", tile_x, ty)
        if result != (block, "1", str(rotation), str(copper), "true"):
            raise RuntimeError(f"Unexpected stock {block} placement: {result}")
        if block == "copper-wall":
            wait_for_touch_pixels(capture, "touch-copper-wall", position, ((200, 165, 143),) * 3)
        else:
            capture(f"touch-{block}", position)
    wait_for(log, emulator, rf"UPSTREAM_TOUCH_PLAN block=break x={tx + 1} y={ty}\b", timeout)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_REMOVED x={tx + 1} y={ty}\b", timeout)
    removed = inspect_building(server_log, server, "touch-removed", tx + 1, ty)
    retained = inspect_building(server_log, server, "touch-retained", tx, ty)
    if removed != ("air", "0", "-1", str(copper + 3), "false") or retained[:3] != ("conveyor", "1", "1"):
        raise RuntimeError(f"Unexpected stock removal, copper refund or adjacent conveyor: {removed}, {retained}")
    wait_for_touch_pixels(capture, "touch-removed", position, ground_pixels)
    print(f"Stock server verified both touch placements and the wall refund; copper={removed[3]}.", flush=True)
    wait_for(log, emulator, r"UPSTREAM_CATALOG_PAGE category=distribution page=2\b", timeout)
    time.sleep(0.2)  # The input event is logged before Citro2D presents the page.
    capture("touch-catalog", position)
    wait_for(log, emulator, r"UPSTREAM_CATALOG_SELECTED router\b", timeout)
    wait_for(log, emulator, rf"UPSTREAM_TOUCH_PLAN block=router x={tx + 2} y={ty} rotation=0\b", timeout)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD block=router x={tx + 2} y={ty} rotation=0 team=1\b", timeout)
    router = inspect_building(server_log, server, "touch-router", tx + 2, ty)
    if router != ("router", "1", "0", str(int(removed[3]) - 3), "true"):
        raise RuntimeError(f"Unexpected stock catalog placement: {router}")
    capture("touch-router", position)
    print(f"Stock server verified the catalog Router; copper={router[3]}.", flush=True)


def verify_touch_config(log, emulator, server_log, server, timeout, capture, position, menu_icons=False):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)
    original = inspect_building(server_log, server, "config-before", tx, ty)
    catalog_open = r"UPSTREAM_CATALOG_PAGE category=distribution page=2\b"
    if menu_icons:
        catalog_open += r"[\s\S]*?UPSTREAM_MENU_ICONS type=block drawn=6\b"
    wait_for(log, emulator, catalog_open, timeout)
    time.sleep(0.2)
    capture("touch-catalog", position)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD block=sorter x={tx} y={ty} rotation=0 team=1\b", timeout)
    placed = inspect_building(server_log, server, "config-placed", tx, ty)
    if original[0] != "air" or placed != ("sorter", "1", "0", str(int(original[3]) - 2), "true"):
        raise RuntimeError(f"Unexpected stock Sorter placement: {original} -> {placed}")
    unconfigured = capture("touch-sorter", position)
    config_open = rf"UPSTREAM_CONFIG_OPEN x={tx} y={ty}\b"
    if menu_icons:
        config_open += r"[\s\S]*?UPSTREAM_MENU_ICONS type=item drawn=6\b"
    wait_for(log, emulator, config_open, timeout)
    time.sleep(0.2)  # The open event precedes presentation.
    capture("touch-config-menu", position)

    def server_item(phase, expected, set_item=""):
        marker = f"UPSTREAM_SERVER_CONFIG phase={phase}"
        server.stdin.write(
            f'js (() => {{ const b = Vars.world.build({tx}, {ty}); {set_item} '
            f'return "{marker} item=" + (b.config() == null ? "none" : b.config().name); }})()\n'
        )
        server.stdin.flush()
        wait_for(server_log, server, rf"{marker} item={expected}\b", 5)

    wait_for(log, emulator, rf"UPSTREAM_CONFIG_LOCAL x={tx} y={ty} item=copper\b", timeout)
    time.sleep(0.2)  # Allow the stock server to process the reliable request.
    server_item("from-3ds", "copper")
    copper = capture("touch-config-copper", position)
    server_item("to-3ds", "lead", "b.configureAny(Items.lead);")
    wait_for(log, emulator, rf"UPSTREAM_CONFIG_REMOTE x={tx} y={ty} item=lead\b", timeout)
    time.sleep(0.2)
    lead = capture("touch-config-lead", position)
    wait_for(log, emulator, rf"UPSTREAM_CONFIG_LOCAL x={tx} y={ty} item=none\b", timeout)
    time.sleep(0.2)
    server_item("cleared", "none")
    cleared = capture("touch-config-cleared", position)
    if unconfigured != cleared or len({unconfigured, copper, lead}) != 3:
        raise RuntimeError(f"Expected distinct original Sorter colors and the clear sprite: "
                           f"{unconfigured}, {copper}, {lead}, {cleared}")
    print("Stock server verified Copper, returned Lead, and accepted clearing the Sorter.", flush=True)


def verify_controls(log, emulator, server_log, server, timeout, capture=None,
                    building=False, desktop_name=None, touch_build=False, touch_config=False, menu_icons=False):
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", timeout)
    baseline = server_position(server_log, server, "UPSTREAM_SERVER_CONTROLS_READY").groups()
    ground_pixels = capture("spawn", baseline) if capture else None
    if building:
        verify_conveyor(log, emulator, server_log, server, timeout, capture, baseline, desktop_name)
    if touch_build:
        verify_touch_build(log, emulator, server_log, server, timeout, capture, baseline, ground_pixels)
    if touch_config:
        verify_touch_config(log, emulator, server_log, server, timeout, capture, baseline, menu_icons)
    for device, direction, axis in (("dpad", "right", 2), ("circle", "up", 3)):
        print(f"Move {direction} with the {device}, then release it.", flush=True)
        stopped = re.compile(rf"UPSTREAM_LIVE_CONTROLS_STOPPED unit=(\d+) input={device} "
                             rf"x=({NUMBER}) y=({NUMBER})")
        client_unit, client_x, client_y = stopped.search(wait_for(log, emulator, stopped, timeout)).groups()
        time.sleep(0.4)  # Allow the stock server to finish interpolating the stopped unit.
        result = server_position(server_log, server, f"UPSTREAM_SERVER_CONTROLS_STOP input={device}")
        settled = result.groups()
        for sample in range(6):
            time.sleep(0.3)
            later = server_position(
                server_log, server, f"UPSTREAM_SERVER_CONTROLS_IDLE input={device} sample={sample}"
            ).groups()
            if int(later[1]) > int(settled[1]):
                break
        if (client_unit != settled[0] or settled[0] != baseline[0] or settled[0] != later[0]
                or int(later[1]) <= int(settled[1])
                or float(settled[axis]) - float(baseline[axis]) < 0.05
                or abs(float(client_x) - float(settled[2])) > 0.2
                or abs(float(client_y) - float(settled[3])) > 0.2
                or any(abs(float(settled[i]) - float(later[i])) > 0.05 for i in (2, 3))):
            raise RuntimeError(f"{device} did not settle with continuing snapshots: "
                               f"client=({client_unit}, {client_x}, {client_y}), server={settled}, idle={later}")
        baseline = later
        if capture:
            capture(device, later)
        print(result.group(0), flush=True)
    server.stdin.write("runwave\n")
    server.stdin.flush()
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", timeout)


def capture_viewport(log, emulator, rpc, destination, position,
                     require_palette=False, require_icons=False, require_world_atlas=False,
                     require_building_layers=False, require_building_cracks=False,
                     world_size=(150, 250), spawn_pixels=None):
    unit, _, x, y = position
    # The renderer centers a 16 × 12 tile viewport in the loaded map.
    width, height = world_size
    left = max(0, min(width - 16, math.floor(float(x) / 8 + 0.5) - 8))
    bottom = max(0, min(height - 12, math.floor(float(y) / 8 + 0.5) - 6))
    frame = f"UPSTREAM_NETWORK_FRAME unit={unit} left={left} bottom={bottom} sprites=1"
    wait_for(log, emulator, rf"{frame}\b", 5)
    if require_world_atlas:
        # The backend reports the first split. The fixture wall is drawn before the core.
        split = r"2365,342\+64x64 fragments=2" if require_building_layers else r"1037,3190\+160x160 fragments=6"
        wait_for(log, emulator, rf"UPSTREAM_WORLD_ATLAS page=0 rect={split}\b", 5)
    # Default layout centers the 320px bottom screen at x=40 below the top screen.
    if destination.stem.endswith("-touch-catalog"):
        palette_pixels = {(50, 250): (45, 52, 64), (52, 293): (45, 52, 64)}
    elif destination.stem.endswith("-touch-config-menu"):
        palette_pixels = {(50, 250): (24, 27, 32), (52, 293): (45, 52, 64)}
    else:
        palette_pixels = {(70, 296): (24, 27, 32), (72, 296): (65, 74, 88)}
    cursor_pixels = {
        "touch-conveyor": ((284, 100), (250, 213, 107)),
        "touch-copper-wall": ((304, 100), (250, 213, 107)),
        "touch-removed": ((304, 100), (241, 117, 97)),
        "touch-router": ((324, 100), (250, 213, 107)),
    }
    points = dict(palette_pixels)
    for phase, (point, color) in cursor_pixels.items():
        if destination.stem.endswith(f"-{phase}"):
            points[point] = color
    left_sample = 288 if re.search(r"-touch-(sorter|config-[a-z]+)$", destination.stem) else 308
    interior = ((left_sample, 108), (left_sample + 2, 110), (left_sample + 4, 112))
    icon_points = ()
    if require_icons and destination.stem.endswith(("-touch-catalog", "-touch-config-menu")):
        icon_points = tuple((x, y) for x in (60, 64, 68, 72, 76) for y in (289, 294, 299))
    samples = (*points, *interior, *icon_points) if require_palette else ()
    if require_world_atlas:
        samples += tuple(
            (x + dx, y + dy)
            for x, y in CORE_PIXELS
            for dx in range(-CORE_PIXEL_TOLERANCE, CORE_PIXEL_TOLERANCE + 1)
            for dy in range(-CORE_PIXEL_TOLERANCE, CORE_PIXEL_TOLERANCE + 1)
        )
    if require_building_layers:
        samples += tuple(BUILDING_CRACK_PIXELS if require_building_cracks else BUILDING_LAYER_PIXELS)
    expected = points if require_palette else {}
    if spawn_pixels:
        expected = spawn_pixels
        samples += tuple(spawn_pixels)
    # Palette and menu input can be logged before the matching Citro2D frame is presented.
    deadline = time.monotonic() + 5
    while True:
        screenshot = rpc.screenshot(res_scale=1)
        pixels = az.inspect_png(screenshot, samples)
        if (all(pixels["samples"][point] == color for point, color in expected.items())
                or time.monotonic() >= deadline):
            break
        time.sleep(0.1)
    destination.write_bytes(screenshot)
    if pixels["width"] != 400 or pixels["height"] != 480 or pixels["top_distinct_colors"] < 8:
        raise RuntimeError(f"Expected a textured world viewport on the top screen: {pixels}")
    if spawn_pixels and any(pixels["samples"][point] != color for point, color in spawn_pixels.items()):
        raise RuntimeError(f"Stock sprite samples differ: {pixels['samples']}")
    if require_world_atlas and not world_atlas_pixels_match(pixels["samples"]):
        observed = {point: pixels["samples"].get(point) for point in CORE_PIXELS}
        raise RuntimeError(
            f"Paged core pixels differ from the original unsplit draw within "
            f"{CORE_PIXEL_TOLERANCE}px: {observed}")
    if require_building_cracks and any(
            abs(observed - expected) > 1
            for point, color in BUILDING_CRACK_PIXELS.items()
            for observed, expected in zip(pixels["samples"][point], color)):
        raise RuntimeError(f"Stock building crack pixels differ: {pixels['samples']}")
    if require_building_layers and not require_building_cracks and any(
            pixels["samples"][point] != color for point, color in BUILDING_LAYER_PIXELS.items()):
        raise RuntimeError(f"Stock building layer pixels differ: {pixels['samples']}")
    if require_palette:
        if any(pixels["samples"][point] != color for point, color in points.items()):
            raise RuntimeError(f"Expected palette and cursor pixels {points}, observed {pixels['samples']}")
        if icon_points and all(pixels["samples"][point] == (45, 52, 64) for point in icon_points):
            raise RuntimeError("The first touchscreen row is missing its original icon")
    print(frame, flush=True)
    if require_palette:
        return tuple(pixels["samples"][point] for point in interior)


def load_payload_mix_fixture(server_log, server):
    # Freeze the original conveyor interpolation at full and partial team glows.
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'const full = Vars.world.tile(75,28), partial = Vars.world.tile(86,24); '
        'full.setBlock(Blocks.payloadConveyor,team,0); '
        'partial.setBlock(Blocks.payloadConveyor,team,0); '
        'const phase = Vars.world.tile(86,28); phase.setBlock(Blocks.phaseWeaver,team,0); phase.build.warmup=1; '
        'full.build.progress=Blocks.payloadConveyor.moveTime*0.5; '
        'partial.build.progress=Blocks.payloadConveyor.moveTime*0.4352753; '
        'return "UPSTREAM_PAYLOAD_FIXTURE fractions=" + Math.round(full.build.fract()*100) '
        '+ "," + Math.round(partial.build.fract()*100); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_PAYLOAD_FIXTURE fractions=50,25\b", 5)


def verify_payload_mix(screenshot):
    # Opaque texels from both halves of the full glow, two 37.5% glow
    # texels, and a transparent overlay texel that must show the base.
    exact = {(71, 59): (255, 211, 127), (105, 76): (255, 211, 127),
             (305, 150): (74, 75, 83), (70, 296): (24, 27, 32),
             # These pixel centers lie exactly on a tile split in the conveyor base.
             (67, 62): (74, 75, 83), (287, 141): (74, 75, 83)}
    partial = {(322, 142): (164, 149, 128), (325, 156): (164, 149, 128)}
    colors = az.inspect_png(screenshot, (*exact, *partial))["samples"]
    if any(colors[point] != expected for point, expected in exact.items()) or any(
            abs(channel - target) > 1
            for point, expected in partial.items() for channel, target in zip(colors[point], expected)):
        raise RuntimeError(f"Original payload conveyor mix differs: {colors}")
    verify_building_effects(screenshot)


def load_carried_buildings_fixture(server_log, server):
    # Keep the stock payloads at the center and the first quarter of their respective belts.
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'const center = Vars.world.tile(75,28), incoming = Vars.world.tile(86,24); '
        'const Payload = Packages.mindustry.world.blocks.payloads.BuildPayload; '
        'center.setBlock(Blocks.payloadConveyor,team,0); '
        'incoming.setBlock(Blocks.payloadConveyor,team,0); '
        'center.build.progress=Blocks.payloadConveyor.moveTime*0.5; '
        'incoming.build.progress=Blocks.payloadConveyor.moveTime*0.4352753; '
        'center.build.handlePayload(center.build, new Payload(Blocks.copperWall,team)); '
        'incoming.build.handlePayload(incoming.build, new Payload(Blocks.copperWallLarge,team)); '
        'const extra = [[75,23,Blocks.mechanicalDrill],[86,29,Blocks.conveyor],[78,21,Blocks.payloadRouter]]; '
        'const names = extra.map(entry => { const tile = Vars.world.tile(entry[0],entry[1]); '
        'tile.setBlock(Blocks.payloadConveyor,team,0); '
        'tile.build.progress=Blocks.payloadConveyor.moveTime*0.5; '
        'tile.build.handlePayload(tile.build,new Payload(entry[2],team)); '
        'return tile.build.item.content().name + "=" + Math.round(tile.build.item.x()); }); '
        'return "UPSTREAM_CARRIED_FIXTURE " + center.build.item.content().name + "=" '
        '+ Math.round(center.build.item.x()) + " " + incoming.build.item.content().name + "=" '
        '+ Math.round(incoming.build.item.x()) + " " + names.join(" "); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_CARRIED_FIXTURE copper-wall=600 copper-wall-large=682 "
             r"mechanical-drill=600 conveyor=688 payload-router=624\b", 5)


def verify_carried_buildings(screenshot):
    # Original atlas texels for the centered small wall and the large wall offset six world units west.
    # Sample away from exact nearest-neighbor ties where adjacent texels have different colors.
    dark, light = (177, 135, 104), (200, 165, 143)
    expected = {(82, 63): dark, (85, 68): light, (91, 70): light, (95, 73): dark,
                (277, 133): dark, (280, 138): light, (286, 140): light,
                (292, 147): light, (304, 156): dark, (311, 164): dark}
    # Original opaque texels from the drill base and both upstream fullIcon regions.
    steel, slate, charcoal = (152, 154, 164), (110, 112, 128), (74, 75, 83)
    expected.update({(73, 154): steel, (75, 156): steel, (81, 160): charcoal, (104, 186): steel,
                     (303, 44): slate, (305, 46): slate, (311, 50): charcoal, (314, 53): slate,
                     (123, 184): slate, (125, 186): slate, (149, 210): charcoal, (165, 228): charcoal,
                     (174, 230): (148, 151, 162)})
    colors = az.inspect_png(screenshot, tuple(expected))["samples"]
    if colors != expected:
        mismatches = {point: (expected[point], colors.get(point)) for point in expected
                      if colors.get(point) != expected[point]}
        raise RuntimeError(f"Original carried buildings differ in color or position: {mismatches}")


def load_payload_handoff_fixture(server_log, server):
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'const Wall = Packages.mindustry.world.blocks.payloads.BuildPayload; '
        'const Unit = Packages.mindustry.world.blocks.payloads.UnitPayload; '
        'const dagger = UnitTypes.dagger.create(Vars.state.rules.waveTeam); dagger.baseRotation=0; '
        'const cargos = [[22,new Wall(Blocks.copperWall,team)],[29,new Unit(dagger)]]; '
        'const names = cargos.map(entry => { const y = entry[0]; '
        'const sender = Vars.world.tile(74,y), receiver = Vars.world.tile(77,y); '
        'sender.setBlock(Blocks.payloadConveyor,team,0); '
        'receiver.setBlock(Blocks.payloadConveyor,team,0); '
        'Vars.world.tile(79,y).setBlock(Blocks.titaniumWall,team,0); '
        'sender.build.progress=Blocks.payloadConveyor.moveTime*0.5; '
        'sender.build.handlePayload(sender.build,entry[1]); '
        'if (receiver.build.item != null) throw "Receiving belt must start empty"; '
        'return sender.build.item.content().name; }); '
        'return "UPSTREAM_HANDOFF_FIXTURE " + names.join(","); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_HANDOFF_FIXTURE copper-wall,dagger\b", 5)


def load_unit_layers_fixture(server_log, server):
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam, enemy = Vars.state.rules.waveTeam; '
        'const gamma = UnitTypes.gamma.spawn(team,600,224,90); '
        'const dagger = UnitTypes.dagger.spawn(enemy,688,184,90); '
        'dagger.baseRotation=90; '
        'const Payload = Packages.mindustry.world.blocks.payloads.UnitPayload; '
        'const loadedGamma = UnitTypes.gamma.create(team), loadedDagger = UnitTypes.dagger.create(enemy); '
        'loadedDagger.baseRotation=90; '
        'const belts = [[75,23,loadedGamma,0.5],[86,29,loadedDagger,0.4352753]]; '
        'const centers = belts.map(entry => { const tile = Vars.world.tile(entry[0],entry[1]); '
        'tile.setBlock(Blocks.payloadConveyor,team,1); '
        'tile.build.progress=Blocks.payloadConveyor.moveTime*entry[3]; '
        'tile.build.handlePayload(tile.build,new Payload(entry[2])); '
        'return Math.round(tile.build.item.y()); }); '
        'return "UPSTREAM_UNIT_FIXTURE gamma=" + gamma.team.id + "," + gamma.rotation '
        '+ " dagger=" + dagger.team.id + "," + dagger.baseRotation '
        '+ " cargoY=" + centers.join(","); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server,
             r"UPSTREAM_UNIT_FIXTURE gamma=1,90(?:\.0)? dagger=2,90(?:\.0)? cargoY=184,226\b", 5)


def verify_unit_layers(screenshot):
    # Opaque white cell texels take the team tint. These pixels sit outside the core.
    sharded, crux = (255, 211, 127), (242, 85, 85)
    expected = {(89, 76): sharded, (90, 76): sharded, (90, 79): sharded,
                (309, 174): crux, (310, 174): crux, (310, 176): crux}
    # The engine is visible beyond the Gamma's body. Both mirrored Dagger mounts have orange tips.
    expected.update({(90, 89): sharded, (300, 165): (255, 166, 100), (319, 165): (255, 166, 100)})
    # The carried Gamma is centered, and the carried Dagger is six world units south of its belt center.
    expected.update({(89, 176): sharded, (90, 176): sharded, (90, 179): sharded,
                     (309, 69): crux, (310, 69): crux, (310, 71): crux,
                     (300, 60): (255, 166, 100), (319, 60): (255, 166, 100)})
    colors = az.inspect_png(screenshot, tuple(expected))["samples"]
    if colors != expected:
        raise RuntimeError(f"Stock Gamma and Dagger cell, engine or weapon pixels differ: {colors}")


def load_unit_roster_fixture(server_log, server):
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam, enemy = Vars.state.rules.waveTeam; '
        'const roster = [[UnitTypes.alpha,team,680,232],[UnitTypes.beta,team,680,200],'
        '[UnitTypes.flare,team,616,168],[UnitTypes.fortress,enemy,688,176],'
        '[UnitTypes.pulsar,team,600,168],[UnitTypes.vela,team,648,168],[UnitTypes.quasar,enemy,600,232]]; '
        'const names = roster.map(entry => { const u = entry[0].spawn(entry[1],entry[2],entry[3],90); '
        'if (typeof u.baseRotation === "number") u.baseRotation=90; '
        'if (u.type === UnitTypes.quasar) u.shield=100; '
        'return u.type.name + ":" + u.shield; }); '
        'return "UPSTREAM_UNIT_ROSTER_FIXTURE " + names.join(","); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_UNIT_ROSTER_FIXTURE alpha:0,beta:0,flare:0,"
             r"fortress:0,pulsar:0,vela:0,quasar:100\b", 5)


def verify_unit_roster(screenshot):
    # White texels from the stock unit cell regions take their full-health team tint.
    sharded, crux = (255, 211, 127), (242, 85, 85)
    expected = {(289, 53): sharded, (290, 54): sharded, (289, 137): sharded, (290, 138): sharded,
                (129, 214): sharded, (130, 215): sharded, (89, 216): sharded, (90, 217): sharded,
                (209, 217): sharded, (210, 218): sharded, (306, 197): crux, (312, 197): crux}
    shield_tip = tuple((x, y) for x in range(233, 241) for y in range(45, 57))
    colors = az.inspect_png(screenshot, (*expected, *shield_tip))["samples"]
    mismatch = {point: (rgb, colors[point]) for point, rgb in expected.items() if colors[point] != rgb}
    shield_pixels = sum(colors[point] == crux for point in shield_tip)
    if mismatch or shield_pixels < 10:
        raise RuntimeError(f"Original unit cells differ {mismatch}, Quasar shield pixels {shield_pixels}")


def load_naval_wake_fixture(server_log, server):
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'for (let x=83; x<=89; x++) for (let y=20; y<=29; y++) { '
        'var tile = Vars.world.tile(x,y); if (tile.build != null) continue; '
        'tile.setFloor(Blocks.water); tile.setOverlay(Blocks.air); tile.setBlock(Blocks.air); } '
        'const risso = UnitTypes.risso.spawn(team,664,176,0); '
        'const retusa = UnitTypes.retusa.spawn(team,696,224,90); '
        'if (!risso.canPassOn() || !retusa.canPassOn()) throw "Expected a navigable water fixture"; '
        'return "UPSTREAM_NAVAL_FIXTURE id=" + risso.id; })()\n'
    )
    server.stdin.flush()
    marker = re.compile(r"UPSTREAM_NAVAL_FIXTURE id=(\d+)\b")
    match = marker.search(wait_for(server_log, server, marker, 5))
    return int(match[1])


def verify_naval_wake(server_log, server, rpc, output, model, before, unit_id):
    # The stock shallow-water palette entry, multiplied by 1.5 in WaterMoveComp.
    wake = (106, 126, 214)
    risso = tuple((x, y) for x in range(241, 261) for y in range(179, 202))
    retusa = tuple((x, y) for x in range(320, 340) for y in range(61, 82))
    port = tuple((x, y) for x in range(255, 316) for y in range(177, 183))
    starboard = tuple((x, y) for x in range(255, 316) for y in range(197, 203))
    points = (*risso, *retusa, *port, *starboard)
    baseline = az.inspect_png(before.read_bytes(), points)["samples"]
    team_color = (255, 211, 127)
    if any(sum(baseline[point] == team_color for point in cell) < 5 for cell in (risso, retusa)):
        raise RuntimeError("Expected original Risso and Retusa team cells before movement")

    # Supply a controlled sequence of authoritative positions over the normal server snapshots.
    server.stdin.write("pause off\n")
    server.stdin.flush()
    for x in range(666, 698, 2):
        server.stdin.write(
            f'js (() => {{ const ship = Groups.unit.getByID({unit_id}); ship.set({x},176); '
            'return "UPSTREAM_NAVAL_STEP " + ship.x; })()\n')
        server.stdin.flush()
        wait_for(server_log, server, rf"UPSTREAM_NAVAL_STEP {x}\b", 5)
        # The wake has a short history. Capture while the boat is moving.
        time.sleep(0.45)
        frame = rpc.screenshot(res_scale=1)
        samples = az.inspect_png(frame, (*port, *starboard))["samples"]
        counts = tuple(sum(samples[p] == wake and baseline[p] != wake for p in band) for band in (port, starboard))
        if min(counts) >= 8:
            (output / f"{model}-naval-wake.png").write_bytes(frame)
            print(f"{model}: original port and starboard wake pixels {counts}.", flush=True)
            return
    (output / f"{model}-naval-wake.png").write_bytes(frame)
    raise RuntimeError(f"Expected both stock wake trails after moving the Risso, got {counts} for color {wake}")


def load_tank_treads_fixture(server_log, server):
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'for (let x=72; x<=77; x++) for (let y=26; y<=31; y++) { '
        'var tile=Vars.world.tile(x,y); if (tile.build != null) continue; '
        'tile.setFloor(Blocks.ice); tile.setOverlay(Blocks.air); tile.setBlock(Blocks.air); } '
        'const locus=UnitTypes.locus.spawn(team,600,224,90); '
        'UnitTypes.stell.spawn(team,680,192,90); '
        'if (!locus.canPassOn() || !locus.type.supportsEnv(Vars.state.rules.env)) throw "Invalid tank fixture"; '
        'return "UPSTREAM_TANK_FIXTURE locus=" + locus.id; })()\n'
    )
    server.stdin.flush()
    marker = re.compile(r"UPSTREAM_TANK_FIXTURE locus=(\d+)\b")
    return int(marker.search(wait_for(server_log, server, marker, 5))[1])


def verify_tank_treads(server_log, server, rpc, output, model, before, unit_id):
    # Stock UnitType.draw composites square-shadow above the tread strip. These
    # samples avoid exact shadow texel ties.
    light, dark = (55, 58, 77), (24, 25, 32)
    rows = {47: (light, 158), 51: (dark, 198), 52: (dark, 198), 53: (dark, 210),
            86: (dark, 210), 87: (dark, 198), 92: (light, 158)}

    def shade(rgb, alpha):
        return tuple(round(channel * (1 - 0.4 * alpha / 255)) for channel in rgb)

    left = tuple((76, y) for y in rows)
    right = tuple((103, y) for y in rows)
    cells = tuple((x, y) for x in range(73, 107) for y in range(62, 78))
    stell = ((279, 145), (300, 145))
    points = (*left, *right, *cells, *stell)
    baseline = az.inspect_png(before.read_bytes(), points)["samples"]
    sharded = (255, 211, 127)
    hull = {point for point in cells if baseline[point] == sharded}
    if len(hull) < 10 or any(baseline[point] != sharded for point in stell):
        raise RuntimeError("Expected the original Locus and Stell team cells")
    if any(baseline[point] != shade(*rows[point[1]]) for point in left + right):
        raise RuntimeError(f"Unexpected initial Locus tread frame: {[baseline[p] for p in left + right]}")

    # Compare the same tread coordinates relative to the hull while the Locus moves north.
    offsets = range(-20, -1)
    moving_points = tuple({(x, y + offset) for x, y in (*left, *right, *cells) for offset in offsets})

    def moved_treads(frame):
        colors = az.inspect_png(frame, moving_points)["samples"]
        for offset in offsets:
            current_hull = {(x, y) for x, y in cells if colors[x, y + offset] == sharded}
            if current_hull != hull:
                continue
            changed = tuple(sum(colors[x, y + offset] != baseline[x, y] for x, y in side)
                            for side in (left, right))
            original_palette = all(colors[x, y + offset] in (shade(dark, rows[y][1]), shade(light, rows[y][1]))
                                   for x, y in left + right)
            if min(changed) >= 2 and original_palette:
                return offset, changed
        return None

    server.stdin.write("pause off\n")
    server.stdin.flush()
    for y in range(225, 233):
        server.stdin.write(
            f'js (() => {{ const tank=Groups.unit.getByID({unit_id}); tank.set(600,{y}); '
            'return "UPSTREAM_TANK_STEP " + tank.y; })()\n')
        server.stdin.flush()
        wait_for(server_log, server, rf"UPSTREAM_TANK_STEP {y}\b", 5)
        time.sleep(0.65)
        frame = rpc.screenshot(res_scale=1)
        result = moved_treads(frame)
        if result:
            offset, changed = result
            (output / f"{model}-tank-treads.png").write_bytes(frame)
            print(f"{model}: Locus moved {-offset} pixels north with {changed} changed tread pixels.", flush=True)
            return
    (output / f"{model}-tank-treads.png").write_bytes(frame)
    raise RuntimeError("Expected a new stock tread frame on both sides of the moving Locus")


def payload_handoff_pixels(screenshot):
    # The receiver sample starts beyond any position the sender can draw during travel.
    wall_rows = (184, 187, 190, 193, 196)
    dagger_rows = range(45, 55)
    wall_sources = tuple((x, y) for y in wall_rows for x in range(60, 81))
    wall_destinations = tuple((x, y) for y in wall_rows for x in range(111, 170))
    unit_sources = tuple((x, y) for y in dagger_rows for x in range(58, 83))
    unit_destinations = tuple((x, y) for y in dagger_rows for x in range(115, 169))
    colors = az.inspect_png(screenshot, (*wall_sources, *wall_destinations, *unit_sources, *unit_destinations))["samples"]
    copper = {(177, 135, 104), (200, 165, 143)}
    crux = (242, 85, 85)
    return (sum(colors[p] in copper for p in wall_sources), sum(colors[p] in copper for p in wall_destinations),
            sum(colors[p] == crux for p in unit_sources), sum(colors[p] == crux for p in unit_destinations))


def verify_payload_handoff(server_log, server, rpc, output, model, before):
    wall_src, wall_dst, unit_src, unit_dst = payload_handoff_pixels(before.read_bytes())
    if wall_src < 40 or wall_dst != 0 or unit_src < 5 or unit_dst != 0:
        raise RuntimeError(f"Expected wall and Dagger on their first belts: {wall_src}, {wall_dst}, {unit_src}, {unit_dst}")

    # Let upstream simulation move the wall. Freeze the receiver at a known display position
    # beyond the sender's reach, then await the server's normal periodic block snapshot.
    server.stdin.write("pause off\n")
    server.stdin.flush()
    time.sleep(2.5)
    server.stdin.write(
        'pause on\n'
        'js (() => { const names = [22,29].map(y => { '
        'const sender = Vars.world.tile(74,y).build, receiver = Vars.world.tile(77,y).build; '
        'if (sender.item != null || receiver.item == null) throw "Stock belts did not transfer cargo"; '
        'receiver.progress=Blocks.payloadConveyor.moveTime*0.5; '
        'return receiver.item.content().name; }); '
        'return "UPSTREAM_HANDOFF_COMPLETE " + names.join(","); })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_HANDOFF_COMPLETE copper-wall,dagger\b", 8)

    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        frame = rpc.screenshot(res_scale=1)
        wall_src, wall_dst, unit_src, unit_dst = payload_handoff_pixels(frame)
        if wall_src == 0 and wall_dst >= 40 and unit_src == 0 and unit_dst >= 5:
            (output / f"{model}-payload-after.png").write_bytes(frame)
            print(f"{model}: stock BlockSnapshot moved the wall and Dagger to their receiving belts.", flush=True)
            return
        time.sleep(0.2)
    (output / f"{model}-payload-after.png").write_bytes(frame)
    raise RuntimeError(f"Expected wall and Dagger on receiving belts after BlockSnapshot: "
                       f"{wall_src}, {wall_dst}, {unit_src}, {unit_dst}")


def load_building_effects_fixture(server_log, server):
    # Freeze the stock simulation at known serialized display states.
    server.stdin.write(
        'pause on\n'
        'js (() => { const team = Vars.state.rules.defaultTeam; '
        'const phase = Vars.world.tile(86,28), overdrive = Vars.world.tile(74,28); '
        'phase.setBlock(Blocks.phaseWeaver,team,0); '
        'overdrive.setBlock(Blocks.overdriveProjector,team,0); '
        'phase.build.warmup=1; overdrive.build.heat=1; '
        'return "UPSTREAM_EFFECTS_FIXTURE weave=" + phase.build.warmup + " overdrive=" + overdrive.build.heat; })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_EFFECTS_FIXTURE weave=1(?:\.0)? overdrive=1(?:\.0)?\b", 5)


def verify_building_effects(screenshot):
    # The original Phase Weaver shell is transparent over its central Lines quad.
    points = ((319, 53), (320, 57), (320, 63), (319, 66))
    colors = az.inspect_png(screenshot, points)["samples"]
    if any(colors[point] != (255, 211, 127) for point in points):
        raise RuntimeError(f"Original Phase Weaver line is missing or miscolored: {colors}")


def load_conduit_fixture(server_log, server):
    # Keep both ends full so the stock server retains water in the linked conduits.
    pipes = ((83, 22, 0), (84, 22, 0), (85, 22, 0), (86, 22, 1), (86, 23, 1),
             (86, 24, 0), (87, 24, 0), (74, 30, 3), (74, 29, 0), (75, 29, 0), (76, 29, 0))
    outlets = ((88, 24), (77, 29))
    commands = [f"Vars.world.tile({x},{y}).setBlock(Blocks.liquidRouter,team,0);" for x, y in outlets]
    elbows = {(86, 24), (74, 29)}
    for x, y, rotation in pipes:
        # The plated elbow crosses an atlas tile boundary along the reflected axis.
        block = "platedConduit" if (x, y) in elbows else "conduit"
        commands.append(f"Vars.world.tile({x},{y}).setBlock(Blocks.{block},team,{rotation});")
    for x, y in (*outlets, *((x, y) for x, y, _ in pipes)):
        commands.append(f"{{ const b=Vars.world.tile({x},{y}).build;"
                        "b.liquids.add(Liquids.water,b.block.liquidCapacity); }")
    server.stdin.write(
        'js (() => { const team = Vars.state.rules.defaultTeam; ' + ' '.join(commands) +
        ' const a=Vars.world.tile(86,24).build, b=Vars.world.tile(74,29).build; '
        'return "UPSTREAM_CONDUIT_FIXTURE corners=" + a.blendbits + "," + b.blendbits '
        '+ " mirrored=" + (a.yscl !== b.yscl) + " liquid=" + a.liquids.current().name '
        '+ " scales=" + a.yscl + "," + b.yscl; })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_CONDUIT_FIXTURE corners=1,1 mirrored=true liquid=water scales=-1,1\b", 5)


def verify_conduit_frames(log, rpc, output, model):
    # Straight pipe followed by the two opposite elbows at the fixed spawn camera.
    centers = ((270, 190), (310, 150), (70, 50))
    samples = [tuple((x, y) for y in range(cy - 4, cy + 5) for x in range(cx - 4, cx + 5))
               for cx, cy in centers]
    # Original opaque plated-conduit-top-1 texels from both backing tiles on each elbow.
    shells = {(304, 141): (110, 112, 128), (301, 155): (100, 94, 155),
              (61, 45): (100, 94, 155), (65, 56): (117, 120, 135)}
    points = (*shells, *(point for patch in samples for point in patch))
    wet_frames = [set() for _ in centers]
    # The synchronized atlas submissions can present fewer frames per second.
    # Wait for the stock fill interpolation and two different wet frames.
    for frame in range(30):
        time.sleep(0.45)
        trace = log.read_text(encoding="utf-8", errors="replace")
        if "UPSTREAM_LIVE_NET_ERROR" in trace:
            raise RuntimeError(f"Live conduit renderer failed: {trace[-4500:]}")
        screenshot = rpc.screenshot(res_scale=1)
        (output / f"{model}-liquids-{frame}.png").write_bytes(screenshot)
        rgb = az.inspect_png(screenshot, points)["samples"]
        if any(rgb[point] != color for point, color in shells.items()):
            raise RuntimeError(f"Plated conduit shell pixels differ: {[(point, rgb[point]) for point in shells]}")
        for patch, seen in zip(samples, wet_frames):
            colors = tuple(rgb[point] for point in patch)
            if sum(r < 130 and b - r > 35 for r, _, b in colors) >= 4:
                seen.add(colors)
        if all(len(seen) >= 2 for seen in wet_frames):
            print(f"{model}: straight conduit and both stock elbows displayed animated water.", flush=True)
            return
    missing = [center for center, seen in zip(centers, wet_frames) if len(seen) < 2]
    raise RuntimeError(f"Expected animated stock water in conduits at {missing}")


def run_model(args, model, config, original_config, log, output):
    stationary = (args.world_atlas_recycle or args.building_layers or args.liquids
                  or args.building_effects or args.payload_mix or args.carried_buildings
                  or args.payload_handoff or args.unit_layers or args.unit_roster or args.naval_wake
                  or args.tank_treads or args.map_spawn)
    map_name = args.map_spawn or "Glacier"
    overrides = {
        "Miscellaneous": {"log_filter": "*:Info Debug.Emulated:Debug Service.SOC:Debug",
                          "check_for_update_on_start": "false"},
        "Debugging": {"enable_rpc_server": "true", "instant_debug_log": "true"},
        "UI": {"firstStart": "false", "confirmClose": "false", "pause_when_in_background": "false"},
        "System": {"is_new_3ds": "true" if model == "new3ds" else "false"},
        "Renderer": {"graphics_api": "2"},
        "Data Storage": {"use_virtual_sd": "true"},
    }
    if args.touch_build or args.touch_config or args.erekir_build or args.mining:
        # The synthetic bursts intentionally offer one sample type to all devices.
        overrides["Miscellaneous"]["log_filter"] += " Movie:Critical"
    if args.terrain_atlas:
        overrides["Layout"] = {"layout_option": "0", "swap_screen": "false",
                               "upright_screen": "false", "render_3d": "0", "screen_gap": "0"}
    config.write_text(az.patch_qt_config_text(original_config, overrides), encoding="utf-8")
    log.write_bytes(b"")
    server_log = output / f"{model}-server.log"
    with tempfile.TemporaryDirectory(prefix="min3dstry-live-net-") as work, server_log.open("wb") as terminal:
        if args.erekir_sector or args.mining == "floor":
            # The stock server lists default multiplayer maps only. Import the release's map bytes unchanged.
            maps = Path(work) / "config" / "maps"
            maps.mkdir(parents=True)
            sector = f"erekir/{args.erekir_sector}" if args.erekir_sector else "serpulo/groundZero"
            filename = sector.rsplit("/", 1)[1] + ".msav"
            with zipfile.ZipFile(args.server) as release:
                (maps / filename).write_bytes(release.read(f"maps/{sector}.msav"))
        server = subprocess.Popen(
            [args.java, "-jar", str(args.server)], cwd=work, stdin=subprocess.PIPE,
            stdout=terminal, stderr=subprocess.STDOUT, text=True,
        )
        try:
            rules = "survival" if args.map_spawn else "attack"
            server.stdin.write(f"config port {args.port}\nhost {map_name.replace(' ', '_')} {rules}\n")
            server.stdin.flush()
            wait_for(server_log, server, f"Opened a server on port {args.port}\\.", 25)
            world_size = (150, 250)
            if args.erekir_build:
                server.stdin.write(
                    'js (() => { const core = Vars.state.rules.defaultTeam.core(); '
                    'if (Vars.world.tile(280,107).block() !== Blocks.air || '
                    'Vars.world.tile(280,106).block() !== Blocks.air) throw "Expected open fixture tiles"; '
                    'core.items.set(Items.beryllium, 16); '
                    'return "UPSTREAM_EREKIR_FIXTURE beryllium=" + core.items.get(Items.beryllium); })()\n')
                server.stdin.flush()
                wait_for(server_log, server, r"UPSTREAM_EREKIR_FIXTURE beryllium=16\b", 5)
            if args.mining:
                mine_item = "beryllium" if args.mining == "wall" else "copper"
                server.stdin.write(
                    f'js "UPSTREAM_MINING_START {mine_item}=" + '
                    f'Vars.state.rules.defaultTeam.core().items.get(Items.{mine_item})\n')
                server.stdin.flush()
                wait_for(server_log, server, rf"UPSTREAM_MINING_START {mine_item}=0\b", 5)
            if args.verify_objectives:
                server.stdin.write(
                    'js (() => { var all=Vars.state.rules.objectives.all, first=all.get(0), next=all.get(1); '
                    'return "UPSTREAM_SERVER_ONSET_START item=" + first.item.name + " amount=" + first.amount + '
                    '" eligible=" + first.qualified() + " next=" + next.qualified(); })()\n'
                )
                server.stdin.flush()
                wait_for(server_log, server,
                         r"UPSTREAM_SERVER_ONSET_START item=beryllium amount=60 eligible=true next=false\b", 5)
            if args.building or args.touch_build:
                # Give the pixel comparison a fixed patch of stock ice. Glacier's filters can
                # place decorations on or alongside the test cells.
                server.stdin.write('js (() => { for (let x = 85; x <= 87; x++) '
                                   'for (let y = 25; y <= 27; y++) { var tile = Vars.world.tile(x, y); '
                                   'if (tile.build != null) throw "Unexpected fixture building"; '
                                   'tile.setFloor(Blocks.ice); tile.setOverlay(Blocks.air); '
                                   'tile.setBlock(Blocks.air); } '
                                   'return "UPSTREAM_TOUCH_FIXTURE clear=85,86,87"; })()\n')
                server.stdin.flush()
                wait_for(server_log, server, r"UPSTREAM_TOUCH_FIXTURE clear=85,86,87\b", 5)
            if args.touch_config:
                server.stdin.write('js (() => { const items = Vars.state.rules.defaultTeam.core().items; '
                                   'if (items.get(Items.lead) < 2) items.add(Items.lead, 2); '
                                   'return "UPSTREAM_CONFIG_FIXTURE lead=" + items.get(Items.lead); })()\n')
                server.stdin.flush()
                wait_for(server_log, server, r"UPSTREAM_CONFIG_FIXTURE lead=\d+", 5)
            if args.payload_mix:
                load_payload_mix_fixture(server_log, server)
            if args.carried_buildings:
                load_carried_buildings_fixture(server_log, server)
            if args.payload_handoff:
                load_payload_handoff_fixture(server_log, server)
            if args.unit_layers:
                load_unit_layers_fixture(server_log, server)
            if args.unit_roster:
                load_unit_roster_fixture(server_log, server)
            if args.naval_wake:
                naval_fixture = load_naval_wake_fixture(server_log, server)
            if args.tank_treads:
                tank_fixture = load_tank_treads_fixture(server_log, server)
            if args.building_effects:
                load_building_effects_fixture(server_log, server)
            if args.liquids:
                load_conduit_fixture(server_log, server)
            if args.building_layers:
                damage = ("wall.build.health = wall.build.maxHealth * 0.5; "
                          "drill.build.health = drill.build.maxHealth * 0.5; ") if args.building_cracks else ""
                server.stdin.write('js (() => { const team = Vars.state.rules.defaultTeam; '
                                   'const wall = Vars.world.tile(72, 27), drill = Vars.world.tile(86, 28); '
                                   'wall.setBlock(Blocks.copperWallLarge, team, 0); '
                                   'drill.setBlock(Blocks.mechanicalDrill, team, 0); ' + damage +
                                   'return "UPSTREAM_BUILDING_LAYER_FIXTURE " + wall.block().name + " " '
                                   '+ Vars.world.tile(73, 27).build.pos() + " " + wall.build.pos() '
                                   '+ " " + drill.block().name + " " + wall.build.healthf() '
                                   '+ " " + drill.build.healthf(); })()\n')
                server.stdin.flush()
                fixture = r"UPSTREAM_BUILDING_LAYER_FIXTURE copper-wall-large (\d+) \1 mechanical-drill"
                health = r"0\.5" if args.building_cracks else r"1(?:\.0)?"
                wait_for(server_log, server, rf"{fixture} {health} {health}\b", 5)
            if args.desktop_name:
                print(f"{model}: Join 127.0.0.1:{args.port} from the stock PC client as "
                      f"{args.desktop_name!r}.", flush=True)
                wait_for(server_log, server, rf"{re.escape(args.desktop_name)} has connected", args.timeout)
            command = [str(args.azahar)]
            if args.controls_replay:
                replay = Path(work) / "controls.ctm"
                first_move = 42 if args.terrain_atlas else 22
                if args.building and args.desktop_name:
                    first_move = 120  # Allow time to inspect and place a block in the desktop client.
                if args.touch_build:
                    first_move = 101
                if args.touch_config:
                    first_move = 92
                write_controls_replay(replay, first_move=first_move, building=args.building,
                                      touch_build=args.touch_build, touch_config=args.touch_config,
                                      erekir_build=args.erekir_build, mining=args.mining, model=model)
                command += ["-p", str(replay)]
            command.append(str(args.rom))
            emulator = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                if args.locale:
                    names = {"en": ("Router", "Copper"), "de": ("Verteiler", "Kupfer")}
                    labels = names.get(args.locale)
                    words = " router=" + re.escape(labels[0]) + " copper=" + re.escape(labels[1]) if labels else ""
                    pattern = rf"UPSTREAM_INTERFACE_LOCALE system=\d+ bundle={re.escape(args.locale)}\b{words}"
                    localized = wait_for(log, emulator, pattern, args.timeout)
                    print(re.search(pattern, localized).group(0), flush=True)
                if args.controls:
                    def capture(phase, position):
                        if args.world_atlas_recycle and phase == "spawn":
                            wait_for(log, emulator, r"UPSTREAM_WORLD_ATLAS_RECYCLE\b", 5)
                        return capture_viewport(log, emulator, rpc, output / f"{model}-{phase}.png", position,
                                                require_palette=args.touch_build or args.touch_config,
                                                require_icons=args.menu_icons,
                                                require_world_atlas=args.world_atlas and phase in ("spawn", "building-layers", "building-cracks"),
                                                require_building_layers=args.building_layers,
                                                require_building_cracks=args.building_cracks,
                                                world_size=world_size,
                                                spawn_pixels=(MAP_SPAWN_PIXELS.get(args.map_spawn) if phase == "spawn"
                                                              else EREKIR_BUILD_PIXELS.get(phase)))

                    if stationary:
                        wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", args.timeout)
                        spawn = server_position(server_log, server, "UPSTREAM_SERVER_CONTROLS_READY").groups()
                        if args.map_spawn:
                            map_spawn = inspect_map_spawn(server_log, server)
                            world_size = tuple(map(int, map_spawn[:2]))
                            verify_map_spawn(log, emulator, map_name, map_spawn, spawn)
                        if args.require_relocation_spill:
                            pattern = r"UPSTREAM_GC_RELOCATION_SPILL bytes=[1-9]\d*\b"
                            trace = wait_for(log, emulator, pattern, 5)
                            for spill in re.findall(pattern, trace):
                                print(f"{model}: {spill}", flush=True)
                        phase = "spawn"
                        if args.building_effects:
                            phase = "building-effects"
                        elif args.payload_mix:
                            phase = "payload-mix"
                        elif args.carried_buildings:
                            phase = "carried-buildings"
                        elif args.payload_handoff:
                            phase = "payload-before"
                        elif args.unit_layers:
                            phase = "unit-layers"
                        elif args.unit_roster:
                            phase = "unit-roster"
                        elif args.naval_wake:
                            phase = "naval-before"
                        elif args.tank_treads:
                            phase = "tank-before"
                        if args.building_layers:
                            phase = "building-cracks" if args.building_cracks else "building-layers"
                        capture(phase, spawn)
                        if args.erekir_build:
                            verify_erekir_construction(log, emulator, server_log, server, args.timeout, capture, spawn)
                        if args.mining:
                            verify_mining(log, emulator, server_log, server, args.timeout, capture,
                                          args.mining, mine_item, args.verify_objectives)
                        if args.building_effects:
                            verify_building_effects((output / f"{model}-{phase}.png").read_bytes())
                        if args.payload_mix:
                            verify_payload_mix((output / f"{model}-{phase}.png").read_bytes())
                        if args.carried_buildings:
                            verify_carried_buildings((output / f"{model}-{phase}.png").read_bytes())
                        if args.payload_handoff:
                            verify_payload_handoff(server_log, server, rpc, output, model,
                                                   output / f"{model}-{phase}.png")
                        if args.unit_layers:
                            verify_unit_layers((output / f"{model}-{phase}.png").read_bytes())
                        if args.unit_roster:
                            verify_unit_roster((output / f"{model}-{phase}.png").read_bytes())
                        if args.naval_wake:
                            verify_naval_wake(server_log, server, rpc, output, model,
                                              output / f"{model}-{phase}.png", naval_fixture)
                        if args.tank_treads:
                            verify_tank_treads(server_log, server, rpc, output, model,
                                               output / f"{model}-{phase}.png", tank_fixture)
                        if args.liquids:
                            verify_conduit_frames(log, rpc, output, model)
                        if not args.payload_handoff:
                            print(f"Verified {map_name} capture.", flush=True)
                        emu_log = log.read_text(encoding="utf-8", errors="replace")
                        if "UPSTREAM_LIVE_NET_ERROR" in emu_log:
                            raise RuntimeError("The client failed after rendering the atlas stress frame")
                    else:
                        verify_controls(log, emulator, server_log, server, args.timeout,
                                        capture if args.terrain_atlas else None, args.building, args.desktop_name,
                                        args.touch_build, args.touch_config, args.menu_icons)
                        (output / f"{model}.png").write_bytes(rpc.screenshot())
                        print("Wave 2 applied. Press the emulated START button to leave.", flush=True)
                        emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_CONTROLS_LEFT", args.timeout)
                else:
                    for join in (1, 2):
                        wait_for(log, emulator, rf"UPSTREAM_LIVE_NET_STATE join={join}\b", args.timeout)
                        if args.movement:
                            verify_movement(log, emulator, server_log, server, join, args.timeout)
                        if join == 2:
                            time.sleep(13)  # Exceed ArcNet's 12-second TCP timeout.
                        server.stdin.write("runwave\n")
                        server.stdin.flush()
                        wait_for(log, emulator, rf"UPSTREAM_LIVE_NET_SYNC join={join}\b", 25)
                    emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_NET_PASS", args.timeout)
                    (output / f"{model}.png").write_bytes(rpc.screenshot())
            finally:
                rpc.close()
                stop(emulator)
                (output / f"{model}-emulator.log").write_bytes(log.read_bytes())
            if not args.controls:
                validate(emu_log)
            disconnected = rf"{PLAYER} has disconnected"
            if not args.controls:
                disconnected += rf"[\s\S]*{PLAYER} has disconnected"
            before_shutdown = wait_for(server_log, server, disconnected, 5)
            if args.desktop_name:
                desktop_join = before_shutdown.find(f"{args.desktop_name} has connected")
                arm_join = before_shutdown.find(f"{PLAYER} has connected")
                if not 0 <= desktop_join < arm_join or f"{args.desktop_name} has disconnected" in before_shutdown:
                    raise RuntimeError(f"{model} PC client did not stay connected through the ARM11 session")
                print(f"{model}: the PC client stayed connected through the ARM11 session", flush=True)
            server.stdin.write("exit\n")
            server.stdin.flush()
            server.wait(timeout=10)
            upstream = server_log.read_text(encoding="utf-8", errors="replace")
            departures = [line for line in upstream.splitlines() if f"{PLAYER} has disconnected" in line]
            # NetServer.sync can observe the closed transport before the disconnect event runs.
            # The stationary render check ends by terminating the emulator process.
            departure = r"\((closed|disappeared|error)\)" if stationary else r"\((closed|disappeared)\)"
            expected_departures = all(re.search(departure, line) for line in departures)
            if stationary and any("(error)" in line for line in departures):
                print(f"{model}: stationary verification ended by emulator kill; "
                      "accepted the server's (error) departure", flush=True)
            expected_joins = 1 if args.controls else 2
            if ((not args.controls and "clientOutdated" not in upstream)
                    or upstream.count(f"{PLAYER} has connected") != expected_joins
                    or len(departures) != expected_joins or not expected_departures):
                raise RuntimeError(f"{model} server did not report the expected completed sessions")
        finally:
            stop(server)
    for line in emu_log.splitlines():
        if (record := re.search(r"UPSTREAM_LIVE_(?:NET|CONTROLS)_.*", line)):
            print(f"{model}: {record.group(0)}", flush=True)


def validate(log):
    world = r"UPSTREAM_LIVE_NET_WORLD join=(\d+) map=Glacier bytes=(\d+) team=(\d+) fingerprint=([a-f0-9]{16})"
    state = r"UPSTREAM_LIVE_NET_STATE join=(\d+) wave=(\d+) time=(\d+) enemies=(\d+) coreBytes=(\d+)"
    worlds = re.findall(world, log)
    states = re.findall(state, log)
    synced = re.findall(r"UPSTREAM_LIVE_NET_SYNC join=(\d+) fromWave=(\d+) wave=(\d+) unit=(\S+) id=(\d+)", log)
    if ("UPSTREAM_LIVE_NET_REJECT build=159 packet=KickCallPacket2" not in log
            or "UPSTREAM_LIVE_NET_KEEPALIVE join=2 transport=tcp" not in log
            or [event[0] for event in worlds] != ["1", "2"]
            or [event[0] for event in states] != ["1", "2"]
            or [event[0] for event in synced] != ["1", "2"]
            or re.findall(r"UPSTREAM_LIVE_NET_DISCONNECTED join=(\d+)", log) != ["1", "2"]
            or any(int(event[1]) <= 0 or int(event[2]) <= 0 for event in worlds)
            or states[1][1] != synced[0][2]
            or synced[0][4] == synced[1][4]
            or any(int(event[1]) <= 0 or int(event[4]) <= 0 for event in states)
            or any(int(event[2]) != int(event[1]) + 1 or event[3] != "dagger"
                   or int(event[4]) <= 0 for event in synced)):
        raise RuntimeError("Expected live wave, state and entity updates on both joins")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", default=16568, type=int)
    parser.add_argument("--timeout", default=120, type=float)
    parser.add_argument("--model", choices=("old3ds", "new3ds"),
                        help="Check one Azahar profile (default: both)")
    parser.add_argument("--desktop-name",
                        help="Wait for a separately launched PC client and verify its connection overlaps both joins")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--movement", action="store_true", help="Verify the stock server applies scripted ARM11 movement")
    mode.add_argument("--controls", action="store_true", help="Verify D-pad and Circle Pad input and START disconnect")
    parser.add_argument("--controls-replay", action="store_true",
                        help="Replay the controls test input in Azahar 2126.2")
    parser.add_argument("--terrain-atlas", type=Path,
                        help="Stage the official v160.8 sprites.aatls and verify the rendered viewport")
    parser.add_argument("--locale", help="Stage a stock locale code and verify the graphical probe loads it")
    parser.add_argument("--menu-icons", action="store_true", help="Verify original block and item icons in the touch menus")
    parser.add_argument("--world-atlas", action="store_true", help="Verify the paged Core Nucleus sprite at Glacier spawn")
    parser.add_argument("--world-atlas-recycle", action="store_true",
                        help="Require the bounded atlas cache to submit and recycle inside the spawn frame")
    build_mode = parser.add_mutually_exclusive_group()
    build_mode.add_argument("--building", action="store_true",
                        help="Verify the ARM11 conveyor plan, copper cost and rendered response")
    build_mode.add_argument("--touch-build", action="store_true",
                            help="Verify touch placement, rotation and removal against the stock server")
    build_mode.add_argument("--touch-config", action="store_true",
                            help="Verify configuring a Sorter with both directions of the stock configuration packet")
    build_mode.add_argument("--building-layers", action="store_true",
                            help="Verify the stock cached wall and drill passes at Glacier spawn")
    parser.add_argument("--building-cracks", action="store_true",
                        help="Give the stock building fixture half health and verify its crack overlays")
    build_mode.add_argument("--liquids", action="store_true",
                            help="Verify animated stock water in straight and mirrored Glacier conduits")
    build_mode.add_argument("--building-effects", action="store_true",
                            help="Verify the original active Phase Weaver line and Overdrive sprite pass")
    build_mode.add_argument("--payload-mix", action="store_true",
                            help="Verify original full and partial team-color glow on stock Payload Conveyors")
    build_mode.add_argument("--carried-buildings", action="store_true",
                            help="Verify stock walls, drill and transport blocks carried on Payload Conveyors")
    build_mode.add_argument("--payload-handoff", action="store_true",
                            help="Verify a stock server transfers a carried wall and Dagger after the client joins")
    build_mode.add_argument("--unit-layers", action="store_true",
                            help="Verify the stock Gamma and Dagger both free and carried in paused Glacier")
    build_mode.add_argument("--unit-roster", action="store_true",
                            help="Verify streamed Serpulo unit types and the Quasar shield on Glacier")
    build_mode.add_argument("--naval-wake", action="store_true",
                            help="Verify stock naval hulls and both live Risso wakes")
    build_mode.add_argument("--tank-treads", action="store_true",
                            help="Verify the original Stell and Locus sprites and animated Locus treads")
    build_mode.add_argument("--map-spawn", metavar="NAME",
                            help="Verify the world and stationary player spawn on a stock server map")
    parser.add_argument("--erekir-sector", metavar="SLUG",
                        help="Import an unchanged Erekir campaign map from the official server jar for --map-spawn")
    campaign_mode = parser.add_mutually_exclusive_group()
    campaign_mode.add_argument("--erekir-build", action="store_true",
                               help="Verify Duct and Beryllium Wall touch construction on The Onset")
    campaign_mode.add_argument("--mining", choices=("wall", "floor"),
                               help="Verify touch mining and core delivery on The Onset or Ground Zero")
    parser.add_argument("--verify-objectives", action="store_true",
                        help="Compare the original first Onset objective transition between client and server")
    parser.add_argument("--require-relocation-spill", action="store_true",
                        help="Require the ARM11 collector to grow its relocation table during the spawn check")
    args = parser.parse_args()
    args.server, args.rom, args.azahar = (path.resolve() for path in (args.server, args.rom, args.azahar))
    with args.server.open("rb") as archive:
        if hashlib.file_digest(archive, "sha256").hexdigest() != SERVER_SHA256:
            parser.error("Expected the official v160.8 server-release.jar")
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    if args.desktop_name is not None and (not args.desktop_name.strip() or args.desktop_name == PLAYER):
        parser.error("The PC client needs a distinct player name")
    if args.controls_replay and not args.controls:
        parser.error("--controls-replay needs --controls")
    if args.building and not args.terrain_atlas:
        parser.error("--building needs --terrain-atlas")
    if args.touch_build and not args.terrain_atlas:
        parser.error("--touch-build needs --terrain-atlas")
    if args.touch_config and not args.terrain_atlas:
        parser.error("--touch-config needs --terrain-atlas")
    if args.menu_icons and not args.touch_config:
        parser.error("--menu-icons needs --touch-config")
    if args.world_atlas_recycle and not args.world_atlas:
        parser.error("--world-atlas-recycle needs --world-atlas")
    if args.world_atlas_recycle and (args.controls_replay or args.building or args.touch_build or args.touch_config):
        parser.error("--world-atlas-recycle runs a stationary spawn check")
    if args.building_cracks and not args.building_layers:
        parser.error("--building-cracks needs --building-layers")
    if args.payload_mix and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--payload-mix needs --controls and --terrain-atlas for a stationary capture")
    if args.carried_buildings and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--carried-buildings needs --controls and --terrain-atlas for a stationary capture")
    if args.payload_handoff and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--payload-handoff needs --controls and --terrain-atlas for a fixed camera")
    if args.unit_layers and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--unit-layers needs --controls and --terrain-atlas for a stationary capture")
    if args.unit_roster and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--unit-roster needs --controls and --terrain-atlas for a stationary capture")
    if args.naval_wake and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--naval-wake needs --controls and --terrain-atlas for a fixed camera")
    if args.tank_treads and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--tank-treads needs --controls and --terrain-atlas for a fixed camera")
    if args.map_spawn is not None:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 -]*", args.map_spawn):
            parser.error("--map-spawn needs a stock map name")
        if (not args.terrain_atlas or not args.controls or args.world_atlas
                or (args.controls_replay and not (args.erekir_build or args.mining))):
            parser.error("--map-spawn needs --controls and --terrain-atlas for a standalone spawn capture")
    if args.erekir_build and (args.map_spawn != "The Onset" or args.erekir_sector != "onset"
                             or not args.controls_replay):
        parser.error('--erekir-build needs --map-spawn "The Onset", --erekir-sector onset and --controls-replay')
    if args.mining == "wall" and (args.map_spawn != "The Onset" or args.erekir_sector != "onset"
                                  or not args.controls_replay):
        parser.error('--mining wall needs --map-spawn "The Onset", --erekir-sector onset and --controls-replay')
    if args.mining == "floor" and (args.map_spawn != "Ground Zero" or args.erekir_sector or not args.controls_replay):
        parser.error('--mining floor needs --map-spawn "Ground Zero" and --controls-replay')
    if args.verify_objectives and args.mining != "wall":
        parser.error("--verify-objectives needs --mining wall")
    if args.erekir_sector is not None:
        if not args.map_spawn or not re.fullmatch(r"[a-z][a-z-]*", args.erekir_sector):
            parser.error("--erekir-sector needs --map-spawn and a sector slug such as onset")
        with zipfile.ZipFile(args.server) as release:
            if f"maps/erekir/{args.erekir_sector}.msav" not in release.namelist():
                parser.error(f"No Erekir sector {args.erekir_sector!r} in the official server jar")
    if args.require_relocation_spill and not args.map_spawn:
        parser.error("--require-relocation-spill needs --map-spawn")
    if args.building_effects and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--building-effects needs --controls and --terrain-atlas for a stationary capture")
    if args.liquids and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--liquids needs --controls and --terrain-atlas for stationary captures")
    if args.building_layers and (not args.terrain_atlas or not args.controls or args.controls_replay):
        parser.error("--building-layers needs --controls and --terrain-atlas for a stationary capture")
    if args.world_atlas and not args.terrain_atlas:
        parser.error("--world-atlas needs --terrain-atlas")
    if args.locale and (not args.terrain_atlas or not re.fullmatch(r"[a-z]{2,3}(?:_[A-Z]{2})?", args.locale)):
        parser.error("--locale needs --terrain-atlas and a stock locale code such as de or pt_BR")
    if args.terrain_atlas:
        if not args.controls:
            parser.error("--terrain-atlas needs --controls")
        atlas_bytes = args.terrain_atlas.read_bytes()
        if hashlib.sha256(atlas_bytes).hexdigest() != ATLAS_SHA256:
            parser.error("Expected the official v160.8 sprites.aatls")
    config, log = az.resolve_azahar_paths()
    endpoint = config.parent.parent / "sdmc/3ds/min3dstry/server-endpoint.txt"
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log, endpoint)}
    atlas_path = endpoint.with_name("sprites.aatls")
    locale_path = endpoint.with_name("locale.txt")
    if args.locale:
        originals[locale_path] = locale_path.read_bytes() if locale_path.exists() else None
    if args.terrain_atlas:
        originals[atlas_path] = atlas_path.read_bytes() if atlas_path.exists() else None
    output = args.rom.parent / "upstream-live-net"
    output.mkdir(exist_ok=True)
    try:
        for path in originals:
            path.parent.mkdir(parents=True, exist_ok=True)
        suffix = " controls" if args.controls else " movement" if args.movement else ""
        endpoint.write_text(f"127.0.0.1 {args.port}{suffix}\n", encoding="ascii")
        if args.locale:
            locale_path.write_text(args.locale + "\n", encoding="ascii")
        if args.terrain_atlas:
            atlas_path.write_bytes(atlas_bytes)
        for model in ((args.model,) if args.model else ("old3ds", "new3ds")):
            run_model(args, model, config, (originals[config] or b"").decode(), log, output)
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


if __name__ == "__main__":
    main()
