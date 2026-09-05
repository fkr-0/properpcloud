#!/usr/bin/env python3
"""Catalog-driven pCloud media-library importer and maintenance CLI.

The writable SQLite database lives on local storage.  A transactionally consistent
snapshot is published to the pCloud/FUSE library after successful mutations so the
cloud copy is portable without relying on SQLite locking semantics over FUSE.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Mapping, Sequence


DEFAULT_LIBRARY_ROOT = Path("/tmp/dib/media-library")
DEFAULT_STATE_DB = Path.home() / ".local/state/properpcloud/media-library/catalog.db"
CHUNK_SIZE = 8 * 1024 * 1024

LIBRARY_DIRS = (
    "audio/music",
    "audio/samples",
    "audio/recordings",
    "audio/podcasts",
    "video/projects",
    "video/recordings",
    "video/media",
    "images/photos",
    "images/screenshots",
    "images/artwork",
    "images/textures",
    "documents/pdfs",
    "documents/ebooks",
    "documents/archives",
    "metadata/manifests",
    "metadata/logs",
)

LIBRARY_README = """# ProperPCloud media library

This tree is managed by properpcloud's catalog-driven import tooling.  The filesystem
is intentionally meaningful and remains usable without the database.

## Layout

- `audio/music/` — albums, singles, and ordinary music files
- `audio/samples/` — loops, one-shots, stems, and sample packs
- `audio/recordings/` — field recordings, voice memos, and other captures
- `audio/podcasts/` — podcast and spoken-episode media
- `video/projects/` — project/session files
- `video/recordings/` — screen/camera captures and recordings
- `video/media/` — movies, shows, clips, and other video
- `images/photos/` — photographs
- `images/screenshots/` — screenshots and screen captures
- `images/artwork/` — designs, illustrations, and artwork
- `images/textures/` — textures, patterns, and reusable image assets
- `documents/pdfs/` — PDF documents
- `documents/ebooks/` — EPUB/MOBI/AZW-family ebooks
- `documents/archives/` — ZIP/TAR/7z/RAR and similar archives
- `metadata/catalog.db` — published read-only snapshot of the local master SQLite DB
- `metadata/manifests/` — append-only per-import JSONL manifests
- `metadata/logs/` — bounded JSON run summaries

Imported objects keep their source-relative hierarchy below a source-disk directory.
The database retains source provenance, import time, hashes when available, extracted
technical metadata, and a full-text filename/path index.

