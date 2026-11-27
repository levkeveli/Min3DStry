"""Edit original processors from the New 3DS palette and check the stock server."""

import argparse
import json
from pathlib import Path
import time

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop


PROGRAM = "# New 3DS stock edit\ngetlink canvas 0\nwrite 5 canvas 0\nend\n"
SETUP = (
    "const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; "
    "Vars.world.tile(74,29).setBlock(Blocks.microProcessor,team); "
    "Vars.world.tile(78,29).setBlock(Blocks.canvas,team); "
    "Vars.world.tile(78,27).setBlock(Blocks.memoryCell,team); "
    "const type=Packages.mindustry.world.blocks.logic.LogicBlock; "
    "const links=new Seq(); links.add(new type.LogicLink(4,0,'canvas1',true)); "
    "const p=Vars.world.build(74,29); "
    "p.configured(null,type.compress('getlink canvas 0\\nwrite 1 canvas 0\\nend\\n',links)); "
    "if(p.executor.links.length!==1 || !p.validLink(Vars.world.build(78,27))) "
    "throw 'Invalid original processor fixture'; "
    "return 'STOCK_PROCESSOR_EDITOR_READY';"
)
RESULT = (
    "const p=Vars.world.build(74,29), canvas=Vars.world.build(78,29), memory=Vars.world.build(78,27); "
    "const asm=Packages.mindustry.logic.LAssembler; "
    f"const instructions=asm.read({json.dumps(PROGRAM)},false); "
    "instructions.add(instructions.peek().copy()); "
    "if(String(p.code)!==String(asm.write(instructions)) || p.executor.links.length!==2 "
    "|| p.executor.links[0]!==canvas || p.executor.links[1]!==memory || canvas.getPixel(0)!==5) "
    "throw 'Edited source, stock links, or Canvas output differs'; "
    "return 'STOCK_PROCESSOR_EDITOR_RESULT source=exact links=2 canvas=5';"
)

TAPS = {
    38: (305, 64), 44: (56, 88), 50: (160, 81),
    58: (305, 64), 64: (56, 88), 70: (160, 106), 76: (160, 131),
    82: (120, 120), 88: (305, 64),
    # Open the instruction list, copy the End statement, and apply the new program.
    94: (305, 64), 100: (56, 88), 106: (160, 156),
    114: (160, 156), 122: (160, 81), 130: (160, 56),
}


def fixture(args):
    directory = args.output / "stock-fixture"
    server, log = stock_session(args, directory)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STOCK_PROCESSOR_EDITOR_READY")
        stock_command(server, log, "save processor-editor", r"Saved to .*processor-editor\.msav")
    finally:
        stop(server)
    return directory / "config/saves/processor-editor.msav"


def setup_sd(root):
    (root / "startup.txt").write_text("direct\n", encoding="ascii")
    (root / "logic-clipboard.mlog").write_text(PROGRAM, encoding="utf-8", newline="\n")


def capture_editor(rpc, destination, *, instructions=False):
    # Sample both sides of each row inside the menu. The left build sidebar
    # shares the menu's panel color and cannot distinguish the two screens.
    panel = (45, 52, 64)
    background = (24, 27, 32)
    first_row = ((94, 290), (304, 290))
    common_rows = tuple((x, y) for x in (94, 304) for y in (312, 361))
    expected = dict.fromkeys(common_rows, panel)
    expected.update(dict.fromkeys(first_row, background if instructions else panel))
    deadline = time.monotonic() + 5
    while True:
        screenshot = rpc.screenshot(res_scale=1)
        pixels = sp.az.inspect_png(screenshot, tuple(expected))["samples"]
        if pixels == expected:
            destination.write_bytes(screenshot)
            return
        if time.monotonic() >= deadline:
            destination.write_bytes(screenshot)
            raise AssertionError(f"Processor menu was not visible in {destination}: {pixels}")
        time.sleep(0.1)


def inspect_editor(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_PROCESSOR_CONFIG_OPEN x=74 y=29\b", 75)
    capture_editor(rpc, output / "processor-config.png")
    sp._wait_for_log(log, proc, r"UPSTREAM_PROCESSOR_EDIT x=74 y=29 bytes=[1-9]\d*", 40)
    clipboard = output / "sdmc/3ds/min3dstry/logic-clipboard.mlog"
    clipboard.unlink()
    sp._wait_for_log(log, proc, r"UPSTREAM_PROCESSOR_EXPORT file=logic-clipboard\.mlog", 35)
    if clipboard.read_bytes() != PROGRAM.encode("utf-8"):
        raise AssertionError("Exported processor program differs from the imported stock source")
    sp._wait_for_log(log, proc, r"UPSTREAM_PROCESSOR_LINK x=78 y=27\b", 30)
    sp._wait_for_log(log, proc, r"UPSTREAM_PROCESSOR_INSTRUCTIONS count=3\b", 35)
    capture_editor(rpc, output / "processor-instructions.png", instructions=True)
    sp._wait_for_log(log, proc,
                     r"(?s)UPSTREAM_PROCESSOR_INSTRUCTIONS count=3\b.*?UPSTREAM_PROCESSOR_EDIT x=74 y=29 bytes=[1-9]\d*",
                     40)


def inspect_stock(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()",
                  "STOCK_PROCESSOR_EDITOR_RESULT source=exact links=2 canvas=5")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16687)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    save = fixture(args)
    for live in (False, True):
        buttons = {0: sp.KEY_X, 152: sp.KEY_START} if live else {142: sp.KEY_SELECT, 152: sp.KEY_START}
        replay(args, "new3ds", save, live, taps=TAPS, buttons=buttons, button_durations={152: 5},
               movie_seconds=162,
               prepare_sd=setup_sd, verify_input=inspect_editor, read_stock=inspect_stock)
        print(f"UPSTREAM_PROCESSOR_EDITOR model=new3ds live={live} source=exact links=2 canvas=5", flush=True)


if __name__ == "__main__":
    main()
