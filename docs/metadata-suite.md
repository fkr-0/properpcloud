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

`0.1.2` implements the domain, batch-planning, local staging, tag verification,
and MusicBrainz lookup foundations. It deliberately does not enable remote file
replacement yet.

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

source-pcloud/ (future mutation capability)
  exact-revision download
  expected-revision replace
  post-upload readback

app/ (future proposal UI)
  layered inspector
  field diff and candidate review
  batch plan confirmation
  apply progress, conflict, and recovery UI
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

Planned transforms include filename templates, folder-derived album/disc values,
Unicode/whitespace normalization, and artwork assignment. Each transform must be
previewable and deterministic.

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

## Future pCloud mutation adapter

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

## UI workflow

The proposal UI is specified as five steps:

1. **Selection** — files, source capability, download/upload estimate.
2. **Candidates** — current and proposed values, provider, confidence, warnings.
3. **Review** — exact approved field diff and original revision/hash.
4. **Apply** — per-file download, stage, validate, revision check, upload, verify.
5. **Result** — verified, skipped, conflicted, failed, indeterminate, recovery reference.

Selection of a candidate and confirmation of remote replacement are separate user
actions. The final confirmation must state that cloud media bytes will be
replaced.

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

### `0.4.0`

Layered inspector and proposal UI with explicit online-provider consent and no
remote writes.

### `0.5.0`

Revision-safe pCloud maintenance, post-upload verification, audit, conflict, and
recovery support.
