package dev.properpcloud.core.model

@JvmInline
value class AudioTabId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "invalid audio tab id" }
    }
}

enum class AudioTabColor {
    BLUE,
    GREEN,
    ORANGE,
    PURPLE,
    RED,
    TEAL,
}

enum class PlayerRepeatMode {
    OFF,
    ONE,
    ALL,
}

data class AudioTabDefinition(
    val id: AudioTabId,
    val name: String,
    val rootPath: String,
    val icon: String? = null,
    val color: AudioTabColor? = null,
) {
    init {
        require(name.isNotBlank()) { "audio tab name must not be blank" }
        require(rootPath == normalizeAudioRootPath(rootPath)) { "audio tab root path must be normalized" }
        require(icon == null || icon.length <= 8) { "audio tab icon is too long" }
    }
}

data class AudioTabSession(
    val definition: AudioTabDefinition,
    val queue: PlaybackQueue = PlaybackQueue(),
    val currentFolderId: NodeId? = null,
    val playbackPositionMillis: Long = 0,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: PlayerRepeatMode = PlayerRepeatMode.OFF,
) {
    init {
        require(playbackPositionMillis >= 0) { "tab playback position must not be negative" }
        require(playbackSpeed in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED) { "tab playback speed out of range" }
        require(volume in 0f..1f) { "tab volume out of range" }
    }
}

data class NamedAudioPlaylist(
    val name: String,
    val entries: List<QueueEntry>,
) {
    init {
        require(name.isNotBlank()) { "playlist name must not be blank" }
        require(name.length <= 120) { "playlist name is too long" }
    }
}

data class AudioTabCollection(
    val tabs: List<AudioTabSession>,
    val activeTabId: AudioTabId,
    val playlists: List<NamedAudioPlaylist> = emptyList(),
) {
    init {
        require(tabs.isNotEmpty()) { "at least one audio tab is required" }
        require(tabs.map { it.definition.id }.distinct().size == tabs.size) { "audio tab ids must be unique" }
        require(tabs.any { it.definition.id == activeTabId }) { "active audio tab must exist" }
        require(playlists.map { it.name.lowercase() }.distinct().size == playlists.size) { "playlist names must be unique" }
    }

    val active: AudioTabSession
        get() = tabs.first { it.definition.id == activeTabId }
}

object AudioTabDefaults {
    val definitions: List<AudioTabDefinition> = listOf(
        AudioTabDefinition(AudioTabId("audiobooks"), "Hörbücher", "hb", "🎧", AudioTabColor.BLUE),
        AudioTabDefinition(AudioTabId("music"), "Musik", "musik", "♫", AudioTabColor.GREEN),
        AudioTabDefinition(AudioTabId("dj"), "DJ/Auflege", "dj", "◉", AudioTabColor.ORANGE),
        AudioTabDefinition(AudioTabId("sci-fi"), "Sci-Fi", "hb/Sci-Fi", "✦", AudioTabColor.PURPLE),
        AudioTabDefinition(AudioTabId("crime"), "Krimi", "hb/Krimi", "◆", AudioTabColor.RED),
        AudioTabDefinition(AudioTabId("fantasy"), "Fantasy", "hb/Fantasy", "✧", AudioTabColor.TEAL),
    )

    fun collection(): AudioTabCollection = AudioTabCollection(
        tabs = definitions.map(::AudioTabSession),
        activeTabId = definitions.first().id,
    )
}

object AudioTabReducer {
    fun switch(
        collection: AudioTabCollection,
        nextTabId: AudioTabId,
        currentPositionMillis: Long,
    ): AudioTabCollection {
        require(collection.tabs.any { it.definition.id == nextTabId }) { "audio tab does not exist" }
        if (collection.activeTabId == nextTabId) return collection
        return collection.copy(
            tabs = collection.tabs.map { tab ->
                if (tab.definition.id == collection.activeTabId) {
                    tab.copy(playbackPositionMillis = currentPositionMillis.coerceAtLeast(0))
                } else {
                    tab
                }
            },
            activeTabId = nextTabId,
        )
    }

