package dev.properpcloud.source.pcloud

import dev.properpcloud.core.model.NodeId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class PCloudMetadataDownloadTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun verifiesDownloadedBytesAndStableRevision() = runTest {
        val bytes = "metadata-source".toByteArray()
        val snapshot = snapshot(bytes, modified = 100)
        val source = PCloudAudioSource(
            client = unsupportedClient(),
            metadataTransport = FakeMetadataTransport(bytes, listOf(snapshot, snapshot)),
        )
        val destination = File(temporary.newFolder("staging"), "track.mp3")

        val prepared = source.prepareMetadataSource(NodeId("pcloud:file:7"), destination)

        assertEquals(snapshot.sha256, prepared.expectedContentHash)
        assertEquals(snapshot.revision, prepared.expectedRevision)
        assertEquals(bytes.toList(), destination.readBytes().toList())
    }

    @Test
    fun deletesCandidateWhenRevisionChangesDuringDownload() {
        val bytes = "metadata-source".toByteArray()
        val source = PCloudAudioSource(
            client = unsupportedClient(),
            metadataTransport = FakeMetadataTransport(
                bytes,
                listOf(snapshot(bytes, modified = 100), snapshot(bytes, modified = 101)),
            ),
        )
        val destination = File(temporary.newFolder("conflict"), "track.mp3")

        assertThrows(IllegalArgumentException::class.java) {
            runTest { source.prepareMetadataSource(NodeId("pcloud:file:7"), destination) }
        }
        assertFalse(destination.exists())
    }

    private fun snapshot(bytes: ByteArray, modified: Long) = PCloudMetadataSnapshot(
        fileId = 7,
        name = "track.mp3",
        sizeBytes = bytes.size.toLong(),
        providerHash = "provider-hash-$modified",
        sha256 = bytes.sha256(),
        modifiedAtEpochMillis = modified,
        canRead = true,
    )

    private class FakeMetadataTransport(
        private val bytes: ByteArray,
        snapshots: List<PCloudMetadataSnapshot>,
    ) : PCloudMetadataTransport {
        private val remaining = ArrayDeque(snapshots)

        override fun snapshot(fileId: Long): PCloudMetadataSnapshot = remaining.removeFirst()

        override fun download(fileId: Long, destinationFile: File) {
            destinationFile.writeBytes(bytes)
        }
    }

    private fun unsupportedClient(): com.pcloud.sdk.ApiClient = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(com.pcloud.sdk.ApiClient::class.java),
    ) { _, method, _ -> error("unexpected SDK call: ${method.name}") } as com.pcloud.sdk.ApiClient
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
