package com.pcloud.sdk.internal

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LegacyTokenAuthenticatorsTest {
    @Test
    fun movesSdkQueryParametersAndLegacyTokenIntoPostBody() {
        val original = Request.Builder()
            .url("https://eapi.pcloud.com/listfolder?folderid=42&recursive=1")
            .get()
            .build()

        val authenticated = LegacyTokenAuthenticators.authenticate(original, "legacy-secret")
        val body = authenticated.body as LegacyTokenAuthenticators.OneShotFormBody

        assertEquals("POST", authenticated.method)
        assertEquals("https://eapi.pcloud.com/listfolder", authenticated.url.toString())
        assertEquals(
            mapOf("folderid" to "42", "recursive" to "1", "auth" to "legacy-secret"),
            (0 until body.size()).associate { body.name(it) to body.value(it) },
        )
        assertEquals(true, body.isOneShot())
        assertFalse(body.toString().contains("legacy-secret"))
        assertFalse(authenticated.headers.names().contains("Authorization"))
    }

    @Test
    fun refusesToAttachTokenOutsideDocumentedRegionalHosts() {
        val request = Request.Builder().url("https://attacker.invalid/listfolder").get().build()

        assertThrows(IOException::class.java) {
            LegacyTokenAuthenticators.authenticate(request, "legacy-secret")
        }
    }

    @Test
    fun passesHttpsContentCapabilityThroughWithoutAccountToken() {
        val request = Request.Builder()
            .url("https://temporary-content.example.test/file?capability=abc")
            .get()
            .build()

        val prepared = LegacyTokenAuthenticators.prepareRequest(request, "legacy-secret")

        assertEquals(request, prepared)
        assertEquals("GET", prepared.method)
        assertFalse(prepared.url.toString().contains("legacy-secret"))
        assertFalse(prepared.headers.names().contains("Authorization"))
    }

    @Test
    fun rejectsCleartextContentCapability() {
        val request = Request.Builder().url("http://temporary-content.example.test/file").get().build()

        assertThrows(IOException::class.java) {
            LegacyTokenAuthenticators.prepareRequest(request, "legacy-secret")
        }
    }
}
