# System design

The normative, machine-readable architecture is in [`../spec/`](../spec/manifest.yml). This document explains the major decisions and their implementation order.

## 1. Product boundary

`properpcloud` is an audio client with file-management context, not a general pCloud clone. Its primary unit is a **source node**:

```text
SourceId + NodeId + ParentId + Filename + optional Revision
```

Tags may add title, artist, album, disc, track, date, artwork, and grouping hints. They never replace node or parent identity. This is what makes both required interactions natural:

```text
folder browser → enqueue folder → player
player → stable parent ID → containing folder
```

The official pCloud Android app is not used as an implementation base because its production source is not publicly exposed. The public Java/Android SDK and HTTP API provide the correct integration boundary.

## 2. Architectural shape

```text
┌───────────────────────────────────────────────────────────────┐
│ Presentation                                                  │
│ Compose Android / Compose Desktop                             │
│ immutable state · typed intents · accessibility               │
└──────────────────────────────┬────────────────────────────────┘
                               │ application commands
┌──────────────────────────────▼────────────────────────────────┐
│ Application                                                   │
│ browse · build queue · resolve item · save progress · repair  │
│ transactions · cancellation · retry budgets                   │
└───────────────┬──────────────────┬──────────────────┬──────────┘
                │ ports            │ ports            │ ports
┌───────────────▼───────┐ ┌────────▼─────────┐ ┌──────▼──────────┐
│ Source adapters       │ │ Persistence      │ │ Playback        │
│ pCloud · WebDAV · SAF │ │ Room/SQLite      │ │ Media3 / mpv    │
└───────────────┬───────┘ └────────┬─────────┘ └──────┬──────────┘
                └──────────────────┬┴──────────────────┘
                                   │ domain values
┌──────────────────────────────────▼────────────────────────────┐
│ Domain                                                       │
│ identity · sorting · queue · progress · metadata provenance  │
└───────────────────────────────────────────────────────────────┘
```

The dependency direction always points inward. Domain code does not know what Android, pCloud, Room, Media3, Compose, SQLite, or mpv are.

## 3. Layers

### 3.1 Domain

The domain layer owns values and decisions that must behave identically on Android, Linux, in unit tests, and against every source:

- opaque source and node identity;
- folder and track records;
- natural filename sorting;
- disc/track sorting with deterministic fallback;
- queue snapshots and duplicate policies;
- progress, smart rewind, and completion policies;
- saved-root matching;
- metadata provenance and confidence;
- immutable repair plans.

A useful rule is: if a function can be tested using only values, it belongs here.

### 3.2 Application

The application layer owns workflows and safe state transitions. It depends on ports, not implementations.

Representative command:

```kotlin
interface BuildFolderQueue {
    suspend operator fun invoke(command: BuildFolderQueueCommand): QueueBuildResult
}
```

The implementation:

1. asks an `AudioSource` for children or a subtree;
2. records structured omissions;
3. asks domain policies to select, group, and sort;
4. creates an immutable snapshot;
5. commits through `QueueRepository` using an expected generation;
6. asks `PlaybackGateway` to select the committed item.

Cancellation before step 5 cannot change the current queue. A concurrent manual reorder changes the generation and causes the stale command to fail rather than overwrite the user's action.

### 3.3 Source adapters

The minimum source port contains only browsing, stream resolution, and provider inspection. Search, change feeds, mutation, artwork, and offline-native behavior are separate capabilities.

```text
AudioSource
├── descriptor
├── root
├── list(folderId)
├── resolveStream(trackId)
└── inspectProviderMetadata(nodeId)

optional capabilities
├── SearchableSource
├── ChangeFeedSource
├── MutableSource
└── ThumbnailSource
```

The pCloud adapter maps SDK objects to domain records and normalizes errors. No `RemoteFile`, `RemoteFolder`, `ApiClient`, SDK `Call`, bearer token, or direct file URL crosses its boundary.

The WebDAV adapter is useful for interoperability and immediate experiments. It is not the preferred pCloud transport because the native API exposes more precise IDs, regional behavior, hashes, links, and change semantics.

### 3.4 Persistence

State is split by durability:

| Class | Examples | Migration policy |
|---|---|---|
| User state | queue, progress, bookmarks, roots, pin intent, repair audit | never destructive |
| Rebuildable cache | folder snapshots, parsed tags, search index, artwork | generation-based rebuild allowed |
| Verified media | downloaded bytes plus source revision/hash | preserve while integrity remains valid |

Android uses Room for relational records, DataStore for small preferences, Android Keystore-backed credential storage, and app-private or explicitly selected storage for media bytes.

Linux uses SQLite, typed atomic XDG configuration, Secret Service/KWallet, and the XDG cache directory.

Secrets are represented in normal records only as opaque credential handles.

### 3.5 Playback

Android playback belongs to a `MediaSessionService`; the Activity and composables are controllers. A queue item has a stable media ID. Immediately before preparation, the application resolves either a verified local file or a fresh pCloud link.

```text
stable QueueItem
   │
   ├─ cache has verified revision ──► local content URI
   │
   └─ otherwise ──► pCloud get file link ──► ephemeral HTTPS URI
                                               │
                                               ▼
                                            Media3
```

An expired link invalidates only the transient stream handle. The item, queue, progress, and parent folder remain valid. The application obtains a new handle and resumes from the last confirmed position within a bounded retry budget.

