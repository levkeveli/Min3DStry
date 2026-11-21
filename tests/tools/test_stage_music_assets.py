#!/usr/bin/env python3
"""Host tests for the upstream Ogg asset staging tools."""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools"))

import stage_music_assets  # noqa: E402
import stage_sound_catalog  # noqa: E402

UPSTREAM_COMMIT = "19a0f40d44514f4802a8b79770456e7e32e34da0"
EXPECTED_MUSICS_ORDER = [
    "boss1",
    "boss2",
    "editor",
    "fine",
    "game1",
    "game2",
    "game3",
    "game4",
    "game5",
    "game6",
    "game7",
    "game8",
    "game9",
    "land",
    "launch",
    "menu",
]


def build_vorbis_ident_packet(
    channels: int = 2,
    sample_rate: int = 44100,
    vorbis_version: int = 0,
    bitrate_nominal: int = 128000,
    blocksize_0: int = 8,
    blocksize_1: int = 11,
    framing_bit: int = 1,
    packet_type: int = 1,
    magic: bytes = b"vorbis",
) -> bytes:
    blocksize_byte = (blocksize_0 & 0x0F) | ((blocksize_1 & 0x0F) << 4)
    return struct.pack(
        "<B6sIBIiiiBB",
        packet_type,
        magic,
        vorbis_version,
        channels,
        sample_rate,
        0,
        bitrate_nominal,
        0,
        blocksize_byte,
        framing_bit,
    )


def build_ogg_bos_page(
    packet: bytes,
    magic: bytes = b"OggS",
    stream_version: int = 0,
    header_type: int = 0x02,
    segment_table: bytes | None = None,
) -> bytes:
    if segment_table is None:
        segments = []
        remaining = len(packet)
        while remaining >= 255:
            segments.append(255)
            remaining -= 255
        segments.append(remaining)
        segment_table = bytes(segments)

    header = struct.pack(
        "<4sBBQIIIB",
        magic,
        stream_version,
        header_type,
        0,
        1,
        0,
        0,
        len(segment_table),
    )
    return header + segment_table + packet


