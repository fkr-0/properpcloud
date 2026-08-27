# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0-rc.3] - 2026-08-27

### Added

- Added a native-desktop `--generate-playlists <local-root>` batch CLI that previews by
  default, requires `--write` for materialization, exposes all five deterministic playlist
  orders, and keeps recursive/per-album scope explicit through the same selected-root,
  revision-bound workbench used by the desktop UI.
- Added an explicit `title-number` playlist order. A leading decimal integer in the embedded
  `TITLE` sorts numerically (`01`, `2`, `10` => `1`, `2`, `10`), with deterministic handling
  for ties, non-numeric titles, missing titles, filenames, and stable paths.
- Added typed, revision-bound tag and playlist review projections. Playlist checkpoints expose
  every exact safe `./...` target path plus every final M3U8 line before any playlist bytes are
  created.
- Added a real-WAV integration test covering folder/filename tag inference, explicit approval,
  dry-run preflight, verified jaudiotagger-backed local writes, fresh metadata readback,
  relative extended-M3U generation, and resulting playback queue order.

### Changed

- Native-desktop tag review is now diff-first: the frozen review presents explicit **Earlier**
  and **Later** values and distinguishes ordinary changes, additions from empty values, and
  destructive removals to empty while retaining rule/confidence provenance, warnings, conflicts,
  filename/path identity, and the existing hash/revision/rollback safety boundary.
- A successful tag dry-run and the final replacement confirmation now explicitly reference the
  same frozen Earlier/Later review revision; watcher/reconciliation drift invalidates that review
  rather than refreshing values underneath the user.
- Playlist CLI preview prints the exact prospective playlist contents rather than a count-only
  summary; `--write` remains the separate materialization confirmation and stale membership,
  content, or revision evidence fails closed before the first output byte.
- Clarified that filesystem watcher events invalidate and reconcile reviewed work but do not
  perform unattended playlist writes; bounded post-sync regeneration remains playlist-only and
  can resume only from an explicitly submitted, still-current reviewed batch.
- Updated metadata documentation with the exercised ID3v2.3/v2.4 compatibility boundary,
  modeled writable fields, preservation of unrelated ID3 frames, current native local-root
  support, and the preview-first CLI workflow.

### Known limitations

- This release candidate does not claim stable `0.2.0` promotion. Physical power-cut filesystem
  durability, physical media-key behavior, a real suspend/resume cycle, GNOME and KDE Plasma
  observations, protected Europe/United States pCloud account validation, and the retained
  protected-provider soak remain unresolved stable-promotion gates.
- Linux AT-SPI/screen-reader traversal remains blocked by the current Compose Multiplatform Linux
  accessibility boundary; selected-folder keyboard/focus/large-text review and a supported Linux
  accessibility bridge or explicit maintainer exception remain required for stable promotion.
- The exact immutable `v0.2.0` Arch archive rebuild is still a post-tag stable-release gate and is
  deliberately not represented as completed by this prerelease.

## [0.2.0-rc.2] - 2026-08-23

### Testing

- Added a packaged native-desktop recovery smoke that externally sends `SIGKILL` only after
  the recovery-armed atomic tag replacement completes, then starts a fresh packaged process,
  supplies the selected scratch root again, rediscovers durable recovery authority, and verifies
  exact-hash guarded rollback without retaining private paths, provider URLs, or credentials.
- Revalidated the release candidate with the pinned Docker toolchain, desktop JVM/JUnit suite,
  complete Linux CI smoke set, host specification checks, and fail-closed `0.2.0` readiness gates.

### Changed

- Promotion evidence now distinguishes verified packaged process restart/reselection recovery
  from still-unverified physical power-loss durability.
- Linux screen-reader/AT-SPI promotion status now records the current Compose Multiplatform Linux
  accessibility boundary as an upstream blocker instead of presenting it as an ordinary manual
  check that could be completed on the existing packaged UI.

### Known limitations

- Physical power-cut durability, physical media-key and suspend/resume observation, GNOME/KDE
  session checks, protected EU/US provider accounts and retained provider soak, and the exact
  post-tag `v0.2.0` Arch rebuild remain explicit promotion gates.
- Real Linux AT-SPI/screen-reader traversal is blocked by the current Compose Multiplatform
  Linux accessibility boundary; final promotion requires an explicit documented exception or a
  supported Linux accessibility bridge/UI strategy rather than silently marking that gate passed.

## [0.2.0-rc.1] - 2026-08-22

### Added

