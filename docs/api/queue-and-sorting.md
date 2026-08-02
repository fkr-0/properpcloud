# Queue and sorting API

Package: `dev.properpcloud.core.model`

## Queue model

```kotlin
data class QueueEntry(
    val track: AudioTrack,
    val originFolderId: NodeId = track.parentId,
)

data class PlaybackQueue(
    val generation: Long = 0,
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)
```

The generation increments on every successful mutation. `current` is derived from `currentIndex`. Empty queues require index `-1`.

## QueueReducer

`QueueReducer` is pure and thread-agnostic.

| Function | Behavior |
| --- | --- |
| `apply(previous, REPLACE, incoming)` | Replace queue and select index zero. |
| `apply(previous, APPEND, incoming)` | Append while retaining the current selection. |
| `apply(previous, PLAY_NEXT, incoming)` | Insert after the current selection. |
| `remove(previous, index)` | Remove and adjust the current index predictably. |
| `move(previous, from, to)` | Reorder and preserve the selected entry by identity. |
| `select(previous, index)` | Select a valid index; invalid input is a no-op. |

The default `COLLAPSE_STABLE_ID` policy deduplicates on `(SourceId, NodeId)`. `PRESERVE` retains all occurrences.

## FolderQueueAssembler

```kotlin
class FolderQueueAssembler(
    private val source: AudioSource,
    private val sortPolicy: TrackSortPolicy = TrackSortPolicy(),
    private val maxFolders: Int = 10_000,
)
```

`build(folderId, recursive)` performs breadth-first traversal, checks coroutine cancellation between folders, sorts each folder deterministically, and records list failures as `QueueOmission` values.

`QueueBuildResult.isPartial` is true whenever an omission exists. Callers should surface that fact rather than silently queueing an incomplete tree.

## Sorting

`TrackSortPolicy` selects ordered keys from:

- `DISC_THEN_TRACK`
- `NATURAL_FILENAME`
- `TAGGED_TITLE`
- `MODIFIED_TIME`

`NaturalTextComparator` compares digit runs by numeric magnitude without integer overflow, then falls back to case-insensitive character order.
