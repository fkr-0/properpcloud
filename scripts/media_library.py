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
import time
import uuid
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Mapping, Sequence


DEFAULT_LIBRARY_ROOT = Path("/tmp/dib/media-library")
DEFAULT_STATE_DB = Path.home() / ".local/state/properpcloud/media-library/catalog.db"
DEFAULT_MUSIC_INGEST_DB = Path.home() / ".local/state/properpcloud/music-ingest.sqlite3"
CANONICAL_PCLOUD_MOUNT = Path("/tmp/dib")
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


def is_within(path: Path, parent: Path) -> bool:
    absolute = Path(os.path.realpath(os.path.abspath(path)))
    ancestor = Path(os.path.realpath(os.path.abspath(parent)))
    return absolute == ancestor or ancestor in absolute.parents


def filesystem_type_for_path(path: Path) -> str | None:
    """Return the Linux mount filesystem type containing path, if mountinfo is readable."""
    probe = Path(os.path.realpath(os.path.abspath(path)))
    while not probe.exists() and probe.parent != probe:
        probe = probe.parent
    try:
        lines = Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    best: tuple[int, str] | None = None
    for line in lines:
        if " - " not in line:
            continue
        before, after = line.split(" - ", 1)
        fields = before.split()
        trailing = after.split()
        if len(fields) < 5 or not trailing:
            continue
        mount_text = fields[4]
        for escaped, literal in (("\\040", " "), ("\\011", "\t"), ("\\012", "\n"), ("\\134", "\\")):
            mount_text = mount_text.replace(escaped, literal)
        mount_path = Path(mount_text)
        if is_within(probe, mount_path):
            score = len(str(mount_path))
            if best is None or score > best[0]:
                best = (score, trailing[0])
    return best[1] if best else None


def validate_storage_boundary(
    library_root: Path,
    state_db: Path,
    *,
    require_library_mount: bool = True,
) -> None:
    """Fail closed when the canonical cloud mount vanished or state was put on it."""
    if (
        require_library_mount
        and is_within(library_root, CANONICAL_PCLOUD_MOUNT)
        and not os.path.ismount(CANONICAL_PCLOUD_MOUNT)
    ):
        raise RuntimeError(
            f"canonical pCloud mount is unavailable: {CANONICAL_PCLOUD_MOUNT}; refusing filesystem access"
        )
    if is_within(state_db, CANONICAL_PCLOUD_MOUNT):
        raise RuntimeError("the writable SQLite state database must not reside on the pCloud/rclone mount")
    state_filesystem = filesystem_type_for_path(state_db.parent)
    if state_filesystem == "fuse.rclone":
        raise RuntimeError("the writable SQLite state database must reside on local storage, not fuse.rclone")


def acquire_writer_lock(library_root: Path, state_db: Path) -> Any:
    """Serialize planning/writes for one library root through a local advisory lock."""
    canonical_root = Path(os.path.realpath(os.path.abspath(library_root)))
    library_key = hashlib.sha256(str(canonical_root).encode()).hexdigest()[:24]
    lock_path = state_db.parent / "locks" / f"library-{library_key}.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+", encoding="utf-8")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as error:
        handle.seek(0)
        holder = handle.read().strip() or "unknown"
        handle.close()
        raise RuntimeError(f"media-library writer is already active for {library_root} ({holder})") from error
    handle.seek(0)
    handle.truncate()
    handle.write(f"pid={os.getpid()} started={utc_now()} root={canonical_root}\n")
    handle.flush()
    return handle


def release_writer_lock(handle: Any) -> None:
    with contextlib.suppress(OSError):
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    handle.close()


def acquire_shared_music_ingest_lock(state_db: Path) -> Any:
    """Refuse catalog reconciliation while music_ingest.py owns its state exclusively."""
    lock_path = Path(f"{state_db}.lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+", encoding="utf-8")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_SH | fcntl.LOCK_NB)
    except BlockingIOError as error:
        handle.close()
        raise RuntimeError(f"music ingest is active for {state_db}; catalog sync refused") from error
    return handle


def release_shared_lock(handle: Any) -> None:
    with contextlib.suppress(OSError):
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    handle.close()


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


def as_sha256(value: str | None) -> str | None:
    """Return normalized SHA-256 evidence without guessing the algorithm of generic hashes."""
    if value is None:
        return None
    text = value.strip().lower()
    return text if re.fullmatch(r"[0-9a-f]{64}", text) else None


def parse_size(value: str) -> int:
    text = value.strip().lower()
    units = {
        "b": 1,
        "kb": 1000,
        "mb": 1000**2,
        "gb": 1000**3,
        "tb": 1000**4,
        "kib": 1024,
        "mib": 1024**2,
        "gib": 1024**3,
        "tib": 1024**4,
        "k": 1024,
        "m": 1024**2,
        "g": 1024**3,
        "t": 1024**4,
    }
    for suffix in sorted(units, key=len, reverse=True):
        if text.endswith(suffix):
            return int(Decimal(text[:-len(suffix)]) * units[suffix])
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
    mtime_tolerance_ns: int
    source_hash: str | None
    declared_type: str | None