- Folder metadata-suite playlist generation now supports deterministic direct-folder and
  explicit subtree `.m3u8` plans with relative paths, natural filename, disc/track tag,
  tagged-title, or modification-time ordering, trusted-duration `EXTINF`, safe tag-derived
  display naming, stale-evidence preflight, and bounded playlist-only post-sync regeneration.
- A shared local-root metadata-suite session adds revision-bound preview/confirmation for tag
  and playlist work, keeps recursive playlist consent independent from recursive tag mutation,
  revokes stale reviews/queued regeneration on reconciliation signals, and requires a fresh
  post-write scan before deriving playlists after confirmed tag changes.
- A neutral local-filesystem workbench host now proves explicit writable-root and atomic-move
  capability, registers a real JVM `WatchService` lease before scanning, invalidates reviews on
  relevant events before debounce, reconciles overflow/invalid observers through full rescans,
  and never turns watcher or post-sync activity into tag writes.
- Native Compose Desktop can now bind an explicitly user-selected local directory to that host as
  a separate filesystem-first `AudioSource`, with opaque stable source/node IDs, direct browsing
  and playback, live/stale reconciliation state, preview/dry-run/confirmation tag controls, and
  independently gated direct or recursive playlist materialization. The selected private root is
  session-scoped rather than persisted, and source switching closes the observer and local queue
  authority.
- A cheap host-side `make local-check` workflow is now the default routine developer gate,
  with optional portable-JVM `make fast-test` when the pinned image is already available,
  while GitHub Actions retains Robolectric, Android lint, APK assembly, docs, and the complete
  `make ci` merge verification.

### Planned

- Complete the remaining folder-scoped Tag workbench release boundary with a truthful Flatpak
  document-portal directory lease/path mapping (without broad host/home access), Android SAF as a
  separate platform adapter, and the remaining conflict/power-loss/rollback plus
  accessibility/platform evidence before claiming the full workbench release-ready.
- `0.2.0` promotion only after the protected EU/US provider, alternate desktop,
  physical media-key/suspend, and real screen-reader gates are complete.
- Verified offline pinning, saved roots, long-form controls, and Android Auto after
  cross-platform queue/progress semantics stabilize.

### Known limitations

- This is a release candidate for hands-on testing, not the final `0.2.0` promotion.
- Physical power-cut durability, real packaged restart/reselection recovery, selected-folder
  screen-reader/focus review, physical media keys and suspend/resume, GNOME/KDE observations,
  and protected EU/US provider soak/account evidence remain explicit final-release blockers.

## [0.1.10] - 2026-08-05

### Added

- Shared Android/Linux signed-link retry policy and desktop stream-failure classification
  that distinguish unexpected playback loss from normal EOF, explicit stop, and process exit.
- Keyboard-first library and queue focus with visible selection, F1 help, complete play,
  append, inspect, reorder, and remove alternatives, and modal shortcut suppression.
- Executable current-session Secret Service/MPRIS evidence, immutable Arch clean-build,
  bounded resilience-soak, and fail-closed `0.2.0` promotion validation commands.
- A canonical `0.2.0` promotion matrix and release evidence schema separating automated,
  current-session, visual, protected-provider, and explicitly accepted boundary states.
- Isolated locked-keyring, 200% high-contrast capture, externally driven MPRIS control,
  and packaged logind sleep-monitor gates with redacted retained evidence.
- A normative folder-scoped Tag workbench specification for reviewing one directory at a
  time, reconciling live changes, proposing deterministic corrections, and applying only
  explicitly approved local edits through an atomic, verified, rollback-capable pipeline.

### Changed

- The documentation header now exposes the latest published release from canonical
  changelog data, and the landing page provides direct Android APK, AppImage, Flatpak,
  checksum, evidence, source, and package-channel links.
- The repository changelog is now generated as a first-class searchable documentation
  page, and the Pages workflow rebuilds when `VERSION` or `CHANGELOG.md` changes.
- Documentation synchronization removes duplicate copied page headings and validates the
  rendered release badge, binary links, changelog route, and release-token closure.
- Linux playback now performs one bounded capability re-resolution per stable media
  identity and cooldown window, resuming from durable progress without automatically
  restarting the mpv process or exposing provider URLs in state or diagnostics.
- The roadmap now treats published `0.1.9` AppImage/Flatpak evidence as complete and lists
  only executable current-host, alternate-session, visual, soak, and protected-provider
  blockers before `0.2.0`.
- Desktop credential restoration performs bounded Secret Service lookup off the UI thread;
  a locked collection leaves the client responsive and exposes a fixed recovery message.
- The Linux client listens for logind `PrepareForSleep`, force-checkpoints and pauses active
  playback before sleep, then resolves a fresh capability and resumes once after wake.
