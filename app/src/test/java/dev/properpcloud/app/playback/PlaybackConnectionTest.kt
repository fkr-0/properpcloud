package dev.properpcloud.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamResolutionException
import dev.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun queueResolutionSkipsBrokenItemsAndMovesStartToNextSurvivor() = runTest {
        val items = listOf("good-a", "broken", "good-b").map { MediaItem.Builder().setMediaId(it).build() }

        val result = resolveMediaItemsSkippingFailures(items, startIndex = 1, startPositionMs = 42_000) { item ->
            if (item.mediaId == "broken") {
                throw StreamResolutionException(
                    StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                    "fixture item unavailable",
                )
            }
            // Keep this a plain JVM policy test. MediaItem.setUri(String) calls android.net.Uri,
            // which is intentionally unavailable outside Robolectric and would make every
            // otherwise-good item look like a resolution failure here.
            item
        }

        assertEquals(listOf("good-a", "good-b"), result.items.map { it.mediaId })
        assertEquals(1, result.startIndex)
        assertEquals(0, result.startPositionMs)
    }

    @Test
    fun queueResolutionPreservesStartPositionWhenRequestedItemSurvives() = runTest {
        val items = listOf("broken", "good").map { MediaItem.Builder().setMediaId(it).build() }

        val result = resolveMediaItemsSkippingFailures(items, startIndex = 1, startPositionMs = 12_345) { item ->
            if (item.mediaId == "broken") {
                throw StreamResolutionException(
                    StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                    "fixture item unavailable",
                )
            }
            item
        }

        assertEquals(listOf("good"), result.items.map { it.mediaId })
        assertEquals(0, result.startIndex)
        assertEquals(12_345, result.startPositionMs)
    }

    @Test
    fun terminalFinalRequestedItemDoesNotReplayEarlierSurvivor() = runTest {
        val items = listOf("good", "broken").map { MediaItem.Builder().setMediaId(it).build() }

        val failure = runCatching {
            resolveMediaItemsSkippingFailures(items, startIndex = 1, startPositionMs = 12_345) { item ->
                if (item.mediaId == "broken") {
                    throw StreamResolutionException(
                        StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                        "fixture item unavailable",
                    )
                }
                item
            }
        }.exceptionOrNull()

        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.ITEM_UNAVAILABLE, (failure as StreamResolutionException).kind)
    }

    @Test
    fun transientResolutionFailureIsSurfacedWithoutDestructiveQueueOmission() = runTest {
        val items = listOf("good-a", "offline", "good-b").map { MediaItem.Builder().setMediaId(it).build() }
        val attempted = mutableListOf<String>()

        val failure = runCatching {
            resolveMediaItemsSkippingFailures(items) { item ->
                attempted += item.mediaId
                if (item.mediaId == "offline") {
                    throw StreamResolutionException(
                        StreamResolutionFailureKind.TRANSIENT,
                        "fixture source offline",
                    )
                }
                item
            }
        }.exceptionOrNull()

        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.TRANSIENT, (failure as StreamResolutionException).kind)
        assertEquals(listOf("good-a", "offline"), attempted)
    }

    @Test
    fun allTerminalResolutionFailuresEndOnceInsteadOfReturningAnEmptyPlayerQueue() = runTest {
        val items = listOf("bad-a", "bad-b").map { MediaItem.Builder().setMediaId(it).build() }
        var attempts = 0

        val failure = runCatching {
            resolveMediaItemsSkippingFailures(items) {
                attempts += 1
                throw StreamResolutionException(
                    StreamResolutionFailureKind.ITEM_UNAVAILABLE,
                    "fixture item unavailable",
                )
            }
        }.exceptionOrNull()

        assertEquals(2, attempts)
        assertTrue(failure is StreamResolutionException)
        assertEquals(StreamResolutionFailureKind.ITEM_UNAVAILABLE, (failure as StreamResolutionException).kind)
    }

    @Test
    fun manualSelectionAfterPlayerErrorRequiresPrepareBeforePlaybackCanResume() {
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_IDLE, hasPlayerError = true))
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_IDLE, hasPlayerError = false))
        assertTrue(shouldPrepareAfterManualSeek(Player.STATE_READY, hasPlayerError = true))
        assertFalse(shouldPrepareAfterManualSeek(Player.STATE_READY, hasPlayerError = false))
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
