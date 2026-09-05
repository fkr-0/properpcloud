from __future__ import annotations

import importlib.util
import json
import shutil
import sqlite3
import subprocess
import sys
import wave
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "music_ingest.py"
SPEC = importlib.util.spec_from_file_location("music_ingest", SCRIPT)
assert SPEC and SPEC.loader
music_ingest = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = music_ingest
SPEC.loader.exec_module(music_ingest)


def make_wav(path: Path, seconds: float = 0.1) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frames = int(8_000 * seconds)
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(8_000)
        audio.writeframes(b"\x00\x00" * frames)


def test_sanitize_filename_normalizes_track_and_junk() -> None:
    value = music_ingest.sanitize_filename(
        '01 -  My__Song [www.badsite.com] <Live>? 0123456789abcdef0123456789abcdef.mp3'
    )
    assert value == "01 My Song Live.mp3"
    assert len(value) <= 200


def test_sanitize_component_preserves_meaningful_parentheses_and_unicode() -> None:
    value = music_ingest.sanitize_component("  Cafe\u0301  (Acoustic)___Remix  ")
    assert value == "Café (Acoustic) Remix"


def test_destination_album_single_compilation_and_unsorted(tmp_path: Path) -> None:
    root = tmp_path / "music"
    source = tmp_path / "src" / "01 - Song.mp3"

    album = music_ingest.destination_for(
        music_ingest.TrackMetadata(title="Song", artist="Artist", album="Album", track="1", year="2024"),
        source,
        root,
    )
    assert album == root / "Artist" / "(2024) Album" / "01 Song.mp3"

    single = music_ingest.destination_for(
        music_ingest.TrackMetadata(title="Song", artist="Artist"), source, root
    )
    assert single == root / "Artist" / "Singles" / "Song.mp3"

    compilation = music_ingest.destination_for(
        music_ingest.TrackMetadata(
            title="Song", artist="Guest", album="Hits", album_artist="Various Artists", compilation=True
        ),
        source,
        root,
    )
    assert compilation == root / "Compilations" / "Hits" / "Song.mp3"

    unsorted = music_ingest.destination_for(music_ingest.TrackMetadata(title="Song"), source, root)
    assert unsorted.parent == root / "Unsorted"
    assert "[" in unsorted.name


@pytest.mark.parametrize(
    ("bitrate", "mode", "tier"),
    [
        (128, "CBR", "LOW_QUALITY"),
        (191, "VBR", "LOW_QUALITY"),
        (192, "CBR", "ACCEPTABLE"),
        (255, "VBR", "ACCEPTABLE"),
        (256, "VBR", "GOOD"),
        (180, "VBR V2", "GOOD"),
        (320, "VBR", "GOOD"),
        (320, "CBR", "HIGH"),
        (None, None, "UNKNOWN"),
    ],
)
def test_quality_tiers(bitrate: int | None, mode: str | None, tier: str) -> None:
    assert music_ingest.classify_quality(bitrate, mode) == tier


def test_catalog_discovery_reads_common_path_column(tmp_path: Path) -> None:
    db = tmp_path / "catalog.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE files(id INTEGER PRIMARY KEY, path TEXT, media_type TEXT)")
    con.executemany(
        "INSERT INTO files(path, media_type) VALUES (?, ?)",
        [("/mnt/a/song.mp3", "audio"), ("/mnt/a/movie.mp4", "video"), ("/mnt/a/song.flac", "audio")],
    )
    con.commit()
    con.close()
    assert [str(path) for path in music_ingest.iter_catalog_files(db)] == [
        "/mnt/a/song.mp3",
        "/mnt/a/song.flac",
    ]


