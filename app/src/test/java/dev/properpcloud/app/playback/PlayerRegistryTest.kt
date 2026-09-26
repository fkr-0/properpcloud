package dev.properpcloud.app.playback

import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerPlaybackState
import dev.properpcloud.core.model.PlayerTargetId
import dev.properpcloud.core.model.PlayerTargetProvider
import dev.properpcloud.core.model.PlayerTargetSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRegistryTest {
    @Test
    fun `discovery reconnect deduplicates targets and degrades stale targets explicitly`() = runTest {
        val remoteId = PlayerTargetId("fake:kitchen")
        val provider = FakeProvider(
            snapshots = listOf(
                remote(remoteId, PlayerConnectivity.DEGRADED, observedAt = 1),
                remote(remoteId, PlayerConnectivity.CONNECTED, observedAt = 2),
            ),
        )
        val providerSet = ProviderSet(listOf(provider))
        val registry = PlayerRegistry(providerSet::snapshot)
        val local = PlaybackUiState(
            connected = true,
            mediaId = "pcloud:chapter:1",
            title = "Chapter 1",
            isPlaying = true,
        )

        val first = registry.refresh(local)
        assertEquals(2, first.size)
        assertEquals(1, playerCount(first, remoteId))
        assertEquals(PlayerConnectivity.CONNECTED, playerById(first, remoteId).connectivity)

        provider.snapshots = listOf(
            remote(
                remoteId,
                PlayerConnectivity.CONNECTED,
                playbackState = PlayerPlaybackState.PAUSED,
                observedAt = 3,
            ),
        )
        val reconnected = registry.refresh(local)
        assertEquals(2, reconnected.size)
        assertEquals(PlayerPlaybackState.PAUSED, playerById(reconnected, remoteId).playbackState)

        provider.fail = true
        val degraded = registry.refresh(local)
        val stale = playerById(degraded, remoteId)
        assertEquals(PlayerConnectivity.DEGRADED, stale.connectivity)
        assertTrue(stale.stale)
    }

    @Test
    fun `provider removal marks remembered remote target unavailable while preserving local authority`() = runTest {
        val remoteId = PlayerTargetId("fake:bedroom")
        val provider = FakeProvider(
            snapshots = listOf(remote(remoteId, PlayerConnectivity.CONNECTED, observedAt = 1)),
        )
        val providerSet = ProviderSet(listOf(provider))
        val registry = PlayerRegistry(providerSet::snapshot)
        val local = PlaybackUiState(connected = true, title = "Local")

        val discovered = registry.refresh(local)
        assertEquals(PlayerConnectivity.CONNECTED, playerById(discovered, remoteId).connectivity)

        providerSet.providers = emptyList()
        val unavailable = registry.refresh(local)
        val rememberedRemote = playerById(unavailable, remoteId)

        assertEquals(PlayerConnectivity.UNAVAILABLE, rememberedRemote.connectivity)
        assertTrue(rememberedRemote.stale)
        assertEquals(1, playerCount(unavailable, PlayerRegistry.LOCAL_PLAYER_ID))
        assertTrue(playerById(unavailable, PlayerRegistry.LOCAL_PLAYER_ID).controllable)
    }

    @Test
    fun `local media3 authority is always represented exactly once`() {
        val registry = PlayerRegistry(::noProviders)

        val players = registry.observeLocal(
            PlaybackUiState(
                connected = true,
                mediaId = "pcloud:file:1",
                title = "Track",
                isPlaying = false,
            ),
        )

        assertEquals(1, players.size)
        assertEquals(PlayerRegistry.LOCAL_PLAYER_ID, players.single().id)
        assertTrue(players.single().controllable)
        assertTrue(players.single().local)
    }

    private fun noProviders(): List<PlayerTargetProvider> = emptyList()

    private fun playerById(
        players: List<PlayerTargetSnapshot>,
        id: PlayerTargetId,
    ): PlayerTargetSnapshot {
        for (player in players) {
            if (player.id == id) return player
        }
        error("player $id not found")
    }

    private fun playerCount(
        players: List<PlayerTargetSnapshot>,
        id: PlayerTargetId,
    ): Int {
        var count = 0
        for (player in players) {
            if (player.id == id) count += 1
        }
        return count
    }

    private fun remote(
        id: PlayerTargetId,
        connectivity: PlayerConnectivity,
        playbackState: PlayerPlaybackState = PlayerPlaybackState.PLAYING,
        observedAt: Long,
    ) = PlayerTargetSnapshot(
        id = id,
        providerId = "fake",
        providerName = "Fake provider",
        displayName = "Kitchen",
        connectivity = connectivity,
        playbackState = playbackState,
        currentMediaTitle = "Book",
        observedAtEpochMillis = observedAt,
    )

    private class ProviderSet(
        var providers: List<PlayerTargetProvider>,
    ) {
        fun snapshot(): List<PlayerTargetProvider> = providers
    }

    private class FakeProvider(
        var snapshots: List<PlayerTargetSnapshot>,
        var fail: Boolean = false,
    ) : PlayerTargetProvider {
        override val providerId: String = "fake"
        override val providerName: String = "Fake provider"

        override suspend fun discoverPlayers(): List<PlayerTargetSnapshot> {
            if (fail) error("provider offline")
            return snapshots
        }
    }
}
