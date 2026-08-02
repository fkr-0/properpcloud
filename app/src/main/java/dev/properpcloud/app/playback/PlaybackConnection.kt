package dev.properpcloud.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val playbackState: Int = Player.STATE_IDLE,
    val error: String? = null,
)

interface PlaybackController : AutoCloseable {
    val state: StateFlow<PlaybackUiState>
    fun setQueue(queue: PlaybackQueue, play: Boolean)
    fun clearQueue()
    fun select(index: Int, play: Boolean = true)
    fun playPause()
    fun skipNext()
    fun skipPrevious()
    fun seekTo(positionMillis: Long)
    fun seekBy(deltaMillis: Long)
}

class PlaybackConnection(context: Context) : PlaybackController, Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    init {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess {
                        controller = it
                        it.addListener(this)
                        updateState(it)
                        startProgressTicker()
                    }
                    .onFailure {
                        _state.value = _state.value.copy(error = "Playback controller connection failed")
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun clearQueue() = withController { player ->
        player.stop()
        player.clearMediaItems()
    }

    override fun setQueue(queue: PlaybackQueue, play: Boolean) {
        val mediaItems = queue.entries.map { entry ->
            MediaItem.Builder()
                .setMediaId(MediaIdentity.encode(entry.track.sourceId, entry.track.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.track.taggedTitle ?: entry.track.filenameStem)
                        .setSubtitle(entry.track.name)
                        .build(),
                )
                .build()
        }
        if (mediaItems.isEmpty()) return
        withController { player ->
            player.setMediaItems(mediaItems, queue.currentIndex.coerceAtLeast(0), 0)
            player.prepare()
            if (play) player.play()
        }
    }

    override fun select(index: Int, play: Boolean) = withController { player ->
        if (index in 0 until player.mediaItemCount) {
            player.seekToDefaultPosition(index)
            if (play) player.play()
        }
    }

    override fun playPause() = withController { if (it.isPlaying) it.pause() else it.play() }
    override fun skipNext() = withController { it.seekToNextMediaItem() }
    override fun skipPrevious() = withController { it.seekToPreviousMediaItem() }
    override fun seekTo(positionMillis: Long) = withController { it.seekTo(positionMillis.coerceAtLeast(0)) }
    override fun seekBy(deltaMillis: Long) = withController { it.seekTo((it.currentPosition + deltaMillis).coerceAtLeast(0)) }

    override fun onEvents(player: Player, events: Player.Events) = updateState(player)

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        _state.value = _state.value.copy(error = error.errorCodeName)
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action)
    }

    private fun updateState(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        _state.value = PlaybackUiState(
            connected = true,
            mediaId = player.currentMediaItem?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            subtitle = metadata?.subtitle?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionMillis = player.currentPosition.coerceAtLeast(0),
            durationMillis = player.duration.takeIf { it > 0 } ?: 0,
            playbackState = player.playbackState,
            error = player.playerError?.errorCodeName,
        )
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                controller?.let(::updateState)
                delay(1_000)
            }
        }
    }

    override fun close() {
        progressJob?.cancel()
        controller?.removeListener(this)
        controller = null
        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
    }
}
