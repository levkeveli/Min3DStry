#!/usr/bin/env python3
"""Validate and stage pinned upstream Mindustry v160.8 OGG music assets for RomFS.

Stages the 16 original Vorbis-in-Ogg tracks unmodified with SHA-256 provenance,
Ogg/Vorbis identification-header validation, and a self-contained C11 RomFS index
so libvorbisidec (Tremor) or streamed PCM adapters can read them cleanly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

DEFAULT_MAP = Path("audio/upstream-music-map.json")
ALLOWED_PREFIX = "core/assets/music/"
ALLOWED_TRIGGER_PREFIX = "core/src/"
VALID_ID = re.compile(r"^[a-z0-9][a-z0-9_.-]*$")
MIN_SAMPLE_RATE = 4000
MAX_SAMPLE_RATE = 48000
OGG_HEADER_READ_LIMIT = 65536


class MusicStagingError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def upstream_head(upstream: Path) -> str | None:
    """Return the checkout HEAD when git is available, else None."""
    try:
        proc = subprocess.run(
            ["git", "-C", str(upstream), "rev-parse", "HEAD"],
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError:
        return None
    if proc.returncode != 0:
        return None
    head = proc.stdout.strip().lower()
    return head if re.fullmatch(r"[0-9a-f]{40}", head) else None


def parse_ogg_vorbis_ident(path: Path, *, max_sample_rate: int = MAX_SAMPLE_RATE) -> dict:
    """Parse and validate the BOS Ogg page and 30-byte Vorbis I ident packet."""
    try:
        with path.open("rb") as handle:
            data = handle.read(OGG_HEADER_READ_LIMIT)
    except OSError as error:
        raise MusicStagingError(f"cannot read Ogg file {path}: {error}") from error

    if len(data) < 28:
        raise MusicStagingError(f"OGG file too short: {path}")
    if data[0:4] != b"OggS":
        raise MusicStagingError(f"missing OggS capture pattern in {path}")
    if data[4] != 0:
        raise MusicStagingError(f"unsupported Ogg stream structure version {data[4]} in {path}")

    header_type = data[5]
    if (header_type & 0x02) == 0:
        raise MusicStagingError(f"first Ogg page missing BOS flag in {path}")
    if (header_type & 0x01) != 0:
        raise MusicStagingError(f"first Ogg page has continuation flag set in {path}")

    page_segments = data[26]
    if page_segments < 1:
        raise MusicStagingError(f"first Ogg page has zero segments in {path}")
    table_end = 27 + page_segments
    if len(data) < table_end:
        raise MusicStagingError(f"truncated Ogg segment table in {path}")

    packet_len = 0
    terminated = False
    for seg in data[27:table_end]:
        packet_len += seg
        if seg < 255:
            terminated = True
            break
    if not terminated or packet_len == 0:
        raise MusicStagingError(f"unterminated or empty Vorbis ident packet in {path}")

    packet_end = table_end + packet_len
    if len(data) < packet_end:
        raise MusicStagingError(f"truncated Vorbis ident packet payload in {path}")

    packet = data[table_end:packet_end]
    if len(packet) != 30 or packet[0] != 1 or packet[1:7] != b"vorbis":
        raise MusicStagingError(f"invalid Vorbis I identification header in {path}")

    (
        vorbis_version,
        channels,
        sample_rate,
        _bitrate_max,
        bitrate_nom,
        _bitrate_min,
        blocksize_byte,
        framing_byte,
    ) = struct.unpack("<IBIiiiBB", packet[7:30])

    if vorbis_version != 0:
        raise MusicStagingError(f"unsupported Vorbis version {vorbis_version} in {path}")
    if channels not in (1, 2):
        raise MusicStagingError(f"unsupported Vorbis channel count {channels} in {path}")
    if not MIN_SAMPLE_RATE <= sample_rate <= max_sample_rate:
        raise MusicStagingError(f"unsupported Vorbis sample rate {sample_rate} in {path}")

    blocksize_0 = blocksize_byte & 0x0F
    blocksize_1 = (blocksize_byte >> 4) & 0x0F
    if not (6 <= blocksize_0 <= 13 and 6 <= blocksize_1 <= 13 and blocksize_0 <= blocksize_1):
        raise MusicStagingError(
            f"invalid Vorbis blocksizes ({blocksize_0}, {blocksize_1}) in {path}"
        )
    if (framing_byte & 0x01) != 1:
        raise MusicStagingError(f"missing Vorbis framing bit in {path}")

    return {
        "channels": channels,
        "sample_rate": sample_rate,
        "bitrate_nominal": bitrate_nom,
    }


def _resolve_under_upstream(raw_rel: str, upstream_resolved: Path, label: str) -> tuple[str, Path]:
    if "\\" in raw_rel or ".." in Path(raw_rel).parts:
        raise MusicStagingError(f"{label} escapes upstream: {raw_rel}")
    normalized = Path(raw_rel).as_posix()
    if ".." in Path(normalized).parts:
        raise MusicStagingError(f"{label} escapes upstream: {raw_rel}")
    target = (upstream_resolved / normalized).resolve()
    try:
        target.relative_to(upstream_resolved)
    except ValueError as error:
        raise MusicStagingError(f"{label} escapes upstream: {raw_rel}") from error
    return normalized, target


def load_map(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MusicStagingError(f"cannot read music map {path}: {error}") from error
    if not isinstance(data, dict):
        raise MusicStagingError("music map root must be an object")
    return data


def validate_map(data: dict, upstream: Path) -> list[dict]:
    if type(data.get("format")) is not int or data.get("format") != 1:
        raise MusicStagingError("music map format must be 1")
    commit = data.get("upstream_commit")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise MusicStagingError("music map upstream_commit must be a 40-character lowercase SHA")

    entries = data.get("entries")
    if not isinstance(entries, list) or not entries:
        raise MusicStagingError("music map entries must be a non-empty list")

    upstream_resolved = upstream.resolve()
    seen_ids: set[str] = set()
    seen_sources: set[str] = set()
    result: list[dict] = []

    for expected_index, raw in enumerate(entries):
        if not isinstance(raw, dict):
            raise MusicStagingError("each music entry must be an object")
        entry_index = raw.get("index")
        entry_id = raw.get("id")
        source = raw.get("source")
        trigger_file = raw.get("trigger_file")
        trigger_symbol = raw.get("trigger_symbol")
        description = raw.get("description", "")

        if type(entry_index) is not int or entry_index != expected_index:
            raise MusicStagingError(
                f"music entry {entry_id!r} index {entry_index!r} != expected {expected_index}"
            )
        if not isinstance(entry_id, str) or not VALID_ID.fullmatch(entry_id):
            raise MusicStagingError(f"invalid music id: {entry_id!r}")
        if entry_id in seen_ids:
            raise MusicStagingError(f"duplicate music id: {entry_id}")

        if (
            not isinstance(source, str)
            or not source.startswith(ALLOWED_PREFIX)
            or not source.endswith(".ogg")
        ):
            raise MusicStagingError(
                f"music source for {entry_id} must be a .ogg under {ALLOWED_PREFIX}"
            )
        normalized_source, source_path = _resolve_under_upstream(
            source, upstream_resolved, f"music source for {entry_id}"
        )
        if not normalized_source.startswith(ALLOWED_PREFIX) or normalized_source in seen_sources:
            raise MusicStagingError(f"invalid or duplicate music source: {source}")
        if not source_path.is_file():
            raise MusicStagingError(f"missing upstream music source: {source}")

        if not isinstance(trigger_file, str) or not trigger_file.startswith(ALLOWED_TRIGGER_PREFIX):
            raise MusicStagingError(
                f"trigger_file for {entry_id} must be under {ALLOWED_TRIGGER_PREFIX}"
            )
        normalized_trigger, trigger_path = _resolve_under_upstream(
            trigger_file, upstream_resolved, f"trigger_file for {entry_id}"
        )
        if not normalized_trigger.startswith(ALLOWED_TRIGGER_PREFIX) or not trigger_path.is_file():
            raise MusicStagingError(f"missing upstream trigger file: {trigger_file}")
        if not isinstance(trigger_symbol, str) or not trigger_symbol:
            raise MusicStagingError(f"trigger_symbol for {entry_id} is empty")
        if not isinstance(description, str):
            raise MusicStagingError(f"description for {entry_id} must be a string")

        trigger_text = trigger_path.read_text(encoding="utf-8", errors="replace")
        trigger_line = 0
        for line_no, line_text in enumerate(trigger_text.splitlines(), start=1):
            if trigger_symbol in line_text:
                trigger_line = line_no
                break
        if trigger_line == 0:
            raise MusicStagingError(
                f"trigger symbol {trigger_symbol!r} not found in {trigger_file} for {entry_id}"
            )

        seen_ids.add(entry_id)
        seen_sources.add(normalized_source)
        result.append(
            {
                "index": entry_index,
                "id": entry_id,
                "source": normalized_source,
                "source_path": source_path,
                "trigger": {
                    "file": normalized_trigger,
                    "line": trigger_line,
                    "symbol": trigger_symbol,
                    "description": description,
                },
            }
        )

    return result


def c_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def pack_romfs(manifest: dict, source_dir: Path, romfs_dir: Path) -> Path:
    """Copy staged OGG tracks into audio/music/ and generate music-index.h."""
    romfs_dir.mkdir(parents=True, exist_ok=True)
    audio_dir = romfs_dir / "audio"
    music_dir = audio_dir / "music"
    music_dir.mkdir(parents=True, exist_ok=True)

    for entry in manifest["entries"]:
        src = source_dir / entry["output"]
        if not src.is_file():
            raise MusicStagingError(f"missing staged OGG: {src}")
        shutil.copy2(src, music_dir / f"{entry['id']}.ogg")

    manifest_src = source_dir / "music-manifest.json"
    if not manifest_src.is_file():
        raise MusicStagingError(f"missing music manifest: {manifest_src}")
    shutil.copy2(manifest_src, audio_dir / "music-manifest.json")

    lines = [
        "/* Generated by tools/stage_music_assets.py. */",
        "#ifndef M3DS_MUSIC_INDEX_H",
        "#define M3DS_MUSIC_INDEX_H",
        "",
        "#include <stdint.h>",
        "",
        "typedef struct M3dsMusicIndexEntry {",
        "  uint32_t index;",
        "  const char *id;",
        "  const char *romfs_path;",
        "  uint32_t sample_rate;",
        "  uint8_t channels;",
        "  uint32_t bytes;",
        "  const char *trigger_file;",
        "  uint32_t trigger_line;",
        "} M3dsMusicIndexEntry;",
        "",
        f"#define M3DS_MUSIC_INDEX_COUNT {len(manifest['entries'])}U",
        f"#define M3DS_MUSIC_TOTAL_BYTES {manifest['total_bytes']}U",
        "",
        "static const M3dsMusicIndexEntry m3ds_music_index[M3DS_MUSIC_INDEX_COUNT] = {",
    ]
    for entry in manifest["entries"]:
        trigger = entry["trigger"]
        lines.append(
            "  {"
            + ", ".join(
                [
                    f"{entry['index']}U",
                    c_string(entry["id"]),
                    c_string(f"romfs:/audio/music/{entry['id']}.ogg"),
                    f"{entry['sample_rate']}U",
                    f"{entry['channels']}U",
                    f"{entry['bytes']}U",
                    c_string(trigger["file"]),
                    f"{trigger['line']}U",
                ]
            )
            + "},"
        )
    lines.extend(["};", "", "#endif", ""])

    index_path = romfs_dir / "music-index.h"
    index_path.write_text("\n".join(lines), encoding="utf-8")
    return index_path


def stage_music_map(
    upstream: Path,
    map_path: Path,
    output_dir: Path | None,
    romfs_out: Path | None = None,
    verify_only: bool = False,
) -> dict:
    if verify_only and romfs_out is not None:
        raise MusicStagingError("--romfs-out cannot be combined with --verify-only")
    if not verify_only and output_dir is None:
        raise MusicStagingError("--out is required unless --verify-only is set")

    data = load_map(map_path)
    entries = validate_map(data, upstream)
    head = upstream_head(upstream)
    if head is not None and head != data["upstream_commit"]:
        raise MusicStagingError(
            f"upstream HEAD {head} does not match mapped commit {data['upstream_commit']}"
        )

    music_out: Path | None = None
    if not verify_only:
        assert output_dir is not None
        output_dir.mkdir(parents=True, exist_ok=True)
        music_out = output_dir / "music"
        music_out.mkdir(parents=True, exist_ok=True)

    staged: list[dict] = []
    for entry in entries:
        ident = parse_ogg_vorbis_ident(entry["source_path"])
        source_sha = sha256_file(entry["source_path"])
        output_rel = f"music/{entry['id']}.ogg"

        if verify_only:
            output_sha = source_sha
            byte_count = entry["source_path"].stat().st_size
        else:
            assert music_out is not None
            dst = music_out / f"{entry['id']}.ogg"
            shutil.copy2(entry["source_path"], dst)
            output_sha = sha256_file(dst)
            if output_sha != source_sha:
                raise MusicStagingError(f"copied OGG checksum mismatch for {entry['id']}")
            byte_count = dst.stat().st_size

        staged.append(
            {
                "index": entry["index"],
                "id": entry["id"],
                "source": entry["source"],
                "source_sha256": source_sha,
                "output": output_rel,
                "output_sha256": output_sha,
                "channels": ident["channels"],
                "sample_rate": ident["sample_rate"],
                "bitrate_nominal": ident["bitrate_nominal"],
                "bytes": byte_count,
                "trigger": entry["trigger"],
            }
        )

    manifest = {
        "format": 1,
        "upstream_commit": data["upstream_commit"],
        "verify_only": verify_only,
        "entries": staged,
        "total_files": len(staged),
        "total_bytes": sum(item["bytes"] for item in staged),
    }

    if not verify_only:
        assert output_dir is not None
        (output_dir / "music-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        if romfs_out is not None:
            pack_romfs(manifest, output_dir, romfs_out)

    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="pinned Mindustry checkout")
    parser.add_argument("--map", type=Path, default=DEFAULT_MAP, help="music map JSON")
    parser.add_argument("--out", type=Path, default=None, help="staging output directory")
    parser.add_argument("--romfs-out", type=Path, default=None, help="RomFS-ready output directory")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="validate upstream OGG files and triggers without writing staged outputs",
    )
    args = parser.parse_args()

    try:
        manifest = stage_music_map(
            args.upstream,
            args.map,
            args.out,
            romfs_out=args.romfs_out,
            verify_only=args.verify_only,
        )
    except MusicStagingError as error:
        print(f"music staging failed: {error}", file=sys.stderr)
        return 1

    for entry in manifest["entries"]:
        print(
            f"MUSIC_STAGE id={entry['id']} index={entry['index']} bytes={entry['bytes']} "
            f"rate={entry['sample_rate']} channels={entry['channels']} "
            f"sha256={entry['source_sha256'][:16]}"
        )
    if args.verify_only:
        print(
            f"MUSIC_ASSETS files={manifest['total_files']} "
            f"bytes={manifest['total_bytes']} verify_only=1"
        )
    else:
        assert args.out is not None
        print(
            f"MUSIC_ASSETS files={manifest['total_files']} "
            f"bytes={manifest['total_bytes']} manifest={args.out / 'music-manifest.json'}"
        )
    if args.romfs_out is not None:
        print(
            f"MUSIC_ROMFS files={manifest['total_files']} index={args.romfs_out / 'music-index.h'}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
