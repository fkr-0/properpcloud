package dev.properpcloud.source.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ServerCatalogAudioSourceTest {
    @Test
    fun `browse load and stream resolution preserve stable catalog identity`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
        try {
            val source = ServerCatalogAudioSource(
                ServerCatalogSession("http://127.0.0.1:${server.address.port}", "test-token"),
            )

            val children = source.list(source.root.id)
            assertEquals(2, children.size)
            assertTrue(children[0] is AudioFolder)
            val track = children[1] as AudioTrack
            assertEquals(NodeId("catalog:pcloud:file:42"), track.id)
            assertEquals("Song", track.taggedTitle)

            val loaded = source.load(track.id) as AudioTrack
            assertEquals(track.id, loaded.id)
            assertEquals(track.parentId, loaded.parentId)

            val stream = source.resolveStream(track.id)
            assertEquals("http://127.0.0.1:${server.address.port}/stream/ticket", stream.url)
            assertEquals(123456789L, stream.expiresAtEpochMillis)
            assertEquals("audio/flac", stream.contentType)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange) {
        assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
        val body = when (exchange.requestURI.path) {
            "/api/v1/library/browse" ->
                """{"entries":[{"nodeId":"catalog:pcloud:folder:7","parentNodeId":"catalog:root","path":"Artist","name":"Artist","kind":"folder"},{"nodeId":"catalog:pcloud:file:42","parentNodeId":"catalog:root","path":"song.flac","name":"song.flac","kind":"audio","contentType":"audio/flac","title":"Song","durationMillis":1000}]}"""
            "/api/v1/library/node" ->
                """{"entry":{"nodeId":"catalog:pcloud:file:42","parentNodeId":"catalog:root","path":"song.flac","name":"song.flac","kind":"audio","contentType":"audio/flac","title":"Song","durationMillis":1000}}"""
            "/api/v1/library/stream-link" ->
                """{"url":"http://127.0.0.1:${exchange.localAddress.port}/stream/ticket","expiresAtEpochMillis":123456789,"contentType":"audio/flac"}"""
            else -> error("unexpected path ${exchange.requestURI.path}")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
