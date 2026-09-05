package dev.properpcloud.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTabsTest {
    private val sourceId = SourceId("pcloud")
    private val root = AudioFolder(sourceId, NodeId("folder:0"), null, "pCloud")
    private val hb = AudioFolder(sourceId, NodeId("folder:1"), root.id, "hb")
    private val sciFi = AudioFolder(sourceId, NodeId("folder:2"), hb.id, "Sci-Fi")

    @Test
    fun `defaults expose the requested content tabs with normalized roots`() {
        val defaults = AudioTabDefaults.collection()

        assertEquals(
            listOf("Hörbücher", "Musik", "DJ/Auflege", "Sci-Fi", "Krimi", "Fantasy"),
            defaults.tabs.map { it.definition.name },
        )
        assertEquals("hb/Sci-Fi", defaults.tabs.first { it.definition.id.value == "sci-fi" }.definition.rootPath)
        assertEquals(AudioTabId("audiobooks"), defaults.activeTabId)
    }

    @Test
    fun `switch snapshots position without starting or mutating another tab queue`() {
        val track = AudioTrack(sourceId, NodeId("file:1"), hb.id, "chapter.m4b")
        val initial = AudioTabDefaults.collection().let { collection ->
            AudioTabReducer.updateActive(collection) { tab ->
                tab.copy(queue = PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0))
            }
        }

        val switched = AudioTabReducer.switch(initial, AudioTabId("music"), 42_500)

        assertEquals(42_500, switched.tabs.first { it.definition.id.value == "audiobooks" }.playbackPositionMillis)
        assertTrue(switched.active.queue.entries.isEmpty())
        assertFalse(switched.active.shuffle)
    }

    @Test
    fun `named playlists copy stable queue entries into the active tab`() {
        val track = AudioTrack(sourceId, NodeId("file:1"), hb.id, "chapter.m4b")
        var collection = AudioTabDefaults.collection()
        collection = AudioTabReducer.updateActive(collection) { tab ->
            tab.copy(queue = PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0))
        }
        collection = AudioTabReducer.savePlaylist(collection, "Night book")
        collection = AudioTabReducer.switch(collection, AudioTabId("music"), 12_000)
        collection = AudioTabReducer.loadPlaylist(collection, "Night book")

        assertEquals(track.id, collection.active.queue.current?.track?.id)
        assertEquals(0, collection.active.playbackPositionMillis)
    }

    @Test
    fun `relative pcloud roots resolve by tree traversal without persisting stream urls`() = runTest {
        val source = FakeSource(
            mapOf(
                root.id to listOf(hb),
                hb.id to listOf(sciFi),
            ),
        )

        assertEquals(sciFi, source.resolveFolderPath("/hb/Sci-Fi/"))
        assertEquals(root, source.resolveFolderPath(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tab roots reject traversal`() {
        normalizeAudioRootPath("hb/../private")
    }

    @Test
    fun `size sort is deterministic with missing sizes last`() {
        val parent = hb.id
        val small = AudioTrack(sourceId, NodeId("file:small"), parent, "z.mp3", sizeBytes = 10)
        val large = AudioTrack(sourceId, NodeId("file:large"), parent, "a.mp3", sizeBytes = 100)
        val unknown = AudioTrack(sourceId, NodeId("file:unknown"), parent, "m.mp3")

        assertEquals(
            listOf(small.id, large.id, unknown.id),
            FolderQueueBuilder.tracksOnly(
                listOf(unknown, large, small),
                TrackSortPolicy(listOf(TrackSortKey.SIZE, TrackSortKey.NATURAL_FILENAME)),
            ).map { it.id },
        )
    }

    private inner class FakeSource(private val children: Map<NodeId, List<MediaNode>>) : AudioSource {
        override val id = sourceId
        override val root = this@AudioTabsTest.root
        override suspend fun list(folderId: NodeId): List<MediaNode> = children[folderId].orEmpty()
        override suspend fun load(nodeId: NodeId): MediaNode =
            (sequenceOf(root) + children.values.asSequence().flatten()).first { it.id == nodeId }
        override suspend fun resolveStream(trackId: NodeId) = error("not required")
        override suspend fun inspect(nodeId: NodeId) = NodeInspection(emptyMap())
    }
}
