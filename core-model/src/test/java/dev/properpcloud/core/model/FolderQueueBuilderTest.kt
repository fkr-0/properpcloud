package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderQueueBuilderTest {
    private val source = SourceId("test")
    private val parent = NodeId("folder:1")

    @Test
    fun `natural filename sorting does not put track 10 before track 2`() {
        val tracks = listOf(
            track("10 - ending.mp3"),
            track("2 - middle.mp3"),
            track("01 - opening.mp3"),
        )

        val names = FolderQueueBuilder.tracksOnly(
            tracks,
            TrackSortPolicy(keys = listOf(TrackSortKey.NATURAL_FILENAME)),
        ).map(AudioTrack::name)

        assertEquals(
            listOf("01 - opening.mp3", "2 - middle.mp3", "10 - ending.mp3"),
            names,
        )
    }

    @Test
    fun `disc and track metadata wins before filename fallback`() {
        val tracks = listOf(
            track("a.mp3", disc = 2, number = 1),
            track("z.mp3", disc = 1, number = 2),
            track("m.mp3", disc = 1, number = 1),
        )

        assertEquals(
            listOf("m.mp3", "z.mp3", "a.mp3"),
            FolderQueueBuilder.tracksOnly(tracks).map(AudioTrack::name),
        )
    }

    private fun track(name: String, disc: Int? = null, number: Int? = null) = AudioTrack(
        sourceId = source,
        id = NodeId("track:$name"),
        parentId = parent,
        name = name,
        discNumber = disc,
        trackNumber = number,
    )
}
