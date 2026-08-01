# Roadmap

## Phase 0 — bootstrap

- [x] Verify public-source and API status.
- [x] Select native API plus provider-neutral player architecture.
- [x] Create Android multi-module build.
- [x] Implement natural filename and disc/track queue ordering.
- [x] Implement pCloud folder listing and renewable stream-link adapter.
- [x] Seed Media3 background playback service.

## Phase 1 — minimum useful player

- [ ] Register the development OAuth application.
- [ ] Wire `AuthorizationActivity` and encrypted token persistence.
- [ ] Implement folder breadcrumb browser.
- [ ] Add folder context actions: play, play next, append, recursive append.
- [ ] Connect queue items to Media3 and refresh expired links.
- [ ] Add now-playing → containing-folder navigation.
- [ ] Persist queue and current item across process death.
- [ ] Add unit tests with a fake `AudioSource` and integration tests with a disposable pCloud test tree.

Acceptance test:

```text
Open /Audiobooks/Book A → enqueue folder → play chapter 1 → close app → reopen →
resume position → open containing folder from player → chapter order remains 1,2,…,10.
```

## Phase 2 — long-form usability

- [ ] Room-backed per-file progress and completion state.
- [ ] Playback speed, sleep timer, skip intervals, bookmarks.
- [ ] Folder-level audiobook grouping and cover selection.
- [ ] Android Auto / media browser hierarchy.
- [ ] Offline pinning with bounded cache and explicit storage accounting.

## Phase 3 — library control without tag coercion

- [ ] Folder whitelist/blacklist rules.
- [ ] Custom tabs as saved folder roots and filters.
- [ ] Sort/group by filename, path, tag track, disc, title, duration, or date.
- [ ] Raw metadata inspector and conflict display.
- [ ] Incremental updates through pCloud `diff`.

## Phase 4 — safe metadata repair

- [ ] Pluggable metadata providers.
- [ ] Candidate matching with confidence and provenance.
- [ ] Per-file and batch dry-run diffs.
- [ ] Revision-aware write-back with post-upload verification.
- [ ] Undo using pCloud revisions where possible.

## Phase 5 — ecosystem

- [ ] WebDAV adapter.
- [ ] Android Storage Access Framework / DocumentsProvider adapter.
- [ ] Local filesystem source.
- [ ] Extract reusable pCloud Media3 data source or URL resolver library.
- [ ] F-Droid reproducible build and privacy declaration.
