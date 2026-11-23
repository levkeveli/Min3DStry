import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import validate_audio_romfs  # noqa: E402


class ValidateAudioRomfsTests(unittest.TestCase):
    def make_romfs(self, tmp: Path, entries: int = 9, truncate: bool = False) -> Path:
        audio = tmp / "romfs" / "audio"
        audio.mkdir(parents=True)
        manifest = {"entries": []}
        for index in range(entries):
            name = f"clip{index}.pcm"
            data = b"\x00\x01" * (index + 1)
            (audio / name).write_bytes(data)
            manifest["entries"].append(
                {"id": f"clip{index}", "output": name, "bytes": len(data) - (1 if truncate else 0)}
            )
        (audio / "audio-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        return tmp / "romfs"

    def test_valid_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_romfs(Path(tmp))
            count, total = validate_audio_romfs.validate(root)
            self.assertEqual(count, 9)
            self.assertGreater(total, 0)

    def test_too_few_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_romfs(Path(tmp), entries=2)
            with self.assertRaises(ValueError):
                validate_audio_romfs.validate(root)

    def test_size_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_romfs(Path(tmp), truncate=True)
            with self.assertRaises(ValueError):
                validate_audio_romfs.validate(root)

    def test_missing_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                validate_audio_romfs.validate(Path(tmp))


if __name__ == "__main__":
    unittest.main()
