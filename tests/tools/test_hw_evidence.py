import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import hw_evidence  # noqa: E402


def sample_log(
    *,
    tag: str = "N3DS",
    app_mem: int = 124,
    build_commit: str = "c0ffee1234ab",
    build_dirty: int = 0,
    loader_loaded: int = 1,
    alloc_hash: str = "783fbd80e415dcd7",
    tile_hash: str = "e35397041df8e5fe",
    save_hash: str = "3a375fd4f81c5680",
    parity_hash: str = "0x53a483c7",
    workers_match: int = 1,
    drops: tuple[int, ...] = (0, 0, 0),
    mem_status: str = "ok",
    mem_failures: int = 0,
    audio_state: str = "ready",
    include: tuple[str, ...] | None = None,
) -> str:
    lines = [
        f"[MIN3DSTRY] BUILD commit={build_commit} dirty={build_dirty}",
        "[MIN3DSTRY] HW: New 3DS XL "
        f"({tag}, 804 MHz, L2:on, AppMem:{app_mem} MiB, FIRM:2.58.0)",
        "[MIN3DSTRY] STORAGE root=/3ds/min3dstry settings=/3ds/min3dstry/settings.bin "
        "sector=/3ds/min3dstry/sector-serpulo-170.msav",
        "[MIN3DSTRY] STORAGE_ROUNDTRIP status=ok bytes=4 root=sdmc:/3ds/min3dstry",
        "[MIN3DSTRY] SAVE_SLOT present=1 valid=1 compressed=1 version=13 bytes=11072 "
        "path=sdmc:/3ds/min3dstry/sector-serpulo-170.msav",
        "[MIN3DSTRY] SECTOR_LOADER source=teavm available=1 "
        f"loaded={loader_loaded} path=/3ds/min3dstry/sector-serpulo-170.msav tiles=37500 buildings=2",
        f"[MIN3DSTRY] SECTOR_ALLOC tiles=37500 bytes=1312788 hash={alloc_hash} status=loaded",
        f"[MIN3DSTRY] SECTOR_TILE_STATE tiles=37500 buildings=2 linked=2 hash={tile_hash} status=loaded",
        f"[MIN3DSTRY] SECTOR_PARITY buildings=2 hash={save_hash} verified=0 status=loaded",
        "[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
        f"hash={parity_hash} copper=22 delivered=12 killed=2 hp=160",
        "[MIN3DSTRY] WORKERS workers=4 active=4 chunks=40 items=40000 "
        f"match={workers_match} checksum=0x4bb2b98d serial_us=1348 parallel_us=1507",
        "[MIN3DSTRY] MEM app=124MiB reserve=16MiB budget=100663296 used=1024 peak=2048 "
        f"remaining=2048 world=1/2 textures=1/2 transient=1/2 failures={mem_failures} status={mem_status}",
        f"[MIN3DSTRY] AUDIO backend=ndsp state={audio_state} rc=0x00000000 rate=22050 voices=0/8 clips=10",
    ]
    for index, drop in enumerate(drops):
        lines.append(
            "[MIN3DSTRY] FRAME tick=%d paused=0 cam=(88.0,72.0) "
            "draw=R:428,L:2,T:0,C:4 cull=448 sw=1 drop=%d cpu=11.49%% gpu=1.50%% cmdbuf=1.11%%"
            % (30 + index, drop)
        )
    if include is not None:
        wanted = set(include)
        lines = [line for line in lines if line.split()[1].rstrip(":") in wanted]
    return "\n".join(lines) + "\n"


def run_main(argv: list[str]) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = hw_evidence.main(argv)
    return code, buffer.getvalue()


