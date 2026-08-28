package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.LibraryFile
import dev.properpcloud.core.model.LibraryFileKind
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import dev.properpcloud.metadata.tags.FolderTagScanner
import dev.properpcloud.metadata.tags.LocalFolderRootCapability
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Stable opaque identity derived from the selected filesystem root and root-relative paths. */
class DesktopLocalFilesystemIdentity private constructor(
    val canonicalRoot: File,
    val sourceId: SourceId,
) {
    fun nodeId(file: File, directory: Boolean): NodeId {
        val canonical = file.canonicalFile
        require(isInsideRoot(canonical)) { "local node is outside the selected root" }
        val relative = canonicalRoot.toPath().relativize(canonical.toPath())
            .joinToString("/") { it.toString() }
            .ifBlank { "/" }
        return NodeId("local-${if (directory) "folder" else "track"}:${sha256(relative).take(32)}")
    }

    fun isInsideRoot(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical == canonicalRoot || canonical.toPath().startsWith(canonicalRoot.toPath())
    }

    companion object {
        fun forSelectedRoot(root: File): DesktopLocalFilesystemIdentity {
            val canonical = root.canonicalFile
            val source = SourceId("local:${sha256(canonical.toPath().toString()).take(24)}")
            return DesktopLocalFilesystemIdentity(canonical, source)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Filesystem-first AudioSource for one explicitly selected local root.
 *
 * It never reads tag values to mint source/node identity and never treats symlinks as library
 * nodes. Audio, playlist, and generic regular files share the same stable root-relative identity;
 * absolute paths stay inside this adapter and are not exposed by inspection state.
 */
class DesktopLocalFolderAudioSource(
    val capability: LocalFolderRootCapability,
    val identity: DesktopLocalFilesystemIdentity,
) : AudioSource {
    init {
        require(capability.sourceId == identity.sourceId) { "local source and root capability identities differ" }
        require(capability.rootDirectory.canonicalFile == identity.canonicalRoot) { "local source root differs from capability root" }
    }

    override val id: SourceId = capability.sourceId
    override val root: AudioFolder = AudioFolder(
        sourceId = id,
        id = identity.nodeId(identity.canonicalRoot, directory = true),
        parentId = null,
        name = identity.canonicalRoot.name.takeIf(String::isNotBlank) ?: "Local folder",
    )

    private val pathsById = ConcurrentHashMap<NodeId, File>().apply { put(root.id, identity.canonicalRoot) }

    override suspend fun list(folderId: NodeId): List<MediaNode> {
        val directory = requireKnown(folderId)
        require(safeDirectory(directory)) { "local folder is no longer a readable regular directory" }
        val nodes = directory.listFiles().orEmpty()
            .asSequence()
            .filterNot { it.isSymbolicLink() }
            .filterNot { it.name.startsWith(TRANSACTION_FILE_PREFIX) }
            .mapNotNull(::toNodeOrNull)
            .toList()
        return FolderQueueBuilder.sortNodes(nodes)
    }

    override suspend fun load(nodeId: NodeId): MediaNode {
        if (nodeId == root.id) return root
        return toNodeOrNull(requireKnown(nodeId)) ?: error("local node is no longer available")
    }

    override suspend fun resolveStream(trackId: NodeId): StreamHandle {
        val file = requireKnown(trackId)
        val node = toNodeOrNull(file) as? AudioTrack ?: error("local audio track required")
        require(node.id == trackId) { "local track identity changed" }
        return StreamHandle(
            url = file.toURI().toString(),
            contentType = node.contentType,
        )
    }

    override suspend fun inspect(nodeId: NodeId): NodeInspection {
        val node = load(nodeId)
        return NodeInspection(
            linkedMapOf(
                "sourceType" to "Local folder",
                "sourceId" to node.sourceId.value,
                "nodeId" to node.id.value,
                "parentId" to node.parentId?.value.orEmpty(),
                "name" to node.name,
                "kind" to when (node) {
                    is AudioFolder -> "folder"
                    is AudioTrack -> "audio"
                    is LibraryFile -> if (node.kind == LibraryFileKind.PLAYLIST) "playlist" else "file"
                },
                "filesystemIdentity" to "root-relative opaque path identity",
            ),
        )
    }

    private fun toNodeOrNull(file: File): MediaNode? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!identity.isInsideRoot(canonical) || canonical.isSymbolicLink()) return null
        val parent = canonical.parentFile?.takeIf(identity::isInsideRoot)
        val parentId = parent?.let { identity.nodeId(it, directory = true) }
        return when {
            safeDirectory(canonical) -> AudioFolder(
                sourceId = id,
                id = identity.nodeId(canonical, directory = true),
                parentId = parentId,
                name = canonical.name,
                modifiedAtEpochMillis = canonical.lastModified().takeIf { it > 0 },
            ).also { pathsById[it.id] = canonical }

            safeAudioFile(canonical) -> AudioTrack(
                sourceId = id,
                id = identity.nodeId(canonical, directory = false),
                parentId = requireNotNull(parentId) {
                    "local audio track parent is outside the selected root"
                },
                name = canonical.name,
                modifiedAtEpochMillis = canonical.lastModified().takeIf { it > 0 },
                contentType = runCatching { Files.probeContentType(canonical.toPath()) }.getOrNull()
                    ?: contentTypeForExtension(canonical.extension),
                sizeBytes = canonical.length(),
            ).also { pathsById[it.id] = canonical }

            safeRegularFile(canonical) -> LibraryFile(
                sourceId = id,
                id = identity.nodeId(canonical, directory = false),
                parentId = requireNotNull(parentId) { "local file parent is outside the selected root" },
                name = canonical.name,
                modifiedAtEpochMillis = canonical.lastModified().takeIf { it > 0 },
                contentType = runCatching { Files.probeContentType(canonical.toPath()) }.getOrNull(),
                sizeBytes = canonical.length(),
                kind = LibraryFileKind.fromFilename(canonical.name),
            ).also { pathsById[it.id] = canonical }

            else -> null
        }
    }

    private fun requireKnown(nodeId: NodeId): File = pathsById[nodeId]
        ?: error("local node is unavailable in the selected root")

    private fun safeDirectory(file: File): Boolean =
        identity.isInsideRoot(file) &&
            !file.isSymbolicLink() &&
            Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isReadable(file.toPath())

    private fun safeRegularFile(file: File): Boolean =
        identity.isInsideRoot(file) &&
            !file.isSymbolicLink() &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isReadable(file.toPath())

    private fun safeAudioFile(file: File): Boolean =
        identity.isInsideRoot(file) &&
            !file.isSymbolicLink() &&
            file.extension.lowercase() in FolderTagScanner.SUPPORTED_EXTENSIONS &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isReadable(file.toPath())

    private fun File.isSymbolicLink(): Boolean = Files.isSymbolicLink(toPath())

    private fun contentTypeForExtension(extension: String): String? = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        else -> null
    }

    private companion object {
        const val TRANSACTION_FILE_PREFIX = ".properpcloud-"
    }
}
