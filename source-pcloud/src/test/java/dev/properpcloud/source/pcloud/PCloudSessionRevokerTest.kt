package dev.properpcloud.source.pcloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PCloudSessionRevokerTest {
    private val session = PCloudSession("token-never-logged", "eapi.pcloud.com", 42)

    @Test
    fun reportsProviderConfirmedRevocation() = runTest {
        val revoker = PCloudSessionRevoker { host, token ->
            assertEquals("eapi.pcloud.com", host)
            assertEquals("token-never-logged", token)
            PCloudLogoutResponse(resultCode = 0, authDeleted = true)
        }

        assertEquals(PCloudRevocationResult.Revoked, revoker.revoke(session))
    }

    @Test
    fun treatsAnInvalidOrExpiredTokenAsAlreadyInactive() = runTest {
        val revoker = PCloudSessionRevoker { _, _ ->
            PCloudLogoutResponse(resultCode = 1000, authDeleted = null)
        }

        assertEquals(PCloudRevocationResult.AlreadyInactive, revoker.revoke(session))
    }

    @Test
    fun networkFailureIsTypedAndContainsNoCredentialMaterial() = runTest {
        val revoker = PCloudSessionRevoker { _, _ -> throw IOException("token-never-logged") }

        assertEquals(
            PCloudRevocationResult.Failed(PCloudRevocationFailure.NETWORK),
            revoker.revoke(session),
        )
    }

    @Test
    fun providerRejectionRetainsOnlyTheNumericCode() = runTest {
        val revoker = PCloudSessionRevoker { _, _ ->
            PCloudLogoutResponse(resultCode = 5000, authDeleted = null)
        }

        assertEquals(
            PCloudRevocationResult.Failed(PCloudRevocationFailure.PROVIDER_REJECTED, 5000),
            revoker.revoke(session),
        )
    }
}
