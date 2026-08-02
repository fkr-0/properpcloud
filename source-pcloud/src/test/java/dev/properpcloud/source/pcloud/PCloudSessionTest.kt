package dev.properpcloud.source.pcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PCloudSessionTest {
    @Test
    fun acceptsOnlyDocumentedRegionalApiHosts() {
        assertEquals("api.pcloud.com", PCloudSession("token", "api.pcloud.com", 1).apiHost)
        assertEquals("eapi.pcloud.com", PCloudSession("token", "eapi.pcloud.com", 1).apiHost)
        assertThrows(IllegalArgumentException::class.java) {
            PCloudSession("token", "attacker.invalid", 1)
        }
    }

    @Test
    fun stringRepresentationRedactsToken() {
        val session = PCloudSession("super-secret-token", "api.pcloud.com", 1)
        assertEquals(
            "PCloudSession(accessToken=<redacted>, apiHost=api.pcloud.com, userId=1, tokenKind=OAUTH_BEARER)",
            session.toString(),
        )
    }

    @Test
    fun rejectsBlankTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            PCloudSession("", "api.pcloud.com", 1)
        }
    }
}
