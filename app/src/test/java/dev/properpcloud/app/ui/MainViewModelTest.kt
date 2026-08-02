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

        override fun setQueue(queue: PlaybackQueue, play: Boolean) = Unit
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
