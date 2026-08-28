package dev.properpcloud.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.PlaybackFailureRecovery
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.PlaybackRecoveryPolicy
import dev.properpcloud.core.model.SignedLinkRetryGate
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
    fun setQueue(queue: PlaybackQueue, play: Boolean, startPositionMillis: Long = 0)
    fun clearQueue()
    fun select(index: Int, play: Boolean = true)
    fun playPause()
    fun skipNext()
    fun skipPrevious()
    fun seekTo(positionMillis: Long)
    fun seekBy(deltaMillis: Long)
}

internal fun isRetriablePlaybackFailure(errorCode: Int, responseCode: Int?): Boolean = when {
    responseCode != null ->
        PlaybackRecoveryPolicy.forHttpStatus(responseCode) == PlaybackFailureRecovery.REFRESH_STREAM_LOCATION
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> true
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
    else -> false
}

@UnstableApi
class PlaybackConnection(context: Context) : PlaybackController, Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var lastQueue: PlaybackQueue = PlaybackQueue()
    private var explicitRecoveryEligible = false
    private val explicitRecoveryGate = SignedLinkRetryGate(retryCooldownMillis = EXPLICIT_RECOVERY_COOLDOWN_MILLIS)
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
        lastQueue = PlaybackQueue(generation = lastQueue.generation + 1)
        player.stop()
        player.clearMediaItems()
    }

    override fun setQueue(queue: PlaybackQueue, play: Boolean, startPositionMillis: Long) {
        lastQueue = queue
        val mediaItems = queue.toStableMediaItems()
        if (mediaItems.isEmpty()) return
        withController { player ->
            player.setMediaItems(
                mediaItems,
                queue.currentIndex.coerceAtLeast(0),
                startPositionMillis.coerceAtLeast(0),
            )
            player.prepare()
            if (play) player.play()
        }
    }

    override fun select(index: Int, play: Boolean) = withController { player ->
        if (index in 0 until player.mediaItemCount) {
            lastQueue = lastQueue.copy(currentIndex = index)
            player.seekToDefaultPosition(index)
            if (play) player.play()
        }
    }

    override fun playPause() = withController { player ->
        if (player.isPlaying) {
            player.pause()
        } else if (player.playerError != null && explicitRecoveryEligible) {
            recoverAndPlay(player)
        } else if (player.playerError != null) {
            updateState(player)
        } else {
            player.play()
        }
    }
    override fun skipNext() = withController { it.seekToNextMediaItem() }
    override fun skipPrevious() = withController { it.seekToPreviousMediaItem() }
    override fun seekTo(positionMillis: Long) = withController { it.seekTo(positionMillis.coerceAtLeast(0)) }
    override fun seekBy(deltaMillis: Long) = withController { it.seekTo((it.currentPosition + deltaMillis).coerceAtLeast(0)) }

    override fun onEvents(player: Player, events: Player.Events) = updateState(player)

    override fun onPlayerError(error: PlaybackException) {
        explicitRecoveryEligible = isRetriablePlaybackFailure(error.errorCode, error.findHttpResponseCode())
        _state.value = _state.value.copy(error = error.errorCodeName)
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action)
    }

    private fun recoverAndPlay(player: MediaController) {
        val mediaId = player.currentMediaItem?.mediaId ?: _state.value.mediaId ?: return
        if (!explicitRecoveryGate.acquire(mediaId, System.currentTimeMillis())) return
        val mediaItems = lastQueue.toStableMediaItems()
        if (mediaItems.isEmpty()) return
        val index = lastQueue.entries.indexOfFirst { entry ->
            MediaIdentity.encode(entry.track.sourceId, entry.track.id) == mediaId
        }.takeIf { it >= 0 } ?: lastQueue.currentIndex.coerceIn(0, mediaItems.lastIndex)
        val position = _state.value.positionMillis.coerceAtLeast(0)
        lastQueue = lastQueue.copy(currentIndex = index)
        player.setMediaItems(mediaItems, index, position)
        player.prepare()
        player.play()
    }

    private fun updateState(player: Player) {
        if (player.playerError == null) explicitRecoveryEligible = false
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

    private companion object {
        const val EXPLICIT_RECOVERY_COOLDOWN_MILLIS = 5_000L
    }
}

internal fun PlaybackQueue.toStableMediaItems(): List<MediaItem> = entries.map { entry ->
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
