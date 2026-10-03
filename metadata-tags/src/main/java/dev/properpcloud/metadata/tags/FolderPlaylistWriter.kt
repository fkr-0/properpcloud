package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.FileTagProposals
import dev.properpcloud.core.model.FolderTagSnapshot
import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.NaturalTextComparator
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.TagField
import java.io.File
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

enum class FolderPlaylistOrder {
    NATURAL_FILENAME,
    TAG_TRACK_NUMBER,
    TAGGED_TITLE,
    TITLE_NUMBER,
    MODIFICATION_TIME,
}

private fun lastModifiedNanos(path: java.nio.file.Path): Long {
    val instant = Files.getLastModifiedTime(path).toInstant()
    return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
}

data class FolderPlaylistWriteCommand(
    val snapshot: FolderTagSnapshot,
    val order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    val outputName: String? = null,
)

data class FolderPlaylistEntryPlan(
    val nodeId: NodeId,
    val sourceFile: File,
    val relativePath: String,
    val durationSeconds: Long?,
    val displayTitle: String,
    val expectedContentEvidence: ContentEvidence,
    val expectedSha256: String,
)

data class FolderPlaylistDirectoryEvidence(
    val directory: File,
    val expectedAudioFileNames: Set<String>,
)

data class FolderPlaylistPlan(
    val directory: File,
    val fileName: String,
    val order: FolderPlaylistOrder,
    val entries: List<FolderPlaylistEntryPlan>,
    val directoryEvidence: List<FolderPlaylistDirectoryEvidence>,
    val extendedM3u: String,
) {
    val relativeEntries: List<String> get() = entries.map(FolderPlaylistEntryPlan::relativePath)
    val durationFallbackCount: Int get() = entries.count { it.durationSeconds == null }
}

data class FolderPlaylistWriteResult(
    val file: File,
    val entryCount: Int,
    val order: FolderPlaylistOrder,
    val relativeEntries: List<String>,
    val sha256: String,
    val durationFallbackCount: Int,
)

data class FolderPlaylistBatchCommand(
    val rootDirectory: File,
    val snapshots: List<FolderTagSnapshot>,
    val recursive: Boolean = false,
    val recursiveOptIn: Boolean = false,
    val onePlaylistPerAlbum: Boolean = false,
    val order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
)

data class FolderPlaylistBatchPlan(
    val rootDirectory: File,
    val recursive: Boolean,
    val recursiveOptInConfirmed: Boolean,
    val onePlaylistPerAlbum: Boolean,
    val playlists: List<FolderPlaylistPlan>,
    val reviewedDirectories: Set<File> = emptySet(),
    val reviewedDirectoryEvidence: List<FolderPlaylistDirectoryEvidence> = emptyList(),
    val order: FolderPlaylistOrder = playlists.firstOrNull()?.order ?: FolderPlaylistOrder.TAG_TRACK_NUMBER,
) {
    init {
        require(!recursive || recursiveOptInConfirmed) {
            "recursive playlist plan must record explicit opt-in"
        }
    }

    val playlistCount: Int get() = playlists.size
    val entryCount: Int get() = playlists.sumOf { it.entries.size }
}

data class FolderPlaylistBatchProgress(
    val completed: Int,
    val total: Int,
    val targetFile: File,
    val entryCount: Int,
)

data class FolderPlaylistBatchWriteResult(
    val plan: FolderPlaylistBatchPlan,
    val results: List<FolderPlaylistWriteResult>,
)

/**
 * Plans and writes portable UTF-8 extended-M3U playlists from already-inspected local
 * folder snapshots. Filesystem location remains authoritative: tags affect labels, ordering,
 * and a sanitized playlist filename only; they never become media path identity.
 */
class FolderPlaylistWriter {
    /** Build an inspectable, side-effect-free direct-folder playlist plan. */
    fun plan(command: FolderPlaylistWriteCommand): FolderPlaylistPlan {
        require(!Files.isSymbolicLink(command.snapshot.folderPath.toPath())) {
            "playlist snapshot directory must not be a symbolic link: ${command.snapshot.folderPath}"
        }
        val directory = command.snapshot.folderPath.canonicalFile
        require(directory.isDirectory) { "playlist target must be a directory: $directory" }
        return buildPlan(
            directory = directory,
            rows = command.snapshot.files,
            membershipSnapshots = listOf(command.snapshot),
            order = command.order,
            outputName = command.outputName,
            allowedRoot = directory,
        )
    }