def test_catalog_discovery_resolves_relative_path_against_source_root(tmp_path: Path) -> None:
    root = tmp_path / "mounted"
    root.mkdir()
    db = tmp_path / "catalog-relative.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE files(source_root TEXT, source_relative_path TEXT)")
    con.executemany(
        "INSERT INTO files(source_root, source_relative_path) VALUES (?, ?)",
        [(str(root), "A/song.mp3"), (str(root), "A/not-music.txt")],
    )
    con.commit()
    con.close()
    assert list(music_ingest.iter_catalog_files(db)) == [root / "A/song.mp3"]


def test_default_companion_catalog_is_used_when_no_source_root_is_given(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "mounted" / "Artist" / "(2024) Album" / "01 - Song.wav"
    make_wav(source)
    catalog = tmp_path / "catalog.sqlite"
    con = sqlite3.connect(catalog)
    con.execute("CREATE TABLE files(path TEXT NOT NULL, category TEXT)")
    con.execute("INSERT INTO files(path, category) VALUES (?, 'audio')", (str(source),))
    con.commit()
    con.close()
    monkeypatch.setattr(music_ingest, "DEFAULT_COMPANION_CATALOG", catalog)

    library = tmp_path / "library"
    assert (
        music_ingest.main(
            [
                "ingest",
                "--library-root",
                str(library),
                "--metadata-root",
                str(tmp_path / "metadata"),
                "--state-db",
                str(tmp_path / "state.sqlite"),
                "--staging-root",
                str(tmp_path / "staging"),
            ]
        )
        == 0
    )
    assert (library / "Artist" / "(2024) Album" / "01 Song.wav").is_file()


def test_pcloud_target_guard_rejects_underlying_tmp_when_mount_is_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(music_ingest.os.path, "ismount", lambda path: False)
    with pytest.raises(RuntimeError, match="pCloud mount is not active"):
        music_ingest.ensure_pcloud_target_available(Path("/tmp/dib/media-library/audio/music/x.mp3"))
    # Custom/local destinations remain usable for tests and explicit staging.
    music_ingest.ensure_pcloud_target_available(Path("/var/tmp/properpcloud-test/x.mp3"))


def test_wav_ingest_uses_staging_and_is_resumable(tmp_path: Path) -> None:
    source = tmp_path / "source" / "Artist" / "(2022) Album" / "03 - Track.wav"
    make_wav(source)
    library = tmp_path / "library"
    metadata = tmp_path / "metadata"
    state = tmp_path / "state.sqlite"
    staging = tmp_path / "staging"

    rc = music_ingest.main(
        [
            "ingest",
            "--source-root",
            str(tmp_path / "source"),
            "--library-root",
            str(library),
            "--metadata-root",
            str(metadata),
            "--state-db",
            str(state),
            "--staging-root",
            str(staging),
        ]
    )
    assert rc == 0
    destination = library / "Artist" / "(2022) Album" / "03 Track.wav"
    assert destination.is_file()
    # The tag writer must keep RIFF/WAVE structurally readable while adding an
    # in-container ID3 chunk rather than treating the file as a raw MP3 tag.
    with wave.open(str(destination), "rb") as audio:
        assert audio.getframerate() == 8_000
    assert (metadata / "quality-audit.json").is_file()
    assert (metadata / "ingest-report.md").is_file()
    assert list(staging.glob("*")) == []

    con = sqlite3.connect(state)
    first = con.execute("SELECT status, destination_path FROM ingest_files").fetchone()
    duration = con.execute("SELECT duration_seconds FROM ingest_files").fetchone()[0]
    con.close()
    assert first == ("INGESTED", str(destination))
    assert duration is not None
    assert 0.09 <= duration <= 0.11

    # A second run should observe unchanged source size/mtime and not create a
    # second destination.
    assert music_ingest.main(
        [
            "ingest",
            "--source-root",
            str(tmp_path / "source"),
            "--library-root",
            str(library),
            "--metadata-root",
            str(metadata),
            "--state-db",
            str(state),
            "--staging-root",
            str(staging),
        ]
    ) == 0
    assert list(library.rglob("*.wav")) == [destination]


def test_duplicate_resume_reprocesses_when_referenced_destination_is_missing(tmp_path: Path) -> None:
    source = tmp_path / "source.wav"
    make_wav(source)
    state = music_ingest.initialize_state(tmp_path / "state.sqlite")
    stat = source.stat()
    state.execute(
        """
        INSERT INTO ingest_files(
          source_path, source_size, source_mtime_ns, status, destination_path, updated_at
        ) VALUES (?, ?, ?, 'DUPLICATE', ?, ?)
        """,
        (str(source), stat.st_size, stat.st_mtime_ns, str(tmp_path / "missing.wav"), music_ingest.utc_now()),
    )
    state.commit()
    assert music_ingest.is_resumable_skip(state, source) is False
    state.close()


def test_record_failure_survives_source_disappearing_before_stat(tmp_path: Path) -> None:
    state = music_ingest.initialize_state(tmp_path / "state.sqlite")
    missing = tmp_path / "gone.mp3"
    result = music_ingest.record_failure(state, missing, OSError("vanished"))
    assert result.status == "FAILED"
    row = state.execute(
        "SELECT source_size, source_mtime_ns, status, error FROM ingest_files WHERE source_path=?",
        (str(missing),),
    ).fetchone()
    state.close()
    assert tuple(row)[:3] == (0, 0, "FAILED")
    assert "vanished" in row[3]


def test_reports_include_replacement_candidate(tmp_path: Path) -> None:
    state = tmp_path / "state.sqlite"
    con = music_ingest.initialize_state(state)
    identity = "same-track"
    low = tmp_path / "low.mp3"
    high = tmp_path / "high.mp3"
    low.write_bytes(b"low")
    high.write_bytes(b"high")
    tags = json.dumps({"artist": "A", "album": "B", "title": "C"})
    for path, tier, bitrate in [(low, "LOW_QUALITY", 128), (high, "HIGH", 320)]:
        con.execute(
            """
            INSERT INTO ingest_files(
                source_path, source_size, source_mtime_ns, status, destination_path,
                normalized_tags_json, quality_tier, bitrate_kbps, bitrate_mode,
                duration_seconds, track_identity, updated_at
            ) VALUES (?, ?, ?, 'INGESTED', ?, ?, ?, ?, 'CBR', 10.0, ?, ?)
            """,
            (str(path), 3, 1, str(path), tags, tier, bitrate, identity, music_ingest.utc_now()),
        )
    con.commit()

    quality, report, unsorted = music_ingest.write_reports(con, tmp_path / "metadata")
    con.close()
    payload = json.loads(quality.read_text())
    assert payload["low_quality"][0]["replacement_candidate"] == str(high)
    assert "replacement:" in report.read_text()
    assert json.loads(unsorted.read_text())["files"] == []


def test_mp3_probe_uses_actual_generated_audio_when_ffmpeg_is_available(tmp_path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        pytest.skip("ffmpeg unavailable")
    path = tmp_path / "real-128k.mp3"
    subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=color=white:duration=2",
            "-codec:a",
            "libmp3lame",
            "-b:a",
            "128k",
            str(path),
        ],
        check=True,
        timeout=30,
    )
    quality = music_ingest.probe_mp3_quality(path)
    assert quality.tier == "LOW_QUALITY"
    assert quality.bitrate_kbps is not None
    assert 120 <= quality.bitrate_kbps <= 136
    assert quality.duration_seconds is not None
    assert 1.8 <= quality.duration_seconds <= 2.2


