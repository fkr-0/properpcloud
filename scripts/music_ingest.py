#!/usr/bin/env python3
"""Resumable music ingestion and quality audit for the pCloud media library.

The source collection is treated as immutable. Files are copied to a local
staging directory, metadata is normalized there, and the completed copy is
then transferred to the library. A local SQLite state database provides
resume/idempotency without doing high-frequency SQLite writes over rclone
FUSE. Final human/machine reports are written to the library metadata folder.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unicodedata
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Protocol, Sequence

try:
    from mutagen import File as MutagenFile
    from mutagen.flac import Picture
    from mutagen.id3 import (
        TALB,
        TCON,
        TDRC,
        TIT2,
        TPE1,
        TPE2,
        TRCK,
        TXXX,
        ID3,
    )
    from mutagen.mp3 import MP3
except ImportError as exc:  # pragma: no cover - exercised by CLI environment.
    raise SystemExit("music_ingest.py requires mutagen (python-mutagen)") from exc


MUSIC_EXTENSIONS = {
    ".mp3",
    ".flac",
    ".wav",
    ".ogg",
    ".opus",
    ".aac",
    ".m4a",
    ".wma",
    ".alac",
    ".aiff",
    ".aif",
    ".ape",
}

LOSSLESS_EXTENSIONS = {".flac", ".wav", ".alac", ".aiff", ".aif", ".ape"}
ILLEGAL_FILENAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
SPACE_RUN = re.compile(r"[\s_]+")
LEADING_TRACK = re.compile(
    r"^\s*(?:\((\d{1,3})\)|(\d{1,3})\s*[-.]\s*|(?:track\s*)?(\d{1,3})\s*[-_]\s*)",
    re.IGNORECASE,
)
URL_ARTIFACT = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
LONG_HASH = re.compile(r"(?<![0-9A-Za-z])[0-9a-f]{24,64}(?![0-9A-Za-z])", re.IGNORECASE)
BRACKETED_SITE_TAG = re.compile(
    r"[\[(](?:www\.)?[A-Za-z0-9-]+\.(?:com|net|org|info|biz|ru|to|cc)[^\])]*[\])]",
    re.IGNORECASE,
)
BARE_SITE_TAG = re.compile(
    r"(?<!\w)(?:www\.)?[A-Za-z0-9-]+\.(?:com|net|org|info|biz|ru|to|cc)(?!\w)",
    re.IGNORECASE,
)
YEAR_RE = re.compile(r"(?:19|20)\d{2}")

CANONICAL_GENRES = {
    "ambient": "Ambient",
    "blues": "Blues",
    "classical": "Classical",
    "country": "Country",
    "dance": "Dance",
    "electronic": "Electronic",
    "electronica": "Electronic",
    "edm": "Electronic",
    "folk": "Folk",
    "funk": "Funk",
    "hip hop": "Hip-Hop",
    "hip-hop": "Hip-Hop",
    "hiphop": "Hip-Hop",
    "house": "House",
    "indie": "Indie",
    "jazz": "Jazz",
    "metal": "Metal",
    "pop": "Pop",
    "punk": "Punk",
    "r&b": "R&B",
    "rnb": "R&B",
    "rap": "Hip-Hop",
    "reggae": "Reggae",
    "rock": "Rock",
    "soul": "Soul",
    "soundtrack": "Soundtrack",
    "techno": "Techno",
    "trance": "Trance",
    "world": "World",
}

UNKNOWN_VALUES = {
    "",
    "unknown",
    "unknown artist",
    "unknown album",
    "untitled",
    "n/a",
    "none",
}

BACKUP_KEY = "PROPERPCLOUD_ORIGINAL_TAGS"
QUALITY_KEY = "PROPERPCLOUD_QUALITY"
PCLOUD_MOUNT = Path("/tmp/dib")
DEFAULT_COMPANION_CATALOG = Path(
    os.environ.get("PROPERPCLOUD_DISK_CATALOG", "~/.local/share/disk-catalog/catalog.db")
).expanduser()


@dataclass(slots=True)
class TrackMetadata:
    title: str = ""
    artist: str = ""
    album: str = ""
    album_artist: str = ""
    track: str = ""
    year: str = ""
    genre: str = ""
    compilation: bool = False


@dataclass(slots=True)
class QualityInfo:
    tier: str
    bitrate_kbps: int | None
    bitrate_mode: str | None
    duration_seconds: float | None
    source: str


@dataclass(slots=True)
class ProcessResult:
    source_path: str
    destination_path: str | None
    source_sha256: str | None
    status: str
    error: str | None
    filename_changed: bool
    tags_changed: bool
    artwork_removed: int
    metadata: TrackMetadata
    quality: QualityInfo | None
    duration_seconds: float | None
    size_bytes: int


class StatLike(Protocol):
    @property
    def st_size(self) -> int: ...

    @property
    def st_mtime_ns(self) -> int: ...


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        value = value[0] if value else ""
    if hasattr(value, "text"):
        value = value.text[0] if value.text else ""
    text = str(value)
    text = unicodedata.normalize("NFC", text)
    text = text.replace("\x00", " ")
    return " ".join(text.split()).strip()


def meaningful(value: str) -> bool:
    return clean_text(value).casefold() not in UNKNOWN_VALUES


def sanitize_component(value: str, *, max_chars: int = 180) -> str:
    """Return a pCloud-safe path component while retaining meaningful text."""

    value = unicodedata.normalize("NFC", clean_text(value))
    value = BRACKETED_SITE_TAG.sub(" ", value)
    value = URL_ARTIFACT.sub(" ", value)
    value = BARE_SITE_TAG.sub(" ", value)
    value = LONG_HASH.sub(" ", value)
    value = ILLEGAL_FILENAME_CHARS.sub(" ", value)
    value = SPACE_RUN.sub(" ", value).strip(" .")
    if not value:
        return "Unknown"
    if len(value) > max_chars:
        value = value[:max_chars].rstrip(" .")
    return value or "Unknown"


def split_track_prefix(stem: str) -> tuple[int | None, str]:
    match = LEADING_TRACK.match(stem)
    if not match:
        return None, stem
    number = next((int(v) for v in match.groups() if v is not None), None)
    return number, stem[match.end() :].strip(" -._")


def sanitize_filename(name: str, track_number: int | None = None) -> str:
    original = unicodedata.normalize("NFC", Path(name).name)
    suffix = Path(original).suffix.lower()
    stem = original[: -len(Path(original).suffix)] if Path(original).suffix else original
    prefix_track, stem = split_track_prefix(stem)
    track_number = track_number or prefix_track
    stem = sanitize_component(stem, max_chars=180)
    prefix = f"{track_number:02d} " if track_number and track_number > 0 else ""
    max_stem = max(1, 200 - len(prefix) - len(suffix))
    stem = stem[:max_stem].rstrip(" .") or "Unknown"
    return f"{prefix}{stem}{suffix}"


def parse_track_number(value: str) -> int | None:
    match = re.match(r"\s*(\d{1,4})", clean_text(value))
    if not match:
        return None
    number = int(match.group(1))
    return number if number > 0 else None


def normalize_year(value: str) -> str:
    match = YEAR_RE.search(clean_text(value))
    return match.group(0) if match else ""


def normalize_genre(value: str) -> str:
    value = clean_text(value)
    if not value:
        return ""
    key = re.sub(r"\s+", " ", value.casefold()).strip()
    if key in CANONICAL_GENRES:
        return CANONICAL_GENRES[key]
    # Keep an unknown but meaningful genre instead of destroying information.
    return value


def infer_from_path(path: Path) -> TrackMetadata:
    stem = path.stem
    track_number, clean_stem = split_track_prefix(stem)
    title = clean_stem
    artist = ""
    if " - " in clean_stem:
        left, right = clean_stem.split(" - ", 1)
        if meaningful(left) and meaningful(right):
            artist, title = left, right

    parents = [p.name for p in path.parents if p.name]
    album = ""
    parent_album = parents[0] if parents else ""
    parent_artist = parents[1] if len(parents) > 1 else ""

    generic_dirs = {
        "audio",
        "downloads",
        "download",
        "media",
        "music",
        "mnt",
        "raw",
        "run",
        "source",
        "tmp",
        "unsorted",
    }

    def useful_dir(value: str) -> bool:
        cleaned = clean_text(value)
        if not meaningful(cleaned) or cleaned.casefold() in generic_dirs:
            return False
        if re.fullmatch(r"(?:sd[a-z]\d*|ext[-_]?sd[a-z]\d*|disk[-_]?\d+)", cleaned, re.IGNORECASE):
            return False
        if re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", cleaned, re.IGNORECASE):
            return False
        return True

    # Directory inference is deliberately conservative.  A filename-level
    # "Artist - Title" signal is strong enough to accept a useful album parent.
    # Otherwise require a track-numbered album hierarchy or an explicit year
    # album marker so mount roots such as /mnt/sda1/Music never become artists.
    year_album = re.match(r"^\((\d{4})\)\s*(.+)$", parent_album)
    if artist and useful_dir(parent_album):
        album = parent_album
    elif useful_dir(parent_album) and useful_dir(parent_artist) and (track_number or year_album):
        album = parent_album
        artist = parent_artist

    year = ""
    year_match = re.match(r"^\((\d{4})\)\s*(.+)$", album)
    if year_match:
        year, album = year_match.groups()

    return TrackMetadata(
        title=clean_text(title),
        artist=clean_text(artist),
        album=clean_text(album),
        track=str(track_number or ""),
        year=year,
    )


def _first(mapping: Any, *keys: str) -> str:
    if mapping is None:
        return ""
    for key in keys:
        try:
            value = mapping.get(key)
        except (AttributeError, KeyError):
            value = None
        if value:
            return clean_text(value)
    return ""


def read_metadata(path: Path) -> TrackMetadata:
    audio = MutagenFile(path, easy=True)
    if audio is None:
        return TrackMetadata()

    tags = audio.tags or {}
    title = _first(tags, "title")
    artist = _first(tags, "artist")
    album = _first(tags, "album")
    album_artist = _first(tags, "albumartist", "album artist")
    track = _first(tags, "tracknumber", "track")
    year = _first(tags, "date", "year", "originaldate")
    genre = _first(tags, "genre")

    compilation = album_artist.casefold() == "various artists"
    return TrackMetadata(
        title=clean_text(title),
        artist=clean_text(artist),
        album=clean_text(album),
        album_artist=clean_text(album_artist),
        track=clean_text(track),
        year=clean_text(year),
        genre=clean_text(genre),
        compilation=compilation,
    )


def _metadata_backup(meta: TrackMetadata) -> str:
    return json.dumps(asdict(meta), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _id3_set_text(tags: ID3, frame_type: type, frame_id: str, value: str) -> bool:
    frame = tags.get(frame_id)
    old = clean_text(frame)
    encoding = getattr(frame, "encoding", None)
    if old == value and (frame is None or encoding == 3):
        return False
    tags.delall(frame_id)
    if value:
        tags.add(frame_type(encoding=3, text=[value]))
    return True


def _has_id3v1(path: Path) -> bool:
    if path.suffix.lower() != ".mp3":
        return False
    try:
        if path.stat().st_size < 128:
            return False
        with path.open("rb") as fh:
            fh.seek(-128, os.SEEK_END)
            return fh.read(3) == b"TAG"
    except OSError:
        return False


def _save_id3(tags: ID3, path: Path) -> None:
    """Save ID3v2.4 using the container-specific ID3 implementation."""

    try:
        tags.save(path, v1=0, v2_version=4)
    except TypeError:
        # AIFF's IFF-ID3 writer has no ID3v1 concept/parameter.
        tags.save(path, v2_version=4)


def _write_id3_metadata(path: Path, meta: TrackMetadata, original: TrackMetadata) -> bool | None:
    try:
        audio = MutagenFile(path, easy=False)
    except Exception:
        return None
    if audio is None:
        return None
    if audio.tags is None:
        try:
            audio.add_tags()
        except Exception:
            return None
    tags = audio.tags
    if not isinstance(tags, ID3):
        return None

    original_version = getattr(tags, "version", (2, 4, 0))
    format_changed = len(original_version) > 1 and original_version[1] != 4
    format_changed = format_changed or _has_id3v1(path)
    changed = False
    changed |= _id3_set_text(tags, TIT2, "TIT2", meta.title)
    changed |= _id3_set_text(tags, TPE1, "TPE1", meta.artist)
    changed |= _id3_set_text(tags, TALB, "TALB", meta.album)
    changed |= _id3_set_text(tags, TPE2, "TPE2", meta.album_artist)
    changed |= _id3_set_text(tags, TRCK, "TRCK", meta.track)
    changed |= _id3_set_text(tags, TDRC, "TDRC", meta.year)
    changed |= _id3_set_text(tags, TCON, "TCON", meta.genre)
    if changed or format_changed:
        tags.delall(f"TXXX:{BACKUP_KEY}")
        tags.add(TXXX(encoding=3, desc=BACKUP_KEY, text=[_metadata_backup(original)]))
    # v1=0 removes a legacy ID3v1 footer while preserving/rewriting v2.4.
    _save_id3(tags, path)
    return changed or format_changed


def _easy_set(tags: Any, key: str, value: str) -> bool:
    try:
        old = clean_text(tags.get(key))
    except Exception:
        return False
    if old == value:
        return False
    try:
        if value:
            tags[key] = [value]
        elif key in tags:
            del tags[key]
    except Exception:
        return False
    return True


def _write_easy_metadata(path: Path, meta: TrackMetadata, original: TrackMetadata) -> bool:
    try:
        audio = MutagenFile(path, easy=True)
    except Exception:
        return False
    if audio is None:
        return False
    if audio.tags is None:
        try:
            audio.add_tags()
        except Exception:
            return False
    tags = audio.tags
    changed = False
    changed |= _easy_set(tags, "title", meta.title)
    changed |= _easy_set(tags, "artist", meta.artist)
    changed |= _easy_set(tags, "album", meta.album)
    changed |= _easy_set(tags, "albumartist", meta.album_artist)
    changed |= _easy_set(tags, "tracknumber", meta.track)
    changed |= _easy_set(tags, "date", meta.year)
    changed |= _easy_set(tags, "genre", meta.genre)
    if changed:
        # Not every Easy* mapping accepts custom fields. The durable state DB
        # always retains this backup; embed it when the container allows it.
        with contextlib.suppress(Exception):
            tags[BACKUP_KEY.lower()] = [_metadata_backup(original)]
        audio.save()
        # Easy mappings intentionally expose only portable fields.  Re-open the
        # real comment map to persist the provenance field for FLAC/Vorbis-like
        # containers when that container supports arbitrary comments.
        with contextlib.suppress(Exception):
            raw = MutagenFile(path, easy=False)
            if raw is not None and raw.tags is not None and not isinstance(raw.tags, ID3):
                raw.tags[BACKUP_KEY] = [_metadata_backup(original)]
                raw.save()
    return changed


def write_metadata(path: Path, meta: TrackMetadata, original: TrackMetadata) -> bool:
    if path.suffix.lower() in {".mp3", ".wav", ".aiff", ".aif"}:
        id3_result = _write_id3_metadata(path, meta, original)
        if id3_result is not None:
            return id3_result
    return _write_easy_metadata(path, meta, original)


def remove_oversized_artwork(path: Path, max_bytes: int = 500 * 1024) -> int:
    removed = 0
    try:
        audio = MutagenFile(path, easy=False)
    except Exception:
        return 0
    if audio is None:
        return 0

    tags = audio.tags
    if isinstance(tags, ID3):
        for frame in list(tags.getall("APIC")):
            if len(frame.data) > max_bytes:
                tags.delall(frame.HashKey)
                removed += 1
        if removed:
            _save_id3(tags, path)
        return removed

    pictures = getattr(audio, "pictures", None)
    if pictures is not None:
        keep = [picture for picture in pictures if len(picture.data) <= max_bytes]
        removed += len(pictures) - len(keep)
        if removed:
            audio.clear_pictures()
            for picture in keep:
                audio.add_picture(picture)
            audio.save()
        return removed

    # Ogg/Opus often stores FLAC picture blocks as base64 comments.
    if tags is not None:
        for key in ("metadata_block_picture", "METADATA_BLOCK_PICTURE"):
            with contextlib.suppress(Exception):
                values = list(tags.get(key, []))
                keep_values: list[str] = []
                for value in values:
                    try:
                        picture = Picture(base64.b64decode(value))
                    except Exception:
                        keep_values.append(value)
                        continue
                    if len(picture.data) > max_bytes:
                        removed += 1
                    else:
                        keep_values.append(value)
                if len(keep_values) != len(values):
                    if keep_values:
                        tags[key] = keep_values
                    elif key in tags:
                        del tags[key]
                    audio.save()
    return removed


def normalized_metadata(meta: TrackMetadata, source: Path) -> TrackMetadata:
    inferred = infer_from_path(source)
    placeholder_title = bool(re.fullmatch(r"track\s*\d+", clean_text(meta.title), re.IGNORECASE))
    title = meta.title if meaningful(meta.title) and not placeholder_title else inferred.title
    artist = meta.artist if meaningful(meta.artist) else inferred.artist
    album = meta.album if meaningful(meta.album) else inferred.album
    album_artist = meta.album_artist if meaningful(meta.album_artist) else ""
    compilation = meta.compilation or album_artist.casefold() == "various artists"
    if artist.casefold() == "various artists":
        album_artist = album_artist or "Various Artists"
        compilation = True
        if meaningful(inferred.artist):
            # A common compilation failure mode places the album artist into
            # TPE1. Prefer a real per-track artist encoded in the filename.
            artist = inferred.artist
    track = str(parse_track_number(meta.track) or parse_track_number(inferred.track) or "")
    year = normalize_year(meta.year or inferred.year)
    genre = normalize_genre(meta.genre)
    return TrackMetadata(
        title=clean_text(title),
        artist=clean_text(artist),
        album=clean_text(album),
        album_artist=clean_text(album_artist),
        track=track,
        year=year,
        genre=genre,
        compilation=compilation,
    )


def classify_quality(bitrate_kbps: int | None, bitrate_mode: str | None = None) -> str:
    mode = (bitrate_mode or "").upper()
    # When a LAME/Xing profile is actually recoverable, V0/V1/V2 is stronger
    # evidence than average bitrate (quiet material can have a low VBR average).
    if re.search(r"\bV[012]\b", mode):
        return "GOOD"
    if bitrate_kbps is None:
        return "UNKNOWN"
    if bitrate_kbps < 192:
        return "LOW_QUALITY"
    if bitrate_kbps >= 318 and "CBR" in mode:
        return "HIGH"
    if bitrate_kbps >= 256:
        return "GOOD"
    return "ACCEPTABLE"


def probe_mp3_quality(path: Path) -> QualityInfo:
    bitrate: int | None = None
    bitrate_mode: str | None = None
    duration: float | None = None
    source = "mutagen"
    try:
        mp3 = MP3(path)
        if mp3.info:
            raw_bitrate = getattr(mp3.info, "bitrate", None)
            if raw_bitrate:
                bitrate = int(round(raw_bitrate / 1000))
            raw_duration = getattr(mp3.info, "length", None)
            if raw_duration:
                duration = float(raw_duration)
            raw_mode = getattr(mp3.info, "bitrate_mode", None)
            if raw_mode is not None:
                bitrate_mode = str(raw_mode).split(".")[-1].upper()
            encoder_settings = clean_text(getattr(mp3.info, "encoder_settings", ""))
            profile = re.search(r"(?:^|\s)-?V\s*([0-9])(?:\s|$)", encoder_settings, re.IGNORECASE)
            if profile and bitrate_mode and "VBR" in bitrate_mode:
                bitrate_mode = f"VBR V{profile.group(1)}"
    except Exception:
        pass

    # ffprobe is a second independent container/stream measurement. Prefer its
    # explicit stream bit rate when available; otherwise retain mutagen's MPEG
    # frame-derived average. This avoids trusting a filename or tag field.
    try:
        proc = subprocess.run(
            [
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "a:0",
                "-show_entries",
                "stream=bit_rate,duration:format=bit_rate,duration",
                "-of",
                "json",
                str(path),
            ],
            check=True,
            text=True,
            capture_output=True,
            timeout=30,
        )
        data = json.loads(proc.stdout)
        stream = (data.get("streams") or [{}])[0]
        fmt = data.get("format") or {}
        measured = stream.get("bit_rate") or fmt.get("bit_rate")
        if measured:
            bitrate = int(round(float(measured) / 1000))
            source = "ffprobe"
        measured_duration = stream.get("duration") or fmt.get("duration")
        if measured_duration:
            duration = float(measured_duration)
    except (FileNotFoundError, subprocess.SubprocessError, ValueError, json.JSONDecodeError):
        pass

    return QualityInfo(
        tier=classify_quality(bitrate, bitrate_mode),
        bitrate_kbps=bitrate,
        bitrate_mode=bitrate_mode,
        duration_seconds=duration,
        source=source,
    )


def probe_audio_duration(path: Path) -> float | None:
    """Measure playable duration for any Mutagen/ffprobe-supported audio file."""

    with contextlib.suppress(Exception):
        audio = MutagenFile(path, easy=False)
        length = getattr(getattr(audio, "info", None), "length", None)
        if length is not None and float(length) >= 0:
            return float(length)
    try:
        proc = subprocess.run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(path),
            ],
            check=True,
            text=True,
            capture_output=True,
            timeout=30,
        )
        value = float(proc.stdout.strip())
        return value if value >= 0 else None
    except (FileNotFoundError, subprocess.SubprocessError, ValueError):
        return None


def mark_mp3_quality(path: Path, tier: str) -> None:
    if path.suffix.lower() != ".mp3":
        return
    try:
        audio = MP3(path)
        if audio.tags is None:
            audio.add_tags()
        assert audio.tags is not None
        audio.tags.delall(f"TXXX:{QUALITY_KEY}")
        audio.tags.add(TXXX(encoding=3, desc=QUALITY_KEY, text=[tier]))
        audio.tags.save(path, v1=0, v2_version=4)
    except Exception:
        # Quality still exists in the audit report/state DB if tagging fails.
        return


def file_sha256(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while chunk := fh.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def track_identity(meta: TrackMetadata) -> str | None:
    if not meaningful(meta.artist) or not meaningful(meta.title):
        return None
    parts = [meta.artist, meta.title]
    normalized = "|".join(
        re.sub(r"[^\w]+", " ", unicodedata.normalize("NFKC", part).casefold()).strip()
        for part in parts
    )
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def destination_for(meta: TrackMetadata, source: Path, library_root: Path) -> Path:
    track_number = parse_track_number(meta.track)
    title = sanitize_component(meta.title if meaningful(meta.title) else source.stem)
    filename = sanitize_filename(f"{title}{source.suffix.lower()}", track_number)

    if meta.compilation or meta.album_artist.casefold() == "various artists":
        album = sanitize_component(meta.album if meaningful(meta.album) else "Unknown Compilation")
        album_dir = f"({meta.year}) {album}" if meta.year else album
        return library_root / "Compilations" / album_dir / filename

    if meaningful(meta.artist):
        artist = sanitize_component(meta.artist)
        if meaningful(meta.album):
            album = sanitize_component(meta.album)
            album_dir = f"({meta.year}) {album}" if meta.year else album
            return library_root / artist / album_dir / filename
        return library_root / artist / "Singles" / filename

    # Use a short source-path fingerprint to make unsorted collisions stable.
    suffix = hashlib.sha256(str(source).encode("utf-8")).hexdigest()[:10]
    unsorted_name = sanitize_filename(f"{source.stem} [{suffix}]{source.suffix.lower()}", track_number)
    return library_root / "Unsorted" / unsorted_name


def collision_safe_destination(destination: Path, source_sha256: str) -> Path:
    if not destination.exists():
        return destination
    try:
        if file_sha256(destination) == source_sha256:
            return destination
    except OSError:
        pass
    suffix = source_sha256[:10]
    return destination.with_name(f"{destination.stem} [{suffix}]{destination.suffix}")


def iter_music_files(roots: Iterable[Path]) -> Iterator[Path]:
    seen: set[tuple[int, int]] = set()
    for root in roots:
        root = root.expanduser().resolve()
        if not root.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
            # Avoid obvious system/recycle metadata without pruning legitimate
            # user directories based on arbitrary names.
            dirnames[:] = [
                name
                for name in dirnames
                if name not in {"$RECYCLE.BIN", "System Volume Information", ".Trash-1000"}
            ]
            for filename in filenames:
                path = Path(dirpath) / filename
                if path.suffix.lower() not in MUSIC_EXTENSIONS:
                    continue
                try:
                    stat = path.stat()
                except OSError:
                    continue
                inode_key = (stat.st_dev, stat.st_ino)
                if inode_key in seen:
                    continue
                seen.add(inode_key)
                yield path


def publish_file(stage: Path, destination: Path, source_sha256: str) -> None:
    """Publish one staged object without exposing a partial final filename."""

    ensure_pcloud_target_available(destination)
    partial = destination.with_name(f".{destination.name}.properpcloud-partial-{source_sha256[:12]}")
    try:
        shutil.copy2(stage, partial)
        os.replace(partial, destination)
    finally:
        with contextlib.suppress(OSError):
            partial.unlink()


def path_is_within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def ensure_pcloud_target_available(target: Path) -> None:
    """Fail closed when a default pCloud target would hit underlying /tmp."""

    if path_is_within(target, PCLOUD_MOUNT) and not os.path.ismount(PCLOUD_MOUNT):
        raise RuntimeError(f"pCloud mount is not active at {PCLOUD_MOUNT}; refusing local fallback write")


def atomic_write_text(path: Path, content: str) -> None:
    ensure_pcloud_target_available(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".partial", dir=path.parent)
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(content)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(temp, path)
    finally:
        with contextlib.suppress(OSError):
            temp.unlink()


def _walk_block_devices(nodes: Sequence[dict[str, Any]], parent_external: bool = False) -> Iterator[str]:
    for node in nodes:
        name = str(node.get("name") or "")
        node_type = str(node.get("type") or "")
        transport = str(node.get("tran") or "").lower()
        removable = bool(node.get("rm"))
        external = parent_external or removable or transport in {"usb", "firewire"} or name.startswith("sd")
        if node_type == "loop":
            external = True
        if external:
            mountpoints = node.get("mountpoints") or []
            if isinstance(mountpoints, str):
                mountpoints = [mountpoints]
            for mountpoint in mountpoints:
                if mountpoint and mountpoint != "/":
                    yield str(mountpoint)
        children = node.get("children") or []
        yield from _walk_block_devices(children, external)


def discover_external_mounts() -> list[Path]:
    try:
        proc = subprocess.run(
            ["lsblk", "-J", "-o", "NAME,TYPE,RM,TRAN,MOUNTPOINTS"],
            check=True,
            text=True,
            capture_output=True,
            timeout=15,
        )
        data = json.loads(proc.stdout)
    except (FileNotFoundError, subprocess.SubprocessError, json.JSONDecodeError):
        return []
    mounts: list[Path] = []
    seen: set[str] = set()
    for mount in _walk_block_devices(data.get("blockdevices") or []):
        resolved = str(Path(mount).resolve())
        if resolved not in seen and Path(resolved).is_dir():
            mounts.append(Path(resolved))
            seen.add(resolved)
    return mounts


def iter_catalog_files(db_path: Path) -> Iterator[Path]:
    """Read common path columns from an arbitrary SQLite file catalog."""

    uri = f"file:{db_path.resolve()}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    try:
        tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        ]
        preferred = ("path", "file_path", "absolute_path", "source_path", "full_path", "filepath")
        relative_preferred = ("relative_path", "relpath", "source_relative_path")
        root_preferred = ("source_root", "mount_root", "mount_path", "root_path")
        emitted: set[str] = set()
        for table in tables:
            escaped_table = table.replace('"', '""')
            columns = [row[1] for row in connection.execute(f'PRAGMA table_info("{escaped_table}")')]
            path_column = next((name for name in preferred if name in columns), None)
            relative_column = next((name for name in relative_preferred if name in columns), None)
            root_column = next((name for name in root_preferred if name in columns), None)
            if not path_column and not (relative_column and root_column):
                continue
            if path_column:
                escaped_col = path_column.replace('"', '""')
                escaped_root = root_column.replace('"', '""') if root_column else None
                select = f'"{escaped_col}"' + (f', "{escaped_root}"' if escaped_root else '')
                query = f'SELECT {select} FROM "{escaped_table}" WHERE "{escaped_col}" IS NOT NULL'
            else:
                assert relative_column is not None and root_column is not None
                escaped_rel = relative_column.replace('"', '""')
                escaped_root = root_column.replace('"', '""')
                query = (
                    f'SELECT "{escaped_rel}", "{escaped_root}" FROM "{escaped_table}" '
                    f'WHERE "{escaped_rel}" IS NOT NULL AND "{escaped_root}" IS NOT NULL'
                )
            for row in connection.execute(query):
                raw_path = row[0]
                raw_root = row[1] if len(row) > 1 else None
                if not isinstance(raw_path, str):
                    continue
                path = Path(raw_path)
                if not path.is_absolute() and isinstance(raw_root, str) and raw_root:
                    path = Path(raw_root) / path
                if path.suffix.lower() not in MUSIC_EXTENSIONS:
                    continue
                normalized = str(path)
                if normalized in emitted:
                    continue
                emitted.add(normalized)
                yield path
    finally:
        connection.close()


def initialize_state(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS ingest_files (
            source_path TEXT PRIMARY KEY,
            source_size INTEGER NOT NULL,
            source_mtime_ns INTEGER NOT NULL,
            source_sha256 TEXT,
            status TEXT NOT NULL,
            destination_path TEXT,
            error TEXT,
            original_tags_json TEXT,
            normalized_tags_json TEXT,
            filename_changed INTEGER NOT NULL DEFAULT 0,
            tags_changed INTEGER NOT NULL DEFAULT 0,
            artwork_removed INTEGER NOT NULL DEFAULT 0,
            quality_tier TEXT,
            bitrate_kbps INTEGER,
            bitrate_mode TEXT,
            duration_seconds REAL,
            track_identity TEXT,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ingest_files_sha256_idx ON ingest_files(source_sha256);
        CREATE INDEX IF NOT EXISTS ingest_files_track_identity_idx ON ingest_files(track_identity);

        CREATE TABLE IF NOT EXISTS ingest_runs (
            run_id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at TEXT NOT NULL,
            finished_at TEXT,
            dry_run INTEGER NOT NULL,
            discovered INTEGER NOT NULL DEFAULT 0,
            ingested INTEGER NOT NULL DEFAULT 0,
            skipped INTEGER NOT NULL DEFAULT 0,
            failed INTEGER NOT NULL DEFAULT 0
        );
        """
    )
    connection.commit()
    return connection


