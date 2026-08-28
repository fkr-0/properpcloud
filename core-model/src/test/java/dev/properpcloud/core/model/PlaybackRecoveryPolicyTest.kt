package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun `stale capability and transient http statuses refresh but permanent client failures surface`() {
        listOf(401, 403, 404, 408, 410, 425, 429, 500, 503, 599).forEach { status ->
            assertEquals(PlaybackFailureRecovery.REFRESH_STREAM_LOCATION, PlaybackRecoveryPolicy.forHttpStatus(status))
        }
        listOf(400, 402, 405, 409, 422).forEach { status ->
            assertEquals(PlaybackFailureRecovery.SURFACE_FAILURE, PlaybackRecoveryPolicy.forHttpStatus(status))
        }
    }
}
