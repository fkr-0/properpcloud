# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The first native Linux `0.2.0` foundation boundary: `core-model` is now a pure
  Kotlin/JVM module that emits JVM 17-compatible bytecode and a reusable JAR.
- A native Compose Desktop client with folder browsing, deterministic queues, SQLite
  state, mpv JSON IPC playback, Secret Service sessions, MPRIS, and XDG paths.
- An Astro Starlight documentation site and a dedicated GitHub Linux integration
  workflow covering the desktop application image, real mpv/SQLite, and packaged MPRIS.
- Checksum-pinned x86_64 AppImage packaging and a Freedesktop 25.08 single-file
  Flatpak bundle, both produced and smoke-tested in CI and attached to tagged releases.

### Changed

- Android, metadata, pCloud, and WebDAV modules now consume the same JVM core artifact
  intended for the desktop client instead of an Android Library artifact.
- The container merge gate now builds the desktop application image, while Android and
  release workflows pin Node.js 24 for reproducible documentation verification.
- Direct-login failures now distinguish ambiguous credential/region rejection, rate
  limiting, and provider failures. The desktop form stays open for correction and can
  reveal the entered password before submission.
- Pages documentation now states the required repository publishing-source and custom-
  domain settings instead of treating an artifact `CNAME` as authoritative.
- Tagged releases now aggregate Android, AppImage, and Flatpak jobs into one checksum
  manifest, provenance record, workflow artifact, and GitHub release.

### Planned

- Desktop release hardening, broad-distribution packages, protected live-provider tests,
  keyring matrix validation, and accessibility review for `0.2.0`.
- Verified offline pinning, saved roots, long-form controls, and Android Auto after
  cross-platform queue/progress semantics stabilize.

## [0.1.6] - 2026-08-02

### Added

- A clearly labelled interim direct pCloud sign-in path implementing the provider's
  documented HTTPS `userinfo` authentication with explicit Europe/United States choice.
- Token-kind-aware session persistence and SDK authentication for OAuth bearer tokens
  and direct-login `auth` tokens.
- A safer account-settings layout that separates recommended OAuth, interim direct
  sign-in, and advanced developer configuration.
- The supplied raster properpcloud logo in README and in-app About branding.

### Changed

- Tagged releases no longer require a pCloud client ID while the developer console is
  unavailable; a configured ID is still validated and immediately enables preferred OAuth.
- Legacy-auth SDK reads move all method parameters and the `auth` token from URL queries
  into HTTPS form POST bodies.
- Disconnect invalidates OAuth and legacy tokens with their respective documented
  transport conventions while preserving local-first removal.

### Security

- Direct-login passwords are held only in short-lived UI/request state, removed from the
  form before the request starts, never persisted/logged/exported/backed up, and sent only
  to the explicitly selected allowlisted regional pCloud API over HTTPS POST.
- Direct authentication disables redirects, bounds response size and time, avoids
  cross-region credential probing, and retains only numeric provider rejection codes.
- Direct-login tokens request a 90-day absolute lifetime and 30-day inactivity lifetime
  rather than the provider's longest possible lifetime.

### Testing

- Added direct-login result, buffer-clearing, network/redaction, legacy SDK request
  transformation, regional-host rejection, and Compose account-settings coverage.

### Known limitations

- pCloud's public direct-login documentation does not describe a two-factor challenge;
  affected accounts may require OAuth once application registration becomes available.
- Live direct-login and OAuth validation require a disposable provider account and remain
  outside public CI; the release is an evaluation build signed with an Android debug key.

## [0.1.5] - 2026-08-02

### Added

- A source-neutral playback-checkpoint policy keyed exclusively by stable source/node
  identity, with coalesced periodic writes and explicit lifecycle flushes.
- Durable progress checkpoints on app background, ViewModel teardown, queue replacement,
  manual item transitions, disconnect, playback errors, task removal, and service teardown.
- A bounded signed-link retry gate that permits one immediate refresh and a later retry
  after cooldown instead of permanently exhausting a track for the process lifetime.
- Direct `MainViewModel` orchestration tests with an injected playback-controller port.
- Frozen `0.1.5` queue/progress JSON fixtures that Android reproduces byte-for-byte and
  Linux `0.2.0` must replay before claiming semantic parity.

### Changed

