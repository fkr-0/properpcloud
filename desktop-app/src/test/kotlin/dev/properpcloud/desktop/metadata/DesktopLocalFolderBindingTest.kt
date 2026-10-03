package dev.properpcloud.desktop.metadata

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.metadata.tags.ApproveLocalProposalsCommand
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.LocalFolderChangeBatch
import dev.properpcloud.metadata.tags.LocalFolderChangeEvent
import dev.properpcloud.metadata.tags.LocalFolderChangeKind
import dev.properpcloud.metadata.tags.LocalFolderChangeObserver
import dev.properpcloud.metadata.tags.LocalFolderChangeObserverFactory
import dev.properpcloud.metadata.tags.LocalFolderRootCapability
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchWatchState
import dev.properpcloud.metadata.tags.StagedTagResult
import dev.properpcloud.metadata.tags.TagProposalEngine
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopLocalFolderBindingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `selected root binds source scanner and observer to the same opaque identity`() = runTest {
        val root = temporary.newFolder("selected")
        File(root, "01-track.mp3").writeText("audio")
        val toolkit = RecordingToolkit()
        val observers = RecordingObserverFactory()
        val binding = DesktopLocalFolderBinding.createSelected(root, toolkit = toolkit, observerFactory = observers)

        val opened = binding.open()

        assertTrue(opened.succeeded)
        val preview = opened.value!!.snapshots.single()
        val browsedTrack = binding.source.list(binding.source.root.id).single()
        assertEquals(binding.source.id, preview.sourceId)
        assertEquals(browsedTrack.id, preview.files.single().identity.nodeId)
        assertEquals(LocalFolderWorkbenchWatchState.LIVE, binding.status.value.state)
        assertEquals(root.canonicalFile, binding.stagingDirectory)
        assertTrue(observers.latest.started)
        assertFalse(observers.latest.closed)

        binding.close()
        assertTrue(observers.latest.closed)
    }

    @Test
    fun `selected root symlink is rejected before a local capability is created`() {
        val target = temporary.newFolder("selected-target")
        File(target, "01-track.mp3").writeText("audio")
        val link = File(temporary.root, "selected-link")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
        assumeTrue(created)

        val failure = runCatching {
            DesktopLocalFolderBinding.createSelected(
                link,
                toolkit = RecordingToolkit(),
                observerFactory = RecordingObserverFactory(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `watcher invalidation revokes desktop-bound approvals before any tag stage call`() = runTest {
        val root = temporary.newFolder("watch")
        val track = File(root, "01-track.mp3").apply { writeText("audio") }
        val toolkit = RecordingToolkit()
        val observers = RecordingObserverFactory()
        val binding = DesktopLocalFolderBinding.createSelected(root, toolkit = toolkit, observerFactory = observers)

        binding.use {
            val snapshot = it.open().value!!.snapshots.single()
            val row = snapshot.files.single()
            val approval = it.approveLocal(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
                ),
            ).value!!
            assertTrue(it.reviewTags(listOf(approval), recursiveTagOptIn = false).succeeded)

            track.appendText("-external")
            observers.latest.emitImmediate(LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, track.name))

            assertEquals(LocalFolderWorkbenchWatchState.STALE, it.status.value.state)
            assertTrue(it.status.value.reconciliationRequired)
            assertFalse(it.reviewTags(listOf(approval), recursiveTagOptIn = false).succeeded)
            assertEquals(0, toolkit.stagePatchCalls)

            observers.latest.emitImmediate(LocalFolderChangeEvent(LocalFolderChangeKind.OVERFLOW))
            assertEquals(LocalFolderWorkbenchWatchState.OVERFLOW_RESCANNING, it.status.value.state)
            assertTrue(it.status.value.reconciliationRequired)
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun `selected root end to end keeps browse review reconciliation playlist and playback order coherent`() = runTest {
        val root = temporary.newFolder("e2e")
        val second = File(root, "2-track.mp3").apply { writeText("two") }
        File(root, "10-track.mp3").writeText("ten")
        val toolkit = RecordingToolkit()
        val observers = RecordingObserverFactory()
        val binding = DesktopLocalFolderBinding.createSelected(root, toolkit = toolkit, observerFactory = observers)

        binding.use {
            val opened = it.open()
            assertTrue(opened.succeeded)
            val queue = FolderQueueAssembler(it.source).build(it.source.root.id, recursive = false)
            assertEquals(listOf("2-track.mp3", "10-track.mp3"), queue.entries.map { entry -> entry.track.name })
            assertTrue(it.source.resolveStream(queue.entries.first().track.id).url.startsWith("file:"))

            val snapshot = opened.value!!.snapshots.single()
            val row = snapshot.files.first { candidate -> candidate.identity.filename == second.name }
            val approval = it.approveLocal(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
                ),
            ).value!!
            val reviewed = it.reviewTags(listOf(approval), recursiveTagOptIn = false).value!!
            val dryRun = it.executeTags(reviewed, dryRun = true)
            assertTrue(dryRun.succeeded)
            assertTrue(dryRun.value!!.preflight.all { item -> item.ready })
            assertEquals(0, toolkit.stagePatchCalls)

            second.appendText("-external")
            observers.latest.emitImmediate(LocalFolderChangeEvent(LocalFolderChangeKind.MODIFIED, second.name))
            assertEquals(LocalFolderWorkbenchWatchState.STALE, it.status.value.state)
            assertTrue(it.reconcileNow().succeeded)
            assertEquals(LocalFolderWorkbenchWatchState.LIVE, it.status.value.state)

            val playlist = it.reviewDirectPlaylist(FolderPlaylistOrder.NATURAL_FILENAME).value!!
            assertFalse(it.materializePlaylist(playlist, confirmWrite = false).succeeded)
            assertTrue(it.materializePlaylist(playlist, confirmWrite = true).succeeded)
            assertTrue(root.walkTopDown().any { file -> file.extension.equals("m3u8", ignoreCase = true) })
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    @Test
    fun `reselection discovers interrupted replacement blocks writes and permits only guarded rollback`() = runTest {
        val root = temporary.newFolder("recovery-reselection")
        val track = File(root, "track.mp3").apply { writeText("original") }
        val rollback = File(root, ".properpcloud-rollback-binding.mp3").apply { writeText("original") }
        val authority = DesktopLocalTagRecoveryAuthority()
        authority.arm(
            target = track,
            rollbackFile = rollback,
            originalSha256 = sha256(track.readBytes()),
            expectedResultSha256 = sha256("candidate".toByteArray()),
        )
        track.writeText("candidate")

        // A new binding represents a restarted process after the user explicitly reselects root.
        val binding = DesktopLocalFolderBinding.createSelected(
            root,
            toolkit = RecordingToolkit(),
            observerFactory = RecordingObserverFactory(),
        )
        binding.use {
            val opened = it.open()
            assertTrue(opened.succeeded)
            assertTrue(opened.reconciliationRequired)
            assertTrue(it.recoveryState.recoveryRequired)
            val recovered = it.recoveryState.recoverableResults.single()

            val playlist = it.reviewDirectPlaylist(FolderPlaylistOrder.NATURAL_FILENAME).value!!
            val blockedWrite = it.materializePlaylist(playlist, confirmWrite = true)
            assertFalse(blockedWrite.succeeded)
            assertTrue(blockedWrite.reconciliationRequired)
            assertEquals("candidate", track.readText())

            val rolledBack = it.rollbackTag(recovered)
            assertTrue(rolledBack.succeeded)
            assertEquals(ApplyResultStatus.VERIFIED, rolledBack.value!!.status)
            assertFalse(it.recoveryState.recoveryRequired)
            assertEquals("original", track.readText())
        }
    }

    @Test
    fun `recursive playlist consent is independent and materialization still needs confirmation`() = runTest {
        val root = temporary.newFolder("tree")
        val album = File(root, "Album").apply { mkdirs() }
        File(album, "01-track.mp3").writeText("audio")
        val toolkit = RecordingToolkit()
        val binding = DesktopLocalFolderBinding.createSelected(
            root,
            recursive = true,
            toolkit = toolkit,
            observerFactory = RecordingObserverFactory(),
        )

        binding.use {
            val tree = it.open().value!!
            assertFalse(
                it.reviewPlaylistBatch(
                    recursivePlaylistOptIn = false,
                    onePlaylistPerAlbum = false,
                    order = FolderPlaylistOrder.TAG_TRACK_NUMBER,
                ).succeeded,
            )
            val playlist = it.reviewPlaylistBatch(
                recursivePlaylistOptIn = true,
                onePlaylistPerAlbum = false,
                order = FolderPlaylistOrder.TAG_TRACK_NUMBER,
            ).value!!
            assertTrue(playlist.plan.recursiveOptInConfirmed)

            val albumSnapshot = tree.snapshots.single { snapshot -> snapshot.folderPath == album.canonicalFile }
            val row = albumSnapshot.files.single()
            val approval = it.approveLocal(
                ApproveLocalProposalsCommand(
                    snapshot = albumSnapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
                ),
            ).value!!
            assertFalse(it.reviewTags(listOf(approval), recursiveTagOptIn = false).succeeded)

            assertFalse(it.materializePlaylistBatch(playlist, confirmWrite = false).succeeded)
            assertTrue(root.walkTopDown().none { file -> file.extension.equals("m3u8", ignoreCase = true) })
            val progress = mutableListOf<Pair<Int, Int>>()
            assertTrue(
                it.materializePlaylistBatch(playlist, confirmWrite = true) { update ->
                    progress += update.completed to update.total
                }.succeeded,
            )
            assertEquals(listOf(1 to 1), progress)
            assertTrue(root.walkTopDown().any { file -> file.extension.equals("m3u8", ignoreCase = true) })
            assertEquals(0, toolkit.stagePatchCalls)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class RecordingToolkit : AudioTagToolkit {
        var stagePatchCalls = 0

        override fun inspect(file: File): TagSnapshot = TagSnapshot(
            format = "ID3",
            fields = mapOf(TagField.TITLE to MetadataValue(file.nameWithoutExtension, MetadataProvenance.EMBEDDED)),
        )

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            error("tag staging must not run in this binding test")
        }
    }

    private class RecordingObserverFactory : LocalFolderChangeObserverFactory {
        lateinit var latest: RecordingObserver

        override fun open(
            capability: LocalFolderRootCapability,
            recursive: Boolean,
            quietWindowMillis: Long,
            maximumCoalescingLatencyMillis: Long,
        ): LocalFolderChangeObserver = RecordingObserver().also { latest = it }
    }

    private class RecordingObserver : LocalFolderChangeObserver {
        var started = false
        var closed = false
        private var onEvent: ((LocalFolderChangeEvent) -> Unit)? = null
        private var onBatch: ((LocalFolderChangeBatch) -> Unit)? = null

        override fun start(
            onEvent: (LocalFolderChangeEvent) -> Unit,
            onBatch: (LocalFolderChangeBatch) -> Unit,
        ) {
            started = true
            this.onEvent = onEvent
            this.onBatch = onBatch
        }

        fun emitImmediate(event: LocalFolderChangeEvent) {
            onEvent?.invoke(event)
        }

        override fun close() {
            closed = true
        }
    }
}
