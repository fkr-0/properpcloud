package dev.properpcloud.app.data

import dev.properpcloud.app.security.PCloudSessionStore
import dev.properpcloud.app.security.ServerCatalogSessionStore
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.server.ServerCatalogSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun disconnectClearsLocalSessionBeforeReturningRemoteRevocationMaterial() {
        val store = RecordingSessionStore()
        val registry = SourceRegistry(FakeSource(), store, RecordingServerSessionStore())
        val session = PCloudSession("never-log-this", "api.pcloud.com", 7)
        registry.installPCloud(session)

        val returned = registry.disconnectPCloudLocally()

        assertEquals(session, returned)
        assertNull(store.session)
        assertTrue(store.cleared)
        assertFalse(registry.hasPCloudSession())
        assertEquals(SourceId("demo"), registry.current.value.id)
    }

    private class RecordingServerSessionStore : ServerCatalogSessionStore {
        var session: ServerCatalogSession? = null
        var cleared = false

        override fun read(): ServerCatalogSession? = session
        override fun write(session: ServerCatalogSession) {
            this.session = session
        }
        override fun clear() {
            cleared = true
            session = null
        }
    }

    @Test
    fun disconnectServerClearsEncryptedSessionAndReturnsToDemo() {
        val pCloudStore = RecordingSessionStore()
        val serverStore = RecordingServerSessionStore()
        val registry = SourceRegistry(FakeSource(), pCloudStore, serverStore)
        val session = ServerCatalogSession("https://library.example", "never-log-this")
        registry.installServer(session)

        registry.disconnectServerLocally()

        assertNull(serverStore.session)
        assertTrue(serverStore.cleared)
        assertFalse(registry.hasServerSession())
        assertEquals(SourceId("demo"), registry.current.value.id)
    }

    private class RecordingSessionStore : PCloudSessionStore {
        var session: PCloudSession? = null
        var cleared = false

        override fun read(): PCloudSession? = session
        override fun write(session: PCloudSession) {
            this.session = session
        }
        override fun clear() {
            cleared = true
            session = null
        }
    }

    private class FakeSource : AudioSource {
        override val id = SourceId("demo")
        override val root = AudioFolder(id, NodeId("root"), null, "Demo")
        override suspend fun list(folderId: NodeId): List<MediaNode> = emptyList()
        override suspend fun load(nodeId: NodeId): MediaNode = root
        override suspend fun resolveStream(trackId: NodeId) = StreamHandle("file:///dev/null")
        override suspend fun inspect(nodeId: NodeId) = NodeInspection(emptyMap())
    }
}
