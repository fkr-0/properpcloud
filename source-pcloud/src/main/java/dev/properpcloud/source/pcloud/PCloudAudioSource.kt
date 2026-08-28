package dev.properpcloud.source.pcloud

import com.pcloud.sdk.ApiClient
import com.pcloud.sdk.Authenticators
import com.pcloud.sdk.Checksums
import com.pcloud.sdk.DownloadOptions
import com.pcloud.sdk.PCloudSdk
import com.pcloud.sdk.RemoteFile
import com.pcloud.sdk.RemoteEntry
import com.pcloud.sdk.internal.LegacyTokenAuthenticators
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.LibraryFile
import dev.properpcloud.core.model.LibraryFileKind
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.MetadataContentSource
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.PreparedMetadataSource
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class PCloudAudioSource internal constructor(
    private val client: ApiClient,
    private val metadataTransport: PCloudMetadataTransport,
    override val id: SourceId = SourceId("pcloud"),
) : AudioSource, MetadataContentSource {
    constructor(
        client: ApiClient,
        id: SourceId = SourceId("pcloud"),
    ) : this(client, SdkPCloudMetadataTransport(client), id)
    override val root = AudioFolder(
        sourceId = id,
        id = PCloudNodeIds.folder(0),
        parentId = null,
        name = "pCloud",
    )

    override suspend fun list(folderId: NodeId): List<MediaNode> = withContext(Dispatchers.IO) {
        val parsed = PCloudNodeIds.parse(folderId)
        require(parsed.kind == PCloudNodeKind.FOLDER) { "folder id required" }

        client.listFolder(parsed.numericId).execute().children().mapNotNull(::toMediaNode)
    }

    override suspend fun load(nodeId: NodeId): MediaNode = withContext(Dispatchers.IO) {
        val parsed = PCloudNodeIds.parse(nodeId)
        val entry = when (parsed.kind) {
            PCloudNodeKind.FILE -> client.loadFile(parsed.numericId).execute()
            PCloudNodeKind.FOLDER -> client.loadFolder(parsed.numericId).execute()
        }
        requireNotNull(toMediaNode(entry)) { "node is unavailable" }
    }

    override suspend fun resolveStream(trackId: NodeId): StreamHandle = withContext(Dispatchers.IO) {
        val parsed = PCloudNodeIds.parse(trackId)
        require(parsed.kind == PCloudNodeKind.FILE) { "file id required" }

        val remoteFile = client.loadFile(parsed.numericId).execute()
        val link = client.createFileLink(remoteFile, DownloadOptions.DEFAULT).execute()
        StreamHandle(
            url = link.bestUrl().toExternalForm(),
            expiresAtEpochMillis = link.expirationDate()?.time,
            contentType = remoteFile.contentType(),
        )
    }

    override suspend fun inspect(nodeId: NodeId): NodeInspection = withContext(Dispatchers.IO) {
        val parsed = PCloudNodeIds.parse(nodeId)
        val entry = when (parsed.kind) {
            PCloudNodeKind.FILE -> client.loadFile(parsed.numericId).execute()
            PCloudNodeKind.FOLDER -> client.loadFolder(parsed.numericId).execute()
        }

        val fields = linkedMapOf(
            "provider" to "pCloud",
            "id" to entry.id(),
            "name" to entry.name(),
            "kind" to if (entry.isFolder) "folder" else "file",
            "parentFolderId" to entry.parentFolderId().toString(),
            "created" to entry.created().toInstant().toString(),
            "modified" to entry.lastModified().toInstant().toString(),
            "canRead" to entry.canRead().toString(),
            "canModify" to entry.canModify().toString(),
            "isMine" to entry.isMine().toString(),
            "isShared" to entry.isShared().toString(),
        )

        if (entry.isFile) {
            val file = entry.asFile()
            fields["fileId"] = file.fileId().toString()
            fields["contentType"] = file.contentType().orEmpty()
            fields["sizeBytes"] = file.size().toString()
            fields["contentHash"] = file.hash()
        }

        NodeInspection(fields)
    }

    override suspend fun prepareMetadataSource(
        nodeId: NodeId,
        destinationFile: File,
    ): PreparedMetadataSource = withContext(Dispatchers.IO) {
        val parsed = PCloudNodeIds.parse(nodeId)
        require(parsed.kind == PCloudNodeKind.FILE) { "file id required" }
        require(destinationFile.parentFile?.let { it.exists() || it.mkdirs() } != false) {
            "could not create metadata staging directory"
        }
        require(!destinationFile.exists()) { "metadata destination already exists" }

        val before = metadataTransport.snapshot(parsed.numericId)
        require(before.canRead) { "pCloud file is not readable" }
        try {
            metadataTransport.download(parsed.numericId, destinationFile)
            require(destinationFile.isFile && destinationFile.length() > 0) {
                "pCloud metadata download produced no file"
            }
            require(destinationFile.length() == before.sizeBytes) {
                "pCloud file size changed during metadata download"
            }
            val localSha256 = destinationFile.sha256()
            require(localSha256.equals(before.sha256, ignoreCase = true)) {
                "pCloud metadata download failed SHA-256 verification"
            }

            val after = metadataTransport.snapshot(parsed.numericId)
            require(before.revision == after.revision && before.sha256.equals(after.sha256, ignoreCase = true)) {
                "pCloud source changed during metadata download"
            }
            PreparedMetadataSource(
                sourceId = id,
                nodeId = nodeId,
                localFile = destinationFile,
                originalFilename = before.name,
                expectedRevision = before.revision,
                expectedContentHash = before.sha256,
                sizeBytes = before.sizeBytes,
            )
        } catch (error: Throwable) {
            destinationFile.delete()
            throw error
        }
    }

    private fun toMediaNode(entry: RemoteEntry): MediaNode? {
        return when {
            entry.isFolder -> {
                val folder = entry.asFolder()
                AudioFolder(
                    sourceId = id,
                    id = PCloudNodeIds.folder(folder.folderId()),
                    parentId = PCloudNodeIds.folder(folder.parentFolderId()),
                    name = folder.name(),
                    modifiedAtEpochMillis = folder.lastModified().time,
                )
            }

            entry.isFile -> {
                val file = entry.asFile()
                if (!file.looksLikeAudio()) {
                    LibraryFile(
                        sourceId = id,
                        id = PCloudNodeIds.file(file.fileId()),
                        parentId = PCloudNodeIds.folder(file.parentFolderId()),
                        name = file.name(),
                        modifiedAtEpochMillis = file.lastModified().time,
                        contentType = file.contentType(),
                        sizeBytes = file.size(),
                        kind = LibraryFileKind.fromFilename(file.name()),
                    )
                } else {
                    AudioTrack(
                        sourceId = id,
                        id = PCloudNodeIds.file(file.fileId()),
                        parentId = PCloudNodeIds.folder(file.parentFolderId()),
                        name = file.name(),
                        modifiedAtEpochMillis = file.lastModified().time,
                        contentType = file.contentType(),
                        sizeBytes = file.size(),
                    )
                }
            }

            else -> null
        }
    }

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

internal data class PCloudMetadataSnapshot(
    val fileId: Long,
    val name: String,
    val sizeBytes: Long,
    val providerHash: String,
    val sha256: String,
    val modifiedAtEpochMillis: Long,
    val canRead: Boolean,
) {
    val revision: String = "$modifiedAtEpochMillis:$sizeBytes:$providerHash"
}

internal interface PCloudMetadataTransport {
    fun snapshot(fileId: Long): PCloudMetadataSnapshot
    fun download(fileId: Long, destinationFile: File)
}

private class SdkPCloudMetadataTransport(
    private val client: ApiClient,
) : PCloudMetadataTransport {
    override fun snapshot(fileId: Long): PCloudMetadataSnapshot {
        val file = client.loadFile(fileId).execute()
        val checksums = client.getChecksums(fileId).execute()
        return file.toMetadataSnapshot(checksums)
    }

    override fun download(fileId: Long, destinationFile: File) {
        val remote = client.loadFile(fileId).execute()
        client.download(remote).execute().use { source ->
            destinationFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun RemoteFile.toMetadataSnapshot(checksums: Checksums): PCloudMetadataSnapshot {
        require(checksums.file.fileId() == fileId()) { "pCloud checksum response referred to another file" }
        val sha256 = requireNotNull(checksums.sha256) { "pCloud did not return a SHA-256 checksum" }.hex()
        require(sha256.isNotBlank()) { "pCloud returned a blank SHA-256 checksum" }
        return PCloudMetadataSnapshot(
            fileId = fileId(),
            name = name(),
            sizeBytes = size(),
            providerHash = hash(),
            sha256 = sha256,
            modifiedAtEpochMillis = lastModified().time,
            canRead = canRead(),
        )
    }
}

enum class PCloudTokenKind {
    OAUTH_BEARER,
    LEGACY_AUTH_TOKEN,
}

data class PCloudSession(
    val accessToken: String,
    val apiHost: String,
    val userId: Long,
    val tokenKind: PCloudTokenKind = PCloudTokenKind.OAUTH_BEARER,
) {
    init {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
        require(apiHost in allowedPCloudApiHosts) { "unsupported pCloud API host" }
        require(userId >= 0) { "invalid pCloud user id" }
    }

    override fun toString(): String =
        "PCloudSession(accessToken=<redacted>, apiHost=$apiHost, userId=$userId, tokenKind=$tokenKind)"
}

object PCloudSourceFactory {
    fun create(session: PCloudSession): PCloudAudioSource {
        val authenticator = when (session.tokenKind) {
            PCloudTokenKind.OAUTH_BEARER -> Authenticators.newOAuthAuthenticator(session.accessToken)
            PCloudTokenKind.LEGACY_AUTH_TOKEN -> LegacyTokenAuthenticators.create(session.accessToken)
        }
        val client = PCloudSdk.newClientBuilder()
            .apiHost(session.apiHost)
            .authenticator(authenticator)
            .create()
        return PCloudAudioSource(client)
    }
}

val allowedPCloudApiHosts: Set<String> = setOf("api.pcloud.com", "eapi.pcloud.com")

private val audioExtensions = setOf(
    "aac", "aiff", "alac", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "wma",
)

private fun com.pcloud.sdk.RemoteFile.looksLikeAudio(): Boolean {
    if (contentType()?.startsWith("audio/", ignoreCase = true) == true) return true
    return name().substringAfterLast('.', missingDelimiterValue = "").lowercase() in audioExtensions
}
