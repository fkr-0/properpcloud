import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


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

    def test_init_creates_documented_tree(self):
        ml.initialize_library(self.library_root)
        for relative in ml.LIBRARY_DIRS:
            self.assertTrue((self.library_root / relative).is_dir(), relative)
        readme = (self.library_root / "README.md").read_text()
        self.assertIn("cloud storage, not the only backup", readme)
        self.assertIn("metadata/catalog.db", readme)

    def test_classification_uses_path_context(self):
        self.assertEqual(("audio", "samples"), ml.classify(Path("packs/loops/kick.wav")))
        self.assertEqual(("images", "screenshots"), ml.classify(Path("Screenshots/x.png")))
        self.assertEqual(("documents", "ebooks"), ml.classify(Path("book.epub")))
        self.assertIsNone(ml.classify(Path("unknown.bin")))

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
            self.assertEqual([], ml.cleanup_report(db, self.library_root)["empty_files"])
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
