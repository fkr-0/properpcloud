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

Status: **released, tagged, validated, and published**.

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

## `0.1.3` — reviewed Tag studio and safe export

Status: **local release candidate**.

- in-app single-file editor with original values and provenance;
- bounded 20-file batch selection, common fields, explicit clears, and sequencing;
- field-level MusicBrainz proposal review;
- exact pCloud source preparation with provider SHA-256 and pre/post revision checks;
- reread-verified single audio export or ZIP plus CSV manifest;
- scoped FileProvider sharing and bounded private-file retention;
- no remote overwrite because the current pCloud SDK lacks atomic expected-revision replacement.

## `0.1.4` — ordinary OAuth onboarding and complete disconnect

Status: **implementation complete; application registration and repository variable configured, protected live evidence remains**.

- public application client ID injected into tagged builds for one-tap pCloud sign-in;
- no user-created app, copied token, or pasted ID in the ordinary path;
- explicit personal/developer client-ID override retained under advanced setup;
- tagged releases validate the supplied public application ID; unconfigured source builds retain the fallback direct-login path;
- local credential removal and source detachment happen before network revocation;
- queues containing pCloud media are cleared so an already-resolved stream cannot continue;
- regional `/logout` invalidation uses an HTTPS bearer header and typed safe outcomes.

The application-registration and repository-variable steps are complete. Remaining
external gates are to confirm `pcloud-oauth://dev.properpcloud.app` and implicit
grant in the provider console and complete protected US/EU OAuth and logout tests.
The client secret is not a client build input and must remain outside Android/Linux
binaries and release automation.

## `0.1.5` — Android semantic freeze and lifecycle hardening

Status: **local release candidate**.

- direct `MainViewModel` orchestration tests through an injected playback-controller port;
- explicit progress flush on app background, ViewModel/service teardown, queue switch,
  manual transition, disconnect, playback error, and task removal;
- one immediate signed-link retry plus a later retry after cooldown;
- controller connection/restoration failures represented in UI state instead of silent fallback;
- stale persisted queues repaired and rewritten with explicit user notice;
- Android queue/progress serialization fixtures frozen and byte-replayed as the `0.2.0` corpus.

External gates remain: physical-device process-death during browse, recursive queue
construction, playback, metadata staging, OAuth return and disconnect; TalkBack,
200% font, media keys, headset/codec behavior, and Android 17 runtime validation.

## `0.1.6` — interim direct login and account UX

Status: **released 2026-08-02; direct login is retained as a fallback-only path**.

- preserve OAuth as the preferred login path whenever a registered public client ID exists;
- add pCloud's documented username/password → `auth` token flow as a visibly interim fallback;
- require explicit Europe/United States selection and send credentials to exactly one allowlisted HTTPS host;
- clear password form state before dispatch, never persist/log/export it, and request bounded token lifetimes;
- persist token kind so OAuth bearer and legacy `auth` sessions restore with the correct transport;
- move legacy SDK method parameters and token from URLs to HTTPS form POST bodies;
- perform token-kind-aware provider logout after local-first disconnect;
- allow tagged evaluation releases without `PCLOUD_CLIENT_ID` while continuing to validate any supplied ID;
- replace the broken documentation SVG with the supplied PNG logo and use it in the in-app About surface;
- keep two-factor direct-login support explicitly unclaimed until protected live-account evidence exists.

Protected EU/US direct-login checks and two-factor behavior remain external
provider/device gates. With application credentials now available, the direct path
is retained only as a fallback for OAuth-unavailable cases.

## `0.1.7` — registered OAuth activation and configuration hardening

Status: **released on 2026-08-03 with Android, AppImage, Flatpak, checksums, attestations, and a verified signed tag**.

- load only the public `PCLOUD_CLIENT_ID` from an ignored repository-root `.env`;
- preserve explicit environment/Gradle overrides for CI and developer builds;
- never read or export `PCLOUD_CLIENT_SECRET` from client build tooling;
- exclude `.env*` from Git and Docker build contexts while publishing a safe template;
- keep OAuth ordinary when configured, with direct sign-in collapsed and described
  only as a fallback;
- validate dotenv parsing, duplicate keys, quoting, environment precedence, and
  malformed public IDs with host-side regression tests;
- publish tagged builds with the configured GitHub repository variable;
- retain protected Android EU/US authorization, denial, logout, and token-redaction
  validation as explicit external evidence.

### Deferred beyond `0.1.0`

