package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val source = SourceId("demo")
    private val parent = NodeId("folder:root")
    private val nodes = listOf(
        AudioFolder(source, NodeId("folder:mixes"), parent, "Summer Mixes"),
        LibraryFile(source, NodeId("file:notes"), parent, "summer notes.TXT"),
        AudioTrack(source, NodeId("track:one"), parent, "Summer Song.FLAC"),
        LibraryFile(source, NodeId("file:playlist"), parent, "Summer set.M3U8"),
    )

    @Test
    fun `query threshold and case insensitive filename matching are deterministic`() {
        assertTrue(LibrarySearch.matches(nodes, LibrarySearchRequest("su")).isEmpty())
        assertEquals(
            listOf("Summer Mixes", "summer notes.TXT", "Summer set.M3U8", "Summer Song.FLAC"),
            LibrarySearch.matches(nodes, LibrarySearchRequest("SUM")).map { it.name },
        )
    }

    @Test
    fun `generic files overrides specialized file filters without duplicates`() {
        val generic = LibrarySearch.matches(nodes, LibrarySearchRequest("summer", setOf(SearchMatchType.FILES)))
        val combined = LibrarySearch.matches(
            nodes + nodes.last(),
            LibrarySearchRequest("summer", setOf(SearchMatchType.FILES, SearchMatchType.AUDIO_FILES, SearchMatchType.PLAYLIST_FILES)),
        )
        assertEquals(listOf("summer notes.TXT", "Summer set.M3U8", "Summer Song.FLAC"), generic.map { it.name })
        assertEquals(generic.map { it.id }, combined.map { it.id })
    }

    @Test
    fun `directories audio and playlist filters remain independently selectable`() {
        assertEquals(listOf("Summer Mixes"), LibrarySearch.matches(nodes, LibrarySearchRequest("summer", setOf(SearchMatchType.DIRECTORIES))).map { it.name })
        assertEquals(listOf("Summer Song.FLAC"), LibrarySearch.matches(nodes, LibrarySearchRequest("summer", setOf(SearchMatchType.AUDIO_FILES))).map { it.name })
        assertEquals(listOf("Summer set.M3U8"), LibrarySearch.matches(nodes, LibrarySearchRequest("summer", setOf(SearchMatchType.PLAYLIST_FILES))).map { it.name })
    }
}
