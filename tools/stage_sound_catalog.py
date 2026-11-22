#!/usr/bin/env python3
"""Stage the original Mindustry v160.8 sound registry and Ogg assets for RomFS."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

from stage_music_assets import MusicStagingError, parse_ogg_vorbis_ident, sha256_file


UPSTREAM_COMMIT = "19a0f40d44514f4802a8b79770456e7e32e34da0"
SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
DESKTOP_SHA256 = "6dd6da921d5021a84823456e05b477ee8de8511990d40de850b37dd0905152f2"
REFERENCE = Path(__file__).resolve().parents[1] / "research/upstream-runtime/host/UpstreamAudioBaseline.java"
SOUND_PATH = re.compile(r"sound\t(\d+)\t(sounds/(?:[\w-]+/)*[\w-]+\.ogg)", re.ASCII)


def stock_registry(java: str, server: Path) -> list[tuple[int, str]]:
    result = subprocess.run([java, "-cp", str(server), str(REFERENCE), "--sound-catalog"],
                            check=True, capture_output=True, encoding="utf-8")
    records = [(int(match[1]), match[2]) for line in result.stdout.splitlines()
               if (match := SOUND_PATH.fullmatch(line))]
    ids, paths = zip(*records) if records else ((), ())
    if ids != tuple(range(205)) or len(set(paths)) != 205:
        raise ValueError("The pinned Arc sound registry did not return 205 unique paths and ordered IDs")
    return records


def ogg_frames(data: bytes, name: str) -> int:
    """Read the final granule of the original single-stream Vorbis file."""
    serial = data[14:18]
    offset = 0
    while offset < len(data):
        if len(data) - offset < 27 or data[offset:offset + 4] != b"OggS" or data[offset + 4] != 0:
            break
        table = offset + 27
        end = table + data[offset + 26]
        if end > len(data) or serial != data[offset + 14:offset + 18]:
            break
        next_page = end + sum(data[table:end])
        if data[offset + 5] & 0x04:
            frames = struct.unpack_from("<q", data, offset + 6)[0]
            if next_page == len(data) and frames > 0:
                return frames
            break
        offset = next_page
    raise ValueError(f"Expected one complete Ogg stream with a final sample count in {name}")


def stage_sound_catalog(java: str, server: Path, desktop: Path, output: Path) -> list[dict]:
    if sha256_file(server) != SERVER_SHA256 or sha256_file(desktop) != DESKTOP_SHA256:
        raise ValueError("Expected the pinned v160.8 server-release.jar and Mindustry.jar")
    if output.exists():
        raise ValueError(f"Output already exists: {output}")
    registry = stock_registry(java, server)
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(dir=output.parent) as scratch, zipfile.ZipFile(desktop) as archive:
        staged = Path(scratch) / "romfs"
        entries = []
        for sound_id, name in registry:
            data = archive.read(name)
            path = staged / "audio" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            ident = parse_ogg_vorbis_ident(path, max_sample_rate=192000)
            entries.append({
                "id": sound_id,
                "source": "core/assets/" + name,
                "romfs": "romfs:/audio/" + name,
                "sha256": hashlib.sha256(data).hexdigest(),
                "bytes": len(data),
                "channels": ident["channels"],
                "sample_rate": ident["sample_rate"],
                "frames": ogg_frames(data, name),
            })
        manifest = {
            "format": 1,
            "upstream_commit": UPSTREAM_COMMIT,
            "server_sha256": SERVER_SHA256,
            "desktop_sha256": DESKTOP_SHA256,
            "sounds": entries,
        }
        (staged / "audio" / "sound-catalog.json").write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
        staged.rename(output)
    return entries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--desktop", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    try:
        entries = stage_sound_catalog(args.java, args.server, args.desktop, args.out)
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"Upstream sound registry failed: {error.stderr or error}\n")
    except (MusicStagingError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Sound catalog: {error}\n")
    print(f"UPSTREAM_SOUND_CATALOG sounds={len(entries)} bytes={sum(entry['bytes'] for entry in entries)}")


if __name__ == "__main__":
    main()