def acquire_state_lock(db_path: Path) -> Any:
    """Take a non-blocking process lock for one writable ingest state DB."""

    lock_path = Path(f"{db_path}.lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+", encoding="utf-8")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        handle.seek(0)
        holder = handle.read().strip() or "unknown"
        handle.close()
        raise RuntimeError(f"music ingest is already running for this state DB (holder {holder})") from exc
    handle.seek(0)
    handle.truncate()
    handle.write(f"pid={os.getpid()} started={utc_now()}\n")
    handle.flush()
    return handle


def release_state_lock(handle: Any) -> None:
    with contextlib.suppress(OSError):
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    handle.close()


def is_resumable_skip(connection: sqlite3.Connection, source: Path) -> bool:
    try:
        stat = source.stat()
    except OSError:
        return False
    row = connection.execute(
        "SELECT source_size, source_mtime_ns, status, destination_path FROM ingest_files WHERE source_path = ?",
        (str(source),),
    ).fetchone()
    if not row or row["status"] not in {"INGESTED", "DUPLICATE"}:
        return False
    if row["source_size"] != stat.st_size or row["source_mtime_ns"] != stat.st_mtime_ns:
        return False
    if row["destination_path"]:
        return Path(row["destination_path"]).exists()
    return False


def duplicate_destination(connection: sqlite3.Connection, source_sha256: str) -> str | None:
    row = connection.execute(
        """
        SELECT destination_path FROM ingest_files
        WHERE source_sha256 = ? AND status = 'INGESTED' AND destination_path IS NOT NULL
        ORDER BY updated_at DESC LIMIT 1
        """,
        (source_sha256,),
    ).fetchone()
    if row and Path(row["destination_path"]).exists():
        return str(row["destination_path"])
    return None


