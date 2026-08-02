package dev.properpcloud.metadata.online

import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

data class MetadataSearchQuery(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val durationMillis: Long? = null,
) {
    init {
        require(listOf(title, artist, album, isrc).any { !it.isNullOrBlank() }) {
            "metadata search requires title, artist, album, or ISRC"
        }
        require(durationMillis == null || durationMillis > 0) { "duration must be positive" }
    }

}

interface OnlineMetadataProvider {
    suspend fun search(query: MetadataSearchQuery, limit: Int = 10): List<MetadataCandidate>
}

data class HttpResponse(val status: Int, val body: ByteArray)

fun interface HttpTransport {
    suspend fun get(uri: URI, userAgent: String): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override suspend fun get(uri: URI, userAgent: String): HttpResponse = withContext(Dispatchers.IO) {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/xml")
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.use(::readBounded) ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            check(output.size() + read <= MAX_RESPONSE_BYTES) { "metadata response exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

class RequestRateGate(
    private val minimumIntervalMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(minimumIntervalMillis >= 0) { "rate interval must not be negative" }
    }

    private val mutex = Mutex()
    private var lastRequestAt = Long.MIN_VALUE

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        if (lastRequestAt != Long.MIN_VALUE) {
            val wait = minimumIntervalMillis - (nowMillis() - lastRequestAt)
            if (wait > 0) sleeper(wait)
        }
        lastRequestAt = nowMillis()
        block()
    }
}

class MusicBrainzMetadataProvider(
    applicationName: String,
    applicationVersion: String,
    contactUrl: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val rateGate: RequestRateGate = RequestRateGate(1_100),
) : OnlineMetadataProvider {
    private val userAgent = "$applicationName/$applicationVersion ($contactUrl)"

    init {
        require(applicationName.isNotBlank()) { "application name must not be blank" }
        require(applicationVersion.isNotBlank()) { "application version must not be blank" }
        require(contactUrl.startsWith("https://")) { "contact URL must use HTTPS" }
    }

    override suspend fun search(query: MetadataSearchQuery, limit: Int): List<MetadataCandidate> {
        require(limit in 1..100) { "MusicBrainz search limit must be between 1 and 100" }
        val response = rateGate.run { transport.get(buildSearchUri(query, limit), userAgent) }
        check(response.status == 200) { "MusicBrainz request failed with HTTP ${response.status}" }
        return parseRecordingSearch(response.body)
    }

    internal fun buildSearchUri(query: MetadataSearchQuery, limit: Int): URI {
        val terms = buildList {
            query.isrc?.takeIf(String::isNotBlank)?.let { add("isrc:${lucene(it)}") }
            query.title?.takeIf(String::isNotBlank)?.let { add("recording:${lucene(it)}") }
            query.artist?.takeIf(String::isNotBlank)?.let { add("artist:${lucene(it)}") }
            query.album?.takeIf(String::isNotBlank)?.let { add("release:${lucene(it)}") }
            query.durationMillis?.let { duration ->
                val tolerance = 2_000
                add("dur:[${(duration - tolerance).coerceAtLeast(0)} TO ${duration + tolerance}]")
            }
        }
        val encoded = URLEncoder.encode(terms.joinToString(" AND "), StandardCharsets.UTF_8.name())
        return URI("https://musicbrainz.org/ws/2/recording/?query=$encoded&limit=$limit")
    }

    internal fun parseRecordingSearch(xml: ByteArray): List<MetadataCandidate> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val recordings = document.getElementsByTagNameNS("*", "recording")
        return buildList {
            for (index in 0 until recordings.length.coerceAtMost(100)) {
                val recording = recordings.item(index) as? Element ?: continue
                val id = recording.getAttribute("id").takeIf(String::isNotBlank) ?: continue
                val score = recording.getAttributeNS(EXT_NAMESPACE, "score")
                    .ifBlank { recording.getAttribute("ext:score") }
                    .toDoubleOrNull()?.div(100.0)?.coerceIn(0.0, 1.0) ?: 0.5
                val title = recording.directChildText("title") ?: continue
                val artist = recording.firstDescendantText("artist-credit", "name")
                val release = recording.firstDescendant("release-list", "release")
                val releaseId = release?.getAttribute("id")?.takeIf(String::isNotBlank)
                val album = release?.directChildText("title")
                val date = release?.directChildText("date")
                val fields = linkedMapOf<TagField, MetadataValue>(
                    TagField.TITLE to onlineValue(title, score),
                    TagField.MUSICBRAINZ_RECORDING_ID to onlineValue(id, score),
                )
                artist?.let { fields[TagField.ARTIST] = onlineValue(it, score) }
                album?.let { fields[TagField.ALBUM] = onlineValue(it, score) }
                date?.take(4)?.takeIf { it.all(Char::isDigit) }?.let {
                    fields[TagField.YEAR] = onlineValue(it, score)
                }
                releaseId?.let { fields[TagField.MUSICBRAINZ_RELEASE_ID] = onlineValue(it, score) }
                add(
                    MetadataCandidate(
                        id = id,
                        provider = MetadataProvenance.MUSICBRAINZ,
                        score = score,
                        fields = fields,
                        releaseId = releaseId,
                        coverArtUrl = releaseId?.let(CoverArtArchive::frontThumbnail),
                    ),
                )
            }
        }
    }

    private fun onlineValue(value: String, score: Double) =
        MetadataValue(value, MetadataProvenance.MUSICBRAINZ, score)

    private fun lucene(value: String): String =
        "\"${value.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private companion object {
        const val EXT_NAMESPACE = "http://musicbrainz.org/ns/ext#-2.0"
    }
}

