package dev.properpcloud.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedLinkRetryGateTest {
    @Test
    fun `allows one immediate retry then requires cooldown`() {
        val gate = SignedLinkRetryGate(retryCooldownMillis = 60_000)

        assertTrue(gate.acquire("pcloud:file:42", 1_000))
        assertFalse(gate.acquire("pcloud:file:42", 1_001))
        assertFalse(gate.acquire("pcloud:file:42", 60_999))
        assertTrue(gate.acquire("pcloud:file:42", 61_000))
    }

    @Test
    fun `independent media and explicit reset do not share budget`() {
        val gate = SignedLinkRetryGate(retryCooldownMillis = 60_000)

        assertTrue(gate.acquire("one", 1_000))
        assertTrue(gate.acquire("two", 1_001))
        gate.reset("one")
        assertTrue(gate.acquire("one", 1_002))
    }

    @Test
    fun `blank media identity never consumes retry`() {
        assertFalse(SignedLinkRetryGate().acquire("", 1_000))
    }
}
