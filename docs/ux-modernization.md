# Android UX modernization plan

## Product stance

properpcloud remains folder-first. The source folder, filename, stable source ID,
and stable node ID stay visible and authoritative. Modern player conventions are
added around that model; they do not replace it with a tag-only artist/album
catalog.

## Current `0.1.2` implementation

### Dedicated now-playing surface

The mini-player now opens a full now-playing destination instead of using the
queue as a substitute player screen. The screen provides:

- a large calm artwork surface with a deterministic project fallback;
- effective title plus filename/subtitle context;
- a seekable timeline with elapsed and remaining time;
- previous, rewind 15 seconds, play/pause, forward 30 seconds, and next;
- queue position and the next item;
- one-tap queue, containing-folder, and metadata actions;
- an empty state that returns directly to the folder library.

The long-lived Media3 player remains owned by `PlaybackService`. Compose renders
state and sends typed actions; it never owns or recreates the player.

### Mini-player

The persistent mini-player keeps title, filename, play/pause, next, and a thin
progress indicator. Tapping the surface opens now playing. Transport buttons keep
their own actions and do not accidentally navigate.

### Queue

Queue rows retain ordinal, title, filename, and current-item semantics while
moving secondary actions into one overflow menu:

- move up;
- move down;
- open containing folder;
- inspect metadata;
- remove.

This reduces the oversized row footprint while preserving non-drag alternatives.

### Inspector

Provider inspection is presented as a modal bottom sheet grouped into provider,
identity, timeline, media-file, and access facts. Signed URLs and credentials are
still excluded.

## Target experience

### Navigation

```text
compact phone
┌──────────────────────────────────┐
│ top app bar / folder breadcrumb  │
├──────────────────────────────────┤
│ active destination              │
│ library | queue | settings      │
├──────────────────────────────────┤
│ mini-player + progress          │
├──────────────────────────────────┤
│ bottom navigation               │
└──────────────────────────────────┘

mini-player tap → dedicated now-playing destination
now-playing folder action → exact containing folder, playback uninterrupted
```

The player is a contextual destination rather than a permanent fourth bottom-nav
item. This keeps the primary information architecture focused while making the
player a first-class surface.

### Folder library

The folder list remains the default home. Planned improvements are:

1. remembered roots and recent folders;
2. optional compact/comfortable row density;
3. inline folder artwork only when it does not obscure filenames;
4. search scoped to current folder, saved root, or account;
5. multi-select for queue, offline, and metadata proposal actions;
6. path-aware results and one-tap reveal-in-folder;
7. lazy paging and stable scroll anchoring for very large folders.

### Now playing roadmap

`0.1.2` establishes the screen and timeline. Later additions should be staged:

- chapter markers and chapter title preview;
- playback speed with per-root memory;
- sleep timer and end-of-item mode;
- bookmarks with optional notes;
- waveform only when cheaply derived and reduced-motion safe;
- artwork from embedded tags, selected release, or folder cover policy;
- output-device and audio-focus diagnostics;
- configurable previous/restart behavior and skip intervals.

### Adaptive layouts

- **Compact:** one content pane, bottom navigation, full-screen player.
- **Medium:** navigation rail, content pane, queue or inspector sheet.
- **Expanded:** navigation tree, folder content, queue/inspector side pane, and a
  wider now-playing composition.

Layout transitions must preserve source identity, folder, scroll position, queue,
selected item, playback position, and open inspector.

## State-of-the-art quality gates

### Accessibility

- 48 dp minimum touch targets;
- complete TalkBack labels for icon-only controls;
- title and filename available as separate semantics when they differ;
- no per-second screen-reader progress announcements;
- queue reorder available through menus, not drag only;
- dynamic text reflows controls instead of clipping the filename or sole action;
- state uses text/icon semantics, never color alone;
- reduced-motion settings disable nonessential transitions.

### Responsiveness

- UI action feedback within 100 ms;
- no source enumeration, tag parsing, hashing, or network call on the main thread;
- stable keys for every list row;
- cached folder content stays visible during refresh;
- progress updates are sampled to avoid recomposition storms;
- artwork decoding is bounded and thumbnail-sized.

### Error language

User-facing errors describe the recovery action, not SDK internals. Expired links
appear as reconnecting. Partial folder scans show useful results and an omission
report. Metadata conflicts state that the remote file changed and that nothing was
overwritten.

## Validation matrix

```yaml
phone:
  widths: [320dp, 360dp, 411dp]
  font_scales: [1.0, 1.3, 1.5, 2.0]
  modes: [portrait, landscape, dark, light, dynamic-color]
tablet:
  widths: [600dp, 840dp, 1200dp]
  modes: [single-window, split-screen]
accessibility:
  - TalkBack focus and action audit
  - Switch Access / keyboard alternatives
  - high-contrast and color-blind review
  - reduced motion
playback:
  - disconnected controller
  - preparing and buffering
  - unknown duration
  - seekable and non-seekable media
  - link refresh
  - queue transition
  - Activity recreation and process restoration
metadata:
  - no tags
  - malformed tags
  - conflicting title and filename
  - large artwork
  - online provider unavailable
  - revision conflict
```

## Deferred design-system work

A future refactor should split the current application composable into feature
modules and shared components for media rows, metadata values, error/empty states,
transport controls, and adaptive panes. String extraction and localization-ready
formatting remain required before claiming polished international distribution.
