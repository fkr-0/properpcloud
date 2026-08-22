package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderStructureTagConfig
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import java.io.Closeable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A truthful capability for metadata work against one caller-selected local filesystem root.
 *
 * Construction proves that the root is a readable/writable non-symlink directory and that a
 * same-directory atomic rename is available. The probe creates only unpredictable hidden probe
 * files and removes them before returning; it never opens or mutates media. A provider cache or
 * prepared download is not a local-root capability unless a client explicitly owns it as such.
 */
class LocalFolderRootCapability private constructor(
    val rootDirectory: File,
    val sourceId: SourceId,
) {
    companion object {
        fun open(rootDirectory: File, sourceId: SourceId): LocalFolderRootCapability {
            require(rootDirectory.isDirectory) { "local metadata root must be a directory: $rootDirectory" }
            require(rootDirectory.canRead()) { "local metadata root is not readable: $rootDirectory" }
            require(rootDirectory.canWrite()) { "local metadata root is not writable: $rootDirectory" }
            require(!Files.isSymbolicLink(rootDirectory.toPath())) {
                "local metadata root must not be a symbolic link: $rootDirectory"
            }
            val canonical = rootDirectory.canonicalFile
            require(!Files.getFileStore(canonical.toPath()).isReadOnly) {
                "local metadata root is on a read-only filesystem: $canonical"
            }
            proveAtomicSiblingMove(canonical.toPath())
            return LocalFolderRootCapability(canonical, sourceId)
        }

        private fun proveAtomicSiblingMove(root: Path) {
            val source = Files.createTempFile(root, ".properpcloud-capability-", ".probe")
            val target = Files.createTempFile(root, ".properpcloud-capability-", ".replace-probe")
            try {
                Files.writeString(source, "source")
                Files.writeString(target, "target")
                try {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    throw IllegalArgumentException(
                        "local metadata root does not support same-directory atomic replacement",
                        unsupported,
                    )
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "could not prove same-directory atomic replacement for local metadata root",
                        error,
                    )
                }
                require(!Files.exists(source) && Files.readString(target) == "source") {
                    "same-directory atomic replacement probe did not replace the target"
                }
            } finally {
                Files.deleteIfExists(source)
                Files.deleteIfExists(target)
            }
        }
    }
}

enum class LocalFolderChangeKind {
    CREATED,
    MODIFIED,
    DELETED,
    OVERFLOW,
    OBSERVER_INVALID,
}

data class LocalFolderChangeEvent(
    val kind: LocalFolderChangeKind,
    val relativePath: String? = null,
    val directory: Boolean = false,
)

data class LocalFolderChangeBatch(
    val events: List<LocalFolderChangeEvent>,
) {
    val overflow: Boolean get() = events.any { it.kind == LocalFolderChangeKind.OVERFLOW }
    val observerInvalid: Boolean get() = events.any { it.kind == LocalFolderChangeKind.OBSERVER_INVALID }
}

/**
 * Low-level observer callbacks are intentionally content-blind. [onEvent] is called immediately
 * so approvals can be revoked before debounce; [onBatch] is a coalesced reconciliation trigger.
 */
interface LocalFolderChangeObserver : Closeable {
    fun start(
        onEvent: (LocalFolderChangeEvent) -> Unit,
        onBatch: (LocalFolderChangeBatch) -> Unit,
    )
}

fun interface LocalFolderChangeObserverFactory {
    fun open(
        capability: LocalFolderRootCapability,
        recursive: Boolean,
        quietWindowMillis: Long,
        maximumCoalescingLatencyMillis: Long,
    ): LocalFolderChangeObserver
}

object JdkLocalFolderChangeObserverFactory : LocalFolderChangeObserverFactory {
    override fun open(
        capability: LocalFolderRootCapability,
        recursive: Boolean,
        quietWindowMillis: Long,
        maximumCoalescingLatencyMillis: Long,
    ): LocalFolderChangeObserver = JdkLocalFolderChangeObserver(
        capability = capability,
        recursive = recursive,
        quietWindowMillis = quietWindowMillis,
        maximumCoalescingLatencyMillis = maximumCoalescingLatencyMillis,
    )
}

