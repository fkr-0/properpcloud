package dev.properpcloud.core.model

data class PlaybackHistoryEntry(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val positionMillis: Long,
    val durationMillis: Long?,
    val observedAtEpochMillis: Long,
    val completed: Boolean,
) {
    init {
        require(positionMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
    }

    companion object {
        fun from(progress: PlaybackProgress): PlaybackHistoryEntry = PlaybackHistoryEntry(
            sourceId = progress.sourceId,
            nodeId = progress.nodeId,
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            observedAtEpochMillis = progress.observedAtEpochMillis,
            completed = progress.completed,
        )
    }
}

object PlaybackHistoryPolicy {
    const val DEFAULT_RETENTION = 100
    const val MAX_RETENTION = 500

    fun normalizeRetention(value: Int): Int = value.coerceIn(1, MAX_RETENTION)

    fun upsert(
        existing: Iterable<PlaybackHistoryEntry>,
        progress: PlaybackProgress,
        retention: Int,
    ): List<PlaybackHistoryEntry> {
        val next = PlaybackHistoryEntry.from(progress)
        return (sequenceOf(next) + existing.asSequence().filterNot {
            it.sourceId == next.sourceId && it.nodeId == next.nodeId
        })
            .sortedByDescending { it.observedAtEpochMillis }
            .take(normalizeRetention(retention))
            .toList()
    }
}
