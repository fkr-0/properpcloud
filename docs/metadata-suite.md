# Metadata inspection and maintenance suite

## Goals

The suite must support serious library maintenance while preserving the central
properpcloud rule: folders, filenames, source IDs, and node IDs remain the stable
library truth. Tags improve display and search but never become identity.

The complete workflow covers:

1. inspect provider facts and embedded tags;
2. normalize values without changing bytes;
3. infer candidates from filename and folder structure;
4. query explicitly enabled online sources;
5. compare candidates with provenance and confidence;
6. build deterministic single-file or batch patches;
7. stage edits on a local copy;
8. validate tags and audio decodability;
9. replace the remote file only with an expected revision/hash;
10. reread remote state and record audit/recovery evidence.

`0.1.3` adds the complete local review/edit/export loop: Tag studio, bounded batch
editing, field-level MusicBrainz acceptance, exact pCloud download-to-staging,
verified single-file or ZIP exports, and scoped Android sharing. It deliberately
does not enable remote file replacement.

## Modules

```text
core-model/
  MetadataModel.kt
    canonical fields
    provenance and confidence
    tag snapshots and mutations
    single and batch edit plans
    deterministic batch planners

metadata-tags/
  AudioTagToolkit.kt
    jaudiotagger-backed inspection
    local copy-on-write staging
    SHA-256 source guard
    post-write reread and field verification

metadata-online/
  OnlineMetadata.kt
    MusicBrainz recording search
    secure XML parsing
    identified User-Agent
    serialized rate gate
    Cover Art Archive URL contract
    AcoustID fingerprint/lookup contracts

source-pcloud/
  provider checksum lookup
  exact download with pre/post revision comparison
  no overwrite until an atomic expected-revision primitive exists

app/
  Tag studio with original values and provenance
  field-level MusicBrainz review
  bounded common-field and sequencing batch editor
  verified file or ZIP/CSV export through FileProvider
```

## Canonical fields

The first canonical vocabulary is intentionally conservative:

```yaml
text:
  - title
  - artist
  - album
  - album_artist
  - genre
  - year
  - comment
  - composer
  - lyrics
identifiers:
  - isrc
  - musicbrainz_recording_id
  - musicbrainz_release_id
ordering:
  - track_number
  - track_total
  - disc_number
  - disc_total
artwork_summary:
  - mime_type
  - byte_count
  - width
  - height
  - description
```

Container-specific frames and atoms remain available in a later raw view rather
than being forced into lossy canonical fields.

## Local tag engine

The adapter uses `net.jthink:jaudiotagger:3.0.1` behind `AudioTagToolkit`.
jaudiotagger is LGPL-licensed; the repository and APK include its notice and the
LGPL 2.1 text. No jaudiotagger type crosses into `core-model` or UI state.

### Inspection

`inspect(file)` reads:

- container and tag implementation;
- common canonical text fields;
- MusicBrainz IDs and ISRC;
- artwork descriptors without requiring unbounded image decoding.

Malformed or unsupported files fail explicitly. They do not disappear from the
folder browser and are not changed.

### Staging

`stagePatch` executes this sequence:

```text
readable source file
  ↓ SHA-256 and optional expected-hash check
inspect original
  ↓ copy bytes
unique app-private staged file
  ↓ apply approved Set/Clear operations
jaudiotagger commit
  ↓ reread
verify each intended field
  ↓ hash
StagedTagResult
```

Any exception deletes the incomplete staged candidate and leaves the source file
untouched. The unit test writes a real PCM WAV, applies title and artist to a
staged copy, rereads them, and proves that the original has no title tag.

## Batch operations

`BatchTagPlanner` currently supports:

- set or clear common fields across selected items;
- sequential track numbers with an optional total;
- selected fields from a reviewed MusicBrainz/AcoustID candidate;
- explicit Keep mutations;
- changed-item and changed-field totals.

Every remotely targetable plan requires an expected revision or content hash.
Duplicate stable identities are rejected. Selection order is preserved for track
sequencing.

The folder auto-tag workbench now adds a deterministic filesystem-derived layer for
Artist/Album/Track.ext layouts. Its configuration chooses the artist and album ancestor
depths, can skip common `CD 1`, `Disc 02`, `Disk 3`, or `Part 1` containers while deriving
the disc number, derives a plain filename-stem title when configured, and sequences missing
or conflicting track numbers using natural filename order (`2` before `10`). Derived values
are always shown beside embedded values. A conflicting non-empty embedded value is never
preselected or silently replaced.

The normal workbench remains direct-folder only. A separate tree preview command defaults
to the same direct scope and becomes recursive only after explicit opt-in. Recursive preview
scans each directory as its own direct-folder snapshot without following directory symlinks.
Explicit field approvals can then be frozen into a batch plan; recursive plans require a
second recursive opt-in before they are accepted.

