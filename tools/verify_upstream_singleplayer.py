#!/usr/bin/env python3
"""Verify the offline single-player Ground Zero .3dsx on Azahar New and Old 3DS profiles."""

from __future__ import annotations

import argparse
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_azahar as az  # noqa: E402

CTM_REVISION = bytes.fromhex("219d743a1e698101fe1ab8bef43e6563126c3c27")
CTM_SAMPLES_PER_SECOND = 234

KEY_SELECT = 1 << 2
KEY_START = 1 << 3
KEY_X = 1 << 10
KEY_Y = 1 << 11

SP_LOADED_RE = re.compile(
    r"UPSTREAM_SP_LOADED source=(?P<source>map|save) map=(?P<map>\S+) "
    r"size=(?P<size>\d+x\d+) wave=(?P<wave>\d+) copper=(?P<copper>\d+)"
)
SP_READY_RE = re.compile(
    r"UPSTREAM_SP_READY unit=(?P<unit>\d+) x=\S+ y=\S+ copper=(?P<copper>\d+) wave=(?P<wave>\d+)"
)
SP_MINED_RE = re.compile(r"UPSTREAM_SP_MINED item=(?P<item>\S+) copper=(?P<copper>\d+)")
SP_BUILT_RE = re.compile(
    r"UPSTREAM_SP_BUILT block=(?P<block>\S+) x=(?P<x>\d+) y=(?P<y>\d+) rotation=(?P<rotation>\d+)"
)
SP_SAVED_RE = re.compile(
    r"UPSTREAM_SP_SAVED path=(?P<path>\S+) bytes=(?P<bytes>\d+) wave=(?P<wave>\d+) copper=(?P<copper>\d+)"
)
UPSTREAM_MEM_RE = re.compile(
    r"UPSTREAM_MEMORY appHeap=(?P<appHeap>\d+) linearHeap=(?P<linearHeap>\d+) stack=(?P<stack>\d+)"
)


def resolve_sd_save_path(log_path: Path) -> Path:
    """Return the Azahar virtual SD path for singleplayer-save.msav."""
    return log_path.parent.parent / "sdmc" / "3ds" / "min3dstry" / "singleplayer-save.msav"


def write_singleplayer_ctm(path: Path, model: str) -> None:
    """Write an Azahar 2126.2 CTM input sequence exercising the Ground Zero vertical slice."""
    # Both New 3DS and Old 3DS reach UPSTREAM_SP_READY around 19s; start CTM taps at 26s for margin.
    t0 = 26
    total_seconds = t0 + 62
    frames = total_seconds * CTM_SAMPLES_PER_SECOND
    header = struct.pack(
        "<4sQ20sQQ32sIQq156x",
        b"CTM\x1b",
        0,
        CTM_REVISION,
        1780000000,
        0x4D334453,
        b"Min3DStry SP GroundZero",
        0,
        frames,
        0,
    )
    touch_idle = struct.pack("<B6x", 1)
    # Camera starts centered on Core: Shard @(129,55) -> viewport left=121, bottom=49.
    # Mine button @(305,96), copper ore (129,49) @(168,232), shortcut 0 (conveyor) @(40,24),
    # build tile (131,55) @(200,136).
    taps = (
        (t0, (305, 96)),
        (t0 + 4, (168, 232)),
        (t0 + 20, (40, 24)),
        (t0 + 24, (200, 136)),
    )
    button_windows = (
        (t0 + 36, KEY_X),      # Pause
        (t0 + 40, KEY_X),      # Resume
        (t0 + 44, KEY_Y),      # Trigger wave 1
        (t0 + 50, KEY_SELECT), # Save .msav to SD
        (t0 + 56, KEY_START),  # Clean exit
    )
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            sec = frame // CTM_SAMPLES_PER_SECOND
            buttons = next((mask for start, mask in button_windows if start <= sec < start + 2), 0)
            circle_x = 100 if (t0 + 30) <= sec < (t0 + 32) else 0
            point = next((pt for start, pt in taps if start <= sec < start + 2), None)
            pad = struct.pack("<BHhh", 0, buttons, circle_x, 0)
            if point:
                touch = struct.pack("<BHHBx", 1, *point, 1)
                movie.write(touch * 2)
            elif buttons or circle_x:
                movie.write(pad * 2)
            else:
                movie.write(pad + touch_idle)
            if model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(struct.pack("<BHHBx", 1, *point, 1) if point else pad)