def test_mp3_ingest_normalizes_id3_artwork_quality_and_preserves_source(tmp_path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        pytest.skip("ffmpeg unavailable")

    source = tmp_path / "source" / "Compilation" / "01 - Real Artist - Real Title.mp3"
    source.parent.mkdir(parents=True)
    subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=color=white:duration=2",
            "-codec:a",
            "libmp3lame",
            "-b:a",
            "128k",
            str(source),
        ],
        check=True,
        timeout=30,
    )
    tags = music_ingest.ID3(source)
    tags.delall("TIT2")
    tags.delall("TPE1")
    tags.delall("TALB")
    tags.delall("TRCK")
    tags.delall("TDRC")
    tags.delall("TCON")
    tags.add(music_ingest.TIT2(encoding=0, text=["Track 1"]))
    tags.add(music_ingest.TPE1(encoding=0, text=["Unknown Artist"]))
    tags.add(music_ingest.TALB(encoding=0, text=["Compilation"]))
    tags.add(music_ingest.TRCK(encoding=0, text=["1/12"]))
    tags.add(music_ingest.TDRC(encoding=0, text=["2020-01-01"]))
    tags.add(music_ingest.TCON(encoding=0, text=["hiphop"]))
    tags.add(
        music_ingest.APIC(
            encoding=0,
            mime="image/jpeg",
            type=3,
            desc="front",
            data=b"x" * (501 * 1024),
        )
    )
    tags.save(source, v1=2, v2_version=3)
    source_hash = music_ingest.file_sha256(source)
    assert music_ingest._has_id3v1(source) is True

    library = tmp_path / "library"
    metadata = tmp_path / "metadata"
    state = tmp_path / "state.sqlite"
    staging = tmp_path / "staging"
    assert (
        music_ingest.main(
            [
                "ingest",
                "--source-root",
                str(tmp_path / "source"),
                "--library-root",
                str(library),
                "--metadata-root",
                str(metadata),
                "--state-db",
                str(state),
                "--staging-root",
                str(staging),
            ]
        )
        == 0
    )

    destination = library / "Real Artist" / "(2020) Compilation" / "01 Real Title.mp3"
    assert destination.is_file()
    assert music_ingest.file_sha256(source) == source_hash
    assert music_ingest._has_id3v1(source) is True
    assert music_ingest._has_id3v1(destination) is False
    assert not list(library.rglob("*.properpcloud-partial-*"))

    cleaned = music_ingest.ID3(destination)
    assert cleaned.version[1] == 4
    assert str(cleaned["TIT2"]) == "Real Title"
    assert cleaned["TIT2"].encoding == 3
    assert str(cleaned["TPE1"]) == "Real Artist"
    assert cleaned["TPE1"].encoding == 3
    assert str(cleaned["TCON"]) == "Hip-Hop"
    assert cleaned.getall("APIC") == []
    assert str(cleaned[f"TXXX:{music_ingest.QUALITY_KEY}"]) == "LOW_QUALITY"
    backup = json.loads(str(cleaned[f"TXXX:{music_ingest.BACKUP_KEY}"]))
    assert backup["artist"] == "Unknown Artist"
    assert backup["title"] == "Track 1"
    assert backup["genre"] == "hiphop"

    audit = json.loads((metadata / "quality-audit.json").read_text())
    assert audit["tiers"]["LOW_QUALITY"] == 1
    assert audit["low_quality"][0]["path"] == str(destination)
    report = (metadata / "ingest-report.md").read_text()
    assert "Oversized embedded images removed: 1" in report
    assert "LOW_QUALITY: 1" in report