    /**
     * Build a side-effect-free subtree playlist plan. Recursion is rejected unless it was
     * explicitly requested by the caller. In one-playlist-per-album mode, common CD/Disc/Disk/
     * Part folders are grouped into their filesystem parent; embedded album/artist tags may
     * improve the generated filename but never choose the target directory or entry path.
     */
    fun planBatch(command: FolderPlaylistBatchCommand): FolderPlaylistBatchPlan {
        val root = command.rootDirectory.canonicalFile
        require(root.isDirectory) { "playlist batch root must be a directory: $root" }
        require(!command.recursive || command.recursiveOptIn) {
            "recursive playlist planning requires explicit opt-in"
        }
        if (!command.recursive) {
            require(command.snapshots.size <= 1) {
                "non-recursive playlist planning accepts at most one direct-folder snapshot"
            }
        }

        command.snapshots.forEach { snapshot ->
            val directory = snapshot.folderPath.canonicalFile
            require(isPathInside(root.toPath(), directory.toPath())) {
                "playlist snapshot escaped the selected subtree: $directory"
            }
            require(!Files.isSymbolicLink(snapshot.folderPath.toPath())) {
                "playlist snapshot directory must not be a symbolic link: ${snapshot.folderPath}"
            }
            if (!command.recursive) {
                require(directory == root) {
                    "non-recursive playlist snapshot must describe the selected root directory"
                }
            }
        }

        val grouped = linkedMapOf<File, MutableList<FileTagProposals>>()
        val groupedSnapshots = linkedMapOf<File, MutableList<FolderTagSnapshot>>()
        command.snapshots.forEach { snapshot ->
            val directory = snapshot.folderPath.canonicalFile
            val targetDirectory = if (command.onePlaylistPerAlbum) {
                albumDirectoryFor(root, directory)
            } else {
                directory
            }
            groupedSnapshots.getOrPut(targetDirectory) { mutableListOf() }.add(snapshot)
        }
        val nonEmptySnapshots = command.snapshots.filter { it.files.isNotEmpty() }
        nonEmptySnapshots.forEach { snapshot ->
            val directory = snapshot.folderPath.canonicalFile
            val targetDirectory = if (command.onePlaylistPerAlbum) {
                albumDirectoryFor(root, directory)
            } else {
                directory
            }
            grouped.getOrPut(targetDirectory) { mutableListOf() }.addAll(snapshot.files)
        }

        val playlists = grouped.map { (directory, rows) ->
            require(rows.map { it.identity.nodeId }.distinct().size == rows.size) {
                "playlist batch contains duplicate media identities for $directory"
            }
            buildPlan(
                directory = directory,
                rows = rows,
                membershipSnapshots = groupedSnapshots[directory].orEmpty(),
                order = command.order,
                outputName = null,
                allowedRoot = root,
            )
        }.sortedWith { left, right ->
            NaturalTextComparator.compare(
                root.toPath().relativize(left.directory.toPath()).toString(),
                root.toPath().relativize(right.directory.toPath()).toString(),
            )
        }

        require(playlists.map { it.directory.toPath().resolve(it.fileName).normalize() }.distinct().size == playlists.size) {
            "playlist batch contains duplicate output targets"
        }

        val snapshotEvidence = directoryEvidenceFor(command.snapshots)
            .associateBy { it.directory.canonicalFile }
        val reviewedDirectories = if (command.recursive) {
            collectSubtreeDirectories(root)
        } else {
            command.snapshots.mapTo(linkedSetOf()) { it.folderPath.canonicalFile }
        }
        val reviewedDirectoryEvidence = reviewedDirectories.map { directory ->
            snapshotEvidence[directory] ?: FolderPlaylistDirectoryEvidence(
                directory = directory,
                expectedAudioFileNames = currentAudioFileNames(directory).also { names ->
                    require(names.isEmpty()) {
                        "recursive playlist snapshots are missing audio membership for $directory"
                    }
                },
            )
        }

        return FolderPlaylistBatchPlan(
            rootDirectory = root,
            recursive = command.recursive,
            recursiveOptInConfirmed = !command.recursive || command.recursiveOptIn,
            onePlaylistPerAlbum = command.onePlaylistPerAlbum,
            playlists = playlists,
            reviewedDirectories = reviewedDirectories,
            reviewedDirectoryEvidence = reviewedDirectoryEvidence,
            order = command.order,
        )
    }

    fun write(command: FolderPlaylistWriteCommand): FolderPlaylistWriteResult = write(plan(command))

