# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- Production pCloud OAuth, folder browsing, queue management, playback, progress,
  diagnostics, and adaptive Compose UI for `0.1.0`.
- A native Linux desktop client with Android feature parity for `0.2.0`.

## [0.0.1] - 2026-08-02

### Added

- Specification-first product, architecture, UX, testing, build, and release contracts.
- Docker-pinned Android SDK and Gradle build with checksum-verified toolchain inputs.
- Source-neutral folder, track, sorting, queue, inspection, and stream-resolution contracts.
- Native pCloud Java SDK adapter and WebDAV endpoint model.
- Media3 background playback service bootstrap.
- GitHub Actions verification for specifications, tests, lint, and debug APK assembly.
- Reproducible build evidence and Linux client architecture.

### Changed

- Adopted Semantic Versioning with `VERSION` as the canonical version source.
- Licensed original project code under MIT; dependencies retain their own licenses.

### Known limitations

- This release is an architectural bootstrap, not yet an end-user pCloud player.
- Live pCloud OAuth, folder UI, persisted queue/progress, and production playback flows
  are intentionally scheduled for `0.1.0`.

[Unreleased]: https://github.com/fkr-0/properpcloud/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/fkr-0/properpcloud/releases/tag/v0.0.1
