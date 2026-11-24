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
import sys
import tempfile
import time
import zipfile

import verify_azahar as az
from stock_puddle_fixture import DEPOSIT as PUDDLE_DEPOSIT, SETUP as PUDDLE_SETUP, changed_colors


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
                          erekir_build=False, mining=None, model="old3ds", stock_menu_font=False,
                          unified_boot=False, stationary=False, chat=False, cancel_restart=False):
    """Write the Azahar 2126.2 CTM input for this probe's pad/touch polling."""
    # The pinned Azahar movie header and HID sample definitions use packed little-endian fields.
    revision = bytes.fromhex("219d743a1e698101fe1ab8bef43e6563126c3c27")
    samples_per_second = 234
    # Construction and configuration replays can take longer between input polls.
    needs_settling_time = building or touch_build or touch_config
    move_hold = 3 if unified_boot else 1
    circle_at = first_move + (18 if needs_settling_time else 6)
    leave_at = first_move + (44 if needs_settling_time else 16)
    if unified_boot:
        # Let each input settle and confirm idle snapshots at the combined ROM's slower frame cadence.
        circle_at = first_move + 28
        leave_at = circle_at + 28
    frames = (128 if erekir_build or mining or chat else leave_at + 7) * samples_per_second
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
        if stock_menu_font:
            # Keep both menus open while their cold glyphs enter the atlas.
            taps = (
                (35, (15, 64)), (42, (268, 218)), (70, (160, 80)), (76, (232, 136)),
                (84, (305, 64)), (90, (232, 136)), (120, (160, 55)),
                (130, (305, 64)), (136, (232, 136)), (144, (160, 80)),
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
    if chat:
        # Type "hi 3ds" across a long-running chat overlay using only bottom-screen HID samples.
        taps = ((39, (176, 132)), (43, (240, 93)), (47, (192, 214)),
                (51, (80, 53)), (55, (80, 132)), (59, (48, 132)))
    pan_end = 61 if model == "new3ds" else 60
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            seconds = frame // samples_per_second
            if chat:
                buttons = (1 << 11) if 32 <= seconds < 34 else 1 if 66 <= seconds < 68 else 0
            elif erekir_build:
                buttons = (1 << 8) if 66 <= seconds < 72 else 0  # R, the 3DS rotate shortcut.
            elif mining or stationary:
                buttons = 0
            else:
                # The unified ROM can take more than one emulated second to
                # service a HID transition on a loaded hosted runner. Hold each
                # control long enough that a late poll still observes the press.
                buttons = (1 << 4) if first_move <= seconds < first_move + move_hold \
                    else (1 << 3) if seconds == leave_at else 0
            if building and seconds in (35, 37, 39):
                buttons = 1  # A, with releases so a late HID poll can observe another press.
            if unified_boot and seconds < 6:
                buttons |= 1 << 10  # X, sampled once before the game starts.
            if cancel_restart and 64 <= seconds < 67:
                buttons |= 1 << 1  # B, while the original server is offline.
            circle_x = 65 if mining == "wall" and 54 <= seconds < pan_end else 0
            circle_y = 106 if circle_at <= seconds < circle_at + move_hold and not (
                erekir_build or mining or stationary) else 0
            if mining == "floor" and 88 <= seconds < 94:
                circle_y = 65
            point = next((point for start, point in taps if start <= seconds < start + 2), None)
            pad = struct.pack("<BHhh", 0, buttons, circle_x, circle_y)
            if point:
                # Extra HID polls on New 3DS can fall between pad/touch pairs.
                # Keep the touch held for the whole burst regardless of the poll phase.
                touch = struct.pack("<BHHBx", 1, *point, 1)
                movie.write(touch * 2)
            elif (taps or unified_boot) and (buttons or circle_x or circle_y):
                movie.write(pad * 2)
            else:
                movie.write(pad + touch_idle)
            if (taps or unified_boot) and model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(touch if point else pad)


def stop(proc, azahar_bin=None):
    if az._is_flatpak_azahar(azahar_bin):
        result = subprocess.run(["flatpak", "ps", "--columns=instance,pid"],
                                capture_output=True, text=True, check=False)
        for row in result.stdout.splitlines():
            fields = row.split()
            if len(fields) == 2 and fields[1] == str(proc.pid):
                subprocess.run(["flatpak", "kill", fields[0]], check=False)
                break
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
        # The stock server can color individual values inside a log line.
        if re.search(pattern, re.sub(r"\x1b\[[0-9;]*m", "", text)):
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


ONSET_MARKER_TEXT = {
    "en": ("Tap to mine", "on the vent."),
    "ja": ("壁から", "ジェットホールに配置しましょう。"),
}


def set_onset_beryllium(server, amount):
    server.stdin.write(f'js Vars.state.rules.defaultTeam.core().items.set(Items.beryllium, {amount})\n')
    server.stdin.flush()


def require_stock_objective_font(log, emulator, icon, timeout, label=None):
    text = re.escape(label) if label is not None else r"[^\r\n]+"
    wait_for(log, emulator, rf"UPSTREAM_OBJECTIVE_FONT source=v160\.8 icon={icon} text={text}", timeout)


def verify_onset_objective_hud(log, emulator, server_log, server, capture, position, markers=None, stock_timeout=0):
    wait_for(log, emulator, r"UPSTREAM_OBJECTIVE_HUD icon=beryllium text=Obtain: 0/60\|Beryllium\b", 10)
    if stock_timeout:
        require_stock_objective_font(log, emulator, "beryllium", stock_timeout, "Obtain: 0/60|Beryllium")
    if markers:
        markers("mine", 2320, 864, 868, ONSET_MARKER_TEXT["en"][0])
    # Drive the real server's item module and let its regular snapshots and objective executor
    # publish the changes. Hold each state until the client presents it for capture.
    for amount, icon, label, phase in (
        (17, "beryllium", "Obtain: 17/60|Beryllium", "objective-progress"),
        (60, "turbine-condenser", "Build: 1x|Turbine Condenser", "objective-next"),
    ):
        set_onset_beryllium(server, amount)
        wait_for(log, emulator, rf"UPSTREAM_OBJECTIVE_HUD icon={icon} text={re.escape(label)}\b", stock_timeout or 25)
        if stock_timeout:
            require_stock_objective_font(log, emulator, icon, stock_timeout, label)
        if not markers:
            capture(phase, position)
    verify_onset_objective(log, emulator, server_log, server, 60)
    if markers:
        markers("research", 2312, 944, 957.8, ONSET_MARKER_TEXT["en"][1])
    print("The objective HUD displayed the stock inventory progress and Turbine Condenser objective.", flush=True)


def capture_onset_marker(log, emulator, server, rpc, output, model, stock_timeout, styled, phase, x, y, label_y, words):
    # Use the stock server correction RPC to frame each marker without changing its coordinates.
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        f'p.unit().set({x}, {y}); p.set({x}, {y}); Call.setPosition(p.con, {x}, {y}); }})()\n'
    )
    server.stdin.flush()
    wait_for(log, emulator, rf"UPSTREAM_NETWORK_FRAME unit=\d+ left={x // 8 - 8} bottom={y // 8 - 6}\b", stock_timeout or 12)
    marker_y = re.escape(f"{label_y:.1f}")
    trace = rf"UPSTREAM_WORLD_LABEL x={x}\.0 y={marker_y} text=[^\r\n]*{re.escape(words)}"
    wait_for(log, emulator, trace, stock_timeout or 12)
    if stock_timeout:
        wait_for(log, emulator, rf"UPSTREAM_WORLD_LABEL_FONT x={x}\.0 y={marker_y} source=v160\.8\b", stock_timeout)
    if styled:
        code = 63304 if phase == "mine" else 63294
        wait_for(log, emulator, rf"UPSTREAM_WORLD_INLINE_GLYPH code={code}\b", 5)
    destination = output / f"{model}-marker-{phase}.png"
    # Both stock shapes use the same outline color. Inspect the area below the label.
    area = tuple((sx, sy) for sx in range(124, 299) for sy in range(100, 235))
    deadline = time.monotonic() + 8
    while True:
        screenshot = rpc.screenshot()
        colors = az.inspect_png(screenshot, area)["samples"]
        if sum(rgb == (255, 211, 127) for rgb in colors.values()) >= 8:
            destination.write_bytes(screenshot)
            print(f"{model}: visible stock Onset {phase} marker", flush=True)
            return
        if time.monotonic() >= deadline:
            destination.write_bytes(screenshot)
            raise RuntimeError(f"No stock Onset {phase} marker outline in {destination}")
        time.sleep(0.25)


def verify_onset_objective(log, emulator, server_log, server, beryllium, receive_timeout=5):
    if beryllium < 60:
        raise RuntimeError(f"Expected at least 60 beryllium for the original objective, got {beryllium}")
    completion = re.compile(r"UPSTREAM_OBJECTIVE_COMPLETE index=0 kind=ItemObjective "
                            r"next=1 nextKind=BuildCountObjective flags=(\d+)\b")
    client_flags = completion.search(wait_for(log, emulator, completion, receive_timeout))[1]
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
    if flags != client_flags:
        raise RuntimeError(f"Expected {flags} stock objective flags, observed {client_flags}")
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


