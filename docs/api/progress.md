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

`normalize(record, nowEpochMillis)` retains the legacy record-normalization behavior. New session restoration should use `resumePositionMillis(record, nowEpochMillis, knownDurationMillis)`:

1. clamp an impossible stored position to a currently known positive duration when available;
2. if the record is explicitly completed or the clamped position reaches the 95% completion threshold, restart at `0`;
3. otherwise choose the short or long rewind from elapsed observation time;
4. subtract the rewind without going below zero.

The policy does not write persistence and does not resolve media.

## Checkpoint policy

`PlaybackCheckpointPolicy` coalesces active playback writes. The default periodic boundary is approximately 30 seconds by elapsed observation time or position movement. The first observation is saved, and important event boundaries force or trigger a save: pause transition, track/queue transition, playback error, background/task removal, service/application shutdown where available, and completion. Repeated one-second ticker observations while already paused do not produce one write per tick.

The player state always supplies a stable `MediaIdentity` (`SourceId` + `NodeId`) and the checkpoint policy verifies that identity against the active queue before creating `PlaybackProgress`.

## Playback history

Playback history is deliberately separate from session/crash restoration. It is disabled by default. When enabled, a progress checkpoint also upserts a history row containing only stable source/node identity, position, optional duration, observation time, and completion. Default retention is 100 identities and the hard maximum is 500. Expiring URLs, stream handles, credentials, provider response bodies, and complete private paths are not history fields.

## Persistence guidance

Use `(sourceId, nodeId)` as the primary key. Treat `observedAtEpochMillis` as ordering evidence rather than a globally synchronized clock. Cross-device merge is intentionally outside the current stable API.
