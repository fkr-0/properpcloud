package dev.properpcloud.core.model

enum class SearchMatchType {
    DIRECTORIES,
    FILES,
    AUDIO_FILES,
    PLAYLIST_FILES,
}

data class LibrarySearchRequest(
    val query: String,
    val matchTypes: Set<SearchMatchType> = SearchMatchType.entries.toSet(),
)

object LibrarySearch {
    const val MIN_QUERY_LENGTH = 3

    fun matches(nodes: Iterable<MediaNode>, request: LibrarySearchRequest): List<MediaNode> {
        val needle = request.query.trim()
        if (needle.length < MIN_QUERY_LENGTH || request.matchTypes.isEmpty()) return emptyList()

        val selected = request.matchTypes
        return nodes.asSequence()
            .filter { it.name.contains(needle, ignoreCase = true) }
            .filter { node ->
                when (node) {
                    is AudioFolder -> SearchMatchType.DIRECTORIES in selected
                    is AudioTrack -> SearchMatchType.FILES in selected || SearchMatchType.AUDIO_FILES in selected
                    is LibraryFile -> SearchMatchType.FILES in selected ||
                        (node.kind == LibraryFileKind.PLAYLIST && SearchMatchType.PLAYLIST_FILES in selected)
                }
            }
            .distinctBy { it.sourceId.value to it.id.value }
            .sortedWith { left, right ->
                val byName = NaturalTextComparator.compare(left.name, right.name)
                if (byName != 0) byName else {
                    compareValuesBy(left, right, { it.sourceId.value }, { it.id.value })
                }
            }
            .toList()
    }
}
