package dev.properpcloud.source.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerPlaybackState
import dev.properpcloud.core.model.StreamResolutionException
import dev.properpcloud.core.model.StreamResolutionFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ServerCatalogAudioSourceTest {
    @Test
    fun `missing catalog item is terminal while auth and server failures remain transient`() = runTest {
        assertEquals(
            StreamResolutionFailureKind.ITEM_UNAVAILABLE,
            classifyServerStreamResolutionFailure(ServerCatalogHttpException(404)).kind,
        )
        assertEquals(
            StreamResolutionFailureKind.ITEM_UNAVAILABLE,
            classifyServerStreamResolutionFailure(ServerCatalogHttpException(410)).kind,
        )
        assertEquals(
            StreamResolutionFailureKind.TRANSIENT,
            classifyServerStreamResolutionFailure(ServerCatalogHttpException(401)).kind,
        )
        assertEquals(
            StreamResolutionFailureKind.TRANSIENT,
            classifyServerStreamResolutionFailure(ServerCatalogHttpException(503)).kind,
        )
    }

    @Test
    fun `stream endpoint 404 is exposed as item unavailable`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "missing".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val source = ServerCatalogAudioSource(ServerCatalogSession("http://127.0.0.1:${server.address.port}"))
            val failure = runCatching { source.resolveStream(NodeId("catalog:pcloud:file:404")) }.exceptionOrNull()

            assertTrue(failure is StreamResolutionException)
            assertEquals(StreamResolutionFailureKind.ITEM_UNAVAILABLE, (failure as StreamResolutionException).kind)
        } finally {
            server.stop(0)
        }
    }

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

            val inspection = source.inspect(track.id)
            assertEquals("128", inspection.fields["bitrateKbps"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `optional server player discovery exposes stable source neutral snapshots without control duplication`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/players") { exchange ->
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            val body = """
                {"players":[
                  {"id":"living-room","name":"Living room","connectivity":"connected","playbackState":"playing","currentMedia":{"id":"book:1:chapter:4","title":"Chapter 4","subtitle":"A Book"}}
                ]}
            """.trimIndent()
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val source = ServerCatalogAudioSource(
                ServerCatalogSession("http://127.0.0.1:${server.address.port}", "test-token"),
            )

            val players = source.discoverPlayers()

            assertEquals(1, players.size)
            val player = players.single()
            assertTrue(player.id.value.endsWith(":living-room"))
            assertEquals(source.providerId, player.providerId)
            assertEquals(PlayerConnectivity.CONNECTED, player.connectivity)
            assertEquals(PlayerPlaybackState.PLAYING, player.playbackState)
            assertEquals("Chapter 4", player.currentMediaTitle)
            assertFalse(player.controllable)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `server without player discovery capability contributes no remote targets`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "missing".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val source = ServerCatalogAudioSource(ServerCatalogSession("http://127.0.0.1:${server.address.port}"))
            assertTrue(source.discoverPlayers().isEmpty())
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
                """{"entry":{"nodeId":"catalog:pcloud:file:42","parentNodeId":"catalog:root","path":"song.flac","name":"song.flac","kind":"audio","contentType":"audio/flac","title":"Song","durationMillis":1000,"bitrateKbps":128}}"""
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
