# Linux client design

The Linux client should **reuse the JVM core rather than reuse the Android application**. Android UI, lifecycle, credential, and playback adapters are platform-specific; the product semantics and pCloud integration are not.

The normative desktop specification is [`../spec/linux-client.yml`](../spec/linux-client.yml).

## Recommended stack

```text
Compose Multiplatform Desktop
        │
core-application + core-domain + core-metadata
        │
        ├── pCloud java-core adapter
        ├── SQLite persistence adapter
        ├── Secret Service credential adapter
        └── mpv JSON IPC playback adapter
                │
              MPRIS
```

This is the shortest credible path to a desktop client with the same folder, queue, progress, saved-root, inspection, and cache semantics.

## What is reusable

The following code should be shared unchanged or with JVM-only abstractions:

- stable source, node, parent, and revision records;
- natural filename and tag-aware sorting;
- queue commands, snapshots, omissions, duplicate handling, and concurrency generation;
- progress, completion, smart rewind, and long-form policies;
- saved-root filter and grouping policies;
- metadata normalization, provenance, proposal, and repair-plan logic;
- application use cases and ports;
- pCloud adapter built against the official `java-core` artifact;
- WebDAV adapter;
- schema-level persistence records and migration fixtures;
- fake sources, contract tests, and provider sandbox fixtures.

The Android-only pCloud authorization Activity is replaced by a desktop OAuth adapter. The SDK's Java core remains usable.

## What should be rewritten as adapters

| Concern | Android | Linux |
|---|---|---|
| UI | Jetpack Compose | Compose Desktop |
| Playback | Media3 / ExoPlayer | mpv JSON IPC initially |
| Media integration | MediaSession / Auto | MPRIS / media keys |
| Relational data | Room | SQLite driver |
| Preferences | DataStore | typed XDG config |
| Secrets | Android Keystore vault | Secret Service / KWallet |
| Durable jobs | WorkManager | application job scheduler + system integration later |
| Files | app-private / SAF | XDG data and cache paths |
| Notifications | Android notifications | freedesktop notifications |

## Why mpv first

mpv gives the desktop client mature codec and network behavior without a large JNI integration. The application starts one controlled child process with a private JSON IPC socket, observes position and state, and sends load, seek, pause, and speed commands.

Security and correctness rules:

- no shell interpolation of filenames or URLs;
- no signed URL in a persistent playlist or log;
- socket under `XDG_RUNTIME_DIR` with user-only permissions;
- queue remains authoritative in the application database;
- mpv crash preserves queue and progress and offers a bounded restart;
- a refreshed pCloud link is loaded at the last confirmed position.

libVLC or GStreamer can be added behind the same playback port if packaging or gapless behavior later justifies it.

## OAuth

The desktop client opens the system browser. The callback should use the redirect mechanism accepted by the registered pCloud application:

1. custom URI scheme when supported for the desktop client; or
2. a temporary loopback listener if pCloud permits that registered redirect form.

The implementation validates a single-use state value and the regional hostname. The loopback server, when used, binds only to localhost and closes after one callback or timeout. The production redirect form must be confirmed in the pCloud application console before implementation.

The token is stored in Secret Service. A KWallet adapter can provide equivalent behavior on KDE. A file-based fallback should be avoided; if implemented, it must be explicitly opt-in, encrypted with a user secret, and visibly less preferred.

## Desktop UX

A desktop client should not emulate the phone layout at a larger size.

```text
┌───────────────┬───────────────────────────┬─────────────────────┐
│ Account/roots │ Folder or search results  │ Queue / inspector   │
│ Folder tree   │                           │                     │
├───────────────┴───────────────────────────┴─────────────────────┤
│ Now playing · path · timeline · transport · speed · bookmark  │
└─────────────────────────────────────────────────────────────────┘
```

Desktop-specific strengths:

- persistent folder tree;
- drag a folder or selection into the queue;
- context menus that mirror Android semantic commands;
- multi-selection and batch inspection;
- keyboard navigation and queue reordering;
- visible path context in search;
- detachable inspector or queue later;
- MPRIS and media-key integration.

All drag operations have menu and keyboard equivalents. The containing folder remains one click from the player.

## Persistence

Logical records should match Android, but the physical schema need not copy Room's generated representation. Share:

- record definitions;
- serialized policy formats;
- migration version model;
- test fixtures;
- transactional invariants.

Use XDG paths:

```text
$XDG_CONFIG_HOME/properpcloud  non-secret settings
$XDG_DATA_HOME/properpcloud    SQLite and durable user state
$XDG_CACHE_HOME/properpcloud   verified media and rebuildable caches
$XDG_RUNTIME_DIR/properpcloud  mpv IPC and ephemeral process state
```

## The official pCloud console client

The open-source pCloud console client is useful as an optional interoperability path:

- it can expose pCloud through a FUSE mount;
- a local-source adapter can browse that mount;
- any local player can read mounted files.

It is not the preferred integrated architecture because a mount hides provider-specific IDs, revisions, direct-link lifecycle, change cursors, and precise cache state. It also makes the client dependent on a separately managed mount. Direct API access gives better player-to-folder navigation, diagnostics, offline accounting, and revision-safe mutation.

A practical fallback mode can still detect a configured mount and expose it as a local source.

## No custom backend initially

A custom server would add authentication, deployment, data protection, synchronization, and availability costs before the core player is proven. Both clients can operate directly against pCloud.

Cross-device progress can be added later through one of three deliberately separate designs:

1. encrypted export/import;
2. a sidecar state file in a user-selected pCloud application folder;
3. a minimal synchronization service.

The second option is attractive but should wait until progress and conflict semantics are stable. A naïve last-write-wins file can lose bookmarks, completion decisions, or deliberate rewinds.

## Implementation path

### Phase 1: shared extraction

- move portable records and policies into `core-domain`;
- add `core-application` ports and commands;
- build pCloud source against `java-core`;
- keep Android auth and Media3 in Android modules;
- run the same source and application contract suites on the JVM.

### Phase 2: headless desktop proof

Build a CLI that:

1. authorizes through a browser;
2. lists a folder;
3. builds and prints a deterministic queue;
4. resolves and plays one item through mpv;
5. persists and restores position through SQLite;
6. prints a redacted metadata inspection.

This validates every risky adapter before UI work.

### Phase 3: Compose Desktop shell

- implement three-pane browser and queue;
- add bottom player and containing-folder navigation;
- add keyboard-first interactions;
- add MPRIS;
- add saved roots and inspector.

### Phase 4: packaging

Start with a Gradle application distribution and an Arch package for the primary development environment. Add Flatpak for broader distribution once browser OAuth, Secret Service, mpv, and file access are tested inside the sandbox. AppImage can be a secondary portable format.

### Phase 5: optional synchronization

Specify a versioned encrypted progress format and conflict tests before any shared state is written. Never make the Android or Linux release depend on this feature.
