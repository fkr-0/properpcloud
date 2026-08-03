package dev.properpcloud.source.pcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

enum class PCloudAccountRegion(
    val displayName: String,
    val apiHost: String,
) {
    EUROPE("Europe", "eapi.pcloud.com"),
    UNITED_STATES("United States", "api.pcloud.com"),
}

internal class PCloudDirectLoginRequestPlan(
    val endpoint: java.net.URL,
    val formBody: ByteArray,
) {
    override fun toString(): String =
        "PCloudDirectLoginRequestPlan(endpoint=$endpoint, formBody=<redacted>)"
}

internal fun pCloudDirectLoginRequestPlan(
    apiHost: String,
    email: String,
    password: CharArray,
): PCloudDirectLoginRequestPlan {
    require(apiHost in allowedPCloudApiHosts) { "unsupported pCloud API host" }
    val endpoint = URI("https", apiHost, "/userinfo", null).toURL()
    val body = formBody(
        "username" to email,
        "password" to password.concatToString(),
        "getauth" to "1",
        "logout" to "1",
        "device" to DIRECT_LOGIN_DEVICE_NAME,
        "timeformat" to "timestamp",
        "authexpire" to DIRECT_LOGIN_AUTH_EXPIRE_SECONDS,
        "authinactiveexpire" to DIRECT_LOGIN_INACTIVE_EXPIRE_SECONDS,
    ).toByteArray(Charsets.UTF_8)
    return PCloudDirectLoginRequestPlan(endpoint, body)
}

sealed interface PCloudDirectLoginResult {
    data class Connected(val session: PCloudSession) : PCloudDirectLoginResult
    data class ProviderRejected(
        val providerCode: Int,
        val reason: PCloudDirectLoginRejectionReason,
    ) : PCloudDirectLoginResult
    data object InvalidInput : PCloudDirectLoginResult
    data object InvalidResponse : PCloudDirectLoginResult
    data object NetworkFailure : PCloudDirectLoginResult
}

enum class PCloudDirectLoginRejectionReason {
    CREDENTIALS_OR_REGION,
    TOO_MANY_ATTEMPTS,
    PROVIDER_FAILURE,
    UNKNOWN,
}

internal fun pCloudDirectLoginRejectionReason(resultCode: Int): PCloudDirectLoginRejectionReason =
    when (resultCode) {
        2000 -> PCloudDirectLoginRejectionReason.CREDENTIALS_OR_REGION
        4000 -> PCloudDirectLoginRejectionReason.TOO_MANY_ATTEMPTS
        5000 -> PCloudDirectLoginRejectionReason.PROVIDER_FAILURE
        else -> PCloudDirectLoginRejectionReason.UNKNOWN
    }

class PCloudDirectLoginClient internal constructor(
    private val transport: PCloudDirectLoginTransport,
) {
    constructor() : this(HttpsPCloudDirectLoginTransport())

    suspend fun signIn(
        email: String,
        password: CharArray,
        region: PCloudAccountRegion,
    ): PCloudDirectLoginResult = withContext(Dispatchers.IO) {
        try {
            val normalizedEmail = email.trim()
            if (
                normalizedEmail.isEmpty() ||
                normalizedEmail.length > MAX_EMAIL_CHARS ||
                normalizedEmail.any(Char::isISOControl) ||
                password.isEmpty() ||
                password.size > MAX_PASSWORD_CHARS
            ) {
                return@withContext PCloudDirectLoginResult.InvalidInput
            }

            val response = try {
                transport.login(region.apiHost, normalizedEmail, password)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                return@withContext PCloudDirectLoginResult.NetworkFailure
            } catch (_: RuntimeException) {
                return@withContext PCloudDirectLoginResult.InvalidResponse
            }

            val resultCode = response.resultCode
                ?: return@withContext PCloudDirectLoginResult.InvalidResponse
            if (resultCode != 0) {
                return@withContext PCloudDirectLoginResult.ProviderRejected(
                    providerCode = resultCode,
                    reason = pCloudDirectLoginRejectionReason(resultCode),
                )
            }
            val authToken = response.authToken
            val userId = response.userId
            if (
                authToken.isNullOrBlank() ||
                authToken.length > MAX_TOKEN_CHARS ||
                authToken.any(Char::isISOControl) ||
                userId == null ||
                userId < 0
            ) {
                return@withContext PCloudDirectLoginResult.InvalidResponse
            }
            PCloudDirectLoginResult.Connected(
                PCloudSession(
                    accessToken = authToken,
                    apiHost = region.apiHost,
                    userId = userId,
                    tokenKind = PCloudTokenKind.LEGACY_AUTH_TOKEN,
                ),
            )
        } finally {
            password.fill('\u0000')
        }
    }

    private companion object {
        const val MAX_EMAIL_CHARS = 320
        const val MAX_PASSWORD_CHARS = 1_024
        const val MAX_TOKEN_CHARS = 512
    }
}

internal data class PCloudDirectLoginResponse(
    val resultCode: Int?,
    val authToken: String?,
    val userId: Long?,
)

internal fun interface PCloudDirectLoginTransport {
    @Throws(IOException::class)
    fun login(apiHost: String, email: String, password: CharArray): PCloudDirectLoginResponse
}

internal fun parsePCloudDirectLoginResponse(body: String): PCloudDirectLoginResponse {
    val json = runCatching { JsonParser.parseString(body) as? JsonObject }.getOrNull()
        ?: return PCloudDirectLoginResponse(null, null, null)
    return PCloudDirectLoginResponse(
        resultCode = json.intOrNull("result"),
        authToken = json.stringOrNull("auth"),
        userId = json.longOrNull("userid") ?: json.longOrNull("uid"),
    )
}

private fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrNull()

private fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asLong }?.getOrNull()

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asString }?.getOrNull()

private class HttpsPCloudDirectLoginTransport : PCloudDirectLoginTransport {
    override fun login(
        apiHost: String,
        email: String,
        password: CharArray,
    ): PCloudDirectLoginResponse {
        val requestPlan = pCloudDirectLoginRequestPlan(apiHost, email, password)
        val body = requestPlan.formBody
        var connection: HttpsURLConnection? = null
        try {
            connection = requestPlan.endpoint.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.setRequestProperty("User-Agent", DIRECT_LOGIN_DEVICE_NAME)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use(::readBoundedUtf8).orEmpty()
            return parsePCloudDirectLoginResponse(responseBody)
        } finally {
            body.fill(0)
            connection?.disconnect()
        }
    }

    private fun readBoundedUtf8(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw IOException("pCloud response exceeded the allowed size")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MAX_RESPONSE_BYTES = 65_536
    }
}

private const val DIRECT_LOGIN_DEVICE_NAME = "properpcloud"
private const val DIRECT_LOGIN_AUTH_EXPIRE_SECONDS = "7776000" // 90 days.
private const val DIRECT_LOGIN_INACTIVE_EXPIRE_SECONDS = "2592000" // 30 days.

private fun formBody(vararg values: Pair<String, String>): String = values.joinToString("&") { (name, value) ->
    "${formEncode(name)}=${formEncode(value)}"
}

private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