The pCloud copy is cloud storage, not the only backup.  Preserve at least one independent
copy of irreplaceable originals and periodically verify both the catalog and backup.
"""

AUDIO_EXTENSIONS = {
    ".aac", ".aif", ".aiff", ".alac", ".flac", ".m4a", ".m4b", ".mp3",
    ".oga", ".ogg", ".opus", ".wav", ".wma",
}
VIDEO_EXTENSIONS = {
    ".3gp", ".avi", ".flv", ".m2ts", ".m4v", ".mkv", ".mov", ".mp4",
    ".mpeg", ".mpg", ".mts", ".ogv", ".ts", ".webm", ".wmv",
}
VIDEO_PROJECT_EXTENSIONS = {".kdenlive", ".mlt", ".prproj", ".veg", ".drp", ".fcpxml"}
IMAGE_EXTENSIONS = {
    ".avif", ".bmp", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".jxl",
    ".png", ".svg", ".tif", ".tiff", ".webp",
}
PDF_EXTENSIONS = {".pdf"}
EBOOK_EXTENSIONS = {".azw", ".azw3", ".cb7", ".cbr", ".cbz", ".epub", ".fb2", ".mobi"}
ARCHIVE_EXTENSIONS = {".7z", ".bz2", ".gz", ".rar", ".tar", ".tgz", ".txz", ".xz", ".zip", ".zst"}

PATH_ALIASES = ("path", "absolute_path", "source_path", "file_path", "full_path")
RELATIVE_PATH_ALIASES = ("relative_path", "relpath", "source_relative_path")
SIZE_ALIASES = ("size", "size_bytes", "bytes", "file_size")
MTIME_ALIASES = ("mtime_ns", "modified_ns", "mtime", "modified_at", "modified_ms")
HASH_ALIASES = ("sha256", "content_hash", "hash")
SOURCE_DISK_ALIASES = ("source_disk", "disk", "disk_id", "volume", "volume_id", "device")
SOURCE_ROOT_ALIASES = ("source_root", "mount_root", "mount_path", "root_path")
MEDIA_TYPE_ALIASES = ("media_type", "type", "kind", "category")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def safe_component(value: str, fallback: str = "unknown") -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value.strip())
    cleaned = cleaned.strip("._")
    return cleaned[:96] or fallback


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_size(value: str) -> int:
    text = value.strip().lower().replace("ib", "b")
    units = {"b": 1, "kb": 1000, "mb": 1000**2, "gb": 1000**3, "tb": 1000**4,
             "k": 1024, "m": 1024**2, "g": 1024**3, "t": 1024**4}
    for suffix in sorted(units, key=len, reverse=True):
        if text.endswith(suffix):
            return int(float(text[:-len(suffix)]) * units[suffix])
    return int(text)


def format_bytes(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if amount < 1024 or unit == "TiB":
            return f"{amount:.1f} {unit}"
        amount /= 1024
    return f"{amount:.1f} TiB"


def classify(path: Path, declared: str | None = None) -> tuple[str, str] | None:
    ext = path.suffix.lower()
    hint = (declared or "").lower()
    context = "/".join(part.lower() for part in path.parts[-5:])
    if ext in AUDIO_EXTENSIONS or hint == "audio":
        if any(word in context for word in ("sample", "loop", "one-shot", "oneshot", "stem", "drumkit")):
            return "audio", "samples"
        if "podcast" in context or "episode" in context:
            return "audio", "podcasts"
        if any(word in context for word in ("recording", "voice memo", "voicememo", "field-record")):
            return "audio", "recordings"
        return "audio", "music"
    if ext in VIDEO_PROJECT_EXTENSIONS:
        return "video", "projects"
    if ext in VIDEO_EXTENSIONS or hint == "video":
        if any(word in context for word in ("screenrecord", "screen-record", "capture", "recording")):
            return "video", "recordings"
        return "video", "media"
    if ext in IMAGE_EXTENSIONS or hint in {"image", "images", "photo"}:
        if "screenshot" in context or "screen-shot" in context:
            return "images", "screenshots"
        if any(word in context for word in ("texture", "pattern", "material")):
            return "images", "textures"
        if any(word in context for word in ("artwork", "illustration", "design", "drawing")):
            return "images", "artwork"
        return "images", "photos"
    if ext in PDF_EXTENSIONS:
        return "documents", "pdfs"
    if ext in EBOOK_EXTENSIONS:
        return "documents", "ebooks"
    if ext in ARCHIVE_EXTENSIONS:
        return "documents", "archives"
    return None


def first_value(row: Mapping[str, Any], aliases: Sequence[str]) -> Any | None:
    by_lower = {str(key).lower(): value for key, value in row.items()}
    for alias in aliases:
        if alias in by_lower and by_lower[alias] not in (None, ""):
            return by_lower[alias]
    return None


@dataclass(frozen=True)
class SourceItem:
    source_path: Path
    relative_path: PurePosixPath
    source_disk: str
    source_root: Path | None
    size_bytes: int
    mtime_ns: int
    source_hash: str | None
    declared_type: str | None


class SourceCatalog:
    def __init__(self, database: Path, table: str | None = None) -> None:
        self.database = database
        self.connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
        self.connection.row_factory = sqlite3.Row
        self.table = table or self._detect_table()

    def close(self) -> None:
        self.connection.close()

    def _detect_table(self) -> str:
        candidates: list[tuple[int, str]] = []
        rows = self.connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
        for row in rows:
            name = row[0]
            quoted = name.replace('"', '""')
            columns = {info[1].lower() for info in self.connection.execute(f'PRAGMA table_info("{quoted}")')}
            has_path = bool(columns.intersection(PATH_ALIASES) or columns.intersection(RELATIVE_PATH_ALIASES))
            if not has_path:
                continue
            score = 10 + len(columns.intersection(SIZE_ALIASES + MTIME_ALIASES + HASH_ALIASES + SOURCE_DISK_ALIASES))
            candidates.append((score, name))
        if not candidates:
            raise ValueError("source catalog has no table with a recognized file path column")
        candidates.sort(key=lambda item: (-item[0], item[1]))
        return candidates[0][1]

    def items(self) -> Iterator[SourceItem]:
        quoted = self.table.replace('"', '""')
        for record in self.connection.execute(f'SELECT * FROM "{quoted}"'):
            row = dict(record)
            absolute = first_value(row, PATH_ALIASES)
            relative = first_value(row, RELATIVE_PATH_ALIASES)
            root_value = first_value(row, SOURCE_ROOT_ALIASES)
            root = Path(str(root_value)).expanduser() if root_value else None
            if absolute:
                source_path = Path(str(absolute)).expanduser()
            elif relative and root:
                source_path = root / str(relative)
            else:
                continue
            if relative:
                relative_path = PurePosixPath(str(relative).replace(os.sep, "/")).relative_to("/") if str(relative).startswith("/") else PurePosixPath(str(relative).replace(os.sep, "/"))
            else:
                relative_path = self._relative_from_path(source_path, root, first_value(row, SOURCE_DISK_ALIASES))
            raw_size = first_value(row, SIZE_ALIASES)
            raw_mtime = first_value(row, MTIME_ALIASES)
            stat = None
            if raw_size is None or raw_mtime is None:
                with contextlib.suppress(OSError):
                    stat = source_path.stat()
            if raw_size is None and stat is None:
                continue
            if raw_mtime is None and stat is None:
                continue
            size = int(raw_size) if raw_size is not None else stat.st_size
            mtime_ns = normalize_mtime_ns(raw_mtime, stat.st_mtime_ns if stat is not None else 0)
            source_disk = safe_component(str(first_value(row, SOURCE_DISK_ALIASES) or infer_disk(source_path)))
            source_hash = first_value(row, HASH_ALIASES)
            yield SourceItem(
                source_path=source_path,
                relative_path=sanitize_relative(relative_path),
                source_disk=source_disk,
                source_root=root,
                size_bytes=size,
                mtime_ns=mtime_ns,
                source_hash=str(source_hash).lower() if source_hash else None,
                declared_type=str(first_value(row, MEDIA_TYPE_ALIASES)) if first_value(row, MEDIA_TYPE_ALIASES) else None,
            )

    @staticmethod
    def _relative_from_path(path: Path, root: Path | None, disk: Any | None) -> PurePosixPath:
        if root:
            with contextlib.suppress(ValueError):
                return PurePosixPath(*path.resolve().relative_to(root.resolve()).parts)
        disk_name = str(disk) if disk else ""
        if disk_name and disk_name in path.parts:
            index = path.parts.index(disk_name)
            tail = path.parts[index + 1 :]
            if tail:
                return PurePosixPath(*tail)
        token = hashlib.sha256(str(path.parent).encode()).hexdigest()[:12]
        return PurePosixPath("_unrooted", token, path.name)


def normalize_mtime_ns(value: Any | None, fallback: int) -> int:
    if value is None:
        return fallback
    number = int(float(value))
    if number < 10_000_000_000:  # seconds
        return number * 1_000_000_000
    if number < 10_000_000_000_000:  # milliseconds
        return number * 1_000_000
    return number


def infer_disk(path: Path) -> str:
    parts = path.parts
    for marker in ("mnt", "media", "run"):
        if marker in parts:
            index = parts.index(marker)
            if len(parts) > index + 1:
                return parts[index + 1]
    return path.anchor.strip("/") or "source"


def sanitize_relative(path: PurePosixPath) -> PurePosixPath:
    parts = [safe_component(part) for part in path.parts if part not in ("", ".", "..")]
    if not parts:
        raise ValueError("empty source-relative path")
    return PurePosixPath(*parts)


class LibraryDatabase:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys=ON")
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute("PRAGMA busy_timeout=5000")
        self._migrate()

    def close(self) -> None:
        self.connection.close()

    def _migrate(self) -> None:
        self.connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS schema_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            INSERT OR REPLACE INTO schema_meta(key,value) VALUES('schema_version','1');
            CREATE TABLE IF NOT EXISTS library_files(
              id INTEGER PRIMARY KEY,
              library_path TEXT NOT NULL UNIQUE,
              filename TEXT NOT NULL,
              extension TEXT NOT NULL,
              size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
              media_type TEXT NOT NULL,
              media_subtype TEXT NOT NULL,
              mtime_ns INTEGER NOT NULL,
              sha256 TEXT,
              imported_at TEXT NOT NULL,
              audio_title TEXT,
              audio_artist TEXT,
              audio_album TEXT,
              audio_duration_seconds REAL,
              audio_bitrate INTEGER,
              image_width INTEGER,
              image_height INTEGER,
              captured_at TEXT,
              metadata_json TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_library_type ON library_files(media_type,media_subtype);
            CREATE INDEX IF NOT EXISTS idx_library_ext_size ON library_files(extension,size_bytes);
            CREATE INDEX IF NOT EXISTS idx_library_hash ON library_files(sha256) WHERE sha256 IS NOT NULL;
            CREATE TABLE IF NOT EXISTS source_items(
              id INTEGER PRIMARY KEY,
              source_catalog TEXT NOT NULL,
              source_disk TEXT NOT NULL,
              source_path TEXT NOT NULL,
              source_relative_path TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              mtime_ns INTEGER NOT NULL,
              source_hash TEXT,
              library_file_id INTEGER NOT NULL REFERENCES library_files(id),
              first_imported_at TEXT NOT NULL,
              last_seen_at TEXT NOT NULL,
              UNIQUE(source_catalog,source_path)
            );
            CREATE INDEX IF NOT EXISTS idx_source_disk ON source_items(source_disk);
            CREATE TABLE IF NOT EXISTS import_runs(
              run_id TEXT PRIMARY KEY,
              source_catalog TEXT NOT NULL,
              started_at TEXT NOT NULL,
              completed_at TEXT,
              copied INTEGER NOT NULL DEFAULT 0,
              deduplicated INTEGER NOT NULL DEFAULT 0,
              unchanged INTEGER NOT NULL DEFAULT 0,
              skipped INTEGER NOT NULL DEFAULT 0,
              failed INTEGER NOT NULL DEFAULT 0,
              bytes_copied INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL
            );
            """
        )
        try:
            self.connection.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS library_fts USING fts5(filename,library_path,content='library_files',content_rowid='id')"
            )
            self.connection.executescript(
                """
                CREATE TRIGGER IF NOT EXISTS library_fts_ai AFTER INSERT ON library_files BEGIN
                  INSERT INTO library_fts(rowid,filename,library_path) VALUES(new.id,new.filename,new.library_path);
                END;
                CREATE TRIGGER IF NOT EXISTS library_fts_ad AFTER DELETE ON library_files BEGIN
                  INSERT INTO library_fts(library_fts,rowid,filename,library_path) VALUES('delete',old.id,old.filename,old.library_path);
                END;
                CREATE TRIGGER IF NOT EXISTS library_fts_au AFTER UPDATE ON library_files BEGIN
                  INSERT INTO library_fts(library_fts,rowid,filename,library_path) VALUES('delete',old.id,old.filename,old.library_path);
                  INSERT INTO library_fts(rowid,filename,library_path) VALUES(new.id,new.filename,new.library_path);
                END;
                """
            )
        except sqlite3.OperationalError as error:
            raise RuntimeError("SQLite FTS5 support is required for the media-library catalog") from error
        self.connection.commit()

    def publish_snapshot(self, library_root: Path) -> Path:
        target = library_root / "metadata/catalog.db"
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix="properpcloud-catalog-", suffix=".db", delete=False) as temp:
            temp_path = Path(temp.name)
        try:
            snapshot = sqlite3.connect(temp_path)
            try:
                self.connection.backup(snapshot)
                snapshot.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            finally:
                snapshot.close()
            atomic_publish_file(temp_path, target)
            return target
        finally:
            temp_path.unlink(missing_ok=True)


