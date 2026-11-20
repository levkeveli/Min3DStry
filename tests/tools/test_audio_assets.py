import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import build_audio_assets  # noqa: E402

COMMIT = "19a0f40d44514f4802a8b79770456e7e32e34da0"


def find_ffmpeg() -> str | None:
    return os.environ.get("FFMPEG") or shutil.which("ffmpeg")


class AudioAssetsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="m3ds-audio-")
        self.root = Path(self.temp.name)
        self.upstream = self.root / "upstream"
        self.sounds = self.upstream / "core" / "assets" / "sounds"
        self.sounds.mkdir(parents=True)
        self.map_path = self.root / "map.json"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_map(self, entries: list[dict], **overrides) -> Path:
        data = {
            "format": 1,
            "upstream_commit": COMMIT,
            "rate": 8000,
            "entries": entries,
            **overrides,
        }
        self.map_path.write_text(json.dumps(data), encoding="utf-8")
        return self.map_path

    def make_tone(self, name: str = "tone.wav", frames: int = 800) -> Path:
        path = self.sounds / "test" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(8000)
            handle.writeframes(b"\x00\x00" * frames)
        return path

    def test_validate_rejects_bad_maps(self) -> None:
        self.make_tone()
        cases = [
            ([{"id": "ok", "source": "core/assets/sounds/test/tone.wav", "channels": 1}], {"format": 2}),
            ([{"id": "bad id", "source": "core/assets/sounds/test/tone.wav", "channels": 1}], {}),
            ([{"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 3}], {}),
            ([{"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": True}], {}),
            ([{"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 1, "rate": 8000.0}], {}),
            ([{"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 1, "rate": 1000}], {}),
            ([{"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 1}], {"format": True}),
            ([{"id": "a", "source": "core/assets/sounds/../tone.wav", "channels": 1}], {}),
            (
                [
                    {"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 1},
                    {"id": "a", "source": "core/assets/sounds/test/tone.wav", "channels": 1},
                ],
                {},
            ),
        ]
        for entries, overrides in cases:
            self.write_map(entries, **overrides)
            with self.assertRaises(build_audio_assets.AudioConversionError):
                build_audio_assets.validate_map(
                    build_audio_assets.load_map(self.map_path), self.upstream
                )

    def test_validate_rejects_missing_source(self) -> None:
        self.write_map(
            [{"id": "missing", "source": "core/assets/sounds/test/nope.ogg", "channels": 1}]
        )
        with self.assertRaises(build_audio_assets.AudioConversionError):
            build_audio_assets.validate_map(
                build_audio_assets.load_map(self.map_path), self.upstream
            )

    def test_convert_rejects_wrong_upstream_head(self) -> None:
        if not shutil.which("git"):
            self.skipTest("git not available")
        self.make_tone()
        subprocess.run(["git", "-C", str(self.upstream), "init", "-q"], check=True)
        subprocess.run(
            ["git", "-C", str(self.upstream), "config", "user.email", "test@example.invalid"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(self.upstream), "config", "user.name", "test"],
            check=True,
        )
        subprocess.run(["git", "-C", str(self.upstream), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(self.upstream), "commit", "-qm", "fixture"], check=True
        )
        self.write_map(
            [{"id": "test.tone", "source": "core/assets/sounds/test/tone.wav", "channels": 1}],
            upstream_commit="0" * 40,
        )
        with self.assertRaises(build_audio_assets.AudioConversionError):
            build_audio_assets.convert_map(
                self.upstream, self.map_path, self.root / "out", "ffmpeg"
            )

    def test_convert_synthetic_wav(self) -> None:
        ffmpeg = find_ffmpeg()
        if not ffmpeg:
            self.skipTest("ffmpeg not available")
        self.make_tone(frames=800)
        self.write_map(
            [{"id": "test.tone", "source": "core/assets/sounds/test/tone.wav", "channels": 1}]
        )
        manifest = build_audio_assets.convert_map(
            self.upstream, self.map_path, self.root / "out", ffmpeg
        )
        self.assertEqual(manifest["total_files"], 1)
        entry = manifest["entries"][0]
        self.assertEqual(entry["id"], "test.tone")
        self.assertEqual(entry["frames"], 800)
        self.assertEqual(entry["bytes"], 1600)
        self.assertEqual(entry["rate"], 8000)
        self.assertEqual(entry["channels"], 1)
        self.assertEqual(entry["source_sha256"], build_audio_assets.sha256_file(self.sounds / "test" / "tone.wav"))
        self.assertTrue((self.root / "out" / "test.tone.pcm").is_file())
        self.assertTrue((self.root / "out" / "audio-manifest.json").is_file())

    def test_sha256_is_stable(self) -> None:
        path = self.root / "data.bin"
        path.write_bytes(b"min3dstry")
        first = build_audio_assets.sha256_file(path)
        second = build_audio_assets.sha256_file(path)
        self.assertEqual(first, second)
        self.assertEqual(len(first), 64)


if __name__ == "__main__":
    unittest.main()