- verified offline file pinning and storage quotas;
- saved-root tabs and whitelist/blacklist policy editor;
- guarded pCloud replacement only after an atomic provider primitive exists;
- Android Auto browse hierarchy;
- bookmarks, sleep timer, variable speed policy, and aggregate book progress;
- cross-device progress synchronization.

## `0.1.8` — cross-platform restoration and desktop runtime hardening

Status: **verified release candidate; all credential-free repository and Linux gates pass**.

This patch release converts the first Linux implementation from a broad functional
prototype into a fail-safe runtime. Its gates are deliberately credential-free and
must pass in public CI:

1. [x] repair partially stale queues through one shared stable-identity algorithm;
2. [x] preserve the selected surviving track when unavailable entries precede it;
3. [x] rewrite repaired Android and Linux queue snapshots instead of repeatedly
   rediscovering the same stale records;
4. [x] force the latest desktop progress sample on queue mutation, player failure,
   disconnect, and orderly shutdown;
5. [x] correlate mpv JSON IPC replies by request ID, ignore unrelated messages, bound
   response size/time, and expose only redacted command failures;
6. [x] durably tombstone and disconnect pCloud locally before attempting Secret Service
   cleanup and typed remote session revocation;
7. [x] bound Secret Service processes, kill timeouts, validate lookup keys, and clear
   caller-owned credential buffers in `finally`;
8. [x] constrain the Flatpak host-mpv bridge to the exact deterministic playback
   arguments and private IPC path used by properpcloud;
9. [x] complete the full Android, desktop, Linux package, documentation, and release
   verification set and record exact evidence in `docs/releases/0.1.8.yml`.

`0.1.8` may ship without protected provider credentials because it makes no new live
provider compatibility claim. It must not weaken the already-published `0.1.7` OAuth,
artifact, or signing guarantees.

## `0.1.9` — Linux distribution and compatibility hardening

Status: **published and verified on immutable tags; current-host keyring, MPRIS,
200% high-contrast, logind, Arch, and soak evidence now passes. Remaining work is limited
to physical/session observations, exact-tag packaging, and protected-provider validation**.

`0.1.9` closes package and desktop-environment uncertainty before the parity release:

1. [x] forced mpv termination/restart tests preserve durable queue, selected identity,
   and a bounded resume position with exactly zero automatic restart attempts;
2. [x] package filenames, AppStream metadata, checksums, evidence records, release notes,
   secret exclusions, and immutable commit provenance are validated as one release graph;
3. [x] tagged AppImage and Flatpak builds run clean-profile package smokes and publish a
   commit-bound checksum/evidence graph;
4. [x] a shared bounded signed-link policy now drives Android and Linux capability
   refresh, and mpv distinguishes stream failure from EOF, explicit stop, and process exit;
5. [x] keyboard focus, selection, play, append, inspect, move, and remove operations have
   tested non-drag shortcuts with visible focus state and modal shortcut suppression;
6. [x] current-session, Arch clean-build, bounded soak, and strict promotion gates are
   executable commands that retain redacted evidence instead of prose-only checklists;
7. [x] retained current i3 Secret Service/MPRIS evidence, the immutable v0.1.9 Arch
   clean-build/install smoke, and a 120-second credential-free resilience soak;
8. [x] isolated locked-keyring failure is bounded without touching the real collection;
   all MPRIS methods are externally invoked; 200% high-contrast base/help captures pass;
   and logind sleep handling checkpoints, pauses, and refreshes on wake;
9. [ ] complete GNOME/KDE sessions, physical media-key and suspend observations, a real
   AT-SPI screen-reader review, and protected provider validation;
10. [x] browser OAuth remains unclaimed until pCloud confirms a desktop redirect; the
   documented regional direct-sign-in fallback is the explicit current boundary.

The planned evidence schema lives in `docs/releases/0.1.9.yml`. Failed matrix cells
remain explicit and block `0.2.0`; they are never converted into optimistic prose.

## `0.1.10` — current-host hardening and next-workflow specification

Status: **release candidate for the completed credential-free current-host tranche**.

`0.1.10` publishes the hardening that is useful before the protected `0.2.0` promotion:

1. [x] restore Secret Service credentials asynchronously with a hard timeout and cancel
   stale restoration after Demo selection, disconnect, close, or a newer login;
2. [x] validate a deliberately locked private keyring without touching the real collection
   and explicitly terminate the temporary daemon;
