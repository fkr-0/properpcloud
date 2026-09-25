package dev.properpcloud.app.playback

import androidx.media3.common.Player
import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerPlaybackState
import dev.properpcloud.core.model.PlayerTargetId
import dev.properpcloud.core.model.PlayerTargetMergePolicy
import dev.properpcloud.core.model.PlayerTargetProvider
import dev.properpcloud.core.model.PlayerTargetSnapshot

class PlayerRegistry(
    private val providers: () -> List<PlayerTargetProvider>,
) {
    private var remoteTargets: Map<PlayerTargetId, PlayerTargetSnapshot> = emptyMap()

    fun observeLocal(playback: PlaybackUiState): List<PlayerTargetSnapshot> =
        mergeWithLocal(playback, remoteTargets.values)

    suspend fun refresh(playback: PlaybackUiState): List<PlayerTargetSnapshot> {
        val configuredProviders = providers()
        val configuredProviderIds = configuredProviders.mapTo(mutableSetOf()) { it.providerId }
        val nextRemote = remoteTargets
            .mapValues { (_, target) ->
                if (target.providerId in configuredProviderIds) target
                else target.copy(connectivity = PlayerConnectivity.UNAVAILABLE, stale = true)
            }
            .toMutableMap()

        configuredProviders.forEach { provider ->
            val previous = remoteTargets.values.filter { it.providerId == provider.providerId }
            runCatching { provider.discoverPlayers() }
                .onSuccess { discovered ->
                    val normalized = PlayerTargetMergePolicy.merge(
                        discovered.map { target ->
                            target.copy(
                                providerId = provider.providerId,
                                providerName = provider.providerName,
                                observedAtEpochMillis = target.observedAtEpochMillis.takeIf { it > 0 }
                                    ?: System.currentTimeMillis(),
                                stale = false,
                            )
                        },
                    )
                    val discoveredIds = normalized.mapTo(mutableSetOf()) { it.id }
                    previous.filterNot { it.id in discoveredIds }.forEach { missing ->
                        nextRemote[missing.id] = missing.copy(
                            connectivity = PlayerConnectivity.UNAVAILABLE,
                            stale = true,
                        )
                    }
                    normalized.forEach { nextRemote[it.id] = it }
                }
                .onFailure {
                    previous.forEach { target ->
                        nextRemote[target.id] = target.copy(
                            connectivity = if (target.connectivity == PlayerConnectivity.UNAVAILABLE) {
                                PlayerConnectivity.UNAVAILABLE
                            } else {
                                PlayerConnectivity.DEGRADED
                            },
                            stale = true,
                        )
                    }
                }
        }
        remoteTargets = PlayerTargetMergePolicy.merge(nextRemote.values).associateBy { it.id }
        return mergeWithLocal(playback, remoteTargets.values)
    }

    private fun mergeWithLocal(
        playback: PlaybackUiState,
        remote: Iterable<PlayerTargetSnapshot>,
    ): List<PlayerTargetSnapshot> = PlayerTargetMergePolicy.merge(listOf(localSnapshot(playback)) + remote)

    private fun localSnapshot(playback: PlaybackUiState): PlayerTargetSnapshot =
        PlayerTargetSnapshot(
            id = LOCAL_PLAYER_ID,
            providerId = LOCAL_PROVIDER_ID,
            providerName = "This device",
            displayName = "ProperPCloud local player",
            connectivity = if (playback.connected) PlayerConnectivity.CONNECTED else PlayerConnectivity.DEGRADED,
            playbackState = when {
                playback.error != null -> PlayerPlaybackState.ERROR
                playback.isPlaying -> PlayerPlaybackState.PLAYING
                playback.playbackState == Player.STATE_BUFFERING -> PlayerPlaybackState.BUFFERING
                playback.playbackState == Player.STATE_ENDED -> PlayerPlaybackState.ENDED
                playback.mediaId != null -> PlayerPlaybackState.PAUSED
                else -> PlayerPlaybackState.IDLE
            },
            currentMediaId = playback.mediaId,
            currentMediaTitle = playback.title.takeIf(String::isNotBlank),
            currentMediaSubtitle = playback.subtitle.takeIf(String::isNotBlank),
            controllable = playback.connected,
            local = true,
            stale = !playback.connected,
            observedAtEpochMillis = System.currentTimeMillis(),
        )

    companion object {
        const val LOCAL_PROVIDER_ID = "local"
        val LOCAL_PLAYER_ID = PlayerTargetId("local:media3")
    }
}
