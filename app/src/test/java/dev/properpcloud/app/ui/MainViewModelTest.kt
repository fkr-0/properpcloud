package dev.properpcloud.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.properpcloud.app.AppContainer
import dev.properpcloud.app.playback.PlaybackController
import dev.properpcloud.app.playback.PlaybackUiState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        context.filesDir.resolve("datastore/properpcloud.preferences_pb").delete()
    }

    @Test
    fun playerDrivenCurrentItemChangePersistsSelectedStableQueueItem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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
    fun queueRestorationInstallsStoredProgressWithoutTransientZeroPosition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val playback = FakePlaybackController()
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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
        val container = AppContainer(context.applicationContext as Application, applicationScope = this)
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

    private class FakePlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow(PlaybackUiState())
        override val state: StateFlow<PlaybackUiState> = mutableState

        fun emit(value: PlaybackUiState) {
            mutableState.value = value
        }

        var lastSetQueue: PlaybackQueue? = null
        var lastSetQueuePositionMillis: Long? = null

        override fun setQueue(queue: PlaybackQueue, play: Boolean, startPositionMillis: Long) {
            lastSetQueue = queue
            lastSetQueuePositionMillis = startPositionMillis
        }
        override fun select(index: Int, play: Boolean) = Unit
        override fun clearQueue() = Unit
        override fun playPause() = Unit
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
        override fun seekBy(deltaMillis: Long) = Unit
        override fun seekTo(positionMillis: Long) = Unit
        override fun close() = Unit
    }
}
