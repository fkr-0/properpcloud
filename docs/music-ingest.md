# Music ingestion and quality audit

`scripts/music_ingest.py` is the server-side ingestion utility for bringing an
external music collection into the filesystem-first pCloud library. It never
edits a source file in place.

## Safety model

The pipeline is deliberately split across two storage classes:

1. source files are opened read-only for metadata, hashes, and audio probes;
2. a local staging copy under `~/.cache/properpcloud/music-ingest-staging/` is
   modified with Mutagen;
3. the completed staged file is copied once to a deterministic hidden partial
   name on rclone FUSE and renamed into place only after the copy succeeds;
4. resumable state is committed to the local SQLite database at
   `~/.local/state/properpcloud/music-ingest.sqlite3`;
5. final `quality-audit.json`, `unsorted-manifest.json`, and `ingest-report.md` snapshots are written under
   `/tmp/dib/media-library/metadata/`.

Keeping the frequently updated SQLite database local avoids a write-heavy
SQLite workload over pCloud FUSE. The reports remain in pCloud as portable
library metadata.

The utility refuses to fall back to `/` when it cannot discover an external or
loop-device mount. Explicit `--source-root` values are always preferred for a
controlled run. It also refuses writes below `/tmp/dib` if that path is no
longer an active mountpoint, preventing a dropped rclone mount from silently
turning a cloud ingest into local `/tmp` data.

## Discovery

Supported music extensions are MP3, FLAC, WAV, Ogg Vorbis, Opus, AAC, M4A,
WMA, ALAC, AIFF/AIF, and APE.

List mounted external/loop filesystems:

    python scripts/music_ingest.py discover --json

The automatic detector reads `lsblk` JSON and follows mounted USB/removable,
`sd*`, and loop-device trees. It only returns real mountpoints and excludes the
system root. The JSON response also reports pCloud mount readiness and whether
the companion catalog exists.

When the companion file catalog already exists, pass it directly. The reader
supports SQLite tables with common absolute path-column names such as `path`,
`file_path`, `absolute_path`, `source_path`, and `full_path`, plus catalogs that
store `relative_path`/`source_relative_path` with a `source_root`/`mount_root`:

    python scripts/music_ingest.py ingest \
      --catalog-db /path/to/catalog.sqlite \
      --dry-run

Direct roots and catalog databases can be combined and repeated.

When neither is supplied, the utility automatically prefers the companion
`disk-catalog` database at `~/.local/share/disk-catalog/catalog.db` when it
exists; otherwise it scans automatically discovered mounted external/loop
filesystems. Override the companion location with `PROPERPCLOUD_DISK_CATALOG`.

## Dry run first

For a newly mounted disk, first bound the run:

    python scripts/music_ingest.py ingest \
      --source-root /mnt/ext-sda1 \
      --source-root /mnt/ext-sda3 \
      --limit 100 \
      --dry-run

Dry-run mode hashes and inspects sources and prints the planned destination,
but does not create media-library files or reports.

## Ingestion layout

Tagged album tracks are written as:

    /tmp/dib/media-library/audio/music/
      Artist Name/
        (Year) Album Name/
          01 Track Title.ext

Tracks with an artist but no album go under `Artist Name/Singles/`.
Compilation tracks with a `Various Artists` album artist go under
`Compilations/(Year) Album/`. Tracks without a usable artist are placed under
`Unsorted/` with a stable source-path fingerprint to prevent ambiguous name
collisions. The state database retains the original source path and original
metadata for every processed item. `unsorted-manifest.json` additionally makes
the original directory context portable with the library.

Filename components are Unicode NFC-normalized. The utility removes filesystem
reserved characters, URL/site artifacts and long hash artifacts, collapses
space/underscore runs, trims leading/trailing spaces and dots, normalizes a
leading track number to `NN `, and caps the final filename at 200 characters.
Meaningful parenthetical text such as `(feat. X)`, `(Remix)`, `(Live)`,
`(Acoustic)`, and `(Remastered)` is not removed.

