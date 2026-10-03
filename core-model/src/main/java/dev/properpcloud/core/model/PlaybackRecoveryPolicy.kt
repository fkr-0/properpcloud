package dev.properpcloud.core.model

enum class PlaybackFailureRecovery {
    REFRESH_STREAM_LOCATION,
    SURFACE_FAILURE,
}

/** Pure transport policy; platform adapters remain responsible for decoding their error types. */
object PlaybackRecoveryPolicy {
    fun forHttpStatus(statusCode: Int): PlaybackFailureRecovery = when {
        statusCode in setOf(401, 403, 404, 408, 410, 425, 429) ->
            PlaybackFailureRecovery.REFRESH_STREAM_LOCATION
        statusCode in 500..599 -> PlaybackFailureRecovery.REFRESH_STREAM_LOCATION
        else -> PlaybackFailureRecovery.SURFACE_FAILURE
    }
}
