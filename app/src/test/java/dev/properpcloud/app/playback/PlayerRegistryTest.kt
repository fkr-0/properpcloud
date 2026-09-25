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
        val registry = PlayerRegistry { listOf(provider) }
        val local = PlaybackUiState(
            connected = true,
            mediaId = "pcloud:chapter:1",
            title = "Chapter 1",
            isPlaying = true,
        )

        val first = registry.refresh(local)
        assertEquals(2, first.size)
        assertEquals(1, first.count { it.id == remoteId })
        assertEquals(PlayerConnectivity.CONNECTED, first.first { it.id == remoteId }.connectivity)

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
        assertEquals(PlayerPlaybackState.PAUSED, reconnected.first { it.id == remoteId }.playbackState)

        provider.fail = true
        val degraded = registry.refresh(local)
        val stale = degraded.first { it.id == remoteId }
        assertEquals(PlayerConnectivity.DEGRADED, stale.connectivity)
        assertTrue(stale.stale)
    }

    @Test
    fun `local media3 authority is always represented exactly once`() {
        val registry = PlayerRegistry { emptyList() }

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
