package dev.properpcloud.app.data

import dev.properpcloud.app.security.PCloudSessionStore
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.pcloud.PCloudSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class SourceKind(val id: String) {
    DEMO("demo"),
    PCLOUD("pcloud"),
}

class SourceRegistry(
    demoSource: AudioSource,
    private val tokenVault: PCloudSessionStore,
) {
    private val sources = ConcurrentHashMap<SourceId, AudioSource>()
    private val _current = MutableStateFlow(demoSource)
    private var pCloudSession: PCloudSession? = null
    val current: StateFlow<AudioSource> = _current.asStateFlow()

    init {
        sources[demoSource.id] = demoSource
        tokenVault.read()?.let(::installPCloud)
    }

    fun source(id: SourceId): AudioSource? = sources[id]

    fun select(kind: SourceKind): Boolean {
        val source = sources[SourceId(kind.id)] ?: return false
        _current.value = source
        return true
    }

    fun installPCloud(session: PCloudSession) {
        tokenVault.write(session)
        pCloudSession = session
        val source = PCloudSourceFactory.create(session)
        sources[source.id] = source
        _current.value = source
    }

    fun disconnectPCloudLocally(): PCloudSession? {
        val session = pCloudSession
        pCloudSession = null
        tokenVault.clear()
        sources.remove(SourceId(SourceKind.PCLOUD.id))
        select(SourceKind.DEMO)
        return session
    }

    fun hasPCloudSession(): Boolean = sources.containsKey(SourceId(SourceKind.PCLOUD.id))
}