def test_flac_ingest_normalizes_vorbis_comments_artwork_cover_and_duration(tmp_path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        pytest.skip("ffmpeg unavailable")

    source = tmp_path / "source" / "Artist" / "(2021) Album" / "02 - Title.flac"
    source.parent.mkdir(parents=True)
    subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            "-codec:a",
            "flac",
            str(source),
        ],
        check=True,
        timeout=30,
    )
    raw = music_ingest.MutagenFile(source, easy=False)
    assert raw is not None
    raw["title"] = ["Title"]
    raw["artist"] = ["Artist"]
    raw["album"] = ["Album"]
    raw["tracknumber"] = ["2/10"]
    raw["date"] = ["2021"]
    raw["genre"] = ["hiphop"]
    picture = music_ingest.Picture()
    picture.mime = "image/jpeg"
    picture.type = 3
    picture.desc = "front"
    picture.data = b"y" * (501 * 1024)
    raw.add_picture(picture)
    raw.save()
    (source.parent / "cover.jpg").write_bytes(b"jpeg" * 100)
    source_hash = music_ingest.file_sha256(source)

    library = tmp_path / "library"
    metadata = tmp_path / "metadata"
    state = tmp_path / "state.sqlite"
    assert (
        music_ingest.main(
            [
                "ingest",
                "--source-root",
                str(tmp_path / "source"),
                "--library-root",
                str(library),
                "--metadata-root",
                str(metadata),
                "--state-db",
                str(state),
                "--staging-root",
                str(tmp_path / "staging"),
            ]
        )
        == 0
    )

    destination = library / "Artist" / "(2021) Album" / "02 Title.flac"
    assert destination.is_file()
    assert (destination.parent / "cover.jpg").read_bytes() == b"jpeg" * 100
    assert music_ingest.file_sha256(source) == source_hash
    source_raw = music_ingest.MutagenFile(source, easy=False)
    cleaned = music_ingest.MutagenFile(destination, easy=False)
    assert source_raw is not None and cleaned is not None
    assert len(source_raw.pictures) == 1
    assert cleaned.pictures == []
    assert cleaned["genre"] == ["Hip-Hop"]
    assert cleaned["tracknumber"] == ["2"]
    assert music_ingest.BACKUP_KEY in cleaned
    backup = json.loads(cleaned[music_ingest.BACKUP_KEY][0])
    assert backup["genre"] == "hiphop"
    assert backup["track"] == "2/10"

    con = sqlite3.connect(state)
    duration = con.execute("SELECT duration_seconds FROM ingest_files").fetchone()[0]
    con.close()
    assert duration is not None
    assert 0.9 <= duration <= 1.1
    report = (metadata / "ingest-report.md").read_text()
    assert "Oversized embedded images removed: 1" in report


