#!/usr/bin/env python3
"""Compare a captured server WorldStream with ARM11 results in Azahar."""

import argparse
import hashlib
import subprocess
import time
from pathlib import Path

import verify_azahar as az

parser = argparse.ArgumentParser()
parser.add_argument('--rom', type=Path, required=True)
parser.add_argument('--azahar', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--reference', required=True)
parser.add_argument('--model', choices=('new3ds', 'old3ds'), default='new3ds')
args = parser.parse_args()
config, log = az.resolve_azahar_paths()
fixture = config.parent.parent / 'sdmc/3ds/min3dstry/glacier-world-stream.bin'
paths = [fixture, log, config]
before = {p: p.read_bytes() if p.exists() else None for p in paths}
try:
    fixture.parent.mkdir(parents=True, exist_ok=True)
    config.parent.mkdir(parents=True, exist_ok=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    contents = args.fixture.read_bytes()
    fixture.write_bytes(contents)
    print('Fixture SHA-256:', hashlib.sha256(contents).hexdigest(), flush=True)
    for model in (args.model,):
        overrides = {
            'Miscellaneous': {'log_filter': '*:Info Debug.Emulated:Debug', 'check_for_update_on_start': 'false'},
            'Debugging': {'enable_rpc_server': 'true', 'instant_debug_log': 'true'},
            'UI': {'firstStart': 'false', 'confirmClose': 'false', 'pause_when_in_background': 'false'},
            'System': {'is_new_3ds': 'true' if model == 'new3ds' else 'false'},
            'Renderer': {'graphics_api': '2'},
            'Data Storage': {'use_virtual_sd': 'true'},
        }
        config.write_text(az.patch_qt_config_text((before[config] or b'').decode(), overrides), encoding='utf-8')
        log.write_bytes(b'')
        with subprocess.Popen([str(args.azahar), str(args.rom)], stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL) as proc:
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    lines = log.read_text(encoding='utf-8', errors='replace').splitlines()
                    if proc.poll() is not None or any('Core::LogException' in line for line in lines):
                        raise RuntimeError(f'{model} emulator stopped')
                    for line in lines:
                        if 'UPSTREAM_WORLD_STREAM ' in line:
                            observed = line[line.index('UPSTREAM_WORLD_STREAM '):].strip()
                            if observed != args.reference:
                                raise RuntimeError(f'{model}: {observed} differs from JVM')
                            print(f'{model}: {observed}', flush=True)
                            time.sleep(.15)
                            (args.rom.parent / f'{model}-stream.png').write_bytes(rpc.screenshot())
                            break
                    else:
                        time.sleep(.05)
                        continue
                    break
                else:
                    raise RuntimeError(f'{model} no world snapshot output')
            finally:
                rpc.close()
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)
                (args.rom.parent / f'{model}-stream.log').write_bytes(log.read_bytes())
finally:
    for path, data in before.items():
        if data is None:
            path.unlink(missing_ok=True)
        else:
            path.write_bytes(data)
