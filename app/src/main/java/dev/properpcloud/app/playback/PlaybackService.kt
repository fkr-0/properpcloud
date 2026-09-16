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
import dev.properpcloud.core.model.PlaybackFailureRecovery
import dev.properpcloud.core.model.PlaybackRecoveryPolicy
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.SignedLinkRetryGate
import dev.properpcloud.core.model.StreamResolutionException
import dev.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PlaybackFailureAction {
    REFRESH_STREAM_LOCATION,
    SKIP_TO_NEXT,
    SURFACE_FAILURE,
}

@UnstableApi
internal fun playbackFailureAction(
    errorCode: Int,
    responseCode: Int?,
    refreshAvailable: Boolean,
    hasNextMediaItem: Boolean,
): PlaybackFailureAction {
    val transportRecovery = responseCode?.let(PlaybackRecoveryPolicy::forHttpStatus)
    if (transportRecovery == PlaybackFailureRecovery.REFRESH_STREAM_LOCATION && refreshAvailable) {
        return PlaybackFailureAction.REFRESH_STREAM_LOCATION
    }
    if (!hasNextMediaItem) return PlaybackFailureAction.SURFACE_FAILURE

    val itemSpecificHttpFailure = responseCode in setOf(404, 410, 415, 416, 422)
    val itemSpecificMediaFailure = errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )
    return if (itemSpecificHttpFailure || itemSpecificMediaFailure) {
        PlaybackFailureAction.SKIP_TO_NEXT
    } else {
        PlaybackFailureAction.SURFACE_FAILURE
    }
}

internal fun nextQueueIndexAfterFailure(currentIndex: Int, itemCount: Int): Int? {
    if (currentIndex !in 0 until itemCount) return null
    return (currentIndex + 1).takeIf { it < itemCount }
}

internal data class ResolvedMediaItems(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

internal suspend fun resolveMediaItemsSkippingFailures(
    mediaItems: List<MediaItem>,
    startIndex: Int = 0,
    startPositionMs: Long = 0,
    resolver: suspend (MediaItem) -> MediaItem,
): ResolvedMediaItems {
    if (mediaItems.isEmpty()) return ResolvedMediaItems(emptyList(), 0, 0)
    val resolved = mutableListOf<Pair<Int, MediaItem>>()
    mediaItems.forEachIndexed { index, item ->
        try {
            resolved += index to resolver(item)
        } catch (error: StreamResolutionException) {
            if (error.kind == StreamResolutionFailureKind.TRANSIENT) throw error
            // A provider has explicitly proven this stable item unavailable. Omit only
            // that item; never infer item failure from a generic network/source error.
        } catch (error: CancellationException) {
            throw error
        }
    }
    if (resolved.isEmpty()) {
        throw StreamResolutionException(
            StreamResolutionFailureKind.ITEM_UNAVAILABLE,
            "none of the submitted media items are available",
        )
    }

    val requested = startIndex.coerceIn(mediaItems.indices)
    val selected = resolved.indexOfFirst { (originalIndex, _) -> originalIndex >= requested }
    if (selected < 0) {
        throw StreamResolutionException(
            StreamResolutionFailureKind.ITEM_UNAVAILABLE,
            "the requested media item and all later queue entries are unavailable",
        )
    }
    return ResolvedMediaItems(
        items = resolved.map { it.second },
        startIndex = selected,
        startPositionMs = startPositionMs.takeIf { resolved[selected].first == requested } ?: 0,
    )
}

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
                    val item = player.currentMediaItem
                    val responseCode = error.findHttpResponseCode()
                    val nextIndex = nextDistinctMediaItemIndex()
                    val refreshAvailable = item != null &&
                        responseCode != null &&
                        PlaybackRecoveryPolicy.forHttpStatus(responseCode) == PlaybackFailureRecovery.REFRESH_STREAM_LOCATION &&
                        retryGate.acquire(item.mediaId, System.currentTimeMillis())

                    when (
                        playbackFailureAction(
                            errorCode = error.errorCode,
                            responseCode = responseCode,
                            refreshAvailable = refreshAvailable,
                            hasNextMediaItem = nextIndex != null,
                        )
                    ) {
                        PlaybackFailureAction.REFRESH_STREAM_LOCATION -> item?.let(::refreshCurrentLink)
                        PlaybackFailureAction.SKIP_TO_NEXT -> nextIndex?.let(::skipFailedItem)
                        PlaybackFailureAction.SURFACE_FAILURE -> Unit
                    }
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

    private fun refreshCurrentLink(item: MediaItem) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val shouldPlay = player.playWhenReady
        serviceScope.launch {
            try {
                val refreshed = resolve(item, force = true)
                if (player.currentMediaItem?.mediaId != item.mediaId || index !in 0 until player.mediaItemCount) {
                    return@launch
                }
                player.replaceMediaItem(index, refreshed)
                player.prepare()
                player.seekTo(index, position)
                player.playWhenReady = shouldPlay
            } catch (error: StreamResolutionException) {
                if (
                    error.kind == StreamResolutionFailureKind.ITEM_UNAVAILABLE &&
                    player.currentMediaItem?.mediaId == item.mediaId
                ) {
                    nextDistinctMediaItemIndex()?.let(::skipFailedItem)
                }
                // Transient/source-wide failures deliberately leave the current item in
                // error so explicit retry/reconnect can recover without consuming queue entries.
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private fun nextDistinctMediaItemIndex(): Int? =
        nextQueueIndexAfterFailure(player.currentMediaItemIndex, player.mediaItemCount)

    private fun skipFailedItem(nextIndex: Int) {
        if (nextIndex !in 0 until player.mediaItemCount || nextIndex == player.currentMediaItemIndex) return
        val shouldPlay = player.playWhenReady
        player.seekToDefaultPosition(nextIndex)
        player.prepare()
        player.playWhenReady = shouldPlay
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
            resolveMediaItemsSkippingFailures(mediaItems, resolver = ::resolve).items
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.IO) {
            val resolved = resolveMediaItemsSkippingFailures(
                mediaItems = mediaItems,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                resolver = ::resolve,
            )
            MediaSession.MediaItemsWithStartPosition(
                resolved.items,
                resolved.startIndex,
                resolved.startPositionMs,
            )
        }
    }

    private companion object {
        const val PROGRESS_FLUSH_TIMEOUT_MILLIS = 1_500L
    }
}

@UnstableApi
internal fun Throwable.findHttpResponseCode(): Int? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}