def test_normalization_repairs_placeholders_without_destroying_real_tags(tmp_path: Path) -> None:
    source = tmp_path / "Various" / "Hits" / "07 - Real Artist - Real Title.mp3"
    repaired = music_ingest.normalized_metadata(
        music_ingest.TrackMetadata(
            title="Track 7",
            artist="Various Artists",
            album="Hits",
            track="7/20",
        ),
        source,
    )
    assert repaired.title == "Real Title"
    assert repaired.artist == "Real Artist"
    assert repaired.album_artist == "Various Artists"
    assert repaired.compilation is True
    assert repaired.track == "7"


def test_path_inference_does_not_promote_mount_or_music_directories() -> None:
    inferred = music_ingest.infer_from_path(Path("/mnt/sda1/Music/03 - Mystery.mp3"))
    assert inferred.title == "Mystery"
    assert inferred.track == "3"
    assert inferred.artist == ""
    assert inferred.album == ""


def test_unsorted_manifest_preserves_source_context(tmp_path: Path) -> None:
    source = tmp_path / "raw" / "mystery.mp3"
    source.parent.mkdir(parents=True)
    source.write_bytes(b"x")
    destination = tmp_path / "library" / "Unsorted" / "mystery [abc].mp3"
    con = music_ingest.initialize_state(tmp_path / "state.sqlite")
    con.execute(
        """
        INSERT INTO ingest_files(
          source_path, source_size, source_mtime_ns, status, destination_path,
          original_tags_json, normalized_tags_json, updated_at
        ) VALUES (?, 1, 1, 'INGESTED', ?, '{}', '{"title":"mystery"}', ?)
        """,
        (str(source), str(destination), music_ingest.utc_now()),
    )
    con.commit()
    _, _, manifest = music_ingest.write_reports(con, tmp_path / "metadata")
    con.close()
    item = json.loads(manifest.read_text())["files"][0]
    assert item["source_path"] == str(source)
    assert item["original_directory"] == str(source.parent)