def _stop_proc(proc: subprocess.Popen[bytes], azahar_bin: str = "") -> None:
    if az._is_flatpak_azahar(azahar_bin) and shutil.which("flatpak"):
        subprocess.run(
            ["flatpak", "kill", "org.azahar_emu.Azahar"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            try:
                probe.bind(("127.0.0.1", az.AZAHAR_RPC_PORT))
                return
            except OSError:
                time.sleep(0.1)


def _wait_for_log(log_path: Path, proc: subprocess.Popen[bytes], pattern: str, timeout: float) -> str:
    deadline = time.monotonic() + timeout
    text = ""
    while time.monotonic() < deadline:
        if log_path.exists():
            text = log_path.read_text(encoding="utf-8", errors="replace")
            if re.search(pattern, text):
                return text
            if "UPSTREAM_MEMORY managed-heap-exhausted" in text or "UPSTREAM_GC " in text:
                raise RuntimeError(f"ARM11 memory failure waiting for {pattern}:\n{text[-2500:]}")
            if "UPSTREAM_SP_ERROR " in text or re.search(r"Core::LogException|exception_handler\.cpp:LogException", text):
                raise RuntimeError(f"Emulated exception waiting for {pattern}:\n{text[-2500:]}")
        if proc.poll() is not None:
            text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else text
            raise RuntimeError(f"Azahar exited early ({proc.returncode}) waiting for {pattern}:\n{text[-2500:]}")
        time.sleep(0.1)
    raise TimeoutError(f"Timed out waiting for {pattern}:\n{text[-2500:]}")


def parse_singleplayer_log(log_text: str) -> dict[str, Any]:
    """Extract structured single-player telemetry lines from an Azahar log."""
    loaded_matches = [m.groupdict() for m in SP_LOADED_RE.finditer(log_text)]
    ready = SP_READY_RE.search(log_text)
    mined = [m.groupdict() for m in SP_MINED_RE.finditer(log_text)]
    built = [m.groupdict() for m in SP_BUILT_RE.finditer(log_text)]
    saved = SP_SAVED_RE.search(log_text)
    mem = UPSTREAM_MEM_RE.search(log_text)
    return {
        "loaded": loaded_matches[-1] if loaded_matches else None,
        "ready": ready.groupdict() if ready else None,
        "mined": mined,
        "built": built,
        "pause_true": "UPSTREAM_SP_PAUSE paused=true" in log_text,
        "pause_false": "UPSTREAM_SP_PAUSE paused=false" in log_text,
        "pause_disabled": "UPSTREAM_SP_PAUSE disabled=true" in log_text,
        "pause_menu_open": "UPSTREAM_PAUSE_MENU open=true" in log_text,
        "pause_menu_close": "UPSTREAM_PAUSE_MENU open=false" in log_text,
        "moving": "UPSTREAM_SP_MOVING " in log_text,
        "wave": "UPSTREAM_SP_WAVE wave=2" in log_text,
        "saved": saved.groupdict() if saved else None,
        "exit": "UPSTREAM_SP_EXIT" in log_text,
        "mem": {k: int(v) for k, v in mem.groupdict().items()} if mem else None,
    }


def pause_milestone_reached(parsed: dict[str, Any]) -> bool:
    """Require the behavior that matches the loaded stock pause rule.

    A pause-enabled world must report a real ``paused=true``/``paused=false``
    toggle. A world with ``rules.pauseDisabled`` must report ``disabled=true``
    and still open/close the pause menu. Menu traffic alone is never enough.
    """
    if parsed["pause_disabled"]:
        return parsed["pause_menu_open"] and parsed["pause_menu_close"]
    return parsed["pause_true"] and parsed["pause_false"]


def _launch_azahar(
    model: str,
    rom_path: Path,
    azahar_bin: str,
    config_path: Path,
    log_path: Path,
    replay_path: Path | None = None,
) -> tuple[subprocess.Popen[bytes], az.AzaharRpcClient]:
    overrides = {
        "Miscellaneous": {
            "log_filter": "*:Info Debug.Emulated:Debug Movie:Critical",
            "check_for_update_on_start": "false",
        },
        "Debugging": {"enable_rpc_server": "true", "instant_debug_log": "true"},
        "UI": {"firstStart": "false", "confirmClose": "false", "pause_when_in_background": "false"},
        "System": {"is_new_3ds": "true" if model == "new3ds" else "false"},
        "Renderer": {"graphics_api": "2"},
        "Data Storage": {"use_virtual_sd": "true"},
        "Layout": {"layout_option": "0", "swap_screen": "false", "upright_screen": "false", "render_3d": "0"},
    }
    original = config_path.read_text(encoding="utf-8") if config_path.exists() else ""
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(az.patch_qt_config_text(original, overrides), encoding="utf-8")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("", encoding="utf-8")

    cmd = [azahar_bin]
    if replay_path is not None:
        cmd += ["-p", az.to_azahar_cli_path(replay_path, azahar_bin)]
    cmd.append(az.to_azahar_cli_path(rom_path, azahar_bin))
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    rpc = az.AzaharRpcClient(timeout=2.0, retries=2)
    return proc, rpc


def run_singleplayer_model(
    model: str,
    rom_path: Path,
    azahar_bin: str,
    config_path: Path,
    log_path: Path,
    out_dir: Path,
    timeout: float,
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    had_config = config_path.exists()
    original_config = config_path.read_text(encoding="utf-8") if had_config else ""
    sd_save = resolve_sd_save_path(log_path)
    if sd_save.exists():
        sd_save.unlink()

    ctm_path = out_dir / f"{model}-controls.ctm"
    write_singleplayer_ctm(ctm_path, model)

    try:
        # Boot 1: full offline Ground Zero vertical slice via CTM replay.
        proc, rpc = _launch_azahar(model, rom_path, azahar_bin, config_path, log_path, ctm_path)
        try:
            _wait_for_log(log_path, proc, r"UPSTREAM_SP_READY ", timeout)
            _wait_for_log(log_path, proc, r"UPSTREAM_SP_MINED item=copper ", timeout)
            _wait_for_log(log_path, proc, r"UPSTREAM_SP_BUILT block=conveyor x=131 y=55\b", timeout)
            rpc.perf_stats(reset=True)
            time.sleep(1.5)
            perf = rpc.perf_stats(reset=True) or {}
            shot_play = rpc.screenshot()
            (out_dir / f"{model}-play.png").write_bytes(shot_play)
            _wait_for_log(log_path, proc, r"UPSTREAM_SP_SAVED ", timeout)
            shot_end = rpc.screenshot()
            (out_dir / f"{model}-wave.png").write_bytes(shot_end)
            log_boot1 = _wait_for_log(log_path, proc, r"UPSTREAM_SP_EXIT\b", timeout)
        finally:
            rpc.close()
            _stop_proc(proc, azahar_bin)
        (out_dir / f"{model}-boot1.log").write_text(log_boot1, encoding="utf-8")

        parsed1 = parse_singleplayer_log(log_boot1)
        if not parsed1["loaded"] or parsed1["loaded"]["source"] != "map":
            raise AssertionError(f"{model} Boot 1 did not load map: {parsed1['loaded']}")
        if not parsed1["moving"] or not pause_milestone_reached(parsed1) or not parsed1["wave"]:
            raise AssertionError(f"{model} Boot 1 missing gameplay milestone: {parsed1}")
        if not parsed1["saved"] or not sd_save.is_file() or sd_save.stat().st_size == 0:
            raise AssertionError(f"{model} Boot 1 did not write Virtual SD save {sd_save}")

        # Boot 2: relaunch cleanly with no CTM and verify .msav reload from SD.
        proc2, rpc2 = _launch_azahar(model, rom_path, azahar_bin, config_path, log_path, None)
        try:
            _wait_for_log(log_path, proc2, r"UPSTREAM_SP_FRAME ", timeout)
            time.sleep(1.5)
            shot_reload = rpc2.screenshot()
            (out_dir / f"{model}-reload.png").write_bytes(shot_reload)
            log_boot2 = log_path.read_text(encoding="utf-8", errors="replace")
        finally:
            rpc2.close()
            _stop_proc(proc2, azahar_bin)
        (out_dir / f"{model}-boot2.log").write_text(log_boot2, encoding="utf-8")

        parsed2 = parse_singleplayer_log(log_boot2)
        loaded2 = parsed2["loaded"]
        if not loaded2 or loaded2["source"] != "save" or int(loaded2["wave"]) != 2:
            raise AssertionError(f"{model} Boot 2 did not resume save state cleanly: {loaded2}")
        if int(loaded2["copper"]) != int(parsed1["saved"]["copper"]):
            raise AssertionError(
                f"{model} Boot 2 copper mismatch: saved={parsed1['saved']['copper']} vs loaded={loaded2['copper']}"
            )
        return {
            "model": model,
            "perf": perf,
            "boot1": parsed1,
            "boot2": parsed2,
            "screenshots": {
                "play": str(out_dir / f"{model}-play.png"),
                "wave": str(out_dir / f"{model}-wave.png"),
                "reload": str(out_dir / f"{model}-reload.png"),
            },
        }
    finally:
        if sd_save.exists():
            sd_save.unlink()
        if had_config:
            config_path.write_text(original_config, encoding="utf-8")
        elif config_path.exists():
            config_path.unlink()


def run_weather_model(
    model: str,
    rom_path: Path,
    azahar_bin: str,
    config_path: Path,
    log_path: Path,
    out_dir: Path,
    timeout: float,
    fixtures: Path,
) -> None:
    sd = (out_dir / "virtual-sd" / model).resolve()
    sd_save = sd / "3ds/min3dstry/singleplayer-save.msav"
    snapshots = {name: (fixtures / f"{name}.msav").read_bytes() for name in ("clear", "weather")}
    original_config = config_path.read_bytes() if config_path.exists() else None
    sd_save.parent.mkdir(parents=True)
    world = tuple((x, y) for y in range(40, 210, 20) for x in range(30, 380, 25))
    touch = tuple((x, y) for y in (350, 385, 415, 445) for x in range(80, 330, 25))
    samples = {}
    try:
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.write_text(az.patch_qt_config_text((original_config or b"").decode("utf-8"), storage),
                               encoding="utf-8")
        for name, save in snapshots.items():
            sd_save.write_bytes(save)
            proc, rpc = _launch_azahar(model, rom_path, azahar_bin, config_path, log_path)
            try:
                _wait_for_log(log_path, proc, r"UPSTREAM_SP_LOADED source=save map=Ground_Zero\b", timeout)
                _wait_for_log(log_path, proc, r"UPSTREAM_SP_FRAME ", timeout)
                if name == "weather":
                    for texture in ("noiseAlpha", "fog"):
                        _wait_for_log(log_path, proc, rf"UPSTREAM_WEATHER_TEXTURE name={texture}\b", timeout)
                time.sleep(3)
                shot = rpc.screenshot()
                (out_dir / f"{model}-{name}.png").write_bytes(shot)
                info = az.inspect_png(shot, world + touch)
                if (info["width"], info["height"]) != (400, 480):
                    raise AssertionError(f"Unexpected screenshot layout: {info['width']}x{info['height']}")
                samples[name] = info["samples"]
                log = log_path.read_text(encoding="utf-8", errors="replace")
                (out_dir / f"{model}-{name}.log").write_text(log, encoding="utf-8")
                events = set(re.findall(r"UPSTREAM_SP_WEATHER name=([\w-]+)", log))
                expected = {"rain", "sandstorm", "fog"} if name == "weather" else set()
                if events != expected:
                    raise AssertionError(f"{model} {name}: expected events {expected}, got {events}")
            finally:
                rpc.close()
                _stop_proc(proc, azahar_bin)
        world_changes = sum(samples["clear"][p] != samples["weather"][p] for p in world)
        touch_changes = sum(samples["clear"][p] != samples["weather"][p] for p in touch)
        if world_changes < len(world) // 2 or touch_changes:
            raise AssertionError(f"{model}: compared {world_changes} changed world and {touch_changes} changed touch samples")
        print(f"[PASS] {model}: weather=rain,sandstorm,fog textures=2 world={world_changes}/{len(world)} touch=unchanged")
    finally:
        if original_config is None:
            config_path.unlink(missing_ok=True)
        else:
            config_path.write_bytes(original_config)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar", default=None)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), default=None)
    parser.add_argument("--output-dir", type=Path, default=Path("build/singleplayer-emu"))
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--weather-fixtures", type=Path, help="directory with stock clear.msav and weather.msav")
    args = parser.parse_args()

    azahar_bin = az.resolve_azahar_bin(args.azahar)
    config_path, log_path = az.resolve_azahar_paths(azahar_bin)
    models = (args.model,) if args.model else ("new3ds", "old3ds")

    for model in models:
        if args.weather_fixtures:
            run_weather_model(model, args.rom, azahar_bin, config_path, log_path,
                              args.output_dir, args.timeout, args.weather_fixtures)
            continue
        result = run_singleplayer_model(model, args.rom, azahar_bin, config_path, log_path, args.output_dir, args.timeout)
        fps = result["perf"].get("game_fps", 0.0)
        b1 = result["boot1"]
        b2 = result["boot2"]
        print(
            f"[PASS] {model}: game_fps={fps:.2f} mem={b1['mem']} "
            f"saved=wave:{b1['saved']['wave']},copper:{b1['saved']['copper']},bytes:{b1['saved']['bytes']} "
            f"reloaded=wave:{b2['loaded']['wave']},copper:{b2['loaded']['copper']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
