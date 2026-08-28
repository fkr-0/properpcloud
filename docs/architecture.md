# Architecture

## System boundary

```text
┌───────────────────────────────────────────────────────────────┐
│ Android UI                                                    │
│ Folder browser/search · Queue · Now playing · Inspector · Settings │
└──────────────────────────────┬────────────────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────┐
│ Playback/application layer                                   │
│ QueueBuilder · filename search · Progress/history policies    │
│ Media3 MediaSessionService · bounded renewable URL handling   │
└──────────────────────────────┬────────────────────────────────┘
                               │ AudioSource
             ┌─────────────────┼──────────────────┐
             │                 │                  │
┌────────────▼──────────┐ ┌────▼─────────────┐ ┌──▼──────────────┐
│ Native pCloud source │ │ WebDAV source    │ │ Local/SAF source│
│ official Java SDK    │ │ fallback/testing │ │ future          │
└───────────────────────┘ └──────────────────┘ └─────────────────┘
```

## Core invariants

1. Folder and filename are first-class metadata, never incidental strings derived after scanning.
2. A queue item preserves `sourceId`, provider node ID, parent folder ID, and filename.
3. The now-playing model always knows its containing folder, enabling player → folder navigation.
4. Stream URLs are capabilities with expiry times, not permanent file identities.
5. Progress is keyed by stable source/node identity and guarded by content revision/hash when available.
6. Tag data enriches a file; it does not replace the file's path identity.
7. Remote mutation is opt-in, previewed, and revision-aware.
8. Generic files and playlist files remain filesystem nodes for filename search but never become playable queue entries merely because they are visible.
9. Queue/current/progress/history persistence contains stable media identity, never an expiring stream capability.

## Native pCloud flow

```text
OAuth AuthorizationActivity
  → bearer token + regional API location
  → encrypted local token store
  → reusable ApiClient
  → listFolder(folderId)
  → AudioFolder / AudioTrack nodes
  → createFileLink(fileId)
  → Media3 MediaItem URI
```

`getfilelink` URLs expire. Before playback begins, and after a retriable HTTP/network failure, the playback resolver obtains a new link from the stable pCloud file ID. Automatic recovery is bounded per stable identity. Explicit Play after an eligible terminal failure rebuilds the item without the failed URI, forcing fresh source resolution before prepare. Permanent HTTP client errors and decoder/media failures remain surfaced instead of entering a refresh loop. Queue persistence stores node IDs, never direct URLs.

## Authentication

The official Android authorization module expects a registered pCloud application and an OAuth redirect URI of the form:

```text
pcloud-oauth://dev.properpcloud.app
```

The source adapter itself depends on the portable `java-core` artifact. The Android OAuth feature depends separately on the `android` artifact; pCloud does not publish the Android artifact with a transitive `java-core` dependency. The OAuth client ID is build configuration, not a secret. OAuth and legacy `auth` tokens are credentials and must be stored with Android Keystore-backed encryption. OAuth requires no password exposure to properpcloud; the explicitly fallback-only direct-login adapter handles a password only for one allowlisted regional HTTPS token request and never persists it.

The WebDAV fallback uses the account email/password and therefore has a larger credential-handling surface. It should support Android credential storage but remain optional.

## Queue semantics

Folder actions:

- **Play folder**: replace queue with direct audio children.
- **Play subtree**: recursively enumerate descendants, preserving folder grouping.
- **Play next**: insert sorted folder content after current item.
- **Append**: add sorted folder content to queue.
- **Shuffle folder**: shuffle only after deterministic enumeration.

Default sort precedence:

```text
disc number → track number → natural filename
```

Unknown tag numbers sort after known values. Users can switch to natural filename, tagged title, or modification time.

Every successful queue mutation, including clear/reorder/remove/replace and current-item selection, crosses the durable queue-state boundary. Player-originated next/previous selection changes are mapped back to queue entries by stable `MediaIdentity` and persisted as well. Startup restoration is guarded against racing a newer user mutation and installs the restored queue/current item plus normalized position together, paused, without surprise autoplay.

## Filename search

Search is deliberately filesystem-first in this tranche. The search icon expands a filename field; fewer than three trimmed characters leave the normal library view intact. At three or more characters, a short debounce filters the already-loaded current-scope `MediaNode` model—there is no provider traversal on each keystroke.

The match types are directories, generic files, audio files, and playlist files. All are enabled initially. Generic files are a superset of audio/playlist files while selected; when generic files are disabled the specialized categories work independently. Results are case-insensitive, duplicate stable identities are suppressed, and ordering is deterministic by natural filename then stable identity. Match-type choices persist, while query text does not. Artist/title/year tag search remains deferred.

## Progress model

Persist at least:

```yaml
source_id: pcloud
node_id: pcloud:file:123456
content_revision: provider hash when available
position_ms: 1842050
duration_ms: 7312000
playback_speed: 1.15
updated_at: 2026-08-01T21:00:00Z
completed: false
```

Music defaults to track-level resume disabled. Long-form audio defaults to resume enabled. The threshold and per-folder policy are configurable.

Active progress is checkpointed on an approximately 30-second cadence, with event-driven flushes at pause, item/queue changes, failure, background/task removal, completion, and shutdown boundaries where available. Repeated paused ticker samples are coalesced. Restore clamps impossible positions to known duration, restarts effectively completed items at zero, and otherwise applies smart rewind.

Playback history is an optional facility separate from restoration. It is disabled by default, retains 100 stable identities by default, and is hard-bounded to 500. Android stores it in DataStore JSON; Desktop uses an additive SQLite `playback_history` table. Neither representation stores direct URLs.

## Metadata inspection and repair

Inspection presents three layers without silently conflating them:

1. filesystem/provider metadata;
2. embedded audio tags;
3. effective display and sort values.

Repair pipeline, later phase:

```text
scan → propose → diff → user approval → download/edit/upload → verify hash/tags
```

pCloud revisions provide recovery after replacement, but the app must still expose a dry-run and never mass-edit silently.

## Extensibility seam

`AudioSource` deliberately exposes only browser, stream resolution, and inspection. Mutation, sync, thumbnails, search, and change feeds should be capability interfaces rather than continuously expanding one provider interface.

Suggested future capabilities:

```kotlin
interface SearchableSource
interface MutableSource
interface ChangeFeedSource
interface ThumbnailSource
interface OfflineCacheSource
```
