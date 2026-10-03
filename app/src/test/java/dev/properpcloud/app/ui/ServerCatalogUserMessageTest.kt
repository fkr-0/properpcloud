package dev.properpcloud.app.ui

import dev.properpcloud.source.server.ServerCatalogFailureKind
import dev.properpcloud.source.server.ServerCatalogRequestException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogUserMessageTest {
    @Test
    fun `server authentication and permission failures are actionable without leaking status details`() {
        val authentication = ServerCatalogRequestException(ServerCatalogFailureKind.AUTHENTICATION, 401)
            .sourceUserMessage("Could not connect server library")
        val permission = ServerCatalogRequestException(ServerCatalogFailureKind.PERMISSION, 403)
            .sourceUserMessage("Could not load folder")

        assertTrue(authentication.contains("credentials were rejected"))
        assertTrue(authentication.contains("API token"))
        assertFalse(authentication.contains("401"))
        assertTrue(permission.contains("do not permit"))
        assertFalse(permission.contains("403"))
    }

    @Test
    fun `server availability and compatibility failures stay safely typed for UI recovery`() {
        val throttled = ServerCatalogRequestException(ServerCatalogFailureKind.RATE_LIMITED, 429)
            .sourceUserMessage("Could not load folder")
        val unavailable = ServerCatalogRequestException(ServerCatalogFailureKind.SERVER_UNAVAILABLE, 503)
            .sourceUserMessage("Could not load folder")
        val invalid = ServerCatalogRequestException(ServerCatalogFailureKind.INVALID_RESPONSE, 200)
            .sourceUserMessage("Could not load folder")

        assertTrue(throttled.contains("rate limiting"))
        assertTrue(unavailable.contains("temporarily unavailable"))
        assertTrue(invalid.contains("invalid catalog data"))
        assertFalse(invalid.contains("200"))
    }
}
