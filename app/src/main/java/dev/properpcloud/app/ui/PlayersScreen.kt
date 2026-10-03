package dev.properpcloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerTargetSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(state: AppUiState, actions: AppActions) {
    Column(Modifier.fillMaxSize().testTag("players-screen")) {
        CenterAlignedTopAppBar(title = { Text("Players") })
        if (state.players.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No players discovered", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The local player will appear once the playback service is available. Remote targets appear when a connected provider reports them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("players-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Known players",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    "Each stable provider target is shown once. Stale or unreachable targets remain visible with an explicit degraded state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.players, key = { it.id.value }) { player ->
                PlayerTargetCard(
                    player = player,
                    active = state.activePlayerId == player.id,
                    playbackIsPlaying = state.playback.isPlaying,
                    actions = actions,
                )
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun PlayerTargetCard(
    player: PlayerTargetSnapshot,
    active: Boolean,
    playbackIsPlaying: Boolean,
    actions: AppActions,
) {
    val connectivity = when (player.connectivity) {
        PlayerConnectivity.CONNECTED -> "Connected"
        PlayerConnectivity.DEGRADED -> "Degraded"
        PlayerConnectivity.UNAVAILABLE -> "Unavailable"
    }
    val accessibility = buildString {
        append(player.displayName)
        append(", provider ")
        append(player.providerName)
        append(", ")
        append(connectivity)
        append(", playback ")
        append(player.playbackState.name.lowercase())
        player.currentMediaTitle?.let {
            append(", current media ")
            append(it)
        }
        if (active) append(", active player")
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-target-${player.id.value}")
            .semantics { contentDescription = accessibility },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(player.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${player.providerName} • $connectivity${if (player.stale) " • stale" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (player.connectivity == PlayerConnectivity.CONNECTED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (active) {
                    Text(
                        "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "State: ${player.playbackState.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.bodyMedium,
            )
            player.currentMediaTitle?.let { title ->
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                player.currentMediaSubtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (player.local && player.controllable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = actions.skipPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous on ${player.displayName}")
                    }
                    IconButton(onClick = actions.playPause) {
                        Icon(
                            if (playbackIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackIsPlaying) {
                                "Pause ${player.displayName}"
                            } else {
                                "Play ${player.displayName}"
                            },
                        )
                    }
                    IconButton(onClick = actions.skipNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next on ${player.displayName}")
                    }
                }
            } else {
                Text(
                    if (player.connectivity == PlayerConnectivity.UNAVAILABLE) {
                        "Controls unavailable while this target is offline."
                    } else {
                        "Observe-only target; this provider has not exposed a controller authority."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