def verify_touch_config(log, emulator, server_log, server, timeout, capture, position, menu_icons=False,
                        stock_menu_font=False):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)
    original = inspect_building(server_log, server, "config-before", tx, ty)
    catalog_open = r"UPSTREAM_CATALOG_PAGE category=distribution page=2\b"
    if menu_icons:
        wait_for(log, emulator, catalog_open + r"[\s\S]*?UPSTREAM_MENU_ICONS type=block drawn=6\b", timeout)
    if stock_menu_font:
        catalog_open += r"[\s\S]*?UPSTREAM_MENU_FONT source=v160\.8 catalog=block\b"
    wait_for(log, emulator, catalog_open, timeout)
    time.sleep(0.2)
    capture("touch-catalog", position)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD block=sorter x={tx} y={ty} rotation=0 team=1\b", timeout)
    placed = inspect_building(server_log, server, "config-placed", tx, ty)
    if original[0] != "air" or placed != ("sorter", "1", "0", str(int(original[3]) - 2), "true"):
        raise RuntimeError(f"Unexpected stock Sorter placement: {original} -> {placed}")
    unconfigured = ((110, 112, 128), (110, 112, 128), (102, 103, 118))
    wait_for_touch_pixels(capture, "touch-sorter", position, unconfigured)
    config_open = rf"UPSTREAM_CONFIG_OPEN x={tx} y={ty}\b"
    if menu_icons:
        wait_for(log, emulator, config_open + r"[\s\S]*?UPSTREAM_MENU_ICONS type=item drawn=6\b", timeout)
    if stock_menu_font:
        config_open += r"[\s\S]*?UPSTREAM_MENU_FONT source=v160\.8 catalog=item\b"
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
    wait_for_touch_pixels(capture, "touch-config-copper", position, ((217, 157, 115),) * 3)
    server_item("to-3ds", "lead", "b.configureAny(Items.lead);")
    wait_for(log, emulator, rf"UPSTREAM_CONFIG_REMOTE x={tx} y={ty} item=lead\b", timeout)
    wait_for_touch_pixels(capture, "touch-config-lead", position, ((140, 127, 169),) * 3)
    wait_for(log, emulator, rf"UPSTREAM_CONFIG_LOCAL x={tx} y={ty} item=none\b", timeout)
    time.sleep(0.2)
    server_item("cleared", "none")
    wait_for_touch_pixels(capture, "touch-config-cleared", position, unconfigured)
    print("Stock server verified Copper, returned Lead, and accepted clearing the Sorter.", flush=True)


def verify_controls(log, emulator, server_log, server, timeout, capture=None,
                    building=False, desktop_name=None, touch_build=False, touch_config=False, menu_icons=False,
                    stock_menu_font=False):
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", timeout)
    if stock_menu_font:
        for index in (0, 1, 2, 3, 4, 5, 7):
            wait_for(log, emulator, rf"UPSTREAM_MENU_FONT source=v160\.8 palette={index}\b", timeout)
    baseline = server_position(server_log, server, "UPSTREAM_SERVER_CONTROLS_READY").groups()
    ground_pixels = capture("spawn", baseline) if capture else None
    if building:
        verify_conveyor(log, emulator, server_log, server, timeout, capture, baseline, desktop_name)
    if touch_build:
        verify_touch_build(log, emulator, server_log, server, timeout, capture, baseline, ground_pixels)
    if touch_config:
        verify_touch_config(log, emulator, server_log, server, timeout, capture, baseline,
                            menu_icons, stock_menu_font)
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
                     world_size=(150, 250), spawn_pixels=None, frame_timeout=5):
    unit, _, x, y = position
    # The renderer centers a 16 × 12 tile viewport in the loaded map.
    width, height = world_size
    left = max(0, min(width - 16, math.floor(float(x) / 8 + 0.5) - 8))
    bottom = max(0, min(height - 12, math.floor(float(y) / 8 + 0.5) - 6))
    frame = f"UPSTREAM_NETWORK_FRAME unit={unit} left={left} bottom={bottom} sprites=1"
    wait_for(log, emulator, rf"{frame}\b", frame_timeout)
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


def spawn_expanded_unit_roster(server_log, server):
    server.stdin.write(
        'js (() => { const enemy = Vars.state.rules.waveTeam; '
        'const missile = UnitTypes.anthicus.weapons.first().bullet.spawnUnit; '
        'const elude = UnitTypes.elude.spawn(enemy,632,228,90); '
        'const shot = missile.spawn(enemy,628,191,90); '
        'const team = Vars.state.rules.defaultTeam; '
        'const carrier = UnitTypes.mega.spawn(team,704,220,90); '
        'const cargo = UnitTypes.horizon.create(team); '
        'carrier.addPayload(new Packages.mindustry.world.blocks.payloads.UnitPayload(cargo)); '
        'const units = Packages.mindustry.entities.Units; '
        'units.notifyUnitSpawn(elude); units.notifyUnitSpawn(shot); units.notifyUnitSpawn(carrier); '
        'return "UPSTREAM_EXPANDED_ROSTER_FIXTURE " + [elude.type.name,shot.type.name,'
        'carrier.type.name,carrier.payloads.first().unit.type.name].join(","); })()\n')
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_EXPANDED_ROSTER_FIXTURE elude,anthicus-missile,mega,horizon\b", 5)


def verify_expanded_unit_roster(log, emulator, server_log, server, rpc, before, destination, timeout):
    spawn_expanded_unit_roster(server_log, server)
    for name in ("elude", "anthicus-missile", "mega", "horizon"):
        wait_for(log, emulator, rf"UPSTREAM_NETWORK_UNIT_ART type={name}\b", timeout)

    # The server and camera stay stationary. Check only the newly occupied sprite regions.
    regions = [tuple((x, y) for x in range(cx - 7, cx + 8) for y in range(cy - 7, cy + 8))
               for cx, cy in ((169, 63), (159, 156), (339, 89))]
    points = tuple(point for region in regions for point in region)
    original = az.inspect_png(before, points)["samples"]
    deadline = time.monotonic() + 15
    changed = []
    while time.monotonic() < deadline:
        after = rpc.screenshot()
        current = az.inspect_png(after, points)["samples"]
        changed = [sum(current[point] != original[point] for point in region) for region in regions]
        if min(changed) >= 8:
            destination.write_bytes(after)
            print(f"Stock Elude, Anthicus missile and Mega with Horizon changed {changed} pixels in their regions.", flush=True)
            return
        time.sleep(0.25)
    raise RuntimeError(f"Expanded unit sprites did not appear in the stationary viewport: {changed}")


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


def send_marker_phase(server_log, server, phase, commands):
    server.stdin.write(f'js (() => {{ {commands} return "UPSTREAM_LOGIC_MARKERS {phase}"; }})()\n')
    server.stdin.flush()
    wait_for(server_log, server, rf"UPSTREAM_LOGIC_MARKERS {phase}\b", 5)


def verify_logic_markers(log, emulator, server_log, server, rpc, output, model, position, timeout):
    # Use the same 16 × 12 viewport and tile-rounded center as the graphical client.
    _, _, x, y = position
    x, y = (math.floor(float(value) / 8 + 0.5) * 8 for value in (x, y))
    # The viewport begins at the outer edge of the first tile, half a tile before its center.
    shape_sample, line_sample, moved_sample = (130, 55), (210, 155), (210, 175)
    magenta, turquoise = (235, 29, 180), (24, 229, 199)
    point_area = tuple((px, py) for px in range(263, 298) for py in range(48, 83))
    point_color = (67, 245, 91)

    def capture(phase, shape, line, moved, point):
        samples = (shape_sample, line_sample, moved_sample)
        colors = (magenta, turquoise, turquoise)
        deadline = time.monotonic() + timeout
        while True:
            png = rpc.screenshot(res_scale=1)
            pixels = az.inspect_png(png, samples + point_area)["samples"]
            visible = tuple(pixels[point] == color for point, color in zip(samples, colors))
            point_visible = any(pixels[pixel] == point_color for pixel in point_area)
            if visible == (shape, line, moved) and point_visible == point:
                (output / f"{model}-logic-markers-{phase}.png").write_bytes(png)
                return
            if time.monotonic() >= deadline:
                (output / f"{model}-logic-markers-{phase}-unexpected.png").write_bytes(png)
                observed = tuple(pixels[sample] for sample in samples)
                raise RuntimeError(f"Unexpected {phase} marker pixels {observed}, visibility {visible}, "
                                   f"point visible {point_visible}")
            time.sleep(0.1)

    capture("baseline", False, False, False, False)
    send_marker_phase(server_log, server, "created",
        f"const shape = new MapObjectives.ShapeMarker({x - 32}, {y + 22}); "
        'shape.fill = true; shape.radius = 7; shape.color.set(Color.valueOf("eb1db4")); '
        f"const line = new MapObjectives.LineMarker({x - 28}, {y - 18}, {x + 28}, {y - 18}, 3); "
        'line.color1.set(Color.valueOf("18e5c7")); line.color2.set(line.color1); '
        f'const point = new MapObjectives.PointMarker({x + 28}, {y + 18}, 0.8, 3, Color.valueOf("43f55b")); '
        f'const text = new MapObjectives.TextMarker("Logic marker created", {x}, {y + 24}); '
        "Call.createMarker(745, shape); Call.createMarker(746, line); "
        "Call.createMarker(747, point); Call.createMarker(748, text);")
    wait_for(log, emulator, r"UPSTREAM_WORLD_LABEL .*text=Logic marker created\b", timeout)
    capture("created", True, True, False, True)

    control = "Packages.mindustry.logic.LMarkerControl"
    send_marker_phase(server_log, server, "updated",
        f"const control = {control}; "
        "Call.updateMarker(745, control.world, 0, NaN, NaN); "
        f"Call.updateMarker(746, control.pos, {(x - 28) / 8}, {(y - 26) / 8}, NaN); "
        f"Call.updateMarker(746, control.endPos, {(x + 28) / 8}, {(y - 26) / 8}, NaN); "
        'Call.updateMarkerText(748, control.flushText, false, "Logic marker updated");')
    wait_for(log, emulator, r"UPSTREAM_WORLD_LABEL .*text=Logic marker updated\b", timeout)
    capture("updated", False, False, True, True)

    send_marker_phase(server_log, server, "removed", "for (let id = 745; id <= 748; id++) Call.removeMarker(id);")
    capture("removed", False, False, False, False)
    print(f"{model}: original logic marker drawing, visibility, position, text and removal passed.", flush=True)