def atomic_publish_file(source: Path, target: Path, retries: int = 4) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.tmp-{uuid.uuid4().hex}")
    try:
        shutil.copyfile(source, temporary)
        for attempt in range(retries):
            try:
                if temporary.stat().st_size != source.stat().st_size:
                    raise OSError("published temporary file size mismatch")
                os.replace(temporary, target)
                if target.stat().st_size != source.stat().st_size:
                    raise OSError("published target size mismatch")
                return
            except OSError:
                if attempt + 1 == retries:
                    raise
                time.sleep(0.5 * (2**attempt))
    finally:
        temporary.unlink(missing_ok=True)


def initialize_library(root: Path) -> None:
    for relative in LIBRARY_DIRS:
        (root / relative).mkdir(parents=True, exist_ok=True)
    readme = root / "README.md"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
        handle.write(LIBRARY_README)
        temp = Path(handle.name)
    try:
        atomic_publish_file(temp, readme)
    finally:
        temp.unlink(missing_ok=True)


def ffprobe_metadata(path: Path, media_type: str) -> dict[str, Any]:
    if not shutil.which("ffprobe"):
        return {}
    command = [
        "ffprobe", "-v", "error", "-show_entries",
        "format=duration,bit_rate:format_tags=title,artist,album,date,creation_time:stream=width,height",
        "-of", "json", str(path),
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=20)
        document = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        return {}
    output: dict[str, Any] = {}
    fmt = document.get("format") or {}
    tags = {str(k).lower(): v for k, v in (fmt.get("tags") or {}).items()}
    if media_type == "audio":
        output.update(
            audio_title=tags.get("title"), audio_artist=tags.get("artist"), audio_album=tags.get("album"),
            audio_duration_seconds=to_float(fmt.get("duration")), audio_bitrate=to_int(fmt.get("bit_rate")),
        )
    if media_type == "images":
        stream = next((stream for stream in document.get("streams", []) if stream.get("width") and stream.get("height")), {})
        output.update(image_width=to_int(stream.get("width")), image_height=to_int(stream.get("height")), captured_at=tags.get("creation_time") or tags.get("date"))
    return {key: value for key, value in output.items() if value is not None}


