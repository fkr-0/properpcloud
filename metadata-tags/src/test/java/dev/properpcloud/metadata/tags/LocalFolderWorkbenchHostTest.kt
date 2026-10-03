package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFolderWorkbenchHostTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun observerLeasePrecedesScanAndBootstrapRacePublishesOnlyAStableGeneration() = runBlocking {
        val album = temporary.newFolder("Bootstrap Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val observers = FakeObserverFactory()
        val emitted = AtomicBoolean(false)
        lateinit var toolkit: RecordingToolkit
        toolkit = RecordingToolkit(
            onInspect = {
                if (emitted.compareAndSet(false, true)) {
                    track.appendText("-changed-during-scan")
                    observers.latest.emit(
                        LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, track.name),
                    )
                }
            },
        )
        val host = host(album, toolkit, observers)

        host.use {
            val opened = it.open()

            assertTrue(observers.openedBeforeFirstInspect)
            assertTrue(opened.succeeded)
            assertFalse(opened.reconciliationRequired)
            assertTrue(emitted.get())
            val live = it.awaitState(LocalFolderWorkbenchWatchState.LIVE)
            assertEquals(1, live.fileCount)
            assertEquals(0, live.pendingEventCount)
            assertFalse(live.reconciliationRequired)
            assertTrue(live.sessionRevision >= 2)
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun externalChangeRevokesApprovalImmediatelyThenCoalescedBatchRescansWithoutTagWrites() = runBlocking {
        val album = temporary.newFolder("Approval Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        val host = host(album, toolkit, observers)

        host.use {
            val preview = it.open().value!!.snapshots.single()
            val row = preview.files.single()
            val approval = it.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = preview,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(
                        TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER,
                    ),
                ),
            ).value!!
            assertTrue(it.reviewTagBatch(listOf(approval)).succeeded)

            track.appendText("-external")
            val change = LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, track.name)
            observers.latest.emitImmediate(change)

            val stale = it.status.value
            assertEquals(LocalFolderWorkbenchWatchState.STALE, stale.state)
            assertTrue(stale.reconciliationRequired)
            assertFalse(it.reviewTagBatch(listOf(approval)).succeeded)
            assertEquals(0, toolkit.stagePatchCalls)

            observers.latest.emitBatch(change)
            val live = it.awaitState(LocalFolderWorkbenchWatchState.LIVE)
            assertFalse(live.reconciliationRequired)
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun overflowCancelsStalePlaylistRegenerationAndForcesFullRescanWithoutMutation() = runBlocking {
        val album = temporary.newFolder("Overflow Album")
        File(album, "01 - One.mp3").writeText("audio")
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        val host = host(album, toolkit, observers)

        host.use {
            it.open()
            val playlistReview = it.reviewPlaylistBatch().value!!
            assertTrue(it.schedulePostSyncPlaylistRegeneration("overflow", playlistReview, nowEpochMillis = 0).succeeded)
            assertEquals(1, it.status.value.pendingPlaylistRegenerations)

            val overflow = LocalFolderChangeEvent(LocalFolderChangeKind.OVERFLOW)
            observers.latest.emitImmediate(overflow)

            val stale = it.status.value
            assertEquals(LocalFolderWorkbenchWatchState.OVERFLOW_RESCANNING, stale.state)
            assertTrue(LocalFolderChangeKind.OVERFLOW in stale.pendingEventKinds)
            assertEquals(0, stale.pendingPlaylistRegenerations)
            assertEquals(0, toolkit.stagePatchCalls)

            observers.latest.emitBatch(overflow)
            val live = it.awaitState(LocalFolderWorkbenchWatchState.LIVE)
            assertFalse(live.reconciliationRequired)
            assertEquals(0, live.pendingPlaylistRegenerations)
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun recursivePlaylistConsentDoesNotEnableRecursiveTagMutationAndPlaylistNeedsConfirmation() = runBlocking {
        val root = temporary.newFolder("Library")
        val album = File(root, "Album").apply { mkdirs() }
        File(album, "01 - One.mp3").writeText("audio")
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        val host = host(root, toolkit, observers, recursive = true)

        host.use {
            val tree = it.open().value!!
            val playlistWithoutConsent = it.reviewPlaylistBatch(recursivePlaylistOptIn = false)
            assertFalse(playlistWithoutConsent.succeeded)
            val playlist = it.reviewPlaylistBatch(recursivePlaylistOptIn = true).value!!
            assertTrue(playlist.plan.recursiveOptInConfirmed)

            val snapshot = tree.snapshots.single { candidate -> candidate.folderPath == album.canonicalFile }
            val row = snapshot.files.single()
            val approval = it.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(
                        TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER,
                    ),
                ),
            ).value!!
            val tagWithoutConsent = it.reviewTagBatch(listOf(approval), recursiveTagOptIn = false)
            assertFalse(tagWithoutConsent.succeeded)
            assertEquals(0, toolkit.stagePatchCalls)

            val rejectedWrite = it.materializePlaylistBatch(playlist, confirmWrite = false)
            assertFalse(rejectedWrite.succeeded)
            assertTrue(root.walkTopDown().none { file -> file.extension.equals("m3u8", ignoreCase = true) })

            val written = it.materializePlaylistBatch(playlist, confirmWrite = true)
            assertTrue(written.succeeded)
            assertTrue(root.walkTopDown().any { file -> file.extension.equals("m3u8", ignoreCase = true) })
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun confirmedTagWriteReconcilesExpectedSelfEventAndWatcherNeverAppliesTagsItself() = runBlocking {
        val album = temporary.newFolder("Self Event Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val observers = FakeObserverFactory()
        val selfEventEmitted = AtomicBoolean(false)
        val toolkit = RecordingToolkit(
            onStagePatch = {
                if (selfEventEmitted.compareAndSet(false, true)) {
                    observers.latest.emit(
                        LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, track.name),
                    )
                }
            },
        )
        val host = host(album, toolkit, observers, quietWindowMillis = 5)

        host.use {
            val snapshot = it.open().value!!.snapshots.single()
            val row = snapshot.files.single()
            val approval = it.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(
                        TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER,
                    ),
                ),
            ).value!!
            val review = it.reviewTagBatch(listOf(approval)).value!!

            val execution = it.executeTagBatch(
                review = review,
                stagingDirectory = File(album, ".scratch"),
                dryRun = false,
                confirmWrite = true,
            )

            assertTrue(execution.succeeded)
            assertFalse(execution.reconciliationRequired)
            assertTrue(selfEventEmitted.get())
            assertEquals(1, toolkit.stagePatchCalls)
            assertEquals("Self Event Album", toolkit.inspect(track).fields[TagField.ALBUM]?.value)
            assertEquals(LocalFolderWorkbenchWatchState.LIVE, it.status.value.state)

            // An external watcher event after the user write still performs only reconciliation.
            track.appendText("-later-external")
            observers.latest.emit(
                LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, track.name),
            )
            it.awaitState(LocalFolderWorkbenchWatchState.LIVE)
            assertEquals(1, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun interruptedCandidateReplacementStaysStaleUntilGuardedRollbackReconciles() = runBlocking {
        val album = temporary.newFolder("Interrupted Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        var replacements = 0
        val host = host(
            root = album,
            toolkit = toolkit,
            observers = observers,
            quietWindowMillis = 5,
            atomicReplaceOperation = { from, to ->
                replacements += 1
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                replacements != 1
            },
        )

        host.use {
            val snapshot = it.open().value!!.snapshots.single()
            val row = snapshot.files.single()
            val approval = it.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(
                        TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER,
                    ),
                ),
            ).value!!
            val review = it.reviewTagBatch(listOf(approval)).value!!

            val interrupted = it.executeTagBatch(
                review = review,
                stagingDirectory = album,
                dryRun = false,
                confirmWrite = true,
            )

            assertTrue(interrupted.succeeded)
            assertTrue(interrupted.reconciliationRequired)
            val result = interrupted.value!!.results.single()
            assertEquals(dev.properpcloud.core.model.ApplyResultStatus.INDETERMINATE, result.status)
            assertNotNull(result.resultSha256)
            assertNotNull(result.rollbackFile)
            assertEquals(LocalFolderWorkbenchWatchState.STALE, it.status.value.state)

            val recovered = it.rollbackTagResult(result)

            assertTrue(recovered.succeeded)
            assertFalse(recovered.reconciliationRequired)
            assertEquals(dev.properpcloud.core.model.ApplyResultStatus.VERIFIED, recovered.value!!.status)
            assertEquals("audio", track.readText())
            assertEquals(LocalFolderWorkbenchWatchState.LIVE, it.status.value.state)
            assertEquals(2, replacements)
        }
    }

    @Test
    fun stalePlaylistPreflightProjectsStaleStateEvenWithoutAWatcherEvent() = runBlocking {
        val album = temporary.newFolder("Preflight Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        val host = host(album, toolkit, observers)

        host.use {
            it.open()
            val review = it.reviewDirectPlaylist().value!!
            track.appendText("-changed-without-fake-event")

            val result = it.materializePlaylist(review, confirmWrite = true)

            assertFalse(result.succeeded)
            assertTrue(result.reconciliationRequired)
            assertEquals(LocalFolderWorkbenchWatchState.STALE, it.status.value.state)
            assertTrue(it.status.value.reconciliationRequired)
            assertTrue(it.status.value.message.contains("changed after preview"))
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun invalidObserverIsReRegisteredBeforeReturningLive() = runBlocking {
        val album = temporary.newFolder("Invalid Observer Album")
        File(album, "01 - One.mp3").writeText("audio")
        val observers = FakeObserverFactory()
        val toolkit = RecordingToolkit()
        val host = host(album, toolkit, observers)

        host.use {
            it.open()
            assertEquals(1, observers.openCount)

            observers.latest.emit(LocalFolderChangeEvent(LocalFolderChangeKind.OBSERVER_INVALID))
            val live = it.awaitState(LocalFolderWorkbenchWatchState.LIVE)

            assertEquals(2, observers.openCount)
            assertFalse(live.reconciliationRequired)
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun jdkWatchServiceReportsAudioChangesButIgnoresDerivedPlaylistWrites() {
        val root = temporary.newFolder("jdk-watch")
        val capability = LocalFolderRootCapability.open(root, SourceId("local"))
        val observer = JdkLocalFolderChangeObserverFactory.open(
            capability = capability,
            recursive = false,
            quietWindowMillis = 20,
            maximumCoalescingLatencyMillis = 200,
        )
        val events = CopyOnWriteArrayList<LocalFolderChangeEvent>()
        val audioSeen = CountDownLatch(1)
        observer.use {
            it.start(
                onEvent = { event ->
                    events += event
                    if (event.relativePath == "track.mp3") audioSeen.countDown()
                },
                onBatch = {},
            )
            File(root, "track.mp3").writeText("audio")
            assertTrue("WatchService did not report the audio file", audioSeen.await(3, TimeUnit.SECONDS))

            File(root, "derived.m3u8").writeText("#EXTM3U\n./track.mp3\n")
            Thread.sleep(250)
            assertTrue(events.any { event -> event.relativePath == "track.mp3" })
            assertTrue(events.none { event -> event.relativePath == "derived.m3u8" })
        }
    }

    private fun host(
        root: File,
        toolkit: RecordingToolkit,
        observers: FakeObserverFactory,
        recursive: Boolean = false,
        quietWindowMillis: Long = 10,
        atomicReplaceOperation: ((File, File) -> Boolean)? = null,
    ): LocalFolderWorkbenchHost {
        val capability = LocalFolderRootCapability.open(root, SourceId("local"))
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val applyService = atomicReplaceOperation?.let { operation ->
            FolderTagApplyService(toolkit, operation)
        } ?: FolderTagApplyService(toolkit)
        val workflow = FolderAutoTagWorkflow(
            scanner = scanner,
            lookup = FolderMetadataLookup { _, _ -> emptyList() },
            applyService = applyService,
        )
        val session = FolderMetadataSuiteSession(workflow)
        observers.onOpened = { observers.openedBeforeFirstInspect = toolkit.inspectCalls == 0 }
        return LocalFolderWorkbenchHost(
            capability = capability,
            session = session,
            recursive = recursive,
            observerFactory = observers,
            quietWindowMillis = quietWindowMillis,
            maximumCoalescingLatencyMillis = quietWindowMillis * 10,
        )
    }

    private suspend fun LocalFolderWorkbenchHost.awaitState(
        target: LocalFolderWorkbenchWatchState,
    ): LocalFolderWorkbenchStatus = withTimeout(3_000) {
        status.first { it.state == target }
    }

    private class FakeObserverFactory : LocalFolderChangeObserverFactory {
        private val observers = mutableListOf<FakeObserver>()
        var openedBeforeFirstInspect: Boolean = false
        var onOpened: () -> Unit = {}
        val latest: FakeObserver get() = observers.last()
        val openCount: Int get() = observers.size

        override fun open(
            capability: LocalFolderRootCapability,
            recursive: Boolean,
            quietWindowMillis: Long,
            maximumCoalescingLatencyMillis: Long,
        ): LocalFolderChangeObserver = FakeObserver().also {
            observers += it
            onOpened()
        }
    }

    private class FakeObserver : LocalFolderChangeObserver {
        private var eventSink: ((LocalFolderChangeEvent) -> Unit)? = null
        private var batchSink: ((LocalFolderChangeBatch) -> Unit)? = null
        private var closed = false

        override fun start(
            onEvent: (LocalFolderChangeEvent) -> Unit,
            onBatch: (LocalFolderChangeBatch) -> Unit,
        ) {
            check(!closed)
            eventSink = onEvent
            batchSink = onBatch
        }

        fun emitImmediate(event: LocalFolderChangeEvent) {
            check(!closed)
            eventSink?.invoke(event) ?: error("fake observer not started")
        }

        fun emitBatch(vararg events: LocalFolderChangeEvent) {
            check(!closed)
            batchSink?.invoke(LocalFolderChangeBatch(events.toList())) ?: error("fake observer not started")
        }

        fun emit(event: LocalFolderChangeEvent) {
            emitImmediate(event)
            emitBatch(event)
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingToolkit(
        private val onInspect: (File) -> Unit = {},
        private val onStagePatch: (File) -> Unit = {},
    ) : AudioTagToolkit {
        var inspectCalls: Int = 0
        var stagePatchCalls: Int = 0

        override fun inspect(file: File): TagSnapshot {
            inspectCalls += 1
            onInspect(file)
            val stem = file.nameWithoutExtension
            val prefix = stem.substringBefore(' ').toIntOrNull()
            val title = stem.substringAfter(" - ", stem)
            val album = file.readText()
                .substringAfter("|album=", "")
                .substringBefore('|')
                .takeIf(String::isNotBlank)
            val fields = linkedMapOf<TagField, MetadataValue>(
                TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
            )
            prefix?.let { fields[TagField.TRACK_NUMBER] = MetadataValue(it.toString(), MetadataProvenance.EMBEDDED) }
            album?.let { fields[TagField.ALBUM] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
            return TagSnapshot(
                format = "ID3",
                fields = fields,
                durationMillis = prefix?.toLong()?.times(1_000L),
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            expectedSourceSha256?.let { check(source.sha256().equals(it, ignoreCase = true)) }
            val staged = File(stagingDirectory, ".properpcloud-stage-${UUID.randomUUID()}-${source.name}")
            source.copyTo(staged)
            (patch.mutations[TagField.ALBUM] as? TagMutation.Set)?.value?.let { album ->
                val base = staged.readText().substringBefore("|album=")
                staged.writeText("$base|album=$album")
            }
            val before = inspect(source)
            val after = inspect(staged)
            onStagePatch(source)
            return StagedTagResult(
                stagedFile = staged,
                sourceSha256 = source.sha256(),
                stagedSha256 = staged.sha256(),
                snapshot = after,
                changedFields = patch.changedFields(before),
            )
        }
    }
}
