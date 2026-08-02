# properpcloud roadmap

The roadmap is release-oriented. A feature is complete only when its behavior,
failure states, accessibility, persistence, tests, and release evidence are all
complete. Dates are intentionally omitted; quality gates, not calendar pressure,
determine publication.

## `0.0.1` — validated architecture bootstrap

Status: **released, tagged, and published on GitHub**.

Completed:

- Semantic Versioning and immutable annotated tags;
- MIT project license plus bundled Apache-2.0 dependency notices;
- checksum-verified Docker/Gradle toolchain;
- source-neutral folder, queue, progress, inspection, and stream contracts;
- pCloud Java SDK and WebDAV adapter boundaries;
- Media3 service bootstrap;
- normative product, UX, test, build, release, and Linux specifications;
- green specification, unit-test, lint, and debug-APK gate.

The `v0.0.1` tag is the immutable baseline and must never be moved.

## `0.1.0` — first validated Android client

Status: **released, tagged, independently audited, attested, and published**.

### Product promise

`0.1.0` is a usable Android application even without provider credentials. It
ships a deterministic, locally generated demo library so every browser, queue,
playback, persistence, and responsive-UI flow can be validated in CI and by a
reviewer. Users who provide their own pCloud application client ID can authorize
through pCloud's trusted surface and use the same folder-first workflow against
their account.

### Feature-complete scope

#### Identity and source handling

- [x] Folder/file identity remains source ID plus stable node ID.
- [x] Built-in deterministic demo source requires no network or credentials.
- [x] Native pCloud source uses the official Java SDK.
- [x] OAuth uses the official Android authorization activity.
- [x] Access tokens are encrypted with Android Keystore AES-GCM.
- [x] Only documented US/EU API hosts are accepted.
- [x] Passwords, signed stream URLs, and tokens are never persisted or logged.
- [x] Disconnect removes the local encrypted session immediately.

#### Folder-first library UX

- [x] Compact bottom navigation and expanded navigation rail.
- [x] Folder breadcrumbs based on stable IDs.
- [x] Folder and track rows preserve filename context.
- [x] Natural filename, disc/track, tagged-title, and modified-time sorting.
- [x] Loading, refresh, empty, error, demo, connected, and partial-result states.
- [x] Metadata/identity inspection with secrets redacted.
- [x] One-action navigation from the player to the containing folder.
- [ ] Add containing-folder and metadata-inspection actions directly to every queue row.
- [x] Dynamic system color plus a custom badger/cloud visual identity.

#### Queue semantics

- [x] Play/replace, play-next, and append for tracks.
- [x] Direct-folder and recursive-subtree queue construction.
- [x] Atomic queue replacement; empty or cancelled scans preserve the old queue.
- [x] Stable-ID duplicate collapse and deterministic ordering.
- [x] Partial traversal records readable omissions rather than silently skipping.
- [x] Select, remove, move-up, move-down, and clear operations.
- [x] Queue order and selected item survive process death.

#### Playback and progress

- [x] Media3 `MediaSessionService` and system media controls.
- [x] Stable media IDs; direct links resolved immediately before playback.
- [x] One bounded link refresh on eligible HTTP 401/403 expiry responses.
- [x] Play/pause, previous/next, ±15/30-second seek, timeline, and mini-player.
- [x] Progress checkpoints and revision-ready progress identity.
- [x] Smart rewind after interruptions and completion threshold policy.
- [x] Generated PCM/WAV demo media exercises real ExoPlayer decoding.

#### Privacy, accessibility, and distribution

- [x] No analytics, mandatory backend, or cleartext network traffic.
- [x] Tokens and app data excluded from Android backup/device transfer.
- [x] Content descriptions, deterministic TalkBack order, and non-color state text.
- [x] Keyboard/non-drag alternatives for queue reorder.
- [x] In-app version, license, dependency notice, and privacy summary.
- [ ] Complete string-resource extraction and localization-ready formatting.
- [ ] Automated large-font and compact/expanded screenshot review.
- [ ] Manual TalkBack pass on a physical or virtual device.

### Automated release gates

Every item below is release-blocking:

```yaml
release_gates:
  metadata:
    - make release-check
    - VERSION == 0.1.0
    - CHANGELOG contains dated 0.1.0 section
    - tag v0.1.0 resolves exactly to release commit
  static:
    - duplicate-key-safe YAML validation
    - git diff --check
    - Android lint has no error or warning findings
  tests:
    - pure queue/progress/sort/identity unit tests
    - pCloud session/host validation tests
    - deterministic demo-source and WAV tests
    - DataStore queue/progress round-trip tests
    - Robolectric Compose compact and expanded navigation tests
    - process-death reconstruction test with stable queue references
  build:
    - pinned Docker image
    - Eclipse Temurin JDK 21 for Android 16 Robolectric compatibility
    - compile SDK 37, target SDK 36
    - debug/demo APK assembly
    - release APK assembly with external signing boundary documented
  evidence:
    - APK SHA-256
    - toolchain image ID
    - JUnit and lint summary
    - third-party notices present in source and APK
```

### External validation gate

No CI credential is embedded. Before describing the pCloud path as live-validated,
a maintainer must run the protected sandbox checklist with a registered pCloud
application and disposable test account:

1. authorize US and EU regional accounts;
2. browse at least three nested folders and a folder above 500 entries;
3. play and seek an MP3, M4A/M4B, FLAC, Ogg/Opus, and WAV where account data permits;
4. invalidate or age a direct link and verify bounded renewal at the same position;
5. deny, cancel, disconnect, revoke, and restore authorization;
6. kill the process during browse, queue construction, and playback;
7. verify no token or signed URL in logs, backups, reports, or persisted state.

