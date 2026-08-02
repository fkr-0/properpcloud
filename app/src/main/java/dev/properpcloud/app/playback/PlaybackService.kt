package dev.properpcloud.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import dev.properpcloud.app.ProperpcloudApplication
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val retryGate = SignedLinkRetryGate()

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    checkpointCurrentProgressAsync()
                    val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    if (responseCode == 401 || responseCode == 403) refreshCurrentLinkWhenAllowed()
                }
            },
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(ResolvingSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        checkpointCurrentProgressBlocking()
        mediaSession?.release()
        mediaSession = null
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        checkpointCurrentProgressAsync()
        super.onTaskRemoved(rootIntent)
    }

    private fun refreshCurrentLinkWhenAllowed() {
        val item = player.currentMediaItem ?: return
        if (!retryGate.acquire(item.mediaId, System.currentTimeMillis())) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val shouldPlay = player.playWhenReady
        serviceScope.launch {
            runCatching { resolve(item, force = true) }.onSuccess { refreshed ->
                player.replaceMediaItem(index, refreshed)
                player.prepare()
                player.seekTo(index, position)
                player.playWhenReady = shouldPlay
            }
        }
    }

    private fun checkpointCurrentProgressAsync() {
        currentProgress()?.let { progress ->
            (application as ProperpcloudApplication).container.applicationScope.launch {
                (application as ProperpcloudApplication).container.preferences.saveProgress(progress)
            }
        }
    }

    private fun checkpointCurrentProgressBlocking() {
        val progress = currentProgress() ?: return
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(PROGRESS_FLUSH_TIMEOUT_MILLIS) {
                (application as ProperpcloudApplication).container.preferences.saveProgress(progress)
            }
        }
    }

    private fun currentProgress(): PlaybackProgress? {
        if (!::player.isInitialized) return null
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        val (sourceId, nodeId) = runCatching { MediaIdentity.decode(mediaId) }.getOrNull() ?: return null
        val duration = player.duration.takeIf { it > 0 }
        val position = player.currentPosition.coerceAtLeast(0)
        return PlaybackProgress(
            sourceId = sourceId,
            nodeId = nodeId,
            positionMillis = position,
            durationMillis = duration,
            playbackSpeed = player.playbackParameters.speed,
            observedAtEpochMillis = System.currentTimeMillis(),
            completed = duration != null && position >= duration * 0.95,
        )
    }

    private suspend fun resolve(item: MediaItem, force: Boolean = false): MediaItem {
        if (!force && item.localConfiguration?.uri != null) return item
        val (sourceId, nodeId) = MediaIdentity.decode(item.mediaId)
        val source = (application as ProperpcloudApplication).container.sources.source(sourceId)
            ?: error("source ${sourceId.value} is not available")
        val handle = source.resolveStream(nodeId)
        return item.buildUpon()
            .setUri(handle.url)
            .setMimeType(handle.contentType)
            .build()
    }

    @UnstableApi
    private inner class ResolvingSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = serviceScope.future(Dispatchers.IO) {
            mediaItems.map { resolve(it) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.IO) {
            MediaSession.MediaItemsWithStartPosition(
                mediaItems.map { resolve(it) },
                startIndex,
                startPositionMs,
            )
        }
    }

    private companion object {
        const val PROGRESS_FLUSH_TIMEOUT_MILLIS = 1_500L
    }
}