/**
 * Portable JVM watcher backed by [WatchService]. Registration is completed synchronously in the
 * constructor so the caller can establish the observer lease before starting its initial scan.
 * The watcher thread performs no tag parsing, hashing, database access, or presentation work.
 */
private class JdkLocalFolderChangeObserver(
    capability: LocalFolderRootCapability,
    private val recursive: Boolean,
    private val quietWindowMillis: Long,
    private val maximumCoalescingLatencyMillis: Long,
) : LocalFolderChangeObserver {
    private val root = capability.rootDirectory.toPath().toAbsolutePath().normalize()
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val keyDirectories = linkedMapOf<WatchKey, Path>()
    private val knownDirectories = linkedSetOf<Path>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var thread: Thread? = null

    init {
        require(quietWindowMillis in 1..10_000L) { "watch quiet window is outside the supported bound" }
        require(maximumCoalescingLatencyMillis in quietWindowMillis..60_000L) {
            "watch maximum coalescing latency must be >= quiet window and bounded"
        }
        registerInitialLease()
    }

    override fun start(
        onEvent: (LocalFolderChangeEvent) -> Unit,
        onBatch: (LocalFolderChangeBatch) -> Unit,
    ) {
        check(started.compareAndSet(false, true)) { "local folder observer already started" }
        thread = Thread(
            { watchLoop(onEvent, onBatch) },
            "properpcloud-folder-watch",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun registerInitialLease() {
        if (recursive) registerTree(root) else registerDirectory(root)
    }

    private fun registerTree(start: Path) {
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(start)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                registerDirectory(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun registerDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        if (normalized !in knownDirectories) {
            val key = normalized.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            keyDirectories[key] = normalized
            knownDirectories.add(normalized)
        }
    }

    private fun watchLoop(
        onEvent: (LocalFolderChangeEvent) -> Unit,
        onBatch: (LocalFolderChangeBatch) -> Unit,
    ) {
        try {
            while (!closed.get()) {
                val firstKey = watchService.take()
                val batch = mutableListOf<LocalFolderChangeEvent>()
                processKey(firstKey, batch, onEvent)
                val startedAt = System.nanoTime()
                var quietDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(quietWindowMillis)
                val maximumDeadline = startedAt + TimeUnit.MILLISECONDS.toNanos(maximumCoalescingLatencyMillis)

                while (!closed.get()) {
                    val now = System.nanoTime()
                    val waitNanos = minOf(quietDeadline - now, maximumDeadline - now)
                    if (waitNanos <= 0L) break
                    val nextKey = watchService.poll(waitNanos, TimeUnit.NANOSECONDS) ?: break
                    val before = batch.size
                    processKey(nextKey, batch, onEvent)
                    if (batch.size > before) {
                        quietDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(quietWindowMillis)
                    }
                }

                if (batch.isNotEmpty()) {
                    onBatch(LocalFolderChangeBatch(coalesce(batch)))
                }
            }
        } catch (_: ClosedWatchServiceException) {
            // Normal close path.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun processKey(
        key: WatchKey,
        batch: MutableList<LocalFolderChangeEvent>,
        onEvent: (LocalFolderChangeEvent) -> Unit,
    ) {
        val directory = keyDirectories[key]
        if (directory == null) {
            emit(LocalFolderChangeEvent(LocalFolderChangeKind.OBSERVER_INVALID), batch, onEvent)
            key.cancel()
            return
        }

        key.pollEvents().forEach { raw ->
            if (raw.kind() == StandardWatchEventKinds.OVERFLOW) {
                emit(LocalFolderChangeEvent(LocalFolderChangeKind.OVERFLOW), batch, onEvent)
                return@forEach
            }
            val context = raw.context() as? Path ?: return@forEach
            val candidate = directory.resolve(context).toAbsolutePath().normalize()
            if (candidate != root && !candidate.startsWith(root)) return@forEach

            val wasKnownDirectory = candidate in knownDirectories
            val isDirectory = when (raw.kind()) {
                StandardWatchEventKinds.ENTRY_DELETE -> wasKnownDirectory
                else -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)
            }
            if (recursive && raw.kind() == StandardWatchEventKinds.ENTRY_CREATE && isDirectory) {
                try {
                    registerTree(candidate)
                } catch (_: Exception) {
                    emit(LocalFolderChangeEvent(LocalFolderChangeKind.OBSERVER_INVALID), batch, onEvent)
                }
            }
            if (raw.kind() == StandardWatchEventKinds.ENTRY_DELETE && wasKnownDirectory) {
                knownDirectories.removeAll { known -> known == candidate || known.startsWith(candidate) }
            }
            if (!isRelevant(candidate, isDirectory)) return@forEach

            val kind = when (raw.kind()) {
                StandardWatchEventKinds.ENTRY_CREATE -> LocalFolderChangeKind.CREATED
                StandardWatchEventKinds.ENTRY_DELETE -> LocalFolderChangeKind.DELETED
                else -> LocalFolderChangeKind.MODIFIED
            }
            emit(
                LocalFolderChangeEvent(
                    kind = kind,
                    relativePath = root.relativize(candidate).toString().replace(File.separatorChar, '/'),
                    directory = isDirectory,
                ),
                batch,
                onEvent,
            )
        }

        if (!key.reset()) {
            keyDirectories.remove(key)
            knownDirectories.remove(directory)
            emit(LocalFolderChangeEvent(LocalFolderChangeKind.OBSERVER_INVALID), batch, onEvent)
        }
    }

    private fun isRelevant(path: Path, directory: Boolean): Boolean {
        if (directory) return recursive
        val name = path.fileName?.toString().orEmpty()
        if (name.startsWith(".properpcloud-")) return false
        return name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in FolderTagScanner.SUPPORTED_EXTENSIONS
    }

    private fun emit(
        event: LocalFolderChangeEvent,
        batch: MutableList<LocalFolderChangeEvent>,
        onEvent: (LocalFolderChangeEvent) -> Unit,
    ) {
        val special = event.kind == LocalFolderChangeKind.OVERFLOW ||
            event.kind == LocalFolderChangeKind.OBSERVER_INVALID
        if (special) {
            if (batch.none { existing -> existing.kind == event.kind }) {
                batch += event
                onEvent(event)
            }
            return
        }
        if (batch.any { existing -> existing.kind == LocalFolderChangeKind.OVERFLOW }) return
        if (batch.size >= MAX_RAW_EVENTS_PER_BATCH) {
            val observerInvalid = batch.firstOrNull { existing ->
                existing.kind == LocalFolderChangeKind.OBSERVER_INVALID
            }
            batch.clear()
            observerInvalid?.let(batch::add)
            val overflow = LocalFolderChangeEvent(LocalFolderChangeKind.OVERFLOW)
            batch += overflow
            onEvent(overflow)
            return
        }
        batch += event
        onEvent(event)
    }

    private fun coalesce(events: List<LocalFolderChangeEvent>): List<LocalFolderChangeEvent> {
        val special = mutableListOf<LocalFolderChangeEvent>()
        val byPath = linkedMapOf<String, LocalFolderChangeEvent>()
        events.forEach { event ->
            val path = event.relativePath
            if (path == null) {
                if (special.none { it.kind == event.kind }) special += event
            } else {
                val previous = byPath[path]
                byPath[path] = when {
                    previous?.kind == LocalFolderChangeKind.CREATED && event.kind == LocalFolderChangeKind.MODIFIED -> previous
                    else -> event
                }
            }
        }
        return special + byPath.values
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { watchService.close() }
        thread?.interrupt()
        thread = null
    }

    private companion object {
        const val MAX_RAW_EVENTS_PER_BATCH = 4_096
    }
}

enum class LocalFolderWorkbenchWatchState {
    CLOSED,
    STARTING,
    SCANNING,
    LIVE,
    STALE,
    OVERFLOW_RESCANNING,
    FAILED,
}

data class LocalFolderWorkbenchStatus(
    val state: LocalFolderWorkbenchWatchState = LocalFolderWorkbenchWatchState.CLOSED,
    val sessionRevision: Long = 0,
    val scanGeneration: Long = 0,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val pendingEventCount: Int = 0,
    val pendingEventKinds: Set<LocalFolderChangeKind> = emptySet(),
    val pendingPlaylistRegenerations: Int = 0,
    val reconciliationRequired: Boolean = false,
    val lastReconciledAtEpochMillis: Long? = null,
    val message: String = "Local metadata workbench is closed.",
    val error: String? = null,
)

/**
 * Real local-filesystem host for [FolderMetadataSuiteSession].
 *
 * This host deliberately owns no provider mapping and no desktop/Android navigation. A caller
 * must first supply [LocalFolderRootCapability]. The JDK observer lease is registered before the
 * first scan, events revoke session reviews immediately, batches are coalesced before one full
 * authoritative rescan, overflow/invalid observers cannot return to LIVE without a fresh scan,
 * and watcher callbacks never stage or apply tags.
 */
class LocalFolderWorkbenchHost(
    private val capability: LocalFolderRootCapability,
    private val session: FolderMetadataSuiteSession,
    private val recursive: Boolean = false,
    private val structureConfig: FolderStructureTagConfig = FolderStructureTagConfig(),
    private val onlineLookupConsent: Boolean = false,
    private val candidateLimitPerFile: Int = 5,
    private val observerFactory: LocalFolderChangeObserverFactory = JdkLocalFolderChangeObserverFactory,
    private val quietWindowMillis: Long = DEFAULT_QUIET_WINDOW_MILLIS,
    private val maximumCoalescingLatencyMillis: Long = DEFAULT_MAXIMUM_COALESCING_LATENCY_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes authoritative scans against user-confirmed tag replacement. Watch callbacks
    // still invalidate immediately, but their rescan waits until an in-flight mutation ends.
    private val operationMutex = Mutex()
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val eventSequence = AtomicLong(0)
    private val lastReconciledSequence = AtomicLong(-1)
    private val requestedReconcileSequence = AtomicLong(-1)
    private val reconcileWorkerScheduled = AtomicBoolean(false)
    private val generationBase = AtomicLong(1)
    private val eventLock = Any()
    private var pendingEventCount = 0
    private val pendingKinds = linkedSetOf<LocalFolderChangeKind>()
    private var invalidationOpen = false
    private var pendingOverflow = false
    private var pendingObserverInvalid = false
    private var observer: LocalFolderChangeObserver? = null
    private var backgroundReconcile: Job? = null
    private val mutableStatus = MutableStateFlow(LocalFolderWorkbenchStatus())
    val status: StateFlow<LocalFolderWorkbenchStatus> = mutableStatus.asStateFlow()

    init {
        require(candidateLimitPerFile in 1..20) { "candidate limit must be between 1 and 20" }
        require(quietWindowMillis in 1..10_000L) { "watch quiet window is outside the supported bound" }
        require(maximumCoalescingLatencyMillis in quietWindowMillis..60_000L) {
            "maximum coalescing latency must be >= quiet window and bounded"
        }
    }

    /** Register the observer lease first, then scan until one event-free generation is coherent. */
    suspend fun open(): FolderMetadataSuiteOperation<FolderTreeTagPreview> {
        check(opened.compareAndSet(false, true)) { "local folder workbench already opened" }
        check(!closed.get()) { "local folder workbench is closed" }
        updateStatus(
            state = LocalFolderWorkbenchWatchState.STARTING,
            message = "Registering local folder observer before the initial scan…",
        )
        try {
            installObserver()
        } catch (error: Exception) {
            return failHost("Could not establish local folder observer: ${error.message}")
        }
        return reconcileUntilStable("Initial local folder scan")
    }

    /** Current coherent preview for a client projection. Reading it never scans or mutates media. */
    suspend fun currentPreview(): FolderTreeTagPreview? = operationMutex.withLock {
        session.currentPreviewSnapshot()
    }

    /** Explicit user/manual refresh. It never stages or applies tags. */
    suspend fun reconcileNow(reason: String = "Manual local folder reconciliation"): FolderMetadataSuiteOperation<FolderTreeTagPreview> {
        require(reason.isNotBlank()) { "reconciliation reason must not be blank" }
        check(opened.get() && !closed.get()) { "local folder workbench is not open" }
        session.invalidateForFilesystemChange(reason)
        synchronized(eventLock) { invalidationOpen = true }
        return reconcileUntilStable(reason)
    }

    fun approveCandidate(command: ApproveCandidateCommand): FolderMetadataSuiteOperation<ReviewedFolderApproval> =
        session.approveCandidate(command)

    fun approveLocalProposals(command: ApproveLocalProposalsCommand): FolderMetadataSuiteOperation<ReviewedFolderApproval> =
        session.approveLocalProposals(command)

    fun reviewTagBatch(
        approvals: List<ReviewedFolderApproval>,
        recursiveTagOptIn: Boolean = false,
    ): FolderMetadataSuiteOperation<ReviewedFolderTagBatch> =
        session.reviewTagBatch(approvals, recursiveTagOptIn)

    fun reviewDirectPlaylist(
        order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): FolderMetadataSuiteOperation<ReviewedFolderPlaylist> = session.reviewDirectPlaylist(order)

    fun reviewPlaylistBatch(
        recursivePlaylistOptIn: Boolean = false,
        onePlaylistPerAlbum: Boolean = false,
        order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): FolderMetadataSuiteOperation<ReviewedFolderPlaylistBatch> =
        session.reviewPlaylistBatch(recursivePlaylistOptIn, onePlaylistPerAlbum, order)

    /** Playlist writes remain explicit-confirmation derived writes; M3U files are not watched as media. */
    fun materializePlaylist(
        review: ReviewedFolderPlaylist,
        confirmWrite: Boolean,
    ): FolderMetadataSuiteOperation<FolderPlaylistWriteResult> =
        session.materializePlaylist(review, confirmWrite).also(::projectOperation)

    fun materializePlaylistBatch(
        review: ReviewedFolderPlaylistBatch,
        confirmWrite: Boolean,
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderPlaylistBatchWriteResult> =
        session.materializePlaylistBatch(review, confirmWrite) { progress ->
            updateStatus(
                state = mutableStatus.value.state,
                message = "Writing reviewed playlist ${progress.completed}/${progress.total}…",
            )
            onProgress(progress)
        }.also(::projectOperation)

    /**
     * User-confirmed tag execution only. A real write is followed by the observer quiet window
     * and an authoritative rescan, so expected self-events are reconciled instead of suppressed.
     */
    suspend fun executeTagBatch(
        review: ReviewedFolderTagBatch,
        stagingDirectory: File,
        dryRun: Boolean = true,
        confirmWrite: Boolean = false,
        onProgress: (FolderTagBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderTagBatchExecutionResult> {
        val execution = operationMutex.withLock {
            session.executeTagBatch(
                review = review,
                stagingDirectory = stagingDirectory,
                dryRun = dryRun,
                confirmWrite = confirmWrite,
            ) { progress ->
                updateStatus(
                    state = mutableStatus.value.state,
                    message = if (progress.dryRun) {
                        "Checking reviewed tag item ${progress.completed}/${progress.total}…"
                    } else {
                        "Applying reviewed tag item ${progress.completed}/${progress.total}…"
                    },
                )
                onProgress(progress)
            }
        }
        if (dryRun || execution.value == null) {
            projectOperation(execution)
            return execution
        }

        // WatchService delivery is asynchronous. Let expected self-events enter the same normal
        // invalidation/coalescing path, then rescan. A coincident external change is captured by
        // the same barrier and can never be hidden by an "own write" flag.
        delay(quietWindowMillis)
        val reconciliation = reconcileUntilStable("Reconciling confirmed local tag write")
        val indeterminate = execution.value.results.any { result -> result.status == ApplyResultStatus.INDETERMINATE }
        val finalExecution = if (reconciliation.succeeded) {
            execution.copy(
                message = if (indeterminate) {
                    "${execution.message} A fresh snapshot was observed, but an indeterminate byte-replacement outcome still requires recovery before further metadata writes."
                } else {
                    "${execution.message} Watcher reconciliation verified a fresh post-write snapshot."
                },
                reconciliationRequired = indeterminate,
            )
        } else {
            execution.copy(
                message = "${execution.message} ${reconciliation.message}",
                reconciliationRequired = true,
            )
        }
        projectOperation(finalExecution)
        return finalExecution
    }

    /**
     * Guarded user recovery for a previously verified apply result. A rollback conflict never
     * force-overwrites a newer file, and an indeterminate rollback keeps the host stale even if
     * a watcher scan can describe the current directory contents.
     */
    suspend fun rollbackTagResult(
        result: FileApplyResult,
    ): FolderMetadataSuiteOperation<FileApplyResult> {
        val rollback = operationMutex.withLock { session.rollbackTagResult(result) }
        if (rollback.value == null) {
            projectOperation(rollback)
            return rollback
        }
        delay(quietWindowMillis)
        val reconciliation = reconcileUntilStable("Reconciling guarded local tag rollback")
        val indeterminate = rollback.value.status == ApplyResultStatus.INDETERMINATE
        val finalRollback = if (reconciliation.succeeded) {
            rollback.copy(
                message = if (indeterminate) {
                    "${rollback.message} The folder was rescanned, but rollback remains indeterminate and recovery evidence must be preserved."
                } else {
                    "${rollback.message} Watcher reconciliation observed a fresh post-rollback snapshot."
                },
                reconciliationRequired = indeterminate,
            )
        } else {
            rollback.copy(
                message = "${rollback.message} ${reconciliation.message}",
                reconciliationRequired = true,
            )
        }
        projectOperation(finalRollback)
        return finalRollback
    }

    fun schedulePostSyncPlaylistRegeneration(
        key: String,
        review: ReviewedFolderPlaylistBatch,
        nowEpochMillis: Long = clockMillis(),
    ): FolderMetadataSuiteOperation<FolderPlaylistRegenerationService.ScheduledRegeneration> =
        session.schedulePostSyncPlaylistRegeneration(key, review, nowEpochMillis).also(::projectOperation)

    fun flushPostSyncPlaylistRegeneration(
        nowEpochMillis: Long = clockMillis(),
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<List<FolderPlaylistBatchWriteResult>> =
        session.flushPostSyncPlaylistRegeneration(nowEpochMillis) { progress ->
            updateStatus(
                state = mutableStatus.value.state,
                message = "Regenerating reviewed playlist ${progress.completed}/${progress.total}…",
            )
            onProgress(progress)
        }.also(::projectOperation)

    private fun installObserver() {
        val replacement = observerFactory.open(
            capability = capability,
            recursive = recursive,
            quietWindowMillis = quietWindowMillis,
            maximumCoalescingLatencyMillis = maximumCoalescingLatencyMillis,
        )
        try {
            replacement.start(::onObservedEvent, ::onObservedBatch)
        } catch (error: Exception) {
            runCatching { replacement.close() }
            throw error
        }
        // Start the replacement before retiring an older invalid lease. The brief overlap is
        // harmless because events only invalidate/coalesce, while closing first would create a
        // new unobserved window during recovery.
        val previous = observer
        observer = replacement
        runCatching { previous?.close() }
    }

    private fun onObservedEvent(event: LocalFolderChangeEvent) {
        if (closed.get()) return
        val sequence = eventSequence.incrementAndGet()
        val shouldInvalidate = synchronized(eventLock) {
            pendingEventCount = (pendingEventCount + 1).coerceAtMost(MAX_PRESENTED_PENDING_EVENTS)
            pendingKinds += event.kind
            if (event.kind == LocalFolderChangeKind.OVERFLOW) pendingOverflow = true
            if (event.kind == LocalFolderChangeKind.OBSERVER_INVALID) pendingObserverInvalid = true
            if (invalidationOpen) {
                false
            } else {
                invalidationOpen = true
                true
            }
        }
        if (shouldInvalidate) {
            session.invalidateForFilesystemChange("Local folder content changed.")
        }
        val overflow = synchronized(eventLock) { pendingOverflow }
        updateStatus(
            state = if (overflow) LocalFolderWorkbenchWatchState.OVERFLOW_RESCANNING else LocalFolderWorkbenchWatchState.STALE,
            message = "Local folder changed; reviewed approvals are stale and reconciliation is required.",
            eventSequenceHint = sequence,
        )
    }

    private fun onObservedBatch(batch: LocalFolderChangeBatch) {
        if (closed.get()) return
        synchronized(eventLock) {
            if (batch.overflow) pendingOverflow = true
            if (batch.observerInvalid) pendingObserverInvalid = true
        }
        requestedReconcileSequence.accumulateAndGet(eventSequence.get()) { current, requested ->
            maxOf(current, requested)
        }
        scheduleBackgroundReconcile()
    }

    private fun scheduleBackgroundReconcile() {
        if (closed.get() || !reconcileWorkerScheduled.compareAndSet(false, true)) return
        backgroundReconcile = scope.launch {
            var attemptedSequence = -1L
            try {
                while (!closed.get()) {
                    val requestedSequence = requestedReconcileSequence.get()
                    if (requestedSequence <= lastReconciledSequence.get()) break
                    attemptedSequence = requestedSequence
                    val result = reconcileUntilStable(
                        "Reconciling coalesced local folder changes",
                        requestedSequence,
                    )
                    if (!result.succeeded) break
                }
            } finally {
                reconcileWorkerScheduled.set(false)
                val newestRequest = requestedReconcileSequence.get()
                if (!closed.get() &&
                    newestRequest > lastReconciledSequence.get() &&
                    newestRequest > attemptedSequence
                ) {
                    scheduleBackgroundReconcile()
                }
            }
        }
    }

    private suspend fun reconcileUntilStable(
        reason: String,
        requestedSequence: Long? = null,
    ): FolderMetadataSuiteOperation<FolderTreeTagPreview> = operationMutex.withLock {
        if (requestedSequence != null && requestedSequence <= lastReconciledSequence.get() &&
            mutableStatus.value.state == LocalFolderWorkbenchWatchState.LIVE
        ) {
            return@withLock FolderMetadataSuiteOperation(
                value = null,
                message = "The requested change batch was already reconciled.",
            )
        }
        var lastMessage = reason
        repeat(MAX_STABILIZATION_PASSES) {
            if (closed.get()) {
                return@withLock FolderMetadataSuiteOperation(
                    value = null,
                    message = "Local folder workbench closed during reconciliation.",
                    reconciliationRequired = true,
                )
            }

            val resetObserver = synchronized(eventLock) { pendingObserverInvalid }
            if (resetObserver) {
                try {
                    installObserver()
                    synchronized(eventLock) { pendingObserverInvalid = false }
                } catch (error: Exception) {
                    return@withLock failHost("Local folder observer could not be re-registered: ${error.message}")
                }
            }

            val startSequence = eventSequence.get()
            val overflow = synchronized(eventLock) { pendingOverflow }
            val generation = nextGenerationBase()
            updateStatus(
                state = if (overflow) LocalFolderWorkbenchWatchState.OVERFLOW_RESCANNING else LocalFolderWorkbenchWatchState.SCANNING,
                message = if (overflow) {
                    "Observer overflow detected; performing a full local folder rescan…"
                } else {
                    "$reason…"
                },
                scanGeneration = generation.value,
            )

            val result = session.reconcile(
                FolderTreeTagPreviewCommand(
                    directory = capability.rootDirectory,
                    sourceId = capability.sourceId,
                    generation = generation,
                    recursive = recursive,
                    structureConfig = structureConfig,
                    onlineLookupConsent = onlineLookupConsent,
                    candidateLimitPerFile = candidateLimitPerFile,
                ),
            )
            val endSequence = eventSequence.get()
            lastMessage = result.message
            if (!result.succeeded && !result.reconciliationRequired) {
                return@withLock failHost("Local folder reconciliation failed: ${result.message}")
            }
            if (result.succeeded && !result.reconciliationRequired && startSequence == endSequence) {
                val accepted = synchronized(eventLock) {
                    if (eventSequence.get() != endSequence) {
                        false
                    } else {
                        pendingEventCount = 0
                        pendingKinds.clear()
                        pendingOverflow = false
                        pendingObserverInvalid = false
                        invalidationOpen = false
                        true
                    }
                }
                if (accepted) {
                    val preview = result.value!!
                    lastReconciledSequence.set(endSequence)
                    val sessionStatus = session.status()
                    mutableStatus.value = LocalFolderWorkbenchStatus(
                        state = LocalFolderWorkbenchWatchState.LIVE,
                        sessionRevision = sessionStatus.revision,
                        scanGeneration = generation.value,
                        folderCount = preview.folderCount,
                        fileCount = preview.fileCount,
                        pendingEventCount = 0,
                        pendingEventKinds = emptySet(),
                        pendingPlaylistRegenerations = sessionStatus.pendingPlaylistRegenerations,
                        reconciliationRequired = false,
                        lastReconciledAtEpochMillis = clockMillis(),
                        message = "Local folder is live after a coherent ${preview.fileCount}-file reconciliation.",
                    )
                    return@withLock result
                }
            }
            delay(quietWindowMillis)
        }

        updateStatus(
            state = LocalFolderWorkbenchWatchState.STALE,
            message = "Local folder kept changing during reconciliation; review remains stale. Last scan: $lastMessage",
        )
        FolderMetadataSuiteOperation(
            value = null,
            message = "Local folder could not reach a stable snapshot after bounded reconciliation passes.",
            reconciliationRequired = true,
        )
    }

    private fun nextGenerationBase(): SnapshotGeneration = SnapshotGeneration(
        generationBase.getAndAdd(GENERATION_STRIDE),
    )

    private fun projectOperation(operation: FolderMetadataSuiteOperation<*>) {
        val sessionStatus = session.status()
        mutableStatus.update { current ->
            val stale = operation.reconciliationRequired || sessionStatus.reconciliationRequired
            current.copy(
                state = if (stale && current.state == LocalFolderWorkbenchWatchState.LIVE) {
                    LocalFolderWorkbenchWatchState.STALE
                } else {
                    current.state
                },
                sessionRevision = sessionStatus.revision,
                pendingPlaylistRegenerations = sessionStatus.pendingPlaylistRegenerations,
                reconciliationRequired = stale || current.state != LocalFolderWorkbenchWatchState.LIVE,
                message = operation.message,
                error = if (!operation.succeeded && !operation.reconciliationRequired) operation.message else null,
            )
        }
    }

    private fun updateStatus(
        state: LocalFolderWorkbenchWatchState,
        message: String,
        scanGeneration: Long = mutableStatus.value.scanGeneration,
        eventSequenceHint: Long? = null,
    ) {
        val sessionStatus = session.status()
        val pending = synchronized(eventLock) { pendingEventCount to pendingKinds.toSet() }
        mutableStatus.update { current ->
            current.copy(
                state = state,
                sessionRevision = sessionStatus.revision,
                scanGeneration = scanGeneration,
                pendingEventCount = pending.first,
                pendingEventKinds = pending.second,
                pendingPlaylistRegenerations = sessionStatus.pendingPlaylistRegenerations,
                reconciliationRequired = sessionStatus.reconciliationRequired || state != LocalFolderWorkbenchWatchState.LIVE,
                message = if (eventSequenceHint == null) message else "$message (change $eventSequenceHint)",
                error = null,
            )
        }
    }

    private fun <T> failHost(message: String): FolderMetadataSuiteOperation<T> {
        val sessionStatus = session.status()
        mutableStatus.value = mutableStatus.value.copy(
            state = LocalFolderWorkbenchWatchState.FAILED,
            sessionRevision = sessionStatus.revision,
            pendingPlaylistRegenerations = sessionStatus.pendingPlaylistRegenerations,
            reconciliationRequired = true,
            message = message,
            error = message,
        )
        return FolderMetadataSuiteOperation(
            value = null,
            message = message,
            reconciliationRequired = true,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { observer?.close() }
        observer = null
        backgroundReconcile?.cancel()
        scope.cancel()
        val sessionStatus = session.status()
        mutableStatus.value = mutableStatus.value.copy(
            state = LocalFolderWorkbenchWatchState.CLOSED,
            sessionRevision = sessionStatus.revision,
            pendingPlaylistRegenerations = sessionStatus.pendingPlaylistRegenerations,
            reconciliationRequired = true,
            message = "Local metadata workbench is closed.",
        )
    }

    companion object {
        const val DEFAULT_QUIET_WINDOW_MILLIS = 250L
        const val DEFAULT_MAXIMUM_COALESCING_LATENCY_MILLIS = 2_000L
        private const val MAX_STABILIZATION_PASSES = 8
        private const val GENERATION_STRIDE = 1_000_000L
        private const val MAX_PRESENTED_PENDING_EVENTS = 10_000
    }
}