class HwEvidenceTests(unittest.TestCase):
    def write_log(self, text: str) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        self.addCleanup(Path(handle.name).unlink, missing_ok=True)
        return Path(handle.name)

    def test_parse_records_tolerates_emulator_prefix(self) -> None:
        text = (
            "[  13.1] Debug.Emulated <Debug> svc.cpp:1190: [MIN3DSTRY] PARITY "
            "scenario=starter_copper_defense_v1 ticks=240 hash=0x53a483c7\n"
        )
        records = hw_evidence.parse_records(text)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["tag"], "PARITY")
        self.assertEqual(records[0]["kv"]["hash"], "0x53a483c7")

    def test_new3ds_valid_log_passes_every_required_check(self) -> None:
        records = hw_evidence.parse_records(sample_log())
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(
            records, "new3ds", "sector170", hardware, "c0ffee1234ab"
        )
        failed = [check for check in checks if check.required and not check.ok]
        self.assertEqual(failed, [])
        self.assertTrue(all(check.ok for check in checks if check.required))

    def test_old3ds_profile_requires_64_mib(self) -> None:
        records = hw_evidence.parse_records(sample_log(tag="O3DS", app_mem=64))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "old3ds", "sector170", hardware)
        hardware_check = next(check for check in checks if check.id == "hardware.profile")
        self.assertTrue(hardware_check.ok)
        self.assertEqual(hardware["tag"], "O3DS")

    def test_hardware_summary_reports_cpu_clock(self) -> None:
        records = hw_evidence.parse_records(sample_log())
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        self.assertEqual(hardware["mhz"], "804 MHz")
        self.assertEqual(hardware["model"], "New 3DS XL")

    def test_profile_mismatch_fails(self) -> None:
        records = hw_evidence.parse_records(sample_log(tag="N3DS", app_mem=124))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "old3ds", "sector170", hardware)
        hardware_check = next(check for check in checks if check.id == "hardware.profile")
        self.assertFalse(hardware_check.ok)
        self.assertIn("profile is old3ds", hardware_check.detail)

    def test_corrupt_save_fails_loader_checks(self) -> None:
        text = sample_log(loader_loaded=0)
        # A failed load emits no ALLOC/TILE_STATE/PARITY rows at all.
        text = "\n".join(
            line
            for line in text.splitlines()
            if not any(
                line.startswith(f"[MIN3DSTRY] {tag}")
                for tag in ("SECTOR_ALLOC", "SECTOR_TILE_STATE", "SECTOR_PARITY")
            )
        )
        records = hw_evidence.parse_records(text)
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "sector170", hardware)
        by_id = {check.id: check for check in checks}
        self.assertFalse(by_id["loader.sector"].ok)
        self.assertFalse(by_id["loader.alloc"].ok)
        self.assertFalse(by_id["loader.tile_state"].ok)
        self.assertFalse(by_id["loader.save_parity"].ok)

    def test_wrong_hashes_fail(self) -> None:
        records = hw_evidence.parse_records(
            sample_log(alloc_hash="deadbeefdeadbeef", parity_hash="0xdeadbeef")
        )
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "sector170", hardware)
        by_id = {check.id: check for check in checks}
        self.assertFalse(by_id["loader.alloc"].ok)
        self.assertFalse(by_id["sim.parity"].ok)

    def test_frame_drops_and_memory_failures_fail(self) -> None:
        records = hw_evidence.parse_records(
            sample_log(drops=(0, 3), mem_status="full", mem_failures=2)
        )
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "sector170", hardware)
        by_id = {check.id: check for check in checks}
        self.assertFalse(by_id["frame.drops"].ok)
        self.assertFalse(by_id["memory.budget"].ok)

    def test_missing_records_fail_required_checks(self) -> None:
        records = hw_evidence.parse_records("[MIN3DSTRY] HW: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:2.58.0)\n")
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "sector170", hardware)
        failed = [check.id for check in checks if check.required and not check.ok]
        self.assertIn("loader.sector", failed)
        self.assertIn("sim.parity", failed)
        self.assertNotIn("hardware.profile", failed)

    def test_smoke_scenario_skips_sector_requirements(self) -> None:
        records = hw_evidence.parse_records(sample_log(include=("BUILD", "HW", "STORAGE_ROUNDTRIP", "PARITY", "WORKERS", "MEM", "FRAME")))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "smoke", hardware)
        failed = [check.id for check in checks if check.required and not check.ok]
        self.assertEqual(failed, [])

    def test_markdown_and_json_include_provenance(self) -> None:
        log = self.write_log(sample_log(build_commit="ea77d0b1234", audio_state="fallback_no_dsp"))
        rom = Path(log.parent) / "min3dstry.3dsx"
        rom.write_bytes(b"fake-rom")
        self.addCleanup(rom.unlink, missing_ok=True)

        code, markdown = run_main(
            [
                "--input",
                str(log),
                "--rom",
                str(rom),
                "--commit",
                "ea77d0b",
                "--target",
                "New 3DS XL",
                "--profile",
                "new3ds",
                "--strict",
            ]
        )
        self.assertEqual(code, 0)
        self.assertIn("Verdict: **PASS**", markdown)
        self.assertIn("ea77d0b", markdown)
        self.assertIn(hw_evidence.sha256_file(rom), markdown)
        self.assertIn("fallback_no_dsp", markdown)

        code, payload = run_main(
            ["--input", str(log), "--commit", "ea77d0b", "--format", "json", "--strict"]
        )
        self.assertEqual(code, 0)
        summary = json.loads(payload)
        self.assertEqual(summary["verdict"], "pass")
        self.assertEqual(summary["commit"], "ea77d0b")
        self.assertEqual(summary["counts"]["failed"], 0)

    def test_strict_exit_code_is_nonzero_on_failure(self) -> None:
        log = self.write_log(sample_log(loader_loaded=0))
        code, _ = run_main(["--input", str(log), "--strict"])
        self.assertEqual(code, 1)
        code, _ = run_main(["--input", str(log)])
        self.assertEqual(code, 0)

    def test_emulator_memory_value_is_rejected(self) -> None:
        # Azahar reports 96 MiB on both profiles; exact matching stops an
        # emulator capture from being recorded as hardware evidence.
        for tag, profile in (("N3DS", "new3ds"), ("O3DS", "old3ds")):
            records = hw_evidence.parse_records(sample_log(tag=tag, app_mem=96))
            hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
            checks = hw_evidence.build_checks(records, profile, "sector170", hardware)
            hardware_check = next(check for check in checks if check.id == "hardware.profile")
            self.assertFalse(hardware_check.ok, msg=f"{tag}/{profile}")

    def test_build_commit_mismatch_fails(self) -> None:
        records = hw_evidence.parse_records(sample_log(build_commit="aaaabbbbcccc"))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(
            records, "new3ds", "sector170", hardware, "dddddeeeefff"
        )
        build_check = next(check for check in checks if check.id == "build.commit")
        self.assertFalse(build_check.ok)
        self.assertIn("--commit says", build_check.detail)

    def test_build_commit_accepts_short_prefix(self) -> None:
        records = hw_evidence.parse_records(sample_log(build_commit="c0ffee1234ab"))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "sector170", hardware, "c0ffee1")
        build_check = next(check for check in checks if check.id == "build.commit")
        self.assertTrue(build_check.ok)

    def test_dirty_build_fails(self) -> None:
        records = hw_evidence.parse_records(sample_log(build_dirty=1))
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(
            records, "new3ds", "sector170", hardware, "c0ffee1234ab"
        )
        build_check = next(check for check in checks if check.id == "build.commit")
        self.assertFalse(build_check.ok)
        self.assertIn("dirty", build_check.detail)

    def test_missing_build_record_fails(self) -> None:
        text = "\n".join(
            line
            for line in sample_log().splitlines()
            if not line.startswith("[MIN3DSTRY] BUILD")
        )
        records = hw_evidence.parse_records(text)
        hardware = hw_evidence.hardware_summary(hw_evidence.first(records, "HW"))
        checks = hw_evidence.build_checks(records, "new3ds", "smoke", hardware)
        build_check = next(check for check in checks if check.id == "build.commit")
        self.assertFalse(build_check.ok)
        self.assertIn("no BUILD record", build_check.detail)

    def test_device_wrapper_delegates_to_evidence(self) -> None:
        import device_deploy

        log = self.write_log(sample_log(build_commit="ea77d0b1234"))
        parser = device_deploy.build_parser()
        args = parser.parse_args(
            [
                "evidence",
                "--input",
                str(log),
                "--commit",
                "ea77d0b",
                "--target",
                "New 3DS XL",
                "--profile",
                "new3ds",
                "--strict",
            ]
        )
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = args.func(args)
        self.assertEqual(code, 0)
        self.assertIn("Verdict: **PASS**", buffer.getvalue())

    def test_missing_input_returns_two(self) -> None:
        code, _ = run_main(["--input", str(Path(tempfile.gettempdir()) / "does-not-exist.log")])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
