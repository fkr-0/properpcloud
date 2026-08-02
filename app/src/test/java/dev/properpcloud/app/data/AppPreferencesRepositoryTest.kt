package dev.properpcloud.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppPreferencesRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = AppPreferencesRepository(context)

    @After
    fun cleanUp() {
        context.filesDir.resolve("datastore/properpcloud.preferences_pb").delete()
    }

    @Test
    fun queueReferencesRoundTripWithoutPersistingStreamCapabilities() = runTest {
        val track = AudioTrack(
            sourceId = SourceId("demo"),
            id = NodeId("demo:track:42"),
            parentId = NodeId("demo:folder:book"),
            name = "01 - Test.wav",
        )
        repository.saveQueue(
            PlaybackQueue(
                generation = 3,
                entries = listOf(QueueEntry(track, track.parentId)),
                currentIndex = 0,
            ),
        )

        val restored = repository.loadQueue()
        assertEquals(0, restored.currentIndex)
        assertEquals(SourceId("demo"), restored.entries.single().sourceId)
        assertEquals(NodeId("demo:track:42"), restored.entries.single().nodeId)
        assertEquals(NodeId("demo:folder:book"), restored.entries.single().originFolderId)
    }

    @Test
    fun progressRoundTripsWithStableIdentityAndTiming() = runTest {
        val progress = PlaybackProgress(
            sourceId = SourceId("demo"),
            nodeId = NodeId("demo:track:42"),
            positionMillis = 42_500,
            durationMillis = 120_000,
            playbackSpeed = 1.25f,
            observedAtEpochMillis = 123_456_789,
        )
        repository.saveProgress(progress)

        val restored = repository.loadProgress(progress.sourceId, progress.nodeId)
        assertNotNull(restored)
        assertEquals(progress, restored)
    }

    @Test
    fun frozenQueueFixtureDecodesAndReencodesExactly() {
        val fixtureDirectory = fixtureDirectory()
        val json = fixtureDirectory.resolve("queue.json").readText().trim()
        val currentIndex = fixtureDirectory.resolve("queue-index.txt").readText().trim().toInt()

        val stored = AppPersistenceCodec.decodeQueue(json, currentIndex)
        assertEquals(2, stored.entries.size)
        assertEquals(SourceId("demo"), stored.entries[0].sourceId)
        assertEquals(SourceId("pcloud"), stored.entries[1].sourceId)

        val tracks = stored.entries.map { reference ->
            QueueEntry(
                AudioTrack(
                    sourceId = reference.sourceId,
                    id = reference.nodeId,
                    parentId = reference.originFolderId,
                    name = reference.nodeId.value,
                ),
                reference.originFolderId,
            )
        }
        val encoded = AppPersistenceCodec.encodeQueue(
            PlaybackQueue(generation = 1, entries = tracks, currentIndex = currentIndex),
        )
        assertEquals(json, encoded.json)
        assertEquals(currentIndex, encoded.currentIndex)
    }

    @Test
    fun frozenProgressFixtureDecodesAndReencodesExactly() {
        val json = fixtureDirectory().resolve("progress.json").readText().trim()
        val first = AppPersistenceCodec.decodeProgress(
            json,
            SourceId("demo"),
            NodeId("demo:track:42"),
        )
        val second = AppPersistenceCodec.decodeProgress(
            json,
            SourceId("pcloud"),
            NodeId("file:99"),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(42_500L, first?.positionMillis)
        assertEquals(true, second?.completed)
        val reproduced = AppPersistenceCodec.upsertProgress(
            AppPersistenceCodec.upsertProgress("{}", requireNotNull(first)),
            requireNotNull(second),
        )
        assertEquals(json, reproduced)
    }

    private fun fixtureDirectory(): File =
        File(requireNotNull(System.getProperty("properpcloud.projectRoot")), "spec/fixtures/0.1.5")
}