class StageMusicAssetsTests(unittest.TestCase):
    def test_stock_sound_preserves_high_rate_and_final_granule(self) -> None:
        page = bytearray(build_ogg_bos_page(build_vorbis_ident_packet(sample_rate=192000), header_type=0x06))
        struct.pack_into("<q", page, 6, 48301)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stock.ogg"
            path.write_bytes(page)
            with self.assertRaises(stage_music_assets.MusicStagingError):
                stage_music_assets.parse_ogg_vorbis_ident(path)
            ident = stage_music_assets.parse_ogg_vorbis_ident(path, max_sample_rate=192000)
            self.assertEqual(ident["sample_rate"], 192000)
        self.assertEqual(stage_sound_catalog.ogg_frames(page, "stock.ogg"), 48301)
        with self.assertRaises(ValueError):
            stage_sound_catalog.ogg_frames(page[:-1], "truncated.ogg")

    def _create_fake_upstream(self, root: Path, names: list[str] | None = None) -> Path:
        upstream = root / "Mindustry"
        music_dir = upstream / "core" / "assets" / "music"
        music_dir.mkdir(parents=True)
        sound_ctl = upstream / "core" / "src" / "mindustry" / "audio" / "SoundControl.java"
        core_block = upstream / "core" / "src" / "mindustry" / "world" / "blocks" / "storage" / "CoreBlock.java"
        planet = upstream / "core" / "src" / "mindustry" / "type" / "Planet.java"
        for src_file in (sound_ctl, core_block, planet):
            src_file.parent.mkdir(parents=True, exist_ok=True)

        sound_ctl.write_text(
            "\n".join(
                [
                    "public class SoundControl {",
                    "  void play() {",
                    "    Seq.with(Musics.game1, Musics.game3, Musics.game6, Musics.game8, Musics.game9, Musics.fine);",
                    "    Seq.with(Musics.game2, Musics.game5, Musics.game7, Musics.game4);",
                    "    Seq.with(Musics.boss1, Musics.boss2);",
                    "    play(Musics.editor);",
                    "    play(Musics.menu);",
                    "  }",
                    "}",
                ]
            ),
            encoding="utf-8",
        )
        core_block.write_text("public class CoreBlock { Music landMusic = Musics.land; }\n", encoding="utf-8")
        planet.write_text("public class Planet { Music launchMusic = Musics.launch; }\n", encoding="utf-8")

        for idx, track in enumerate(names or EXPECTED_MUSICS_ORDER):
            pkt = build_vorbis_ident_packet(
                channels=2,
                sample_rate=44100,
                bitrate_nominal=96000 + idx * 1000,
            )
            page = build_ogg_bos_page(pkt) + f"payload:{track}".encode("utf-8")
            (music_dir / f"{track}.ogg").write_bytes(page)
        return upstream

    def test_committed_music_map_matches_pinned_musics_order(self) -> None:
        map_path = REPO_ROOT / "audio" / "upstream-music-map.json"
        data = stage_music_assets.load_map(map_path)
        self.assertEqual(data["format"], 1)
        self.assertEqual(data["upstream_commit"], UPSTREAM_COMMIT)
        entries = data["entries"]
        self.assertEqual(len(entries), 16)
        self.assertEqual([entry["index"] for entry in entries], list(range(16)))
        self.assertEqual([entry["id"] for entry in entries], EXPECTED_MUSICS_ORDER)

        with tempfile.TemporaryDirectory() as tmp:
            upstream = self._create_fake_upstream(Path(tmp))
            validated = stage_music_assets.validate_map(data, upstream)
            self.assertEqual([e["id"] for e in validated], EXPECTED_MUSICS_ORDER)

    def test_parse_ogg_vorbis_ident_valid_and_multi_segment(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)

            stereo_file = tmp_dir / "stereo.ogg"
            stereo_file.write_bytes(build_ogg_bos_page(build_vorbis_ident_packet(channels=2, sample_rate=44100)))
            self.assertEqual(
                stage_music_assets.parse_ogg_vorbis_ident(stereo_file),
                {"channels": 2, "sample_rate": 44100, "bitrate_nominal": 128000},
            )

            mono_file = tmp_dir / "mono.ogg"
            mono_file.write_bytes(
                build_ogg_bos_page(build_vorbis_ident_packet(channels=1, sample_rate=22050, bitrate_nominal=64000))
            )
            self.assertEqual(
                stage_music_assets.parse_ogg_vorbis_ident(mono_file),
                {"channels": 1, "sample_rate": 22050, "bitrate_nominal": 64000},
            )

            # Page containing multiple segments where segment 0 is the 30-byte ident header.
            multi_seg_pkt = build_vorbis_ident_packet(channels=2, sample_rate=48000)
            split_file = tmp_dir / "multi_segment.ogg"
            split_file.write_bytes(
                build_ogg_bos_page(
                    multi_seg_pkt + b"\x03vorbis_comment_stub",
                    segment_table=bytes([30, 19]),
                )
            )
            self.assertEqual(
                stage_music_assets.parse_ogg_vorbis_ident(split_file),
                {"channels": 2, "sample_rate": 48000, "bitrate_nominal": 128000},
            )

    def test_parse_ogg_vorbis_ident_rejects_invalid_headers(self) -> None:
        good_pkt = build_vorbis_ident_packet()
        cases = [
            ("short", b"OggS"),
            ("bad_magic", build_ogg_bos_page(good_pkt, magic=b"RIFF")),
            ("bad_stream_version", build_ogg_bos_page(good_pkt, stream_version=1)),
            ("missing_bos", build_ogg_bos_page(good_pkt, header_type=0x00)),
            ("continuation_flag", build_ogg_bos_page(good_pkt, header_type=0x03)),
            ("zero_segments", build_ogg_bos_page(good_pkt, segment_table=b"")),
            ("unterminated_packet", build_ogg_bos_page(good_pkt, segment_table=bytes([255]))),
            (
                "oversized_multi_segment",
                build_ogg_bos_page(good_pkt + b"\x00" * 255, segment_table=bytes([255, 30])),
            ),
            ("truncated_payload", build_ogg_bos_page(good_pkt[:10], segment_table=bytes([30]))),
            ("wrong_vorbis_type", build_ogg_bos_page(build_vorbis_ident_packet(packet_type=3))),
            ("wrong_vorbis_magic", build_ogg_bos_page(build_vorbis_ident_packet(magic=b"opus!!"))),
            ("bad_vorbis_version", build_ogg_bos_page(build_vorbis_ident_packet(vorbis_version=1))),
            ("zero_channels", build_ogg_bos_page(build_vorbis_ident_packet(channels=0))),
            ("six_channels", build_ogg_bos_page(build_vorbis_ident_packet(channels=6))),
            ("low_sample_rate", build_ogg_bos_page(build_vorbis_ident_packet(sample_rate=1000))),
            ("high_sample_rate", build_ogg_bos_page(build_vorbis_ident_packet(sample_rate=96000))),
            (
                "inverted_blocksizes",
                build_ogg_bos_page(build_vorbis_ident_packet(blocksize_0=11, blocksize_1=8)),
            ),
            ("bad_framing_bit", build_ogg_bos_page(build_vorbis_ident_packet(framing_bit=0))),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            for name, content in cases:
                path = Path(tmp) / f"{name}.ogg"
                path.write_bytes(content)
                with self.subTest(case=name):
                    with self.assertRaises(stage_music_assets.MusicStagingError):
                        stage_music_assets.parse_ogg_vorbis_ident(path)

    def test_validate_map_rejects_malformed_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            upstream = self._create_fake_upstream(root, names=["boss1", "menu"])
            valid_entry_0 = {
                "index": 0,
                "id": "boss1",
                "source": "core/assets/music/boss1.ogg",
                "trigger_file": "core/src/mindustry/audio/SoundControl.java",
                "trigger_symbol": "Musics.boss1",
                "description": "Boss wave music rotation",
            }
            valid_entry_1 = {
                "index": 1,
                "id": "menu",
                "source": "core/assets/music/menu.ogg",
                "trigger_file": "core/src/mindustry/audio/SoundControl.java",
                "trigger_symbol": "Musics.menu",
                "description": "Main menu music",
            }

            invalid_maps = [
                ("bad_format", {"format": 2, "upstream_commit": UPSTREAM_COMMIT, "entries": [valid_entry_0]}),
                ("bad_commit", {"format": 1, "upstream_commit": "deadbeef", "entries": [valid_entry_0]}),
                ("empty_entries", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": []}),
                ("non_contiguous_index", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "index": 1}]}),
                ("duplicate_id", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [valid_entry_0, {**valid_entry_1, "id": "boss1"}]}),
                ("duplicate_source", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [valid_entry_0, {**valid_entry_1, "source": "core/assets/music/boss1.ogg"}]}),
                ("wrong_prefix", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "source": "core/assets/sounds/boss1.ogg"}]}),
                ("non_ogg_ext", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "source": "core/assets/music/boss1.wav"}]}),
                ("path_escape", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "source": "core/assets/music/../music/boss1.ogg"}]}),
                ("missing_source", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "source": "core/assets/music/missing.ogg"}]}),
                ("missing_trigger_file", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "trigger_file": "core/src/mindustry/Missing.java"}]}),
                ("missing_trigger_symbol", {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "entries": [{**valid_entry_0, "trigger_symbol": "Musics.nonexistent"}]}),
            ]
            for name, map_data in invalid_maps:
                with self.subTest(case=name):
                    with self.assertRaises(stage_music_assets.MusicStagingError):
                        stage_music_assets.validate_map(map_data, upstream)

    def test_stage_and_romfs_and_verify_only_end_to_end(self) -> None:
        map_path = REPO_ROOT / "audio" / "upstream-music-map.json"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            upstream = self._create_fake_upstream(root)
            out_dir = root / "staged"
            romfs_dir = root / "romfs"

            manifest = stage_music_assets.stage_music_map(
                upstream, map_path, out_dir, romfs_out=romfs_dir
            )
            self.assertEqual(manifest["total_files"], 16)
            self.assertFalse(manifest["verify_only"])
            self.assertTrue((out_dir / "music-manifest.json").is_file())
            self.assertTrue((romfs_dir / "audio" / "music-manifest.json").is_file())
            index_header = romfs_dir / "music-index.h"
            self.assertTrue(index_header.is_file())

            header_text = index_header.read_text(encoding="utf-8")
            self.assertIn("#define M3DS_MUSIC_INDEX_COUNT 16U", header_text)
            self.assertIn(f"#define M3DS_MUSIC_TOTAL_BYTES {manifest['total_bytes']}U", header_text)

            for entry in manifest["entries"]:
                src_bytes = (upstream / entry["source"]).read_bytes()
                staged_file = out_dir / entry["output"]
                romfs_file = romfs_dir / "audio" / "music" / f"{entry['id']}.ogg"
                self.assertEqual(staged_file.read_bytes(), src_bytes)
                self.assertEqual(romfs_file.read_bytes(), src_bytes)
                self.assertEqual(entry["source_sha256"], entry["output_sha256"])
                self.assertEqual(
                    hashlib.sha256(src_bytes).hexdigest(),
                    entry["output_sha256"],
                )
                self.assertIn(f'"romfs:/audio/music/{entry["id"]}.ogg"', header_text)

            # Verify-only validates without writing output files and rejects --romfs-out.
            verify_out = root / "verify-unused"
            verified = stage_music_assets.stage_music_map(
                upstream, map_path, verify_out, verify_only=True
            )
            self.assertTrue(verified["verify_only"])
            self.assertEqual(verified["total_files"], 16)
            self.assertFalse(verify_out.exists())

            with self.assertRaises(stage_music_assets.MusicStagingError):
                stage_music_assets.stage_music_map(
                    upstream, map_path, None, romfs_out=romfs_dir, verify_only=True
                )


if __name__ == "__main__":
    unittest.main()
