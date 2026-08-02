package dev.properpcloud.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PCloudOAuthConfigurationTest {
    @Test
    fun bundledClientIdMakesOrdinaryLoginReady() {
        val configuration = PCloudOAuthConfiguration.resolve(" bundled-app ", "")

        assertEquals("bundled-app", configuration.clientId)
        assertTrue(configuration.isConfigured)
        assertTrue(configuration.usesBundledClientId)
        assertFalse(configuration.usesCustomClientId)
    }

    @Test
    fun explicitDeveloperOverrideWinsWithoutExposingASecret() {
        val configuration = PCloudOAuthConfiguration.resolve("bundled-app", " personal-test-app ")

        assertEquals("personal-test-app", configuration.clientId)
        assertEquals(PCloudClientIdSource.CUSTOM, configuration.source)
    }

    @Test
    fun missingConfigurationFailsClosed() {
        val configuration = PCloudOAuthConfiguration.resolve("  ", "\n")

        assertFalse(configuration.isConfigured)
        assertEquals(PCloudClientIdSource.MISSING, configuration.source)
    }

    @Test
    fun redirectUriMatchesTheOfficialSdkPackageConvention() {
        assertEquals(
            "pcloud-oauth://dev.properpcloud.app",
            PCloudOAuthConfiguration.redirectUri("dev.properpcloud.app"),
        )
    }
}
