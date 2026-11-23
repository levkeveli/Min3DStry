#!/usr/bin/env python3
"""Check two independent ARM11 profiles against one strict stock server."""

import argparse
from contextlib import ExitStack, contextmanager
from pathlib import Path
import subprocess

from verify_upstream_live_net import stop, wait_for
from verify_azahar import patch_qt_config_text


@contextmanager
def profile(model, emulator, work, name, port):
    # Each installed test instance has its own portable data folder.
    user = emulator.parent / "user"
    if not user.is_dir():
        raise ValueError(f"Expected an isolated portable Azahar data directory beside {emulator}")
    config = user / "config/qt-config.ini"
    log = user / "log/azahar_log.txt"
    previous = {file: file.read_bytes() if file.exists() else None for file in (config, log)}
    sd = (work / "virtual-sd").resolve()
    directory = sd / "3ds/min3dstry"
    directory.mkdir(parents=True)
    (directory / "player-name.txt").write_text(name + "\n", encoding="utf-8")
    (directory / "server-endpoint.txt").write_text(f"127.0.0.1 {port} controls\n", encoding="ascii")
    config.parent.mkdir(parents=True, exist_ok=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(patch_qt_config_text((previous[config] or b"").decode("utf-8"), {
        "Data Storage": {"use_custom_storage": "true", "use_virtual_sd": "true", "sdmc_directory": sd.as_posix() + "/"},
        "Miscellaneous": {"log_filter": "*:Info Debug.Emulated:Debug", "check_for_update_on_start": "false"},
        "Debugging": {"enable_rpc_server": "false", "instant_debug_log": "true"},
        "UI": {"firstStart": "false", "confirmClose": "false", "pause_when_in_background": "false"},
        "Renderer": {"graphics_api": "2"},
        "System": {"is_new_3ds": "true" if model == "new3ds" else "false"},
    }), encoding="utf-8")
    try:
        yield log, directory
    finally:
        for file, data in previous.items():
            if data is None:
                file.unlink(missing_ok=True)
            else:
                file.write_bytes(data)


def run_emulator(executable, rom, log):
    log.write_bytes(b"")
    return subprocess.Popen([str(executable), str(rom)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def server_marker(server, log, code, marker):
    server.stdin.write("js " + code + "\n")
    server.stdin.flush()
    wait_for(log, server, rf"\b{marker}\b", 20)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("rom", "server", "original-azahar", "new-azahar", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16691)
    args = parser.parse_args()
    if args.original_azahar.parent == args.new_azahar.parent:
        parser.error("Use separate portable Azahar directories")
    args.output.mkdir(parents=True, exist_ok=True)
    server_log = args.output / "server.log"
    with server_log.open("wb") as terminal, ExitStack() as stack:
        server = subprocess.Popen([args.java, "-jar", str(args.server)], cwd=args.output, stdin=subprocess.PIPE,
                                  stdout=terminal, stderr=subprocess.STDOUT, text=True)
        stack.callback(stop, server)
        server.stdin.write(f"config port {args.port}\nconfig strict true\nhost Glacier attack\n")
        server.stdin.flush()
        wait_for(server_log, server, rf"Opened a server on port {args.port}\.", 30)
        original_log, original_profile = stack.enter_context(
            profile("old3ds", args.original_azahar, args.output / "original", "Original ARM11", args.port)
        )
        new_log, _ = stack.enter_context(
            profile("new3ds", args.new_azahar, args.output / "new", "New ARM11", args.port)
        )
        original = run_emulator(args.original_azahar, args.rom, original_log)
        stack.callback(stop, original, str(args.original_azahar))
        wait_for(original_log, original, r"UPSTREAM_NETWORK_FRAME unit=\d+", 160)
        new = run_emulator(args.new_azahar, args.rom, new_log)
        stack.callback(stop, new, str(args.new_azahar))
        wait_for(new_log, new, r"UPSTREAM_NETWORK_FRAME unit=\d+", 160)
        both = """
            var old3ds = Groups.player.find(boolf(p => String(p.name)==='Original ARM11'));
            var new3ds = Groups.player.find(boolf(p => String(p.name)==='New ARM11'));
            if (!Vars.netServer.admins.isStrict() || Groups.player.size() !== 2 || old3ds === null ||
                new3ds === null || String(old3ds.uuid()) === String(new3ds.uuid()) ||
                String(old3ds.usid()) === String(new3ds.usid())) throw 'Unexpected strict server identity';
            var oldUuid = String(old3ds.uuid()), oldUsid = String(old3ds.usid());
            'STOCK_TWO_ARM11_PROFILES';
        """
        server_marker(server, server_log, " ".join(both.splitlines()), "STOCK_TWO_ARM11_PROFILES")
        server.stdin.write("runwave\n")
        server.stdin.flush()
        for log, client in ((original_log, original), (new_log, new)):
            wait_for(log, client, r"UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", 45)
        (args.output / "original-first.log").write_bytes(original_log.read_bytes())
        settings = original_profile / "settings.bin"
        persisted = settings.read_bytes()
        stop(original, str(args.original_azahar))
        wait_for(server_log, server, r"Original ARM11 has disconnected", 30)
        original = run_emulator(args.original_azahar, args.rom, original_log)
        stack.callback(stop, original, str(args.original_azahar))
        wait_for(original_log, original, r"UPSTREAM_NETWORK_FRAME unit=\d+", 160)
        repeated = """
            var rejoined = Groups.player.find(boolf(p => String(p.name)==='Original ARM11'));
            if (rejoined === null || Groups.player.size() !== 2 ||
                String(rejoined.uuid()) !== oldUuid || String(rejoined.usid()) !== oldUsid)
                throw 'Reconnection changed the previous stock profile';
            'STOCK_ARM11_PROFILE_REJOINED';
        """
        server_marker(server, server_log, " ".join(repeated.splitlines()), "STOCK_ARM11_PROFILE_REJOINED")
        if settings.read_bytes() != persisted:
            raise AssertionError("Reconnecting rewrote the stock settings values")
        (args.output / "original-rejoined.log").write_bytes(original_log.read_bytes())
        (args.output / "new.log").write_bytes(new_log.read_bytes())
        print("[PASS] Strict stock server accepted two ARM11 profiles and preserved IDs on rejoin")


if __name__ == "__main__":
    main()
