#!/usr/bin/env python3
"""Fetch the stock community directory over HTTPS from an empty New 3DS SD card."""

import argparse
import json
from pathlib import Path
import re
import time

import verify_upstream_singleplayer as sp
from verify_upstream_campaign_launch import write_input_replay


def verify(args):
    args.output.mkdir(parents=True)
    sd = (args.output / "sdmc").resolve()
    sd.mkdir()
    cache = sd / "3ds/min3dstry/server_list.json"
    movie = args.output / "new3ds-community-tls.ctm"
    # Play, Join Game, then wrap from Address past Remote Servers to Community Servers.
    windows = [(24, 27, 1), (33, 36, 1 << 7), (42, 45, 1),
               (54, 57, 1 << 6), (62, 65, 1 << 6), (70, 73, 1)]
    # A fixed old emulated date can be earlier than the current server certificates.
    write_input_replay(movie, "new3ds", windows, 170, b"Min3DStry Community TLS", rtc_start=int(time.time()))
    emulator = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(emulator)
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode(), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar("new3ds", args.rom, emulator, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_COMMUNITY_DIRECTORY cache=0\b", 150)
            outcome = r"UPSTREAM_COMMUNITY_DIRECTORY (?:fetched=\d+ source=\d+|refresh=failed cache=0)"
            transcript = sp._wait_for_log(log, proc, outcome, 75)
            result = re.search(r"UPSTREAM_COMMUNITY_DIRECTORY fetched=([1-9]\d*) source=(\d+)\b", transcript)
            if result is None:
                raise AssertionError("The handheld failed to fetch the official community directory over HTTPS")
            groups, source = map(int, result.groups())
            if source != args.expected_endpoint:
                raise AssertionError(f"Expected official directory endpoint {args.expected_endpoint}, got {source}")
            directory = json.loads(cache.read_bytes())
            if not isinstance(directory, list) or len(directory) != groups:
                raise AssertionError("The stock directory was not persisted to server_list.json")
            print(f"UPSTREAM_COMMUNITY_TLS_PASS model=new3ds groups={groups} endpoint={source}", flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, emulator)
            if log.is_file():
                (args.output / "new3ds-community-tls.log").write_bytes(log.read_bytes())
    finally:
        for path, original in originals.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-endpoint", type=int, choices=(0, 1), default=0,
                        help="Stock stable directory index expected in the handheld fetch log")
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