Batch execution is dry-run by default. Dry runs re-hash every approved file, surface stale
content as a conflict, and produce progress without staging or replacing media bytes.
Confirmed execution is sequential, keeps every per-file SHA-256 guard, stops on
an indeterminate result, reports completed/total/current-file progress, and retains each
verified result hash plus rollback reference. A changed file becomes a conflict instead of
weakening the guard for later files.

Remaining transforms include filename templates and artwork assignment. Each transform must
remain previewable and deterministic.

## Playlist generation

Playlist generation is a derived-filesystem operation, not a tag mutation. The shared
`FolderPlaylistWriter` accepts an already-inspected folder snapshot and produces a reviewable
plan before changing the filesystem. Direct-folder generation remains the default. Existing
natural-filename, embedded disc/track, and tagged-title ordering are preserved, and an
additional modification-time mode sorts oldest observed content evidence first with natural
filename as the deterministic tie-break.

`#EXTINF` uses locally inspected audio-header duration only when the duration is positive and
therefore trustworthy enough to expose as container evidence. It is rounded to whole seconds.
When no such evidence exists the generated entry says `#EXTINF:-1`; file size, bitrate guesses,
or tag text are never used to invent a duration. The same inspected duration is also available
to the explicit MusicBrainz query boundary without exposing audio bytes or paths.

Playlist naming may use a unanimous embedded album plus unanimous album-artist/artist so a
generic folder such as `incoming/` can still produce `Artist - Album.m3u8`. This is only the
name of a derived playlist artifact. The playlist directory and every media entry remain based
on the reviewed filesystem paths.

Recursive playlist work has its own opt-in and does not imply recursive tag writes. A subtree
batch can either create one playlist per non-empty scanned folder or use one-playlist-per-album
mode. The album mode is filesystem-first: recognized `CD`, `Disc`, `Disk`, or `Part` folders are
collapsed into their real parent directory inside the selected root, so entries such as
`./CD 1/01 - Song.mp3` remain portable. Tags may improve the output filename but cannot choose
the target directory. Planning rejects root/path/symlink escapes and batch materialization
preflights every output before the first derived playlist is replaced, then reports
completed/total progress.

The reusable planning layer remains deliberately preview-first: `FolderAutoTagWorkflow`
produces immutable tag and playlist plans and has no snapshot-to-write shortcut. The
revision-bound application seam is `FolderMetadataSuiteSession`. Phase 5 adds the neutral
`LocalFolderRootCapability` and `LocalFolderWorkbenchHost` above it for callers that truly own a
user-selected writable local filesystem root. Capability construction rejects symlink/read-only
roots and proves same-directory atomic move with disposable hidden non-media probes before the
workbench can open; provider caches, prepared downloads, and stream capabilities are never
promoted implicitly.

The JVM host uses a real `WatchService`. Directory registration completes synchronously before
the first scan starts. Relevant audio create/modify/delete events invalidate the current session
review immediately, then a 250 ms quiet window (bounded by a 2 s maximum coalescing latency)
triggers an authoritative rescan. A scan becomes `live` only when no newer watcher event raced
that generation. Overflow or an invalid watch key forces observer re-registration/full
reconciliation. Recursive mode registers non-symlink descendants and newly created directories.
Rename is intentionally not guessed from event ordering: ordinary JDK create/delete evidence is
resolved by the authoritative rescan.

The host does not suppress expected events from a confirmed tag write. Tag mutation and
reconciliation share one operation mutex, expected self-events take the same invalidation path as
external events, and the host requires a fresh stable post-write snapshot before clearing the
reconciliation flag. This avoids hiding a coincident external edit behind an "own write" marker.
Watcher callbacks themselves have no tag-stage/apply path. Derived `.m3u`/`.m3u8` writes are not
classified as media events because playlist materialization already has its own frozen
content/membership preflight.

Before any materialization, the writer also rechecks the size, observed modification-time
evidence, and authoritative SHA-256 frozen into every planned entry, plus the reviewed
audio-child membership of every reviewed source directory, including directories that were
empty during preview. Recursive batches also freeze the complete reviewed non-symlink directory
set. Changed bytes, added/removed/renamed audio children, or a new/removed descendant directory
reject the stale plan with a reconcile/new-preview error before a derived playlist is changed.
The hash closes the case where a tag rewrite preserves modification time and happens to keep the
same byte size. Confirmed tag execution also invalidates the application session after verified
results, forcing playlist derivation to use a fresh post-write scan rather than pre-write tags.

