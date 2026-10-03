package dev.properpcloud.metadata.online

import dev.properpcloud.core.model.FolderMetadataQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMetadataLookupAdapterTest {
    @Test
    fun forwardsOnlyDisclosedMetadataAndLimit() = runBlocking {
        var received: MetadataSearchQuery? = null
        var receivedLimit = 0
        val adapter = OnlineFolderMetadataLookup(object : OnlineMetadataProvider {
            override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>().also {
                received = query
                receivedLimit = limit
            }
        })

        adapter.search(FolderMetadataQuery(title = "Track", artist = "Artist", durationMillis = 1234), 4)

        assertEquals("Track", received?.title)
        assertEquals("Artist", received?.artist)
        assertEquals(1234L, received?.durationMillis)
        assertEquals(4, receivedLimit)
    }
}