@dataclass(frozen=True)
class CatalogIssue:
    source_path: str
    source_disk: str
    reason: str


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

    def items(self) -> Iterator[SourceItem | CatalogIssue]:
        quoted = self.table.replace('"', '""')
        for record in self.connection.execute(f'SELECT * FROM "{quoted}"'):
            row = dict(record)
            try:
                yield self._item_from_row(row)
            except (OSError, TypeError, ValueError, OverflowError) as error:
                path_value = first_value(row, PATH_ALIASES) or first_value(row, RELATIVE_PATH_ALIASES) or "<unresolved>"
                disk_value = first_value(row, SOURCE_DISK_ALIASES) or "unknown"
                yield CatalogIssue(
                    source_path=str(path_value),
                    source_disk=safe_component(str(disk_value)),
                    reason=str(error)[:200] or error.__class__.__name__,
                )

    def _item_from_row(self, row: Mapping[str, Any]) -> SourceItem:
        absolute = first_value(row, PATH_ALIASES)
        relative = first_value(row, RELATIVE_PATH_ALIASES)
        root_value = first_value(row, SOURCE_ROOT_ALIASES)
        root = Path(str(root_value)).expanduser() if root_value else None
        relative_path = None
        if relative:
            relative_path = sanitize_relative(PurePosixPath(str(relative).replace(os.sep, "/")))
        if absolute:
            source_path = Path(str(absolute)).expanduser()
        elif relative_path is not None and root:
            source_path = root / Path(*relative_path.parts)
            if not is_within(source_path, root):
                raise ValueError("source-relative path escapes its declared source root")
        else:
            raise ValueError("catalog row has no resolvable source path")
        if root is not None and absolute and not is_within(source_path, root):
            raise ValueError("absolute source path escapes its declared source root")
        if relative_path is None:
            relative_path = self._relative_from_path(source_path, root, first_value(row, SOURCE_DISK_ALIASES))
        raw_size = first_value(row, SIZE_ALIASES)
        raw_mtime = first_value(row, MTIME_ALIASES)
        stat = None
        if raw_size is None or raw_mtime is None:
            with contextlib.suppress(OSError):
                stat = source_path.stat()
        if raw_size is None and stat is None:
            raise ValueError("catalog row has no usable file size")
        if raw_mtime is None and stat is None:
            raise ValueError("catalog row has no usable modification time")
        size = int(raw_size) if raw_size is not None else stat.st_size
        if size < 0:
            raise ValueError("catalog row has negative file size")
        mtime_ns, mtime_tolerance_ns = normalize_mtime(raw_mtime, stat.st_mtime_ns if stat is not None else 0)
        source_disk = safe_component(str(first_value(row, SOURCE_DISK_ALIASES) or infer_disk(source_path)))
        source_hash = first_value(row, HASH_ALIASES)
        return SourceItem(
            source_path=source_path,
            relative_path=relative_path,
            source_disk=source_disk,
            source_root=root,
            size_bytes=size,
            mtime_ns=mtime_ns,
            mtime_tolerance_ns=mtime_tolerance_ns,
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


def normalize_mtime(value: Any | None, fallback: int) -> tuple[int, int]:
    if value is None:
        return fallback, 0
    try:
        number = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        text = str(value).strip().replace("Z", "+00:00")
        parsed = dt.datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return int(parsed.timestamp() * 1_000_000_000), 1_000
    if abs(number) < 10_000_000_000:  # seconds
        return int(number * Decimal(1_000_000_000)), 1_000_000_000
    if abs(number) < 10_000_000_000_000:  # milliseconds
        return int(number * Decimal(1_000_000)), 1_000_000
    return int(number), 0


def infer_disk(path: Path) -> str:
    parts = path.parts
    for marker in ("mnt", "media", "run"):
        if marker in parts:
            index = parts.index(marker)
            if len(parts) > index + 1:
                return parts[index + 1]
    return path.anchor.strip("/") or "source"


def source_provenance_location(path: Path) -> tuple[str, PurePosixPath]:
    """Recover a useful stable mount identity and relative path from a source path."""
    parts = path.parts
    # Common desktop automounts: /run/media/<user>/<volume>/... and /media/<user>/<volume>/...
    if len(parts) >= 6 and parts[1:3] == ("run", "media"):
        return safe_component(parts[4]), PurePosixPath(*parts[5:])
    if len(parts) >= 5 and parts[1] == "media":
        return safe_component(parts[3]), PurePosixPath(*parts[4:])
    if len(parts) >= 4 and parts[1] == "mnt":
        return safe_component(parts[2]), PurePosixPath(*parts[3:])
    token = hashlib.sha256(str(path.parent).encode()).hexdigest()[:12]
    return safe_component(infer_disk(path)), PurePosixPath("_unrooted", token, path.name)


def sanitize_relative(path: PurePosixPath) -> PurePosixPath:
    if path.is_absolute():
        raise ValueError("source-relative path must not be absolute")
    parts: list[str] = []
    for part in path.parts:
        if part in ("", "."):
            continue
        if part == ".." or "\x00" in part:
            raise ValueError("unsafe source-relative path component")
        parts.append(part)
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
    expected_size = source.stat().st_size
    last_error: OSError | None = None
    for attempt in range(retries):
        temporary = target.parent / f".tmp-{uuid.uuid4().hex}"
        try:
            shutil.copyfile(source, temporary)
            if temporary.stat().st_size != expected_size:
                raise OSError("published temporary file size mismatch")
            os.replace(temporary, target)
            if target.stat().st_size != expected_size:
                raise OSError("published target size mismatch")
            return
        except OSError as error:
            last_error = error
            if attempt + 1 == retries:
                break
            time.sleep(0.5 * (2**attempt))
        finally:
            temporary.unlink(missing_ok=True)
    assert last_error is not None
    raise last_error


def initialize_library(root: Path) -> None:
    for relative in LIBRARY_DIRS:
        directory = root / relative
        if not is_within(directory, root):
            raise ValueError(f"library directory escapes root through a symlink: {directory}")
        directory.mkdir(parents=True, exist_ok=True)
        if directory.is_symlink() or not is_within(directory, root):
            raise ValueError(f"library directory is not a contained real directory: {directory}")
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


def choose_collision_path(path: Path, item: SourceItem, content_hash: str | None = None) -> Path:
    token = hashlib.sha256(
        f"{item.source_path}\0{item.size_bytes}\0{item.mtime_ns}\0{content_hash or ''}".encode()
    ).hexdigest()[:12]
    return path.with_name(f"{path.stem}__{token}{path.suffix}")


def copy_with_progress(
    source: Path,
    target: Path,
    bandwidth_mib: float | None = None,
    expected_size: int | None = None,
    expected_mtime_ns: int | None = None,
) -> int:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.parent / f".part-{uuid.uuid4().hex}"
    source_before = source.stat()
    if expected_size is not None and source_before.st_size != expected_size:
        raise OSError("source size changed before copy")
    if expected_mtime_ns is not None and source_before.st_mtime_ns != expected_mtime_ns:
        raise OSError("source modification time changed before copy")
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
        last_replace_error: OSError | None = None
        for attempt in range(4):
            try:
                os.replace(temporary, target)
                last_replace_error = None
                break
            except OSError as error:
                last_replace_error = error
                if attempt + 1 == 4:
                    break
                time.sleep(0.5 * (2**attempt))
        if last_replace_error is not None:
            raise last_replace_error
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
    expected_size: int | None = None,
    expected_mtime_ns: int | None = None,
) -> int:
    """Retry transient copy/FUSE errors without ever exposing a partial target."""
    last_error: OSError | None = None
    for attempt in range(attempts):
        try:
            return copy_with_progress(
                source,
                target,
                bandwidth_mib,
                expected_size=expected_size,
                expected_mtime_ns=expected_mtime_ns,
            )
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
    would_copy: int = 0
    deduplicated: int = 0
    unchanged: int = 0
    skipped: int = 0
    failed: int = 0
    bytes_copied: int = 0
    bytes_would_copy: int = 0


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
        self._manifest = tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            prefix="properpcloud-import-manifest-",
            suffix=".jsonl",
            delete=False,
        )
        self._manifest_path = Path(self._manifest.name)

    def run(self, catalog: SourceCatalog) -> ImportSummary:
        try:
            if not self.dry_run:
                self.db.connection.execute(
                    "INSERT INTO import_runs(run_id,source_catalog,started_at,status) VALUES(?,?,?,'running')",
                    (self.run_id, self.source_catalog, self.started_at),
                )
                self.db.connection.commit()
            for item in catalog.items():
                if isinstance(item, CatalogIssue):
                    self.summary.failed += 1
                    self._record_issue(item)
                    continue
                try:
                    self._import_one(item)
                except (OSError, sqlite3.Error, ValueError) as error:
                    self.summary.failed += 1
                    self._record(
                        item,
                        "failed",
                        error=error.__class__.__name__,
                        detail=str(error)[:200],
                    )
            if not self.dry_run:
                self.db.connection.execute(
                    "UPDATE import_runs SET completed_at=?,copied=?,deduplicated=?,unchanged=?,skipped=?,failed=?,bytes_copied=?,status=? WHERE run_id=?",
                    (utc_now(), self.summary.copied, self.summary.deduplicated, self.summary.unchanged,
                     self.summary.skipped, self.summary.failed, self.summary.bytes_copied,
                     "complete" if self.summary.failed == 0 else "partial", self.run_id),
                )
                self.db.connection.commit()
                self._manifest.flush()
                self._manifest.close()
                self._publish_run_artifacts()
            return self.summary
        finally:
            if not self._manifest.closed:
                self._manifest.close()
            self._manifest_path.unlink(missing_ok=True)

    def _import_one(self, item: SourceItem) -> None:
        if item.source_path.is_symlink():
            self.summary.skipped += 1
            self._record(item, "skipped", reason="source_symlink")
            return
        if not item.source_path.exists():
            raise FileNotFoundError(f"cataloged source is unavailable: {item.source_path}")
        if not item.source_path.is_file():
            self.summary.skipped += 1
            self._record(item, "skipped", reason="not_regular_file")
            return
        current_stat = item.source_path.stat()
        if current_stat.st_size != item.size_bytes:
            raise ValueError("source size changed since catalog")
        if abs(current_stat.st_mtime_ns - item.mtime_ns) > item.mtime_tolerance_ns:
            raise ValueError("source modification time changed since catalog")
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
        if self.dedupe == "hash":
            catalog_sha256 = as_sha256(item.source_hash)
            previous_sha256 = as_sha256(previous["source_hash"]) if previous else None
            hash_evidence_matches = previous_sha256 is not None and (
                catalog_sha256 is None or previous_sha256 == catalog_sha256
            )
        else:
            catalog_sha256 = None
            hash_evidence_matches = item.source_hash is None or (
                previous and previous["source_hash"] == item.source_hash
            )
        if previous and previous["size_bytes"] == item.size_bytes and previous["mtime_ns"] == item.mtime_ns and hash_evidence_matches:
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
            actual_hash = sha256_file(item.source_path)
            if catalog_sha256 is not None and catalog_sha256 != actual_hash:
                raise ValueError("source SHA-256 does not match catalog")
            source_hash = actual_hash
            duplicate = self.db.connection.execute(
                "SELECT * FROM library_files WHERE sha256=? AND size_bytes=? ORDER BY id LIMIT 1",
                (source_hash, item.size_bytes),
            ).fetchone()
        elif self.dedupe == "filename-size":
            if previous:
                duplicate = self.db.connection.execute(
                    "SELECT * FROM library_files WHERE filename=? AND size_bytes=? AND id<>? ORDER BY id LIMIT 1",
                    (item.source_path.name, item.size_bytes, previous["library_file_id"]),
                ).fetchone()
            else:
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
        if not is_within(target.parent, self.root):
            raise ValueError("destination path escapes the media-library root")
        if target.exists():
            target_relative = str(target.relative_to(self.root)).replace(os.sep, "/")
            target_is_previous = bool(previous and previous["library_path"] == target_relative)
            if target.is_symlink() or not target.is_file():
                target = choose_collision_path(target, item, source_hash)
            elif target.stat().st_size == item.size_bytes and self.dedupe == "filename-size":
                if target_is_previous:
                    target = choose_collision_path(target, item, source_hash)
                else:
                    self.summary.deduplicated += 1
                    if not self.dry_run:
                        library_id = self._register_existing(target, item, media_type, subtype, source_hash)
                        self._upsert_source(item, library_id, source_hash)
                    self._record(item, "deduplicated", library_path=target_relative)
                    return
            elif self.dedupe == "hash":
                if sha256_file(target) == source_hash:
                    self.summary.deduplicated += 1
                    if not self.dry_run:
                        library_id = self._register_existing(target, item, media_type, subtype, source_hash)
                        self._upsert_source(item, library_id, source_hash)
                    self._record(item, "deduplicated", library_path=str(target.relative_to(self.root)).replace(os.sep, "/"))
                    return
                target = choose_collision_path(target, item, source_hash)
            else:
                target = choose_collision_path(target, item, source_hash)

        relative_target = str(target.relative_to(self.root)).replace(os.sep, "/")
        if self.dry_run:
            self.summary.would_copy += 1
            self.summary.bytes_would_copy += item.size_bytes
            self._record(item, "would_copy", library_path=relative_target)
            return

        metadata = extract_metadata(item.source_path, media_type)
        copied = copy_with_retries(
            item.source_path,
            target,
            self.bandwidth_mib,
            expected_size=current_stat.st_size,
            expected_mtime_ns=current_stat.st_mtime_ns,
        )
        try:
            library_id = self._insert_library_file(target, item, media_type, subtype, source_hash, metadata)
            self._upsert_source(item, library_id, source_hash)
            self.db.connection.commit()
        except (sqlite3.Error, ValueError):
            self.db.connection.rollback()
            with contextlib.suppress(OSError):
                target.unlink()
            raise
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
        record = {
            "run_id": self.run_id, "timestamp": utc_now(), "status": status,
            "source_disk": item.source_disk, "source_path": str(item.source_path),
            "source_relative_path": str(item.relative_path), "size_bytes": item.size_bytes,
            **extra,
        }
        self._manifest.write(json.dumps(record, sort_keys=True) + "\n")

    def _record_issue(self, issue: CatalogIssue) -> None:
        self._manifest.write(json.dumps({
            "run_id": self.run_id,
            "timestamp": utc_now(),
            "status": "failed",
            "source_disk": issue.source_disk,
            "source_path": issue.source_path,
            "reason": "catalog_row_invalid",
            "detail": issue.reason,
        }, sort_keys=True) + "\n")

    def _publish_run_artifacts(self) -> None:
        stamp = self.started_at.replace(":", "").replace("-", "")
        manifests = self.root / "metadata/manifests"
        logs = self.root / "metadata/logs"
        manifests.mkdir(parents=True, exist_ok=True)
        logs.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="properpcloud-run-") as temp_dir:
            atomic_publish_file(self._manifest_path, manifests / f"import-{stamp}-{self.run_id[:8]}.jsonl")
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