Android Tag studio uses a narrower boundary because its current sources expose prepared
copies rather than a writable local library directory. Batch review now has an explicit
include-playlist toggle and deterministic order selector; the chosen playlist is shown in the
confirmation dialog and is materialized only inside the verified ZIP export. This does not
pretend Android has direct-folder or recursive filesystem ownership. Clients that do own a
selected local root use the shared tree workflow, where recursive playlist opt-in remains
independent from recursive tag-apply opt-in.

For post-sync integration the module exposes a bounded playlist-only regeneration service.
Callers submit a fresh reviewed batch plan after reconciliation; repeated keys are debounced
for 250 ms, with defaults of 16 pending batches and 256 playlists per batch. The low-level
service has no scanner, tag toolkit, or tag-apply dependency, so it has no code path that can
turn a filesystem event into a metadata write. Regeneration still uses same-directory staged
replacement and is deterministic for an unchanged plan. A low-level stale materialization
fails closed. Through `FolderMetadataSuiteSession`, a watcher/reconciliation signal or stale
flush additionally revokes the queued review so it cannot become eligible again merely because
a later scan happens to resemble the old one; the client must reconcile and submit a fresh plan.

Phase 6 binds that neutral host to one deliberately narrow desktop source. In an unsandboxed
native desktop build, **Local** selects one directory for direct-folder scope and **Local tree**
selects one directory for an explicitly recursive workbench scope. The picker result is validated
before `LocalFolderRootCapability` exists. The resulting `AudioSource` uses an opaque source ID
derived from the selected filesystem root and opaque node IDs derived from root-relative paths;
tag values, pCloud IDs, provider URLs, and the deterministic demo never participate in that
identity. Enumeration follows no symlinks, admits only supported audio files/directories, keeps
the shared deterministic folder/track ordering, and resolves playback to non-expiring `file:`
handles rather than provider links.

The Compose desktop workbench projects the host's `starting`, `scanning`, `live`, `stale`,
`overflow-rescanning`, and `failed` states plus its session revision. A revision change clears any
held tag or playlist review. Tag mutation remains field-review -> dry-run -> separate replacement
confirmation. Only one proposal rule may be selected for a given file/field at a time, and tag
dry-run/apply plus recursive playlist writes project completed/total progress without exposing a
private path. Recursive tag permission is a dedicated checkbox and is not derived from selecting
recursive playlists. Playlist planning likewise has its own recursive opt-in and a separate
materialization confirmation. Switching away from the local source or closing the controller
closes the observer host and removes queue entries that depended on the selected root. The local
selection is intentionally process-session scoped for now: the complete private root path is not
persisted and a restart requires explicit reselection.

Android remains on the verified prepared-copy/ZIP boundary and gains no local-root identity from
this desktop work. pCloud, signed links, metadata staging downloads, and demo media also remain
outside `LocalFolderRootCapability`.

The current Flatpak package is a deliberate packaging blocker for this feature. Its manifest does
not grant host/home filesystem access and this codebase has no document-portal directory lease
implementation. `NativeLocalFolderSelector` therefore reports local-folder selection unavailable
when the Flatpak environment or sandbox marker is present. We do not widen the sandbox or fabricate
a portal-backed path; native packages retain the local-source integration where ordinary
user-selected filesystem access is a truthful capability.

## Online matching

### MusicBrainz

The MusicBrainz client searches recordings by any combination of:

- ISRC;
- recording title;
- artist;
- release title;
- duration within a two-second tolerance.

It sends a meaningful application/version/contact User-Agent and serializes calls
through a 1.1-second gate. XML parsing rejects document types and external
entities. Results become `MetadataCandidate` records with MusicBrainz provenance,
score, recording ID, optional release ID, and a Cover Art Archive reference.

No MusicBrainz credentials are required for public lookup. Commercial usage and
service terms must be reviewed before commercial distribution.

### Cover Art Archive

Artwork is requested only after a release is selected. Release IDs are validated
as UUIDs and generated URLs stay on HTTPS. `0.1.2` does not download or write
artwork; it only establishes the safe reference contract.

### AcoustID and Chromaprint

AcoustID is valuable when filenames and existing tags are poor. A future Android
adapter will generate a Chromaprint fingerprint locally, then send only duration
and fingerprint to AcoustID lookup.

The current foundation defines the fingerprint and lookup request contracts. It
does not embed an API key or ship a native fingerprint binary. Application keys
must be configured outside source control, and service/commercial terms must be
reviewed before distribution.

### Matching hierarchy

```text
existing MusicBrainz ID / ISRC
          ↓ absent or invalid
local Chromaprint → AcoustID → MusicBrainz recording IDs
          ↓ unavailable or ambiguous
MusicBrainz text + album + duration search
          ↓ reviewed release selected
Cover Art Archive candidate
```