- Compose Desktop now exposes explicit heading, selection, current-track, and player-state
  semantics plus a deterministic black/white/yellow high-contrast palette and non-color labels.

### Security

- Current-session evidence uses a disposable random Secret Service value, clears it
  immediately, and records neither the value nor the session D-Bus address.
- The locked-keyring gate operates on a private ephemeral D-Bus/keyring, returns no
  credential, enforces a five-second maximum, and never touches the real user collection.
- Stream refresh status is fixed and redacted; signed URLs and provider response content
  remain outside persistence, UI state, logs, and evidence.

### Testing

- Added shared retry-policy, mpv EOF/stop/failure classification, shortcut resolution,
  selection bounds, readiness-schema, session-audit, Arch-gate, and soak-contract tests.
- Added sleep-transition policy, blocked-vault timeout, external MPRIS method, Flatpak
  logind permission, accessibility semantics, and current-host evidence contract coverage.

### Known limitations

- The Tag workbench is a reviewed specification in this release, not an implemented file
  mutation surface. Existing Tag studio export behavior remains unchanged.
- `0.2.0` still requires protected EU/US provider evidence, GNOME and KDE observations,
  physical media-key and suspend/resume checks, and a real screen-reader review.

## [0.1.9] - 2026-08-03

### Added

- Explicit unexpected-mpv-exit state with a user-controlled restart-and-resume action;
  automatic player restart attempts remain exactly zero.
- A real-host crash-recovery smoke that forcibly terminates mpv, verifies stable queue
  identity, and requires resumed playback to remain within a five-second checkpoint bound.
- A clean-profile runner for packaged desktop and AppImage smoke tests, including an
  isolated temporary directory that prevents stale extract-and-run state, plus isolated
  Flatpak application HOME/config/data/cache/state paths.
- A deterministic AppImage smoke path that explicitly extracts into a private directory,
  verifies the reviewed `AppRun`, embedded version metadata, and launcher containment,
  then executes the packaged smoke instead of trusting runtime extract-and-run caching.
- Retrying MPRIS identity and playback-status probes so transient D-Bus registration
  races cannot fail an otherwise healthy Flatpak package run.
- A complete release-graph validator covering immutable version/tag/commit provenance,
  required artifact kinds and filenames, sizes, SHA-256 evidence, checksum closure,
  release notes, symlink rejection, and forbidden secret/ephemeral fields.
- An Arch `PKGBUILD` renderer that requires a real HTTPS source archive and calculates
  its checksum instead of accepting `SKIP` or unresolved placeholders.
- A detailed GNOME/KDE/i3, accessibility, package, and soak evidence matrix whose
  unverified cells remain explicit blockers for `0.2.0`.

### Changed

- The Linux gate now includes forced crash recovery and packaged clean-profile smokes in
  addition to unit, application-image, normal mpv/SQLite, and MPRIS checks.
- Tagged AppImage and Flatpak jobs run application smokes with isolated user state, and
  publication revalidates the finalized artifact graph against `GITHUB_SHA`.
- Player IPC polling reports fixed local health messages and cannot expose provider
  response data through process-exit diagnostics.

### Security

- A crashed player is never restarted automatically; recovery requires an observable
  user action that re-resolves the current stream and uses durable progress.
- Release publication rejects artifact symlinks, unsafe paths, missing or extra checksum
  entries, mismatched evidence, and secret-bearing evidence keys.
- Arch package preparation rejects insecure source URLs and floating/skipped checksums.

### Testing

- Added pure exit-state tests, real mpv termination/restart coverage, clean-profile
  environment tests, release-graph mutation tests, Arch renderer tests, and a simulated
  delayed Flatpak playback-status registration regression.

### Known limitations

- Clean-profile workflow wiring is automated, but final AppImage/Flatpak evidence still
  belongs to the immutable tagged release run.
- GNOME, KDE Plasma, and i3 keyring/MPRIS/suspend cells, the manual accessibility matrix,
  a four-hour soak, the final Arch `makepkg --cleanbuild`, and protected EU/US provider
  validation remain external blockers for `0.2.0`.

## [0.1.8] - 2026-08-03

### Added

- A shared queue-restoration algorithm that repairs stale snapshots by stable identity,
  preserves the selected surviving item, and chooses a deterministic nearest fallback.
- Desktop Secret Service regression coverage for missing tooling, caller-buffer clearing,
  and invalid key rejection.
- Host-side tests for the Flatpak-to-host mpv argument boundary.

### Changed

- Android and Linux now persist partially repaired queues immediately and report omitted
  entries rather than rediscovering the same stale state on every launch.
