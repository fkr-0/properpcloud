package dev.properpcloud.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class PCloudProviderEntry(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val fileId: Long? = null,
    val folderId: Long? = null,
    val parentFolderId: Long? = null,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val providerHash: String? = null,
)

class PCloudRestClient(
    private val sessionFile: PCloudSessionFile,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_2)
        .build(),
) {
    fun health(): Boolean = runCatching {
        request("/userinfo", mapOf("timeformat" to "timestamp")).also(::requireSuccess)
        true
    }.getOrDefault(false)

    fun listAll(): List<PCloudProviderEntry> {
        val response = request(
            "/listfolder",
            mapOf("folderid" to "0", "recursive" to "1", "showdeleted" to "0", "timeformat" to "timestamp"),
        )
        requireSuccess(response)
        val root = response.getAsJsonObject("metadata") ?: error("pCloud listfolder omitted metadata")
        val entries = mutableListOf<PCloudProviderEntry>()
        walkMetadata(root, "", entries)
        return entries
    }

    private fun providerEntry(value: JsonObject, path: String): PCloudProviderEntry = PCloudProviderEntry(
        path = path,
        name = value.get("name")?.asString?.ifEmpty { "pCloud" } ?: "pCloud",
        isFolder = value.get("isfolder")?.asBoolean == true,
        fileId = value.get("fileid")?.takeUnless { it.isJsonNull }?.asLong,
        folderId = value.get("folderid")?.takeUnless { it.isJsonNull }?.asLong,
        parentFolderId = value.get("parentfolderid")?.takeUnless { it.isJsonNull }?.asLong,
        sizeBytes = value.get("size")?.takeUnless { it.isJsonNull }?.asLong,
        modifiedAtEpochMillis = value.get("modified")?.takeUnless { it.isJsonNull }?.asLong?.let { it * 1000L },
        providerHash = value.get("hash")?.takeUnless { it.isJsonNull }?.asString,
    )

    fun createFolder(parentFolderId: Long, name: String): Long {
        require(parentFolderId >= 0) { "invalid parent folder id" }
        require(name.isNotBlank() && name.length <= 255 && '/' !in name && '\u0000' !in name) { "invalid folder name" }
        val response = request("/createfolder", mapOf("folderid" to parentFolderId.toString(), "name" to name))
        requireSuccess(response)
        return response.getAsJsonObject("metadata")?.get("folderid")?.asLong
            ?: error("pCloud createfolder omitted folder id")
    }

    fun createFileLink(fileId: Long): String {
        require(fileId > 0) { "invalid pCloud file id" }
        val response = request("/getfilelink", mapOf("fileid" to fileId.toString()))
        requireSuccess(response)
        val host = response.getAsJsonArray("hosts")?.firstOrNull()?.asString ?: error("pCloud getfilelink omitted hosts")
        val path = response.get("path")?.asString ?: error("pCloud getfilelink omitted path")
        return "https://$host$path"
    }

    fun statFile(fileId: Long): PCloudProviderEntry {
        require(fileId > 0) { "invalid pCloud file id" }
        val response = request("/stat", mapOf("fileid" to fileId.toString(), "timeformat" to "timestamp"))
        requireSuccess(response)
        val metadata = response.getAsJsonObject("metadata") ?: error("pCloud stat omitted metadata")
        return providerEntry(metadata, metadata.get("path")?.asString?.removePrefix("/").orEmpty())
    }

    private fun request(path: String, query: Map<String, String>, allowReload: Boolean = true): JsonObject {
        val session = sessionFile.load()
        val encoded = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val uri = URI("https://${session.apiHost}$path${if (encoded.isEmpty()) "" else "?$encoded"}")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.accessToken}")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body().use(::readBoundedUtf8)
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrElse {
            error("pCloud returned malformed JSON")
        }
        val providerResult = json.get("result")?.asInt
        if (allowReload && (response.statusCode() in setOf(401, 403) || providerResult == 2000)) {
            sessionFile.load(force = true)
            return request(path, query, allowReload = false)
        }
        require(response.statusCode() in 200..299) { "pCloud request failed with HTTP ${response.statusCode()}" }
        return json
    }

    private fun requireSuccess(response: JsonObject) {
        val result = response.get("result")?.asInt ?: error("pCloud response omitted result")
        require(result == 0) { "pCloud request failed with provider code $result" }
    }

    private fun walkMetadata(value: JsonObject, parentPath: String, output: MutableList<PCloudProviderEntry>) {
        val name = value.get("name")?.asString.orEmpty()
        val isFolder = value.get("isfolder")?.asBoolean == true
        val path = when {
            parentPath.isEmpty() && name.isEmpty() -> ""
            parentPath.isEmpty() -> name
            name.isEmpty() -> parentPath
            else -> "$parentPath/$name"
        }
        output += providerEntry(value, path)
        if (isFolder) {
            value.getAsJsonArray("contents")?.forEach { child ->
                if (child.isJsonObject) walkMetadata(child.asJsonObject, path, output)
            }
        }
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_RESPONSE_BYTES) { "pCloud response exceeded allowed size" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
    }
}