## Metadata normalization

Mutagen reads common title, artist, album, album artist, track number, date, and
genre fields. Missing values are inferred conservatively from a descriptive
filename and parent directories. Existing meaningful metadata wins over path
heuristics.

For ID3-backed files, writes use Unicode ID3v2.4 and suppress ID3v1 output.
Changed ID3 records receive a `PROPERPCLOUD_ORIGINAL_TAGS` TXXX JSON backup.
Other supported EasyTag containers receive the same normalized common fields
when their Mutagen mapping permits writes; the local ingestion database always
retains the pre-normalization JSON backup even when a container cannot embed a
custom backup field.

Embedded ID3/FLAC/Ogg pictures larger than 500 KiB are removed from the staged
copy. A sibling `cover.jpg`, `folder.jpg`, or `front.jpg` at or below 500 KiB is
copied once into an album folder when available.

Genre spellings are normalized against a conservative canonical mapping. An
unknown but meaningful genre value is preserved instead of being guessed away.

## MP3 quality audit

MP3 bitrate is measured from audio/container data, never from a filename or tag.
Mutagen reads MPEG frame/Xing information and `ffprobe` provides an independent
stream/container measurement when installed. The quality tiers are:

- below 192 kbps: `LOW_QUALITY`;
- 192–255 kbps: `ACCEPTABLE`;
- 256 kbps and above: `GOOD`;
- an explicitly recoverable LAME/Xing V0, V1, or V2 profile: `GOOD`, even when
  quiet material has a lower average bitrate;
- 320 kbps CBR: `HIGH`.

The staged MP3 receives a `PROPERPCLOUD_QUALITY` TXXX tag. No low-quality track
is deleted. `quality-audit.json` includes every low-quality destination,
measured bitrate, common metadata, and a higher-quality in-collection match
when the normalized artist/title identity has one.

The byte total reported for low-quality tracks is storage currently occupied
by those sources; it is a replacement-planning metric, not a claim that the
whole size could be saved without a replacement codec decision.

## Resume and reports

Run ingestion normally after reviewing a bounded dry run:

    python scripts/music_ingest.py ingest \
      --source-root /mnt/ext-sda1 \
      --source-root /mnt/ext-sda3

Each completed source records size and `mtime_ns`. A later run skips unchanged
completed inputs whose destination still exists. Exact content duplicates are
identified by SHA-256 and reference the already ingested destination.

Only one ingest process may use a state database at a time. A local advisory
lock (`music-ingest.sqlite3.lock`) fails a second writer immediately instead of
allowing two processes to race on pCloud destinations. The lock is kernel-held;
a stale lock file after a crash does not keep the database locked.

Use `--no-resume` to deliberately re-evaluate completed sources. To rebuild
portable reports without re-reading the source collection:

    python scripts/music_ingest.py report

Final report paths:

- `/tmp/dib/media-library/metadata/quality-audit.json`
- `/tmp/dib/media-library/metadata/unsorted-manifest.json`
- `/tmp/dib/media-library/metadata/ingest-report.md`

The Markdown report contains processed/duplicate/failure counts, filename/tag
change counts, removed artwork count, unique artist and album counts, measured
audio duration, the MP3 quality-tier breakdown, and low-quality replacement
matches.

## Operational caveats

- Automatic discovery only sees mounted filesystems. Mount the external
  partitions through the server's persistent mount infrastructure first.
- Raw AAC and unusual WMA/APE variants may expose limited writable tag mappings
  in Mutagen. The source is still preserved and failures are recorded rather
  than silently discarded.
- Exact SHA-256 duplicate suppression intentionally does not collapse two files
  whose audio is the same but whose bytes differ. Acoustic-fingerprint dedupe
  is a separate, review-sensitive policy.
- Album art copying currently recognizes JPEG cover files. Embedded artwork is
  preserved when it is 500 KiB or smaller.
- Source paths that resolve inside the destination music library are excluded,
  preventing accidental recursive self-ingestion.