    /**
     * Materialize an already-reviewed plan. Regeneration is replace-in-place, but first writes
     * and fsyncs a same-directory temporary file so a failed generation never truncates a
     * previously usable playlist.
     */
    fun write(plan: FolderPlaylistPlan): FolderPlaylistWriteResult {
        validateMaterializationPlan(plan)
        val directory = plan.directory.canonicalFile
        val directoryPath = directory.toPath()
        val target = directoryPath.resolve(plan.fileName).normalize()

        val staging = Files.createTempFile(directoryPath, ".properpcloud-playlist-", ".tmp")
        try {
            Files.write(
                staging,
                plan.extendedM3u.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            FileChannel.open(staging, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(
                    staging,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Playlists are derived/regenerable artifacts. Same-directory replacement is an
                // acceptable compatibility fallback after the staged bytes were flushed.
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }

        return FolderPlaylistWriteResult(
            file = target.toFile(),
            entryCount = plan.entries.size,
            order = plan.order,
            relativeEntries = plan.relativeEntries,
            sha256 = target.toFile().sha256ForPlaylist(),
            durationFallbackCount = plan.durationFallbackCount,
        )
    }

    /** Preflight every output before changing any derived playlist, then write sequentially. */
    fun writeBatch(
        plan: FolderPlaylistBatchPlan,
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderPlaylistBatchWriteResult {
        require(!plan.recursive || plan.recursiveOptInConfirmed) {
            "recursive playlist materialization requires recorded opt-in"
        }
        val root = plan.rootDirectory.canonicalFile
        require(root.isDirectory) { "playlist batch root must be a directory: $root" }
        plan.reviewedDirectoryEvidence.forEach { evidence ->
            require(isPathInside(root.toPath(), evidence.directory.canonicalFile.toPath())) {
                "reviewed playlist source directory escaped the selected subtree: ${evidence.directory}"
            }
            validateDirectoryEvidence(evidence)
        }
        if (plan.recursive) {
            val reviewed = plan.reviewedDirectories.mapTo(linkedSetOf()) { it.canonicalFile }
            require(reviewed.isNotEmpty() && root in reviewed) {
                "recursive playlist plan is missing reviewed directory membership"
            }
            val evidenced = plan.reviewedDirectoryEvidence.mapTo(linkedSetOf()) { it.directory.canonicalFile }
            require(evidenced == reviewed) {
                "recursive playlist plan is missing reviewed audio membership evidence"
            }
            val current = collectSubtreeDirectories(root)
            require(current == reviewed) {
                "playlist subtree directory membership changed after preview; reconcile and preview again: $root"
            }
        }
        plan.playlists.forEach { playlist ->
            require(isPathInside(root.toPath(), playlist.directory.canonicalFile.toPath())) {
                "playlist output escaped the selected subtree: ${playlist.directory}"
            }
            validateMaterializationPlan(playlist)
        }

        val results = mutableListOf<FolderPlaylistWriteResult>()
        plan.playlists.forEachIndexed { index, playlist ->
            val result = write(playlist)
            results += result
            onProgress(
                FolderPlaylistBatchProgress(
                    completed = index + 1,
                    total = plan.playlists.size,
                    targetFile = result.file,
                    entryCount = result.entryCount,
                ),
            )
        }
        return FolderPlaylistBatchWriteResult(plan, results)
    }

    private fun collectSubtreeDirectories(root: File): Set<File> {
        val directories = linkedSetOf<File>()
        fun visit(directory: File) {
            val canonical = directory.canonicalFile
            directories += canonical
            canonical.listFiles()
                .orEmpty()
                .filter { child -> child.isDirectory && !Files.isSymbolicLink(child.toPath()) }
                .sortedWith { left, right -> NaturalTextComparator.compare(left.name, right.name) }
                .forEach(::visit)
        }
        visit(root)
        return directories
    }

    private fun buildPlan(
        directory: File,
        rows: List<FileTagProposals>,
        membershipSnapshots: List<FolderTagSnapshot>,
        order: FolderPlaylistOrder,
        outputName: String?,
        allowedRoot: File,
    ): FolderPlaylistPlan {
        val canonicalDirectory = directory.canonicalFile
        val canonicalRoot = allowedRoot.canonicalFile
        require(isPathInside(canonicalRoot.toPath(), canonicalDirectory.toPath())) {
            "playlist target escaped the selected root: $canonicalDirectory"
        }
        val sortedRows = sortRows(rows, order)
        val entries = sortedRows.map { row ->
            validateRow(canonicalRoot, canonicalDirectory, row)
            val source = row.identity.file.canonicalFile
            val relative = canonicalDirectory.toPath().relativize(source.toPath()).toString()
                .replace(File.separatorChar, '/')
            require(relative.isNotBlank() && relative != "." && !relative.startsWith("../") && relative != "..") {
                "playlist entry escaped its target directory: ${row.identity.filename}"
            }
            require(relative.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "playlist format cannot represent a path containing a line break or NUL"
            }
            FolderPlaylistEntryPlan(
                nodeId = row.identity.nodeId,
                sourceFile = source,
                relativePath = "./$relative",
                durationSeconds = row.originalSnapshot.durationMillis
                    ?.let { ((it + 500L) / 1_000L).coerceAtLeast(1L) },
                displayTitle = displayTitle(row),
                expectedContentEvidence = row.identity.contentEvidence,
                expectedSha256 = row.identity.contentEvidence.sha256 ?: source.sha256ForPlaylist(),
            )
        }
        val directoryEvidence = directoryEvidenceFor(membershipSnapshots)
        val content = buildString {
            append("#EXTM3U\n")
            entries.forEach { entry ->
                append("#EXTINF:")
                append(entry.durationSeconds ?: -1)
                append(',')
                append(entry.displayTitle)
                append('\n')
                append(entry.relativePath)
                append('\n')
            }
        }
        val defaultName = derivedPlaylistStem(sortedRows, canonicalDirectory.name)
        return FolderPlaylistPlan(
            directory = canonicalDirectory,
            fileName = playlistFileName(outputName ?: defaultName),
            order = order,
            entries = entries,
            directoryEvidence = directoryEvidence,
            extendedM3u = content,
        )
    }

    private fun validateRow(root: File, directory: File, row: FileTagProposals) {
        require(row.identity.file.name == row.identity.filename) {
            "playlist identity filename does not match the inspected file"
        }
        require(!Files.isSymbolicLink(row.identity.file.toPath())) {
            "playlist entry must not be a symbolic link: ${row.identity.filename}"
        }
        val source = row.identity.file.canonicalFile
        require(isPathInside(root.toPath(), source.toPath())) {
            "playlist entry escaped the selected root: ${row.identity.filename}"
        }
        require(isPathInside(directory.toPath(), source.toPath()) && source != directory) {
            "playlist entry escaped its playlist directory: ${row.identity.filename}"
        }
        require(row.identity.filename.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "playlist format cannot represent a filename containing a line break or NUL"
        }
    }

    private fun validateMaterializationPlan(plan: FolderPlaylistPlan) {
        val directory = plan.directory.canonicalFile
        require(directory.isDirectory && directory.canWrite()) {
            "playlist target is not a writable directory: $directory"
        }
        val directoryPath = directory.toPath()
        require(!Files.isSymbolicLink(plan.directory.toPath())) {
            "playlist target directory must not be a symbolic link: ${plan.directory}"
        }
        val target = directoryPath.resolve(plan.fileName).normalize()
        require(target.parent == directoryPath) { "playlist must remain inside the planned directory" }
        require(!Files.isSymbolicLink(target)) { "refusing to replace a playlist symlink: $target" }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "playlist target is not a regular file: $target"
            }
        }

        plan.directoryEvidence.forEach(::validateDirectoryEvidence)

        plan.entries.forEach { entry ->
            require(entry.relativePath.startsWith("./")) { "playlist entry must remain location-relative" }
            require(entry.relativePath.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "playlist entry contains an unsupported line break or NUL"
            }
            val sourcePath = entry.sourceFile.canonicalFile.toPath()
            val representedPath = directoryPath.resolve(entry.relativePath.removePrefix("./")).normalize()
            require(representedPath == sourcePath) {
                "playlist entry path no longer resolves to its reviewed media file: ${entry.relativePath}"
            }
            require(isPathInside(directoryPath, sourcePath) && sourcePath != directoryPath) {
                "playlist entry escaped the planned directory: ${entry.relativePath}"
            }
            require(Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sourcePath)) {
                "playlist entry is no longer a regular non-symlink media file: ${entry.relativePath}"
            }
            val currentEvidence = ContentEvidence(
                sizeBytes = Files.size(sourcePath),
                modifiedTimeNanos = lastModifiedNanos(sourcePath),
            )
            require(
                currentEvidence.sizeBytes == entry.expectedContentEvidence.sizeBytes &&
                    currentEvidence.modifiedTimeNanos == entry.expectedContentEvidence.modifiedTimeNanos
            ) {
                "playlist entry changed after preview; reconcile and preview again: ${entry.relativePath}"
            }
            require(entry.sourceFile.sha256ForPlaylist().equals(entry.expectedSha256, ignoreCase = true)) {
                "playlist entry bytes changed after preview; reconcile and preview again: ${entry.relativePath}"
            }
        }
    }

    private fun sortRows(
        rows: List<FileTagProposals>,
        order: FolderPlaylistOrder,
    ): List<FileTagProposals> = rows.sortedWith(Comparator { left, right ->
        when (order) {
            FolderPlaylistOrder.NATURAL_FILENAME -> compareFilename(left, right)
            FolderPlaylistOrder.TAGGED_TITLE -> {
                val byTitle = NaturalTextComparator.compare(tagTitle(left), tagTitle(right))
                if (byTitle != 0) byTitle else compareFilename(left, right)
            }
            FolderPlaylistOrder.TITLE_NUMBER -> compareTitleNumber(left, right)
            FolderPlaylistOrder.TAG_TRACK_NUMBER -> {
                val disc = compareValues(tagNumber(left, TagField.DISC_NUMBER), tagNumber(right, TagField.DISC_NUMBER))
                if (disc != 0) return@Comparator disc
                val track = compareValues(tagNumber(left, TagField.TRACK_NUMBER), tagNumber(right, TagField.TRACK_NUMBER))
                if (track != 0) return@Comparator track
                compareFilename(left, right)
            }
            FolderPlaylistOrder.MODIFICATION_TIME -> {
                val modified = compareValues(
                    left.identity.contentEvidence.modifiedTimeNanos,
                    right.identity.contentEvidence.modifiedTimeNanos,
                )
                if (modified != 0) modified else compareFilename(left, right)
            }
        }
    })

    private fun directoryEvidenceFor(
        snapshots: List<FolderTagSnapshot>,
    ): List<FolderPlaylistDirectoryEvidence> = snapshots
        .map { snapshot ->
            FolderPlaylistDirectoryEvidence(
                directory = snapshot.folderPath.canonicalFile,
                expectedAudioFileNames = snapshot.files.mapTo(linkedSetOf()) { it.identity.filename },
            )
        }
        .distinctBy { it.directory }
        .sortedWith { left, right ->
            NaturalTextComparator.compare(left.directory.absolutePath, right.directory.absolutePath)
        }

    private fun currentAudioFileNames(directory: File): Set<String> = directory.canonicalFile.listFiles()
        .orEmpty()
        .filter { child ->
            child.isFile &&
                !Files.isSymbolicLink(child.toPath()) &&
                !child.name.startsWith(".properpcloud-") &&
                child.extension.lowercase() in FolderTagScanner.SUPPORTED_EXTENSIONS
        }
        .mapTo(linkedSetOf()) { it.name }

    private fun validateDirectoryEvidence(evidence: FolderPlaylistDirectoryEvidence) {
        val evidenceDirectory = evidence.directory.canonicalFile
        require(evidenceDirectory.isDirectory && !Files.isSymbolicLink(evidence.directory.toPath())) {
            "playlist source directory changed after preview; reconcile and preview again: $evidenceDirectory"
        }
        require(currentAudioFileNames(evidenceDirectory) == evidence.expectedAudioFileNames) {
            "playlist folder membership changed after preview; reconcile and preview again: $evidenceDirectory"
        }
    }

    private fun compareFilename(left: FileTagProposals, right: FileTagProposals): Int {
        val byFilename = NaturalTextComparator.compare(left.identity.filename, right.identity.filename)
        if (byFilename != 0) return byFilename
        // Equal leaf names can occur when a grouped album spans multiple disc folders.
        // Use the reviewed filesystem location as a stable final tie-break, never tag data.
        return NaturalTextComparator.compare(
            left.identity.file.canonicalPath,
            right.identity.file.canonicalPath,
        )
    }

    private fun tagNumber(row: FileTagProposals, field: TagField): Int =
        row.originalSnapshot.fields[field]?.value
            ?.substringBefore('/')
            ?.trim()
            ?.toIntOrNull()
            ?: Int.MAX_VALUE

    private fun tagTitle(row: FileTagProposals): String =
        row.originalSnapshot.fields[TagField.TITLE]?.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: row.identity.file.nameWithoutExtension

    /**
     * "Title number" is the leading decimal integer in the embedded TITLE after trimming.
     * Numeric titles sort first by arbitrary-precision numeric value, then full title and
     * filesystem identity. Non-numeric titles follow in natural title order; missing titles
     * sort last by natural filename/path. This is deterministic and locale-independent.
     */
    private fun compareTitleNumber(left: FileTagProposals, right: FileTagProposals): Int {
        val leftTitle = embeddedTitle(left)
        val rightTitle = embeddedTitle(right)
        val leftNumber = leadingTitleNumber(leftTitle)
        val rightNumber = leadingTitleNumber(rightTitle)
        val leftRank = when {
            leftNumber != null -> 0
            leftTitle != null -> 1
            else -> 2
        }
        val rightRank = when {
            rightNumber != null -> 0
            rightTitle != null -> 1
            else -> 2
        }
        if (leftRank != rightRank) return leftRank.compareTo(rightRank)
        if (leftNumber != null && rightNumber != null) {
            val byNumber = leftNumber.compareTo(rightNumber)
            if (byNumber != 0) return byNumber
        }
        if (leftTitle != null && rightTitle != null) {
            val byTitle = NaturalTextComparator.compare(leftTitle, rightTitle)
            if (byTitle != 0) return byTitle
        }
        return compareFilename(left, right)
    }

    private fun embeddedTitle(row: FileTagProposals): String? =
        row.originalSnapshot.fields[TagField.TITLE]?.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun leadingTitleNumber(title: String?): BigInteger? = title
        ?.let { LEADING_TITLE_NUMBER.find(it)?.groupValues?.get(1) }
        ?.toBigIntegerOrNull()

    private fun displayTitle(row: FileTagProposals): String {
        val title = tagTitle(row)
        val artist = row.originalSnapshot.fields[TagField.ARTIST]?.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return sanitizeM3uMetadata(if (artist == null) title else "$artist - $title")
    }

    private fun derivedPlaylistStem(rows: List<FileTagProposals>, folderFallback: String): String {
        val album = unanimousTag(rows, TagField.ALBUM) ?: return folderFallback
        val albumArtist = unanimousTag(rows, TagField.ALBUM_ARTIST) ?: unanimousTag(rows, TagField.ARTIST)
        return if (albumArtist == null) album else "$albumArtist - $album"
    }

    private fun unanimousTag(rows: List<FileTagProposals>, field: TagField): String? {
        if (rows.isEmpty()) return null
        val values = rows.mapNotNull { row ->
            row.originalSnapshot.fields[field]?.value?.trim()?.takeIf(String::isNotEmpty)
        }
        if (values.size != rows.size) return null
        return values.distinct().singleOrNull()
    }

    private fun albumDirectoryFor(root: File, directory: File): File {
        if (!DISC_FOLDER_PATTERN.matches(directory.name)) return directory
        val parent = directory.parentFile?.canonicalFile ?: return directory
        return parent.takeIf { isPathInside(root.toPath(), it.toPath()) } ?: directory
    }

    private fun sanitizeM3uMetadata(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun playlistFileName(raw: String): String {
        val withoutExtension = when {
            raw.endsWith(".m3u8", ignoreCase = true) -> raw.dropLast(5)
            raw.endsWith(".m3u", ignoreCase = true) -> raw.dropLast(4)
            else -> raw
        }
        var stem = withoutExtension
            .trim()
            .replace(UNSAFE_PLAYLIST_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .ifBlank { "playlist" }
            .take(MAX_PLAYLIST_STEM_CHARS)
            .trimEnd(' ', '.')
        if (WINDOWS_RESERVED_NAME.matches(stem)) stem = "$stem playlist"
        return "$stem.m3u8"
    }

    private fun isPathInside(root: Path, candidate: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        return normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith(normalizedRoot)
    }

    companion object {
        private val LEADING_TITLE_NUMBER = Regex("^(\\d+)")
        private const val MAX_PLAYLIST_STEM_CHARS = 120
        private val UNSAFE_PLAYLIST_NAME_CHARS = Regex("[\\u0000-\\u001f<>:\"/\\\\|?*\\u007f]")
        private val WINDOWS_RESERVED_NAME = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$")
        private val DISC_FOLDER_PATTERN = Regex("(?i)^(?:cd|disc|disk|part)[ _.-]*0*([1-9][0-9]*)$")
    }
}

private fun File.sha256ForPlaylist(): String {
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
