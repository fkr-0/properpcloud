package dev.properpcloud.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelTest {
    private val sourceId = SourceId("test")
    private val root = AudioFolder(sourceId, NodeId("root"), null, "Root")
    private val folder = AudioFolder(sourceId, NodeId("folder"), root.id, "Folder")
    private val nested = AudioFolder(sourceId, NodeId("nested"), folder.id, "Nested")

    @Test
    fun emptyReplacementPreservesExistingQueue() {
        val previous = PlaybackQueue(entries = listOf(entry("1.mp3")), currentIndex = 0)
        assertSame(previous, QueueReducer.apply(previous, QueueOperation.REPLACE, emptyList()))
    }

    @Test
    fun playNextInsertsAfterCurrentAndKeepsSelection() {
        val previous = PlaybackQueue(entries = listOf(entry("1.mp3"), entry("4.mp3")), currentIndex = 0)
        val result = QueueReducer.apply(previous, QueueOperation.PLAY_NEXT, listOf(entry("2.mp3"), entry("3.mp3")))
        assertEquals(listOf("1.mp3", "2.mp3", "3.mp3", "4.mp3"), result.entries.map { it.track.name })
        assertEquals("1.mp3", result.current?.track?.name)
    }

    @Test
    fun stableIdentityDeduplicationPreservesFirstOccurrence() {
        val duplicate = entry("1.mp3")
        val result = QueueReducer.apply(PlaybackQueue(), QueueOperation.REPLACE, listOf(duplicate, duplicate))
        assertEquals(1, result.entries.size)
    }

    @Test
    fun recursiveAssemblerSortsAndTraversesFolders() = runTest {
        val source = FakeAudioSource(
            mapOf(
                root.id to listOf(folder),
                folder.id to listOf(track("10.mp3", folder.id), nested, track("2.mp3", folder.id)),
                nested.id to listOf(track("1.mp3", nested.id)),
            ),
        )
        val result = FolderQueueAssembler(source).build(folder.id, recursive = true)
        assertEquals(listOf("2.mp3", "10.mp3", "1.mp3"), result.entries.map { it.track.name })
        assertEquals(2, result.visitedFolders)
        assertFalse(result.isPartial)
    }

    @Test
    fun assemblerReportsPartialFailureWithoutDiscardingSuccessfulTracks() = runTest {
        val source = FakeAudioSource(mapOf(folder.id to listOf(track("1.mp3", folder.id), nested)), failOn = nested.id)
        val result = FolderQueueAssembler(source).build(folder.id, recursive = true)
        assertEquals(listOf("1.mp3"), result.entries.map { it.track.name })
        assertTrue(result.isPartial)
        assertEquals(nested.id, result.omissions.single().folderId)
    }

    @Test
    fun mediaIdentityRoundTripsOpaqueIds() {
        val encoded = MediaIdentity.encode(SourceId("pcloud"), NodeId("pcloud:file:42"))
        assertEquals(SourceId("pcloud") to NodeId("pcloud:file:42"), MediaIdentity.decode(encoded))
    }

    @Test
    fun queueRestorationPreservesSelectedItemWhenEarlierEntryIsMissing() {
        val selected = entry("2.mp3")
        val result = QueueRestoration.repair(listOf(null, selected), storedCurrentIndex = 1)

        assertEquals(listOf("2.mp3"), result.queue.entries.map { it.track.name })
        assertEquals(selected, result.queue.current)
        assertEquals(1, result.omittedCount)
        assertTrue(result.requiresRewrite)
    }

    @Test
    fun queueRestorationSelectsNearestFollowingItemWhenSelectionIsMissing() {
        val before = entry("1.mp3")
        val after = entry("3.mp3")
        val result = QueueRestoration.repair(listOf(before, null, after), storedCurrentIndex = 1)

        assertEquals(after, result.queue.current)
        assertEquals(1, result.queue.currentIndex)
    }

    @Test
    fun queueRestorationFallsBackToPreviousItemAtEnd() {
        val before = entry("1.mp3")
        val result = QueueRestoration.repair(listOf(before, null), storedCurrentIndex = 1)

        assertEquals(before, result.queue.current)
        assertEquals(0, result.queue.currentIndex)
    }

    @Test
    fun queueRestorationClearsFullyUnavailableQueue() {
        val result = QueueRestoration.repair(listOf(null, null), storedCurrentIndex = 1)

        assertTrue(result.queue.entries.isEmpty())
        assertEquals(-1, result.queue.currentIndex)
        assertEquals(2, result.omittedCount)
        assertTrue(result.requiresRewrite)
    }

    private fun entry(name: String): QueueEntry = QueueEntry(track(name, folder.id))

    private fun track(name: String, parentId: NodeId): AudioTrack = AudioTrack(
        sourceId = sourceId,
        id = NodeId("track:$name"),
        parentId = parentId,
        name = name,
    )

    private class FakeAudioSource(
        private val children: Map<NodeId, List<MediaNode>>,
        private val failOn: NodeId? = null,
    ) : AudioSource {
        override val id = SourceId("test")
        override val root = AudioFolder(id, NodeId("root"), null, "Root")

        override suspend fun list(folderId: NodeId): List<MediaNode> {
            if (folderId == failOn) error("fixture failure")
            return children[folderId].orEmpty()
        }

        override suspend fun load(nodeId: NodeId): MediaNode =
            (sequenceOf(root) + children.values.asSequence().flatten()).first { it.id == nodeId }

        override suspend fun resolveStream(trackId: NodeId) = StreamHandle("https://example.invalid/$trackId")
        override suspend fun inspect(nodeId: NodeId) = NodeInspection(mapOf("id" to nodeId.value))
    }
}
