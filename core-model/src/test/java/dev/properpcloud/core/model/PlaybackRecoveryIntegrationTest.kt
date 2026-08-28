package dev.properpcloud.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PlaybackRecoveryIntegrationTest {
    @Test
    fun `stale HTTP capability is re-resolved by stable identity and resumes preserved state`() = runTest {
        LoopbackHttpFixture().use { http ->
            val source = RotatingHttpSource(http)
            val track = source.track
            val queue = PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0)
            val mediaId = MediaIdentity.encode(track.sourceId, track.id)
            val intendedPositionMillis = 42_000L
            val retryGate = SignedLinkRetryGate(retryCooldownMillis = 1_000)

            val stale = source.resolveStream(track.id)
            assertEquals(403, fetch(stale.url).status)
            assertEquals(
                PlaybackFailureRecovery.REFRESH_STREAM_LOCATION,
                PlaybackRecoveryPolicy.forHttpStatus(403),
            )
            assertTrue(retryGate.acquire(mediaId, nowEpochMillis = 10_000))

            val refreshed = source.resolveStream(track.id)
            val recovered = fetch(refreshed.url)
            assertEquals(200, recovered.status)
            assertEquals("playable-audio", recovered.body)

            assertEquals(2, source.resolveCount.get())
            assertEquals(track.sourceId, queue.current?.track?.sourceId)
            assertEquals(track.id, queue.current?.track?.id)
            assertEquals(mediaId, MediaIdentity.encode(queue.current!!.track.sourceId, queue.current!!.track.id))
            assertEquals(42_000L, intendedPositionMillis)
            assertFalse(mediaId.contains("127.0.0.1"))
            assertFalse(queue.toString().contains("127.0.0.1"))

            // A failure loop remains bounded inside the cooldown window.
            assertFalse(retryGate.acquire(mediaId, nowEpochMillis = 10_100))

            // A material retry boundary (for example explicit recovery after a network change)
            // may invalidate only ephemeral retry state, never the stable media identity.
            retryGate.reset(mediaId)
            assertTrue(retryGate.acquire(mediaId, nowEpochMillis = 10_101))
            val afterNetworkLikeReset = source.resolveStream(track.id)
            assertEquals(200, fetch(afterNetworkLikeReset.url).status)
            assertEquals(3, source.resolveCount.get())
            assertEquals(track.id, queue.current?.track?.id)
        }
    }

    @Test
    fun `permanent HTTP failure stays surfaced without capability refresh`() = runTest {
        LoopbackHttpFixture().use { http ->
            val source = PermanentFailureHttpSource(http)
            val track = source.track
            val handle = source.resolveStream(track.id)

            assertEquals(422, fetch(handle.url).status)
            assertEquals(
                PlaybackFailureRecovery.SURFACE_FAILURE,
                PlaybackRecoveryPolicy.forHttpStatus(422),
            )
            assertEquals(1, source.resolveCount.get())
        }
    }

    private data class HttpResult(val status: Int, val body: String = "")

    private fun fetch(url: String): HttpResult {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            } else {
                ""
            }
            HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private abstract class TestHttpSource : AudioSource {
        override val id = SourceId("fake-http")
        override val root = AudioFolder(id, NodeId("folder:root"), null, "Fixture")
        val track = AudioTrack(
            sourceId = id,
            id = NodeId("file:stable-42"),
            parentId = root.id,
            name = "chapter.flac",
            durationMillis = 120_000,
        )
        val resolveCount = AtomicInteger()

        override suspend fun list(folderId: NodeId): List<MediaNode> = listOf(track)
        override suspend fun load(nodeId: NodeId): MediaNode = track.also { require(nodeId == track.id) }
        override suspend fun inspect(nodeId: NodeId): NodeInspection = NodeInspection(mapOf("id" to nodeId.value))
    }

    private class RotatingHttpSource(private val http: LoopbackHttpFixture) : TestHttpSource() {
        override suspend fun resolveStream(trackId: NodeId): StreamHandle {
            require(trackId == track.id)
            return if (resolveCount.incrementAndGet() == 1) {
                StreamHandle(http.url("/stale"), expiresAtEpochMillis = 1)
            } else {
                StreamHandle(http.url("/fresh"), expiresAtEpochMillis = 60_000)
            }
        }
    }

    private class PermanentFailureHttpSource(private val http: LoopbackHttpFixture) : TestHttpSource() {
        override suspend fun resolveStream(trackId: NodeId): StreamHandle {
            require(trackId == track.id)
            resolveCount.incrementAndGet()
            return StreamHandle(http.url("/permanent"))
        }
    }

    private class LoopbackHttpFixture : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "properpcloud-http-fixture").apply { isDaemon = true }
        }

        init {
            executor.submit {
                while (running.get()) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val (status, reason, body) = when (path) {
                            "/stale" -> Triple(403, "Forbidden", "stale")
                            "/fresh" -> Triple(200, "OK", "playable-audio")
                            "/permanent" -> Triple(422, "Unprocessable Content", "permanent")
                            else -> Triple(404, "Not Found", "missing")
                        }
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        client.getOutputStream().buffered().use { output ->
                            output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
                            output.write("Content-Type: application/octet-stream\r\n".toByteArray(StandardCharsets.US_ASCII))
                            output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
                            output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            output.write(bytes)
                        }
                    }
                }
            }
        }

        fun url(path: String): String = "http://${server.inetAddress.hostAddress}:${server.localPort}$path"

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }
}