def capture_marker_pixels(rpc, output, model, phase, points, timeout, expected=None, tolerance=1):
    deadline = time.monotonic() + timeout
    png, samples = None, None
    while time.monotonic() < deadline:
        try:
            captured = rpc.screenshot(res_scale=1)
        except TimeoutError:
            time.sleep(0.2)
            continue
        png = captured
        pixels = az.inspect_png(png, points)["samples"]
        samples = tuple(pixels[point] for point in points)
        if expected is None or all(abs(a - b) <= tolerance for actual, exact in zip(samples, expected)
                                   for a, b in zip(actual, exact)):
            (output / f"{model}-{phase}.png").write_bytes(png)
            return samples
        time.sleep(0.2)
    if png is not None:
        (output / f"{model}-{phase}-unexpected.png").write_bytes(png)
    raise RuntimeError(f"{phase} pixels: expected {expected}, got {samples}")


def verify_texture_markers(server_log, server, rpc, output, model, position, timeout):
    _, _, x, y = position
    x, y = (math.floor(float(value) / 8 + 0.5) * 8 for value in (x, y))
    # The 40px marker is centered at (140, 50). Take opaque samples from the
    # v160.8 atlas crops for block-router-ui, item-copper and error.
    points = ((128, 44), (128, 50), (152, 53))
    plain = ((110, 112, 128), (110, 112, 128), (152, 154, 164))
    rotated = ((152, 154, 164), (152, 154, 164), (110, 112, 128))
    copper = ((216, 157, 115), (234, 193, 168), (184, 112, 92))
    error = ((255, 255, 255),) * 3

    def capture(phase, expected=None):
        return capture_marker_pixels(rpc, output, model, f"texture-marker-{phase}", points, timeout, expected)

    baseline = capture("baseline")
    send_marker_phase(server_log, server, "texture-created",
        f'Call.createMarker(749, new MapObjectives.TextureMarker("block-router-ui", {x - 28}, {y + 24}, 16, 16));')
    capture("created", plain)
    send_marker_phase(server_log, server, "texture-rotated",
        "Call.updateMarker(749, Packages.mindustry.logic.LMarkerControl.rotation, 90, NaN, NaN);")
    capture("rotated", rotated)
    send_marker_phase(server_log, server, "texture-content", "Call.updateMarkerTexture(749, Items.copper);")
    capture("content", copper)
    send_marker_phase(server_log, server, "texture-error", 'Call.updateMarkerTexture(749, "min3dstry-missing-region");')
    capture("error", error)
    send_marker_phase(server_log, server, "texture-removed", "Call.removeMarker(749);")
    capture("removed", baseline)
    print(f"{model}: original texture marker creation, rotation, content, fallback and removal passed.", flush=True)


def create_fixture_quad(server_log, server, phase, marker_id, texture, x, y):
    corners = ((-52, 0, 0, 0), (-48, 24, 0, 1), (-24, 28, 1, 1), (-20, 4, 1, 0))
    assignments = " ".join(
        f"v[{i * 6}]={x + dx}; v[{i * 6 + 1}]={y + dy}; v[{i * 6 + 3}]={u}; v[{i * 6 + 4}]={t};"
        for i, (dx, dy, u, t) in enumerate(corners))
    send_marker_phase(server_log, server, phase,
        f"const q=new MapObjectives.QuadMarker(); q.texture.value={texture}; const v=q.vertices; "
        + assignments + f' v[8]=Color.valueOf("80ffffff").toFloatBits(); Call.createMarker({marker_id}, q);')


def verify_quad_markers(log, emulator, server_log, server, rpc, output, model, position, timeout):
    _, _, x, y = position
    x, y = (math.floor(float(value) / 8 + 0.5) * 8 for value in (x, y))
    # Keep all samples below the stock health, ammo, enemy-core and item HUD.
    y -= 32
    # Opaque samples in the original Core Nucleus span three 128 × 128 atlas
    # tiles. The first sample includes the stock interpolated red tint.
    points = ((94, 132), (109, 166), (133, 148), (153, 125))
    initial = ((101, 185, 192), (152, 154, 164), (176, 185, 192))
    moved = ((86, 154, 164), (110, 112, 128), (152, 154, 164), (152, 154, 164))
    router = ((62, 112, 128), (110, 112, 128), (152, 154, 164), (147, 149, 160))

    def capture(phase, expected=None):
        return capture_marker_pixels(rpc, output, model, f"quad-marker-{phase}",
                                     points, timeout, expected, tolerance=4)

    baseline = capture("baseline")
    create_fixture_quad(server_log, server, "quad-created", 752, '"core-nucleus"', x, y)
    wait_for(log, emulator, "UPSTREAM_TEXTURED_QUAD source=arc gpu=citro3d", timeout)
    capture("created", (*initial, baseline[3]))
    send_marker_phase(server_log, server, "quad-moved",
        "Call.updateMarker(752, Packages.mindustry.logic.LMarkerControl.posi, "
        f"2, {(x - 16) / 8}, {(y + 32) / 8});")
    capture("moved", moved)
    send_marker_phase(server_log, server, "quad-retargeted", 'Call.updateMarkerTexture(752, "block-router-ui");')
    capture("retargeted", router)
    send_marker_phase(server_log, server, "quad-removed", "Call.removeMarker(752);")
    capture("removed", baseline)
    print(f"{model}: stock quad tint, atlas clipping, corner update and retargeting passed.", flush=True)


PROCESSOR_CANVAS_COLUMN = 82


def load_canvas_fixture(server_log, server, offscreen=False, small_column=77):
    extra = ("Vars.world.tile(45,27).setBlock(Blocks.canvas,t); paint(Vars.world.build(45,27)); " if offscreen else "")
    send_marker_phase(server_log, server, "canvas-fixture",
        "const t=Vars.state.rules.defaultTeam; "
        "Vars.world.tile(75,29).setBlock(Blocks.canvas,t); "
        f"Vars.world.tile({small_column},29).setBlock(Blocks.canvas,t); "
        "Vars.world.tile(77,23).setBlock(Blocks.largeCanvas,t); "
        f"const small=Vars.world.build({small_column},29), large=Vars.world.build(77,23); "
        "const paint=c=>{for (let y=0; y<12; y++) for (let x=0; x<12; x++) "
        "c.setPixel(y*12+x, y<6 ? (x<6 ? 1 : 2) : (x<6 ? 4 : 6));}; paint(small); "
        "for (let y=0; y<24; y++) for (let x=0; x<24; x++) "
        "large.setPixel(y*24+x, x<12 ? 3 : 8); " + extra)


def verify_canvas(server_log, server, rpc, output, model, timeout):
    # The original Glacier camera starts centered at world (648, 208).
    # Sample inside the original 12×12 and 24×24 pictures, away from their borders.
    small_points = ((130, 30), (150, 30), (130, 50), (150, 50))
    large_points = ((115, 155), (145, 185))
    marker_points = ((261, 51), (279, 51), (261, 69), (279, 69))
    small = ((196, 93, 159), (227, 154, 172), (100, 97, 194), (147, 212, 181))
    large = ((250, 191, 97), (120, 194, 214))
    changed = ((240, 218, 177), (43, 169, 180), (240, 218, 177), (43, 169, 180))

    capture_marker_pixels(rpc, output, model, "canvas-loaded", small_points + large_points, timeout, small + large)
    send_marker_phase(server_log, server, "canvas-marker",
        "const c=Vars.world.build(77,29); "
        "Call.createMarker(753,new MapObjectives.TextureMarker(c,672,228,16,16));")
    points = small_points + large_points + marker_points
    capture_marker_pixels(rpc, output, model, "canvas-marker", points, timeout, small + large + small)

    send_marker_phase(server_log, server, "canvas-configured",
        "const staging=Vars.world.build(75,29), canvas=Vars.world.build(77,29); "
        "for(let i=0; i<144; i++) staging.setPixel(i, i%12<6 ? 3 : 5); "
        "canvas.configureAny(staging.data); "
        'if(canvas.getPixel(0)!==3 || canvas.getPixel(11)!==5) throw "Canvas config mismatch";')
    capture_marker_pixels(rpc, output, model, "canvas-updated", points, timeout, changed + large + changed)
    print(f"{model}: stock Canvas snapshot pixels, live configuration and linked marker passed.", flush=True)