The Linux player follows the same port. Its first adapter uses mpv JSON IPC because mpv supplies mature codec, network, and seeking behavior without forcing a large JNI media stack into the shared core.

### 3.6 Presentation

The UI consumes immutable state and emits typed intents. It does not call the provider SDK, SQL, or player directly.

Every screen with remote or durable work distinguishes:

```text
idle
loading with no content
ready
refreshing with existing content
stale or offline with existing content
partial success with omission report
empty
failure with recovery action
```

A spinner without timeout, cancellation, stale behavior, or recovery is not a complete state.

## 4. Core state transitions

### Folder play

```text
FolderAction.Play
  → BuildFolderQueue command
  → source listing
  → audio selection
  → deterministic sorting
  → atomic queue commit
  → fresh stream resolution
  → player prepare
```

An empty folder preserves the existing queue. A partial recursive result is committed only when policy permits or the user accepts it.

### Player to folder

```text
NowPlaying.openContainingFolder
  → QueueItem.parentId
  → BrowserRoute(sourceId, parentId, highlight=nodeId)
```

No display path parsing is involved, and playback is unaffected.

### Progress checkpoint

```text
Media3/mpv position sample
  → stable media ID + revision
  → progress/completion policy
  → transactional upsert
  → browser/player projections
```

Writes are coalesced during playback and forced at pause, completed seek, transition, backgrounding, and service shutdown. The maximum acceptable loss window is 15 seconds.

### Metadata mutation

```text
scan
  → proposal with provenance/confidence
  → field-level approval
  → immutable repair plan
  → revalidate source revision
  → stage and edit local copy
  → validate tags and decode
  → upload with expected revision
  → re-read and verify
  → audit with recovery reference
```

A remote revision change stops the file before overwrite. Network loss after an ambiguous upload boundary creates an `indeterminate` audit state and a reconciliation task; it is never reported as success.

## 5. Android module plan

```text
core-domain
core-application
core-metadata
source-pcloud-jvm
source-webdav-jvm
persistence-schema
persistence-android
playback-android
ui-design-system
feature-auth
feature-browser
feature-player
feature-inspector
feature-settings
app-android
```

The bootstrap currently combines some of these concerns in `core-model`, `source-pcloud`, `source-webdav`, and `app`. The split should occur incrementally when the first vertical slice is implemented, not as empty-module ceremony.

## 6. External libraries

The architectural preference is to depend on maintained platform or official components at adapters:

- Android Gradle Plugin with built-in Kotlin;
- AndroidX Media3 for Android playback and media sessions;
- pCloud Java/Android SDK for source access and OAuth integration;
- Kotlin coroutines for structured concurrency;
- Room for Android relational persistence;
- DataStore for Android preferences;
- Compose Material 3 for Android UI;
- Compose Multiplatform Desktop for the Linux shell;
- SQLite and Secret Service adapters on Linux;
- mpv through JSON IPC for first desktop playback adapter.

A metadata parser must support bounded reads, malformed input, and raw-frame inspection. The exact library should be selected through a spike against the fixtures in `spec/testing.yml`; no parser receives unbounded artwork or tag allocation authority.

## 7. Implementation sequence

### Slice 1: real folder playback

1. Register a pCloud application and document redirect configuration.
2. Implement Android OAuth adapter and credential vault.
3. Split `AudioSource` and queue command into application ports.
4. Implement Room queue, progress, and folder-snapshot repositories.
5. Implement a minimal Compose folder browser.
6. Implement folder play and open-containing-folder.
7. Connect Media3 service with just-in-time link resolution.
8. Add process-death and link-expiry tests.

This slice satisfies the two minimum usability requirements before advanced library work.

### Slice 2: long-form quality

1. Add progress policy, smart rewind, speed, skip, sleep timer, and bookmarks.
2. Add saved roots and folder policies.
3. Add verified offline cache and accounting.
4. Add MediaLibraryService hierarchy for Android Auto.

### Slice 3: inspection and maintenance

1. Add bounded tag reader and layered inspector.
2. Add pCloud change cursor support.
3. Add proposal engine.
4. Add revision-safe write adapter only after read-only inspection is mature.

## 8. Correctness model

The system uses four types of identity deliberately:

```text
stable source identity: source instance
stable node identity: provider file or folder
content revision identity: exact bytes/version
ephemeral capability identity: temporary URL or local open handle
```

Conflating these causes most subtle failures. Progress normally follows stable node identity but is checked against content revision. Cache validity follows content revision. Playback URLs are capabilities and are disposable. UI paths are presentation context and are never authority.

## 9. Security and privacy

- OAuth occurs in a trusted authorization surface.
- Access tokens are stored through a vault and never normal database columns.
- Signed URLs are redacted and not persisted as queue data.
- Cleartext traffic is disabled in release builds.
- Provider names, tags, artwork, and paths are untrusted inputs with parser and rendering limits.
- No analytics or custom backend is required.
- External metadata lookup is independently consented because it sends descriptive data to another service.
- Support bundles are user-triggered, bounded, and redacted.

## 10. Verification

The complete matrix is in `spec/testing.yml`. The guiding rule is that domain behavior is proven with pure tests, workflows with fake ports, adapters with reusable contract suites, and only platform facts with device or live-provider tests.

A release is blocked by any path that can:

- delete cloud data while evicting local cache;
- leak credentials or signed URLs;
- destroy queue or progress during migration;
- commit cancelled recursive queue state silently;
- overwrite a changed remote revision;
- claim partial content is verified offline media.
