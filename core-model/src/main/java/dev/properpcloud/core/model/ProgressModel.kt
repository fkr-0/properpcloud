package dev.properpcloud.core.model

data class PlaybackProgress(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val positionMillis: Long,
    val durationMillis: Long?,
    val playbackSpeed: Float = 1f,
    val observedAtEpochMillis: Long,
    val completed: Boolean = false,
) {
    init {
        require(positionMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        require(playbackSpeed in 0.5f..4f)
    }
}

data class ResumePolicy(
    val completionRatio: Double = 0.95,
    val smartRewindShortMillis: Long = 5_000,
    val smartRewindLongMillis: Long = 15_000,
    val longInterruptionMillis: Long = 30 * 60 * 1_000,
) {
    fun normalize(record: PlaybackProgress, nowEpochMillis: Long): PlaybackProgress {
        val duration = record.durationMillis
        val completed = duration != null && duration > 0 && record.positionMillis.toDouble() / duration >= completionRatio
        if (completed) return record.copy(completed = true)

        val interruption = (nowEpochMillis - record.observedAtEpochMillis).coerceAtLeast(0)
        val rewind = if (interruption >= longInterruptionMillis) smartRewindLongMillis else smartRewindShortMillis
        return record.copy(positionMillis = (record.positionMillis - rewind).coerceAtLeast(0))
    }
}
