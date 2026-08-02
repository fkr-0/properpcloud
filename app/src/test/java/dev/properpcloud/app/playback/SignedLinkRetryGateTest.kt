package dev.properpcloud.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedLinkRetryGateTest {
    @Test
    fun allowsOneImmediateRetryThenRequiresCooldown() {
        val gate = SignedLinkRetryGate(retryCooldownMillis = 60_000)

        assertTrue(gate.acquire("pcloud:file:42", 1_000))
        assertFalse(gate.acquire("pcloud:file:42", 1_001))
        assertFalse(gate.acquire("pcloud:file:42", 60_999))
        assertTrue(gate.acquire("pcloud:file:42", 61_000))
    }

    @Test
    fun independentMediaAndExplicitResetDoNotShareBudget() {
        val gate = SignedLinkRetryGate(retryCooldownMillis = 60_000)

        assertTrue(gate.acquire("one", 1_000))
        assertTrue(gate.acquire("two", 1_001))
        gate.reset("one")
        assertTrue(gate.acquire("one", 1_002))
    }

    @Test
    fun blankMediaIdentityNeverConsumesRetry() {
        assertFalse(SignedLinkRetryGate().acquire("", 1_000))
    }
}
