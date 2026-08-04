# Versioning and release policy

properpcloud follows Semantic Versioning 2.0.0.

## Current published release

The latest stable release is **[properpcloud {{LATEST_RELEASE_TAG}}]({{LATEST_RELEASE_URL}})**, published {{LATEST_RELEASE_DATE}}. The documentation build derives this value from the first dated release section in the canonical `CHANGELOG.md`, while development builds continue to read their working version from `VERSION`.

Every published release groups the Android APK, AppImage, Flatpak bundle, checksums, and machine-readable evidence on one immutable GitHub release page. The site header links to GitHub's `/releases/latest` route so it remains a stable discovery point.

## Canonical version

`VERSION` is the only manually edited version source. It contains one normal
Semantic Version such as `0.1.0` or a valid pre-release such as `0.1.0-rc.1`.

The Android build reads this value directly:

- `versionName` is the complete value from `VERSION`;
- `versionCode` is `major × 1,000,000 + minor × 1,000 + patch`;
- pre-release labels do not alter `versionCode`, so only one public artifact
  with a given core version may be uploaded to an Android distribution channel.

## Public API before 1.0

The public API consists of:

1. source-neutral Kotlin contracts under `core-model`;
2. documented provider adapter behavior;
3. persisted database and settings schemas once introduced;
4. user-visible queue, progress, and folder-navigation semantics;
5. release CLI/Make targets documented in the repository.

Before `1.0.0`, minor releases may refine these APIs incompatibly when the
change is clearly documented. Patch releases remain backward compatible.

## Version intent

| Version range | Meaning |
|---|---|
| `0.0.x` | Architecture, contracts, and development bootstrap |
| `0.1.x` | Validated Android client; first end-user release line |
| `0.2.x` | Native Linux desktop client with Android feature parity |
| `1.0.0` | Stable cross-platform contracts and migration guarantees |

## Release sequence

1. Update `VERSION` and move entries from `Unreleased` into a dated section.
2. Run `make release-check` and `make ci` in the pinned Docker toolchain.
3. Build the release artifact and create release evidence with hashes.
4. Commit the exact release tree.
5. Create an annotated `vX.Y.Z` tag at that commit.
6. Push commit and tag.
7. Create the GitHub release from the tag and attach verified artifacts.

Tags and published release assets are immutable. A correction requires a new
patch version rather than moving an existing tag.
