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
    return {
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


def convert_map(upstream: Path, map_path: Path, output_dir: Path, ffmpeg: str) -> dict:
    data = load_map(map_path)
    entries = validate_map(data, upstream)
    head = upstream_head(upstream)
    if head is not None and head != data["upstream_commit"]:
        raise AudioConversionError(
            f"upstream HEAD {head} does not match mapped commit {data['upstream_commit']}"
        )
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
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="pinned Mindustry checkout")
    parser.add_argument("--map", type=Path, default=DEFAULT_MAP, help="audio map JSON")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    parser.add_argument("--ffmpeg", default=None, help="ffmpeg executable")
    args = parser.parse_args()

    ffmpeg = args.ffmpeg or shutil.which("ffmpeg")
    if not ffmpeg:
        print(
            "audio conversion: ffmpeg is required (install it or pass --ffmpeg)",
            file=sys.stderr,
        )
        return 2
    try:
        manifest = convert_map(args.upstream, args.map, args.out, ffmpeg)
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