object CoverArtArchive {
    fun frontThumbnail(releaseId: String, size: Int = 500): String {
        require(releaseId.matches(UUID_PATTERN)) { "release ID must be a UUID" }
        require(size in setOf(250, 500, 1200)) { "unsupported Cover Art Archive thumbnail size" }
        return "https://coverartarchive.org/release/$releaseId/front-$size"
    }

    private val UUID_PATTERN = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )
}

data class AcousticFingerprint(
    val durationSeconds: Int,
    val encodedFingerprint: String,
) {
    init {
        require(durationSeconds > 0) { "fingerprint duration must be positive" }
        require(encodedFingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }

    override fun toString(): String =
        "AcousticFingerprint(durationSeconds=$durationSeconds, encodedFingerprint=<redacted>)"
}

data class AcoustIdLookupRequest(
    val applicationKey: String,
    val fingerprint: AcousticFingerprint,
) {
    init {
        require(applicationKey.isNotBlank()) { "AcoustID application key must not be blank" }
    }

    override fun toString(): String =
        "AcoustIdLookupRequest(applicationKey=<redacted>, fingerprint=$fingerprint)"
}

fun interface AcousticMetadataProvider {
    suspend fun lookup(request: AcoustIdLookupRequest): List<MetadataCandidate>
}

private fun Element.directChildText(localName: String): String? {
    val children = childNodes
    for (index in 0 until children.length) {
        val element = children.item(index) as? Element ?: continue
        if (element.localName == localName || element.tagName == localName) {
            return element.textContent.trim().takeIf(String::isNotBlank)
        }
    }
    return null
}

private fun Element.firstDescendant(parentName: String, childName: String): Element? {
    val parents = getElementsByTagNameNS("*", parentName)
    if (parents.length == 0) return null
    val parent = parents.item(0) as? Element ?: return null
    val children = parent.getElementsByTagNameNS("*", childName)
    return children.item(0) as? Element
}

private fun Element.firstDescendantText(parentName: String, childName: String): String? =
    firstDescendant(parentName, childName)?.textContent?.trim()?.takeIf(String::isNotBlank)
