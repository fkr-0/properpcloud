package dev.properpcloud.metadata.tags

/**
 * Explicit playlist-only post-sync regeneration boundary.
 *
 * A caller may submit a freshly reviewed playlist batch after a local/source reconciliation.
 * Repeated submissions under the same key are debounced. The service intentionally has no
 * scanner, [AudioTagToolkit], or tag-apply dependency, so it cannot mutate media metadata.
 */
class FolderPlaylistRegenerationService(
    private val writer: FolderPlaylistWriter = FolderPlaylistWriter(),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxPendingBatches: Int = DEFAULT_MAX_PENDING_BATCHES,
    private val maxPlaylistsPerBatch: Int = DEFAULT_MAX_PLAYLISTS_PER_BATCH,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(debounceMillis in 0..MAX_DEBOUNCE_MILLIS) { "playlist debounce is outside the supported bound" }
        require(maxPendingBatches in 1..MAX_PENDING_BATCH_LIMIT) { "playlist pending-batch bound is invalid" }
        require(maxPlaylistsPerBatch in 1..MAX_PLAYLISTS_PER_BATCH_LIMIT) { "playlist batch-size bound is invalid" }
    }

    data class ScheduledRegeneration(
        val key: String,
        val dueAtEpochMillis: Long,
        val playlistCount: Int,
        val replacedPendingRequest: Boolean,
    )

    private data class PendingBatch(
        val key: String,
        val plan: FolderPlaylistBatchPlan,
        val dueAtEpochMillis: Long,
    )

    private val pending = linkedMapOf<String, PendingBatch>()

    /**
     * Schedule a freshly planned derived-playlist batch. Scheduling performs no filesystem
     * write. A repeated key replaces the older request and restarts the quiet window.
     */
    @Synchronized
    fun schedule(
        key: String,
        plan: FolderPlaylistBatchPlan,
        nowEpochMillis: Long = clockMillis(),
    ): ScheduledRegeneration {
        require(key.isNotBlank()) { "playlist regeneration key must not be blank" }
        require(plan.playlistCount <= maxPlaylistsPerBatch) {
            "playlist regeneration batch exceeds the configured playlist bound"
        }
        val replaced = pending.containsKey(key)
        if (!replaced) {
            require(pending.size < maxPendingBatches) {
                "playlist regeneration queue is full; reconcile before scheduling more work"
            }
        }
        val dueAt = Math.addExact(nowEpochMillis, debounceMillis)
        pending[key] = PendingBatch(key, plan, dueAt)
        return ScheduledRegeneration(key, dueAt, plan.playlistCount, replaced)
    }

    /**
     * Materialize only requests whose quiet window has elapsed. Failed writes remain pending
     * so an operator/caller can reconcile and replace the request rather than losing state.
     */
    @Synchronized
    fun flushDue(
        nowEpochMillis: Long = clockMillis(),
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): List<FolderPlaylistBatchWriteResult> {
        val due = pending.values
            .filter { it.dueAtEpochMillis <= nowEpochMillis }
            .sortedWith(compareBy<PendingBatch> { it.dueAtEpochMillis }.thenBy { it.key })
        val results = mutableListOf<FolderPlaylistBatchWriteResult>()
        due.forEach { item ->
            val result = writer.writeBatch(item.plan, onProgress)
            pending.remove(item.key)
            results += result
        }
        return results
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    /**
     * Revoke all queued derived work after a higher-level reconciliation boundary reports that
     * its reviewed snapshots are no longer current. This only forgets playlist plans; it never
     * touches media metadata or filesystem bytes.
     */
    @Synchronized
    fun cancelAll(): Int {
        val cancelled = pending.size
        pending.clear()
        return cancelled
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
        const val DEFAULT_MAX_PENDING_BATCHES = 16
        const val DEFAULT_MAX_PLAYLISTS_PER_BATCH = 256
        private const val MAX_DEBOUNCE_MILLIS = 60_000L
        private const val MAX_PENDING_BATCH_LIMIT = 1_024
        private const val MAX_PLAYLISTS_PER_BATCH_LIMIT = 10_000
    }
}