def verify_stock_weather_layers(log, emulator, server_log, server, rpc, output, model, timeout):
    wait_for(log, emulator, r"UPSTREAM_NETWORK_FRAME unit=\d+", timeout)
    world = [(x, y) for y in range(12, 231, 6) for x in range(52, 349, 6)
             if not (165 < x < 235 and 82 < y < 151)]
    # Sample the touch grid after the button labels, which can switch fonts on startup.
    touch = [(x, y) for y in (350, 385, 415, 445) for x in range(54, 347, 8)]
    before = rpc.screenshot(res_scale=1)
    (output / f"{model}-weather-clear.png").write_bytes(before)
    baseline = az.inspect_png(before, world + touch)["samples"]
    server.stdin.write(
        'js (() => { Call.createWeather(Weathers.rain, 1, 900, 0, 0); '
        'Call.createWeather(Weathers.snow, 1, 900, 0, 0); '
        'Call.createWeather(Weathers.sandstorm, 1, 900, 0, 0); '
        'Call.createWeather(Weathers.fog, 1, 900, 0, 0); '
        'return "UPSTREAM_STOCK_WEATHER_VISUALS ready"; })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_STOCK_WEATHER_VISUALS ready\b", 5)
    for name in ("rain", "snowing", "sandstorm", "fog"):
        wait_for(log, emulator, rf"UPSTREAM_LIVE_WEATHER name={name}\b", timeout)
    for name in ("noiseAlpha", "fog"):
        wait_for(log, emulator, rf"UPSTREAM_WEATHER_TEXTURE name={name}\b", timeout)
    deadline = time.monotonic() + min(timeout, 45)
    frame = before
    while time.monotonic() < deadline:
        frame = rpc.screenshot(res_scale=1)
        pixels = az.inspect_png(frame, world + touch)["samples"]
        changed = sum(max(abs(a - b) for a, b in zip(pixels[p], baseline[p])) >= 12 for p in world)
        if changed >= 96 and all(pixels[p] == baseline[p] for p in touch):
            (output / f"{model}-weather-active.png").write_bytes(frame)
            print(f"{model}: the stock weather changed {changed} sampled world pixels with an unchanged touch grid.", flush=True)
            return log.read_text(encoding="utf-8", errors="replace")
        time.sleep(0.2)
    (output / f"{model}-weather-unexpected.png").write_bytes(frame)
    raise RuntimeError(f"{model} did not composite the original weather inside the world viewport")


def verify_stock_chat(log, emulator, server_log, server, rpc, output, model, timeout):
    wait_for(log, emulator, r"UPSTREAM_NETWORK_FRAME unit=\d+", timeout)
    points = [(x, y) for y in range(180, 226, 3) for x in range(48, 228, 3)]
    before = az.inspect_png(rpc.screenshot(res_scale=1), points)["samples"]
    server.stdin.write(
        'js (() => { const p=Groups.player.find(boolf(v=>String(v.name)==="Min3DStry ARM11")); '
        'Call.sendMessage("[accent]Stock notice"); '
        'Packages.mindustry.core.NetClient.sendChatMessage(p,"Stock player chat"); '
        'return "UPSTREAM_STOCK_CHAT_SENT"; })()\n'
    )
    server.stdin.flush()
    wait_for(server_log, server, r"UPSTREAM_STOCK_CHAT_SENT\b", 10)
    for sender in ("server", "player"):
        wait_for(log, emulator, rf"UPSTREAM_LIVE_CHAT_RECEIVED source={sender} chars=[1-9]\d*\b", timeout)
    wait_for(log, emulator, r"UPSTREAM_LIVE_CHAT_DRAW line=(?:[3-9]|\d{2,})\b", timeout)
    deadline = time.monotonic() + min(timeout, 10)
    while time.monotonic() < deadline:
        frame = rpc.screenshot(res_scale=1)
        after = az.inspect_png(frame, points)["samples"]
        changed = sum(max(abs(a - b) for a, b in zip(before[p], after[p])) >= 16 for p in points)
        if changed >= 40:
            (output / f"{model}-stock-chat.png").write_bytes(frame)
            break
        time.sleep(0.1)
    else:
        raise RuntimeError(f"{model}: stock chat reached ARM11 without a visible chat panel")
    bottom = [(x, y) for y in range(258, 458, 8) for x in range(48, 354, 8)]
    play_grid = az.inspect_png(frame, bottom)["samples"]
    wait_for(log, emulator, r"UPSTREAM_LIVE_CHAT_INPUT state=open\b", timeout)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        keyboard = rpc.screenshot(res_scale=1)
        keys = az.inspect_png(keyboard, bottom)["samples"]
        if sum(max(abs(a - b) for a, b in zip(play_grid[p], keys[p])) >= 16 for p in bottom) >= 60:
            (output / f"{model}-chat-keyboard.png").write_bytes(keyboard)
            break
        time.sleep(0.1)
    else:
        raise RuntimeError(f"{model}: the bottom screen did not present the touch keyboard")
    server.stdin.write("runwave\n")
    server.stdin.flush()
    during_chat = wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", timeout)
    if "UPSTREAM_LIVE_CHAT_INPUT state=closed" in during_chat:
        raise RuntimeError("The wave snapshot must reach ARM11 while chat is open")
    wait_for(log, emulator, r"UPSTREAM_LIVE_CHAT_SENT chars=6\b", timeout)
    wait_for(server_log, server, rf"{PLAYER}: hi 3ds\b", timeout)
    wait_for(log, emulator, r"UPSTREAM_LIVE_CHAT_SENT chars=6[^\r\n]*[\s\S]+"
                            r"UPSTREAM_LIVE_CHAT_RECEIVED source=player", timeout)
    wait_for(log, emulator, r"UPSTREAM_LIVE_CHAT_SENT chars=6[^\r\n]*[\s\S]+"
                            r"UPSTREAM_LIVE_CHAT_DRAW line=(?:[4-9]|\d{2,})\b", timeout)
    server.stdin.write("runwave\n")
    server.stdin.flush()
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_STATE wave=3\b", timeout)
    print(f"{model}: original chat round trip passed and snapshots continued during text entry.", flush=True)


def verify_canvas_quads(log, emulator, server_log, server, rpc, output, model, position, timeout):
    _, _, x, y = position
    x, y = (math.floor(float(value) / 8 + 0.5) * 8 for value in (x, y))
    if 45 >= x // 8 - 8:
        raise RuntimeError("The Canvas quad fixture must keep its source tile offscreen")
    # Place the quad beneath the live HUD in the unified ROM.
    y -= 32
    points = ((94, 132), (109, 166), (133, 148), (153, 125))
    original = ((112, 93, 159), (100, 97, 194), (227, 154, 172))
    configured = ((137, 218, 177), (240, 218, 177), (43, 169, 180))
    moved = ((136, 218, 177), (240, 218, 177), (43, 169, 180), (43, 169, 180))
    router = ((62, 112, 128), (110, 112, 128), (152, 154, 164), (147, 149, 160))

    def capture(phase, expected=None):
        return capture_marker_pixels(rpc, output, model, f"canvas-quad-{phase}",
                                     points, timeout, expected, tolerance=4)

    baseline = capture("baseline")
    create_fixture_quad(server_log, server, "canvas-quad-created", 754, "Vars.world.build(45,27)", x, y)
    wait_for(log, emulator, "UPSTREAM_CANVAS_QUAD source=arc gpu=citro3d", timeout)
    capture("created", (*original, baseline[3]))
    send_marker_phase(server_log, server, "canvas-quad-configured",
        "const staging=Vars.world.build(75,29), canvas=Vars.world.build(45,27); "
        "for(let i=0; i<144; i++) staging.setPixel(i, i%12<6 ? 3 : 5); "
        "canvas.configureAny(staging.data); "
        'if(canvas.getPixel(0)!==3 || canvas.getPixel(11)!==5) throw "Offscreen Canvas config mismatch";')
    capture("configured", (*configured, baseline[3]))
    send_marker_phase(server_log, server, "canvas-quad-moved",
        "Call.updateMarker(754, Packages.mindustry.logic.LMarkerControl.posi, "
        f"2, {(x - 16) / 8}, {(y + 32) / 8});")
    capture("moved", moved)
    send_marker_phase(server_log, server, "canvas-quad-retargeted", 'Call.updateMarkerTexture(754, "block-router-ui");')
    capture("retargeted", router)
    send_marker_phase(server_log, server, "canvas-quad-removed", "Call.removeMarker(754);")
    capture("removed", baseline)
    print(f"{model}: offscreen Canvas quad pixels, configuration, tint, corner and retargeting passed.", flush=True)


def verify_client_processor(server_log, server, rpc, output, model, timeout):
    # The Canvas is on the right side of the spawn, clear of the live HUD.
    points = ((230, 30), (250, 30), (230, 50), (250, 50))
    original = ((196, 93, 159), (227, 154, 172), (100, 97, 194), (147, 212, 181))
    capture_marker_pixels(rpc, output, model, "processor-before", points, timeout, original)
    # Stock world processors expose @client. The dedicated server skips the
    # write, and it never sends a Canvas configuration packet in this replay.
    code = ("jump 6 notEqual @client 1\ngetlink canvas 0\nset i 0\n"
            "write 7 canvas i\nop add i i 1\njump 3 lessThan i 144\nend")
    send_marker_phase(server_log, server, "processor-configured",
        f"const p=Vars.world.build({PROCESSOR_CANVAS_COLUMN},32), links=new Seq(); "
        "links.add(new Packages.mindustry.world.blocks.logic.LogicBlock.LogicLink(0,-3,'canvas1',true)); "
        f"p.configureAny(Packages.mindustry.world.blocks.logic.LogicBlock.compress({json.dumps(code)},links)); "
        'if(p.executor.instructions.length!==7 || p.executor.links.length!==1) throw "Invalid processor config";')
    capture_marker_pixels(rpc, output, model, "processor-client-write", points, timeout, ((240, 246, 232),) * 4)
    send_marker_phase(server_log, server, "processor-server-unchanged",
        f"const c=Vars.world.build({PROCESSOR_CANVAS_COLUMN},29); "
        'if(c.getPixel(0)!==1 || c.getPixel(143)!==6) throw "Server Canvas changed";')
    print(f"{model}: stock processor config, link and client-local Canvas writes passed.", flush=True)


def verify_stock_puddles(server_log, server, rpc, output, model, timeout):
    before = rpc.screenshot()
    send_marker_phase(server_log, server, "puddles-deposited", PUDDLE_DEPOSIT)
    deadline = time.monotonic() + min(timeout, 30)
    while time.monotonic() < deadline:
        after = rpc.screenshot()
        water, neoplasm = changed_colors(before, after)
        if min(water, neoplasm) >= 12:
            (output / f"{model}-puddles-before.png").write_bytes(before)
            (output / f"{model}-puddles-after.png").write_bytes(after)
            print(f"{model}: stock puddle snapshots matched water/neoplasm samples {water}/{neoplasm}.", flush=True)
            return
        time.sleep(0.15)
    raise RuntimeError(f"{model}: original liquid colors did not appear after the stock entity snapshot")


def verify_logic_displays(server_log, server, rpc, output, model, timeout):
    # The normal display sits below the live HUD in the unified ROM.
    points = ((123, 130), (150, 170), (141, 163), (300, 50), (120, 190))
    capture_marker_pixels(rpc, output, model, "display-background", points, timeout, ((86, 86, 102),) * len(points))
    code = """getlink primary 0
getlink source 1
getlink tiled 2
draw clear 160 210 75 0 0 0
drawflush tiled
draw clear 30 200 240 0 0 0
drawflush source
draw clear 16 32 64 0 0 0
draw color 232 54 80 255 0 0
draw rect 20 60 24 20 0 0
draw color 255 255 255 255 0 0
draw image 56 20 source 20 0 0
print "ARM11"
draw print 17 36 @bottomLeft 0 0 0
drawflush primary
end
"""
    send_marker_phase(server_log, server, "display-configured",
        "const p=Vars.world.build(77,26), links=new Seq(); "
        "const Link=Packages.mindustry.world.blocks.logic.LogicBlock.LogicLink; "
        "links.add(new Link(0,-2,'display1',true)); links.add(new Link(9,3,'display2',true)); "
        "links.add(new Link(-1,-4,'display3',true)); "
        f"p.configureAny(Packages.mindustry.world.blocks.logic.LogicBlock.compress({json.dumps(code)},links)); "
        'if(p.executor.instructions.length!==16 || p.executor.links.length!==3) throw "Invalid display config";')
    capture_marker_pixels(rpc, output, model, "display-program", points, timeout,
                          ((232, 54, 80), (16, 32, 64), (30, 200, 240), (30, 200, 240), (160, 210, 75)))
    text_area = tuple((x, y) for x in range(115, 137) for y in range(145, 155))
    screenshot = (output / f"{model}-display-program.png").read_bytes()
    text_pixels = az.inspect_png(screenshot, text_area)["samples"]
    if sum(min(color) >= 160 for color in text_pixels.values()) < 8:
        raise RuntimeError("Original logic font was missing from the display framebuffer")
    send_marker_phase(server_log, server, "display-server-headless",
        'if(Vars.world.build(77,24).buffer!=null || Vars.world.build(86,29).buffer!=null) '
        'throw "Dedicated server unexpectedly rendered displays";')
    print(f"{model}: normal, large and tiled Logic Displays rendered the stock processor program.", flush=True)


def verify_display_markers(server_log, server, rpc, output, model, timeout):
    # Sample both markers below the stock status HUD.
    points = ((105, 155), (105, 185), (275, 155), (275, 185))

    def capture(phase, colors=None):
        return capture_marker_pixels(rpc, output, model, f"display-markers-{phase}",
                                     points, timeout, colors)

    baseline = capture("baseline")
    send_marker_phase(server_log, server, "display-markers-created",
        "const source=Vars.world.build(45,27); "
        "const t=new MapObjectives.TextureMarker(source,608,184,24,24); "
        "const q=new MapObjectives.QuadMarker(); q.texture.value=source; const v=q.vertices; "
        "const points=[[664,172,0,0],[664,196,0,1],[688,196,1,1],[688,172,1,0]]; "
        "for(let i=0;i<4;i++){v[i*6]=points[i][0]; v[i*6+1]=points[i][1]; "
        "v[i*6+3]=points[i][2]; v[i*6+4]=points[i][3];} "
        "Call.createMarker(755,t); Call.createMarker(756,q);")
    capture("created", ((86, 86, 102),) * len(points))
    for phase, top, bottom in (("painted", (30, 200, 240), (232, 54, 80)),
                               ("updated", (230, 210, 75), (58, 200, 99))):
        code = (f"getlink source 0\ndraw clear {' '.join(map(str, top))} 0 0 0\n"
                f"draw color {' '.join(map(str, bottom))} 255 0 0\n"
                "draw rect 0 0 80 40 0 0\ndrawflush source\nend\n")
        send_marker_phase(server_log, server, f"display-markers-{phase}",
            "const p=Vars.world.build(46,23), links=new Seq(); "
            "const Link=Packages.mindustry.world.blocks.logic.LogicBlock.LogicLink; "
            "links.add(new Link(-1,4,'display1',true)); "
            f"p.configureAny(Packages.mindustry.world.blocks.logic.LogicBlock.compress({json.dumps(code)},links)); "
            'if(p.executor.instructions.length!==6 || p.executor.links.length!==1) throw "Invalid marker source program";')
        capture(phase, (top, bottom) * 2)
    send_marker_phase(server_log, server, "display-markers-retargeted",
        'Call.updateMarkerTexture(755,"white"); Call.updateMarkerTexture(756,"white");')
    capture("retargeted", ((255, 255, 255),) * len(points))
    send_marker_phase(server_log, server, "display-markers-removed",
        'Call.removeMarker(755); Call.removeMarker(756);')
    capture("removed", baseline)
    print(f"{model}: stock display-backed texture and quad passed orientation, offscreen updates and retargeting.", flush=True)


def run_model(args, model, config, original_config, log, output):
    stationary = (args.world_atlas_recycle or args.building_layers or args.liquids
                  or args.building_effects or args.payload_mix or args.carried_buildings
                  or args.payload_handoff or args.unit_layers or args.unit_roster or args.naval_wake
                  or args.tank_treads or args.map_spawn or args.combat
                  or args.verify_logic_markers or args.verify_texture_markers or args.verify_quad_markers or args.verify_canvas
                  or args.verify_canvas_quads or args.verify_client_processor or args.verify_logic_displays
                  or args.verify_display_markers or args.verify_puddles or args.verify_weather_layers
                  or args.verify_chat)
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
    if args.touch_build or args.touch_config or args.erekir_build or args.mining or args.unified_boot:
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
            rules = "sandbox" if args.combat else "survival" if args.map_spawn else "attack"
            server.stdin.write(f"config port {args.port}\nhost {map_name.replace(' ', '_')} {rules}\n")
            server.stdin.flush()
            wait_for(server_log, server, f"Opened a server on port {args.port}\\.", 25)
            world_size = (150, 250)
            if args.verify_canvas or args.verify_canvas_quads or args.verify_client_processor:
                small_column = PROCESSOR_CANVAS_COLUMN if args.verify_client_processor else 77
                load_canvas_fixture(server_log, server, offscreen=args.verify_canvas_quads, small_column=small_column)
            if args.verify_client_processor:
                send_marker_phase(server_log, server, "processor-fixture",
                    "const rules=Vars.state.rules; rules.worldProcessorPlayerLink=true; "
                    "rules.allowEditWorldProcessors=true; "
                    f"Vars.world.tile({PROCESSOR_CANVAS_COLUMN},32).setBlock(Blocks.worldProcessor,rules.defaultTeam);")
            if args.verify_puddles:
                send_marker_phase(server_log, server, "puddles-clear", PUDDLE_SETUP)
            if args.verify_logic_displays:
                send_marker_phase(server_log, server, "display-fixture",
                    "const rules=Vars.state.rules; rules.worldProcessorPlayerLink=true; "
                    "rules.allowEditWorldProcessors=true; const team=rules.defaultTeam; "
                    "Vars.world.tile(77,26).setBlock(Blocks.worldProcessor,team); "
                    "Vars.world.tile(77,24).setBlock(Blocks.logicDisplay,team); "
                    "Vars.world.tile(86,29).setBlock(Blocks.largeLogicDisplay,team); "
                    "Vars.world.tile(76,22).setBlock(Blocks.tileLogicDisplay,team); "
                    "Vars.world.tile(77,22).setBlock(Blocks.tileLogicDisplay,team); "
                    'if(Vars.world.tile(81,26).block()!==Blocks.coreNucleus) throw "Display fixture touched the core";')
            if args.verify_display_markers:
                send_marker_phase(server_log, server, "display-marker-fixture",
                    "const rules=Vars.state.rules; rules.worldProcessorPlayerLink=true; "
                    "rules.allowEditWorldProcessors=true; const team=rules.defaultTeam; "
                    "Vars.world.tile(46,23).setBlock(Blocks.worldProcessor,team); "
                    "Vars.world.tile(45,27).setBlock(Blocks.logicDisplay,team);")
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
            if args.verify_objectives or args.verify_objective_hud or args.verify_objective_markers:
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
            if args.combat:
                # Remove the enemy production base and all turrets before the client joins,
                # so stock block snapshots cannot starve the UnitSpawnCallPacket stream.
                prep = (
                    '(() => { var units = []; for (var i = 0; i < Groups.unit.size; i++) '
                    'units.push(Groups.unit.get(i)); '
                    'for (var j = 0; j < units.length; j++) { var u = units[j]; '
                    'if (u.team == Vars.state.rules.waveTeam) u.remove(); } '
                    'var builds = []; for (var k = 0; k < Groups.build.size; k++) '
                    'builds.push(Groups.build.get(k)); '
                    'for (var m = 0; m < builds.length; m++) { var b = builds[m]; '
                    'if (b.block instanceof Packages.mindustry.world.blocks.defense.turrets.BaseTurret '
                    '|| (b.team == Vars.state.rules.waveTeam '
                    '&& !(b.block instanceof Packages.mindustry.world.blocks.storage.CoreBlock))) b.kill(); } '
                    'return "UPSTREAM_COMBAT_PREP"; })()')
                server.stdin.write("js " + prep + "\n")
                server.stdin.flush()
                wait_for(server_log, server, r"UPSTREAM_COMBAT_PREP", 10)
            command = [str(args.azahar)]
            if args.controls_replay or args.unified_boot or args.verify_chat:
                replay = Path(work) / "controls.ctm"
                first_move = 42 if args.terrain_atlas else 22
                if args.building and args.desktop_name:
                    first_move = 120  # Allow time to inspect and place a block in the desktop client.
                if args.touch_build:
                    first_move = 101
                if args.touch_config:
                    first_move = 154 if args.verify_stock_menu_font else 92
                write_controls_replay(replay, first_move=first_move, building=args.building,
                                      touch_build=args.touch_build, touch_config=args.touch_config,
                                      erekir_build=args.erekir_build, mining=args.mining, model=model,
                                      stock_menu_font=args.verify_stock_menu_font, unified_boot=args.unified_boot,
                                      stationary=not args.controls_replay, chat=args.verify_chat,
                                      cancel_restart=args.server_kick == "restart-cancel")
                command += ["-p", str(replay)]
            command.append(str(args.rom))
            process_log = output / f"{model}-azahar-process.log"
            with process_log.open("wb") as terminal:
                emulator = subprocess.Popen(command, stdout=terminal, stderr=subprocess.STDOUT)
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                if args.unified_boot:
                    try:
                        wait_for(log, emulator, r"UPSTREAM_BOOT mode=join\b", args.timeout)
                    except RuntimeError:
                        print(process_log.read_text(encoding="utf-8", errors="replace")[-2500:], file=sys.stderr)
                        raise
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
                                                frame_timeout=25 if args.verify_stock_font else 5,
                                                spawn_pixels=(MAP_SPAWN_PIXELS.get(args.map_spawn) if phase == "spawn"
                                                              else EREKIR_BUILD_PIXELS.get(phase)))

                    if args.server_kick:
                        wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", args.timeout)
                        restart = args.server_kick in ("restart", "restart-cancel")
                        reason = "Hosted stock kick" if args.server_kick == "text" else "serverRestarting" if restart else "kick"
                        kind = "enum" if restart else args.server_kick
                        wire_reason = (json.dumps(reason) if args.server_kick == "text"
                                       else f"Packages.mindustry.net.Packets.KickReason.{reason}")
                        server.stdin.write(
                            f'js (() => {{ const player = Groups.player.find(boolf(p => String(p.name) === {json.dumps(PLAYER)})); '
                            f'player.con.kick({wire_reason}, 0); }})()\n'
                        )
                        server.stdin.flush()
                        wait_for(server_log, server, rf"Kicking connection .*Reason: {re.escape(reason)}", args.timeout)
                        wait_for(log, emulator,
                                 rf"UPSTREAM_LIVE_CONTROLS_KICK kind={kind} reason={re.escape(reason)}",
                                 args.timeout)
                        emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_CONTROLS_LEFT", args.timeout)
                        if restart:
                            wait_for(log, emulator, "UPSTREAM_LIVE_RECONNECT_WAIT", args.timeout)
                            server.stdin.write("stop\n")
                            server.stdin.flush()
                            wait_for(server_log, server, "Stopped server.", 10)
                        if args.server_kick == "restart-cancel":
                            emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_RECONNECT_CANCEL", args.timeout)
                            if "UPSTREAM_LIVE_NET_WORLD join=2" in emu_log:
                                raise RuntimeError("Cancelling reconnect unexpectedly opened a second session")
                        elif restart:
                            time.sleep(2)
                            server.stdin.write("host Glacier survival\n")
                            server.stdin.flush()
                            wait_for(log, emulator, r"UPSTREAM_LIVE_NET_WORLD join=2\b", args.timeout)
                            wait_for(server_log, server,
                                     rf"{PLAYER} has connected[\s\S]*{PLAYER} has connected", args.timeout)
                            server.stdin.write(
                                f'js (() => {{ const player = Groups.player.find(boolf(p => String(p.name) === {json.dumps(PLAYER)})); '
                                'player.con.kick("Hosted stop after reconnect", 0); })()\n'
                            )
                            server.stdin.flush()
                            emu_log = wait_for(log, emulator,
                                r"UPSTREAM_LIVE_CONTROLS_KICK kind=text reason=Hosted stop after reconnect"
                                r"[\s\S]*UPSTREAM_LIVE_CONTROLS_LEFT", args.timeout)
                        if args.verify_kick_notice:
                            message = reason if args.server_kick == "text" else "You have been kicked from the server!"
                            emu_log = wait_for(log, emulator, re.escape(
                                f"UPSTREAM_LIVE_KICK_NOTICE title=Disconnected. detail={message}"), args.timeout)
                            (output / f"{model}-kick-notice.png").write_bytes(rpc.screenshot())
                    elif args.combat:
                        wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", args.timeout)
                        status = wait_for(log, emulator,
                                          r"UPSTREAM_STATUS_HUD wave=\d+ enemies=\d+ copper=\d+ rows=5", args.timeout)
                        print(re.search(r"UPSTREAM_STATUS_HUD wave=\d+ enemies=\d+ copper=\d+ rows=5", status).group(0),
                              flush=True)
                        fixture = (
                            '(() => { for (var i = 0; i < Groups.player.size; i++) { var p = Groups.player.get(i); if (p.unit() != null) p.unit().health = 100000; } for (var x = 75; x <= 80; x++) for (var y = 24; y <= 28; y++) { '
                            'var tile = Vars.world.tile(x, y); tile.setBlock(Blocks.air); '
                            'tile.setFloor(Blocks.ice); tile.setOverlay(Blocks.air); } '
                            'const t = Vars.world.tile(76, 26); '
                            't.setBlock(Blocks.duo, Vars.state.rules.defaultTeam, 0); '
                            'const b = t.build; for (let i = 0; i < 10; i++) b.handleItem(b, Items.copper); '
                            'return "UPSTREAM_COMBAT_FIXTURE block=" + t.block().name + " ammo=" + b.totalAmmo; })()')
                        server.stdin.write("js " + fixture + "\n")
                        server.stdin.flush()
                        setup = wait_for(server_log, server,
                                         r"UPSTREAM_COMBAT_FIXTURE block=\S+ ammo=(\d+)", 10)
                        initial_ammo = int(re.search(r"ammo=(\d+)", setup).group(1))
                        post = (
                            '(() => { const u = UnitTypes.dagger.spawn(Vars.state.rules.waveTeam, 79 * 8, 26 * 8); '
                            'u.health = 9; '
                            'Packages.mindustry.entities.Units.notifyUnitSpawn(u); '
                            'return "UPSTREAM_COMBAT_POST id=" + u.id; })()')
                        server.stdin.write("js " + post + "\n")
                        server.stdin.flush()
                        post_result = wait_for(server_log, server,
                                               r"UPSTREAM_COMBAT_POST id=(\d+)", 10)
                        post_id = int(re.search(r"UPSTREAM_COMBAT_POST id=(\d+)", post_result).group(1))
                        spawn_packet = wait_for(
                            log, emulator, rf"UPSTREAM_LIVE_UNIT_SPAWN id={post_id}\b", args.timeout)
                        print(re.search(rf"UPSTREAM_LIVE_UNIT_SPAWN id={post_id}\b[^\n]*",
                                        spawn_packet).group(0), flush=True)
                        enemy = wait_for(log, emulator,
                                         rf"UPSTREAM_LIVE_ENEMY type=dagger id={post_id}\b", args.timeout)
                        enemy_line = re.search(
                            rf"UPSTREAM_LIVE_ENEMY type=dagger id={post_id}\b[^\n]*", enemy)
                        if not enemy_line:
                            raise RuntimeError(
                                f"Could not isolate the post-join Dagger id={post_id} in the client log")
                        print(enemy_line.group(0), flush=True)
                        dagger, ammo = -1, initial_ammo
                        query_seq = 0
                        deadline = time.monotonic() + 20
                        while time.monotonic() < deadline:
                            query_seq += 1
                            query = (
                                f'(() => {{ const u = Groups.unit.find(x => x.id === {post_id}); '
                                'const b = Vars.world.tile(76, 26).build; '
                                f'return "UPSTREAM_SERVER_COMBAT seq={query_seq} dagger=" '
                                '+ (u ? Math.round(u.health) : -1) + " ammo=" + b.totalAmmo; })()')
                            server.stdin.write("js " + query + "\n")
                            server.stdin.flush()
                            result = wait_for(
                                server_log, server,
                                rf"UPSTREAM_SERVER_COMBAT seq={query_seq} dagger=(-?\d+) ammo=(\d+)", 10)
                            dagger, ammo = (int(value) for value in
                                            re.search(rf"seq={query_seq} dagger=(-?\d+) ammo=(\d+)",
                                                      result).groups())
                            if dagger == -1:
                                break
                            time.sleep(1.0)
                        if initial_ammo - ammo < 1 or dagger != -1:
                            raise RuntimeError(
                                f"Stock Duo did not fire and destroy the post-join Dagger: "
                                f"ammo {initial_ammo}->{ammo}, health={dagger}")
                        # UnitDestroyCallPacket is sent unreliably by the stock server.
                        # Re-send the identical packet reliably to verify the client handler.
                        destroy = (
                            f'(() => {{ const p = new Packages.mindustry.gen.UnitDestroyCallPacket(); '
                            f'p.uid = {post_id}; Vars.net.send(p, false); '
                            'return "UPSTREAM_COMBAT_DESTROY_SENT"; })()')
                        server.stdin.write("js " + destroy + "\n")
                        server.stdin.flush()
                        wait_for(server_log, server, r"UPSTREAM_COMBAT_DESTROY_SENT", 10)
                        removed = wait_for(log, emulator,
                                           rf"(?:UPSTREAM_LIVE_ENEMY_GONE|UPSTREAM_LIVE_UNIT_(?:REMOVED|DESTROY|DESPAWN)) id={post_id}\b",
                                           args.timeout)
                        print(re.search(rf"(?:UPSTREAM_LIVE_ENEMY_GONE|UPSTREAM_LIVE_UNIT_(?:REMOVED|DESTROY|DESPAWN)) id={post_id}\b[^\n]*",
                                        removed).group(0), flush=True)
                        (output / f"{model}-combat.png").write_bytes(rpc.screenshot())
                        print(f"Stock Duo destroyed the post-join Dagger; ammo {initial_ammo}->{ammo}.",
                              flush=True)
                        emu_log = log.read_text(encoding="utf-8", errors="replace")
                        if "UPSTREAM_LIVE_NET_ERROR" in emu_log:
                            raise RuntimeError("The client failed during the live combat check")
                    elif stationary:
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
                        if args.verify_logic_markers:
                            verify_logic_markers(log, emulator, server_log, server, rpc, output, model, spawn, args.timeout)
                        if args.verify_texture_markers:
                            verify_texture_markers(server_log, server, rpc, output, model, spawn, args.timeout)
                        if args.verify_quad_markers:
                            verify_quad_markers(log, emulator, server_log, server, rpc, output, model, spawn, args.timeout)
                        if args.verify_stock_font:
                            wait_for(log, emulator, r"UPSTREAM_RENDER_KEEPALIVE transport=tcp\b", 5)
                        if args.verify_canvas:
                            verify_canvas(server_log, server, rpc, output, model, args.timeout)
                        if args.verify_weather_layers:
                            verify_stock_weather_layers(log, emulator, server_log, server, rpc, output,
                                                        model, args.timeout)
                        if args.verify_chat:
                            verify_stock_chat(log, emulator, server_log, server, rpc, output, model, args.timeout)
                        if args.verify_canvas_quads:
                            verify_canvas_quads(log, emulator, server_log, server, rpc, output, model, spawn, args.timeout)
                        if args.verify_client_processor:
                            verify_client_processor(server_log, server, rpc, output, model, args.timeout)
                        if args.verify_puddles:
                            verify_stock_puddles(server_log, server, rpc, output, model, args.timeout)
                        if args.verify_logic_displays:
                            verify_logic_displays(server_log, server, rpc, output, model, args.timeout)
                        if args.verify_display_markers:
                            verify_display_markers(server_log, server, rpc, output, model, args.timeout)
                        if args.erekir_build:
                            verify_erekir_construction(log, emulator, server_log, server, args.timeout, capture, spawn)
                        if args.mining:
                            verify_mining(log, emulator, server_log, server, args.timeout, capture,
                                          args.mining, mine_item, args.verify_objectives)
                        if args.verify_objective_hud or args.verify_objective_markers:
                            def capture_marker(*marker):
                                capture_onset_marker(log, emulator, server, rpc, output, model,
                                                     args.timeout if args.verify_stock_font else 0,
                                                     args.verify_styled_markers, *marker)
                            if args.verify_objective_hud:
                                verify_onset_objective_hud(log, emulator, server_log, server, capture, spawn,
                                                           capture_marker if args.verify_objective_markers else None,
                                                           args.timeout if args.verify_stock_hud else 0)
                            else:
                                mining, research = ONSET_MARKER_TEXT[args.locale]
                                if args.verify_stock_hud:
                                    require_stock_objective_font(log, emulator, "beryllium", args.timeout)
                                capture_marker("mine", 2320, 864, 868, mining)
                                set_onset_beryllium(server, 60)
                                verify_onset_objective(log, emulator, server_log, server, 60, args.timeout)
                                if args.verify_stock_hud:
                                    require_stock_objective_font(log, emulator, "turbine-condenser", args.timeout)
                                capture_marker("research", 2312, 944, 957.8, research)
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
                            roster = (output / f"{model}-{phase}.png").read_bytes()
                            verify_unit_roster(roster)
                            if args.expanded_unit_roster:
                                verify_expanded_unit_roster(log, emulator, server_log, server, rpc, roster,
                                                            output / f"{model}-expanded-unit-roster.png", args.timeout)
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
                                        args.touch_build, args.touch_config, args.menu_icons,
                                        args.verify_stock_menu_font)
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
                stop(emulator, str(args.azahar))
                (output / f"{model}-emulator.log").write_bytes(log.read_bytes())
            if args.require_deferred_graphics:
                trace = log.read_text(encoding="utf-8", errors="replace")
                decoded = trace.find("UPSTREAM_WORLD_DECODED beforeGraphics=true")
                graphics = trace.find("UPSTREAM_INTERFACE_GRAPHICS atlas")
                if not 0 <= decoded < graphics:
                    raise RuntimeError("Expected WorldStream decode to finish before graphics allocation")
            if not args.controls:
                validate(emu_log)
            disconnected = rf"{PLAYER} has disconnected"
            if not args.controls or args.server_kick == "restart":
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
            upstream = re.sub(r"\x1b\[[0-9;]*m", "", server_log.read_text(encoding="utf-8", errors="replace"))
            departures = [line for line in upstream.splitlines() if f"{PLAYER} has disconnected" in line]
            # NetServer.sync can observe the closed transport before the disconnect event runs.
            # The stationary render check ends by terminating the emulator process.
            departure = r"\((closed|disappeared|error)\)" if stationary else r"\((closed|disappeared)\)"
            expected_departures = all(re.search(departure, line) for line in departures)
            if stationary and any("(error)" in line for line in departures):
                print(f"{model}: stationary verification ended by emulator kill; "
                      "accepted the server's (error) departure", flush=True)
            expected_joins = 1 if args.controls and args.server_kick != "restart" else 2
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
    parser.add_argument("--server-kick", choices=("text", "enum", "restart", "restart-cancel"),
                        help="Verify a stock server kick after entering live play")
    parser.add_argument("--verify-kick-notice", action="store_true",
                        help="Check and capture the English stock disconnect notice")
    parser.add_argument("--controls-replay", action="store_true",
                        help="Replay the controls test input in Azahar 2126.2")
    parser.add_argument("--unified-boot", action="store_true",
                        help="Hold X at startup to join from the unified offline and multiplayer ROM")
    parser.add_argument("--terrain-atlas", type=Path,
                        help="Stage the official v160.8 sprites.aatls and verify the rendered viewport")
    parser.add_argument("--locale", help="Stage a stock locale code and verify the graphical probe loads it")
    parser.add_argument("--menu-icons", action="store_true", help="Verify original block and item icons in the touch menus")
    parser.add_argument("--verify-stock-menu-font", action="store_true",
                        help="Require the original font on the touch palette and both configuration menus")
    parser.add_argument("--world-atlas", action="store_true", help="Verify the paged Core Nucleus sprite at Glacier spawn")
    parser.add_argument("--world-atlas-recycle", action="store_true",
                        help="Require the bounded atlas cache to submit and recycle inside the spawn frame")
    parser.add_argument("--combat", action="store_true",
                        help="Verify stock turret damage and the post-join unit spawn packet")
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
    parser.add_argument("--expanded-unit-roster", action="store_true",
                        help="Verify live Elude, Anthicus missile and carried Horizon art after the standard unit-roster checks")
    parser.add_argument("--verify-objectives", action="store_true",
                        help="Compare the original first Onset objective transition between client and server")
    parser.add_argument("--verify-objective-hud", action="store_true",
                        help="Verify the rendered Onset objective labels as the stock progress changes")
    parser.add_argument("--verify-objective-markers", action="store_true",
                        help="Verify the original Onset mining and research directions in world space")
    parser.add_argument("--verify-logic-markers", action="store_true",
                        help="Verify stock logic marker packets and visible state on Glacier")
    parser.add_argument("--verify-texture-markers", action="store_true",
                        help="Verify original atlas-backed texture markers on Glacier")
    parser.add_argument("--verify-quad-markers", action="store_true",
                        help="Verify clipped stock textured quads and live corner updates on Glacier")
    parser.add_argument("--verify-stock-font", action="store_true",
                        help="Require original font rendering for the Onset world markers")
    parser.add_argument("--verify-styled-markers", action="store_true",
                        help="Require the original inline item and block art in the Onset markers")
    parser.add_argument("--verify-stock-hud", action="store_true",
                        help="Require the original font on the campaign objective HUD")
    parser.add_argument("--verify-canvas", action="store_true",
                        help="Verify original Canvas pixels in Glacier snapshots, configuration and linked markers")
    parser.add_argument("--verify-weather-layers", action="store_true",
                        help="Capture the stock weather over the stationary Glacier viewport")
    parser.add_argument("--verify-chat", action="store_true",
                        help="Verify original server and player chat over the stationary Glacier viewport")
    parser.add_argument("--verify-canvas-quads", action="store_true",
                        help="Verify stock quads backed by an offscreen Canvas on Glacier")
    parser.add_argument("--verify-client-processor", action="store_true",
                        help="Verify a stock processor writes to its linked Canvas on the client")
    parser.add_argument("--verify-puddles", action="store_true",
                        help="Verify water and neoplasm in stock Glacier entity snapshots")
    parser.add_argument("--verify-logic-displays", action="store_true",
                        help="Verify normal, large, and tileable Logic Displays on Glacier")
    parser.add_argument("--verify-display-markers", action="store_true",
                        help="Verify stock markers sampling an offscreen Logic Display on Glacier")
    parser.add_argument("--require-deferred-graphics", action="store_true",
                        help="Require WorldStream decode to finish before loading the renderer atlas")
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
    if args.server_kick and (not args.controls or args.controls_replay):
        parser.error("--server-kick needs stationary --controls")
    if args.verify_kick_notice and (args.server_kick not in ("text", "enum") or args.locale != "en"):
        parser.error("--verify-kick-notice needs --server-kick and --locale en")
    if args.unified_boot and not args.controls:
        parser.error("--unified-boot needs --controls")
    if args.combat and (not args.controls or not args.terrain_atlas):
        parser.error("--combat needs --controls and --terrain-atlas")
    if args.combat and (args.controls_replay or args.building or args.touch_build or args.touch_config
                        or args.world_atlas or args.world_atlas_recycle or args.menu_icons
                        or args.building_layers or args.building_cracks or args.liquids
                        or args.building_effects or args.payload_mix or args.carried_buildings
                        or args.payload_handoff or args.unit_layers or args.unit_roster
                        or args.naval_wake or args.tank_treads or args.map_spawn):
        parser.error("--combat runs a separate stationary check")
    if args.building and not args.terrain_atlas:
        parser.error("--building needs --terrain-atlas")
    if args.touch_build and not args.terrain_atlas:
        parser.error("--touch-build needs --terrain-atlas")
    if args.touch_config and not args.terrain_atlas:
        parser.error("--touch-config needs --terrain-atlas")
    if args.menu_icons and not args.touch_config:
        parser.error("--menu-icons needs --touch-config")
    if args.verify_stock_menu_font and not args.touch_config:
        parser.error("--verify-stock-menu-font needs --touch-config")
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
    if args.expanded_unit_roster and not args.unit_roster:
        parser.error("--expanded-unit-roster needs --unit-roster")
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
    if args.verify_objective_hud and (
        args.map_spawn != "The Onset" or args.erekir_sector != "onset" or args.locale != "en"
        or args.mining or args.erekir_build or args.controls_replay
    ):
        parser.error('--verify-objective-hud needs a stationary --map-spawn "The Onset", '
                     '--erekir-sector onset and --locale en')
    if args.verify_logic_markers and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-logic-markers needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_texture_markers and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-texture-markers needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_quad_markers and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-quad-markers needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_objective_markers and (
        args.map_spawn != "The Onset" or args.erekir_sector != "onset" or args.locale not in ONSET_MARKER_TEXT
        or args.mining or args.erekir_build or args.controls_replay
    ):
        parser.error('--verify-objective-markers needs a stationary --map-spawn "The Onset", '
                     '--erekir-sector onset and --locale en or ja')
    if args.verify_stock_font and not args.verify_objective_markers:
        parser.error("--verify-stock-font needs --verify-objective-markers")
    if args.verify_styled_markers and not args.verify_stock_font:
        parser.error("--verify-styled-markers needs --verify-stock-font")
    if args.verify_stock_hud and not (args.verify_objective_hud or args.verify_objective_markers):
        parser.error("--verify-stock-hud needs --verify-objective-hud or --verify-objective-markers")
    if args.verify_canvas and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-canvas needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_weather_layers and (not args.controls or not args.terrain_atlas
                                       or args.controls_replay or args.map_spawn):
        parser.error("--verify-weather-layers needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_chat and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-chat needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_canvas_quads and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-canvas-quads needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_client_processor and (not args.controls or not args.terrain_atlas or args.controls_replay
                                        or args.map_spawn or args.verify_canvas):
        parser.error("--verify-client-processor needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_puddles and (not args.controls or not args.terrain_atlas or args.controls_replay or args.map_spawn):
        parser.error("--verify-puddles needs stationary --controls and --terrain-atlas on Glacier")
    if args.verify_logic_displays and (not args.controls or not args.terrain_atlas or args.controls_replay
                                       or args.map_spawn or args.verify_canvas or args.verify_canvas_quads
                                       or args.verify_client_processor):
        parser.error("--verify-logic-displays needs a separate stationary --controls and --terrain-atlas run on Glacier")
    if args.verify_display_markers and (not args.controls or not args.terrain_atlas or args.controls_replay
                                        or args.map_spawn or args.verify_canvas_quads):
        parser.error("--verify-display-markers needs stationary --controls and --terrain-atlas on Glacier")
    if args.erekir_sector is not None:
        if not args.map_spawn or not re.fullmatch(r"[a-z][a-z-]*", args.erekir_sector):
            parser.error("--erekir-sector needs --map-spawn and a sector slug such as onset")
        with zipfile.ZipFile(args.server) as release:
            if f"maps/erekir/{args.erekir_sector}.msav" not in release.namelist():
                parser.error(f"No Erekir sector {args.erekir_sector!r} in the official server jar")
    if args.require_deferred_graphics and not args.terrain_atlas:
        parser.error("--require-deferred-graphics needs --terrain-atlas")
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
    config, log = az.resolve_azahar_paths(str(args.azahar))
    endpoint = log.parent.parent / "sdmc/3ds/min3dstry/server-endpoint.txt"
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log, endpoint)}
    atlas_path = endpoint.with_name("sprites.aatls")
    locale_path = endpoint.with_name("locale.txt")
    if args.locale:
        originals[locale_path] = locale_path.read_bytes() if locale_path.exists() else None
    if args.terrain_atlas:
        originals[atlas_path] = atlas_path.read_bytes() if atlas_path.exists() else None
    output = args.rom.parent / "upstream-live-net"
    if args.verify_objective_markers and args.locale != "en":
        output /= args.locale
    output.mkdir(parents=True, exist_ok=True)
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
