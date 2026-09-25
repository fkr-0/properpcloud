package dev.properpcloud.source.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.LibraryFile
import dev.properpcloud.core.model.LibraryFileKind
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerPlaybackState
import dev.properpcloud.core.model.PlayerTargetId
import dev.properpcloud.core.model.PlayerTargetProvider
import dev.properpcloud.core.model.PlayerTargetSnapshot
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import dev.properpcloud.core.model.StreamResolutionException
import dev.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

data class ServerCatalogSession(
    val baseUrl: String,
    val apiToken: String? = null,
) {
    init {
        val uri = URI(baseUrl.trimEnd('/'))
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in loopbackHosts)) {
            "server catalog must use HTTPS except for loopback development"
        }
        require(!uri.host.isNullOrBlank()) { "server catalog URL requires a host" }
        require(apiToken == null || (apiToken.isNotBlank() && apiToken.length <= 4096)) { "invalid server API token" }
    }

    val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    override fun toString(): String = "ServerCatalogSession(baseUrl=$normalizedBaseUrl, apiToken=<redacted>)"

    private companion object {
        val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")
    }
}

internal class ServerCatalogHttpException(
    val statusCode: Int,
) : IOException("server catalog request failed with HTTP $statusCode")

internal fun classifyServerStreamResolutionFailure(error: Exception): StreamResolutionException = when (error) {
    is StreamResolutionException -> error
    is ServerCatalogHttpException -> StreamResolutionException(
        kind = if (error.statusCode in setOf(404, 410)) {
            StreamResolutionFailureKind.ITEM_UNAVAILABLE
        } else {
            StreamResolutionFailureKind.TRANSIENT
        },
        message = if (error.statusCode in setOf(404, 410)) {
            "server catalog media item is unavailable"
        } else {
            "server catalog stream capability is temporarily unavailable"
        },
        cause = error,
    )
    is IOException -> StreamResolutionException(
        StreamResolutionFailureKind.TRANSIENT,
        "server catalog stream capability is temporarily unavailable",
        error,
    )
    else -> StreamResolutionException(
        StreamResolutionFailureKind.TRANSIENT,
        "server catalog stream capability could not be resolved",
        error,
    )
}