def save_result(
    connection: sqlite3.Connection,
    result: ProcessResult,
    stat: StatLike,
    original: TrackMetadata,
) -> None:
    quality = result.quality
    connection.execute(
        """
        INSERT INTO ingest_files (
            source_path, source_size, source_mtime_ns, source_sha256, status,
            destination_path, error, original_tags_json, normalized_tags_json,
            filename_changed, tags_changed, artwork_removed, quality_tier,
            bitrate_kbps, bitrate_mode, duration_seconds, track_identity, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(source_path) DO UPDATE SET
            source_size=excluded.source_size,
            source_mtime_ns=excluded.source_mtime_ns,
            source_sha256=excluded.source_sha256,
            status=excluded.status,
            destination_path=excluded.destination_path,
            error=excluded.error,
            original_tags_json=excluded.original_tags_json,
            normalized_tags_json=excluded.normalized_tags_json,
            filename_changed=excluded.filename_changed,
            tags_changed=excluded.tags_changed,
            artwork_removed=excluded.artwork_removed,
            quality_tier=excluded.quality_tier,
            bitrate_kbps=excluded.bitrate_kbps,
            bitrate_mode=excluded.bitrate_mode,
            duration_seconds=excluded.duration_seconds,
            track_identity=excluded.track_identity,
            updated_at=excluded.updated_at
        """,
        (
            result.source_path,
            stat.st_size,
            stat.st_mtime_ns,
            result.source_sha256,
            result.status,
            result.destination_path,
            result.error,
            _metadata_backup(original),
            _metadata_backup(result.metadata),
            int(result.filename_changed),
            int(result.tags_changed),
            result.artwork_removed,
            quality.tier if quality else None,
            quality.bitrate_kbps if quality else None,
            quality.bitrate_mode if quality else None,
            result.duration_seconds,
            track_identity(result.metadata),
            utc_now(),
        ),
    )
    connection.commit()


