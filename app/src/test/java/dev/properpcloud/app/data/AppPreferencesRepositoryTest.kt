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
}
