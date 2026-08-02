# Progress API

Package: `dev.properpcloud.core.model`

## PlaybackProgress

```kotlin
data class PlaybackProgress(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val positionMillis: Long,
    val durationMillis: Long?,
    val playbackSpeed: Float = 1f,
    val observedAtEpochMillis: Long,
    val completed: Boolean = false,
)
```

Validation requires non-negative positions and durations and a speed in the range `0.5f..4f`.

## ResumePolicy

```kotlin
data class ResumePolicy(
    val completionRatio: Double = 0.95,
    val smartRewindShortMillis: Long = 5_000,
    val smartRewindLongMillis: Long = 15_000,
    val longInterruptionMillis: Long = 30 * 60 * 1_000,
)
```

`normalize(record, nowEpochMillis)` returns a copy suitable for playback:

1. if duration is known and the position ratio reaches the completion threshold, set `completed=true`;
2. otherwise choose the short or long rewind from elapsed observation time;
3. subtract the rewind without going below zero.

The policy does not write persistence and does not resolve media. Platform controllers decide checkpoint frequency and when a completed record should restart from zero.

## Persistence guidance

Use `(sourceId, nodeId)` as the primary key. Treat `observedAtEpochMillis` as ordering evidence rather than a globally synchronized clock. Cross-device merge is intentionally outside the current stable API.
