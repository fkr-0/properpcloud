package dev.properpcloud.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryCatalogTest {
    @Test
    fun addsAndBrowsesExactlyOneDirectorySnapshotAtATime() {
        val folder = File("/music/Album")
        val source = SourceId("local")
        val folderId = NodeId("folder:album")
        val snapshot = FolderTagSnapshot(
            generation = SnapshotGeneration(1),
            folderPath = folder,
            sourceId = source,
            folderId = folderId,
            files = listOf(
                file(source, folder, "02 - Second.mp3", "Second", "Artist", "Album", "2"),
                file(source, folder, "01 - First.mp3", "First", "Artist", "Album", "1"),
            ),
            scanTimeEpochMillis = 1,
        )

        val catalog = MusicLibraryCatalog().addDirectory(snapshot)

        assertEquals(listOf("First", "Second"), catalog.browseFolders().single().tracks.map { it.title })
        assertEquals(2, catalog.browseArtists().getValue("Artist").size)
        assertEquals(2, catalog.browseAlbums().getValue("Album").size)
        assertTrue(catalog.folders.containsKey(source to folderId))
    }

    private fun file(
        source: SourceId,
        folder: File,
        filename: String,
        title: String,
        artist: String,
        album: String,
        track: String,
    ) = FileTagProposals(
        identity = LocalFileIdentity(
            source,
            NodeId("file:$filename"),
            File(folder, filename),
            filename,
            ContentEvidence(1, 1),
        ),
        originalSnapshot = TagSnapshot(
            "ID3",
            mapOf(
                TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
                TagField.ARTIST to MetadataValue(artist, MetadataProvenance.EMBEDDED),
                TagField.ALBUM to MetadataValue(album, MetadataProvenance.EMBEDDED),
                TagField.TRACK_NUMBER to MetadataValue(track, MetadataProvenance.EMBEDDED),
            ),
        ),
        fieldProposals = emptyList(),
    )
}
