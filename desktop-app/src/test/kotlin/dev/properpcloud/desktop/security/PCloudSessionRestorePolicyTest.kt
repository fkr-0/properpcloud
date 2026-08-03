package dev.properpcloud.desktop.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PCloudSessionRestorePolicyTest {
    @Test
    fun `durable disconnect tombstone blocks stale credential restoration`() {
        assertTrue(PCloudSessionRestorePolicy.permitsRestore(null))
        assertTrue(PCloudSessionRestorePolicy.permitsRestore(PCloudSessionRestorePolicy.ACTIVE))
        assertFalse(PCloudSessionRestorePolicy.permitsRestore(PCloudSessionRestorePolicy.DISCONNECTED))
    }
}
