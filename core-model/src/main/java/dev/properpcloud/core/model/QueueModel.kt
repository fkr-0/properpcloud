package dev.properpcloud.core.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class QueueOperation {
    REPLACE,
    PLAY_NEXT,
    APPEND,
}

data class QueueRestorationResult(
    val queue: PlaybackQueue,
    val omittedCount: Int,
    val requiresRewrite: Boolean,
)

object QueueRestoration {
    /**
     * Repairs a persisted queue without shifting the selected stable item when an
     * earlier entry is unavailable. Null entries retain their original positions so
     * the selected item can be mapped into the compacted queue deterministically.
     */
    fun repair(
        restoredEntries: List<QueueEntry?>,
        storedCurrentIndex: Int,
        generation: Long = 1,
    ): QueueRestorationResult {
        val surviving = restoredEntries.mapIndexedNotNull { originalIndex, entry ->
            entry?.let { originalIndex to it }
        }
        val omittedCount = restoredEntries.size - surviving.size
        if (surviving.isEmpty()) {
            val empty = PlaybackQueue(generation = generation)
            return QueueRestorationResult(
                queue = empty,
                omittedCount = omittedCount,
                requiresRewrite = omittedCount > 0 || storedCurrentIndex != -1,
            )
        }

        val selectedOriginalIndex = when {
            storedCurrentIndex < 0 -> null
            restoredEntries.getOrNull(storedCurrentIndex) != null -> storedCurrentIndex
            else -> surviving.firstOrNull { (originalIndex, _) -> originalIndex > storedCurrentIndex }?.first
                ?: surviving.last().first
        }
        val repairedCurrentIndex = selectedOriginalIndex?.let { selected ->
            surviving.indexOfFirst { (originalIndex, _) -> originalIndex == selected }
        } ?: -1
        val queue = PlaybackQueue(
            generation = generation,
            entries = surviving.map { it.second },
            currentIndex = repairedCurrentIndex,
        )
        return QueueRestorationResult(
            queue = queue,
            omittedCount = omittedCount,
            requiresRewrite = omittedCount > 0 || storedCurrentIndex != repairedCurrentIndex,
        )
    }
}

object QueueTimelineReconciliation {
    /**
     * Align the durable/application queue with the platform-player timeline by stable media ID.
     * An empty or unrelated timeline is not authoritative: this prevents a controller reconnect
     * or a failed replacement from deleting a queue before the requested timeline is installed.
     */
    fun reconcile(
        queue: PlaybackQueue,
        timelineMediaIds: List<String>,
        currentMediaId: String?,
    ): PlaybackQueue {
        if (timelineMediaIds.isEmpty() || queue.entries.isEmpty()) return select(queue, currentMediaId)
        val entriesByMediaId = queue.entries.associateBy { entry ->
            MediaIdentity.encode(entry.track.sourceId, entry.track.id)
        }
        val alignedEntries = timelineMediaIds.map { mediaId -> entriesByMediaId[mediaId] ?: return queue }
        val selectedMediaId = currentMediaId ?: queue.current?.let { entry ->
            MediaIdentity.encode(entry.track.sourceId, entry.track.id)
        }
        val alignedIndex = selectedMediaId
            ?.let(timelineMediaIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: -1
        return queue.copy(entries = alignedEntries, currentIndex = alignedIndex)
    }

    private fun select(queue: PlaybackQueue, currentMediaId: String?): PlaybackQueue {
        if (currentMediaId == null) return queue
        val index = queue.entries.indexOfFirst { entry ->
            MediaIdentity.encode(entry.track.sourceId, entry.track.id) == currentMediaId
        }
        return if (index >= 0 && index != queue.currentIndex) queue.copy(currentIndex = index) else queue
    }
}

enum class DuplicatePolicy {
    PRESERVE,
    COLLAPSE_STABLE_ID,
}

data class QueueEntry(
    val track: AudioTrack,
    val originFolderId: NodeId = track.parentId,
)

data class PlaybackQueue(
    val generation: Long = 0,
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    val current: QueueEntry?
        get() = entries.getOrNull(currentIndex)

    init {
        require(currentIndex in -1 until entries.size || (entries.isEmpty() && currentIndex == -1)) {
            "current index is outside queue"
        }
    }
}

data class QueueOmission(
    val folderId: NodeId,
    val reason: String,
)

data class QueueBuildResult(
    val entries: List<QueueEntry>,
    val visitedFolders: Int,
    val omissions: List<QueueOmission>,
) {
    val isPartial: Boolean
        get() = omissions.isNotEmpty()
}

object QueueReducer {
    fun apply(
        previous: PlaybackQueue,
        operation: QueueOperation,
        incoming: List<QueueEntry>,
        duplicatePolicy: DuplicatePolicy = DuplicatePolicy.COLLAPSE_STABLE_ID,
    ): PlaybackQueue {
        if (incoming.isEmpty()) return previous

        val normalized = deduplicate(incoming, duplicatePolicy)
        val nextEntries = when (operation) {
            QueueOperation.REPLACE -> normalized
            QueueOperation.APPEND -> deduplicate(previous.entries + normalized, duplicatePolicy)
            QueueOperation.PLAY_NEXT -> {
                val insertion = (previous.currentIndex + 1).coerceIn(0, previous.entries.size)
                deduplicate(
                    previous.entries.take(insertion) + normalized + previous.entries.drop(insertion),
                    duplicatePolicy,
                )
            }
        }
        val nextIndex = when (operation) {
            QueueOperation.REPLACE -> 0
            QueueOperation.APPEND -> if (previous.currentIndex >= 0) previous.currentIndex else 0
            QueueOperation.PLAY_NEXT -> if (previous.currentIndex >= 0) previous.currentIndex else 0
        }
        return PlaybackQueue(
            generation = previous.generation + 1,
            entries = nextEntries,
            currentIndex = nextIndex.coerceAtMost(nextEntries.lastIndex),
        )
    }

