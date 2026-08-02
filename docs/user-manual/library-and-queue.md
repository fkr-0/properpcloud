# Library and queue

## Folder-first browsing

properpcloud shows provider folders and audio files even when tags are incomplete. A file is considered playable when its MIME type starts with `audio/` or its filename has a recognized audio extension.

The default ordering is:

1. folders before tracks;
2. disc number;
3. track number;
4. natural filename order, so `2` sorts before `10`.

Alternative sort keys include tagged title and modified time.

## Queue actions

| Action | Result |
| --- | --- |
| Play now / Replace | Replaces the queue with the selected track or folder snapshot and starts at the first entry. |
| Play next | Inserts entries immediately after the current track. |
| Append | Adds entries to the queue tail. |
| Remove | Deletes one queue entry without deleting the provider file. |
| Move | Reorders the snapshot while preserving the current media identity. |

By default, duplicate stable source/node identities collapse to their first occurrence. This prevents repeated folder scans from multiplying the same track while preserving deterministic ordering.

## Direct versus recursive folder playback

- **Direct children** includes audio files immediately inside the selected folder.
- **Recursive** traverses descendant folders breadth-first, applies deterministic sorting within each folder, and records inaccessible folders as omissions.

Queue assembly is cancellable and bounded. A partial result is explicit; permission or network failures are not silently presented as a complete folder.

## Queue persistence

Persistent queue entries contain only:

```text
source ID
node ID
origin folder ID
current queue index
```

Temporary HTTPS links are resolved again immediately before playback and never serialized into queue state.

## Return to the containing folder

Use **Show folder** in the queue or player area. The application switches to the track's source and opens its stable parent folder. This works even when the track entered the queue through a recursive ancestor scan.
