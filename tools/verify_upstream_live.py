"""Verify the live Glacier fixture in both Azahar profiles on Windows."""

import argparse
import ctypes
from ctypes import wintypes
from contextlib import contextmanager
import json
from pathlib import Path
import re
import subprocess
import time

import verify_azahar as az

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--rom", required=True, type=Path)
parser.add_argument("--azahar", required=True, type=Path)
parser.add_argument("--glacier", required=True, type=Path)
parser.add_argument("--atlas", required=True, type=Path)
args = parser.parse_args()
if not hasattr(ctypes, "windll"):
    parser.error("The keyboard driver requires Windows")
binary, emulator, glacier, atlas = (p.resolve() for p in
                                    (args.rom, args.azahar, args.glacier, args.atlas))
for path in (binary, emulator, glacier, atlas):
    if not path.is_file():
        parser.error(f"File not found: {path}")

win = ctypes.windll.user32
callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
win.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
win.EnumWindows.argtypes = (callback_type, wintypes.LPARAM)
win.GetWindowThreadProcessId.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
win.IsWindowVisible.argtypes = (wintypes.HWND,)
win.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)


def find_window(pid):
    matches = []
    @callback_type
    def collect(window, data):
        owner = wintypes.DWORD()
        win.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value == pid and win.IsWindowVisible(window):
            title = ctypes.create_unicode_buffer(512)
            win.GetWindowTextW(window, title, len(title))
            if 'Azahar' in title.value:
                matches.append(window)
        return True
    win.EnumWindows(collect, 0)
    if not matches:
        raise RuntimeError('No visible Azahar window for the test process')
    return matches[0]


@contextmanager
def held(window, key):
    vk = ord(key) if isinstance(key, str) else key
    scan = win.MapVirtualKeyW(vk, 0)
    flags = 1 | (scan << 16)
    if vk == 0x26:  # Extended Windows virtual key for the Up arrow.
        flags |= 1 << 24
    if not win.PostMessageW(window, 0x100, vk, flags):
        raise RuntimeError(f'Could not queue {key} press')
    try:
        yield
    finally:
        if not win.PostMessageW(window, 0x101, vk, flags | (3 << 30)):
            raise RuntimeError(f'Could not queue {key} release')


def tap(window, key):
    with held(window, key):
        time.sleep(0.10)

config, log = az.resolve_azahar_paths()
sd = config.parent.parent / 'sdmc/3ds/min3dstry'
staged = {sd / 'glacier.msav': glacier, sd / 'sprites.aatls': atlas}
originals = {p: p.read_bytes() if p.exists() else None for p in [config, log, *staged]}
for path in [config, log, *staged]:
    path.parent.mkdir(parents=True, exist_ok=True)
report = []
try:
    for dest, src in staged.items():
        dest.write_bytes(src.read_bytes())
    for model in ('old3ds', 'new3ds'):
        overrides = {
            'Miscellaneous': {'log_filter': '*:Info Debug.Emulated:Debug', 'check_for_update_on_start': 'false'},
            'Debugging': {'enable_rpc_server': 'true', 'instant_debug_log': 'true'},
            'UI': {'firstStart': 'false', 'confirmClose': 'false', 'pause_when_in_background': 'false'},
            'System': {'is_new_3ds': 'true' if model == 'new3ds' else 'false'},
            'Renderer': {'graphics_api': '2'},
            'Data Storage': {'use_virtual_sd': 'true'},
        }
        config.write_text(az.patch_qt_config_text((originals[config] or b'').decode(), overrides), encoding='utf-8')
        log.write_bytes(b'')
        proc = subprocess.Popen([str(emulator), str(binary)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        rpc = az.AzaharRpcClient(timeout=2, retries=2)
        def wait_for(pattern, timeout=8):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                run_log = log.read_text(encoding='utf-8', errors='replace')
                if 'Core::LogException' in run_log:
                    raise RuntimeError(f'{model} ARM exception')
                match = re.search(pattern, run_log)
                if match:
                    return match
                if proc.poll() is not None:
                    raise RuntimeError(f'{model} emulator stopped ({proc.returncode})')
                time.sleep(0.02)
            raise RuntimeError(f'{model} missing {pattern}')
        try:
            wait_for('UPSTREAM_LIVE_READY', timeout=100)
            window = find_window(proc.pid)
            snapshot = binary.parent / (model + '-ready.png')
            snapshot.write_bytes(rpc.screenshot())
            tap(window, 'A')
            sent = int(wait_for(r'UPSTREAM_LIVE_SEND tick=(\d+)')[1])
            tap(window, 'Z')
            paused = int(wait_for(r'UPSTREAM_LIVE_PAUSE paused=true ticks=(\d+)')[1])
            time.sleep(0.25)
            paused_path = binary.parent / (model + '-paused.png')
            paused_path.write_bytes(rpc.screenshot())
            with held(window, 'H'):  # Azahar's D-pad Right.
                wait_for(r'UPSTREAM_LIVE_CAMERA left=34 bottom=0\b')
            with held(window, 0x26):  # Azahar's Circle Pad Up.
                panned = wait_for(r'UPSTREAM_LIVE_CAMERA left=(\d+) bottom=8\b')
            if int(panned[1]) < 34:
                raise RuntimeError(f'{model} lost horizontal movement when panning up')
            (binary.parent / (model + '-panned.png')).write_bytes(rpc.screenshot())
            tap(window, 'Z')
            resumed = int(wait_for(r'UPSTREAM_LIVE_PAUSE paused=false ticks=(\d+)')[1])
            arrived = int(wait_for(r'UPSTREAM_LIVE_DELIVERY copper=1 tick=(\d+)')[1])
            if paused != resumed or not sent < paused < arrived or arrived - sent != 57:
                raise RuntimeError(f'{model} update mismatch: {sent}, {paused}, {resumed}, {arrived}')
            with held(window, 'H'):
                wait_for(r'UPSTREAM_LIVE_CAMERA left=75 bottom=(\d+)')
            with held(window, 'T'):  # Azahar's D-pad Up.
                core = wait_for(r'UPSTREAM_LIVE_CAMERA left=(\d+) bottom=20\b')
            if not 72 <= int(core[1]) <= 78:
                raise RuntimeError(f'{model} did not reach the first Glacier core')
            (binary.parent / (model + '-core.png')).write_bytes(rpc.screenshot())
            tap(window, 'X')  # Azahar's 3DS Y button.
            wait_for(r'UPSTREAM_LIVE_CAMERA left=18 bottom=0\b')
            (binary.parent / (model + '-reset.png')).write_bytes(rpc.screenshot())
            tap(window, 'S')
            wait_for('UPSTREAM_LIVE_COLLECT copper=0')
            tap(window, 'M')
            wait_for('UPSTREAM_LIVE_EXIT')
            print(f'{model}: panned=({panned[1]},8) reset=(18,0) sent={sent} paused={paused} '
                  f'resumed={resumed} arrived={arrived} collected=1 exited=1', flush=True)
            report.append(dict(model=model, sent=sent, paused=paused, resumed=resumed,
                               arrived=arrived, panned=[int(panned[1]), 8],
                               core=[int(core[1]), 20], reset=[18, 0]))
        finally:
            rpc.close()
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
            (binary.parent / (model + '.log')).write_bytes(log.read_bytes())
finally:
    for path, data in originals.items():
        if data is None:
            path.unlink(missing_ok=True)
        else:
            path.write_bytes(data)
    (binary.parent / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