    fun remove(previous: PlaybackQueue, index: Int): PlaybackQueue {
        if (index !in previous.entries.indices) return previous
        val nextEntries = previous.entries.toMutableList().also { it.removeAt(index) }
        val nextIndex = when {
            nextEntries.isEmpty() -> -1
            index < previous.currentIndex -> previous.currentIndex - 1
            previous.currentIndex > nextEntries.lastIndex -> nextEntries.lastIndex
            else -> previous.currentIndex
        }
        return PlaybackQueue(previous.generation + 1, nextEntries, nextIndex)
    }

    fun move(previous: PlaybackQueue, from: Int, to: Int): PlaybackQueue {
        if (from !in previous.entries.indices || to !in previous.entries.indices || from == to) return previous
        val nextEntries = previous.entries.toMutableList()
        val moved = nextEntries.removeAt(from)
        nextEntries.add(to, moved)
        val current = previous.current
        val nextIndex = current?.let(nextEntries::indexOf) ?: -1
        return PlaybackQueue(previous.generation + 1, nextEntries, nextIndex)
    }

    fun select(previous: PlaybackQueue, index: Int): PlaybackQueue =
        if (index in previous.entries.indices) previous.copy(generation = previous.generation + 1, currentIndex = index)
        else previous

    private fun deduplicate(entries: List<QueueEntry>, policy: DuplicatePolicy): List<QueueEntry> {
        if (policy == DuplicatePolicy.PRESERVE) return entries
        val seen = mutableSetOf<Pair<SourceId, NodeId>>()
        return entries.filter { seen.add(it.track.sourceId to it.track.id) }
    }
}

class FolderQueueAssembler(
    private val source: AudioSource,
    private val sortPolicy: TrackSortPolicy = TrackSortPolicy(),
    private val maxFolders: Int = 10_000,
) {
    suspend fun build(folderId: NodeId, recursive: Boolean = true): QueueBuildResult {
        val pending = ArrayDeque<NodeId>().apply { add(folderId) }
        val visited = linkedSetOf<NodeId>()
        val tracks = mutableListOf<QueueEntry>()
        val omissions = mutableListOf<QueueOmission>()

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (visited.size > maxFolders) {
                omissions += QueueOmission(current, "folder limit exceeded")
                break
            }

            val children = try {
                source.list(current)
            } catch (error: Exception) {
                omissions += QueueOmission(current, error.message ?: error::class.simpleName.orEmpty())
                continue
            }
            val sorted = FolderQueueBuilder.sortNodes(children, sortPolicy)
            tracks += sorted.filterIsInstance<AudioTrack>().map { QueueEntry(it, current) }
            if (recursive) {
                sorted.filterIsInstance<AudioFolder>().forEach { pending.addLast(it.id) }
            }
        }

        return QueueBuildResult(
            entries = tracks,
            visitedFolders = visited.size,
            omissions = omissions,
        )
    }
}

object MediaIdentity {
    private const val separator = "\u001f"

    fun encode(sourceId: SourceId, nodeId: NodeId): String = sourceId.value + separator + nodeId.value

    fun decode(value: String): Pair<SourceId, NodeId> {
        val parts = value.split(separator, limit = 2)
        require(parts.size == 2) { "invalid media identity" }
        return SourceId(parts[0]) to NodeId(parts[1])
    }
}
