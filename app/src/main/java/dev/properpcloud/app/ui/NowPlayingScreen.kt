package dev.properpcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.properpcloud.core.model.PlaybackContentMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(state: AppUiState, actions: AppActions) {
    val current = state.queue.current?.track
    val tab = state.audioTabs.active
    val audiobookMode = tab.definition.contentMode == PlaybackContentMode.AUDIOBOOK
    // These UI preferences are saveable so a user-selected interval survives Activity
    // recreation and Android saved-state process restoration. The persisted tab values remain
    // the cold-start defaults and forward-compatible storage authority.
    var audiobookBackSkipMillis by rememberSaveable {
        mutableStateOf(tab.audiobookSkipBackMillis)
    }
    var audiobookForwardSkipMillis by rememberSaveable {
        mutableStateOf(tab.audiobookSkipForwardMillis)
    }
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Now playing") },
            navigationIcon = {
                IconButton(onClick = { actions.selectDestination(state.playerReturnDestination) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to ${state.playerReturnDestination.name.lowercase()}",
                    )
                }
            },
            actions = {
                IconButton(onClick = { actions.selectDestination(AppDestination.QUEUE) }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Open queue")
                }
            },
        )
        if (current == null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BadgerCloudMark(size = 96.dp)
                Spacer(Modifier.height(20.dp))
                Text("Nothing is playing", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Choose a track or play a folder from the library.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { actions.selectDestination(AppDestination.LIBRARY) }) {
                    Text("Browse library")
                }
            }
            return
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .testTag("player-artwork"),
            ) {
                Box(
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    BadgerCloudMark(size = 150.dp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                state.playback.title.ifBlank { current.taggedTitle ?: current.filenameStem },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.playback.subtitle.ifBlank { current.name },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            SeekTimeline(state, actions)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.skipPrevious, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = if (audiobookMode) "Previous chapter or restart chapter" else "Previous",
                    )
                }
                val backSkip = if (audiobookMode) audiobookBackSkipMillis else 30_000L
                IconButton(onClick = { actions.seekBy(-backSkip) }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Default.FastRewind,
                        contentDescription = "Rewind ${backSkip / 1_000} seconds",
                    )
                }
                FilledIconButton(onClick = actions.playPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (state.playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.playback.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                val forwardSkip = if (audiobookMode) audiobookForwardSkipMillis else 30_000L
                IconButton(onClick = { actions.seekBy(forwardSkip) }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = "Forward ${forwardSkip / 1_000} seconds",
                    )
                }
                IconButton(onClick = actions.skipNext, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = if (audiobookMode) "Next chapter" else "Next",
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            PlaybackContextCard(state)
            Spacer(Modifier.height(12.dp))
            PlaybackOptionsCard(
                state = state,
                actions = actions,
                audiobookBackSkipMillis = audiobookBackSkipMillis,
                audiobookForwardSkipMillis = audiobookForwardSkipMillis,
                onAudiobookBackSkipChanged = { audiobookBackSkipMillis = it },
                onAudiobookForwardSkipChanged = { audiobookForwardSkipMillis = it },
            )
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Queue", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${state.queue.currentIndex + 1} of ${state.queue.entries.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { actions.selectDestination(AppDestination.QUEUE) }) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                    state.queue.entries.getOrNull(state.queue.currentIndex + 1)?.track?.let { next ->
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("Up next", style = MaterialTheme.typography.labelLarge)
                        Text(
                            next.taggedTitle ?: next.filenameStem,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { actions.openContainingFolder(current) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Folder")
                }
                OutlinedButton(
                    onClick = { actions.inspect(current) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Metadata")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { actions.openMetadataEditor(current) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.EditNote, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Edit embedded tags")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun trimPlayerFloat(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.')

@Composable
private fun PlaybackContextCard(state: AppUiState) {
    val tab = state.audioTabs.active
    val player = state.players.firstOrNull { it.id == state.activePlayerId }
    val current = state.queue.current?.track
    val audiobookMode = tab.definition.contentMode == PlaybackContentMode.AUDIOBOOK
    val timerText = state.sleepTimerEndsAtEpochMillis?.let { endsAt ->
        val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
        "active · about ${(remaining + 59_999) / 60_000} min remaining"
    } ?: "off"
    val summary = buildString {
        append("Active player: ")
        append(player?.displayName ?: "Local playback controller")
        if (audiobookMode) {
            append(". Audiobook mode. Book: ")
            append(tab.activeAudiobookBookId?.bookNodeId?.value ?: "not identified")
            append(". Chapter: ")
            append(state.playback.title.ifBlank { current?.taggedTitle ?: current?.filenameStem ?: "none" })
            append(". Speed ")
            append(trimPlayerFloat(tab.playbackSpeed))
            append(" times. Resume position ")
            append(formatPlayerDuration(state.playback.positionMillis))
            append(". Sleep timer ")
            append(timerText)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary }.testTag("playback-context"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Playback context", style = MaterialTheme.typography.titleMedium)
            Text(
                "Active player · ${player?.displayName ?: "Local playback controller"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (audiobookMode) {
                Text("Mode · Audiobook", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Book · ${tab.activeAudiobookBookId?.bookNodeId?.value ?: "Not identified"}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Chapter · ${state.playback.title.ifBlank { current?.taggedTitle ?: current?.filenameStem ?: "None" }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Resume · ${formatPlayerDuration(state.playback.positionMillis)} · ${trimPlayerFloat(tab.playbackSpeed)}×",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Chapter end · ${if (tab.stopAtChapterEnd) "stop" else "continue"} · Sleep timer · $timerText",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PlaybackOptionsCard(
    state: AppUiState,
    actions: AppActions,
    audiobookBackSkipMillis: Long,
    audiobookForwardSkipMillis: Long,
    onAudiobookBackSkipChanged: (Long) -> Unit,
    onAudiobookForwardSkipChanged: (Long) -> Unit,
) {
    val tab = state.audioTabs.active
    val audiobookMode = tab.definition.contentMode == PlaybackContentMode.AUDIOBOOK
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var volume by remember(tab.definition.id, tab.volume) { mutableStateOf(tab.volume) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(tab.definition.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Playback settings are stored for this tab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!audiobookMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tab.shuffle,
                            onClick = actions.toggleShuffle,
                            label = { Text("Shuffle") },
                        )
                        FilterChip(
                            selected = tab.repeatMode != dev.properpcloud.core.model.PlayerRepeatMode.OFF,
                            onClick = actions.cycleRepeatMode,
                            label = { Text("Repeat ${tab.repeatMode.name.lowercase()}") },
                        )
                    }
                } else {
                    Text(
                        "Audiobook",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (audiobookMode) {
                Text(
                    "Chapter-aware previous/next · music shuffle and repeat are disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Back skip · ${audiobookBackSkipMillis / 1_000}s", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10_000L, 15_000L, 30_000L, 60_000L).forEach { interval ->
                        FilterChip(
                            selected = audiobookBackSkipMillis == interval,
                            onClick = { onAudiobookBackSkipChanged(interval) },
                            label = { Text("${interval / 1_000}s") },
                        )
                    }
                }
                Text("Forward skip · ${audiobookForwardSkipMillis / 1_000}s", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10_000L, 15_000L, 30_000L, 60_000L).forEach { interval ->
                        FilterChip(
                            selected = audiobookForwardSkipMillis == interval,
                            onClick = { onAudiobookForwardSkipChanged(interval) },
                            label = { Text("${interval / 1_000}s") },
                        )
                    }
                }
            }
            Text("Speed · ${trimPlayerFloat(tab.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(tab.playbackSpeed - speed) < 0.01f,
                        onClick = { actions.setPlaybackSpeed(speed) },
                        label = { Text("${trimPlayerFloat(speed)}×") },
                    )
                }
            }
            Text("Volume · ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { actions.setVolume(volume) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Box {
                OutlinedButton(onClick = { sleepMenuOpen = true }) {
                    Text(if (state.sleepTimerEndsAtEpochMillis == null) "Sleep timer" else "Sleep timer active")
                }
                DropdownMenu(expanded = sleepMenuOpen, onDismissRequest = { sleepMenuOpen = false }) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("Stop after $minutes min") },
                            onClick = {
                                sleepMenuOpen = false
                                actions.setSleepTimer(minutes)
                            },
                        )
                    }
                    if (state.sleepTimerEndsAtEpochMillis != null) {
                        DropdownMenuItem(
                            text = { Text("Cancel timer") },
                            onClick = {
                                sleepMenuOpen = false
                                actions.setSleepTimer(null)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekTimeline(state: AppUiState, actions: AppActions) {
    val current = state.queue.current?.track ?: return
    val duration = state.playback.durationMillis.takeIf { it > 0 } ?: current.durationMillis ?: 0L
    var draggedPosition by remember(current.id) { mutableStateOf<Long?>(null) }
    val position = (draggedPosition ?: state.playback.positionMillis).coerceIn(0, duration.coerceAtLeast(0))

    Slider(
        value = position.toFloat(),
        onValueChange = { draggedPosition = it.toLong() },
        onValueChangeFinished = {
            draggedPosition?.let(actions.seekTo)
            draggedPosition = null
        },
        enabled = duration > 0,
        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
        modifier = Modifier.fillMaxWidth().testTag("player-seek"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatPlayerDuration(position), style = MaterialTheme.typography.labelMedium)
        Text("−${formatPlayerDuration((duration - position).coerceAtLeast(0))}", style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatPlayerDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