def cleanup_report(db: LibraryDatabase, root: Path, limit: int = 1000) -> dict[str, Any]:
    limit = max(1, limit)
    exact_duplicates = [dict(row) for row in db.connection.execute(
        """SELECT sha256,COUNT(*) AS files,SUM(size_bytes) AS bytes,
                  (COUNT(*)-1)*MIN(size_bytes) AS reclaimable_bytes
           FROM library_files WHERE sha256 IS NOT NULL
           GROUP BY sha256 HAVING COUNT(*)>1 ORDER BY reclaimable_bytes DESC LIMIT ?""",
        (limit,),
    )]
    heuristic_duplicates = [dict(row) for row in db.connection.execute(
        """SELECT filename,size_bytes,COUNT(*) AS files,(COUNT(*)-1)*size_bytes AS candidate_reclaimable_bytes
           FROM library_files GROUP BY filename,size_bytes HAVING COUNT(*)>1
           ORDER BY candidate_reclaimable_bytes DESC LIMIT ?""",
        (limit,),
    )]
    empty_total = db.connection.execute("SELECT COUNT(*) FROM library_files WHERE size_bytes=0").fetchone()[0]
    empty = [row[0] for row in db.connection.execute(
        "SELECT library_path FROM library_files WHERE size_bytes=0 ORDER BY library_path LIMIT ?",
        (limit,),
    )]
    unreferenced_total = db.connection.execute(
        "SELECT COUNT(*) FROM library_files l LEFT JOIN source_items s ON s.library_file_id=l.id WHERE s.id IS NULL"
    ).fetchone()[0]
    unreferenced = [row[0] for row in db.connection.execute(
        """SELECT l.library_path FROM library_files l LEFT JOIN source_items s ON s.library_file_id=l.id
           WHERE s.id IS NULL ORDER BY l.library_path LIMIT ?""",
        (limit,),
    )]
    adopted_unresolved_total = db.connection.execute(
        """SELECT COUNT(*) FROM library_files l LEFT JOIN source_items s ON s.library_file_id=l.id
           WHERE s.id IS NULL AND json_valid(l.metadata_json)
             AND json_extract(l.metadata_json, '$.catalog_source')='existing_pcloud_adoption'"""
    ).fetchone()[0]
    adopted_unresolved = [row[0] for row in db.connection.execute(
        """SELECT l.library_path FROM library_files l LEFT JOIN source_items s ON s.library_file_id=l.id
           WHERE s.id IS NULL AND json_valid(l.metadata_json)
             AND json_extract(l.metadata_json, '$.catalog_source')='existing_pcloud_adoption'
           ORDER BY l.library_path LIMIT ?""",
        (limit,),
    )]

    broken_symlinks: list[str] = []
    broken_symlink_count = 0
    filesystem_empty: list[str] = []
    filesystem_empty_count = 0
    untracked_media: list[str] = []
    untracked_media_count = 0
    partial_uploads: list[str] = []
    partial_upload_count = 0
    media_roots = {"audio", "video", "images", "documents"}
    for base, dirnames, filenames in os.walk(root, followlinks=False):
        for name in dirnames + filenames:
            path = Path(base) / name
            relative = str(path.relative_to(root)).replace(os.sep, "/")
            if path.is_symlink() and not path.exists():
                broken_symlink_count += 1
                if len(broken_symlinks) < limit:
                    broken_symlinks.append(relative)
                continue
            if path.is_symlink() or not path.is_file():
                continue
            if ".part-" in name or ".tmp-" in name:
                partial_upload_count += 1
                if len(partial_uploads) < limit:
                    partial_uploads.append(relative)
                continue
            parts = Path(relative).parts
            if not parts or parts[0] not in media_roots:
                continue
            stat = path.stat()
            if stat.st_size == 0:
                filesystem_empty_count += 1
                if len(filesystem_empty) < limit:
                    filesystem_empty.append(relative)
            if db.connection.execute("SELECT 1 FROM library_files WHERE library_path=?", (relative,)).fetchone() is None:
                untracked_media_count += 1
                if len(untracked_media) < limit:
                    untracked_media.append(relative)
    return {
        "sample_limit": limit,
        "exact_duplicate_groups": exact_duplicates,
        "filename_size_duplicate_groups": heuristic_duplicates,
        "catalog_empty_files": {"count": empty_total, "sample": empty},
        "filesystem_empty_files": {"count": filesystem_empty_count, "sample": filesystem_empty},
        "broken_symlinks": {"count": broken_symlink_count, "sample": broken_symlinks},
        "partial_uploads": {"count": partial_upload_count, "sample": partial_uploads},
        "untracked_media_files": {"count": untracked_media_count, "sample": untracked_media},
        "unreferenced_catalog_files": {"count": unreferenced_total, "sample": unreferenced},
        "adopted_unresolved_provenance_files": {
            "count": adopted_unresolved_total,
            "sample": adopted_unresolved,
        },
    }