- Desktop progress is force-checkpointed before queue mutation, on playback failure,
  during disconnect, and on orderly shutdown instead of only at five-second boundaries.
- mpv JSON IPC commands carry request IDs, ignore unrelated messages, enforce bounded
  responses, and require an explicit successful command result.
- Desktop pCloud disconnect removes the active source locally first, stops pCloud
  playback, persists a disconnect tombstone and clears affected queue state before
  attempting Secret Service cleanup and typed remote session revocation.
- AppStream metadata now records release history and release validation requires the
  current `VERSION` to be represented.

### Security

- Secret Service subprocesses are bounded and terminated on timeout, caller credential
  buffers are cleared in `finally`, lookup keys are constrained, and oversized results
  are rejected.
- The Flatpak host-mpv bridge rejects arbitrary host command flags and accepts only the
  deterministic properpcloud playback contract plus its private runtime socket.
- mpv failures expose a fixed command error rather than response data that may include an
  ephemeral signed stream location.

### Testing

- Added shared restoration tests for missing predecessors, missing selected entries,
  end-of-queue fallback, and fully unavailable queues.
- Added Android orchestration coverage proving the repaired selection and rewritten
  persisted index.
- Expanded desktop mpv protocol tests for event filtering, response correlation, and
  redacted command failures.

### Known limitations

- Protected pCloud account validation and the GNOME/KDE/i3 compatibility matrix remain
  external gates; this patch makes no new live-provider claim.
- Automatic mpv crash restart, long-duration soak evidence, broad desktop accessibility,
  and reproducible Arch packaging are intentionally assigned to `0.1.9`.

## [0.1.7] - 2026-08-03

### Added

- The first native Linux foundation: a JVM 17 `core-model` artifact and Compose Desktop
  client with folder browsing, deterministic queues, SQLite state, mpv JSON IPC,
  Secret Service sessions, MPRIS, and XDG paths.
- Astro Starlight documentation plus a dedicated Linux integration workflow covering
  the desktop application image, real mpv/SQLite, and packaged MPRIS.
- Checksum-pinned x86_64 AppImage and Freedesktop 25.08 Flatpak packaging, smoke-tested
  in CI and published with the tagged release.
- Secret-safe local OAuth configuration that reads only the public
  `PCLOUD_CLIENT_ID` from an ignored `.env`.
- A committed `.env.example` and host-side regression tests for dotenv parsing,
  quoting, environment precedence, duplicate keys, and malformed identifiers.

### Changed

- Android, metadata, pCloud, and WebDAV modules consume the same portable JVM core
  artifact used by the desktop client.
- Tagged releases aggregate Android, AppImage, and Flatpak jobs into one checksum
  manifest, provenance record, workflow artifact, and GitHub release.
- Tagged builds now receive properpcloud's registered public pCloud application ID
  through the GitHub repository variable, enabling the ordinary OAuth button.
- Android account settings describe direct username/password sign-in only as a
  collapsed fallback when OAuth is configured.

### Security

- `.env*` files are excluded from Git and Docker build contexts. Client tooling
  exports only the public application ID and never reads or passes
  `PCLOUD_CLIENT_SECRET` to Gradle, containers, binaries, CI, or release artifacts.

### Testing

- The build path is validated from dotenv/environment configuration through Make,
  Docker, Gradle, and Android `BuildConfig`, with malformed configuration failing
  closed before client compilation.

### Known limitations

- Protected live OAuth authorization, denial, regional logout, and device-log
  redaction evidence remains a maintainer/device gate outside public CI.

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

[Unreleased]: https://github.com/fkr-0/properpcloud/compare/v0.2.0-rc.3...HEAD
[0.2.0-rc.3]: https://github.com/fkr-0/properpcloud/compare/v0.2.0-rc.2...v0.2.0-rc.3
[0.2.0-rc.2]: https://github.com/fkr-0/properpcloud/compare/v0.2.0-rc.1...v0.2.0-rc.2
[0.2.0-rc.1]: https://github.com/fkr-0/properpcloud/compare/v0.1.10...v0.2.0-rc.1
[0.1.10]: https://github.com/fkr-0/properpcloud/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/fkr-0/properpcloud/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/fkr-0/properpcloud/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/fkr-0/properpcloud/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/fkr-0/properpcloud/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/fkr-0/properpcloud/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/fkr-0/properpcloud/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/fkr-0/properpcloud/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/fkr-0/properpcloud/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/fkr-0/properpcloud/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fkr-0/properpcloud/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/fkr-0/properpcloud/releases/tag/v0.0.1
