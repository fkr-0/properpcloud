package dev.properpcloud.core.model

enum class PlaybackContentMode {
    MUSIC,
    AUDIOBOOK,
}

@JvmInline
value class PlayerTargetId(val value: String) {
    init {
        require(value.isNotBlank()) { "player target id must not be blank" }
    }
}

enum class PlayerConnectivity {
    CONNECTED,
    DEGRADED,
    UNAVAILABLE,
}

enum class PlayerPlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
    ERROR,
    UNKNOWN,
}

data class PlayerTargetSnapshot(
    val id: PlayerTargetId,
    val providerId: String,
    val providerName: String,
    val displayName: String,
    val connectivity: PlayerConnectivity,
    val playbackState: PlayerPlaybackState = PlayerPlaybackState.UNKNOWN,
    val currentMediaId: String? = null,
    val currentMediaTitle: String? = null,
    val currentMediaSubtitle: String? = null,
    val controllable: Boolean = false,
    val local: Boolean = false,
    val stale: Boolean = false,
    val observedAtEpochMillis: Long = 0,
) {
    init {
        require(providerId.isNotBlank()) { "player provider id must not be blank" }
        require(providerName.isNotBlank()) { "player provider name must not be blank" }
        require(displayName.isNotBlank()) { "player display name must not be blank" }
    }
}

interface PlayerTargetProvider {
    val providerId: String
    val providerName: String
    suspend fun discoverPlayers(): List<PlayerTargetSnapshot>
}

object PlayerTargetMergePolicy {
    fun merge(snapshots: Iterable<PlayerTargetSnapshot>): List<PlayerTargetSnapshot> =
        snapshots
            .groupBy { it.id }
            .values
            .map { candidates ->
                candidates.maxWithOrNull(
                    compareBy<PlayerTargetSnapshot> { connectivityRank(it.connectivity) }
                        .thenBy { !it.stale }
                        .thenBy { it.observedAtEpochMillis },
                ) ?: error("empty player target group")
            }
            .sortedWith(compareByDescending<PlayerTargetSnapshot> { it.local }.thenBy { it.displayName.lowercase() })

    private fun connectivityRank(connectivity: PlayerConnectivity): Int = when (connectivity) {
        PlayerConnectivity.UNAVAILABLE -> 0
        PlayerConnectivity.DEGRADED -> 1
        PlayerConnectivity.CONNECTED -> 2
    }
}

data class AudiobookBookId(
    val sourceId: SourceId,
    val bookNodeId: NodeId,
)

data class AudiobookResumePoint(
    val bookId: AudiobookBookId,
    val chapterNodeId: NodeId,
    val positionMillis: Long,
    val durationMillis: Long? = null,
    val playbackSpeed: Float = 1f,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(positionMillis >= 0) { "audiobook position must not be negative" }
        require(durationMillis == null || durationMillis >= 0) { "audiobook duration must not be negative" }
        require(playbackSpeed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED) { "audiobook speed out of range" }
    }

    fun clampedPositionMillis(chapterDurationMillis: Long?): Long {
        val duration = chapterDurationMillis ?: durationMillis
        return if (duration != null && duration > 0) {
            positionMillis.coerceIn(0, duration)
        } else {
            positionMillis.coerceAtLeast(0)
        }
    }
}

data class AudiobookManualNavigationIntent(
    val fromMediaId: String,
    val targetMediaId: String,
    val expiresAtEpochMillis: Long,
)

object AudiobookPlaybackPolicy {
    const val DEFAULT_BACK_SKIP_MILLIS = 15_000L
    const val DEFAULT_FORWARD_SKIP_MILLIS = 30_000L
    const val PREVIOUS_CHAPTER_RESTART_THRESHOLD_MILLIS = 5_000L
    const val MIN_SKIP_MILLIS = 5_000L
    const val MAX_SKIP_MILLIS = 120_000L
    const val MANUAL_NAVIGATION_WINDOW_MILLIS = 5_000L

    fun manualNavigationIntent(
        fromMediaId: String?,
        targetMediaId: String?,
        nowEpochMillis: Long,
    ): AudiobookManualNavigationIntent? =
        if (
            fromMediaId.isNullOrBlank() ||
            targetMediaId.isNullOrBlank() ||
            fromMediaId == targetMediaId
        ) {
            null
        } else {
            AudiobookManualNavigationIntent(
                fromMediaId = fromMediaId,
                targetMediaId = targetMediaId,
                expiresAtEpochMillis = nowEpochMillis + MANUAL_NAVIGATION_WINDOW_MILLIS,
            )
        }

    fun matchesManualTransition(
        intent: AudiobookManualNavigationIntent?,
        previousMediaId: String?,
        nextMediaId: String?,
        nowEpochMillis: Long,
    ): Boolean =
        intent != null &&
            nowEpochMillis <= intent.expiresAtEpochMillis &&
            previousMediaId == intent.fromMediaId &&
            nextMediaId == intent.targetMediaId

    fun normalizeSkipMillis(value: Long, fallback: Long): Long =
        value.takeIf { it in MIN_SKIP_MILLIS..MAX_SKIP_MILLIS } ?: fallback

    fun previousRestartsChapter(positionMillis: Long): Boolean =
        positionMillis >= PREVIOUS_CHAPTER_RESTART_THRESHOLD_MILLIS

    fun shouldStopAtChapterBoundary(
        enabled: Boolean,
        wasPlaying: Boolean,
        previousMediaId: String?,
        nextMediaId: String?,
        manualNavigation: Boolean,
    ): Boolean =
        enabled &&
            wasPlaying &&
            !manualNavigation &&
            previousMediaId != null &&
            nextMediaId != null &&
            previousMediaId != nextMediaId
}