3. [x] invoke every supported MPRIS method through the external D-Bus path;
4. [x] expose heading, selected/current row, and player-state semantics plus non-color
   labels and deterministic 200% high-contrast captures;
5. [x] accept logind sleep signals only from the verified owner/path, checkpoint and pause
   synchronously before sleep, and refresh once after wake without auto-restarting mpv;
6. [x] define `spec/tag-folder-workbench.yml` as the guarded next pre-`0.2.0` workflow,
   explicitly without claiming implementation or source mutation in this release.

## `0.1.11` — folder-scoped Tag workbench (planned)

- a dedicated direct-folder table and field inspector preserving filename/path identity;
- gap-free initial scan plus robust watcher/change-feed reconciliation;
- deterministic explainable correction proposals, where autocorrect never means auto-write;
- per-field approval and conflict invalidation when source content changes;
- local sibling staging, reread/decoder verification, atomic replacement, final readback,
  rollback, and export-only fallback when safe replacement is unavailable;
- remote review/export only until an atomic expected-revision provider primitive exists.

## `0.2.0` — native Linux desktop parity

Status: **implementation parity is substantially complete. Promotion is now governed by
`docs/reviews/0.2.0-promotion-matrix.yml`; automated and current-host gates can be closed
locally, while protected EU/US accounts and alternate desktop/visual observations remain
explicit pre-tag blockers**.

Completed implementation:

1. [x] `core-model`, pCloud, WebDAV, metadata-online, and metadata-tags build as plain JVM modules without Android linkage.
2. [x] Compose Desktop three-pane library, inspector, queue, and bottom-player shell.
3. [x] deterministic direct and recursive folder queues with shared sorting/reducer semantics.
4. [x] SQLite settings, queue, and progress persistence under XDG data paths.
5. [x] freedesktop Secret Service session-token storage with no plaintext fallback.
6. [x] mpv child-process supervision over private Unix JSON IPC with `--no-config`.
7. [x] fresh pCloud stream resolution, play/pause/seek/next/previous, and smart resume.
8. [x] MPRIS root/player service and media-state publication.
9. [x] deterministic generated-WAV demo source and real-host mpv/SQLite smoke entry point.
10. [x] Compose Desktop application-image plus `.deb`/`.rpm` packaging configuration.
11. [x] one bounded stream-capability refresh per stable media identity with cooldown,
    redacted failure state, durable resume position, and zero automatic process restarts.
12. [x] keyboard-first library and queue focus with visible selection, complete non-drag
    alternatives, tested shortcut resolution, and shortcut suppression in modal dialogs.
13. [x] executable current-session audit, immutable Arch clean-build/install smoke,
    bounded resilience soak, and fail-closed strict promotion validator.
14. [x] nonblocking bounded locked-keyring restore, externally invoked MPRIS control path,
    explicit Compose accessibility semantics and high-contrast palette, and retained 200%
    visual evidence without color-only selected/current states.
15. [x] logind `PrepareForSleep` handling that force-checkpoints and pauses before sleep,
    refreshes the stream once after wake, and preserves manual recovery after process exit.

Final release sequence after the hardened `0.1.8` through `0.1.10` line:

1. [ ] repeat the Arch gate against the exact immutable `v0.2.0` archive after the tag
   exists; the v0.1.9 clean-build/install/license/smoke baseline already passes;
2. [x] retain the automated i3 audit, isolated locked-keyring evidence, external MPRIS
   controls, 200% high-contrast captures, and packaged logind subscription; [ ] physically
   observe one media-key path and one real suspend/resume cycle;
3. [x] retain the 120-second credential-free soak; [ ] additionally retain a four-hour
   protected-provider soak with genuine capability expiry and one suspend/resume cycle;
4. [ ] complete protected EU and US pCloud browse, playback, expiry, disconnect, cleanup,
   and restart evidence without retaining media, tokens, URLs, or response bodies;
5. [ ] complete GNOME/KDE Plasma sessions and a real AT-SPI screen-reader traversal/action review;
6. [ ] run `python3 scripts/validate-020-readiness.py --pre-tag`, create the signed tag only
   after it passes, then run `--strict` after exact-tag package verification and before
   publication; review every explicit exception without silently relaxing a failed gate.

`0.2.0` is not an Android feature bucket. It delivers a native Linux desktop
client over the shared source-neutral contract. It is a promotion of the hardened
`0.1.8` through `0.1.10` line, not a new implementation dump.

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