Candidate confidence does not equal permission to write. Low-confidence fields
remain unselected.

## pCloud source preparation and future mutation adapter

`0.1.3` safely prepares remote source bytes:

```text
provider metadata + SHA-256
  → exact app-private download
  → local size/hash verification
  → provider metadata + SHA-256 reread
  → accept only when revision and checksum are unchanged
```

Any mismatch deletes the local candidate and no edit begins. This is sufficient
for safe local staging/export, but not for safe cloud overwrite.

Remote maintenance will not be enabled until `source-pcloud` exposes a separate
mutable capability with these operations:

```yaml
inspect_revision:
  output: stable node, revision/hash, size, permissions
download_exact:
  input: node and expected revision/hash
  output: verified staging source
replace_conditional:
  input: node, expected revision/hash, staged bytes
  output: new revision/hash and provider metadata
reread:
  output: resulting revision/hash and metadata
```

The apply state machine is:

```text
draft → approved → downloading → staged → validated
      → revision recheck → uploading → verifying → verified
                                       ↘ indeterminate → reconcile
remote changed before upload → conflicted, no overwrite
```

A remote upload response alone is insufficient evidence. Success requires
provider readback. An ambiguous network failure after the upload boundary becomes
`indeterminate`, not `failed`, until reconciliation.

## Implemented UI workflow

The local proposal UI follows five steps:

1. **Selection** — files, source capability, download/upload estimate.
2. **Candidates** — current and proposed values, provider, confidence, warnings.
3. **Review** — exact approved field diff and original revision/hash.
4. **Stage** — exact source preparation, candidate write, tag reread, and hash.
5. **Export** — one verified file or a ZIP with `metadata-manifest.csv`.

Candidate selection and field acceptance are separate actions. Export grants a
read-only URI to the chosen target. Cloud media bytes are never replaced.

## Privacy and security

- Online lookup is opt-in and discloses the transmitted metadata.
- MusicBrainz receives text identifiers and optional duration, not audio bytes.
- AcoustID lookup receives a derived fingerprint and duration.
- Tokens, signed URLs, provider keys, fingerprints, and unrestricted response
  bodies are excluded from diagnostics and support exports.
- Metadata queries use TLS; cleartext fallback is forbidden.
- Artwork and tag parsers must enforce memory and dimension limits.
- Remote changes require expected revision/hash, modify permission, post-write
  verification, and an audit record.

## Test strategy

```yaml
domain:
  - changed-field detection
  - candidate field selection
  - deterministic track sequencing
  - duplicate identity rejection
  - mandatory revision/hash guard
local_adapter:
  - valid tagged and untagged files
  - original bytes unchanged
  - staged reread verifies Set and Clear
  - wrong expected SHA-256 aborts before copy/write
  - unsupported and malformed formats leave no candidate
local_workbench_host:
  - observer lease exists before initial scan and a scan-time event prevents stale live publication
  - approval invalidation occurs on the immediate event callback, before coalesced rescan
  - overflow and invalid observer states force authoritative rescan/re-registration
  - raw event storms are bounded and degrade to synthetic overflow rather than accumulating indefinitely
  - confirmed self-events reconcile after mutation; watcher paths never invoke tag apply
  - stale playlist preflight projects stale/error state even when no watcher event is delivered
  - real WatchService smoke observes audio changes and ignores derived M3U writes as media events
online:
  - query escaping and duration bounds
  - User-Agent identification
  - 1.1-second serialized rate gate
  - secure parser rejects DOCTYPE/external entities
  - candidate score and provenance mapping
remote_future:
  - revision changes before upload
  - timeout before and after upload boundary
  - post-upload hash mismatch
  - partial batch with verified audit for every item
  - process death and reconciliation
```

## Release boundaries

### `0.1.2`

Delivered: modern player surface, compact queue actions, canonical metadata
records, batch planning, real staged local tag editing and verification,
MusicBrainz lookup foundation, and comprehensive contracts.

Not delivered: in-app field editor, pCloud remote replacement, fingerprint
binary, artwork writes, unattended online matching.

### `0.1.3`

Delivered: Tag studio, original/provenance display, explicit field-level
MusicBrainz review, bounded batch common-field edits, deterministic sequencing,
exact pCloud source preparation, reread-verified candidates, single-file sharing,
and ZIP plus CSV-manifest export.

Not delivered: atomic pCloud replacement, artwork writes, Chromaprint generation,
AcoustID configuration UI, or unattended candidate acceptance.

### `0.4.0`

Layered inspector and proposal UI with explicit online-provider consent and no
remote writes.

### `0.5.0`

Revision-safe pCloud maintenance, post-upload verification, audit, conflict, and
recovery support.
