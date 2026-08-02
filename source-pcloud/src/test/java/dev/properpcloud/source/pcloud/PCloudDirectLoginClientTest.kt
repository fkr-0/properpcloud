package dev.properpcloud.source.pcloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class PCloudDirectLoginClientTest {
    @Test
    fun buildsRegionalHttpsFormRequestWithoutCredentialsInUrl() {
        val secret = "credential with spaces".toCharArray()
        val plan = pCloudDirectLoginRequestPlan("eapi.pcloud.com", "listener+tag@example.test", secret)
        val values = plan.formBody.toString(Charsets.UTF_8)
            .split('&')
            .associate { entry ->
                val (name, value) = entry.split('=', limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8.name()) to
                    URLDecoder.decode(value, Charsets.UTF_8.name())
            }

        assertEquals("https", plan.endpoint.protocol)
        assertEquals("eapi.pcloud.com", plan.endpoint.host)
        assertEquals("/userinfo", plan.endpoint.path)
        assertTrue(!plan.endpoint.toString().contains("listener"))
        assertTrue(!plan.endpoint.toString().contains("credential"))
        assertEquals("listener+tag@example.test", values["username"])
        assertEquals("credential with spaces", values["password"])
        assertEquals("1", values["getauth"])
        assertEquals("1", values["logout"])
        assertEquals("properpcloud-android", values["device"])
        assertEquals("7776000", values["authexpire"])
        assertEquals("2592000", values["authinactiveexpire"])
        assertTrue(!plan.toString().contains("credential with spaces"))
        plan.formBody.fill(0)
        secret.fill('\u0000')
    }

    @Test
    fun requestPlanRejectsUntrustedApiHost() {
        val secret = "never-send".toCharArray()
        try {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                pCloudDirectLoginRequestPlan("attacker.invalid", "listener@example.test", secret)
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    @Test
    fun parsesDocumentedSuccessAndProviderErrorShapes() {
        assertEquals(
            PCloudDirectLoginResponse(0, "auth-token", 77),
            parsePCloudDirectLoginResponse("""{"result":0,"auth":"auth-token","userid":77}"""),
        )
        assertEquals(
            PCloudDirectLoginResponse(2000, null, null),
            parsePCloudDirectLoginResponse("""{"result":2000,"error":"login failed"}"""),
        )
        assertEquals(
            PCloudDirectLoginResponse(null, null, null),
            parsePCloudDirectLoginResponse("not-json"),
        )
    }

    @Test
    fun createsRegionalLegacySessionAndClearsPasswordBuffer() = runTest {
        val password = "correct horse battery staple".toCharArray()
        val client = PCloudDirectLoginClient { host, email, providedPassword ->
            assertEquals("eapi.pcloud.com", host)
            assertEquals("listener@example.test", email)
            assertEquals("correct horse battery staple", providedPassword.concatToString())
            PCloudDirectLoginResponse(resultCode = 0, authToken = "legacy-auth-token", userId = 42)
        }

        val result = client.signIn(
            email = " listener@example.test ",
            password = password,
            region = PCloudAccountRegion.EUROPE,
        )

        val session = (result as PCloudDirectLoginResult.Connected).session
        assertEquals("eapi.pcloud.com", session.apiHost)
        assertEquals(42L, session.userId)
        assertEquals(PCloudTokenKind.LEGACY_AUTH_TOKEN, session.tokenKind)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun malformedProviderResponseIsNotMisreportedAsCredentialRejection() = runTest {
        val password = "never-print-this".toCharArray()
        val client = PCloudDirectLoginClient { _, _, _ ->
            PCloudDirectLoginResponse(resultCode = null, authToken = null, userId = null)
        }

        assertEquals(
            PCloudDirectLoginResult.InvalidResponse,
            client.signIn("listener@example.test", password, PCloudAccountRegion.EUROPE),
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun retainsOnlyNumericProviderRejectionAndClearsPassword() = runTest {
        val password = "not-the-right-password".toCharArray()
        val client = PCloudDirectLoginClient { _, _, _ ->
            PCloudDirectLoginResponse(resultCode = 2000, authToken = null, userId = null)
        }

        assertEquals(
            PCloudDirectLoginResult.ProviderRejected(2000),
            client.signIn("listener@example.test", password, PCloudAccountRegion.UNITED_STATES),
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun networkFailureIsTypedAndContainsNoCredentialMaterial() = runTest {
        val password = "never-print-this".toCharArray()
        val client = PCloudDirectLoginClient { _, _, _ -> throw IOException("never-print-this") }

        assertEquals(
            PCloudDirectLoginResult.NetworkFailure,
            client.signIn("listener@example.test", password, PCloudAccountRegion.EUROPE),
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun rejectsInvalidInputBeforeTransportAndStillClearsPassword() = runTest {
        var called = false
        val password = CharArray(0)
        val client = PCloudDirectLoginClient { _, _, _ ->
            called = true
            PCloudDirectLoginResponse(0, "token", 1)
        }

        assertEquals(
            PCloudDirectLoginResult.InvalidInput,
            client.signIn("", password, PCloudAccountRegion.EUROPE),
        )
        assertTrue(!called)
    }
}