def existing_location_class(root: Path, path: Path) -> tuple[str, str] | None:
    """Return the media class asserted by an existing canonical library location."""
    if path.is_symlink() or not path.is_file() or not is_within(path, root):
        return None
    relative = path.relative_to(root)
    if len(relative.parts) < 3:
        return None
    media_type, subtype = relative.parts[:2]
    location = f"{media_type}/{subtype}"
    if location not in LIBRARY_DIRS or media_type == "metadata":
        return None
    classified = classify(path)
    if classified is None or classified[0] != media_type:
        return None
    return media_type, subtype


def adopt_existing_media(
    db: LibraryDatabase,
    root: Path,
    *,
    prefixes: Sequence[str] = (),
    execute: bool,
    hashes: bool,
    probe_metadata: bool = False,
) -> dict[str, Any]:
    """Catalog existing pCloud media without inventing external-source provenance."""
    normalized_prefixes = tuple(prefix.strip("/") for prefix in prefixes if prefix.strip("/"))
    invalid_prefixes = [prefix for prefix in normalized_prefixes if prefix not in LIBRARY_DIRS or prefix.startswith("metadata/")]
    if invalid_prefixes:
        raise ValueError("invalid media-library prefix: " + ", ".join(sorted(invalid_prefixes)))
    known = {row[0] for row in db.connection.execute("SELECT library_path FROM library_files")}
    candidates: list[tuple[Path, str, str, str, int, int]] = []
    skipped_unsupported = 0
    for base, _, filenames in os.walk(root, followlinks=False):
        for name in filenames:
            if ".part-" in name or ".tmp-" in name or ".properpcloud-partial-" in name:
                continue
            path = Path(base) / name
            relative = str(path.relative_to(root)).replace(os.sep, "/")
            if relative in known:
                continue
            if normalized_prefixes and not any(
                relative == prefix or relative.startswith(prefix + "/") for prefix in normalized_prefixes
            ):
                continue
            location = existing_location_class(root, path)
            if location is None:
                if relative.split("/", 1)[0] in {"audio", "video", "images", "documents"}:
                    skipped_unsupported += 1
                continue
            media_type, subtype = location
            stat = path.stat()
            candidates.append((path, relative, media_type, subtype, stat.st_size, stat.st_mtime_ns))

    summary: dict[str, Any] = {
        "execute": execute,
        "hashes": hashes,
        "probe_metadata": probe_metadata,
        "prefixes": list(normalized_prefixes),
        "candidates": len(candidates),
        "adopted": 0,
        "bytes": sum(size for _, _, _, _, size, _ in candidates),
        "skipped_unsupported": skipped_unsupported,
        "provenance_status": "unresolved",
    }
    if not execute:
        return summary

    adopted_at = utc_now()
    try:
        for path, relative, media_type, subtype, expected_size, expected_mtime_ns in candidates:
            stat = path.stat()
            if stat.st_size != expected_size or stat.st_mtime_ns != expected_mtime_ns:
                raise OSError(f"existing media changed before adoption: {relative}")
            metadata = extract_metadata(path, media_type) if probe_metadata else {}
            digest = sha256_file(path) if hashes else None
            after = path.stat()
            if after.st_size != expected_size or after.st_mtime_ns != expected_mtime_ns:
                raise OSError(f"existing media changed during adoption: {relative}")
            catalog_metadata = {
                **metadata,
                "catalog_source": "existing_pcloud_adoption",
                "provenance_status": "unresolved",
                "adopted_at": adopted_at,
            }
            db.connection.execute(
                """INSERT INTO library_files(
                       library_path,filename,extension,size_bytes,media_type,media_subtype,mtime_ns,
                       sha256,imported_at,audio_title,audio_artist,audio_album,audio_duration_seconds,
                       audio_bitrate,image_width,image_height,captured_at,metadata_json
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    relative, path.name, path.suffix.lower(), stat.st_size, media_type, subtype,
                    stat.st_mtime_ns, digest, adopted_at, metadata.get("audio_title"),
                    metadata.get("audio_artist"), metadata.get("audio_album"),
                    metadata.get("audio_duration_seconds"), metadata.get("audio_bitrate"),
                    metadata.get("image_width"), metadata.get("image_height"),
                    metadata.get("captured_at"), json.dumps(catalog_metadata, sort_keys=True),
                ),
            )
        db.connection.commit()
    except (OSError, sqlite3.Error, ValueError):
        db.connection.rollback()
        raise
    summary["adopted"] = len(candidates)
    return summary


def _music_ingest_columns(connection: sqlite3.Connection) -> set[str]:
    return {str(row[1]) for row in connection.execute("PRAGMA table_info(ingest_files)")}


def sync_music_ingest_catalog(
    db: LibraryDatabase,
    root: Path,
    music_state_db: Path,
    *,
    execute: bool,
) -> dict[str, Any]:
    """Reconcile specialized music-ingest results into the canonical media catalog.

    The specialized ingester may rewrite tags, so its source SHA-256 is provenance
    evidence and is deliberately not copied into library_files.sha256 as a claim about
    the transformed destination bytes.
    """
    music_state_db = music_state_db.expanduser().resolve()
    if not music_state_db.is_file():
        raise ValueError(f"music ingest state database not found: {music_state_db}")
    shared_lock = acquire_shared_music_ingest_lock(music_state_db)
    try:
        source = sqlite3.connect(f"file:{music_state_db}?mode=ro", uri=True)
        source.row_factory = sqlite3.Row
        try:
            required = {
                "source_path", "source_size", "source_mtime_ns", "source_sha256", "status",
                "destination_path", "normalized_tags_json", "quality_tier", "bitrate_kbps",
                "bitrate_mode", "duration_seconds", "updated_at",
            }
            columns = _music_ingest_columns(source)
            missing_columns = sorted(required - columns)
            if missing_columns:
                raise ValueError(
                    "music ingest state is missing required columns: " + ", ".join(missing_columns)
                )
            rows = source.execute(
                """SELECT * FROM ingest_files
                   WHERE status IN ('INGESTED','DUPLICATE') AND destination_path IS NOT NULL
                   ORDER BY destination_path, CASE status WHEN 'INGESTED' THEN 0 ELSE 1 END, source_path"""
            ).fetchall()
        finally:
            source.close()

        summary: dict[str, Any] = {
            "execute": execute,
            "music_state_db": str(music_state_db),
            "source_rows": len(rows),
            "physical_candidates": 0,
            "cataloged_files": 0,
            "source_links": 0,
            "missing_destinations": [],
            "invalid_destinations": [],
        }
        by_destination: dict[str, list[sqlite3.Row]] = {}
        for row in rows:
            by_destination.setdefault(str(row["destination_path"]), []).append(row)
        summary["physical_candidates"] = len(by_destination)

        validated: list[tuple[Path, list[sqlite3.Row]]] = []
        for destination_text, linked_rows in by_destination.items():
            destination = Path(destination_text).expanduser()
            if not is_within(destination, root):
                summary["invalid_destinations"].append(destination_text)
                continue
            if destination.is_symlink() or not destination.is_file():
                summary["missing_destinations"].append(destination_text)
                continue
            validated.append((destination, linked_rows))

        if not execute:
            summary["cataloged_files"] = len(validated)
            summary["source_links"] = sum(len(linked_rows) for _, linked_rows in validated)
            return summary

        source_catalog = f"music-ingest:{music_state_db}"
        try:
            for destination, linked_rows in validated:
                physical_row = linked_rows[0]
                for candidate in linked_rows:
                    if candidate["status"] == "INGESTED":
                        physical_row = candidate
                        break
                stat = destination.stat()
                relative = str(destination.relative_to(root)).replace(os.sep, "/")
                try:
                    tags = json.loads(physical_row["normalized_tags_json"] or "{}")
                    if not isinstance(tags, dict):
                        tags = {}
                except (json.JSONDecodeError, TypeError):
                    tags = {}
                existing = db.connection.execute(
                    "SELECT id,size_bytes,mtime_ns,sha256 FROM library_files WHERE library_path=?",
                    (relative,),
                ).fetchone()
                retained_sha256 = None
                if existing and existing["size_bytes"] == stat.st_size and existing["mtime_ns"] == stat.st_mtime_ns:
                    retained_sha256 = existing["sha256"]
                metadata = {
                    "catalog_source": "music_ingest",
                    "quality_tier": physical_row["quality_tier"],
                    "bitrate_kbps": physical_row["bitrate_kbps"],
                    "bitrate_mode": physical_row["bitrate_mode"],
                }
                imported_at = physical_row["updated_at"] or utc_now()
                db.connection.execute(
                    """INSERT INTO library_files(
                           library_path,filename,extension,size_bytes,media_type,media_subtype,mtime_ns,
                           sha256,imported_at,audio_title,audio_artist,audio_album,
                           audio_duration_seconds,audio_bitrate,metadata_json
                       ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(library_path) DO UPDATE SET
                           filename=excluded.filename,extension=excluded.extension,size_bytes=excluded.size_bytes,
                           media_type=excluded.media_type,media_subtype=excluded.media_subtype,
                           mtime_ns=excluded.mtime_ns,sha256=excluded.sha256,
                           audio_title=excluded.audio_title,audio_artist=excluded.audio_artist,
                           audio_album=excluded.audio_album,audio_duration_seconds=excluded.audio_duration_seconds,
                           audio_bitrate=excluded.audio_bitrate,metadata_json=excluded.metadata_json""",
                    (
                        relative, destination.name, destination.suffix.lower(), stat.st_size, "audio", "music",
                        stat.st_mtime_ns, retained_sha256, imported_at, tags.get("title"), tags.get("artist"),
                        tags.get("album"), physical_row["duration_seconds"],
                        int(physical_row["bitrate_kbps"] * 1000) if physical_row["bitrate_kbps"] is not None else None,
                        json.dumps(metadata, sort_keys=True),
                    ),
                )
                library_id = int(db.connection.execute(
                    "SELECT id FROM library_files WHERE library_path=?", (relative,)
                ).fetchone()[0])
                for linked in linked_rows:
                    source_path = Path(str(linked["source_path"]))
                    source_disk, source_relative = source_provenance_location(source_path)
                    seen_at = linked["updated_at"] or utc_now()
                    db.connection.execute(
                        """INSERT INTO source_items(
                               source_catalog,source_disk,source_path,source_relative_path,size_bytes,mtime_ns,
                               source_hash,library_file_id,first_imported_at,last_seen_at
                           ) VALUES(?,?,?,?,?,?,?,?,?,?)
                           ON CONFLICT(source_catalog,source_path) DO UPDATE SET
                               source_disk=excluded.source_disk,source_relative_path=excluded.source_relative_path,
                               size_bytes=excluded.size_bytes,mtime_ns=excluded.mtime_ns,
                               source_hash=excluded.source_hash,library_file_id=excluded.library_file_id,
                               last_seen_at=excluded.last_seen_at""",
                        (
                            source_catalog, source_disk, str(source_path), str(source_relative),
                            int(linked["source_size"]), int(linked["source_mtime_ns"]), linked["source_sha256"],
                            library_id, seen_at, seen_at,
                        ),
                    )
            db.connection.commit()
        except (OSError, sqlite3.Error, ValueError, TypeError):
            db.connection.rollback()
            raise
        summary["cataloged_files"] = len(validated)
        summary["source_links"] = sum(len(linked_rows) for _, linked_rows in validated)
        return summary
    finally:
        release_shared_lock(shared_lock)


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
    cleanup = sub.add_parser("cleanup", help="report duplicate, empty, broken-symlink, and unreferenced candidates")
    cleanup.add_argument("--limit", type=int, default=1000, help="maximum sample rows per cleanup category")
    adopt = sub.add_parser("adopt-existing", help="catalog existing library media without fabricating source provenance")
    adopt.add_argument(
        "--prefix",
        action="append",
        default=[],
        choices=tuple(path for path in LIBRARY_DIRS if not path.startswith("metadata/")),
        help="limit to a canonical media prefix such as audio/music; repeatable",
    )
    adopt.add_argument("--hash", action="store_true", help="compute SHA-256 for adopted physical files")
    adopt.add_argument(
        "--probe-metadata",
        action="store_true",
        help="probe embedded technical/tag metadata; optional because cloud-FUSE reads can be slow",
    )
    adopt.add_argument("--execute", action="store_true", help="write catalog rows; default is preview only")
    music_sync = sub.add_parser("sync-music-ingest", help="reconcile specialized music-ingest state into this catalog")
    music_sync.add_argument("--music-state-db", type=Path, default=DEFAULT_MUSIC_INGEST_DB)
    music_sync.add_argument("--execute", action="store_true", help="update the local catalog and publish its snapshot")
    sub.add_parser("publish", help="publish a consistent catalog.db snapshot to pCloud")
    return parser


def print_rows(rows: Iterable[Mapping[str, Any]]) -> None:
    for row in rows:
        print(json.dumps(dict(row), sort_keys=True, default=str))


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        validate_storage_boundary(
            args.library_root,
            args.state_db,
            require_library_mount=args.command in {"init", "import", "verify", "cleanup", "adopt-existing", "sync-music-ingest", "publish"},
        )
    except RuntimeError as error:
        print(f"media-library: {error}", file=sys.stderr)
        return 2
    writer_lock = None
    if args.command in {"init", "import", "adopt-existing", "sync-music-ingest", "publish"}:
        try:
            writer_lock = acquire_writer_lock(args.library_root, args.state_db)
        except RuntimeError as error:
            print(f"media-library: {error}", file=sys.stderr)
            return 3
    if args.command == "init":
        try:
            initialize_library(args.library_root)
            db = LibraryDatabase(args.state_db)
            try:
                db.publish_snapshot(args.library_root)
            finally:
                db.close()
            print(args.library_root)
            return 0
        finally:
            assert writer_lock is not None
            release_writer_lock(writer_lock)
    db: LibraryDatabase | None = None
    try:
        db = LibraryDatabase(args.state_db)
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
            print(json.dumps(cleanup_report(db, args.library_root, args.limit), indent=2, sort_keys=True))
        elif args.command == "adopt-existing":
            report = adopt_existing_media(
                db,
                args.library_root,
                prefixes=args.prefix,
                execute=args.execute,
                hashes=args.hash,
                probe_metadata=args.probe_metadata,
            )
            if args.execute:
                db.publish_snapshot(args.library_root)
            print(json.dumps(report, indent=2, sort_keys=True))
        elif args.command == "sync-music-ingest":
            report = sync_music_ingest_catalog(
                db, args.library_root, args.music_state_db, execute=args.execute
            )
            if args.execute:
                db.publish_snapshot(args.library_root)
            print(json.dumps(report, indent=2, sort_keys=True))
            return 1 if report["missing_destinations"] or report["invalid_destinations"] else 0
        elif args.command == "publish":
            print(db.publish_snapshot(args.library_root))
        return 0
    finally:
        if db is not None:
            db.close()
        if writer_lock is not None:
            release_writer_lock(writer_lock)


if __name__ == "__main__":
    raise SystemExit(main())