    fun updateActive(
        collection: AudioTabCollection,
        transform: (AudioTabSession) -> AudioTabSession,
    ): AudioTabCollection = collection.copy(
        tabs = collection.tabs.map { tab ->
            if (tab.definition.id == collection.activeTabId) transform(tab) else tab
        },
    )

    fun add(
        collection: AudioTabCollection,
        definition: AudioTabDefinition,
    ): AudioTabCollection {
        require(collection.tabs.none { it.definition.id == definition.id }) { "audio tab id already exists" }
        return collection.copy(tabs = collection.tabs + AudioTabSession(definition))
    }

    fun remove(collection: AudioTabCollection, tabId: AudioTabId): AudioTabCollection {
        require(collection.tabs.size > 1) { "cannot remove the last audio tab" }
        val index = collection.tabs.indexOfFirst { it.definition.id == tabId }
        require(index >= 0) { "audio tab does not exist" }
        val remaining = collection.tabs.filterNot { it.definition.id == tabId }
        val active = if (collection.activeTabId == tabId) {
            remaining[index.coerceAtMost(remaining.lastIndex)].definition.id
        } else {
            collection.activeTabId
        }
        return collection.copy(tabs = remaining, activeTabId = active)
    }

    fun rename(collection: AudioTabCollection, tabId: AudioTabId, name: String): AudioTabCollection =
        updateDefinition(collection, tabId) { it.copy(name = name.trim()) }

    fun updateRoot(collection: AudioTabCollection, tabId: AudioTabId, rootPath: String): AudioTabCollection =
        updateDefinition(collection, tabId) { it.copy(rootPath = normalizeAudioRootPath(rootPath)) }

    fun savePlaylist(collection: AudioTabCollection, name: String): AudioTabCollection {
        val normalized = name.trim()
        val playlist = NamedAudioPlaylist(normalized, collection.active.queue.entries)
        return collection.copy(
            playlists = collection.playlists.filterNot { it.name.equals(normalized, ignoreCase = true) } + playlist,
        )
    }

    fun loadPlaylist(collection: AudioTabCollection, name: String): AudioTabCollection {
        val playlist = collection.playlists.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: error("playlist does not exist")
        return updateActive(collection) { tab ->
            tab.copy(
                queue = PlaybackQueue(
                    generation = tab.queue.generation + 1,
                    entries = playlist.entries,
                    currentIndex = if (playlist.entries.isEmpty()) -1 else 0,
                ),
                playbackPositionMillis = 0,
            )
        }
    }

    private fun updateDefinition(
        collection: AudioTabCollection,
        tabId: AudioTabId,
        transform: (AudioTabDefinition) -> AudioTabDefinition,
    ): AudioTabCollection {
        require(collection.tabs.any { it.definition.id == tabId }) { "audio tab does not exist" }
        return collection.copy(
            tabs = collection.tabs.map { tab ->
                if (tab.definition.id == tabId) tab.copy(definition = transform(tab.definition)) else tab
            },
        )
    }
}

suspend fun AudioSource.resolveFolderPath(relativePath: String): AudioFolder {
    val normalized = normalizeAudioRootPath(relativePath)
    if (normalized.isEmpty()) return root
    var current = root
    for (segment in normalized.split('/')) {
        val folders = list(current.id).filterIsInstance<AudioFolder>()
        current = folders.firstOrNull { it.name == segment }
            ?: folders.singleOrNull { it.name.equals(segment, ignoreCase = true) }
            ?: error("folder '$segment' is unavailable below ${current.name}")
    }
    return current
}

fun normalizeAudioRootPath(path: String): String {
    val normalized = path.trim().replace('\\', '/').trim('/')
    if (normalized.isEmpty()) return ""
    val segments = normalized.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) { "invalid audio tab root path" }
    return segments.joinToString("/")
}

const val MIN_PLAYBACK_SPEED = 0.5f
const val MAX_PLAYBACK_SPEED = 3f
