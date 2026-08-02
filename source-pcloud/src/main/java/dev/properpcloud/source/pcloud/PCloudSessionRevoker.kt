package dev.properpcloud.source.pcloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

enum class PCloudRevocationFailure {
    NETWORK,
    PROVIDER_REJECTED,
    INVALID_RESPONSE,
}

sealed interface PCloudRevocationResult {
    data object Revoked : PCloudRevocationResult
    data object AlreadyInactive : PCloudRevocationResult
    data class Failed(
        val reason: PCloudRevocationFailure,
        val providerCode: Int? = null,
    ) : PCloudRevocationResult
}

class PCloudSessionRevoker internal constructor(
    private val transport: PCloudLogoutTransport,
) {
    constructor() : this(HttpsPCloudLogoutTransport())

    suspend fun revoke(session: PCloudSession): PCloudRevocationResult = withContext(Dispatchers.IO) {
        val response = try {
            transport.logout(session)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return@withContext PCloudRevocationResult.Failed(PCloudRevocationFailure.NETWORK)
        } catch (_: RuntimeException) {
            return@withContext PCloudRevocationResult.Failed(PCloudRevocationFailure.INVALID_RESPONSE)
        }

        when {
            response.resultCode == 0 && response.authDeleted == true -> PCloudRevocationResult.Revoked
            response.resultCode == 1000 || response.resultCode == 2000 -> PCloudRevocationResult.AlreadyInactive
            response.resultCode == 0 -> PCloudRevocationResult.Failed(PCloudRevocationFailure.INVALID_RESPONSE)
            response.resultCode != null -> PCloudRevocationResult.Failed(
                PCloudRevocationFailure.PROVIDER_REJECTED,
                response.resultCode,
            )
            else -> PCloudRevocationResult.Failed(PCloudRevocationFailure.INVALID_RESPONSE)
        }
    }
}

internal data class PCloudLogoutResponse(
    val resultCode: Int?,
    val authDeleted: Boolean?,
)

internal fun interface PCloudLogoutTransport {
    @Throws(IOException::class)
    fun logout(session: PCloudSession): PCloudLogoutResponse
}

internal class PCloudLogoutRequestPlan(
    val method: String,
    val authorizationHeader: String?,
    val formBody: ByteArray?,
) {
    override fun toString(): String =
        "PCloudLogoutRequestPlan(method=$method, authorizationHeader=<redacted>, formBody=<redacted>)"
}

internal fun pCloudLogoutRequestPlan(session: PCloudSession): PCloudLogoutRequestPlan =
    when (session.tokenKind) {
        PCloudTokenKind.OAUTH_BEARER -> PCloudLogoutRequestPlan(
            method = "GET",
            authorizationHeader = "Bearer ${session.accessToken}",
            formBody = null,
        )
        PCloudTokenKind.LEGACY_AUTH_TOKEN -> PCloudLogoutRequestPlan(
            method = "POST",
            authorizationHeader = null,
            formBody = "auth=${java.net.URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())}"
                .toByteArray(Charsets.UTF_8),
        )
    }

private class HttpsPCloudLogoutTransport : PCloudLogoutTransport {
    override fun logout(session: PCloudSession): PCloudLogoutResponse {
        require(session.apiHost in allowedPCloudApiHosts) { "unsupported pCloud API host" }
        val connection = URI("https", session.apiHost, "/logout", null).toURL().openConnection() as HttpsURLConnection
        val requestPlan = pCloudLogoutRequestPlan(session)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "properpcloud-android")
            connection.requestMethod = requestPlan.method
            requestPlan.authorizationHeader?.let { header ->
                connection.setRequestProperty("Authorization", header)
            }
            requestPlan.formBody?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader ->
                    buildString {
                        val buffer = CharArray(1_024)
                        var remaining = MAX_RESPONSE_CHARS
                        while (remaining > 0) {
                            val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                            if (read < 0) break
                            append(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                .orEmpty()
            return PCloudLogoutResponse(
                resultCode = RESULT_PATTERN.find(body)?.groupValues?.get(1)?.toIntOrNull(),
                authDeleted = AUTH_DELETED_PATTERN.find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
            )
        } finally {
            requestPlan.formBody?.fill(0)
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_CHARS = 16_384
        val RESULT_PATTERN = Regex("\\\"result\\\"\\s*:\\s*(\\d+)")
        val AUTH_DELETED_PATTERN = Regex("\\\"auth_deleted\\\"\\s*:\\s*(true|false)")
    }
}