def exiftool_metadata(path: Path) -> dict[str, Any]:
    if not shutil.which("exiftool"):
        return {}
    try:
        result = subprocess.run(
            ["exiftool", "-j", "-ImageWidth", "-ImageHeight", "-DateTimeOriginal", str(path)],
            check=True, capture_output=True, text=True, timeout=20,
        )
        row = json.loads(result.stdout)[0]
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError, IndexError):
        return {}
    return {
        key: value for key, value in {
            "image_width": to_int(row.get("ImageWidth")),
            "image_height": to_int(row.get("ImageHeight")),
            "captured_at": row.get("DateTimeOriginal"),
        }.items() if value is not None
    }


def to_int(value: Any | None) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def to_float(value: Any | None) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def extract_metadata(path: Path, media_type: str) -> dict[str, Any]:
    data = ffprobe_metadata(path, media_type)
    if media_type == "images":
        data.update({key: value for key, value in exiftool_metadata(path).items() if value is not None})
    return data


def destination_for(root: Path, item: SourceItem, media_type: str, subtype: str) -> Path:
    return root / media_type / subtype / item.source_disk / Path(*item.relative_path.parts)


def choose_collision_path(path: Path, item: SourceItem) -> Path:
    token = hashlib.sha256(f"{item.source_path}\0{item.size_bytes}\0{item.mtime_ns}".encode()).hexdigest()[:12]
    return path.with_name(f"{path.stem}__{token}{path.suffix}")


