from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from release_graph import validate_release_graph  # noqa: E402


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseGraphTest(unittest.TestCase):
    def make_dist(self, root: Path) -> tuple[Path, str, str]:
        version = "0.1.9"
        commit = "a" * 40
        dist = root / "dist"
        third_party = dist / "third-party"
        third_party.mkdir(parents=True)
        files = {
            "android_apk": dist / f"properpcloud-{version}-demo-debug.apk",
            "linux_appimage": dist / f"properpcloud-{version}-x86_64.AppImage",
            "linux_flatpak": dist / f"properpcloud-{version}-x86_64.flatpak",
            "third_party_sources": third_party / "jaudiotagger-3.0.1-sources.jar",
        }
        for index, path in enumerate(files.values(), 1):
            path.write_bytes(f"artifact-{index}".encode())
        evidence = {
            "version": version,
            "tag": f"v{version}",
            "commit": commit,
            "artifacts": {
                kind: {
                    "path": path.relative_to(dist).as_posix(),
                    "size_bytes": path.stat().st_size,
                    "sha256": digest(path),
                }
                for kind, path in files.items()
                if kind != "third_party_sources"
            },
            "third_party_sources": {
                "path": files["third_party_sources"].relative_to(dist).as_posix(),
                "size_bytes": files["third_party_sources"].stat().st_size,
                "sha256": digest(files["third_party_sources"]),
            },
            "authentication": {"password_persisted": False},
        }
        (dist / "release-evidence.json").write_text(json.dumps(evidence), encoding="utf-8")
        (dist / "RELEASE_NOTES.md").write_text(f"## [{version}]\n", encoding="utf-8")
        (dist / "SHA256SUMS").write_text(
            "".join(
                f"{digest(path)}  {path.relative_to(dist).as_posix()}\n"
                for path in files.values()
            ),
            encoding="utf-8",
        )
        return dist, version, commit

    def test_accepts_complete_exact_graph(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-release-graph-") as raw:
            dist, version, commit = self.make_dist(Path(raw))
            self.assertEqual([], validate_release_graph(dist, version, commit))

    def test_rejects_wrong_commit_and_unchecksummed_artifact(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-release-graph-") as raw:
            dist, version, commit = self.make_dist(Path(raw))
            with (dist / "SHA256SUMS").open("a", encoding="utf-8") as stream:
                stream.write(f"{'0' * 64}  unexpected.bin\n")
            errors = validate_release_graph(dist, version, "b" * 40)
            self.assertTrue(any("commit" in error for error in errors))
            self.assertTrue(any("checksum graph" in error for error in errors))

    def test_rejects_secret_field(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-release-graph-") as raw:
            dist, version, commit = self.make_dist(Path(raw))
            evidence_path = dist / "release-evidence.json"
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["client_secret"] = "must-not-ship"
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            self.assertTrue(any("forbidden" in error for error in validate_release_graph(dist, version, commit)))

    def test_rejects_symlinked_artifact_parent(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-release-graph-") as raw:
            root = Path(raw)
            dist, version, commit = self.make_dist(root)
            third_party = dist / "third-party"
            outside = root / "outside-third-party"
            third_party.rename(outside)
            third_party.symlink_to(outside, target_is_directory=True)

            errors = validate_release_graph(dist, version, commit)

            self.assertTrue(any("symlinked parent" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
