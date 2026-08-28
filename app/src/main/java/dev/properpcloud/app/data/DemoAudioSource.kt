package dev.properpcloud.app.data

import android.content.Context
import androidx.core.net.toUri
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.LibraryFile
import dev.properpcloud.core.model.MetadataContentSource
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.PreparedMetadataSource
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

class DemoAudioSource(context: Context) : AudioSource, MetadataContentSource {
    override val id = SourceId("demo")
    override val root = AudioFolder(id, NodeId("demo:folder:root"), null, "Demo library")

    private val toneStore = DemoToneStore(context.applicationContext)
    private val audiobooks = AudioFolder(id, NodeId("demo:folder:audiobooks"), root.id, "Audiobooks")
    private val cityBook = AudioFolder(id, NodeId("demo:folder:city-book"), audiobooks.id, "The Badger and the City")
    private val fieldNotes = AudioFolder(id, NodeId("demo:folder:field-notes"), root.id, "Field recordings")
    private val music = AudioFolder(id, NodeId("demo:folder:music"), root.id, "Numbered tracks")
    private val playlist = LibraryFile(
        id,
        NodeId("demo:file:summer-playlist"),
        music.id,
        "Summer demo set.m3u8",
        contentType = "audio/x-mpegurl",
        sizeBytes = 96,
    )
    private val notes = LibraryFile(
        id,
        NodeId("demo:file:notes"),
        music.id,
        "Summer demo notes.txt",
        contentType = "text/plain",
        sizeBytes = 64,
    )

    private val nodes: Map<NodeId, MediaNode>
    private val children: Map<NodeId, List<MediaNode>>

    init {
        val bookTracks = listOf(
            track(cityBook, "01 - A Door in the Rain.wav", 1, 1, 8_000, 220),
            track(cityBook, "02 - Map of Quiet Streets.wav", 1, 2, 9_000, 247),
            track(cityBook, "03 - Cloud Archive.wav", 1, 3, 10_000, 262),
        )
        val recordings = listOf(
            track(fieldNotes, "2026-07-31 Night tram.wav", null, null, 7_000, 196),
            track(fieldNotes, "2026-08-01 Courtyard birds.wav", null, null, 7_500, 294),
        )
        val numbered = listOf(
            track(music, "1 - First signal.wav", null, null, 5_000, 330),
            track(music, "2 - Second signal.wav", null, null, 5_000, 349),
            track(music, "10 - Tenth signal.wav", null, null, 5_000, 392),
        )
        children = mapOf(
            root.id to listOf(audiobooks, fieldNotes, music),
            audiobooks.id to listOf(cityBook),
            cityBook.id to bookTracks,
            fieldNotes.id to recordings,
            music.id to numbered + listOf(playlist, notes),
        )
        nodes = buildMap {
            put(root.id, root)
            children.values.flatten().forEach { put(it.id, it) }
        }
    }

    override suspend fun prepareMetadataSource(nodeId: NodeId, destinationFile: File): PreparedMetadataSource {
        val track = load(nodeId) as? AudioTrack ?: error("track required")
        val source = toneStore.fileFor(track)
        require(destinationFile.parentFile?.let { it.exists() || it.mkdirs() } != false) {
            "could not create metadata staging directory"
        }
        require(!destinationFile.exists()) { "metadata destination already exists" }
        source.copyTo(destinationFile, overwrite = false)
        val hash = destinationFile.sha256()
        return PreparedMetadataSource(
            sourceId = id,
            nodeId = nodeId,
            localFile = destinationFile,
            originalFilename = track.name,
            expectedRevision = "sha256:$hash",
            expectedContentHash = hash,
            sizeBytes = destinationFile.length(),
        )
    }

    override suspend fun list(folderId: NodeId): List<MediaNode> = children[folderId].orEmpty()

    override suspend fun load(nodeId: NodeId): MediaNode = requireNotNull(nodes[nodeId]) { "unknown demo node" }

    override suspend fun resolveStream(trackId: NodeId): StreamHandle {
        val track = load(trackId) as? AudioTrack ?: error("track required")
        return StreamHandle(
            url = toneStore.fileFor(track).toUri().toString(),
            contentType = "audio/wav",
        )
    }

    override suspend fun inspect(nodeId: NodeId): NodeInspection {
        val node = load(nodeId)
        return NodeInspection(
            linkedMapOf(
                "provider" to "Built-in deterministic demo",
                "sourceId" to node.sourceId.value,
                "nodeId" to node.id.value,
                "parentId" to node.parentId?.value.orEmpty(),
                "name" to node.name,
                "kind" to when (node) {
                    is AudioFolder -> "folder"
                    is AudioTrack -> "audio"
                    is LibraryFile -> if (node.kind == dev.properpcloud.core.model.LibraryFileKind.PLAYLIST) "playlist" else "file"
                },
                "generatedLocally" to "true",
            ),
        )
    }

    private fun track(
        folder: AudioFolder,
        name: String,
        disc: Int?,
        number: Int?,
        durationMillis: Long,
        frequency: Int,
    ) = AudioTrack(
        sourceId = id,
        id = NodeId("demo:track:${frequency}:${durationMillis}"),
        parentId = folder.id,
        name = name,
        contentType = "audio/wav",
        discNumber = disc,
        trackNumber = number,
        taggedTitle = name.substringBeforeLast('.').substringAfter(" - "),
        durationMillis = durationMillis,
    )
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class DemoToneStore(context: Context) {
    private val directory = File(context.filesDir, "demo-media").apply { mkdirs() }

    fun fileFor(track: AudioTrack): File {
        val file = File(directory, track.id.value.replace(':', '_') + ".wav")
        if (!file.exists() || file.length() < 44) {
            val parts = track.id.value.split(':')
            val frequency = parts.getOrNull(2)?.toIntOrNull() ?: 220
            val durationMillis = track.durationMillis ?: 5_000
            writeTone(file, frequency, durationMillis)
        }
        return file
    }

    private fun writeTone(file: File, frequency: Int, durationMillis: Long) {
        val sampleRate = 16_000
        val sampleCount = (sampleRate * durationMillis / 1_000).toInt()
        val dataSize = sampleCount * 2
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeAscii("WAVEfmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(sampleRate * 2)
            output.writeLittleEndianShort(2)
            output.writeLittleEndianShort(16)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataSize)
            repeat(sampleCount) { index ->
                val envelope = minOf(1.0, index / 800.0, (sampleCount - index) / 800.0)
                val sample = (sin(2 * PI * frequency * index / sampleRate) * 6_000 * envelope).toInt()
                output.writeLittleEndianShort(sample)
            }
        }
    }
}

private fun DataOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

private fun DataOutputStream.writeLittleEndianInt(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
    writeByte(value ushr 16 and 0xff)
    writeByte(value ushr 24 and 0xff)
}

private fun DataOutputStream.writeLittleEndianShort(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
}
