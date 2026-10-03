import importlib.util
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import yaml


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("properpcloud_media_library", ROOT / "scripts/media_library.py")
assert SPEC and SPEC.loader
ml = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ml
SPEC.loader.exec_module(ml)


class MediaLibraryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source_root = self.root / "source" / "sda1"
        self.library_root = self.root / "cloud" / "media-library"
        self.source_root.mkdir(parents=True)
        self.source_db = self.root / "source.db"
        self.state_db = self.root / "state.db"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def build_source_catalog(self, rows):
        connection = sqlite3.connect(self.source_db)
        connection.execute(
            "CREATE TABLE discovered_files(path TEXT, relative_path TEXT, size_bytes INTEGER, mtime_ns INTEGER, source_disk TEXT, media_type TEXT, sha256 TEXT)"
        )
        connection.executemany("INSERT INTO discovered_files VALUES(?,?,?,?,?,?,?)", rows)
        connection.commit()
        connection.close()

    def build_relative_source_catalog(self, rows):
        connection = sqlite3.connect(self.source_db)
        connection.execute(
            "CREATE TABLE discovered_files(relative_path TEXT, source_root TEXT, size_bytes INTEGER, mtime_ns INTEGER, source_disk TEXT, media_type TEXT, sha256 TEXT)"
        )
        connection.executemany("INSERT INTO discovered_files VALUES(?,?,?,?,?,?,?)", rows)
        connection.commit()
        connection.close()

    def test_init_creates_documented_tree_and_catalog_snapshot(self):
        self.assertEqual(
            0,
            ml.main([
                "--library-root", str(self.library_root),
                "--state-db", str(self.state_db),
                "init",
            ]),
        )
        for relative in ml.LIBRARY_DIRS:
            self.assertTrue((self.library_root / relative).is_dir(), relative)
        readme = (self.library_root / "README.md").read_text()
        self.assertIn("cloud storage, not the only backup", readme)
        self.assertIn("metadata/catalog.db", readme)
        self.assertTrue((self.library_root / "metadata/catalog.db").is_file())

    def test_classification_uses_path_context(self):
        self.assertEqual(("audio", "samples"), ml.classify(Path("packs/loops/kick.wav")))
        self.assertEqual(("images", "screenshots"), ml.classify(Path("Screenshots/x.png")))
        self.assertEqual(("documents", "ebooks"), ml.classify(Path("book.epub")))
        self.assertIsNone(ml.classify(Path("unknown.bin")))

    def test_size_time_and_relative_path_normalization_are_lossless(self):
        self.assertEqual(10 * 1024 * 1024, ml.parse_size("10MiB"))
        self.assertEqual(10 * 1000 * 1000, ml.parse_size("10MB"))
        exact_ns = 1_788_528_000_123_456_789
        self.assertEqual((exact_ns, 0), ml.normalize_mtime(exact_ns, 0))
        self.assertEqual("Ärtist name/very long recording name.flac", str(ml.sanitize_relative(ml.PurePosixPath("Ärtist name/very long recording name.flac"))))
        with self.assertRaises(ValueError):
            ml.sanitize_relative(ml.PurePosixPath("../escape.flac"))

    def test_relative_only_catalog_rejects_absolute_and_parent_escape_before_source_access(self):
        self.build_relative_source_catalog([
            ("/etc/passwd", str(self.source_root), 1, 1, "sda1", "audio", None),
            ("../escape.wav", str(self.source_root), 1, 1, "sda1", "audio", None),
        ])
        catalog = ml.SourceCatalog(self.source_db)
        try:
            items = list(catalog.items())
        finally:
            catalog.close()
        self.assertEqual(2, len(items))
        self.assertTrue(all(isinstance(item, ml.CatalogIssue) for item in items))

    def test_source_provenance_location_under_common_automount(self):
        disk, relative = ml.source_provenance_location(
            Path("/run/media/user/volume-uuid/Music/Artist/track.flac")
        )
        self.assertEqual("volume-uuid", disk)
        self.assertEqual("Music/Artist/track.flac", str(relative))

    def test_music_ingest_sync_builds_canonical_catalog_and_source_provenance(self):
        destination = self.library_root / "audio/music/Artist/Album/01 Track.wav"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"RIFFcatalogued")
        music_state = self.root / "music-ingest.sqlite3"
        source = sqlite3.connect(music_state)
        source.execute(
            """CREATE TABLE ingest_files(
                 source_path TEXT PRIMARY KEY, source_size INTEGER NOT NULL,
                 source_mtime_ns INTEGER NOT NULL, source_sha256 TEXT, status TEXT NOT NULL,
                 destination_path TEXT, normalized_tags_json TEXT, quality_tier TEXT,
                 bitrate_kbps INTEGER, bitrate_mode TEXT, duration_seconds REAL, updated_at TEXT NOT NULL
               )"""
        )
        tags = '{"title":"Track","artist":"Artist","album":"Album"}'
        source.executemany(
            "INSERT INTO ingest_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            [
                (
                    "/run/media/user/vol/Music/a.wav", 100, 10, "a" * 64, "INGESTED",
                    str(destination), tags, "GOOD", 256, "VBR", 12.5, ml.utc_now(),
                ),
                (
                    "/run/media/user/vol/Backup/a.wav", 100, 11, "a" * 64, "DUPLICATE",
                    str(destination), tags, None, None, None, None, ml.utc_now(),
                ),
            ],
        )
        source.commit()
        source.close()

        db = ml.LibraryDatabase(self.state_db)
        try:
            preview = ml.sync_music_ingest_catalog(db, self.library_root, music_state, execute=False)
            self.assertEqual(1, preview["cataloged_files"])
            self.assertEqual(2, preview["source_links"])
            self.assertEqual(0, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])

            applied = ml.sync_music_ingest_catalog(db, self.library_root, music_state, execute=True)
            self.assertEqual(1, applied["cataloged_files"])
            self.assertEqual(2, applied["source_links"])
            row = db.connection.execute("SELECT * FROM library_files").fetchone()
            self.assertEqual("audio/music/Artist/Album/01 Track.wav", row["library_path"])
            self.assertEqual("audio", row["media_type"])
            self.assertEqual("music", row["media_subtype"])
            self.assertEqual("Track", row["audio_title"])
            self.assertEqual(256000, row["audio_bitrate"])
            self.assertIsNone(row["sha256"])
            provenance = db.connection.execute(
                "SELECT source_disk,source_relative_path,source_hash FROM source_items ORDER BY source_path"
            ).fetchall()
            self.assertEqual(2, len(provenance))
            self.assertEqual({"vol"}, {item["source_disk"] for item in provenance})
            self.assertEqual({"a" * 64}, {item["source_hash"] for item in provenance})
        finally:
            db.close()

    def test_music_ingest_sync_reports_missing_destination_without_cataloguing_it(self):
        music_state = self.root / "missing-music.sqlite3"
        source = sqlite3.connect(music_state)
        source.execute(
            """CREATE TABLE ingest_files(
                 source_path TEXT PRIMARY KEY, source_size INTEGER NOT NULL,
                 source_mtime_ns INTEGER NOT NULL, source_sha256 TEXT, status TEXT NOT NULL,
                 destination_path TEXT, normalized_tags_json TEXT, quality_tier TEXT,
                 bitrate_kbps INTEGER, bitrate_mode TEXT, duration_seconds REAL, updated_at TEXT NOT NULL
               )"""
        )
        missing = self.library_root / "audio/music/missing.wav"
        source.execute(
            "INSERT INTO ingest_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            ("/mnt/disk/missing.wav", 4, 1, None, "INGESTED", str(missing), "{}", None, None, None, None, ml.utc_now()),
        )
        source.commit()
        source.close()
        db = ml.LibraryDatabase(self.state_db)
        try:
            report = ml.sync_music_ingest_catalog(db, self.library_root, music_state, execute=True)
            self.assertEqual([str(missing)], report["missing_destinations"])
            self.assertEqual(0, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])
        finally:
            db.close()

    def test_source_root_rejects_intermediate_symlink_escape(self):
        outside = self.root / "outside"
        outside.mkdir()
        media = outside / "escaped.wav"
        media.write_bytes(b"RIFFescape")
        (self.source_root / "linked").symlink_to(outside, target_is_directory=True)
        stat = media.stat()
        self.build_relative_source_catalog([
            ("linked/escaped.wav", str(self.source_root), stat.st_size, stat.st_mtime_ns, "sda1", "audio", None),
        ])
        catalog = ml.SourceCatalog(self.source_db)
        try:
            item = next(catalog.items())
        finally:
            catalog.close()
        self.assertIsInstance(item, ml.CatalogIssue)
        self.assertIn("escapes", item.reason)

    def test_library_init_rejects_intermediate_symlink_escape(self):
        outside = self.root / "outside-library"
        outside.mkdir()
        self.library_root.mkdir(parents=True)
        (self.library_root / "audio").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            ml.initialize_library(self.library_root)

    def test_realpath_aliases_share_containment_identity(self):
        target = self.root / "real-root"
        target.mkdir()
        alias = self.root / "alias-root"
        alias.symlink_to(target, target_is_directory=True)
        self.assertTrue(ml.is_within(alias / "nested", target))
        self.assertTrue(ml.is_within(target / "nested", alias))

    def test_import_is_incremental_and_publishes_manifest(self):
        source = self.source_root / "Music" / "Artist" / "track.flac"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"fLaC" + b"x" * 64)
        stat = source.stat()
        self.build_source_catalog([(str(source), "Music/Artist/track.flac", stat.st_size, stat.st_mtime_ns, "sda1", "audio", None)])

        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            first = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, first.copied)
            self.assertEqual(0, first.failed)
            catalog.close()
            catalog = ml.SourceCatalog(self.source_db)
            second = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, second.unchanged)
            db.publish_snapshot(self.library_root)
        finally:
            catalog.close()
            db.close()

        target = self.library_root / "audio/music/sda1/Music/Artist/track.flac"
        self.assertEqual(source.read_bytes(), target.read_bytes())
        self.assertTrue((self.library_root / "metadata/catalog.db").is_file())
        self.assertEqual(2, len(list((self.library_root / "metadata/manifests").glob("*.jsonl"))))

    def test_hash_mode_does_not_misclassify_generic_non_sha256_hash(self):
        source = self.source_root / "generic-hash.flac"
        source.write_bytes(b"fLaC" + b"generic")
        stat = source.stat()
        self.build_source_catalog([
            (str(source), source.name, stat.st_size, stat.st_mtime_ns, "sda1", "audio", "d41d8cd98f00b204e9800998ecf8427e"),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "hash", False, None).run(catalog)
            self.assertEqual(1, summary.copied)
            stored = db.connection.execute("SELECT source_hash FROM source_items").fetchone()[0]
            self.assertEqual(64, len(stored))
            self.assertEqual(ml.sha256_file(source), stored)
        finally:
            catalog.close()
            db.close()

    def test_hash_mode_rejects_stale_catalog_hash(self):
        source = self.source_root / "wrong-hash.flac"
        source.write_bytes(b"fLaC" + b"payload")
        stat = source.stat()
        self.build_source_catalog([
            (str(source), source.name, stat.st_size, stat.st_mtime_ns, "sda1", "audio", "0" * 64),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "hash", False, None).run(catalog)
            self.assertEqual(1, summary.failed)
            self.assertEqual(0, summary.copied)
            self.assertEqual(0, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])
        finally:
            catalog.close()
            db.close()

    def test_hash_mode_treats_computed_hash_as_extra_unchanged_evidence(self):
        source = self.source_root / "computed.wav"
        source.write_bytes(b"RIFF" + b"payload")
        stat = source.stat()
        self.build_source_catalog([
            (str(source), source.name, stat.st_size, stat.st_mtime_ns, "sda1", "audio", None),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            first = ml.Importer(db, self.library_root, self.source_db, "hash", False, None).run(catalog)
            self.assertEqual(1, first.copied)
            catalog.close()
            catalog = ml.SourceCatalog(self.source_db)
            second = ml.Importer(db, self.library_root, self.source_db, "hash", False, None).run(catalog)
            self.assertEqual(1, second.unchanged)
            self.assertEqual(0, second.deduplicated)
        finally:
            catalog.close()
            db.close()

    def test_changed_same_size_source_is_reimported_not_self_deduplicated(self):
        source = self.source_root / "mutable.flac"
        source.write_bytes(b"fLaC" + b"AAAA")
        first_stat = source.stat()
        self.build_source_catalog([
            (str(source), source.name, first_stat.st_size, first_stat.st_mtime_ns, "sda1", "audio", None),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            first = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, first.copied)
            catalog.close()

            source.write_bytes(b"fLaC" + b"BBBB")
            changed_mtime = first_stat.st_mtime_ns + 2_000_000_000
            os.utime(source, ns=(source.stat().st_atime_ns, changed_mtime))
            changed_stat = source.stat()
            connection = sqlite3.connect(self.source_db)
            connection.execute(
                "UPDATE discovered_files SET mtime_ns=? WHERE path=?",
                (changed_stat.st_mtime_ns, str(source)),
            )
            connection.commit()
            connection.close()

            catalog = ml.SourceCatalog(self.source_db)
            second = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, second.copied)
            self.assertEqual(0, second.deduplicated)
            self.assertEqual(2, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])
            mapped = db.connection.execute(
                "SELECT l.library_path FROM source_items s JOIN library_files l ON l.id=s.library_file_id WHERE s.source_path=?",
                (str(source),),
            ).fetchone()[0]
            self.assertIn("__", Path(mapped).name)
            self.assertEqual(source.read_bytes(), (self.library_root / mapped).read_bytes())
        finally:
            catalog.close()
            db.close()

    def test_invalid_catalog_row_is_isolated_and_manifested(self):
        bad = self.source_root / "bad.wav"
        good = self.source_root / "good.wav"
        bad.write_bytes(b"RIFFbad")
        good.write_bytes(b"RIFFgood")
        bad_stat = bad.stat()
        good_stat = good.stat()
        self.build_source_catalog([
            (str(bad), "../escape.wav", bad_stat.st_size, bad_stat.st_mtime_ns, "sda1", "audio", None),
            (str(good), "good.wav", good_stat.st_size, good_stat.st_mtime_ns, "sda1", "audio", None),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, summary.failed)
            self.assertEqual(1, summary.copied)
        finally:
            catalog.close()
            db.close()
        manifest = next((self.library_root / "metadata/manifests").glob("*.jsonl")).read_text()
        self.assertIn('"reason": "catalog_row_invalid"', manifest)
        self.assertIn("good.wav", manifest)

    def test_copy_retry_is_bounded(self):
        with mock.patch.object(ml, "copy_with_progress", side_effect=[OSError("busy"), OSError("busy"), 123]) as copied:
            with mock.patch.object(ml.time, "sleep") as slept:
                self.assertEqual(123, ml.copy_with_retries(Path("source"), Path("target"), attempts=3))
        self.assertEqual(3, copied.call_count)
        self.assertEqual(2, slept.call_count)

    def test_copy_temp_name_does_not_extend_max_length_media_name(self):
        source = self.source_root / ("a" * 240 + ".wav")
        source.write_bytes(b"RIFFlong-name")
        target = self.library_root / "audio/music/sda1" / source.name
        copied = ml.copy_with_progress(source, target)
        self.assertEqual(source.stat().st_size, copied)
        self.assertEqual(source.read_bytes(), target.read_bytes())
        self.assertEqual([], list(target.parent.glob(".part-*")))

    def test_canonical_mount_boundary_fails_closed(self):
        with mock.patch.object(ml.os.path, "ismount", return_value=False):
            with self.assertRaises(RuntimeError):
                ml.validate_storage_boundary(Path("/tmp/dib/media-library"), Path("/tmp/state.db"))
        with mock.patch.object(ml.os.path, "ismount", return_value=True):
            with self.assertRaises(RuntimeError):
                ml.validate_storage_boundary(
                    Path("/tmp/dib/media-library"),
                    Path("/tmp/dib/media-library/metadata/live.db"),
                )
        with mock.patch.object(ml, "filesystem_type_for_path", return_value="fuse.rclone"):
            with self.assertRaises(RuntimeError):
                ml.validate_storage_boundary(self.library_root, self.state_db)
        with mock.patch.object(ml.os.path, "ismount", return_value=False):
            ml.validate_storage_boundary(
                Path("/tmp/dib/media-library"),
                self.state_db,
                require_library_mount=False,
            )

    def test_writer_lock_serializes_same_library_root(self):
        first = ml.acquire_writer_lock(self.library_root, self.state_db)
        try:
            with self.assertRaises(RuntimeError):
                ml.acquire_writer_lock(self.library_root, self.state_db)
        finally:
            ml.release_writer_lock(first)
        second = ml.acquire_writer_lock(self.library_root, self.state_db)
        ml.release_writer_lock(second)

    def test_dry_run_reports_planned_bytes_without_claiming_a_copy(self):
        source = self.source_root / "preview.flac"
        source.write_bytes(b"fLaCpreview")
        stat = source.stat()
        self.build_source_catalog([
            (str(source), source.name, stat.st_size, stat.st_mtime_ns, "sda1", "audio", None),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "filename-size", True, None).run(catalog)
        finally:
            catalog.close()
            db.close()
        self.assertEqual(0, summary.copied)
        self.assertEqual(0, summary.bytes_copied)
        self.assertEqual(1, summary.would_copy)
        self.assertEqual(stat.st_size, summary.bytes_would_copy)
        self.assertFalse((self.library_root / "audio/music/sda1/preview.flac").exists())

    def test_missing_cataloged_source_fails_without_blocking_following_rows(self):
        missing = self.source_root / "missing.wav"
        good = self.source_root / "good-after-missing.wav"
        good.write_bytes(b"RIFFgood")
        good_stat = good.stat()
        self.build_source_catalog([
            (str(missing), missing.name, 8, good_stat.st_mtime_ns, "sda1", "audio", None),
            (str(good), good.name, good_stat.st_size, good_stat.st_mtime_ns, "sda1", "audio", None),
        ])
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "filename-size", False, None).run(catalog)
            self.assertEqual(1, summary.failed)
            self.assertEqual(1, summary.copied)
        finally:
            catalog.close()
            db.close()
        manifest = next((self.library_root / "metadata/manifests").glob("*.jsonl")).read_text()
        self.assertIn("FileNotFoundError", manifest)
        self.assertIn("good-after-missing.wav", manifest)

    def test_normative_spec_docs_and_make_targets_are_wired(self):
        manifest = yaml.safe_load((ROOT / "spec/manifest.yml").read_text())
        files = [entry["file"] for entry in manifest["specification"]["source_of_truth"]]
        self.assertIn("media-library.yml", files)
        contract = yaml.safe_load((ROOT / "spec/media-library.yml").read_text())["media_library"]
        self.assertEqual("/tmp/dib/media-library", contract["paths"]["default_root"])
        self.assertEqual("dry_run", contract["ingestion"]["default_mode"])
        self.assertFalse(contract["backup"]["pcloud_is_backup"])
        self.assertIn("SHA-256 exact-content equality", contract["deduplication"]["modes"]["hash"])
        self.assertEqual(
            "~/.local/state/properpcloud/music-ingest.sqlite3",
            contract["ingestion"]["specialized_music_reconciliation"]["source"],
        )
        self.assertIn(
            "never asserted as library_files.sha256",
            contract["ingestion"]["specialized_music_reconciliation"]["transformed_hash_rule"],
        )

        docs = (ROOT / "docs/media-library.md").read_text()
        self.assertIn("--extension flac --min-size 10MiB", docs)
        self.assertIn("--media-type images --taken-year 2024", docs)
        self.assertIn("report-only", docs.lower())
        makefile = (ROOT / "Makefile").read_text()
        for target in (
            "media-library-test:", "media-library-init:", "media-library-dry-run:",
            "media-library-import:", "media-library-adopt-existing:", "media-library-adopt-existing-apply:",
            "media-library-sync-music:", "media-library-sync-music-apply:", "music-organize-plan:",
            "media-library-verify:", "media-library-space:", "media-library-cleanup:",
        ):
            self.assertIn(target, makefile)

    def test_hash_dedupe_preserves_two_source_provenance_rows(self):
        first = self.source_root / "a" / "same.wav"
        second = self.source_root / "b" / "copy.wav"
        first.parent.mkdir(parents=True)
        second.parent.mkdir(parents=True)
        payload = b"RIFF" + b"0" * 128
        first.write_bytes(payload)
        second.write_bytes(payload)
        digest = ml.sha256_file(first)
        rows = []
        for path, relative in ((first, "a/same.wav"), (second, "b/copy.wav")):
            stat = path.stat()
            rows.append((str(path), relative, stat.st_size, stat.st_mtime_ns, "sda1", "audio", digest))
        self.build_source_catalog(rows)
        db = ml.LibraryDatabase(self.state_db)
        catalog = ml.SourceCatalog(self.source_db)
        try:
            summary = ml.Importer(db, self.library_root, self.source_db, "hash", False, None).run(catalog)
            self.assertEqual(1, summary.copied)
            self.assertEqual(1, summary.deduplicated)
            self.assertEqual(1, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])
            self.assertEqual(2, db.connection.execute("SELECT COUNT(*) FROM source_items").fetchone()[0])
        finally:
            catalog.close()
            db.close()

    def test_query_verify_space_and_cleanup(self):
        ml.initialize_library(self.library_root)
        media = self.library_root / "audio/music/sda1/large.flac"
        media.parent.mkdir(parents=True, exist_ok=True)
        media.write_bytes(b"x" * 128)
        db = ml.LibraryDatabase(self.state_db)
        try:
            db.connection.execute(
                "INSERT INTO library_files(library_path,filename,extension,size_bytes,media_type,media_subtype,mtime_ns,sha256,imported_at,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                ("audio/music/sda1/large.flac", "large.flac", ".flac", 128, "audio", "music", 1, ml.sha256_file(media), ml.utc_now(), None),
            )
            db.connection.commit()
            args = type("Args", (), {"extension": "flac", "min_size": 100, "media_type": None, "taken_year": None, "limit": 10})()
            self.assertEqual(1, len(ml.query_files(db, args)))
            verify = ml.verify_library(db, self.library_root, True)
            self.assertEqual([], verify["missing"])
            self.assertEqual(128, ml.space_report(db)["physical"]["bytes"])
            orphan = self.library_root / "images/photos/orphan.jpg"
            orphan.parent.mkdir(parents=True, exist_ok=True)
            orphan.write_bytes(b"")
            partial = self.library_root / "video/media/.clip.mp4.part-deadbeef"
            partial.parent.mkdir(parents=True, exist_ok=True)
            partial.write_bytes(b"partial")
            cleanup = ml.cleanup_report(db, self.library_root, limit=10)
            self.assertEqual(0, cleanup["catalog_empty_files"]["count"])
            self.assertEqual(1, cleanup["filesystem_empty_files"]["count"])
            self.assertEqual(1, cleanup["untracked_media_files"]["count"])
            self.assertEqual(1, cleanup["partial_uploads"]["count"])
        finally:
            db.close()

    def test_adopt_existing_catalogs_bytes_without_fabricating_source_provenance(self):
        track = self.library_root / "audio/music/Artist/Album/01 Track.wav"
        track.parent.mkdir(parents=True, exist_ok=True)
        track.write_bytes(b"RIFFexisting")
        db = ml.LibraryDatabase(self.state_db)
        try:
            preview = ml.adopt_existing_media(
                db, self.library_root, prefixes=("audio/music",), execute=False, hashes=True
            )
            self.assertEqual(1, preview["candidates"])
            self.assertEqual(0, db.connection.execute("SELECT COUNT(*) FROM library_files").fetchone()[0])

            applied = ml.adopt_existing_media(
                db, self.library_root, prefixes=("audio/music",), execute=True, hashes=True
            )
            self.assertEqual(1, applied["adopted"])
            row = db.connection.execute("SELECT * FROM library_files").fetchone()
            self.assertEqual("audio/music/Artist/Album/01 Track.wav", row["library_path"])
            self.assertEqual(ml.sha256_file(track), row["sha256"])
            self.assertEqual(0, db.connection.execute("SELECT COUNT(*) FROM source_items").fetchone()[0])
            metadata = json.loads(row["metadata_json"])
            self.assertEqual("existing_pcloud_adoption", metadata["catalog_source"])
            self.assertEqual("unresolved", metadata["provenance_status"])
            cleanup = ml.cleanup_report(db, self.library_root, limit=10)
            self.assertEqual(1, cleanup["adopted_unresolved_provenance_files"]["count"])
            self.assertEqual(0, cleanup["untracked_media_files"]["count"])
        finally:
            db.close()

    def test_adopt_existing_skips_location_extension_mismatch(self):
        cover = self.library_root / "audio/music/Artist/Album/cover.jpg"
        cover.parent.mkdir(parents=True, exist_ok=True)
        cover.write_bytes(b"jpeg")
        db = ml.LibraryDatabase(self.state_db)
        try:
            report = ml.adopt_existing_media(
                db, self.library_root, prefixes=("audio/music",), execute=True, hashes=False
            )
            self.assertEqual(0, report["adopted"])
            self.assertEqual(1, report["skipped_unsupported"])
        finally:
            db.close()

    def test_source_catalog_autodetects_common_schema(self):
        path = self.source_root / "photo.jpg"
        path.write_bytes(b"jpeg")
        stat = path.stat()
        self.build_source_catalog([(str(path), "photo.jpg", stat.st_size, stat.st_mtime_ns, "sda1", "image", None)])
        catalog = ml.SourceCatalog(self.source_db)
        try:
            self.assertEqual("discovered_files", catalog.table)
            item = next(catalog.items())
            self.assertEqual("sda1", item.source_disk)
            self.assertEqual("photo.jpg", str(item.relative_path))
        finally:
            catalog.close()


if __name__ == "__main__":
    unittest.main()
