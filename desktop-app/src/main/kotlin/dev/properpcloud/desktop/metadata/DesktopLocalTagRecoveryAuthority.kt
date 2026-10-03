package dev.properpcloud.desktop.metadata

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.desktop.data.DesktopLocalFilesystemIdentity
import dev.properpcloud.metadata.tags.LocalTagRecoveryAuthority
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64

/** A redacted problem discovered while re-associating durable local-tag recovery evidence. */
data class DesktopLocalTagRecoveryIssue(
    val filename: String? = null,
    val message: String,
)

/**
 * Recovery state discovered only after the user has explicitly reselected a local root.
 * Complete paths and recovery sibling names never leave this adapter.
 */
data class DesktopLocalTagRecoveryState(
    val recoverableResults: List<FileApplyResult> = emptyList(),
    val issues: List<DesktopLocalTagRecoveryIssue> = emptyList(),
    val cleanedRecordCount: Int = 0,
) {
    val recoveryRequired: Boolean get() = recoverableResults.isNotEmpty() || issues.isNotEmpty()
    val rollbackAvailableCount: Int get() = recoverableResults.count { result ->
        result.resultSha256 != null && result.rollbackFile?.isFile == true
    }

    companion object {
        val EMPTY = DesktopLocalTagRecoveryState()
    }
}

/**
 * Native-desktop durable recovery authority for one explicitly selected filesystem root.
 *
 * The authority never persists the selected root or a complete media path. Before media
 * replacement it writes a private, atomically published sibling record containing only encoded
 * target/rollback basenames and the reviewed original/expected-result SHA-256 values. After a
 * restart the user must explicitly reselect a root; only then are those sibling records walked
 * and resolved relative to their own containing directory.
 *
 * A record grants rollback authority only when all of the following are true:
 * - record, target, and rollback stay in one non-symlink directory under the selected root;
 * - rollback bytes hash to the recorded original SHA-256; and
 * - current target bytes hash to the recorded expected-result SHA-256.
 *
 * If current bytes already equal the original, the stale recovery record is removed. Any other
 * state stays blocked and never gains a force-overwrite path.
 */
