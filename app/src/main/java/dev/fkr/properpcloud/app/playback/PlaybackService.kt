package dev.fkr.properpcloud.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import dev.fkr.properpcloud.app.ProperpcloudApplication
import dev.fkr.properpcloud.core.model.MediaFormatRegistry
import dev.fkr.properpcloud.core.model.MediaIdentity
import dev.fkr.properpcloud.core.model.PlaybackFailureRecovery
import dev.fkr.properpcloud.core.model.PlaybackRecoveryPolicy
import dev.fkr.properpcloud.core.model.PlaybackProgress
import dev.fkr.properpcloud.core.model.SignedLinkRetryGate
import dev.fkr.properpcloud.core.model.StreamResolutionException
import dev.fkr.properpcloud.core.model.StreamHandle
import dev.fkr.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException

internal enum class PlaybackFailureAction {
    REFRESH_STREAM_LOCATION,
    RECOVER_RANGE,
    RETRY_TRANSIENT,
    SKIP_TO_NEXT,
    SURFACE_FAILURE,
}

@UnstableApi
internal fun playbackFailureAction(
    errorCode: Int,
    responseCode: Int?,
    refreshAvailable: Boolean,
    hasNextMediaItem: Boolean,
    rangeRecoveryAvailable: Boolean = false,
    transientRetryAvailable: Boolean = false,
): PlaybackFailureAction {
    val rangeFailure = responseCode == 416 ||
        errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
    if (rangeFailure) {
        return if (rangeRecoveryAvailable) PlaybackFailureAction.RECOVER_RANGE
        else PlaybackFailureAction.SURFACE_FAILURE
    }

    val transientReadFailure = errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    if (transientReadFailure) {
        return if (transientRetryAvailable) PlaybackFailureAction.RETRY_TRANSIENT
        else PlaybackFailureAction.SURFACE_FAILURE
    }

    val transportRecovery = responseCode?.let(PlaybackRecoveryPolicy::forHttpStatus)
    if (transportRecovery == PlaybackFailureRecovery.REFRESH_STREAM_LOCATION && refreshAvailable) {
        return PlaybackFailureAction.REFRESH_STREAM_LOCATION
    }
    if (!hasNextMediaItem) return PlaybackFailureAction.SURFACE_FAILURE

    // Only failures that identify this item as unavailable or unplayable consume a queue entry.
    // 416/range errors and transient I/O failures are deliberately handled above.
    val itemSpecificHttpFailure = responseCode in setOf(404, 410, 415, 422)
    val itemSpecificMediaFailure = errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
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

/** A per-media bounded transient retry budget; it stores only stable media IDs. */
internal class BoundedPlaybackRetryGate(
    private val retryDelaysMillis: List<Long> = listOf(250L, 500L, 1_000L),
) {
    private val attempts = linkedMapOf<String, Int>()

    init {
        require(retryDelaysMillis.isNotEmpty() && retryDelaysMillis.all { it >= 0 }) {
            "retry delays must be non-empty and non-negative"
        }
    }

    @Synchronized
    fun nextDelayMillis(mediaId: String): Long? {
        if (mediaId.isBlank()) return null
        val nextAttempt = attempts[mediaId] ?: 0
        if (nextAttempt >= retryDelaysMillis.size) return null
        attempts[mediaId] = nextAttempt + 1
        if (attempts.size > MAX_TRACKED_MEDIA_IDS) {
            attempts.keys.firstOrNull { it != mediaId }?.let(attempts::remove)
        }
        return retryDelaysMillis[nextAttempt]
    }

    @Synchronized
    fun reset(mediaId: String) {
        attempts.remove(mediaId)
    }

    private companion object {
        const val MAX_TRACKED_MEDIA_IDS = 256
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

internal fun prepareMediaItemsForPlayback(
    mediaItems: List<MediaItem>,
    startIndex: Int = 0,
    startPositionMs: Long = 0,
): ResolvedMediaItems {
    if (mediaItems.isEmpty()) return ResolvedMediaItems(emptyList(), 0, 0)
    return ResolvedMediaItems(
        items = mediaItems.toList(),
        startIndex = startIndex.coerceIn(mediaItems.indices),
        startPositionMs = startPositionMs,
    )
}

/** Attach only a MIME hint revalidated against the stable filename carried as subtitle metadata. */
internal fun MediaItem.withDeferredStreamUri(requestUri: Uri): MediaItem {
    val builder = buildUpon().setUri(requestUri)
    val mimeType = MediaFormatRegistry.classify(
        mediaMetadata.subtitle?.toString(),
        mediaMetadata.extras?.getString(PLAYBACK_MIME_TYPE_EXTRA),
    ).playbackMimeType
    mimeType?.let(builder::setMimeType)
    return builder.build()
}

internal const val STREAM_RESOLUTION_MAX_ATTEMPTS = 3
internal const val STREAM_CAPABILITY_MINIMUM_VALIDITY_MILLIS = 30_000L

internal fun streamCapabilityRetryDelayMillis(attempt: Int): Long {
    require(attempt >= 0) { "attempt must not be negative" }
    return when (attempt) {
        0 -> 250L
        1 -> 500L
        else -> 1_000L
    }
}

/**
 * A provider URL is useful only if it remains valid long enough to reach the first read.
 * Retry transient lookup failures and near-expiry capabilities with a small deterministic
 * backoff. Permanent item failures are not retried.
 */
internal suspend fun resolveStreamHandleWithRetry(
    resolver: suspend () -> StreamHandle,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    maxAttempts: Int = STREAM_RESOLUTION_MAX_ATTEMPTS,
    minimumValidityMillis: Long = STREAM_CAPABILITY_MINIMUM_VALIDITY_MILLIS,
): StreamHandle {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    require(minimumValidityMillis >= 0) { "minimumValidityMillis must not be negative" }

    var lastFailure: StreamResolutionException? = null
    repeat(maxAttempts) { attempt ->
        val handle = try {
            resolver()
        } catch (error: StreamResolutionException) {
            if (error.kind == StreamResolutionFailureKind.ITEM_UNAVAILABLE) throw error
            lastFailure = error
            null
        }

        if (handle != null) {
            val now = nowEpochMillis()
            val expiry = handle.expiresAtEpochMillis
            if (expiry == null || (expiry > now && expiry - now > minimumValidityMillis)) {
                return handle
            }
            lastFailure = StreamResolutionException(
                StreamResolutionFailureKind.TRANSIENT,
                "provider returned a playback capability too close to expiry",
            )
        }

        if (attempt + 1 < maxAttempts) {
            wait(streamCapabilityRetryDelayMillis(attempt))
        }
    }

    throw lastFailure ?: StreamResolutionException(
        StreamResolutionFailureKind.TRANSIENT,
        "playback capability could not be resolved",
    )
}

internal fun missingPlaybackSourceFailure(): StreamResolutionException =
    StreamResolutionException(
        StreamResolutionFailureKind.TRANSIENT,
        "playback source is not connected",
    )

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val retryGate = SignedLinkRetryGate()
    private val rangeRetryGate = SignedLinkRetryGate(retryCooldownMillis = 60_000L)
    private val transientReadRetryGate = BoundedPlaybackRetryGate()

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createResolvingDataSourceFactory()))
            .build()
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    checkpointCurrentProgressAsync()
                    val item = player.currentMediaItem
                    val responseCode = error.findHttpResponseCode()
                    val nextIndex = nextDistinctMediaItemIndex()
                    val now = System.currentTimeMillis()
                    val rangeFailure = responseCode == 416 ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                    val rangeRecoveryAvailable = item != null && rangeFailure &&
                        rangeRetryGate.acquire(item.mediaId, now)
                    val transientRetryDelayMillis = item?.takeIf {
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    }?.let { transientReadRetryGate.nextDelayMillis(it.mediaId) }
                    val refreshAvailable = item != null &&
                        responseCode != null &&
                        PlaybackRecoveryPolicy.forHttpStatus(responseCode) == PlaybackFailureRecovery.REFRESH_STREAM_LOCATION &&
                        retryGate.acquire(item.mediaId, now)

                    when (
                        playbackFailureAction(
                            errorCode = error.errorCode,
                            responseCode = responseCode,
                            refreshAvailable = refreshAvailable,
                            hasNextMediaItem = nextIndex != null,
                            rangeRecoveryAvailable = rangeRecoveryAvailable,
                            transientRetryAvailable = transientRetryDelayMillis != null,
                        )
                    ) {
                        PlaybackFailureAction.REFRESH_STREAM_LOCATION -> item?.let { refreshCurrentLink(it) }
                        PlaybackFailureAction.RECOVER_RANGE -> item?.let { refreshCurrentLink(it, positionMillis = 0) }
                        PlaybackFailureAction.RETRY_TRANSIENT -> item?.let {
                            retryTransientRead(it, requireNotNull(transientRetryDelayMillis))
                        }
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

    private fun refreshCurrentLink(
        item: MediaItem,
        positionMillis: Long = player.currentPosition.coerceAtLeast(0),
        expectedTimelineIds: List<String>? = null,
        shouldPlay: Boolean = player.playWhenReady,
    ) {
        val index = player.currentMediaItemIndex
        val timelineIds = currentTimelineMediaIds()
        serviceScope.launch {
            if (
                player.currentMediaItemIndex != index ||
                player.currentMediaItem?.mediaId != item.mediaId ||
                index !in 0 until player.mediaItemCount ||
                currentTimelineMediaIds() != timelineIds ||
                (expectedTimelineIds != null && timelineIds != expectedTimelineIds)
            ) {
                return@launch
            }
            // Reinstall only this stable item as a fresh deferred request. The capability is
            // resolved when Media3 opens it; the rest of the timeline is left untouched.
            player.replaceMediaItem(index, deferredStreamMediaItem(item, System.nanoTime()))
            player.prepare()
            player.seekTo(index, positionMillis.coerceAtLeast(0))
            player.playWhenReady = shouldPlay
        }
    }

    private fun retryTransientRead(item: MediaItem, delayMillis: Long) {
        val index = player.currentMediaItemIndex
        val positionMillis = player.currentPosition.coerceAtLeast(0)
        val shouldPlay = player.playWhenReady
        val expectedTimelineIds = currentTimelineMediaIds()
        serviceScope.launch {
            delay(delayMillis)
            if (
                player.currentMediaItemIndex != index ||
                player.currentMediaItem?.mediaId != item.mediaId ||
                currentTimelineMediaIds() != expectedTimelineIds
            ) {
                return@launch
            }
            refreshCurrentLink(
                item = item,
                positionMillis = positionMillis,
                expectedTimelineIds = expectedTimelineIds,
                shouldPlay = shouldPlay,
            )
        }
    }

    private fun currentTimelineMediaIds(): List<String> =
        (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).mediaId }

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

    private fun deferredStreamMediaItem(item: MediaItem, refreshToken: Long = 0): MediaItem {
        // Media identity is the encoded source/node pair; incoming URIs (including any stale
        // signed URL from a controller) are never retained on the player's timeline.
        MediaIdentity.decode(item.mediaId)
        val requestUri = Uri.Builder()
            .scheme(DEFERRED_STREAM_SCHEME)
            .authority(DEFERRED_STREAM_AUTHORITY)
            .appendQueryParameter(STREAM_MEDIA_ID_QUERY, item.mediaId)
            .appendQueryParameter(STREAM_REFRESH_QUERY, refreshToken.toString())
            .build()
        return item.withDeferredStreamUri(requestUri)
    }

    private suspend fun resolveStreamCapability(mediaId: String): StreamHandle {
        val (sourceId, nodeId) = try {
            MediaIdentity.decode(mediaId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw StreamResolutionException(
                StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                "playback media identity is invalid",
                error,
            )
        }
        val source = (application as ProperpcloudApplication).container.sources.source(sourceId)
            ?: throw missingPlaybackSourceFailure()
        return resolveStreamHandleWithRetry(
            resolver = {
                try {
                    source.resolveStream(nodeId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: StreamResolutionException) {
                    throw error
                } catch (error: Exception) {
                    throw StreamResolutionException(
                        StreamResolutionFailureKind.TRANSIENT,
                        "stream capability lookup failed",
                        error,
                    )
                }
            },
        )
    }

    @UnstableApi
    private fun createResolvingDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val requestUri = dataSpec.uri
                    if (
                        requestUri.scheme != DEFERRED_STREAM_SCHEME ||
                        requestUri.authority != DEFERRED_STREAM_AUTHORITY
                    ) {
                        return dataSpec
                    }
                    val mediaId = requestUri.getQueryParameter(STREAM_MEDIA_ID_QUERY)
                        ?: throw FileNotFoundException("deferred playback request has no stable media identity")
                    val handle = try {
                        runBlocking(Dispatchers.IO) { resolveStreamCapability(mediaId) }
                    } catch (error: StreamResolutionException) {
                        when (error.kind) {
                            StreamResolutionFailureKind.ITEM_UNAVAILABLE ->
                                throw FileNotFoundException("media item is no longer available").also {
                                    it.initCause(error)
                                }
                            StreamResolutionFailureKind.TRANSIENT ->
                                throw IOException("playback stream capability is temporarily unavailable", error)
                        }
                    }
                    // The signed URL exists only in this transient DataSpec and the upstream
                    // DataSource. It is not written into MediaItem, queue state, or progress.
                    return dataSpec.buildUpon().setUri(Uri.parse(handle.url)).build()
                }

                override fun resolveReportedUri(uri: Uri): Uri =
                    if (uri.scheme.equals("http", ignoreCase = true) ||
                        uri.scheme.equals("https", ignoreCase = true)
                    ) {
                        REDACTED_STREAM_REPORT_URI
                    } else {
                        uri
                    }
            },
        )
    }

    @UnstableApi
    private inner class ResolvingSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = serviceScope.future(Dispatchers.IO) {
            prepareMediaItemsForPlayback(mediaItems).items.map(::deferredStreamMediaItem)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.IO) {
            val prepared = prepareMediaItemsForPlayback(
                mediaItems = mediaItems,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
            )
            MediaSession.MediaItemsWithStartPosition(
                prepared.items.map(::deferredStreamMediaItem),
                prepared.startIndex,
                prepared.startPositionMs,
            )
        }
    }

    private companion object {
        const val PROGRESS_FLUSH_TIMEOUT_MILLIS = 1_500L
        const val DEFERRED_STREAM_SCHEME = "properpcloud-stream"
        const val DEFERRED_STREAM_AUTHORITY = "item"
        const val STREAM_MEDIA_ID_QUERY = "mediaId"
        const val STREAM_REFRESH_QUERY = "refresh"
        val REDACTED_STREAM_REPORT_URI: Uri = Uri.Builder()
            .scheme(DEFERRED_STREAM_SCHEME)
            .authority("redacted")
            .build()
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