def copy_cover_if_available(source: Path, destination_dir: Path, max_bytes: int = 500 * 1024) -> None:
    target = destination_dir / "cover.jpg"
    ensure_pcloud_target_available(target)
    if target.exists():
        return
    candidates = ["cover.jpg", "folder.jpg", "front.jpg", "Cover.jpg", "Folder.jpg"]
    for name in candidates:
        candidate = source.parent / name
        try:
            if candidate.is_file() and candidate.stat().st_size <= max_bytes:
                publish_file(candidate, target, file_sha256(candidate))
                return
        except OSError:
            continue


def process_one(
    source: Path,
    library_root: Path,
    staging_root: Path,
    connection: sqlite3.Connection,
    *,
    dry_run: bool = False,
) -> ProcessResult:
    stat = source.stat()
    original = read_metadata(source)
    normalized = normalized_metadata(original, source)
    source_sha = file_sha256(source)

    duplicate = duplicate_destination(connection, source_sha)
    if duplicate:
        result = ProcessResult(
            source_path=str(source),
            destination_path=duplicate,
            source_sha256=source_sha,
            status="DUPLICATE",
            error=None,
            filename_changed=False,
            tags_changed=False,
            artwork_removed=0,
            metadata=normalized,
            quality=None,
            duration_seconds=None,
            size_bytes=stat.st_size,
        )
        save_result(connection, result, stat, original)
        return result

    destination = destination_for(normalized, source, library_root)
    destination = collision_safe_destination(destination, source_sha)
    filename_changed = source.name != destination.name

    if dry_run:
        quality = probe_mp3_quality(source) if source.suffix.lower() == ".mp3" else None
        duration = quality.duration_seconds if quality else probe_audio_duration(source)
        return ProcessResult(
            source_path=str(source),
            destination_path=str(destination),
            source_sha256=source_sha,
            status="DRY_RUN",
            error=None,
            filename_changed=filename_changed,
            tags_changed=normalized != original,
            artwork_removed=0,
            metadata=normalized,
            quality=quality,
            duration_seconds=duration,
            size_bytes=stat.st_size,
        )

    staging_root.mkdir(parents=True, exist_ok=True)
    ensure_pcloud_target_available(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, stage_name = tempfile.mkstemp(prefix="track-", suffix=source.suffix.lower(), dir=staging_root)
    os.close(fd)
    stage = Path(stage_name)
    try:
        shutil.copy2(source, stage)
        tags_changed = write_metadata(stage, normalized, original)
        artwork_removed = remove_oversized_artwork(stage)
        quality = probe_mp3_quality(stage) if source.suffix.lower() == ".mp3" else None
        duration = quality.duration_seconds if quality else probe_audio_duration(stage)
        if quality:
            mark_mp3_quality(stage, quality.tier)

        # Publish through a deterministic temporary name so interruption does
        # not leave an apparently valid final object on pCloud/FUSE.
        publish_file(stage, destination, source_sha)
        copy_cover_if_available(source, destination.parent)
        result = ProcessResult(
            source_path=str(source),
            destination_path=str(destination),
            source_sha256=source_sha,
            status="INGESTED",
            error=None,
            filename_changed=filename_changed,
            tags_changed=tags_changed,
            artwork_removed=artwork_removed,
            metadata=normalized,
            quality=quality,
            duration_seconds=duration,
            size_bytes=stat.st_size,
        )
        save_result(connection, result, stat, original)
        return result
    finally:
        with contextlib.suppress(OSError):
            stage.unlink()


def record_failure(connection: sqlite3.Connection, source: Path, exc: Exception) -> ProcessResult:
    stat: StatLike
    try:
        stat = source.stat()
    except OSError:
        # A source can disappear between discovery and processing.  Avoid
        # letting the failure recorder itself violate SQLite NOT NULL columns.
        class MissingStat:
            st_size = 0
            st_mtime_ns = 0

        stat = MissingStat()
    empty = TrackMetadata()
    result = ProcessResult(
        source_path=str(source),
        destination_path=None,
        source_sha256=None,
        status="FAILED",
        error=f"{type(exc).__name__}: {exc}",
        filename_changed=False,
        tags_changed=False,
        artwork_removed=0,
        metadata=empty,
        quality=None,
        duration_seconds=None,
        size_bytes=getattr(stat, "st_size", 0),
    )
    save_result(connection, result, stat, empty)
    return result


def replacement_candidates(connection: sqlite3.Connection) -> dict[str, str]:
    rows = connection.execute(
        """
        SELECT source_path, destination_path, track_identity, quality_tier,
               bitrate_kbps, normalized_tags_json
        FROM ingest_files
        WHERE status = 'INGESTED' AND track_identity IS NOT NULL
        """
    ).fetchall()
    by_track: dict[str, list[sqlite3.Row]] = {}
    for row in rows:
        by_track.setdefault(row["track_identity"], []).append(row)

    rank = {"LOW_QUALITY": 1, "ACCEPTABLE": 2, "GOOD": 3, "HIGH": 4}
    replacements: dict[str, str] = {}
    for candidates in by_track.values():
        low = [row for row in candidates if row["quality_tier"] == "LOW_QUALITY"]
        if not low:
            continue
        better = [
            row
            for row in candidates
            if rank.get(row["quality_tier"], 0) > rank["LOW_QUALITY"]
            or Path(row["source_path"]).suffix.lower() in LOSSLESS_EXTENSIONS
        ]
        if not better:
            continue
        best = max(
            better,
            key=lambda row: (
                Path(row["source_path"]).suffix.lower() in LOSSLESS_EXTENSIONS,
                rank.get(row["quality_tier"], 0),
                row["bitrate_kbps"] or 0,
            ),
        )
        for row in low:
            if row["destination_path"]:
                replacements[row["destination_path"]] = best["destination_path"] or best["source_path"]
    return replacements


def write_reports(connection: sqlite3.Connection, metadata_root: Path) -> tuple[Path, Path, Path]:
    ensure_pcloud_target_available(metadata_root)
    metadata_root.mkdir(parents=True, exist_ok=True)
    rows = connection.execute(
        "SELECT * FROM ingest_files WHERE status IN ('INGESTED', 'DUPLICATE', 'FAILED') ORDER BY source_path"
    ).fetchall()
    replacements = replacement_candidates(connection)

    quality_counts = Counter(row["quality_tier"] or "NOT_MP3" for row in rows if row["status"] == "INGESTED")
    status_counts = Counter(row["status"] for row in rows)
    artists: set[str] = set()
    albums: set[str] = set()
    duration = 0.0
    low_records: list[dict[str, Any]] = []
    unsorted_records: list[dict[str, Any]] = []
    filename_changes = 0
    tag_changes = 0
    artwork_removed = 0
    low_size = 0

    for row in rows:
        if row["status"] != "INGESTED":
            continue
        try:
            tags = json.loads(row["normalized_tags_json"] or "{}")
        except json.JSONDecodeError:
            tags = {}
        if meaningful(tags.get("artist", "")):
            artists.add(tags["artist"])
        if meaningful(tags.get("album", "")):
            albums.add(tags["album"])
        destination_path = row["destination_path"] or ""
        if destination_path and "Unsorted" in Path(destination_path).parts:
            try:
                original_tags = json.loads(row["original_tags_json"] or "{}")
            except json.JSONDecodeError:
                original_tags = {}
            unsorted_records.append(
                {
                    "source_path": row["source_path"],
                    "destination_path": destination_path,
                    "original_directory": str(Path(row["source_path"]).parent),
                    "original_tags": original_tags,
                    "normalized_tags": tags,
                }
            )
        duration += row["duration_seconds"] or 0.0
        filename_changes += row["filename_changed"]
        tag_changes += row["tags_changed"]
        artwork_removed += row["artwork_removed"]
        if row["quality_tier"] == "LOW_QUALITY":
            low_size += row["source_size"]
            low_records.append(
                {
                    "path": row["destination_path"],
                    "source_path": row["source_path"],
                    "bitrate_kbps": row["bitrate_kbps"],
                    "artist": tags.get("artist", ""),
                    "album": tags.get("album", ""),
                    "title": tags.get("title", ""),
                    "replacement_candidate": replacements.get(row["destination_path"]),
                }
            )

    quality_payload = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "tiers": dict(sorted(quality_counts.items())),
        "low_quality_total_bytes": low_size,
        "low_quality": low_records,
    }
    quality_path = metadata_root / "quality-audit.json"
    atomic_write_text(quality_path, json.dumps(quality_payload, ensure_ascii=False, indent=2) + "\n")

    unsorted_payload = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "files": unsorted_records,
    }
    unsorted_path = metadata_root / "unsorted-manifest.json"
    atomic_write_text(unsorted_path, json.dumps(unsorted_payload, ensure_ascii=False, indent=2) + "\n")

    failed_rows = [row for row in rows if row["status"] == "FAILED"]
    report_lines = [
        "# Music ingest report",
        "",
        f"Generated: {utc_now()}",
        "",
        "## Processing summary",
        "",
        f"- Total processed records: {len(rows)}",
        f"- Ingested: {status_counts['INGESTED']}",
        f"- Skipped as exact duplicates: {status_counts['DUPLICATE']}",
        f"- Failed: {status_counts['FAILED']}",
        f"- Filenames changed: {filename_changes}",
        f"- Tag sets changed: {tag_changes}",
        f"- Oversized embedded images removed: {artwork_removed}",
        f"- Unique artists: {len(artists)}",
        f"- Unique albums: {len(albums)}",
        f"- Total measured audio duration: {duration / 3600:.2f} hours",
        "",
        "## MP3 quality",
        "",
    ]
    for tier in ("LOW_QUALITY", "ACCEPTABLE", "GOOD", "HIGH", "UNKNOWN"):
        report_lines.append(f"- {tier}: {quality_counts.get(tier, 0)}")
    report_lines.extend(
        [
            f"- Bytes occupied by LOW_QUALITY sources: {low_size}",
            f"- LOW_QUALITY tracks with an in-collection replacement candidate: {sum(1 for item in low_records if item['replacement_candidate'])}",
            "",
            "## LOW_QUALITY replacement candidates",
            "",
        ]
    )
    if low_records:
        for record in low_records:
            replacement = record["replacement_candidate"] or "none found"
            report_lines.append(
                f"- {record['artist']} — {record['title']} ({record['bitrate_kbps'] or '?'} kbps): {record['path']} — replacement: {replacement}"
            )
    else:
        report_lines.append("- None.")

    report_lines.extend(["", "## Failures", ""])
    if failed_rows:
        for row in failed_rows:
            report_lines.append(f"- `{row['source_path']}` — {row['error']}")
    else:
        report_lines.append("- None.")

    report_path = metadata_root / "ingest-report.md"
    atomic_write_text(report_path, "\n".join(report_lines) + "\n")
    return quality_path, report_path, unsorted_path


