package dev.properpcloud.core.model

import kotlin.math.abs

data class PlaybackObservation(
    val mediaId: String?,
    val positionMillis: Long,
    val durationMillis: Long?,
    val playbackSpeed: Float = 1f,
    val isPlaying: Boolean,
) {
    init {
        require(positionMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        require(playbackSpeed in 0.5f..4f)
    }
}

data class PlaybackCheckpointCursor(
    val mediaId: String? = null,
    val positionMillis: Long = -1,
    val observedAtEpochMillis: Long = -1,
    val wasPlaying: Boolean? = null,
)

data class PlaybackCheckpointDecision(
    val progress: PlaybackProgress?,
    val cursor: PlaybackCheckpointCursor,
)

class PlaybackCheckpointPolicy(
    private val minimumPositionDeltaMillis: Long = 30_000,
    private val minimumCheckpointIntervalMillis: Long = 30_000,
    private val completionRatio: Double = 0.95,
) {
    init {
        require(minimumPositionDeltaMillis > 0)
        require(minimumCheckpointIntervalMillis > 0)
        require(completionRatio in 0.5..1.0)
    }

    fun evaluate(
        queue: PlaybackQueue,
        observation: PlaybackObservation,
        cursor: PlaybackCheckpointCursor,
        observedAtEpochMillis: Long,
        force: Boolean = false,
    ): PlaybackCheckpointDecision {
        val mediaId = observation.mediaId
            ?: return PlaybackCheckpointDecision(null, cursor)
        val identity = runCatching { MediaIdentity.decode(mediaId) }.getOrNull()
            ?: return PlaybackCheckpointDecision(null, cursor)
        val entry = queue.entries.firstOrNull {
            it.track.sourceId == identity.first && it.track.id == identity.second
        } ?: return PlaybackCheckpointDecision(null, cursor)

        val shouldPersist = force ||
            cursor.mediaId != mediaId ||
            abs(observation.positionMillis - cursor.positionMillis) >= minimumPositionDeltaMillis ||
            cursor.observedAtEpochMillis < 0 ||
            observedAtEpochMillis - cursor.observedAtEpochMillis >= minimumCheckpointIntervalMillis ||
            (!observation.isPlaying && cursor.wasPlaying != false)
        if (!shouldPersist) {
            return PlaybackCheckpointDecision(null, cursor.copy(wasPlaying = observation.isPlaying))
        }

        val duration = observation.durationMillis
            ?.takeIf { it > 0 }
            ?: entry.track.durationMillis
        val completed = duration != null && duration > 0 &&
            observation.positionMillis.toDouble() / duration >= completionRatio
        val progress = PlaybackProgress(
            sourceId = entry.track.sourceId,
            nodeId = entry.track.id,
            positionMillis = observation.positionMillis,
            durationMillis = duration,
            playbackSpeed = observation.playbackSpeed,
            observedAtEpochMillis = observedAtEpochMillis,
            completed = completed,
        )
        return PlaybackCheckpointDecision(
            progress = progress,
            cursor = PlaybackCheckpointCursor(mediaId, observation.positionMillis, observedAtEpochMillis, observation.isPlaying),
        )
    }
}
