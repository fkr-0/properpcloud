package dev.properpcloud.source.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogSessionTest {
    @Test
    fun `session redacts bearer and permits loopback http`() {
        val session = ServerCatalogSession("http://127.0.0.1:8787/", "top-secret")
        assertTrue(session.normalizedBaseUrl == "http://127.0.0.1:8787")
        assertFalse(session.toString().contains("top-secret"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `remote cleartext URL is rejected`() {
        ServerCatalogSession("http://example.com", "secret")
    }
}
