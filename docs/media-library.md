# Extensive pCloud media library

`scripts/media_library.py` is the organizational/import layer for media discovered by
the companion external-disk catalog tooling. It writes the human-readable library below
`/tmp/dib/media-library` by default and keeps durable import state in a **local** SQLite
database.

The local database is deliberate: SQLite WAL/locking is not entrusted to an rclone FUSE
mount. After a successful write operation, properpcloud publishes a consistent SQLite
backup snapshot to `metadata/catalog.db` on pCloud.

## Directory contract

```text
media-library/
├── audio/
│   ├── music/
│   ├── samples/
│   ├── recordings/
│   └── podcasts/
├── video/
│   ├── projects/
│   ├── recordings/
│   └── media/
├── images/
│   ├── photos/
│   ├── screenshots/
│   ├── artwork/
│   └── textures/
├── documents/
│   ├── pdfs/
│   ├── ebooks/
│   └── archives/
└── metadata/
    ├── catalog.db
    ├── manifests/
    └── logs/
```

Within a media subtype, imports keep a source-disk component and the catalog's relative
path. For example, a catalog row for `Music/Artist/Album/01.flac` from `sda1` becomes
`audio/music/sda1/Music/Artist/Album/01.flac`. This preserves useful filesystem context
without making the original absolute mount path part of the cloud layout.

## Source catalog compatibility

The importer opens the discovery SQLite database read-only. Supply `--source-table` when
the scripting tool has a canonical table name; otherwise the importer deterministically
selects a table containing a recognized path column.

Recognized column aliases include:

| Meaning | Accepted names |
| --- | --- |
| Absolute path | `path`, `absolute_path`, `source_path`, `file_path`, `full_path` |
| Relative path | `relative_path`, `relpath`, `source_relative_path` |
| Source root | `source_root`, `mount_root`, `mount_path`, `root_path` |
| Source disk | `source_disk`, `disk`, `disk_id`, `volume`, `volume_id`, `device` |
| Size | `size`, `size_bytes`, `bytes`, `file_size` |
| Modification time | `mtime_ns`, `modified_ns`, `mtime`, `modified_at`, `modified_ms` |
| Exact hash | `sha256`, `content_hash`, `hash` |
| Type hint | `media_type`, `type`, `kind`, `category` |

An absolute path is sufficient. A relative-only catalog must also expose a source-root
column so the importer can open the source bytes.

## Initialize and test

The repository test does not touch pCloud:

```bash
make media-library-test
```

Create the directory contract on the configured mount:

```bash
make media-library-init
```

Override locations when needed:

```bash
make media-library-init \
  MEDIA_LIBRARY_ROOT=/some/other/mount/media-library \
  MEDIA_LIBRARY_STATE_DB="$HOME/.local/state/properpcloud/media-library/catalog.db"
```

## Import workflow

Import is preview-first. `MEDIA_LIBRARY_SOURCE_DB` is required by the Make targets.

```bash
# No pCloud media writes. Reports what would be copied/skipped/deduplicated.
make media-library-dry-run MEDIA_LIBRARY_SOURCE_DB=/path/to/external-catalog.db

# Explicitly perform the import after reviewing the dry-run.
make media-library-import MEDIA_LIBRARY_SOURCE_DB=/path/to/external-catalog.db
```

The direct CLI exposes exact-content dedupe and a transfer throttle:

```bash
python3 scripts/media_library.py \
  --library-root /tmp/dib/media-library \
  import \
  --source-db /path/to/external-catalog.db \
  --dedupe hash \
  --bandwidth-limit-mib 20 \
  --execute
```

Deduplication modes:

- `filename-size` (default) is a fast **heuristic**. It satisfies cheap incremental
  screening but does not claim two files have equal content.
- `hash` computes/uses SHA-256 and deduplicates only exact content.
- `off` never deduplicates independent source objects.

Every successful run records source provenance in `source_items`, publishes one JSONL
manifest in `metadata/manifests/`, writes a compact run summary in `metadata/logs/`, and
publishes a consistent `metadata/catalog.db` snapshot. Multiple source rows can point at
one physical cloud object after deduplication without losing their source disk/path.

Copies are written to hidden `.part-*` siblings first. A source whose size or mtime
changes during the copy fails rather than becoming visible as complete. Transient copy
errors are retried with bounded exponential backoff; no hardlink behavior is assumed.
Interactive runs report bytes, throughput, and ETA.

## Metadata and search

The master database stores path, filename, extension, bytes, media type/subtype, mtime,
source disk/provenance, import timestamps and SHA-256 when available. If installed:

- `ffprobe` adds audio title/artist/album, duration and bitrate;
- `ffprobe`/`exiftool` add image dimensions and capture date where readable.

Metadata extraction is enrichment, not identity, and failure does not discard a valid
media copy. FTS5 indexes filenames and library paths.

Examples matching common library questions:

```bash
# All FLAC files at least 10 MiB.
python3 scripts/media_library.py query --extension flac --min-size 10MiB

# Photos/images whose extracted capture date starts with 2024.
python3 scripts/media_library.py query --media-type images --taken-year 2024

# Filename/path full-text search.
python3 scripts/media_library.py search 'field recording'

# Physical library size by type and provenance-referenced bytes by source disk.
make media-library-space
```

The source report intentionally labels **referenced bytes**: the same physical object can
be attributed to multiple source disks after exact dedupe. The physical total and
by-media-type totals do not double-count those provenance links.

## Health and maintenance

Fast existence/size verification:

```bash
make media-library-verify
```

Include exact SHA-256 checks for rows that have hashes:

```bash
python3 scripts/media_library.py verify --hash
```

Inspect cleanup candidates:

```bash
python3 scripts/media_library.py cleanup
```

Cleanup is deliberately **report-only**. It reports hash duplicate groups, zero-byte
catalog entries, broken symlinks found below the library root, and cataloged objects with
no remaining source provenance. It never deletes cloud media automatically.

## Backup strategy

pCloud is a storage destination and synchronization service; treat it as one copy, not
the sole backup of irreplaceable media.

1. Keep source disks/read-only originals until an independent backup has been created and
   verified.
2. Back up the authoritative local SQLite state database independently; the pCloud
   `metadata/catalog.db` copy is a published snapshot, not the live database.
3. Retain import manifests so a physical cloud object can be traced back to every source
   path that supplied it.
4. Periodically run size verification, periodic hash verification for hashed objects, and
   verify an independent backup copy as well.
5. Do not make cleanup reports destructive by automation. Review duplicate/empty/
   unreferenced candidates against source and backup evidence before removing anything.

The import tool never removes source files. Bulk source cleanup belongs to a separate,
explicitly reviewed workflow after independent backup verification.
