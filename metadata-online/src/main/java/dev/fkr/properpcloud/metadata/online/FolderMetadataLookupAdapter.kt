package dev.fkr.properpcloud.metadata.online

import dev.fkr.properpcloud.core.model.FolderMetadataLookup
import dev.fkr.properpcloud.core.model.FolderMetadataQuery

/** Adapts the generic folder workflow to any configured online provider chain. */
class OnlineFolderMetadataLookup(
    private val provider: OnlineMetadataProvider,
) : FolderMetadataLookup {
    override suspend fun search(query: FolderMetadataQuery, limit: Int) = provider.search(
        MetadataSearchQuery(
            title = query.title,
            artist = query.artist,
            album = query.album,
            isrc = query.isrc,
            durationMillis = query.durationMillis,
        ),
        limit,
    )
}