def collect_sources(source_roots: Sequence[Path], catalog_dbs: Sequence[Path]) -> list[Path]:
    paths: dict[str, Path] = {}
    for path in iter_music_files(source_roots):
        paths[str(path)] = path
    for db in catalog_dbs:
        for path in iter_catalog_files(db):
            if path.is_file():
                paths[str(path)] = path
    return [paths[key] for key in sorted(paths)]


def default_state_db() -> Path:
    state_home = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return state_home / "properpcloud" / "music-ingest.sqlite3"


def default_staging_root() -> Path:
    cache_home = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    return cache_home / "properpcloud" / "music-ingest-staging"


def command_discover(args: argparse.Namespace) -> int:
    mounts = discover_external_mounts()
    companion = DEFAULT_COMPANION_CATALOG if DEFAULT_COMPANION_CATALOG.is_file() else None
    payload = {
        "external_mounts": [str(path) for path in mounts],
        "count": len(mounts),
        "companion_catalog": str(companion) if companion else None,
        "pcloud_mounted": os.path.ismount(PCLOUD_MOUNT),
    }
    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        if mounts:
            for mount in mounts:
                print(mount)
        else:
            print("No mounted external/loop filesystems discovered.", file=sys.stderr)
    return 0 if mounts or companion else 2


def command_ingest(args: argparse.Namespace) -> int:
    library_root = args.library_root.expanduser().resolve()
    metadata_root = args.metadata_root.expanduser().resolve()
    state_db = args.state_db.expanduser().resolve()
    staging_root = args.staging_root.expanduser().resolve()
    source_roots = [path.expanduser().resolve() for path in args.source_root]
    catalog_dbs = [path.expanduser().resolve() for path in args.catalog_db]
    if not source_roots and not catalog_dbs and DEFAULT_COMPANION_CATALOG.is_file():
        catalog_dbs.append(DEFAULT_COMPANION_CATALOG.resolve())
    if not source_roots and not catalog_dbs:
        source_roots = discover_external_mounts()
    if not source_roots and not catalog_dbs:
        print(
            "No mounted external/loop source filesystems discovered; refusing to scan an implicit system root.",
            file=sys.stderr,
        )
        return 2
    if not args.dry_run:
        try:
            ensure_pcloud_target_available(library_root)
            ensure_pcloud_target_available(metadata_root)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 2

    try:
        state_lock = acquire_state_lock(state_db)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 3
    try:
        connection = initialize_state(state_db)
    except Exception:
        release_state_lock(state_lock)
        raise
    run_id = connection.execute(
        "INSERT INTO ingest_runs(started_at, dry_run) VALUES (?, ?)",
        (utc_now(), int(args.dry_run)),
    ).lastrowid
    connection.commit()
    results: list[ProcessResult] = []
    try:
        sources = collect_sources(source_roots, catalog_dbs)
        sources = [path for path in sources if not path_is_within(path, library_root)]
        if args.limit is not None:
            sources = sources[: args.limit]
        if run_id is not None:
            connection.execute("UPDATE ingest_runs SET discovered = ? WHERE run_id = ?", (len(sources), run_id))
            connection.commit()

        for index, source in enumerate(sources, start=1):
            if args.resume and is_resumable_skip(connection, source):
                print(f"[{index}/{len(sources)}] resume-skip {source}")
                continue
            try:
                result = process_one(
                    source,
                    library_root,
                    staging_root,
                    connection,
                    dry_run=args.dry_run,
                )
            except Exception as exc:  # Continue the batch; persist failures.
                result = record_failure(connection, source, exc)
            results.append(result)
            destination = f" -> {result.destination_path}" if result.destination_path else ""
            print(f"[{index}/{len(sources)}] {result.status} {source}{destination}")

        if not args.dry_run:
            quality_path, report_path, unsorted_path = write_reports(connection, metadata_root)
            print(f"quality audit: {quality_path}")
            print(f"ingest report: {report_path}")
            print(f"unsorted manifest: {unsorted_path}")

        counts = Counter(result.status for result in results)
        if run_id is not None:
            connection.execute(
                """
                UPDATE ingest_runs SET finished_at=?, ingested=?, skipped=?, failed=? WHERE run_id=?
                """,
                (
                    utc_now(),
                    counts["INGESTED"],
                    counts["DUPLICATE"],
                    counts["FAILED"],
                    run_id,
                ),
            )
            connection.commit()
        return 1 if counts["FAILED"] else 0
    finally:
        connection.close()
        release_state_lock(state_lock)


