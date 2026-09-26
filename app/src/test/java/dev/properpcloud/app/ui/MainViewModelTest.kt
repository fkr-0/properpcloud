package dev.properpcloud.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.properpcloud.app.AppContainer
import dev.properpcloud.app.data.AppPreferencesRepository
import dev.properpcloud.app.data.GeneratedTestAudioSource
import dev.properpcloud.app.playback.PlaybackController
import dev.properpcloud.app.playback.PlaybackUiState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTabDefaults
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.AudioTabReducer
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.AudiobookBookId
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        Dispatchers.resetMain()
        runBlocking { AppPreferencesRepository(context).clearAudioTabsForTests() }
        context.filesDir.resolve("datastore/properpcloud.preferences_pb").delete()
    }

    @Test
    fun switchingAudioTabsPausesAndRestoresNextQueueWithoutAutoplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val tracks = source.list(folder.id).filterIsInstance<AudioTrack>().take(2)
        var tabs = AudioTabDefaults.collection()
        tabs = AudioTabReducer.updateActive(tabs) { tab ->
            tab.copy(queue = PlaybackQueue(entries = listOf(QueueEntry(tracks[0])), currentIndex = 0))
        }
        tabs = AudioTabReducer.switch(tabs, AudioTabId("music"), 23_000)
        tabs = AudioTabReducer.updateActive(tabs) { tab ->
            tab.copy(
                queue = PlaybackQueue(entries = listOf(QueueEntry(tracks[1])), currentIndex = 0),
                playbackPositionMillis = 9_000,
            )
        }
        tabs = AudioTabReducer.switch(tabs, AudioTabId("audiobooks"), 9_000)
        container.preferences.saveAudioTabs(tabs)

        withViewModel(container, playback) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.queue.current?.track?.id == tracks[0].id) break
                Thread.sleep(10)
            }
            playback.pauseCalls = 0
            playback.lastSetQueuePlay = null

            viewModel.switchAudioTab(AudioTabId("music"))
            advanceUntilIdle()

            assertEquals(1, playback.pauseCalls)
            assertEquals(false, playback.lastSetQueuePlay)
            assertEquals(9_000L, playback.lastSetQueuePositionMillis)
            assertEquals(tracks[1].id, playback.lastSetQueue?.current?.track?.id)
            assertEquals(AudioTabId("music"), viewModel.state.value.audioTabs.activeTabId)
            assertEquals(tracks[1].id, viewModel.state.value.queue.current?.track?.id)
        }
    }

    @Test
    fun playerDrivenCurrentItemChangePersistsSelectedStableQueueItem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val tracks = source.list(folder.id).filterIsInstance<AudioTrack>().take(2)
        container.preferences.saveQueue(
            PlaybackQueue(entries = tracks.map(::QueueEntry), currentIndex = 0),
        )

        withViewModel(container, playback) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.queue.entries.size == 2) break
                Thread.sleep(10)
            }
            val second = tracks[1]
            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = MediaIdentity.encode(second.sourceId, second.id),
                    positionMillis = 500,
                    durationMillis = second.durationMillis ?: 5_000,
                    isPlaying = true,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.queue.currentIndex)
            assertEquals(second.id, viewModel.state.value.queue.current?.track?.id)
            var stored = container.preferences.loadQueue()
            for (attempt in 0 until 200) {
                if (stored.currentIndex == 1) break
                advanceUntilIdle()
                Thread.sleep(10)
                stored = container.preferences.loadQueue()
            }
            assertEquals(1, stored.currentIndex)
            assertEquals(second.id, stored.entries[1].nodeId)
        }
    }

    @Test
    fun controllerDisconnectAndRebindPreservePlayerDrivenStableSelectionWithoutAutoplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val tracks = source.list(folder.id).filterIsInstance<AudioTrack>().take(2)
        container.preferences.saveQueue(
            PlaybackQueue(entries = tracks.map(::QueueEntry), currentIndex = 0),
        )

        withViewModel(container, playback) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.queue.entries.size == 2) break
                Thread.sleep(10)
            }
            val secondMediaId = MediaIdentity.encode(tracks[1].sourceId, tracks[1].id)
            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = secondMediaId,
                    positionMillis = 900,
                    durationMillis = 5_000,
                    isPlaying = false,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.queue.currentIndex)

            playback.emit(PlaybackUiState(connected = false, error = "controller disconnected"))
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.queue.currentIndex)
            assertEquals(tracks[1].id, viewModel.state.value.queue.current?.track?.id)

            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = secondMediaId,
                    positionMillis = 900,
                    durationMillis = 5_000,
                    isPlaying = false,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.state.value.queue.currentIndex)
            assertEquals(false, viewModel.state.value.playback.isPlaying)
            var stored = container.preferences.loadQueue()
            for (attempt in 0 until 200) {
                if (stored.currentIndex == 1) break
                advanceUntilIdle()
                Thread.sleep(10)
                stored = container.preferences.loadQueue()
            }
            assertEquals(1, stored.currentIndex)
        }
    }

    @Test
    fun playerTimelineCompactionConvergesUiAndPersistedQueueAfterTerminalOmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val tracks = source.list(folder.id).filterIsInstance<AudioTrack>().take(3)
        container.preferences.saveQueue(PlaybackQueue(entries = tracks.map(::QueueEntry), currentIndex = 0))

        withViewModel(container, playback) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.queue.entries.size == 3) break
                Thread.sleep(10)
            }
            val good = tracks[1]
            val later = tracks[2]
            val goodId = MediaIdentity.encode(good.sourceId, good.id)
            val laterId = MediaIdentity.encode(later.sourceId, later.id)
            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = goodId,
                    timelineMediaIds = listOf(goodId, laterId),
                    positionMillis = 250,
                    durationMillis = 5_000,
                    isPlaying = true,
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf(good.id, later.id), viewModel.state.value.queue.entries.map { it.track.id })
            assertEquals(0, viewModel.state.value.queue.currentIndex)
            var stored = container.preferences.loadQueue()
            for (attempt in 0 until 200) {
                if (stored.entries.size == 2 && stored.currentIndex == 0) break
                advanceUntilIdle()
                Thread.sleep(10)
                stored = container.preferences.loadQueue()
            }
            assertEquals(listOf(good.id, later.id), stored.entries.map { it.nodeId })
            assertEquals(0, stored.currentIndex)
        }
    }

    @Test
    fun queueRestorationInstallsStoredProgressWithoutTransientZeroPosition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Audiobooks" }
        val book = source.list(folder.id).filterIsInstance<AudioFolder>().single()
        val track = source.list(book.id).filterIsInstance<AudioTrack>().first()
        container.preferences.saveQueue(
            PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0),
        )
        container.preferences.saveProgress(
            PlaybackProgress(
                sourceId = track.sourceId,
                nodeId = track.id,
                positionMillis = 6_000,
                durationMillis = 8_000,
                observedAtEpochMillis = System.currentTimeMillis(),
            ),
        )

        withViewModel(container, playback) {
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (playback.lastSetQueue?.current?.track?.id == track.id) break
                Thread.sleep(10)
            }

            assertEquals(track.id, playback.lastSetQueue?.current?.track?.id)
            assertEquals(1_000L, playback.lastSetQueuePositionMillis)
            assertEquals(
                6_000L,
                container.preferences.loadProgress(track.sourceId, track.id)?.positionMillis,
            )
        }
    }

    @Test
    fun partialQueueRestorationPreservesSelectedStableItemAndRewritesStorage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val selected = source.list(folder.id).filterIsInstance<AudioTrack>().first()
        val missing = AudioTrack(
            sourceId = SourceId("missing-source"),
            id = NodeId("missing-track"),
            parentId = NodeId("missing-folder"),
            name = "missing.flac",
        )
        container.preferences.saveQueue(
            PlaybackQueue(
                entries = listOf(QueueEntry(missing), QueueEntry(selected)),
                currentIndex = 1,
            ),
        )

        withViewModel(container, playback) { viewModel ->
            var restoredState: AppUiState? = null
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                restoredState = viewModel.state.value.takeIf {
                    it.queue.current?.track?.id == selected.id
                }
                if (restoredState != null) break
                Thread.sleep(10)
            }
            val resolved = requireNotNull(restoredState) {
                "partial queue restoration did not preserve the selected stable item"
            }

            assertEquals(selected.id, resolved.queue.current?.track?.id)
            val stored = container.preferences.loadQueue()
            assertEquals(listOf(selected.id), stored.entries.map { it.nodeId })
            assertEquals(0, stored.currentIndex)
            assertTrue(resolved.message.orEmpty().contains("1 unavailable"))
        }
    }

    @Test
    fun playbackControllerFailureBecomesActionableUiMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        withViewModel(container, playback) { viewModel ->
            advanceUntilIdle()

            playback.emit(PlaybackUiState(error = "controller connection failed"))
            advanceUntilIdle()

            assertEquals(
                "Playback controller reported: controller connection failed",
                viewModel.state.value.message,
            )
        }
    }

    @Test
    fun lifecycleFlushPersistsLatestSubThresholdPosition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        withViewModel(container, playback) { viewModel ->
            advanceUntilIdle()
            val source = container.sources.current.value
            val folder = source.list(source.root.id)
                .filterIsInstance<AudioFolder>()
                .first { it.name == "Numbered tracks" }
            val track = source.list(folder.id).filterIsInstance<AudioTrack>().first()
            viewModel.playTrack(track)
            advanceUntilIdle()
            val mediaId = MediaIdentity.encode(track.sourceId, track.id)

            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = mediaId,
                    positionMillis = 1_000,
                    durationMillis = 30_000,
                    isPlaying = true,
                ),
            )
            advanceUntilIdle()
            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = mediaId,
                    positionMillis = 1_500,
                    durationMillis = 30_000,
                    isPlaying = true,
                ),
            )
            advanceUntilIdle()
            assertEquals(track.id, viewModel.state.value.queue.current?.track?.id)
            assertEquals(mediaId, viewModel.state.value.playback.mediaId)
            requireNotNull(viewModel.flushPlaybackProgress()).join()

            assertEquals(
                1_500L,
                container.preferences.loadProgress(track.sourceId, track.id)?.positionMillis,
            )
        }
    }

    @Test
    fun staleStoredQueueIsClearedAndReported() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val missingTrack = AudioTrack(
            sourceId = SourceId("missing-source"),
            id = NodeId("missing-track"),
            parentId = NodeId("missing-folder"),
            name = "missing.flac",
        )
        container.preferences.saveQueue(
            PlaybackQueue(entries = listOf(QueueEntry(missingTrack)), currentIndex = 0),
        )

        withViewModel(container, playback) { viewModel ->
            var restoredState: AppUiState? = null
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                restoredState = viewModel.state.value.takeIf {
                    it.message.orEmpty().contains("could not be restored")
                }
                if (restoredState != null) break
                Thread.sleep(10)
            }
            val resolved = requireNotNull(restoredState) {
                "queue restoration did not report its stale persisted entry"
            }

            assertTrue(resolved.queue.entries.isEmpty())
            assertTrue(resolved.message.orEmpty().contains("could not be restored"))
            assertTrue(container.preferences.loadQueue().entries.isEmpty())
        }
    }

    @Test
    fun audiobookProgressFlushPersistsResumePointAndPlaybackSpeed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = GeneratedTestAudioSource(context),
        )
        val source = container.sources.current.value
        val audiobookRoot = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Audiobooks" }
        val book = source.list(audiobookRoot.id).filterIsInstance<AudioFolder>().single()
        val track = source.list(book.id).filterIsInstance<AudioTrack>().first()
        val bookId = AudiobookBookId(track.sourceId, book.id)
        val tabs = AudioTabReducer.updateActive(AudioTabDefaults.collection()) { tab ->
            tab.copy(
                queue = PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0),
                playbackSpeed = 1.35f,
                activeAudiobookBookId = bookId,
            )
        }
        container.preferences.saveAudioTabs(tabs)

        withViewModel(container, playback) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.queue.current?.track?.id == track.id) break
                Thread.sleep(10)
            }
            val mediaId = MediaIdentity.encode(track.sourceId, track.id)
            playback.emit(
                PlaybackUiState(
                    connected = true,
                    mediaId = mediaId,
                    positionMillis = 4_250,
                    durationMillis = 30_000,
                    playbackSpeed = 1.35f,
                    isPlaying = true,
                ),
            )
            advanceUntilIdle()
            requireNotNull(viewModel.flushPlaybackProgress()).join()

            var resume = container.preferences.loadAudioTabs()
                ?.audiobookResumes
                ?.firstOrNull { it.bookId == bookId }
            for (attempt in 0 until 200) {
                if (resume?.positionMillis == 4_250L) break
                advanceUntilIdle()
                Thread.sleep(10)
                resume = container.preferences.loadAudioTabs()
                    ?.audiobookResumes
                    ?.firstOrNull { it.bookId == bookId }
            }

            val stored = requireNotNull(resume)
            assertEquals(track.id, stored.chapterNodeId)
            assertEquals(4_250L, stored.positionMillis)
            assertEquals(30_000L, stored.durationMillis)
            assertEquals(1.35f, stored.playbackSpeed)
        }
    }

    @Test
    fun libraryRefreshFailurePreservesVisibleNodesAndRedactsProviderDetails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FailingTestAudioSource()
        val track = AudioTrack(
            sourceId = source.id,
            id = NodeId("none:track:one"),
            parentId = source.root.id,
            name = "chapter.m4b",
        )
        source.children = listOf(track)
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = source,
        )

        withViewModel(container, FakePlaybackController()) { viewModel ->
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (viewModel.state.value.nodes.any { it.id == track.id }) break
                Thread.sleep(10)
            }
            assertEquals(listOf(track.id), viewModel.state.value.nodes.map { it.id })

            source.listFailure = SecurityException("/private/library/token=secret")
            viewModel.refresh()
            for (attempt in 0 until 200) {
                advanceUntilIdle()
                if (!viewModel.state.value.refreshing && viewModel.state.value.errorMessage != null) break
                Thread.sleep(10)
            }

            assertEquals(listOf(track.id), viewModel.state.value.nodes.map { it.id })
            assertFalse(viewModel.state.value.refreshing)
            assertEquals(
                "Could not load folder: permission denied. Check source access and retry.",
                viewModel.state.value.errorMessage,
            )
            assertFalse(viewModel.state.value.errorMessage.orEmpty().contains("/private"))
            assertFalse(viewModel.state.value.errorMessage.orEmpty().contains("token=secret"))
        }
    }

    @Test
    fun openContainingFolderTurnsProviderFailureIntoRecoverableUiMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FailingTestAudioSource()
        val container = AppContainer(
            context.applicationContext as Application,
            applicationScope = this,
            disconnectedSource = source,
        )
        val track = AudioTrack(
            sourceId = source.id,
            id = NodeId("none:track:book"),
            parentId = source.root.id,
            name = "book.m4b",
        )

        withViewModel(container, FakePlaybackController()) { viewModel ->
            advanceUntilIdle()
            source.loadFailure = SecurityException("/private/book/path")
            viewModel.openContainingFolder(track)
            advanceUntilIdle()

            assertEquals(
                "Could not open containing folder: permission denied. Check source access and retry.",
                viewModel.state.value.message,
            )
            assertFalse(viewModel.state.value.message.orEmpty().contains("/private"))
        }
    }

    @Test
    fun sourceFailureMessagesClassifyOfflineAndUnavailableWithoutRawDetails() {
        assertEquals(
            "Could not load folder: source is temporarily unavailable. Check the connection and retry.",
            java.net.ConnectException("host=private.example").sourceUserMessage("Could not load folder"),
        )
        assertEquals(
            "Could not load folder. Retry, reconnect the source, or check its permissions.",
            IllegalStateException("token=secret").sourceUserMessage("Could not load folder"),
        )
    }

    private suspend fun withViewModel(
        container: AppContainer,
        playback: PlaybackController,
        block: suspend (MainViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        val application = context.applicationContext as Application
        val viewModel = ViewModelProvider(
            store,
            MainViewModel.Factory(application, container, playback),
        )[MainViewModel::class.java]
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    private class FailingTestAudioSource : AudioSource {
        override val id = SourceId("none")
        override val root = AudioFolder(id, NodeId("none:folder:root"), null, "Test source")
        var children: List<MediaNode> = emptyList()
        var listFailure: Throwable? = null
        var loadFailure: Throwable? = null

        override suspend fun list(folderId: NodeId): List<MediaNode> {
            listFailure?.let { throw it }
            require(folderId == root.id)
            return children
        }

        override suspend fun load(nodeId: NodeId): MediaNode {
            loadFailure?.let { throw it }
            if (nodeId == root.id) return root
            return children.first { it.id == nodeId }
        }

        override suspend fun resolveStream(trackId: NodeId): StreamHandle =
            error("not used by this test source")

        override suspend fun inspect(nodeId: NodeId): NodeInspection =
            NodeInspection(mapOf("test" to "true"))
    }

    private class FakePlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> = mutableState

        fun emit(value: PlaybackUiState) {
            mutableState.value = value
        }

        var lastSetQueue: PlaybackQueue? = null
        var lastSetQueuePositionMillis: Long? = null
        var lastSetQueuePlay: Boolean? = null
        var pauseCalls: Int = 0

        override fun setQueue(queue: PlaybackQueue, play: Boolean, startPositionMillis: Long) {
            lastSetQueue = queue
            lastSetQueuePositionMillis = startPositionMillis
            lastSetQueuePlay = play
        }
        override fun select(index: Int, play: Boolean) = Unit
        override fun clearQueue() = Unit
        override fun pause() {
            pauseCalls += 1
        }
        override fun playPause() = Unit
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
        override fun seekBy(deltaMillis: Long) = Unit
        override fun seekTo(positionMillis: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeatMode(mode: dev.properpcloud.core.model.PlayerRepeatMode) = Unit
        override fun close() = Unit
    }
}
