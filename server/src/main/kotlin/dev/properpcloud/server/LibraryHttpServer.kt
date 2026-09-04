package dev.properpcloud.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LibraryHttpServer(
    private val config: ServerConfig,
    private val repository: CatalogRepository,
    private val scanner: LibraryScanner,
    private val pCloud: PCloudRestClient? = null,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) : AutoCloseable {
    private val apiToken: ByteArray? = config.apiTokenFile?.let { SecretFile(it).readText().toByteArray(Charsets.UTF_8) }
    private val http = HttpServer.create(InetSocketAddress(config.bindHost, config.port), 64)
    private val requestExecutor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "properpcloud-http").apply { isDaemon = true } }
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "properpcloud-library-scan").apply { isDaemon = true } }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "properpcloud-library-scheduler").apply { isDaemon = true } }
    private val scanRunning = AtomicBoolean(false)
    private val tickets = ConcurrentHashMap<String, StreamTicket>()
    private val secureRandom = SecureRandom()

    init {
        http.executor = requestExecutor
        http.createContext("/") { exchange -> route(exchange) }
    }

    private fun duplicates(exchange: HttpExchange) {
        val limit = exchange.queryParameters()["limit"]?.toIntOrNull() ?: 100
        json(exchange, 200, mapOf("groups" to repository.duplicateGroups(limit), "status" to repository.status()))
    }

    fun start() = http.start()

    fun requestBackgroundScan(): Boolean {
        if (!scanRunning.compareAndSet(false, true)) return false
        scanExecutor.submit {
            try {
                scanner.scan()
            } finally {
                scanRunning.set(false)
            }
        }
        return true
    }

    fun scheduleScans(interval: Duration) {
        require(!interval.isNegative && !interval.isZero) { "scan interval must be positive" }
        scheduler.scheduleWithFixedDelay(
            { requestBackgroundScan() },
            interval.toSeconds(),
            interval.toSeconds(),
            TimeUnit.SECONDS,
        )
    }

    private fun route(exchange: HttpExchange) {
        try {
            when {
                exchange.requestURI.path == "/health" && exchange.requestMethod == "GET" -> health(exchange)
                exchange.requestURI.path == "/api/v1/library/status" && exchange.requestMethod == "GET" -> authenticated(exchange) { json(exchange, 200, repository.status()) }
                exchange.requestURI.path == "/api/v1/library/scan" && exchange.requestMethod == "POST" -> authenticated(exchange) {
                    val accepted = requestBackgroundScan()
                    json(exchange, if (accepted) 202 else 409, mapOf("accepted" to accepted, "status" to repository.status()))
                }
                exchange.requestURI.path == "/api/v1/library/browse" && exchange.requestMethod == "GET" -> authenticated(exchange) { browse(exchange) }
                exchange.requestURI.path == "/api/v1/library/search" && exchange.requestMethod == "GET" -> authenticated(exchange) { search(exchange) }
                exchange.requestURI.path == "/api/v1/library/duplicates" && exchange.requestMethod == "GET" -> authenticated(exchange) { duplicates(exchange) }
                exchange.requestURI.path == "/api/v1/library/node" && exchange.requestMethod == "GET" -> authenticated(exchange) { node(exchange) }
                exchange.requestURI.path == "/api/v1/library/stream-link" && exchange.requestMethod == "POST" -> authenticated(exchange) { streamLink(exchange) }
                exchange.requestURI.path == "/api/v1/pcloud/folders/create" && exchange.requestMethod == "POST" -> authenticated(exchange) { createFolder(exchange) }
                exchange.requestURI.path.startsWith("/stream/") && exchange.requestMethod in setOf("GET", "HEAD") -> stream(exchange)
                else -> json(exchange, 404, mapOf("error" to "not_found"))
            }
        } catch (_: IllegalArgumentException) {
            safeJson(exchange, 400, mapOf("error" to "invalid_request"))
        } catch (_: SecurityException) {
            safeJson(exchange, 403, mapOf("error" to "forbidden"))
        } catch (_: Throwable) {
            safeJson(exchange, 500, mapOf("error" to "internal_error"))
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun health(exchange: HttpExchange) {
        val status = repository.status()
        val mountOnline = Files.isDirectory(config.mountRoot, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(config.mountRoot)
        val pCloudOnline = pCloud?.health() ?: false
        json(
            exchange,
            if (mountOnline && (pCloud == null || pCloudOnline)) 200 else 503,
            mapOf(
                "status" to if (mountOnline && (pCloud == null || pCloudOnline)) "ok" else "degraded",
                "mountOnline" to mountOnline,
                "pcloudOnline" to pCloudOnline,
                "catalogState" to status.state,
                "generation" to status.generation,
            ),
        )
    }

    private fun browse(exchange: HttpExchange) {
        val parent = exchange.queryParameters()["parent"] ?: LibraryScanner.ROOT_NODE_ID
        json(exchange, 200, mapOf("entries" to repository.browse(parent), "status" to repository.status()))
    }

    private fun search(exchange: HttpExchange) {
        val parameters = exchange.queryParameters()
        val query = parameters["q"]?.trim().orEmpty()
        require(query.length in 1..512) { "search query required" }
        val limit = parameters["limit"]?.toIntOrNull() ?: 100
        json(exchange, 200, mapOf("entries" to repository.search(query, limit), "status" to repository.status()))
    }

    private fun node(exchange: HttpExchange) {
        val id = exchange.queryParameters()["id"] ?: throw IllegalArgumentException("id required")
        val entry = repository.findByNodeId(id) ?: return json(exchange, 404, mapOf("error" to "not_found"))
        json(exchange, 200, mapOf("entry" to entry))
    }

    private fun streamLink(exchange: HttpExchange) {
        val id = exchange.queryParameters()["id"] ?: throw IllegalArgumentException("id required")
        val entry = repository.findByNodeId(id) ?: return json(exchange, 404, mapOf("error" to "not_found"))
        require(entry.kind == "audio") { "only audio entries can be streamed" }
        cleanupTickets()
        val expires = System.currentTimeMillis() + STREAM_TICKET_TTL_MILLIS
        val tokenBytes = ByteArray(32).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        tickets[token] = StreamTicket(id, expires)
        json(
            exchange,
            200,
            mapOf(
                "url" to "${config.publicBaseUrl}/stream/$token",
                "expiresAtEpochMillis" to expires,
                "contentType" to entry.contentType,
            ),
        )
    }

    private fun createFolder(exchange: HttpExchange) {
        val client = pCloud ?: return json(exchange, 503, mapOf("error" to "pcloud_unavailable"))
        val parameters = exchange.queryParameters()
        val parent = parameters["parentId"]?.toLongOrNull() ?: throw IllegalArgumentException("parentId required")
        val name = parameters["name"] ?: throw IllegalArgumentException("name required")
        val folderId = client.createFolder(parent, name)
        json(exchange, 201, mapOf("folderId" to folderId))
    }

    private fun stream(exchange: HttpExchange) {
        val token = exchange.requestURI.path.removePrefix("/stream/")
        val ticket = tickets[token]
        if (ticket == null || ticket.expiresAtEpochMillis < System.currentTimeMillis()) {
            tickets.remove(token)
            return json(exchange, 404, mapOf("error" to "stream_ticket_expired"))
        }
        val entry = repository.findByNodeId(ticket.nodeId) ?: return json(exchange, 404, mapOf("error" to "not_found"))
        val root = config.mountRoot.toAbsolutePath().normalize()
        val file = root.resolve(entry.path).normalize()
        require(file.startsWith(root)) { "catalog path escaped mount root" }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) { "catalog file unavailable" }
        val size = Files.size(file)
        val range = parseRange(exchange.requestHeaders.getFirst("Range"), size)
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("Content-Type", entry.contentType ?: "application/octet-stream")
        exchange.responseHeaders.set("Cache-Control", "private, no-store")
        if (range != null) {
            exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/$size")
        }
        val start = range?.first ?: 0L
        val end = range?.last ?: (size - 1L)
        val length = (end - start + 1L).coerceAtLeast(0)
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.set("Content-Length", length.toString())
            exchange.sendResponseHeaders(if (range == null) 200 else 206, -1)
            return
        }
        exchange.sendResponseHeaders(if (range == null) 200 else 206, length)
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            copyBounded(channel, exchange.responseBody, length)
        }
    }

    private fun parseRange(header: String?, size: Long): LongRange? {
        if (header.isNullOrBlank()) return null
        require(header.startsWith("bytes=") && ',' !in header) { "unsupported Range header" }
        val raw = header.removePrefix("bytes=")
        val (left, right) = raw.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (left.isBlank()) {
            val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("invalid Range")
            val start = (size - suffix).coerceAtLeast(0)
            return start..(size - 1)
        }
        val start = left.toLongOrNull()?.takeIf { it >= 0 && it < size } ?: throw IllegalArgumentException("invalid Range")
        val end = right.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        require(end >= start) { "invalid Range" }
        return start..end
    }

    private fun copyBounded(channel: FileChannel, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = channel.read(buffer)
            if (read < 0) break
            buffer.flip()
            output.write(buffer.array(), 0, read)
            remaining -= read
        }
    }

    private fun authenticated(exchange: HttpExchange, action: () -> Unit) {
        val expected = apiToken ?: return action()
        val header = exchange.requestHeaders.getFirst("Authorization")
        val supplied = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.toByteArray(Charsets.UTF_8)
        if (supplied == null || !MessageDigest.isEqual(expected, supplied)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            json(exchange, 401, mapOf("error" to "unauthorized"))
            return
        }
        action()
    }

    private fun cleanupTickets(now: Long = System.currentTimeMillis()) {
        tickets.entries.removeIf { it.value.expiresAtEpochMillis < now }
    }

    private fun HttpExchange.queryParameters(): Map<String, String> = requestURI.rawQuery
        ?.split('&')
        ?.filter(String::isNotBlank)
        ?.associate { part ->
            val split = part.split('=', limit = 2)
            decode(split[0]) to decode(split.getOrElse(1) { "" })
        }
        .orEmpty()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun json(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = gson.toJson(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun safeJson(exchange: HttpExchange, status: Int, body: Any) {
        runCatching { json(exchange, status, body) }
    }

    override fun close() {
        http.stop(1)
        scheduler.shutdownNow()
        scanExecutor.shutdownNow()
        requestExecutor.shutdownNow()
        apiToken?.fill(0)
    }

    private data class StreamTicket(val nodeId: String, val expiresAtEpochMillis: Long)

    private companion object {
        const val STREAM_TICKET_TTL_MILLIS = 5 * 60 * 1000L
    }
}
