# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Release workflow tag validation now runs on the GitHub host where Git is
  available, and manual dispatch can rebuild an existing immutable tag.

### Planned

- Native Linux desktop feature parity through Compose Multiplatform, mpv JSON IPC,
  SQLite, Secret Service/KWallet, MPRIS, media keys, and XDG integration for `0.2.0`.
- Verified offline pinning, saved roots, long-form controls, and Android Auto after
  cross-platform queue/progress semantics stabilize.

## [0.1.0] - 2026-08-02

### Added

- Adaptive Material 3 Android application with compact bottom navigation and an
  expanded navigation rail/list-detail layout.
- Folder-first library browser with stable-ID breadcrumbs, deterministic sorting,
  refresh/loading/error/empty states, and raw metadata/identity inspection.
- Play, replace, play-next, append, direct-folder, and recursive-subtree queue
  operations with cancellation, partial-result reporting, duplicate collapse,
  move/remove/select/clear controls, and containing-folder navigation.
- Media3 background playback, mini-player, now-playing controls, seeking, system
  media integration, stable media IDs, just-in-time stream resolution, and one
  bounded link refresh for eligible HTTP 401/403 expiry responses.
- Durable DataStore queue/progress snapshots, completion policy, and smart rewind.
- Official pCloud Android OAuth flow with user-supplied client ID, documented US/EU
  API-host validation, encrypted Android Keystore token storage, and disconnect.
- Deterministic built-in demo library with locally generated PCM/WAV media so the
  complete browse/queue/playback experience is useful and testable without an
  account, credentials, network, or private fixtures.
- Dynamic system color plus a custom badger/cloud project identity.
- In-app privacy, version, license, and dependency-notice summary.
- Native Linux `0.2.0` parity specification covering shared Kotlin contracts,
  Compose Desktop, mpv, SQLite, Secret Service/KWallet, MPRIS, XDG, and packaging.
- Tag-driven GitHub release workflow with checksums, release evidence, artifact
  attestation, and explicit signing/live-provider validation status.
- Repository health files including contributing/security/conduct policies,
  CODEOWNERS, Dependabot, pull-request template, citation metadata, and issue routing.

### Changed

- Upgraded the pinned build image to Eclipse Temurin JDK 21.
- Compiled against Android API 37 while deliberately targeting stable API 36 until
  the protected Android 17 compatibility matrix is complete.
- Upgraded to current stable Compose, AndroidX, DataStore, coroutines, Media3, and
  pCloud SDK dependencies selected for this release.
- Made Robolectric's Android 16 runtime an explicit checksum-verified offline test
  fixture instead of an implicit network side effect.
- Refined the release roadmap: `0.2.0` is exclusively the native Linux parity line;
  offline/long-form and metadata maintenance move to later minor releases.

### Security

- OAuth tokens are encrypted with Android Keystore AES-GCM and excluded from backup.
- Passwords are never accepted by the native pCloud path.
- Signed stream URLs are resolved immediately before playback and never persisted.
- Only documented `api.pcloud.com` and `eapi.pcloud.com` hosts are accepted.
- Cleartext traffic, app-data backup, analytics, and a mandatory project backend are
  disabled or absent.

### Testing

- Added queue reducer, recursive traversal, cancellation/partial-result, identity,
  progress, smart-rewind, pCloud-session, demo-source, WAV, DataStore round-trip,
  and Robolectric Compose navigation/rendering tests.
- Android lint passes with zero errors and zero warnings; deliberate target/Kotlin
  policy notices remain informational.
- The Docker-backed release gate validates SemVer/changelog/license agreement,
  specification traceability, all module tests, lint, and APK assembly.

### Known limitations

- Public CI validates the deterministic demo source and provider contracts, but live
  pCloud OAuth, account-region, large-folder, codec, and expiring-link behavior still
  require the documented maintainer sandbox checklist because no credentials are
  embedded or available in public automation.
- The attached APK is an installable debug-signed demonstration build. Production
  signing keys remain an external maintainer boundary.
- Full localization extraction, physical-device TalkBack review, Android 17 runtime
  validation, offline pinning, saved roots, Android Auto, and metadata mutation are
  tracked as explicit follow-up gates rather than silently claimed complete.

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

[Unreleased]: https://github.com/fkr-0/properpcloud/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/fkr-0/properpcloud/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/fkr-0/properpcloud/releases/tag/v0.0.1
