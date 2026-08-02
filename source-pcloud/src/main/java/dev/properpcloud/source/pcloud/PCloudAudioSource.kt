package dev.properpcloud.source.pcloud

import com.pcloud.sdk.ApiClient
import com.pcloud.sdk.Authenticators
import com.pcloud.sdk.DownloadOptions
import com.pcloud.sdk.PCloudSdk
import com.pcloud.sdk.RemoteEntry
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PCloudAudioSource(
    private val client: ApiClient,
    override val id: SourceId = SourceId("pcloud"),
) : AudioSource {
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
        requireNotNull(toMediaNode(entry)) { "node is not playable audio or folder" }
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
                    null
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

data class PCloudSession(
    val accessToken: String,
    val apiHost: String,
    val userId: Long,
) {
    init {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
        require(apiHost in allowedPCloudApiHosts) { "unsupported pCloud API host" }
        require(userId >= 0) { "invalid pCloud user id" }
    }

    override fun toString(): String =
        "PCloudSession(accessToken=<redacted>, apiHost=$apiHost, userId=$userId)"
}

object PCloudSourceFactory {
    fun create(session: PCloudSession): PCloudAudioSource {
        val client = PCloudSdk.newClientBuilder()
            .apiHost(session.apiHost)
            .authenticator(Authenticators.newOAuthAuthenticator(session.accessToken))
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