- Playback-controller connection failures and stale persisted queues are surfaced as
  actionable UI messages instead of silently degrading.
- Queue restoration removes unavailable entries, persists the repaired queue, and reports
  partial or complete restoration failure.
- Persistence serialization is centralized in a tested codec that stores stable identity,
  timing, completion, and speed—never signed stream capabilities.

### Security

- Controller connection failures use a fixed redacted message rather than arbitrary
  exception text that could contain transport details.
- Progress and compatibility fixtures exclude tokens, signed URLs, local paths, and
  provider response bodies.

### Testing

- Added progress-threshold/force/completion tests, signed-link retry cooldown tests,
  exact persistence fixture tests, and controller/progress/stale-queue orchestration tests.

### Known limitations

- Abrupt kernel/process termination can still lose the final sub-checkpoint interval when
  Android invokes neither Activity nor service lifecycle callbacks.
- Physical-device process-death, TalkBack, 200% font, codec, headset/media-key, and
  Android 17 validation remain external release gates.

## [0.1.4] - 2026-08-02

### Added

- Release-time injection of properpcloud's public pCloud application client ID, enabling
  an ordinary one-tap **Sign in to pCloud** flow without asking users to create an app.
- Advanced developer override for personal/test pCloud applications, including the exact
  package-derived redirect URI and a direct link to pCloud's developer site.
- Typed provider-side token revocation using the account's regional pCloud API host.

### Changed

- Settings now explain the application-ID/token distinction: users authenticate only on
  pCloud's official surface and the approved access token returns directly to the app.
- User-facing tagged releases fail closed when the public `PCLOUD_CLIENT_ID` repository
  variable has not been configured; ordinary source builds remain usable with a custom ID.
- Disconnect removes the encrypted local session and provider source immediately, then
  clears queues containing pCloud media and reports remote invalidation as confirmed,
  already inactive, or unconfirmed.

### Security

- properpcloud still never collects a pCloud account password or asks users to copy an
  access token. The client ID is public application metadata, not a client secret.
- Remote logout sends the OAuth bearer token in the HTTPS Authorization header rather
  than a URL, rejects redirects and unknown regional hosts, bounds response size/time,
  and never surfaces provider response bodies or credential-bearing exceptions.
- Local disconnect succeeds independently of network availability; remote-revocation
  failure cannot restore or retain the local credential handle.

### Testing

- Added bundled/custom/missing OAuth configuration tests, redirect-URI tests, local-first
  registry disconnect coverage, and typed revocation success/inactive/failure tests.

### Known limitations

- The maintainer must register properpcloud once in pCloud's developer console, enable
  implicit grant, register `pcloud-oauth://dev.properpcloud.app`, and set the resulting
  public client ID as the repository variable before publishing `v0.1.4`.
- Live US/EU OAuth and logout validation, production signing, physical-device
  accessibility, and Android 17 runtime validation remain external gates.

## [0.1.3] - 2026-08-02

### Added

- First-class **Tag studio** for editing title, artist, album, album artist, genre,
  year, track/disc values, composer, comments, ISRC, MusicBrainz IDs, and lyrics while
  displaying the original embedded value and provenance beside every draft field.
- Folder, queue, and now-playing entry points for single-file editing plus a bounded
  20-file selection workflow for common-field updates and deterministic track sequencing.
- Explicit MusicBrainz candidate review with confidence, per-field acceptance, and
  disclosure of the textual fields and duration sent to the provider.
- Exact pCloud download-to-staging using provider SHA-256 checksums and matching
  pre/post revision snapshots before any tag work begins.
- Verified single-file exports and multi-file ZIP bundles with SHA-256 evidence and a
  CSV manifest, shared through a narrowly scoped Android `FileProvider` URI.
- Bounded retention for app-private metadata source copies and verified exports.

### Changed

- Metadata batch cancellation now propagates immediately instead of being converted
  into an ordinary per-file failure.
- Empty batch inputs preserve existing values; clearing a field requires an explicit
  clear action.
- Settings now distinguish implemented inspection/edit/export capabilities from the
  deliberately disabled cloud-overwrite boundary.

### Security

- Original local/demo bytes and pCloud objects remain unchanged; jaudiotagger writes
  only to separate app-private candidates and rereads every requested mutation.