class DesktopLocalTagRecoveryAuthority : LocalTagRecoveryAuthority {
    override fun arm(
        target: File,
        rollbackFile: File,
        originalSha256: String,
        expectedResultSha256: String,
    ) {
        requireSha256(originalSha256, "original")
        requireSha256(expectedResultSha256, "expected result")
        val targetPath = target.toPath().toAbsolutePath().normalize()
        val rollbackPath = rollbackFile.toPath().toAbsolutePath().normalize()
        val parent = targetPath.parent ?: error("local recovery target has no parent")
        require(rollbackPath.parent == parent) { "local recovery bytes must be a target sibling" }
        requireRegularNonSymlink(targetPath, "local recovery target")
        requireRegularNonSymlink(rollbackPath, "local recovery rollback bytes")
        require(sha256(rollbackPath).equals(originalSha256, ignoreCase = true)) {
            "local recovery rollback SHA-256 does not match the reviewed original"
        }

        val record = recordPath(parent, rollbackPath.fileName.toString())
        val temporary = Files.createTempFile(parent, RECOVERY_WRITE_PREFIX, ".tmp")
        try {
            applyPrivatePermissions(temporary)
            Files.writeString(
                temporary,
                encodeRecord(
                    targetName = targetPath.fileName.toString(),
                    rollbackName = rollbackPath.fileName.toString(),
                    originalSha256 = originalSha256,
                    expectedResultSha256 = expectedResultSha256,
                ),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            forceFile(temporary)
            Files.move(
                temporary,
                record,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectoryBestEffort(parent)
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw IllegalStateException("could not persist local tag recovery authority", error)
        }
    }

    override fun disarm(target: File, rollbackFile: File) {
        val targetPath = target.toPath().toAbsolutePath().normalize()
        val rollbackPath = rollbackFile.toPath().toAbsolutePath().normalize()
        val parent = targetPath.parent ?: error("local recovery target has no parent")
        require(rollbackPath.parent == parent) { "local recovery bytes must be a target sibling" }
        Files.deleteIfExists(recordPath(parent, rollbackPath.fileName.toString()))
        forceDirectoryBestEffort(parent)
    }

    /**
     * Discover recovery authority under a newly and explicitly selected root. This walks the
     * selected tree because a previous session may have been recursive even when this reopening
     * is not. Symlink directories are never followed.
     */
    fun discover(identity: DesktopLocalFilesystemIdentity): DesktopLocalTagRecoveryState {
        val root = identity.canonicalRoot.toPath().toAbsolutePath().normalize()
        val records = mutableListOf<Path>()
        val staleWrites = mutableListOf<Path>()
        val issues = mutableListOf<DesktopLocalTagRecoveryIssue>()
        var recordLimitExceeded = false

        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName?.toString().orEmpty()
                    when {
                        name.startsWith(RECOVERY_WRITE_PREFIX) -> staleWrites.add(file)
                        isRecoveryRecordName(name) -> {
                            if (records.size < MAX_RECOVERY_RECORDS) {
                                records.add(file)
                            } else {
                                recordLimitExceeded = true
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    issues += DesktopLocalTagRecoveryIssue(
                        message = "A selected-root subtree could not be checked for interrupted tag recovery; metadata writes remain blocked.",
                    )
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {
            issues += DesktopLocalTagRecoveryIssue(
                message = "The selected root could not be fully checked for interrupted tag recovery; metadata writes remain blocked.",
            )
        }

        staleWrites.forEach { path -> runCatching { Files.deleteIfExists(path) } }
        if (recordLimitExceeded) {
            issues += DesktopLocalTagRecoveryIssue(
                message = "Too many interrupted tag recovery records were found; metadata writes remain blocked.",
            )
        }

        val recoverable = mutableListOf<FileApplyResult>()
        var cleaned = 0
        records.sortedBy { path -> path.toString() }.forEach { record ->
            when (val inspected = inspectRecord(root, record, identity)) {
                is RecordInspection.Recoverable -> recoverable += inspected.result
                is RecordInspection.Blocked -> issues += inspected.issue
                RecordInspection.Cleaned -> cleaned += 1
            }
        }
        return DesktopLocalTagRecoveryState(
            recoverableResults = recoverable,
            issues = issues,
            cleanedRecordCount = cleaned,
        )
    }

    private fun inspectRecord(
        root: Path,
        record: Path,
        identity: DesktopLocalFilesystemIdentity,
    ): RecordInspection {
        if (!record.normalize().startsWith(root) ||
            Files.isSymbolicLink(record) ||
            !Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)
        ) {
            return blocked("An interrupted tag recovery record is not a regular local file.")
        }
        val size = runCatching { Files.size(record) }.getOrNull()
        if (size == null || size !in 1..MAX_RECORD_BYTES.toLong()) {
            return blocked("An interrupted tag recovery record has an invalid size.")
        }

        val parsed = runCatching { decodeRecord(Files.readString(record, StandardCharsets.UTF_8)) }
            .getOrElse { return blocked("An interrupted tag recovery record is malformed; metadata writes remain blocked.") }
        val parent = record.parent?.toAbsolutePath()?.normalize()
            ?: return blocked("An interrupted tag recovery record has no containing directory.")
        if (parent != root && !parent.startsWith(root)) {
            return blocked("An interrupted tag recovery record escaped the explicitly selected root.")
        }
        if (record.fileName.toString() != recordFileName(parsed.rollbackName)) {
            return blocked("An interrupted tag recovery record does not match its rollback identity.", parsed.targetName)
        }

        val target = parent.resolve(parsed.targetName).normalize()
        val rollback = parent.resolve(parsed.rollbackName).normalize()
        if (target.parent != parent || rollback.parent != parent) {
            return blocked("Interrupted tag recovery names are not safe sibling identities.", parsed.targetName)
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return blocked("The interrupted tag target is unavailable as a regular non-symlink file.", parsed.targetName)
        }

        val currentHash = runCatching { sha256(target) }.getOrNull()
            ?: return blocked("The interrupted tag target could not be hashed.", parsed.targetName)
        if (currentHash.equals(parsed.originalSha256, ignoreCase = true)) {
            // The destructive boundary was never crossed, or a rollback was already completed.
            // Remove the record. Delete a sibling rollback only after independently proving that
            // it also contains these exact original bytes, so a forged/stale record can never
            // delete unrelated recovery material.
            runCatching { Files.deleteIfExists(record) }
            val rollbackIsExactOriginal = !Files.isSymbolicLink(rollback) &&
                Files.isRegularFile(rollback, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { sha256(rollback).equals(parsed.originalSha256, ignoreCase = true) }.getOrDefault(false)
            if (rollbackIsExactOriginal) runCatching { Files.deleteIfExists(rollback) }
            forceDirectoryBestEffort(parent)
            return RecordInspection.Cleaned
        }

        if (Files.isSymbolicLink(rollback) || !Files.isRegularFile(rollback, LinkOption.NOFOLLOW_LINKS)) {
            return blocked("Exact rollback bytes for the interrupted tag operation are unavailable.", parsed.targetName)
        }
        val rollbackHash = runCatching { sha256(rollback) }.getOrNull()
        if (rollbackHash == null || !rollbackHash.equals(parsed.originalSha256, ignoreCase = true)) {
            return blocked("Retained rollback bytes do not match the reviewed original SHA-256.", parsed.targetName)
        }
        if (!currentHash.equals(parsed.expectedResultSha256, ignoreCase = true)) {
            return blocked(
                "Current bytes match neither the reviewed original nor the interrupted replacement; no rollback overwrite is authorized.",
                parsed.targetName,
            )
        }

        val targetFile = target.toFile()
        val contentEvidence = runCatching { contentEvidence(target) }
            .getOrElse { return blocked("Current interrupted tag bytes could not be re-associated with stable file evidence.", parsed.targetName) }
        val localIdentity = LocalFileIdentity(
            sourceId = identity.sourceId,
            nodeId = runCatching { identity.nodeId(targetFile, directory = false) }
                .getOrElse { return blocked("The interrupted tag target is outside the explicitly selected root.", parsed.targetName) },
            file = targetFile,
            filename = parsed.targetName,
            contentEvidence = contentEvidence,
        )
        return RecordInspection.Recoverable(
            FileApplyResult(
                identity = localIdentity,
                status = ApplyResultStatus.INDETERMINATE,
                message = "Recovered an interrupted local tag replacement after this root was explicitly reselected; current bytes exactly match the staged result and guarded rollback is available.",
                originalSha256 = parsed.originalSha256,
                resultSha256 = parsed.expectedResultSha256,
                rollbackFile = rollback.toFile(),
            ),
        )
    }

    private fun blocked(message: String, filename: String? = null): RecordInspection.Blocked =
        RecordInspection.Blocked(DesktopLocalTagRecoveryIssue(filename = filename, message = message))

    private fun contentEvidence(path: Path): ContentEvidence {
        val instant = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
        val modifiedTimeNanos = Math.addExact(
            Math.multiplyExact(instant.epochSecond, 1_000_000_000L),
            instant.nano.toLong(),
        )
        return ContentEvidence(
            sizeBytes = Files.size(path),
            modifiedTimeNanos = modifiedTimeNanos,
            sha256 = sha256(path),
        )
    }

    private fun encodeRecord(
        targetName: String,
        rollbackName: String,
        originalSha256: String,
        expectedResultSha256: String,
    ): String = buildString {
        appendLine(RECORD_MAGIC)
        appendLine("target=${encodeName(targetName)}")
        appendLine("rollback=${encodeName(rollbackName)}")
        appendLine("original_sha256=${originalSha256.lowercase()}")
        appendLine("expected_result_sha256=${expectedResultSha256.lowercase()}")
    }

    private fun decodeRecord(content: String): RecoveryRecord {
        val lines = content.lineSequence().filter(String::isNotEmpty).toList()
        require(lines.size == 5 && lines.first() == RECORD_MAGIC) { "invalid recovery record schema" }
        val values = lines.drop(1).associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid recovery record field" }
            line.substring(0, separator) to line.substring(separator + 1)
        }
        require(values.size == 4) { "duplicate recovery record fields" }
        val targetName = decodeName(requireNotNull(values["target"]))
        val rollbackName = decodeName(requireNotNull(values["rollback"]))
        requireSafeBasename(targetName, "target")
        requireSafeBasename(rollbackName, "rollback")
        require(rollbackName.startsWith(ROLLBACK_PREFIX)) { "invalid rollback recovery identity" }
        val originalSha256 = requireNotNull(values["original_sha256"])
        val expectedResultSha256 = requireNotNull(values["expected_result_sha256"])
        requireSha256(originalSha256, "original")
        requireSha256(expectedResultSha256, "expected result")
        return RecoveryRecord(targetName, rollbackName, originalSha256.lowercase(), expectedResultSha256.lowercase())
    }

    private fun encodeName(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeName(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )

    private fun requireSafeBasename(value: String, label: String) {
        require(value.isNotBlank() && value != "." && value != "..") { "$label recovery name is blank or reserved" }
        require('/' !in value && '\u0000' !in value) { "$label recovery name contains a path separator or NUL" }
    }

    private fun requireSha256(value: String, label: String) {
        require(SHA256_REGEX.matches(value)) { "$label recovery SHA-256 is invalid" }
    }

    private fun requireRegularNonSymlink(path: Path, label: String) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label must be a regular non-symlink file"
        }
    }

    private fun recordPath(parent: Path, rollbackName: String): Path = parent.resolve(recordFileName(rollbackName))

    private fun recordFileName(rollbackName: String): String =
        "$RECOVERY_RECORD_PREFIX${sha256Text(rollbackName).take(32)}$RECOVERY_RECORD_SUFFIX"

    private fun isRecoveryRecordName(name: String): Boolean =
        name.startsWith(RECOVERY_RECORD_PREFIX) && name.endsWith(RECOVERY_RECORD_SUFFIX)

    private fun applyPrivatePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun forceDirectoryBestEffort(path: Path) {
        runCatching {
            FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class RecoveryRecord(
        val targetName: String,
        val rollbackName: String,
        val originalSha256: String,
        val expectedResultSha256: String,
    )

    private sealed interface RecordInspection {
        data class Recoverable(val result: FileApplyResult) : RecordInspection
        data class Blocked(val issue: DesktopLocalTagRecoveryIssue) : RecordInspection
        data object Cleaned : RecordInspection
    }

    private companion object {
        const val RECORD_MAGIC = "properpcloud-local-tag-recovery-v1"
        const val RECOVERY_RECORD_PREFIX = ".properpcloud-recovery-"
        const val RECOVERY_RECORD_SUFFIX = ".v1"
        const val RECOVERY_WRITE_PREFIX = ".properpcloud-recovery-write-"
        const val ROLLBACK_PREFIX = ".properpcloud-rollback-"
        const val MAX_RECOVERY_RECORDS = 1_024
        const val MAX_RECORD_BYTES = 8_192
        val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