class ServerCatalogAudioSource(
    private val session: ServerCatalogSession,
    override val id: SourceId = SourceId("server"),
) : AudioSource, PlayerTargetProvider {
    private val playerProviderFingerprint = UUID.nameUUIDFromBytes(session.normalizedBaseUrl.toByteArray()).toString()
    override val providerId: String = "server:$playerProviderFingerprint"
    override val providerName: String = "Server"

    override val root = AudioFolder(
        sourceId = id,
        id = NodeId("catalog:root"),
        parentId = null,
        name = "Server library",
    )

    override suspend fun list(folderId: NodeId): List<MediaNode> = withContext(Dispatchers.IO) {
        requestJson("/api/v1/library/browse?parent=${encode(folderId.value)}")
            .getAsJsonArray("entries")
            .mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let(::toMediaNode)
            }
    }

    override suspend fun load(nodeId: NodeId): MediaNode = withContext(Dispatchers.IO) {
        val json = requestJson("/api/v1/library/node?id=${encode(nodeId.value)}")
        requireNotNull(toMediaNode(json.getAsJsonObject("entry"))) { "catalog node is unavailable" }
    }

    override suspend fun resolveStream(trackId: NodeId): StreamHandle = withContext(Dispatchers.IO) {
        try {
            val json = requestJson("/api/v1/library/stream-link?id=${encode(trackId.value)}", method = "POST")
            StreamHandle(
                url = json.get("url").asString,
                expiresAtEpochMillis = json.get("expiresAtEpochMillis")?.takeUnless { it.isJsonNull }?.asLong,
                contentType = json.get("contentType")?.takeUnless { it.isJsonNull }?.asString,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw classifyServerStreamResolutionFailure(error)
        }
    }

    override suspend fun inspect(nodeId: NodeId): NodeInspection = withContext(Dispatchers.IO) {
        val entry = requestJson("/api/v1/library/node?id=${encode(nodeId.value)}").getAsJsonObject("entry")
        val fields = linkedMapOf<String, String>()
        listOf(
            "nodeId", "path", "name", "kind", "contentHash", "title", "artist", "album", "genre",
            "durationMillis", "sampleRate", "bitrateKbps", "channels", "bitDepth", "format", "metadataError",
        ).forEach { name ->
            entry.get(name)?.takeUnless { it.isJsonNull }?.let { fields[name] = it.asString }
        }
        NodeInspection(fields)
    }

    override suspend fun discoverPlayers(): List<PlayerTargetSnapshot> = withContext(Dispatchers.IO) {
        val response = try {
            requestJson("/api/v1/players")
        } catch (error: ServerCatalogHttpException) {
            if (error.statusCode == 404) return@withContext emptyList()
            throw error
        }
        response.getAsJsonArray("players")
            ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject?.let(::toPlayerTarget) }
            .orEmpty()
    }

    private fun requestJson(path: String, method: String = "GET"): JsonObject {
        val connection = URI(session.normalizedBaseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            session.apiToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (method != "GET") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.use { }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use(::readBoundedUtf8).orEmpty()
            if (status !in 200..299) throw ServerCatalogHttpException(status)
            return JsonParser.parseString(body).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_RESPONSE_BYTES) { "server catalog response exceeded size limit" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun toMediaNode(entry: JsonObject): MediaNode? {
        val nodeId = NodeId(entry.get("nodeId")?.asString ?: return null)
        val parentId = entry.get("parentNodeId")?.takeUnless { it.isJsonNull }?.asString?.let(::NodeId)
        val name = entry.get("name")?.asString ?: return null
        val modified = entry.get("modifiedAtEpochMillis")?.takeUnless { it.isJsonNull }?.asLong
        return when (entry.get("kind")?.asString) {
            "folder" -> AudioFolder(id, nodeId, parentId, name, modified)
            "audio" -> {
                val parent = parentId ?: return null
                AudioTrack(
                    sourceId = id,
                    id = nodeId,
                    parentId = parent,
                    name = name,
                    modifiedAtEpochMillis = modified,
                    contentType = entry.get("contentType")?.takeUnless { it.isJsonNull }?.asString,
                    sizeBytes = entry.get("sizeBytes")?.takeUnless { it.isJsonNull }?.asLong,
                    discNumber = entry.get("discNumber")?.takeUnless { it.isJsonNull }?.asInt,
                    trackNumber = entry.get("trackNumber")?.takeUnless { it.isJsonNull }?.asInt,
                    taggedTitle = entry.get("title")?.takeUnless { it.isJsonNull }?.asString,
                    durationMillis = entry.get("durationMillis")?.takeUnless { it.isJsonNull }?.asLong,
                )
            }
            "file" -> {
                val parent = parentId ?: return null
                LibraryFile(
                    sourceId = id,
                    id = nodeId,
                    parentId = parent,
                    name = name,
                    modifiedAtEpochMillis = modified,
                    contentType = entry.get("contentType")?.takeUnless { it.isJsonNull }?.asString,
                    sizeBytes = entry.get("sizeBytes")?.takeUnless { it.isJsonNull }?.asLong,
                    kind = LibraryFileKind.fromFilename(name),
                )
            }
            else -> null
        }
    }

    private fun toPlayerTarget(player: JsonObject): PlayerTargetSnapshot? {
        val remoteId = player.get("id")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        val displayName = player.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        if (remoteId.isEmpty() || displayName.isEmpty()) return null
        val media = player.getAsJsonObject("currentMedia")
        return PlayerTargetSnapshot(
            id = PlayerTargetId("$providerId:$remoteId"),
            providerId = providerId,
            providerName = providerName,
            displayName = displayName,
            connectivity = enumField(player, "connectivity", PlayerConnectivity.UNAVAILABLE),
            playbackState = enumField(player, "playbackState", PlayerPlaybackState.UNKNOWN),
            currentMediaId = media?.get("id")?.takeUnless { it.isJsonNull }?.asString,
            currentMediaTitle = media?.get("title")?.takeUnless { it.isJsonNull }?.asString,
            currentMediaSubtitle = media?.get("subtitle")?.takeUnless { it.isJsonNull }?.asString,
            controllable = false,
            local = false,
            observedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private inline fun <reified T : Enum<T>> enumField(player: JsonObject, name: String, fallback: T): T =
        player.get(name)
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.uppercase()
            ?.let { encoded -> enumValues<T>().firstOrNull { it.name == encoded } }
            ?: fallback

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