def copy_with_progress(source: Path, target: Path, bandwidth_mib: float | None = None) -> int:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.part-{uuid.uuid4().hex}")
    source_before = source.stat()
    total = source_before.st_size
    copied = 0
    started = time.monotonic()
    last_report = started
    try:
        with source.open("rb") as src, temporary.open("xb") as dst:
            while True:
                chunk = src.read(CHUNK_SIZE)
                if not chunk:
                    break
                dst.write(chunk)
                copied += len(chunk)
                now = time.monotonic()
                if bandwidth_mib:
                    expected = copied / (bandwidth_mib * 1024 * 1024)
                    delay = expected - (now - started)
                    if delay > 0:
                        time.sleep(delay)
                        now = time.monotonic()
                if sys.stderr.isatty() and now - last_report >= 1:
                    elapsed = max(now - started, 0.001)
                    rate = copied / elapsed
                    eta = (total - copied) / rate if rate > 0 else 0
                    print(f"\r{source.name}: {copied/total:6.1%} {format_bytes(copied)}/{format_bytes(total)} {format_bytes(int(rate))}/s ETA {eta:,.0f}s", end="", file=sys.stderr)
                    last_report = now
            dst.flush()
            with contextlib.suppress(OSError):
                os.fsync(dst.fileno())
        if temporary.stat().st_size != total:
            raise OSError("copied file size mismatch")
        source_after = source.stat()
        if source_after.st_size != source_before.st_size or source_after.st_mtime_ns != source_before.st_mtime_ns:
            raise OSError("source changed during copy")
        os.replace(temporary, target)
        if sys.stderr.isatty():
            print(file=sys.stderr)
        return copied
    finally:
        temporary.unlink(missing_ok=True)


def copy_with_retries(
    source: Path,
    target: Path,
    bandwidth_mib: float | None = None,
    attempts: int = 4,
) -> int:
    """Retry transient copy/FUSE errors without ever exposing a partial target."""
    last_error: OSError | None = None
    for attempt in range(attempts):
        try:
            return copy_with_progress(source, target, bandwidth_mib)
        except OSError as error:
            last_error = error
            if attempt + 1 == attempts:
                break
            time.sleep(0.5 * (2**attempt))
    assert last_error is not None
    raise last_error


@dataclass
class ImportSummary:
    copied: int = 0
    deduplicated: int = 0
    unchanged: int = 0
    skipped: int = 0
    failed: int = 0
    bytes_copied: int = 0


