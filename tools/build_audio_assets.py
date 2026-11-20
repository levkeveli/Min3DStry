#!/usr/bin/env python3
"""Convert pinned upstream Mindustry OGG assets to PCM16 with provenance.

The output is an internal build artifact, not an upstream audio format or a
3DS save/network format. See docs/audio/asset-conversion.md.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_MAP = Path("audio/upstream-audio-map.json")
DEFAULT_TRIGGERS = Path("audio/upstream-audio-triggers.json")
DEFAULT_RATE = 22050
VALID_ID = re.compile(r"^[a-z0-9][a-z0-9_.-]*$")
ALLOWED_PREFIXES = ("core/assets/sounds/", "core/assets/music/")


class AudioConversionError(RuntimeError):
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


def load_map(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AudioConversionError(f"cannot read audio map {path}: {error}") from error
    if not isinstance(data, dict):
        raise AudioConversionError("audio map root must be an object")
    return data


def load_triggers(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AudioConversionError(f"cannot read trigger map {path}: {error}") from error
    if not isinstance(data, dict):
        raise AudioConversionError("trigger map root must be an object")
    return data


def validate_triggers(data: dict, entries: list[dict], upstream: Path) -> dict[str, dict]:
    if type(data.get("format")) is not int or data.get("format") != 1:
        raise AudioConversionError("trigger map format must be 1")
    commit = data.get("upstream_commit")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise AudioConversionError("trigger map upstream_commit must be a 40-character SHA")
    counts = {entry["id"] for entry in entries}
    raw_events = data.get("events")
    if not isinstance(raw_events, list):
        raise AudioConversionError("trigger map events must be a list")

    result: dict[str, dict] = {}
    for raw in raw_events:
        if not isinstance(raw, dict):
            raise AudioConversionError("each trigger entry must be an object")
        entry_id = raw.get("id")
        source_file = raw.get("file")
        symbol = raw.get("symbol")
        description = raw.get("description", "")
        if entry_id not in counts:
            raise AudioConversionError(f"trigger references unknown audio id: {entry_id!r}")
        if entry_id in result:
            raise AudioConversionError(f"duplicate trigger for audio id: {entry_id}")
        if not isinstance(source_file, str) or not source_file.startswith("core/src/"):
            raise AudioConversionError(f"trigger file for {entry_id} must be under core/src/")
        normalized = Path(source_file).as_posix()
        if ".." in Path(normalized).parts:
            raise AudioConversionError(f"trigger file escapes upstream: {source_file}")
        if not isinstance(symbol, str) or not symbol:
            raise AudioConversionError(f"trigger symbol for {entry_id} is empty")
        if not isinstance(description, str):
            raise AudioConversionError(f"trigger description for {entry_id} must be a string")
        source_path = (upstream / normalized).resolve()
        try:
            source_path.relative_to(upstream.resolve())
        except ValueError as error:
            raise AudioConversionError(f"trigger file escapes upstream: {source_file}") from error
        if not source_path.is_file():
            raise AudioConversionError(f"missing upstream trigger file: {source_file}")
        text = source_path.read_text(encoding="utf-8", errors="replace")
        line_number = 0
        for index, line in enumerate(text.splitlines(), start=1):
            if symbol in line:
                line_number = index
                break
        if line_number == 0:
            raise AudioConversionError(
                f"trigger symbol {symbol!r} not found in {source_file} for {entry_id}"
            )
        result[entry_id] = {
            "file": normalized,
            "line": line_number,
            "symbol": symbol,
            "description": description,
        }

    missing = sorted(counts - set(result))
    if missing:
        raise AudioConversionError(f"trigger map missing audio ids: {', '.join(missing)}")
    return result


def validate_map(data: dict, upstream: Path) -> list[dict]:
    if type(data.get("format")) is not int or data.get("format") != 1:
        raise AudioConversionError("audio map format must be 1")
    commit = data.get("upstream_commit")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise AudioConversionError("audio map upstream_commit must be a 40-character SHA")
    default_rate = data.get("rate", DEFAULT_RATE)
    if type(default_rate) is not int or not 4000 <= default_rate <= 48000:
        raise AudioConversionError("audio map rate must be 4000..48000")

    entries = data.get("entries")
    if not isinstance(entries, list) or not entries:
        raise AudioConversionError("audio map entries must be a non-empty list")

    seen_ids: set[str] = set()
    seen_sources: set[str] = set()
    result: list[dict] = []
    for raw in entries:
        if not isinstance(raw, dict):
            raise AudioConversionError("each audio entry must be an object")
        entry_id = raw.get("id")
        source = raw.get("source")
        channels = raw.get("channels", 1)
        rate = raw.get("rate", default_rate)
        if not isinstance(entry_id, str) or not VALID_ID.fullmatch(entry_id):
            raise AudioConversionError(f"invalid audio id: {entry_id!r}")
        if entry_id in seen_ids:
            raise AudioConversionError(f"duplicate audio id: {entry_id}")
        if not isinstance(source, str) or not source.startswith(ALLOWED_PREFIXES):
            raise AudioConversionError(
                f"audio source for {entry_id} must be under core/assets/sounds/ or core/assets/music/"
            )
        normalized = Path(source).as_posix()
        if ".." in Path(normalized).parts or normalized in seen_sources:
            raise AudioConversionError(f"invalid or duplicate audio source: {source}")
        if type(channels) is not int or channels not in (1, 2):
            raise AudioConversionError(f"audio channels for {entry_id} must be 1 or 2")
        if type(rate) is not int or not 4000 <= rate <= 48000:
            raise AudioConversionError(f"audio rate for {entry_id} must be 4000..48000")

        source_path = (upstream / normalized).resolve()
        try:
            source_path.relative_to(upstream.resolve())
        except ValueError as error:
            raise AudioConversionError(f"audio source escapes upstream: {source}") from error
        if not source_path.is_file():
            raise AudioConversionError(f"missing upstream audio source: {source}")

        seen_ids.add(entry_id)
        seen_sources.add(normalized)
        result.append(
            {
                "id": entry_id,
                "source": normalized,
                "source_path": source_path,
                "channels": channels,
                "rate": rate,
            }
        )
    return result


def ffmpeg_version(ffmpeg: str) -> str:
    proc = subprocess.run(
        [ffmpeg, "-version"],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0 or not proc.stdout:
        raise AudioConversionError(f"ffmpeg is not runnable: {ffmpeg}")
    return proc.stdout.splitlines()[0]


def convert_entry(ffmpeg: str, entry: dict, output_dir: Path) -> dict:
    output = output_dir / f"{entry['id']}.pcm"
    command = [
        ffmpeg,
        "-nostdin",
        "-v",
        "error",
        "-y",
        "-i",
        str(entry["source_path"]),
        "-vn",
        "-ac",
        str(entry["channels"]),
        "-ar",
        str(entry["rate"]),
        "-f",
        "s16le",
        "-acodec",
        "pcm_s16le",
        str(output),
    ]
    proc = subprocess.run(command, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        raise AudioConversionError(
            f"ffmpeg failed for {entry['id']}: {proc.stderr.strip() or proc.stdout.strip()}"
        )
    size = output.stat().st_size
    frame_bytes = 2 * entry["channels"]
    if size == 0 or size % frame_bytes != 0:
        raise AudioConversionError(f"invalid PCM size {size} for {entry['id']}")
    result = {
        "id": entry["id"],
        "source": entry["source"],
        "source_sha256": sha256_file(entry["source_path"]),
        "output": output.name,
        "output_sha256": sha256_file(output),
        "channels": entry["channels"],
        "rate": entry["rate"],
        "frames": size // frame_bytes,
        "bytes": size,
    }
    if entry.get("trigger"):
        result["trigger"] = entry["trigger"]
    return result


def c_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def pack_romfs(manifest: dict, source_dir: Path, romfs_dir: Path) -> Path:
    """Copy PCM files under audio/ and write a C index for the mixer loader."""
    romfs_dir.mkdir(parents=True, exist_ok=True)
    audio_dir = romfs_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    for entry in manifest["entries"]:
        source = source_dir / entry["output"]
        if not source.is_file():
            raise AudioConversionError(f"missing converted PCM: {source}")
        shutil.copy2(source, audio_dir / entry["output"])
    shutil.copy2(source_dir / "audio-manifest.json", audio_dir / "audio-manifest.json")

    lines = [
        "/* Generated by tools/build_audio_assets.py. */",
        "#ifndef M3DS_AUDIO_INDEX_H",
        "#define M3DS_AUDIO_INDEX_H",
        "",
        '#include "audio/audio_registry.h"',
        "",
        f"#define M3DS_AUDIO_CLIP_COUNT {len(manifest['entries'])}U",
        f"#define M3DS_AUDIO_TOTAL_BYTES {sum(entry['bytes'] for entry in manifest['entries'])}U",
        "",
        "static const M3dsAudioIndexEntry m3ds_audio_index[M3DS_AUDIO_CLIP_COUNT] = {",
    ]
    for entry in manifest["entries"]:
        trigger = entry.get("trigger") or {}
        lines.append(
            "  {"
            + ", ".join(
                [
                    c_string(entry["id"]),
                    c_string(f"romfs:/audio/{entry['output']}"),
                    str(entry["rate"]),
                    str(entry["channels"]),
                    str(entry["frames"]),
                    c_string(trigger.get("file", "-")),
                    str(trigger.get("line", 0)),
                ]
            )
            + "},"
        )
    lines.extend(["};", "", "#endif", ""])
    index_path = romfs_dir / "audio-index.h"
    index_path.write_text("\n".join(lines), encoding="utf-8")
    return index_path


def convert_map(
    upstream: Path,
    map_path: Path,
    output_dir: Path,
    ffmpeg: str,
    triggers_path: Path | None = None,
    romfs_out: Path | None = None,
) -> dict:
    data = load_map(map_path)
    entries = validate_map(data, upstream)
    head = upstream_head(upstream)
    if head is not None and head != data["upstream_commit"]:
        raise AudioConversionError(
            f"upstream HEAD {head} does not match mapped commit {data['upstream_commit']}"
        )
    if triggers_path is not None:
        trigger_data = load_triggers(triggers_path)
        if trigger_data.get("upstream_commit") != data["upstream_commit"]:
            raise AudioConversionError("trigger map upstream_commit differs from audio map")
        triggers = validate_triggers(trigger_data, entries, upstream)
        entries = [dict(entry, trigger=triggers[entry["id"]]) for entry in entries]
    output_dir.mkdir(parents=True, exist_ok=True)
    converted = [convert_entry(ffmpeg, entry, output_dir) for entry in entries]
    converted.sort(key=lambda item: item["id"])
    version = ffmpeg_version(ffmpeg)
    manifest = {
        "format": 1,
        "upstream_commit": data["upstream_commit"],
        "ffmpeg_version": version,
        "entries": converted,
        "total_files": len(converted),
        "total_bytes": sum(item["bytes"] for item in converted),
    }
    (output_dir / "audio-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    if romfs_out is not None:
        pack_romfs(manifest, output_dir, romfs_out)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="pinned Mindustry checkout")
    parser.add_argument("--map", type=Path, default=DEFAULT_MAP, help="audio map JSON")
    parser.add_argument(
        "--triggers",
        type=Path,
        default=DEFAULT_TRIGGERS,
        help="pinned upstream trigger map (empty string disables)",
    )
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    parser.add_argument("--romfs-out", type=Path, default=None, help="RomFS-ready output directory")
    parser.add_argument("--ffmpeg", default=None, help="ffmpeg executable")
    args = parser.parse_args()

    ffmpeg = args.ffmpeg or shutil.which("ffmpeg")
    if not ffmpeg:
        print(
            "audio conversion: ffmpeg is required (install it or pass --ffmpeg)",
            file=sys.stderr,
        )
        return 2
    triggers = args.triggers if str(args.triggers) else None
    try:
        manifest = convert_map(
            args.upstream,
            args.map,
            args.out,
            ffmpeg,
            triggers_path=triggers,
            romfs_out=args.romfs_out,
        )
    except AudioConversionError as error:
        print(f"audio conversion failed: {error}", file=sys.stderr)
        return 1

    for entry in manifest["entries"]:
        print(
            f"AUDIO_CONVERT id={entry['id']} frames={entry['frames']} "
            f"bytes={entry['bytes']} rate={entry['rate']} channels={entry['channels']}"
        )
    print(
        f"AUDIO_ASSETS files={manifest['total_files']} "
        f"bytes={manifest['total_bytes']} manifest={args.out / 'audio-manifest.json'}"
    )
    if args.romfs_out is not None:
        print(f"AUDIO_ROMFS files={manifest['total_files']} index={args.romfs_out / 'audio-index.h'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
