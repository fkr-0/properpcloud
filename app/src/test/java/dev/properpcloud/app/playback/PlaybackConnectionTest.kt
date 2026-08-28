package dev.properpcloud.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
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
