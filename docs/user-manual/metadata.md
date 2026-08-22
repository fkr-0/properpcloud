# Metadata inspection and repair

properpcloud separates playback identity from embedded tags. A badly tagged file can still be browsed and played using its provider filename and stable node ID.

## Inspection

Select an item and choose **Inspect** to view read-only information such as:

- provider and stable IDs;
- parent folder;
- content type and size;
- modified time and provider hash;
- embedded title, artist, album, track, disc, and duration when available.

Inspection does not download or rewrite the complete file unless a metadata workflow explicitly requires a local staging copy.

## Repair workflow

Metadata writes follow a staged transaction:

```text
provider snapshot
    -> verified local download
    -> parse and propose changes
    -> user review
    -> write staged copy
    -> verify output
    -> guarded provider replacement
    -> retain rollback evidence
```

The staging download is accepted only when size, provider revision, and SHA-256 remain consistent before and after transfer.

## Online suggestions

Online metadata providers can suggest candidates, but suggestions are evidence rather than authority. A match should show the queried recording, confidence signals, and field-level changes before acceptance.

The direct-folder workbench described below is implemented as a shared local-root capability,
watcher/reconciliation host, and metadata workflow. Native desktop builds now expose it through
the **Local** and **Local tree** source actions. Android Tag studio still uses verified
prepared-copy export, and desktop pCloud/demo sources remain separate provider/demo identities.
The current Flatpak build does not expose local-root selection because it has neither host/home
filesystem permission nor a document-portal directory lease implementation.

On native desktop, choose **Local** for one direct directory or **Local tree** when you need an
explicit recursive workbench. Selection happens before the writable-root capability is created.
The desktop source uses filesystem-first opaque source/node IDs and never stores tag values or a
provider URL as identity. The selected complete private path is not restored from persistence in
the current implementation, so restart requires a fresh selection. Switching to another source
or closing properpcloud closes the observer lease and drops queued entries that depended on the
closed root.

The folder tagging workflow always starts from one selected directory and scans direct audio children only. Online matching is opt-in. The preview lists each file, its current values, ranked candidates, confidence, and the exact fields selected for change. Moving to the next or previous file does not apply anything; applying requires a separate confirmation and verifies each staged result.

Folder-derived suggestions can use an Artist/Album/Track.ext style layout. The inference
profile controls how many parent levels contain the album and artist. Common disc folders such
as `CD 1`, `Disc 02`, `Disk 3`, and `Part 1` can be recognized as disc-number evidence while
artist/album inference continues from the parent album folder. Track numbering uses natural
filename order, so `2 Song.mp3` comes before `10 Song.mp3`. If a derived artist, album, title,
track, or disc value disagrees with a non-empty embedded tag, both values remain visible and
the derived value is unselected until you explicitly approve that field.

Recursive repair is a separate batch action, not a change to the direct-folder default. You
must explicitly select recursive tree preview and explicitly opt into a recursive batch plan.
The batch is preview/dry-run first, contains only approved fields, and keeps the reviewed
SHA-256 for every file. Dry-run rechecks those hashes without creating staging bytes.
Confirmed writes run sequentially through the same staged, hash-guarded,
verified transaction used for one file. Progress reports completed and total items, and each
verified edit retains rollback evidence. If bytes change after review, that file is reported as
a conflict and is not overwritten.

The music database can be browsed by folder, artist, or album. Adding music to that database is deliberately narrower than browsing: each add action accepts one reviewed direct-directory snapshot. Loose-file, recursive-tree, and whole-source add operations are not available.

## Folder playlists

After a folder has been inspected, the shared tag workflow can preview and then write or update a standard UTF-8 extended-M3U playlist beside the audio files. When every reviewed track agrees on album and artist/album-artist tags, those tags can improve the derived filename (for example `Artist - Album.m3u8`); otherwise the real folder name is used. This naming never changes where tracks live or how their identity is resolved.

Four deterministic orders are available:

- **Filename:** natural filename order, so `2 - Track` sorts before `10 - Track`.
- **Tag track number:** embedded disc number and track number first, falling back to natural filename when a tag is absent or tied.
- **Tagged title:** embedded title first, with natural filename as the fallback and tie-break.
- **Modification time:** oldest observed file modification time first, with natural filename as the tie-break.