- pCloud preparation rejects size/hash mismatches or a source revision change during
  download and removes incomplete local copies.
- The current pCloud SDK exposes ordinary overwrite but no atomic expected-revision
  replacement primitive, so remote metadata overwrite remains unavailable rather than
  introducing a check-then-write race.

### Testing

- Added real demo-WAV editor/export and ZIP-manifest integration tests, metadata draft
  and batch precedence tests, pCloud stable-download/conflict tests, and Tag studio UI tests.

### Known limitations

- Artwork mutation, Android Chromaprint generation, AcoustID configuration UI, and
  atomic remote replacement are not enabled.
- Live pCloud account validation, production signing, physical-device accessibility,
  and Android 17 runtime validation remain external gates.

## [0.1.2] - 2026-08-02

### Added

- Dedicated now-playing destination with large artwork fallback, title and filename
  context, seekable elapsed/remaining timeline, transport controls, queue position,
  up-next preview, and one-tap queue/folder/metadata navigation.
- Canonical metadata domain records for provenance, confidence, tag snapshots,
  `Keep`/`Clear`/`Set` patches, revision-or-hash-guarded edit plans, and deterministic
  common-field, candidate, and track-sequencing batch operations.
- `metadata-tags` module using a replaceable jaudiotagger adapter for real local tag
  inspection, copy-on-write staging, SHA-256 guarding, tag reread, and field verification.
- `metadata-online` module with an identified, HTTPS MusicBrainz recording client,
  serialized request-rate gate, secure XML parser, Cover Art Archive references, and
  opt-in AcoustID/Chromaprint lookup contracts without embedded service keys.
- Comprehensive Android UX modernization and metadata maintenance specifications,
  including the guarded pCloud remote-replacement state machine and audit boundaries.

### Changed

- Mini-player now opens the first-class player and displays thin playback progress.
- Queue rows use one compact overflow menu while retaining move-up/down alternatives,
  containing-folder navigation, metadata inspection, and removal.
- Provider inspection uses a grouped adaptive bottom sheet instead of an oversized
  blocking dialog.
- Settings disclose the exact metadata-tool status and the fact that remote file
  replacement remains disabled until conditional upload and readback are implemented.

### Security

- Local metadata edits never modify source bytes in place; failed candidates are
  removed and intended fields must pass reread verification.
- MusicBrainz XML parsing rejects document types and external entities, and online
  matching remains explicit, rate-limited, provenance-preserving, and non-mutating.
- Added jaudiotagger's LGPL 2.1-or-later notice and complete license text to the
  repository and APK asset set.

### Testing

- Added metadata plan, sequencing, revision/hash guard, real WAV staged-edit,
  MusicBrainz query/parser, rate-gate, and dedicated now-playing Compose tests.

### Known limitations

- The metadata editor UI, Android Chromaprint implementation, artwork writes, and
  expected-revision pCloud replacement are specified but intentionally not enabled.
- Live pCloud account validation, production signing, physical-device accessibility,
  and Android 17 runtime validation remain external release gates.

## [0.1.1] - 2026-08-02

### Fixed

- Manual release dispatch now checks out, validates, rebuilds, attests, and publishes
  the explicitly requested immutable tag rather than operating on the workflow branch.
- GitHub release publication receives the release tag explicitly, fixing the failed
  final publication step seen in the first `v0.1.0` rebuild attempt.

### Security

- Pinned every third-party GitHub Action to a reviewed immutable commit SHA while
  preserving Dependabot-managed version comments.
- Added a committed SHA-256 for the Gradle Wrapper JAR and made wrapper integrity a
  doctor, CI, and release-metadata gate.

### Testing

- Release validation now rejects floating GitHub Action references, missing release
  manifests, malformed wrapper checksums, and wrapper byte drift.
- Release jobs print and verify the exact tag-to-commit target before building.

### Known limitations

- This patch changes release engineering only; the installable APK remains the
  folder-first `0.1.0` product behavior with version metadata advanced to `0.1.1`.

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

[Unreleased]: https://github.com/fkr-0/properpcloud/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/fkr-0/properpcloud/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/fkr-0/properpcloud/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/fkr-0/properpcloud/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/fkr-0/properpcloud/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/fkr-0/properpcloud/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fkr-0/properpcloud/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/fkr-0/properpcloud/releases/tag/v0.0.1