Until this checklist has evidence, release notes must say **implementation
validated with deterministic fakes; live pCloud account validation outstanding**.

### `0.1.x` post-release hardening

Independent read-only UI/UX and release-engineering audits found no blocker for
`0.1.0`, but identified the following concrete patch-line work before Android
semantics are considered frozen for Linux parity:

- add direct `MainViewModel` orchestration tests with a fake playback controller;
- flush playback progress explicitly on app background and service teardown;
- allow a later retry when a transient failure prevents the one bounded signed-link refresh;
- add containing-folder and metadata-inspection actions to each queue row;
- surface controller-connection and stale-queue-restoration failures to the user;
- convert `core-model` from an Android library to a pure JVM or multiplatform module;
- verify the committed Gradle wrapper JAR checksum in the release gate;
- pin third-party GitHub Actions by immutable commit SHA and let Dependabot update them.

These are release-quality hardening items, not retroactive changes to the immutable
`v0.1.0` tag. Product behavior changes ship under a new SemVer tag.

## `0.1.1` — release-pipeline integrity patch

Status: **released, tagged, validated, and published**.

- rebuild and publication are bound to one explicit immutable tag;
- all third-party GitHub Actions are pinned to reviewed commit SHAs;
- the committed Gradle Wrapper JAR has a separately reviewed SHA-256 gate;
- release validation rejects floating actions, wrapper drift, and missing release evidence;
- no user-facing playback or library behavior changes.

## `0.1.2` — modern player and metadata foundations

Status: **release candidate**.

- dedicated seekable now-playing destination while preserving folder-first identity;
- compact queue actions and grouped metadata inspection;
- canonical tag snapshots, provenance, confidence, patches, and batch edit plans;
- real jaudiotagger-backed local inspection and copy-on-write staged mutation;
- SHA-256 source guard plus staged reread and field verification;
- identified, HTTPS, serialized MusicBrainz search with secure XML parsing;
- Cover Art Archive and AcoustID/Chromaprint contracts without embedded keys;
- comprehensive UX and guarded remote metadata-maintenance specifications.

Remote pCloud file replacement is not enabled in this release. It remains gated
on exact-revision download, conditional replace, provider readback, audit, and
indeterminate-state reconciliation.

### Deferred beyond `0.1.0`

- verified offline file pinning and storage quotas;
- saved-root tabs and whitelist/blacklist policy editor;
- in-app metadata proposal/editor workflow and guarded pCloud replacement;
- Android Auto browse hierarchy;
- bookmarks, sleep timer, variable speed policy, and aggregate book progress;
- cross-device progress synchronization.

## `0.2.0` — native Linux desktop parity

Status: **specified; implementation starts after Android `0.1.x` semantics are stable**.

`0.2.0` is not an Android feature bucket. It delivers a native Linux desktop
client with parity for the complete `0.1.0` semantic contract.

### Reuse boundary

Shared without Android dependencies:

- source/node identity and folder model;
- sorting, queue reducer, recursive assembler, omission model;
- progress, completion, and smart-rewind policy;
- pCloud `java-core` adapter and source contract tests;
- serialized queue/progress records and migration fixtures;
- redaction, error taxonomy, and inspection records.

Native Linux adapters:

- Compose Multiplatform Desktop UI;
- system-browser OAuth and Secret Service/KWallet storage;
- SQLite persistence under XDG paths;
- mpv JSON IPC playback with expiring-link renewal;
- MPRIS, media keys, notifications, and desktop file integration.

### Desktop product gates

```yaml
0_2_0_parity:
  library:
    - folder tree, breadcrumbs, search scope, and raw filename context
    - same sort behavior and containing-folder navigation as Android
  queue:
    - same reducer fixtures and queue snapshot format
    - mouse, keyboard, and context-menu operations
  playback:
    - mpv process supervision
    - MPRIS controls and position
    - crash/restart resumes from durable queue and progress
  security:
    - token only in Secret Service/KWallet
    - private IPC socket under XDG_RUNTIME_DIR
    - no signed URL in command history, logs, or playlist files
  accessibility:
    - full keyboard operation
    - no drag-only action
    - semantic accessibility bridge and high-contrast review
  packaging:
    - Gradle distribution and Arch package
    - Flatpak with browser/secret-service/mpv portal review
  compatibility:
    - GNOME, KDE Plasma, and i3 validation
```

The detailed architecture is in `spec/linux-client.yml` and
`docs/linux-client.md`.

## `0.3.0` — durable offline and long-form power features

- verified file/folder/subtree pinning and cache accounting;
- saved roots, custom tabs, whitelist/blacklist rules;
- variable speed, sleep timer, bookmarks, and aggregate progress;
- Android Auto and richer external media browsing;
- optional local/FUSE source.

## `0.4.0` — transparent metadata intelligence

- embedded metadata parsing with raw/normalized/effective views;
- filename/path inference and explicit external-provider consent;
- candidate confidence/provenance and dry-run field diffs;
- no remote writes.

## `0.5.0` — revision-safe metadata maintenance

- staged edits against an expected revision/hash;
- decode and tag validation before upload;
- post-upload readback verification;
- conflict handling, audit trail, and recovery revision.

## `1.0.0` — stable cross-platform contract

`1.0.0` requires stable migrations, documented public contracts, Android and
Linux release lines, complete privacy/security review, reproducible signed
artifacts, and compatibility guarantees for queue/progress/source records.