When the local audio parser reports a positive container duration, `EXTINF` includes that duration rounded to whole seconds. When trustworthy duration evidence is unavailable the entry explicitly uses `-1`; properpcloud does not guess from file size or tag text.

Playlist entries are always relative to the playlist location, such as `./03 - Song.mp3` or `./CD 2/03 - Song.mp3`. No pCloud signed link, provider URL, or absolute local media path is written. Plans reject path and symlink escapes before materialization. Re-running playlist generation updates the same `.m3u8` target through a flushed sibling temporary file.

The preview is also a content snapshot. If an audio child is added to a directory that was empty during preview, or any audio child is added, removed, or renamed, or a
planned media file changes size, observed modification time, or SHA-256 before you confirm
materialization, properpcloud rejects the old plan and asks the client to reconcile and preview
again. Recursive plans also reject a changed descendant-directory set, such as a newly added
disc folder. Local workbench reviews also belong to one reconciliation revision. The shared JVM
local-workbench host registers a filesystem observer before its first scan; relevant audio
create/modify/delete events revoke old tag approvals, playlist confirmations, and queued derived
regeneration immediately, then a bounded quiet window triggers an authoritative rescan. Overflow
or an invalid observer forces a full reconciliation before the host can return to `live`.
Confirmed tag writes likewise require a new watcher-stable scan before a playlist can be derived
from the resulting tags. Expected events from properpcloud's own confirmed tag write are not
silently suppressed, so a coincident external edit cannot be hidden by an "own write" shortcut.

Recursive playlist generation is a separate opt-in action and never enables recursive tag writes. You can generate one playlist per non-empty folder, or select one-playlist-per-album mode. In the latter mode, common `CD`, `Disc`, `Disk`, or `Part` subfolders are grouped under their real parent album directory; tags may improve the playlist filename but do not choose the directory. The complete batch is previewable before any playlist file changes, and generation reports completed/total progress for large trees.

After a source/local sync, clients may submit a freshly planned batch to the playlist-only regeneration service. Repeated requests are debounced and bounded. This integration can only regenerate derived playlist files; it has no path to invoke tag staging or tag apply. A watcher/reconciliation signal cancels queued application-session work and pauses regeneration until the client has completed reconciliation and reviewed a fresh plan.

On Android, the current source model does not expose a writable local library folder, so Tag
studio does not pretend to offer direct-folder or recursive playlist writes. For batch exports,
the review screen instead exposes an explicit **Include relative UTF-8 playlist** option and
order choice; the confirmation summary shows that choice and the playlist is written only
inside the verified ZIP.

On native desktop, a selected local root exposes the shared workbench below the browser. Its
state label reports `starting`, `scanning`, `live`, `stale`, `overflow-rescanning`, or `failed`
plus the current revision. A filesystem change revokes an existing tag/playlist review before
reconciliation. Only one proposal rule can be selected for the same file and field. Select exact
field proposals, choose recursive tag permission separately when using **Local tree**, review the
tag batch, run **Dry run**, and only then use the separate **Apply reviewed tags** confirmation.
Tag dry-run/apply and recursive playlist writes show completed/total progress without displaying
the selected private root path. Playlist order, recursive playlist permission, and
one-playlist-per-album are reviewed independently and **Write reviewed playlist** has its own
confirmation. Recursive playlist consent never opts into recursive tag mutation.

The current Flatpak package deliberately leaves these local-source actions unavailable. There is
no document-portal directory lease implementation yet, and the package does not request broad
host/home filesystem access as a shortcut. pCloud, signed links, the deterministic demo cache,
and metadata staging downloads are likewise never promoted to this local-root authority.

## Safety rules

- Never infer provider media identity from a title or filename.
- Never perform silent bulk writes.
- Never replace a provider file after its observed revision changes.
- Never discard the original until replacement verification succeeds.
- Keep ordinary playback functional when metadata parsing fails.
- Keep generated playlists location-relative; never persist provider capabilities in M3U files.
