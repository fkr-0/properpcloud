package dev.properpcloud.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.AudioTabDefaults
import dev.properpcloud.core.model.AudioTabReducer
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.AudiobookBookId
import dev.properpcloud.core.model.AudiobookResumePoint
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackContentMode
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppPreferencesRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = AppPreferencesRepository(context)

    @After
    fun cleanUp() {
        runBlocking { repository.clearAudioTabsForTests() }
        context.filesDir.resolve("datastore/properpcloud.preferences_pb").delete()
    }

    @Test
    fun audioTabsRoundTripWithoutPersistingStreamLocations() = runTest {
        val track = AudioTrack(
            sourceId = SourceId("pcloud"),
            id = NodeId("file:9001"),
            parentId = NodeId("folder:44"),
            name = "chapter.m4b",
        )
        var tabs = AudioTabDefaults.collection()
        tabs = AudioTabReducer.updateActive(tabs) { tab ->
            tab.copy(
                queue = PlaybackQueue(entries = listOf(QueueEntry(track, track.parentId)), currentIndex = 0),
                currentFolderId = track.parentId,
                playbackPositionMillis = 73_000,
                playbackSpeed = 1.5f,
                volume = 0.65f,
                audiobookSkipBackMillis = 10_000,
                audiobookSkipForwardMillis = 60_000,
                stopAtChapterEnd = true,
                sleepTimerEndsAtEpochMillis = 987_654_321,
                activeAudiobookBookId = AudiobookBookId(track.sourceId, track.parentId),
            )
        }
        tabs = AudioTabReducer.upsertAudiobookResume(
            tabs,
            AudiobookResumePoint(
                bookId = AudiobookBookId(track.sourceId, track.parentId),
                chapterNodeId = track.id,
                positionMillis = 73_000,
                durationMillis = 120_000,
                playbackSpeed = 1.5f,
                updatedAtEpochMillis = 123_456,
            ),
        )
        tabs = AudioTabReducer.savePlaylist(tabs, "Commute")
        tabs = AudioTabReducer.switch(tabs, AudioTabId("music"), 73_000)

        repository.saveAudioTabs(tabs)
        val restored = requireNotNull(repository.loadAudioTabs())

        assertEquals(AudioTabId("music"), restored.activeTabId)
        val audiobook = restored.tabs.first { it.id == AudioTabId("audiobooks") }
        assertEquals(73_000, audiobook.playbackPositionMillis)
        assertEquals(NodeId("file:9001"), audiobook.queue.entries.single().nodeId)
        assertEquals(PlaybackContentMode.AUDIOBOOK, audiobook.contentMode)
        assertEquals(10_000, audiobook.audiobookSkipBackMillis)
        assertEquals(60_000, audiobook.audiobookSkipForwardMillis)
        assertEquals(987_654_321L, audiobook.sleepTimerEndsAtEpochMillis)
        assertEquals(NodeId("folder:44"), audiobook.activeAudiobookBookId?.bookNodeId)
        assertEquals(NodeId("file:9001"), restored.audiobookResumes.single().chapterNodeId)
        assertEquals(1.5f, restored.audiobookResumes.single().playbackSpeed)
        assertEquals("Commute", restored.playlists.single().name)
    }

    @Test
    fun directoryBookmarksPersistStableSourceAndNodeIdentity() = runTest {
        val bookmarks = listOf(
            DirectoryBookmark(SourceId("pcloud"), NodeId("folder:42"), "Dub crates"),
            DirectoryBookmark(SourceId("server"), NodeId("catalog:folder:9"), "Audiobooks"),
        )

        repository.saveDirectoryBookmarks(bookmarks + bookmarks.first())

        assertEquals(bookmarks, repository.loadDirectoryBookmarks())
    }

    @Test
    fun searchFiltersAndBoundedHistoryPersistWithoutEphemeralLocations() = runTest {
        repository.updateSearchMatchTypes(setOf(SearchMatchType.DIRECTORIES, SearchMatchType.PLAYLIST_FILES))
        repository.updatePlaybackHistoryEnabled(true)
        repository.updatePlaybackHistoryRetention(2)
        val source = SourceId("pcloud")
        listOf(
            PlaybackProgress(source, NodeId("file:1"), 10_000, 100_000, observedAtEpochMillis = 1),
            PlaybackProgress(source, NodeId("file:2"), 20_000, 100_000, observedAtEpochMillis = 2),
            PlaybackProgress(source, NodeId("file:3"), 30_000, 100_000, observedAtEpochMillis = 3),
        ).forEach { repository.saveProgress(it) }

        val settings = repository.settings.first()
        assertEquals(setOf(SearchMatchType.DIRECTORIES, SearchMatchType.PLAYLIST_FILES), settings.searchMatchTypes)
        assertEquals(true, settings.playbackHistoryEnabled)
        assertEquals(2, settings.playbackHistoryRetention)
        assertEquals(listOf("file:3", "file:2"), repository.loadPlaybackHistory().map { it.nodeId.value })
        repository.updatePlaybackHistoryEnabled(false)
    }

    @Test
    fun historyDisabledByDefaultAndMalformedLegacyPayloadsFailClosed() = runTest {
        repository.updatePlaybackHistoryEnabled(false)
        val progress = PlaybackProgress(SourceId("demo"), NodeId("demo:track:history-off"), 1_000, 10_000, observedAtEpochMillis = 5)
        repository.saveProgress(progress)
        assertEquals(emptyList<Any>(), repository.loadPlaybackHistory())
        assertEquals(emptyList<Any>(), AppPersistenceCodec.decodeQueue("not-json", 4).entries)
        assertEquals(null, AppPersistenceCodec.decodeProgress("not-json", progress.sourceId, progress.nodeId))
    }

    @Test
    fun queueReferencesRoundTripWithoutPersistingStreamCapabilities() = runTest {
        val track = AudioTrack(
            sourceId = SourceId("demo"),
            id = NodeId("demo:track:42"),
            parentId = NodeId("demo:folder:book"),
            name = "01 - Test.wav",
        )
        repository.saveQueue(
            PlaybackQueue(
                generation = 3,
                entries = listOf(QueueEntry(track, track.parentId)),
                currentIndex = 0,
            ),
        )

        val restored = repository.loadQueue()
        assertEquals(0, restored.currentIndex)
        assertEquals(SourceId("demo"), restored.entries.single().sourceId)
        assertEquals(NodeId("demo:track:42"), restored.entries.single().nodeId)
        assertEquals(NodeId("demo:folder:book"), restored.entries.single().originFolderId)
    }

    @Test
    fun progressRoundTripsWithStableIdentityAndTiming() = runTest {
        val progress = PlaybackProgress(
            sourceId = SourceId("demo"),
            nodeId = NodeId("demo:track:42"),
            positionMillis = 42_500,
            durationMillis = 120_000,
            playbackSpeed = 1.25f,
            observedAtEpochMillis = 123_456_789,
        )
        repository.saveProgress(progress)

        val restored = repository.loadProgress(progress.sourceId, progress.nodeId)
        assertNotNull(restored)
        assertEquals(progress, restored)
    }

    @Test
    fun frozenQueueFixtureDecodesAndReencodesExactly() {
        val fixtureDirectory = fixtureDirectory()
        val json = fixtureDirectory.resolve("queue.json").readText().trim()
        val currentIndex = fixtureDirectory.resolve("queue-index.txt").readText().trim().toInt()

        val stored = AppPersistenceCodec.decodeQueue(json, currentIndex)
        assertEquals(2, stored.entries.size)
        assertEquals(SourceId("demo"), stored.entries[0].sourceId)
        assertEquals(SourceId("pcloud"), stored.entries[1].sourceId)

        val tracks = stored.entries.map { reference ->
            QueueEntry(
                AudioTrack(
                    sourceId = reference.sourceId,
                    id = reference.nodeId,
                    parentId = reference.originFolderId,
                    name = reference.nodeId.value,
                ),
                reference.originFolderId,
            )
        }
        val encoded = AppPersistenceCodec.encodeQueue(
            PlaybackQueue(generation = 1, entries = tracks, currentIndex = currentIndex),
        )
        assertEquals(json, encoded.json)
        assertEquals(currentIndex, encoded.currentIndex)
    }

    @Test
    fun frozenProgressFixtureDecodesAndReencodesExactly() {
        val json = fixtureDirectory().resolve("progress.json").readText().trim()
        val first = AppPersistenceCodec.decodeProgress(
            json,
            SourceId("demo"),
            NodeId("demo:track:42"),
        )
        val second = AppPersistenceCodec.decodeProgress(
            json,
            SourceId("pcloud"),
            NodeId("file:99"),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(42_500L, first?.positionMillis)
        assertEquals(true, second?.completed)
        val reproduced = AppPersistenceCodec.upsertProgress(
            AppPersistenceCodec.upsertProgress("{}", requireNotNull(first)),
            requireNotNull(second),
        )
        assertEquals(json, reproduced)
    }

    private fun fixtureDirectory(): File =
        File(requireNotNull(System.getProperty("properpcloud.projectRoot")), "spec/fixtures/0.1.5")
}
