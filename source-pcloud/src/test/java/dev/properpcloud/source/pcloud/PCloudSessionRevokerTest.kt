package dev.properpcloud.source.pcloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PCloudSessionRevokerTest {
    private val session = PCloudSession("token-never-logged", "eapi.pcloud.com", 42)

    @Test
    fun buildsTokenKindSpecificRedactedLogoutRequests() {
        val oauthPlan = pCloudLogoutRequestPlan(session)
        assertEquals("GET", oauthPlan.method)
        assertEquals("Bearer token-never-logged", oauthPlan.authorizationHeader)
        assertNull(oauthPlan.formBody)
        assertTrue(!oauthPlan.toString().contains("token-never-logged"))

        val legacyPlan = pCloudLogoutRequestPlan(
            session.copy(tokenKind = PCloudTokenKind.LEGACY_AUTH_TOKEN),
        )
        assertEquals("POST", legacyPlan.method)
        assertNull(legacyPlan.authorizationHeader)
        assertEquals("auth=token-never-logged", legacyPlan.formBody?.toString(Charsets.UTF_8))
        assertTrue(!legacyPlan.toString().contains("token-never-logged"))
        legacyPlan.formBody?.fill(0)
    }

    @Test
    fun reportsProviderConfirmedRevocation() = runTest {
        val revoker = PCloudSessionRevoker { supplied ->
            assertEquals("eapi.pcloud.com", supplied.apiHost)
            assertEquals("token-never-logged", supplied.accessToken)
            PCloudLogoutResponse(resultCode = 0, authDeleted = true)
        }

        assertEquals(PCloudRevocationResult.Revoked, revoker.revoke(session))
    }

    @Test
    fun treatsAnInvalidOrExpiredTokenAsAlreadyInactive() = runTest {
        val revoker = PCloudSessionRevoker {
            PCloudLogoutResponse(resultCode = 1000, authDeleted = null)
        }

        assertEquals(PCloudRevocationResult.AlreadyInactive, revoker.revoke(session))
    }

    @Test
    fun networkFailureIsTypedAndContainsNoCredentialMaterial() = runTest {
        val revoker = PCloudSessionRevoker { throw IOException("token-never-logged") }

        assertEquals(
            PCloudRevocationResult.Failed(PCloudRevocationFailure.NETWORK),
            revoker.revoke(session),
        )
    }

    @Test
    fun providerRejectionRetainsOnlyTheNumericCode() = runTest {
        val revoker = PCloudSessionRevoker {
            PCloudLogoutResponse(resultCode = 5000, authDeleted = null)
        }

        assertEquals(
            PCloudRevocationResult.Failed(PCloudRevocationFailure.PROVIDER_REJECTED, 5000),
            revoker.revoke(session),
        )
    }
}
