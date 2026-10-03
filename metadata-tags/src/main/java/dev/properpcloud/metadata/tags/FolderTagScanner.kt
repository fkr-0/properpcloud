package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FileTagProposals
import dev.properpcloud.core.model.FolderTagSnapshot
import dev.properpcloud.core.model.FolderStructureTagConfig
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.NaturalTextComparator
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.core.model.TagScanFailure
import dev.properpcloud.core.model.TagScanFailureKind
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Scanner that enumerates audio files in a single directory, parses their tags,
 * and runs [TagProposalEngine] to produce a complete [FolderTagSnapshot].
 *
 * This scanner:
 * - Accepts a [File] directory, a [SourceId], and an [AudioTagToolkit] instance.
 * - Enumerates direct children only (no recursion), rejecting symlinks.
 * - Filters to regular audio files by extension.
 * - Captures stable file identity and parses tags via [AudioTagToolkit.inspect].
 * - Uses bounded concurrency for tag parsing.
 * - Validates that the directory is inside an authorized root.
 */
class FolderTagScanner(
    private val toolkit: AudioTagToolkit,
    private val engine: TagProposalEngine = TagProposalEngine(),
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val nodeIdentity: (File, Boolean) -> NodeId = { file, directory ->
        NodeId("${if (directory) "folder" else "file"}:${file.canonicalPath}")
    },
) {
    private val authorizedRoots = mutableSetOf<File>()

    /**
     * Register a directory tree that the scanner is allowed to read.
     * Paths are resolved to their canonical form at registration time.
     */
    fun addAuthorizedRoot(root: File) {
        require(root.isDirectory) { "authorized root must be a directory: $root" }
        authorizedRoots += root.canonicalFile
    }

    /**
     * Scan a folder and produce a [FolderTagSnapshot].
     *
     * @param directory the folder to scan; must be inside an authorized root.
     * @param sourceId the source identity for all files in this folder.
     * @param generation monotonically increasing snapshot generation counter.
     * @return a complete folder tag snapshot with proposals.
     * @throws IllegalArgumentException if the directory is not inside an authorized root
     *         or is not a readable directory.
     */
    fun scan(
        directory: File,
        sourceId: SourceId,
        generation: SnapshotGeneration = SnapshotGeneration(0),
        structureConfig: FolderStructureTagConfig = FolderStructureTagConfig(),
    ): FolderTagSnapshot {
        require(directory.isDirectory) { "scan target must be a directory: $directory" }
        require(directory.canRead()) { "directory is not readable: $directory" }

        val canonicalDir = directory.canonicalFile
        require(isInsideAuthorizedRoot(canonicalDir)) {
            "directory $canonicalDir is not inside any authorized root"
        }

        val folderId = nodeIdentity(canonicalDir, true)

        val audioChildren = listAudioChildren(canonicalDir)
        val scanWarnings = mutableListOf<String>()

        // Parse tags with bounded concurrency
        val parsed = parseTagsConcurrently(audioChildren, sourceId, folderId, scanWarnings)
        val generated = engine.generateProposals(
            parsed.filter { it.failure == null }.map { it.identity to it.snapshot },
            canonicalDir,
            structureConfig,
        ).associateBy { it.identity.nodeId }
        val proposals = parsed.map { row ->
            row.failure?.let { failure ->
                FileTagProposals(
                    identity = row.identity,
                    originalSnapshot = row.snapshot,
                    fieldProposals = emptyList(),
                    formatWarnings = row.snapshot.warnings,
                    scanFailure = failure,
                )
            } ?: generated.getValue(row.identity.nodeId)
        }

        return FolderTagSnapshot(
            generation = generation,
            folderPath = canonicalDir,
            sourceId = sourceId,
            folderId = folderId,
            files = proposals,
            scanTimeEpochMillis = System.currentTimeMillis(),
            warnings = scanWarnings,
        )
    }

    // ── Directory enumeration ──────────────────────────────────────────────

    /**
     * List direct children that are regular audio files, rejecting symlinks and
     * non-regular files.
     */
    private fun listAudioChildren(directory: File): List<File> {
        val children = directory.listFiles() ?: return emptyList()
        return children
            .filter { child ->
                child.isFile &&
                    !Files.isSymbolicLink(child.toPath()) &&
                    !child.name.startsWith(TRANSACTION_FILE_PREFIX) &&
                    child.extension.lowercase() in SUPPORTED_EXTENSIONS
            }
            .sortedWith { left, right -> NaturalTextComparator.compare(left.name, right.name) }
    }

    // ── Bounded-concurrency tag parsing ────────────────────────────────────

    /**
     * Parse tags for all files using a fixed thread pool of size [concurrency].
     * Returns the successfully parsed (identity, snapshot) pairs. Files that fail
     * to parse are reported in [scanWarnings] and excluded from the result.
     */
    private data class ParsedTagRow(
        val identity: LocalFileIdentity,
        val snapshot: TagSnapshot,
        val failure: TagScanFailure? = null,
    )

    private fun parseTagsConcurrently(
        files: List<File>,
        sourceId: SourceId,
        folderId: NodeId,
        scanWarnings: MutableList<String>,
    ): List<ParsedTagRow> {
        if (files.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtMost(files.size))
        try {
            val futures: List<Future<ParsedTagRow>> = files.map { file ->
                executor.submit<ParsedTagRow> {
                    val identity = captureIdentity(file, sourceId, folderId)
                    if (!file.canRead()) {
                        val failure = TagScanFailure(TagScanFailureKind.UNREADABLE, "File is not readable.")
                        return@submit ParsedTagRow(
                            identity,
                            TagSnapshot("Unavailable", warnings = listOf(failure.message)),
                            failure,
                        )
                    }
                    try {
                        ParsedTagRow(identity, toolkit.inspect(file))
                    } catch (error: Exception) {
                        val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
                        val failure = TagScanFailure(
                            TagScanFailureKind.MALFORMED_OR_UNSUPPORTED,
                            "Could not inspect embedded tags: $detail",
                        )
                        ParsedTagRow(
                            identity,
                            TagSnapshot("Malformed or unsupported", warnings = listOf(failure.message)),
                            failure,
                        )
                    }
                }
            }

            val results = mutableListOf<ParsedTagRow>()
            for ((index, future) in futures.withIndex()) {
                try {
                    results += future.get()
                } catch (ex: Exception) {
                    scanWarnings += "Failed to parse ${files[index].name}: ${ex.message}"
                }
            }
            return results
        } finally {
            executor.shutdown()
        }
    }

    // ── Identity capture ───────────────────────────────────────────────────

    private fun captureIdentity(
        file: File,
        sourceId: SourceId,
        folderId: NodeId,
    ): LocalFileIdentity {
        val canonical = file.canonicalFile
        return LocalFileIdentity(
            sourceId = sourceId,
            nodeId = nodeIdentity(canonical, false),
            file = canonical,
            filename = canonical.name,
            contentEvidence = ContentEvidence(
                sizeBytes = canonical.length(),
                modifiedTimeNanos = getLastModifiedNanos(canonical),
                sha256 = null, // captured lazily at apply time
            ),
        )
    }

    /**
     * Retrieve the last-modified time in nanosecond precision when available,
     * falling back to milliseconds × 1_000_000.
     */
    private fun getLastModifiedNanos(file: File): Long {
        return try {
            val instant = Files.getLastModifiedTime(file.toPath()).toInstant()
            Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
        } catch (_: Exception) {
            file.lastModified() * 1_000_000L
        }
    }

    // ── Path escape validation ─────────────────────────────────────────────

    private fun isInsideAuthorizedRoot(canonicalDir: File): Boolean {
        if (authorizedRoots.isEmpty()) return false
        return authorizedRoots.any { root ->
            canonicalDir.absolutePath == root.absolutePath ||
                canonicalDir.absolutePath.startsWith(root.absolutePath + File.separator)
        }
    }

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_CONCURRENCY = 4
        private const val TRANSACTION_FILE_PREFIX = ".properpcloud-"

        /**
         * Audio file extensions recognized by the scanner.
         * This set is intentionally conservative; the underlying toolkit may support more.
         */
        val SUPPORTED_EXTENSIONS: Set<String> = setOf(
            "mp3",
            "flac",
            "ogg",
            "oga",
            "opus",
            "m4a",
            "mp4",
            "aac",
            "wav",
            "wma",
            "asf",
            "aif",
            "aiff",
            "ape",
            "mpc",
            "wv",
            "dsf",
            "dff",
        )
    }
}