class Importer:
    def __init__(self, db: LibraryDatabase, root: Path, source_catalog: Path, dedupe: str, dry_run: bool, bandwidth_mib: float | None) -> None:
        self.db = db
        self.root = root
        self.source_catalog = str(source_catalog.resolve())
        self.dedupe = dedupe
        self.dry_run = dry_run
        self.bandwidth_mib = bandwidth_mib
        self.run_id = uuid.uuid4().hex
        self.started_at = utc_now()
        self.summary = ImportSummary()
        self.manifest_rows: list[dict[str, Any]] = []

    def run(self, catalog: SourceCatalog) -> ImportSummary:
        if not self.dry_run:
            self.db.connection.execute(
                "INSERT INTO import_runs(run_id,source_catalog,started_at,status) VALUES(?,?,?,'running')",
                (self.run_id, self.source_catalog, self.started_at),
            )
            self.db.connection.commit()
        for item in catalog.items():
            try:
                self._import_one(item)
            except (OSError, sqlite3.Error, ValueError) as error:
                self.summary.failed += 1
                self._record(item, "failed", error=error.__class__.__name__)
        if not self.dry_run:
            self.db.connection.execute(
                "UPDATE import_runs SET completed_at=?,copied=?,deduplicated=?,unchanged=?,skipped=?,failed=?,bytes_copied=?,status=? WHERE run_id=?",
                (utc_now(), self.summary.copied, self.summary.deduplicated, self.summary.unchanged,
                 self.summary.skipped, self.summary.failed, self.summary.bytes_copied,
                 "complete" if self.summary.failed == 0 else "partial", self.run_id),
            )
            self.db.connection.commit()
            self._publish_run_artifacts()
        return self.summary

    def _import_one(self, item: SourceItem) -> None:
        if item.source_path.is_symlink() or not item.source_path.is_file():
            self.summary.skipped += 1
            self._record(item, "skipped", reason="not_regular_file")
            return
        current_stat = item.source_path.stat()
        if current_stat.st_size != item.size_bytes:
            raise ValueError("source size changed since catalog")
        classified = classify(item.source_path, item.declared_type)
        if classified is None:
            self.summary.skipped += 1
            self._record(item, "skipped", reason="unsupported_media_type")
            return
        media_type, subtype = classified
        previous = self.db.connection.execute(
            "SELECT s.*,l.library_path FROM source_items s JOIN library_files l ON l.id=s.library_file_id WHERE s.source_catalog=? AND s.source_path=?",
            (self.source_catalog, str(item.source_path)),
        ).fetchone()
        if previous and previous["size_bytes"] == item.size_bytes and previous["mtime_ns"] == item.mtime_ns and previous["source_hash"] == item.source_hash:
            cloud_path = self.root / previous["library_path"]
            if cloud_path.is_file() and cloud_path.stat().st_size == item.size_bytes:
                self.summary.unchanged += 1
                if not self.dry_run:
                    self.db.connection.execute("UPDATE source_items SET last_seen_at=? WHERE id=?", (utc_now(), previous["id"]))
                    self.db.connection.commit()
                self._record(item, "unchanged", library_path=previous["library_path"])
                return

        source_hash = item.source_hash
        duplicate = None
        if self.dedupe == "hash":
            source_hash = source_hash or sha256_file(item.source_path)
            duplicate = self.db.connection.execute(
                "SELECT * FROM library_files WHERE sha256=? AND size_bytes=? ORDER BY id LIMIT 1",
                (source_hash, item.size_bytes),
            ).fetchone()
        elif self.dedupe == "filename-size":
            duplicate = self.db.connection.execute(
                "SELECT * FROM library_files WHERE filename=? AND size_bytes=? ORDER BY id LIMIT 1",
                (item.source_path.name, item.size_bytes),
            ).fetchone()
        if duplicate and (self.root / duplicate["library_path"]).is_file():
            self.summary.deduplicated += 1
            if not self.dry_run:
                self._upsert_source(item, int(duplicate["id"]), source_hash)
            self._record(item, "deduplicated", library_path=duplicate["library_path"])
            return

        target = destination_for(self.root, item, media_type, subtype)
        if target.exists():
            if target.is_symlink() or not target.is_file():
                target = choose_collision_path(target, item)
            elif target.stat().st_size == item.size_bytes and self.dedupe == "filename-size":
                self.summary.deduplicated += 1
                if not self.dry_run:
                    library_id = self._register_existing(target, item, media_type, subtype, source_hash)
                    self._upsert_source(item, library_id, source_hash)
                self._record(item, "deduplicated", library_path=str(target.relative_to(self.root)).replace(os.sep, "/"))
                return
            elif self.dedupe == "hash":
                source_hash = source_hash or sha256_file(item.source_path)
                if sha256_file(target) == source_hash:
                    self.summary.deduplicated += 1
                    if not self.dry_run:
                        library_id = self._register_existing(target, item, media_type, subtype, source_hash)
                        self._upsert_source(item, library_id, source_hash)
                    self._record(item, "deduplicated", library_path=str(target.relative_to(self.root)).replace(os.sep, "/"))
                    return
                target = choose_collision_path(target, item)
            else:
                target = choose_collision_path(target, item)

        relative_target = str(target.relative_to(self.root)).replace(os.sep, "/")
        if self.dry_run:
            self.summary.copied += 1
            self.summary.bytes_copied += item.size_bytes
            self._record(item, "would_copy", library_path=relative_target)
            return

        copied = copy_with_retries(item.source_path, target, self.bandwidth_mib)
        source_hash = source_hash or (sha256_file(target) if self.dedupe == "hash" else None)
        metadata = extract_metadata(target, media_type)
        library_id = self._insert_library_file(target, item, media_type, subtype, source_hash, metadata)
        self._upsert_source(item, library_id, source_hash)
        self.db.connection.commit()
        self.summary.copied += 1
        self.summary.bytes_copied += copied
        self._record(item, "copied", library_path=relative_target, sha256=source_hash)

    def _insert_library_file(self, target: Path, item: SourceItem, media_type: str, subtype: str, source_hash: str | None, metadata: Mapping[str, Any]) -> int:
        relative = str(target.relative_to(self.root)).replace(os.sep, "/")
        values = {
            "library_path": relative, "filename": target.name, "extension": target.suffix.lower(),
            "size_bytes": item.size_bytes, "media_type": media_type, "media_subtype": subtype,
            "mtime_ns": item.mtime_ns, "sha256": source_hash, "imported_at": utc_now(),
            "audio_title": metadata.get("audio_title"), "audio_artist": metadata.get("audio_artist"),
            "audio_album": metadata.get("audio_album"), "audio_duration_seconds": metadata.get("audio_duration_seconds"),
            "audio_bitrate": metadata.get("audio_bitrate"), "image_width": metadata.get("image_width"),
            "image_height": metadata.get("image_height"), "captured_at": metadata.get("captured_at"),
            "metadata_json": json.dumps(metadata, sort_keys=True) if metadata else None,
        }
        columns = ",".join(values)
        placeholders = ",".join("?" for _ in values)
        cursor = self.db.connection.execute(f"INSERT INTO library_files({columns}) VALUES({placeholders})", tuple(values.values()))
        return int(cursor.lastrowid)

    def _register_existing(self, target: Path, item: SourceItem, media_type: str, subtype: str, source_hash: str | None) -> int:
        relative = str(target.relative_to(self.root)).replace(os.sep, "/")
        existing = self.db.connection.execute("SELECT id FROM library_files WHERE library_path=?", (relative,)).fetchone()
        if existing:
            return int(existing[0])
        return self._insert_library_file(target, item, media_type, subtype, source_hash, extract_metadata(target, media_type))

    def _upsert_source(self, item: SourceItem, library_id: int, source_hash: str | None) -> None:
        now = utc_now()
        self.db.connection.execute(
            """INSERT INTO source_items(source_catalog,source_disk,source_path,source_relative_path,size_bytes,mtime_ns,source_hash,library_file_id,first_imported_at,last_seen_at)
               VALUES(?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(source_catalog,source_path) DO UPDATE SET source_disk=excluded.source_disk,source_relative_path=excluded.source_relative_path,
               size_bytes=excluded.size_bytes,mtime_ns=excluded.mtime_ns,source_hash=excluded.source_hash,library_file_id=excluded.library_file_id,last_seen_at=excluded.last_seen_at""",
            (self.source_catalog, item.source_disk, str(item.source_path), str(item.relative_path), item.size_bytes,
             item.mtime_ns, source_hash, library_id, now, now),
        )

    def _record(self, item: SourceItem, status: str, **extra: Any) -> None:
        self.manifest_rows.append({
            "run_id": self.run_id, "timestamp": utc_now(), "status": status,
            "source_disk": item.source_disk, "source_path": str(item.source_path),
            "source_relative_path": str(item.relative_path), "size_bytes": item.size_bytes,
            **extra,
        })

    def _publish_run_artifacts(self) -> None:
        stamp = self.started_at.replace(":", "").replace("-", "")
        manifests = self.root / "metadata/manifests"
        logs = self.root / "metadata/logs"
        manifests.mkdir(parents=True, exist_ok=True)
        logs.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="properpcloud-run-") as temp_dir:
            manifest_source = Path(temp_dir) / "manifest.jsonl"
            manifest_source.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in self.manifest_rows), encoding="utf-8")
            atomic_publish_file(manifest_source, manifests / f"import-{stamp}-{self.run_id[:8]}.jsonl")
            summary_source = Path(temp_dir) / "summary.json"
            summary_source.write_text(json.dumps({
                "run_id": self.run_id, "started_at": self.started_at, "completed_at": utc_now(),
                "source_catalog": self.source_catalog, "summary": self.summary.__dict__,
            }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            atomic_publish_file(summary_source, logs / f"import-{stamp}-{self.run_id[:8]}.json")


def query_files(db: LibraryDatabase, args: argparse.Namespace) -> list[sqlite3.Row]:
    clauses: list[str] = []
    params: list[Any] = []
    if args.extension:
        clauses.append("extension=?")
        params.append("." + args.extension.lower().lstrip("."))
    if args.min_size is not None:
        clauses.append("size_bytes>=?")
        params.append(args.min_size)
    if args.media_type:
        clauses.append("media_type=?")
        params.append(args.media_type)
    if args.taken_year:
        clauses.append("captured_at LIKE ?")
        params.append(f"{args.taken_year}%")
    sql = "SELECT * FROM library_files"
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY library_path COLLATE NOCASE LIMIT ?"
    params.append(args.limit)
    return db.connection.execute(sql, params).fetchall()


def verify_library(db: LibraryDatabase, root: Path, hashes: bool) -> dict[str, Any]:
    result: dict[str, Any] = {"checked": 0, "missing": [], "size_mismatch": [], "hash_mismatch": []}
    for row in db.connection.execute("SELECT * FROM library_files ORDER BY library_path"):
        result["checked"] += 1
        path = root / row["library_path"]
        if not path.is_file() or path.is_symlink():
            result["missing"].append(row["library_path"])
            continue
        if path.stat().st_size != row["size_bytes"]:
            result["size_mismatch"].append(row["library_path"])
            continue
        if hashes and row["sha256"] and sha256_file(path) != row["sha256"]:
            result["hash_mismatch"].append(row["library_path"])
    return result


def space_report(db: LibraryDatabase) -> dict[str, Any]:
    by_type = [dict(row) for row in db.connection.execute(
        "SELECT media_type,COUNT(*) AS files,SUM(size_bytes) AS bytes FROM library_files GROUP BY media_type ORDER BY bytes DESC"
    )]
    by_source = [dict(row) for row in db.connection.execute(
        """SELECT s.source_disk,COUNT(DISTINCT s.id) AS source_items,SUM(l.size_bytes) AS referenced_bytes
           FROM source_items s JOIN library_files l ON l.id=s.library_file_id GROUP BY s.source_disk ORDER BY referenced_bytes DESC"""
    )]
    physical = db.connection.execute("SELECT COUNT(*) AS files,COALESCE(SUM(size_bytes),0) AS bytes FROM library_files").fetchone()
    return {"physical": dict(physical), "by_type": by_type, "by_source": by_source}


def cleanup_report(db: LibraryDatabase, root: Path) -> dict[str, Any]:
    exact_duplicates = [dict(row) for row in db.connection.execute(
        "SELECT sha256,COUNT(*) AS files,SUM(size_bytes) AS bytes FROM library_files WHERE sha256 IS NOT NULL GROUP BY sha256 HAVING COUNT(*)>1 ORDER BY bytes DESC"
    )]
    empty = [row[0] for row in db.connection.execute("SELECT library_path FROM library_files WHERE size_bytes=0 ORDER BY library_path")]
    broken_symlinks: list[str] = []
    for base, dirnames, filenames in os.walk(root, followlinks=False):
        for name in dirnames + filenames:
            path = Path(base) / name
            if path.is_symlink() and not path.exists():
                broken_symlinks.append(str(path.relative_to(root)).replace(os.sep, "/"))
    unreferenced = [row[0] for row in db.connection.execute(
        "SELECT l.library_path FROM library_files l LEFT JOIN source_items s ON s.library_file_id=l.id WHERE s.id IS NULL ORDER BY l.library_path"
    )]
    return {"exact_duplicate_groups": exact_duplicates, "empty_files": empty, "broken_symlinks": broken_symlinks, "unreferenced_catalog_files": unreferenced}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--state-db", type=Path, default=DEFAULT_STATE_DB)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init", help="create the media-library directory layout and README")
    imp = sub.add_parser("import", help="import cataloged source files incrementally")
    imp.add_argument("--source-db", type=Path, required=True)
    imp.add_argument("--source-table")
    imp.add_argument("--dedupe", choices=("filename-size", "hash", "off"), default="filename-size")
    imp.add_argument("--bandwidth-limit-mib", type=float)
    imp.add_argument("--execute", action="store_true", help="perform copies; default is dry-run")
    q = sub.add_parser("query", help="query cataloged physical library files")
    q.add_argument("--extension")
    q.add_argument("--min-size", type=parse_size)
    q.add_argument("--media-type", choices=("audio", "video", "images", "documents"))
    q.add_argument("--taken-year", type=int)
    q.add_argument("--limit", type=int, default=500)
    search = sub.add_parser("search", help="full-text search filenames and library paths")
    search.add_argument("text")
    search.add_argument("--limit", type=int, default=100)
    verify = sub.add_parser("verify", help="verify cataloged objects exist and match expected size/hash")
    verify.add_argument("--hash", action="store_true")
    sub.add_parser("space", help="report physical usage by media type and source")
    sub.add_parser("cleanup", help="report duplicate, empty, broken-symlink, and unreferenced candidates")
    sub.add_parser("publish", help="publish a consistent catalog.db snapshot to pCloud")
    return parser


def print_rows(rows: Iterable[Mapping[str, Any]]) -> None:
    for row in rows:
        print(json.dumps(dict(row), sort_keys=True, default=str))


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "init":
        initialize_library(args.library_root)
        print(args.library_root)
        return 0
    db = LibraryDatabase(args.state_db)
    try:
        if args.command == "import":
            if not args.source_db.is_file():
                raise SystemExit(f"source catalog not found: {args.source_db}")
            initialize_library(args.library_root) if args.execute else None
            source = SourceCatalog(args.source_db, args.source_table)
            try:
                importer = Importer(db, args.library_root, args.source_db, args.dedupe, not args.execute, args.bandwidth_limit_mib)
                summary = importer.run(source)
            finally:
                source.close()
            if args.execute:
                db.publish_snapshot(args.library_root)
            print(json.dumps(summary.__dict__, indent=2, sort_keys=True))
            return 1 if summary.failed else 0
        if args.command == "query":
            print_rows(query_files(db, args))
        elif args.command == "search":
            rows = db.connection.execute(
                "SELECT l.* FROM library_fts f JOIN library_files l ON l.id=f.rowid WHERE library_fts MATCH ? ORDER BY bm25(library_fts) LIMIT ?",
                (args.text, args.limit),
            ).fetchall()
            print_rows(rows)
        elif args.command == "verify":
            report = verify_library(db, args.library_root, args.hash)
            print(json.dumps(report, indent=2, sort_keys=True))
            return 1 if report["missing"] or report["size_mismatch"] or report["hash_mismatch"] else 0
        elif args.command == "space":
            print(json.dumps(space_report(db), indent=2, sort_keys=True))
        elif args.command == "cleanup":
            print(json.dumps(cleanup_report(db, args.library_root), indent=2, sort_keys=True))
        elif args.command == "publish":
            print(db.publish_snapshot(args.library_root))
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
