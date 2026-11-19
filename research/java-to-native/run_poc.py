#!/usr/bin/env python3
"""Reproducible proof of concept for Min3DStry issue #1 (Java-to-native).

What it does:
  1. builds research/java-to-native/sim_kernel.c for the host at -O0 and -O2;
  2. runs both and compares trace hashes, item counts and combat counters;
  3. measures host simulation throughput on 200x200 and 500x500 maps;
  4. cross-compiles the same core for ARM11 (ARMv6K + VFPv2) when an
     arm-none-eabi-gcc is available, and verifies the ELF header;
  5. prints the memory/cycle budget model summary.

Evidence labels:
  - host build and host determinism: host-tested when a compiler is present;
  - ARM11: cross-compiled only, never executed on hardware or emulator;
  - ARM11 without a cross compiler: not-tested.

Usage:
    python3 run_poc.py                  # skip gracefully if no host compiler
    python3 run_poc.py --require-compiler
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "sim_kernel.c"

sys.path.insert(0, str(HERE))
import budget_model  # noqa: E402


def find_host_compiler() -> str | None:
    candidates = []
    if os.environ.get("CC"):
        candidates.append(os.environ["CC"])
    candidates.extend(["cc", "gcc", "clang"])
    for candidate in candidates:
        path = shutil.which(candidate)
        if path:
            return path
    return None


def find_arm_compiler() -> str | None:
    candidates = []
    if os.environ.get("ARM_CC"):
        candidates.append(os.environ["ARM_CC"])
    for base in (os.environ.get("DEVKITARM"), os.environ.get("DEVKITPRO")):
        if not base:
            continue
        suffix = Path("bin") / "arm-none-eabi-gcc"
        candidates.append(str(Path(base) / suffix))
        candidates.append(str(Path(base) / "devkitARM" / suffix))
    candidates.append("arm-none-eabi-gcc")
    for candidate in candidates:
        path = shutil.which(candidate)
        if path:
            return path
    return None


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=True,
        check=False,
    )


def build(compiler: str, optimization: str, output: Path) -> subprocess.CompletedProcess:
    return run(
        [
            compiler,
            "-std=c99",
            "-Wall",
            "-Wextra",
            "-Werror",
            optimization,
            str(SOURCE),
            "-o",
            str(output),
        ]
    )


def parse_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in text.splitlines():
        for token in line.split():
            if "=" in token:
                key, value = token.split("=", 1)
                fields.setdefault(key, value)
    return fields


def run_sim(binary: Path, args: list[str]) -> subprocess.CompletedProcess:
    return run([str(binary), *args])


def check_determinism(o0: Path, o2: Path) -> tuple[bool, str]:
    configs = [
        ["--map", "96x96", "--ticks", "2000", "--seed", "7"],
        ["--map", "128x96", "--ticks", "1500", "--seed", "11"],
    ]
    for config in configs:
        results = []
        for binary in (o0, o2, o2):
            proc = run_sim(binary, config)
            if proc.returncode != 0:
                return False, f"run failed for {' '.join(config)}: {proc.stderr.strip()}"
            results.append(parse_fields(proc.stdout))
        keys = ("trace_hash", "items_delivered", "enemies_spawned",
                "enemies_killed", "leaks")
        first = tuple(results[0].get(key) for key in keys)
        for index, result in enumerate(results[1:], start=1):
            current = tuple(result.get(key) for key in keys)
            if current != first:
                return (
                    False,
                    f"mismatch for {' '.join(config)}: run 0 {first}, "
                    f"run {index} {current}",
                )
    return True, "identical trace hashes and counters at -O0/-O2 and repeated runs"


def measure(binary: Path, map_size: str, ticks: str) -> dict[str, float]:
    proc = run_sim(binary, ["--map", map_size, "--ticks", ticks, "--seed", "7", "--nohash"])
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "simulation run failed")
    fields = parse_fields(proc.stdout)
    return {
        "ticks_per_sec": float(fields.get("ticks_per_sec", "0")),
        "sim_ms": float(fields.get("sim_ms", "0")),
        "ticks": float(fields.get("ticks", "0")),
    }


def check_elf32_arm(path: Path) -> tuple[bool, str]:
    data = path.read_bytes()
    if len(data) < 52 or data[:4] != b"\x7fELF":
        return False, "not an ELF file"
    elf_class = data[4]
    elf_data = data[5]
    machine = int.from_bytes(data[18:20], "little")
    e_flags = int.from_bytes(data[36:40], "little")
    if elf_class != 1:
        return False, f"ELF class {elf_class}, expected 32-bit"
    if elf_data != 1:
        return False, f"ELF data encoding {elf_data}, expected little-endian"
    if machine != 40:
        return False, f"e_machine {machine}, expected 40 (ARM)"
    if (e_flags & 0xFF000000) != 0x05000000:
        return False, f"EABI version bits 0x{e_flags:08x}, expected version 5"
    return True, "ELF32 little-endian ARM, EABI version 5"


def arm_cross_compile(compiler: str, workdir: Path) -> tuple[str, str]:
    output = workdir / "sim_kernel_armv6k.o"
    proc = run(
        [
            compiler,
            "-std=c99",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-O2",
            "-march=armv6k",
            "-mfpu=vfp",
            "-mfloat-abi=hard",
            "-DSIM_FREESTANDING",
            "-c",
            str(SOURCE),
            "-o",
            str(output),
        ]
    )
    if proc.returncode != 0:
        return "fail", (proc.stdout + proc.stderr).strip()
    ok, detail = check_elf32_arm(output)
    if not ok:
        return "fail", detail
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    extra = ""
    size_tool = Path(compiler).with_name(
        "arm-none-eabi-size.exe" if os.name == "nt" else "arm-none-eabi-size"
    )
    if size_tool.exists():
        size_proc = run([str(size_tool), str(output)])
        if size_proc.returncode == 0:
            lines = size_proc.stdout.strip().splitlines()
            if len(lines) >= 2:
                extra = " | size: " + " ".join(lines[1].split()[:3])
    return (
        "pass",
        f"ARMv6K/VFPv2 object built ({detail}), {output.stat().st_size} bytes, "
        f"sha256 {digest[:16]}...{extra}",
    )


def print_budget_summary() -> None:
    memory = budget_model.memory_rows()
    cpu = budget_model.cpu_rows()
    print("Budget model (assumptions in budget_model.py, not measurements):")
    for row in memory:
        targets = ", ".join(
            f"{target['name']}={target['used_fraction'] * 100:.1f}%"
            for target in row["targets"]
        )
        print(
            f"  map {row['map']}: world {row['world_mib']:.1f} MiB, "
            f"total+overhead {row['with_overhead_mib']:.1f} MiB ({targets})"
        )
    for row in cpu:
        per_tile = row["tiles"][1]
        print(
            f"  {row['clock']} @ {row['tick_hz']} Hz: "
            f"{row['cycles']:.0f} cycles/tick, "
            f"{per_tile['cycles_per_tile']:.1f} cycles per tile at 40k tiles"
        )

    # Arithmetic invariants: the model must be monotonic and bounded.
    assert memory[0]["with_overhead_mib"] < memory[-1]["with_overhead_mib"]
    for row in memory:
        for target in row["targets"]:
            assert 0.0 <= target["used_fraction"] <= 1.0
    for row in cpu:
        counts = [entry["cycles_per_tile"] for entry in row["tiles"]]
        assert counts[0] > counts[1] > counts[2]
    assert budget_model.PER_TILE["cell record (sim_kernel.c)"] == 12


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--require-compiler",
        action="store_true",
        help="fail instead of skipping when no host C compiler is available",
    )
    args = parser.parse_args()

    print(f"host={platform.system()} {platform.machine()}")
    host = find_host_compiler()
    if not host:
        message = "no host C compiler (cc/gcc/clang) found"
        if args.require_compiler:
            print(f"host_build=fail: {message}", file=sys.stderr)
            return 1
        print(f"host_build=skipped: {message}")
        print_budget_summary()
        print("POC SKIPPED (budget model only)")
        return 0

    version = run([host, "--version"])
    print(f"compiler={host}")
    if version.returncode == 0 and version.stdout:
        print(f"compiler_version={version.stdout.splitlines()[0]}")

    with tempfile.TemporaryDirectory(prefix="min3dstry-poc-") as tmp:
        workdir = Path(tmp)
        suffix = ".exe" if os.name == "nt" else ""
        o0 = workdir / f"sim_O0{suffix}"
        o2 = workdir / f"sim_O2{suffix}"

        for optimization, output in (("-O0", o0), ("-O2", o2)):
            proc = build(host, optimization, output)
            if proc.returncode != 0:
                print(f"host_build=fail ({optimization})", file=sys.stderr)
                print(proc.stdout, file=sys.stderr)
                print(proc.stderr, file=sys.stderr)
                return 1
        print("host_build=pass (-O0 and -O2)")

        ok, detail = check_determinism(o0, o2)
        print(f"determinism={'pass' if ok else 'fail'}: {detail}")
        if not ok:
            return 1

        for map_size, ticks in (("200x200", "3000"), ("500x500", "3900")):
            stats = measure(o2, map_size, ticks)
            print(
                f"throughput_{map_size}=host-tested: "
                f"{stats['ticks_per_sec']:.0f} ticks/s "
                f"({stats['sim_ms']:.1f} ms for {int(stats['ticks'])} ticks, hash disabled)"
            )

        arm = find_arm_compiler()
        if arm:
            status, detail = arm_cross_compile(arm, workdir)
            print(f"arm11_cross_compile={status}: {detail}")
            if status == "fail":
                return 1
            arm_version = run([arm, "--version"])
            if arm_version.returncode == 0 and arm_version.stdout:
                print(f"arm11_compiler_version={arm_version.stdout.splitlines()[0]}")
        else:
            print(
                "arm11_cross_compile=not-tested: no arm-none-eabi-gcc on PATH "
                "(set ARM_CC or DEVKITARM to enable)"
            )

    print_budget_summary()
    print("POC PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