def command_report(args: argparse.Namespace) -> int:
    try:
        ensure_pcloud_target_available(args.metadata_root.expanduser().resolve())
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    connection = initialize_state(args.state_db.expanduser().resolve())
    try:
        quality_path, report_path, unsorted_path = write_reports(
            connection, args.metadata_root.expanduser().resolve()
        )
    finally:
        connection.close()
    print(quality_path)
    print(report_path)
    print(unsorted_path)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    discover = subparsers.add_parser("discover", help="list mounted external/loop source filesystems")
    discover.add_argument("--json", action="store_true")
    discover.set_defaults(func=command_discover)

    ingest = subparsers.add_parser("ingest", help="scan, sanitize, ingest, and audit music")
    ingest.add_argument("--source-root", action="append", type=Path, default=[], help="explicit source root; repeatable")
    ingest.add_argument("--catalog-db", action="append", type=Path, default=[], help="SQLite catalog DB; repeatable")
    ingest.add_argument(
        "--library-root",
        type=Path,
        default=Path("/tmp/dib/media-library/audio/music"),
        help="destination music root",
    )
    ingest.add_argument(
        "--metadata-root",
        type=Path,
        default=Path("/tmp/dib/media-library/metadata"),
        help="final report directory",
    )
    ingest.add_argument("--state-db", type=Path, default=default_state_db(), help="local resumable state DB")
    ingest.add_argument("--staging-root", type=Path, default=default_staging_root(), help="local staging directory")
    ingest.add_argument("--limit", type=int, help="process at most N discovered files")
    ingest.add_argument("--dry-run", action="store_true", help="plan paths/audit without writing media or reports")
    ingest.add_argument("--no-resume", dest="resume", action="store_false", help="re-evaluate previously completed sources")
    ingest.set_defaults(resume=True, func=command_ingest)

    report = subparsers.add_parser("report", help="regenerate reports from local ingestion state")
    report.add_argument("--state-db", type=Path, default=default_state_db())
    report.add_argument("--metadata-root", type=Path, default=Path("/tmp/dib/media-library/metadata"))
    report.set_defaults(func=command_report)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if getattr(args, "limit", None) is not None and args.limit < 1:
        parser.error("--limit must be positive")
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
