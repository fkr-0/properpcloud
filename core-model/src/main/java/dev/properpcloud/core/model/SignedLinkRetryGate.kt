package dev.properpcloud.core.model

/**
 * Bounds capability-link re-resolution independently for each stable media identity.
 *
 * The gate deliberately stores only stable IDs and timestamps. Provider URLs, response
 * bodies, and credentials never enter this policy object.
 */
class SignedLinkRetryGate(
    private val retryCooldownMillis: Long = 60_000,
) {
    private val lastAttempts = linkedMapOf<String, Long>()

    init {
        require(retryCooldownMillis > 0)
    }

    @Synchronized
    fun acquire(mediaId: String, nowEpochMillis: Long): Boolean {
        if (mediaId.isBlank()) return false
        val previous = lastAttempts[mediaId]
        if (previous != null && nowEpochMillis - previous < retryCooldownMillis) return false
        lastAttempts[mediaId] = nowEpochMillis
        lastAttempts.entries.removeAll { nowEpochMillis - it.value >= retryCooldownMillis * RETENTION_MULTIPLIER }
        return true
    }

    @Synchronized
    fun reset(mediaId: String) {
        lastAttempts.remove(mediaId)
    }

    private companion object {
        const val RETENTION_MULTIPLIER = 10
    }
}
