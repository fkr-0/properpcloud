package dev.fkr.properpcloud.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.fkr.properpcloud.core.model.AudioTrack
import dev.fkr.properpcloud.core.model.MediaIdentity
import dev.fkr.properpcloud.core.model.NodeId
import dev.fkr.properpcloud.core.model.PlaybackQueue
import dev.fkr.properpcloud.core.model.QueueEntry
import dev.fkr.properpcloud.core.model.SourceId
import dev.fkr.properpcloud.core.model.StreamHandle
import dev.fkr.properpcloud.core.model.StreamResolutionException
import dev.fkr.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@UnstableApi
class PlaybackConnectionTest {
    @Test
    fun retriablePlaybackFailureRequiresNetworkOrRefreshableHttpFailure() {
        assertTrue(
            isRetriablePlaybackFailure(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 403,
            ),
        )
        assertTrue(
            isRetriablePlaybackFailure(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                responseCode = null,
            ),
        )
        assertTrue(
            isRetriablePlaybackFailure(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                responseCode = null,
            ),
        )
        assertFalse(
            isRetriablePlaybackFailure(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 422,
            ),
        )
        assertFalse(
            isRetriablePlaybackFailure(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                responseCode = null,
            ),
        )
    }

    @Test
    fun playbackFailuresExposeActionableRedactedMessages() {
        assertEquals(
            "Playback was interrupted by a network problem. Reconnect and press play to retry.",
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                responseCode = null,
                errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            ),
        )
        assertEquals(
            "Playback cannot access this media. Check the source permission and reconnect.",
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                responseCode = null,
                errorCodeName = "ERROR_CODE_IO_NO_PERMISSION",
            ),
        )
        assertEquals(
            "This media file could not be decoded. It may be corrupt or unsupported.",
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                responseCode = null,
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
            ),
        )
    }

    @Test
    fun malformedAndUnsupportedMediaExposeCorruptMediaMessage() {
        val expected = "This media file could not be decoded. It may be corrupt or unsupported."
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        ).forEach { errorCode ->
            assertEquals(
                expected,
                playbackErrorUserMessage(errorCode, responseCode = null, errorCodeName = "fixture"),
            )
        }
    }

    @Test
    fun outOfRangeReadExplainsPositionRecoveryInsteadOfCallingMediaCorrupt() {
        val expected = "The playback position is outside the available stream range. Restart from the beginning or seek to an earlier position."
        assertEquals(
            expected,
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                responseCode = null,
                errorCodeName = "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
            ),
        )
        assertEquals(
            expected,
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 416,
                errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            ),
        )
    }

    @Test
    fun missingPlaybackSourceIsTransientSoReconnectCanRecoverQueue() {
        val failure = missingPlaybackSourceFailure()

        assertEquals(StreamResolutionFailureKind.TRANSIENT, failure.kind)
        assertEquals("playback source is not connected", failure.message)
    }

    @Test
    fun playbackHttpFailuresDistinguishExpiredAccessFromMissingMedia() {
        assertEquals(
            "Playback access expired or was denied. Reconnect and press play to retry.",
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 403,
                errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            ),
        )
        assertEquals(
            "This media item is no longer available at its source.",
            playbackErrorUserMessage(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 404,
                errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            ),
        )
    }

    @Test
    fun terminalItemFailuresSkipButTransientNetworkFailuresDoNotCascadeThroughQueue() {
        assertEquals(
            PlaybackFailureAction.SKIP_TO_NEXT,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.SKIP_TO_NEXT,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 404,
                refreshAvailable = false,
                hasNextMediaItem = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.REFRESH_STREAM_LOCATION,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 403,
                refreshAvailable = true,
                hasNextMediaItem = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.SURFACE_FAILURE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.SURFACE_FAILURE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = false,
            ),
        )
    }

    @Test
    fun terminalMediaVariantsSkipWithoutTreatingPermissionFailureAsItemTerminal() {
        listOf(404, 410, 415, 422).forEach { responseCode ->
            assertEquals(
                PlaybackFailureAction.SKIP_TO_NEXT,
                playbackFailureAction(
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    responseCode = responseCode,
                    refreshAvailable = false,
                    hasNextMediaItem = true,
                ),
            )
        }

        listOf(
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
        ).forEach { errorCode ->
            assertEquals(
                PlaybackFailureAction.SKIP_TO_NEXT,
                playbackFailureAction(
                    errorCode,
                    responseCode = null,
                    refreshAvailable = false,
                    hasNextMediaItem = true,
                ),
            )
        }

        assertEquals(
            PlaybackFailureAction.SURFACE_FAILURE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = true,
            ),
        )
    }

    @Test
    fun rangeReadFailuresRecoverOnceInsteadOfConsumingTheQueueEntry() {
        assertEquals(
            PlaybackFailureAction.RECOVER_RANGE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 416,
                refreshAvailable = false,
                hasNextMediaItem = true,
                rangeRecoveryAvailable = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.RECOVER_RANGE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = false,
                rangeRecoveryAvailable = true,
            ),
        )
        assertEquals(
            PlaybackFailureAction.SURFACE_FAILURE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = true,
                rangeRecoveryAvailable = false,
            ),
        )
        assertEquals(
            PlaybackFailureAction.SURFACE_FAILURE,
            playbackFailureAction(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                responseCode = 416,
                refreshAvailable = false,
                hasNextMediaItem = true,
                rangeRecoveryAvailable = false,
            ),
        )
    }

    @Test
    fun transientNetworkReadFailuresRetryCurrentItemWithoutSkipping() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ).forEach { errorCode ->
            assertEquals(
                PlaybackFailureAction.RETRY_TRANSIENT,
                playbackFailureAction(
                    errorCode = errorCode,
                    responseCode = null,
                    refreshAvailable = false,
                    hasNextMediaItem = false,
                    transientRetryAvailable = true,
                ),
            )
            assertEquals(
                PlaybackFailureAction.SURFACE_FAILURE,
                playbackFailureAction(
                    errorCode = errorCode,
                    responseCode = null,
                    refreshAvailable = false,
                    hasNextMediaItem = true,
                    transientRetryAvailable = false,
                ),
            )
        }
    }

    @Test
    fun boundedPlaybackRetryGateLimitsRetriesPerStableMediaIdentity() {
        val gate = BoundedPlaybackRetryGate()

        assertEquals(250L, gate.nextDelayMillis("pcloud:file:42"))
        assertEquals(500L, gate.nextDelayMillis("pcloud:file:42"))
        assertEquals(1_000L, gate.nextDelayMillis("pcloud:file:42"))
        assertNull(gate.nextDelayMillis("pcloud:file:42"))
        assertEquals(250L, gate.nextDelayMillis("pcloud:file:43"))

        gate.reset("pcloud:file:42")
        assertEquals(250L, gate.nextDelayMillis("pcloud:file:42"))
    }

    @Test
    fun failureSkipAdvancesForwardWithoutRepeatWraparound() {
        assertEquals(1, nextQueueIndexAfterFailure(currentIndex = 0, itemCount = 3))
        assertEquals(2, nextQueueIndexAfterFailure(currentIndex = 1, itemCount = 3))
        assertNull(nextQueueIndexAfterFailure(currentIndex = 2, itemCount = 3))
        assertNull(nextQueueIndexAfterFailure(currentIndex = -1, itemCount = 3))
    }

    @Test
    fun allBadQueueTerminatesAfterFiniteForwardSkips() {
        var index = 0
        var skips = 0
        val itemCount = 3
        while (true) {
            val next = nextQueueIndexAfterFailure(index, itemCount)
            val action = playbackFailureAction(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                responseCode = null,
                refreshAvailable = false,
                hasNextMediaItem = next != null,
            )
            if (action != PlaybackFailureAction.SKIP_TO_NEXT) {
                assertEquals(PlaybackFailureAction.SURFACE_FAILURE, action)
                break
            }
            index = requireNotNull(next)
            skips += 1
            assertTrue("terminal failure skip loop must remain bounded", skips < itemCount)
        }

        assertEquals(2, skips)
        assertEquals(2, index)
    }

    @Test
    fun queuePreparationPreservesEveryStableItemAndRequestedPosition() = runTest {
        val items = listOf("first", "current", "later").map { mediaId ->
            MediaItem.Builder().setMediaId(mediaId).build()
        }

        val prepared = prepareMediaItemsForPlayback(items, startIndex = 1, startPositionMs = 42_000)

        assertEquals(items, prepared.items)
        assertEquals(listOf("first", "current", "later"), prepared.items.map { it.mediaId })
        assertEquals(1, prepared.startIndex)
        assertEquals(42_000, prepared.startPositionMs)
    }

    @Test
    fun queuePreparationClampsIndexWithoutDroppingItemsOrPosition() = runTest {
        val items = listOf("first", "last").map { mediaId ->
            MediaItem.Builder().setMediaId(mediaId).build()
        }

        val prepared = prepareMediaItemsForPlayback(items, startIndex = Int.MAX_VALUE, startPositionMs = 12_345)

        assertEquals(items, prepared.items)
        assertEquals(1, prepared.startIndex)
        assertEquals(12_345, prepared.startPositionMs)
    }

    @Test
    fun queuePreparationPreservesUnsetPositionSentinel() = runTest {
        val items = listOf(MediaItem.Builder().setMediaId("current").build())

        val prepared = prepareMediaItemsForPlayback(items, startIndex = 0, startPositionMs = C.TIME_UNSET)

        assertEquals(C.TIME_UNSET, prepared.startPositionMs)
    }

    @Test
    fun streamResolutionRetriesCapabilityAtExpiryBoundaryAfterDeterministicDelay() = runTest {
        val handles = listOf(
            StreamHandle("https://expired.example/audio", expiresAtEpochMillis = 30_000),
            StreamHandle("https://fresh.example/audio", expiresAtEpochMillis = 30_001),
        )
        val delays = mutableListOf<Long>()
        var attempts = 0

        val resolved = resolveStreamHandleWithRetry(
            resolver = { handles[attempts++] },
            nowEpochMillis = { 0L },
            wait = { duration ->
                delays += duration
                delay(duration)
            },
        )

        assertEquals("https://fresh.example/audio", resolved.url)
        assertEquals(2, attempts)
        assertEquals(listOf(250L), delays)
    }

    @Test
    fun streamResolutionRetriesTransientFailuresWithBoundedBackoff() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val failure = runCatching {
            resolveStreamHandleWithRetry(
                resolver = {
                    attempts += 1
                    throw StreamResolutionException(
                        StreamResolutionFailureKind.TRANSIENT,
                        "temporary provider failure",
                    )
                },
                nowEpochMillis = { 0L },
                wait = { duration ->
                    delays += duration
                    delay(duration)
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.TRANSIENT, (failure as StreamResolutionException).kind)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), delays)
    }

    @Test
    fun repeatedlyNearExpiredCapabilitiesStopAtTheRetryLimit() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val failure = runCatching {
            resolveStreamHandleWithRetry(
                resolver = {
                    attempts += 1
                    StreamHandle("https://near-expiry.example/audio", expiresAtEpochMillis = 29_999)
                },
                nowEpochMillis = { 0L },
                wait = { duration ->
                    delays += duration
                    delay(duration)
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.TRANSIENT, (failure as StreamResolutionException).kind)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), delays)
    }

    @Test
    fun permanentUnavailableItemIsNotRetried() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val failure = runCatching {
            resolveStreamHandleWithRetry(
                resolver = {
                    attempts += 1
                    throw StreamResolutionException(
                        StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                        "missing stable item",
                    )
                },
                wait = { duration ->
                    delays += duration
                    delay(duration)
                },
                maxAttempts = 5,
            )
        }.exceptionOrNull()

        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.ITEM_UNAVAILABLE, (failure as StreamResolutionException).kind)
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun manualSelectionAfterPlayerErrorRequiresPrepareBeforePlaybackCanResume() {
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_IDLE, hasPlayerError = true))
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_IDLE, hasPlayerError = false))
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_READY, hasPlayerError = true))
        assertFalse(shouldPrepareAfterManualSeek(Player.STATE_READY, hasPlayerError = false))
    }

    @Test
    fun stableQueueCarriesOnlyCanonicalConsistentMimeHints() {
        val sourceId = SourceId("pcloud")
        fun track(name: String, contentType: String?) = AudioTrack(
            sourceId = sourceId,
            id = NodeId("file:$name"),
            parentId = NodeId("folder:1"),
            name = name,
            contentType = contentType,
        )
        val queue = PlaybackQueue(
            entries = listOf(
                QueueEntry(track("chapter.m4b", null)),
                QueueEntry(track("recording.AIFF", "application/octet-stream")),
                QueueEntry(track("payload.exe", "audio/mpeg")),
                QueueEntry(track("mismatch.mp3", "audio/flac")),
                QueueEntry(track("movie.mp4", "application/octet-stream")),
                QueueEntry(track("voice", " AUDIO/OPUS ; codecs=opus ")),
            ),
        )

        val hints = queue.toStableMediaItems().map { item ->
            item.mediaMetadata.extras?.getString(PLAYBACK_MIME_TYPE_EXTRA)
        }

        assertEquals(
            listOf("audio/mp4", "audio/aiff", null, null, null, "audio/opus"),
            hints,
        )

        val audioItem = queue.toStableMediaItems().first()
        val deferred = audioItem.withDeferredStreamUri(
            Uri.Builder().scheme("properpcloud-stream").authority("item").build(),
        )
        assertEquals("audio/mp4", deferred.localConfiguration?.mimeType)
        assertEquals("properpcloud-stream", deferred.localConfiguration?.uri?.scheme)
    }

    @Test
    fun explicitRecoveryItemsCarryStableIdentityButNoEphemeralUri() {
        val track = AudioTrack(
            sourceId = SourceId("pcloud"),
            id = NodeId("file:42"),
            parentId = NodeId("folder:7"),
            name = "chapter.flac",
        )
        val queue = PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0)

        val item = queue.toStableMediaItems().single()

        assertEquals(MediaIdentity.encode(track.sourceId, track.id), item.mediaId)
        assertNull(item.localConfiguration)
        assertFalse(item.mediaId.contains("https://"))
    }
}
