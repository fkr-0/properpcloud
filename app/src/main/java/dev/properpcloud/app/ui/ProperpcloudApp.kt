package dev.properpcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.properpcloud.app.BuildConfig
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.TrackSortKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProperpcloudApp(
    state: AppUiState,
    actions: AppActions,
    onAuthorizePCloud: (String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.consumeMessage()
        }
    }

    ProperpcloudTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                if (expanded) {
                    AppNavigationRail(state.destination, actions.selectDestination)
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        Column {
                            if (
                                state.queue.current != null &&
                                state.destination != AppDestination.PLAYER &&
                                state.destination != AppDestination.METADATA
                            ) {
                                MiniPlayer(state, actions)
                            }
                            if (!expanded) {
                                AppNavigationBar(state.destination, actions.selectDestination)
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        when (state.destination) {
                            AppDestination.LIBRARY -> LibraryScreen(state, actions, expanded)
                            AppDestination.PLAYER -> NowPlayingScreen(state, actions)
                            AppDestination.QUEUE -> QueueScreen(state, actions)
                            AppDestination.METADATA -> MetadataEditorScreen(state, actions)
                            AppDestination.SETTINGS -> SettingsScreen(state, actions, onAuthorizePCloud)
                        }
                    }
                }
            }
        }
        state.inspection?.let { inspection ->
            MetadataInspectionSheet(
                name = state.inspectedNodeName ?: "Metadata",
                fields = inspection.fields,
                onDismiss = actions.closeInspection,
            )
        }
    }
}

@Composable
private fun MetadataSelectionBar(state: AppUiState, actions: AppActions) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("metadata-selection-bar"),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.EditNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("${state.metadataSelection.size} selected for tags", Modifier.weight(1f))
            TextButton(onClick = actions.clearMetadataSelection) { Text("Clear") }
            Button(onClick = actions.openBatchMetadataEditor) { Text("Edit batch") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(state: AppUiState, actions: AppActions, expanded: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgerCloudMark(size = 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("properpcloud", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.sourceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                SortMenu(state.sortKey, actions.setSort)
                IconButton(onClick = actions.refresh, enabled = !state.refreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh folder")
                }
            },
        )
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        SourceBanner(state, actions)
        if (state.queueBuilding) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Building queue…", Modifier.weight(1f))
                    TextButton(onClick = actions.cancelQueueBuild) { Text("Cancel") }
                }
            }
        }
        Breadcrumbs(state, actions)
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                FolderContent(state, actions, Modifier.weight(1.45f))
                VerticalQueuePreview(state, actions, Modifier.weight(0.8f))
            }
        } else {
            FolderContent(state, actions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SourceBanner(state: AppUiState, actions: AppActions) {
    val isDemo = state.sourceKind == SourceKind.DEMO
    Surface(
        color = if (isDemo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isDemo) Icons.Default.CloudOff else Icons.Default.Cloud,
                contentDescription = null,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (isDemo) "Playable local demo. Connect pCloud in Settings when ready."
                else "Connected through pCloud OAuth; passwords are never collected.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isDemo && state.pCloudConnected) {
                TextButton(onClick = { actions.selectSource(SourceKind.PCLOUD) }) { Text("Use pCloud") }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(state: AppUiState, actions: AppActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.breadcrumbs.forEachIndexed { index, folder ->
            AssistChip(
                onClick = { actions.navigateBreadcrumb(index) },
                label = { Text(folder.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun FolderContent(state: AppUiState, actions: AppActions, modifier: Modifier) {
    Box(modifier) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("library-loading"))
            state.errorMessage != null -> ErrorState(state.errorMessage, actions.refresh, Modifier.align(Alignment.Center))
            state.nodes.isEmpty() -> EmptyFolderState(state.currentFolder, actions, Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize().testTag("library-list")) {
                if (state.metadataSelection.isNotEmpty()) {
                    item { MetadataSelectionBar(state, actions) }
                }
                item {
                    FolderQuickActions(state.currentFolder, actions)
                }
                items(state.nodes, key = { it.sourceId.value + ":" + it.id.value }) { node ->
                    MediaNodeRow(node, state, actions)
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderQuickActions(folder: AudioFolder?, actions: AppActions) {
    if (folder == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { actions.enqueueFolder(folder, QueueOperation.REPLACE, false) }) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Play folder")
        }
        OutlinedButton(onClick = { actions.enqueueFolder(folder, QueueOperation.APPEND, true) }) {
            Icon(Icons.Default.SubdirectoryArrowRight, null)
            Spacer(Modifier.width(6.dp))
            Text("Append subtree")
        }
    }
}

@Composable
private fun MediaNodeRow(node: MediaNode, state: AppUiState, actions: AppActions) {
    var menuOpen by remember(node.id) { mutableStateOf(false) }
    val isFolder = node is AudioFolder
    val selectedForMetadata = node is AudioTrack && state.metadataSelection.any {
        it.sourceId == node.sourceId && it.id == node.id
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when (node) {
                    is AudioFolder -> actions.openFolder(node)
                    is AudioTrack -> actions.playTrack(node)
                }
            }
            .testTag("node-${node.id.value}"),
        headlineContent = {
            Text(
                if (node is AudioTrack) node.taggedTitle ?: node.filenameStem else node.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (node is AudioTrack) {
                Text(
                    buildString {
                        if (node.taggedTitle != null) append(node.name).append(" · ")
                        node.durationMillis?.let { append(formatDuration(it)) }
                    }.trim().trimEnd('·').trim(),
                    maxLines = 2,
                )
            } else {
                Text("Folder · stable source identity")
            }
        },
        leadingContent = {
            Icon(
                when {
                    selectedForMetadata -> Icons.Default.CheckCircle
                    isFolder -> Icons.Default.Folder
                    else -> Icons.Default.AudioFile
                },
                contentDescription = if (isFolder) "Folder" else "Audio file",
                tint = when {
                    selectedForMetadata -> MaterialTheme.colorScheme.secondary
                    isFolder -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${node.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (node is AudioFolder) {
                        DropdownMenuItem(
                            text = { Text("Play folder") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueFolder(node, QueueOperation.REPLACE, false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play subtree") },
                            leadingIcon = { Icon(Icons.Default.SubdirectoryArrowRight, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueFolder(node, QueueOperation.REPLACE, true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Append subtree") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueFolder(node, QueueOperation.APPEND, true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play subtree next") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueFolder(node, QueueOperation.PLAY_NEXT, true)
                            },
                        )
                    } else if (node is AudioTrack) {
                        DropdownMenuItem(
                            text = { Text("Edit tags") },
                            leadingIcon = { Icon(Icons.Default.EditNote, null) },
                            onClick = {
                                menuOpen = false
                                actions.openMetadataEditor(node)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (selectedForMetadata) "Remove from tag batch" else "Add to tag batch") },
                            leadingIcon = { Icon(if (selectedForMetadata) Icons.Default.Delete else Icons.Default.CheckCircle, null) },
                            onClick = {
                                menuOpen = false
                                actions.toggleMetadataSelection(node)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueTrack(node, QueueOperation.PLAY_NEXT)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Append") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = {
                                menuOpen = false
                                actions.enqueueTrack(node, QueueOperation.APPEND)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open containing folder") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = {
                                menuOpen = false
                                actions.openContainingFolder(node)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Inspect metadata") },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = {
                            menuOpen = false
                            actions.inspect(node)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun VerticalQueuePreview(state: AppUiState, actions: AppActions, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Text(
            "Queue · ${state.queue.entries.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (state.queue.entries.isEmpty()) {
            Text("Play a track or folder to build the queue.", Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(state.queue.entries.take(12).withIndex().toList(), key = { it.value.track.id.value }) { indexed ->
                    QueueRow(indexed.index, indexed.value.track, state, actions, compact = true)
                }
                if (state.queue.entries.size > 12) {
                    item {
                        TextButton(onClick = { actions.selectDestination(AppDestination.QUEUE) }) {
                            Text("Open full queue")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueScreen(state: AppUiState, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Queue") },
            actions = {
                if (state.queue.entries.isNotEmpty()) {
                    IconButton(onClick = actions.clearQueue) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear queue")
                    }
                }
            },
        )
        NowPlayingCard(state, actions)
        state.queueBuildReport?.takeIf { it.isPartial }?.let { report ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Partial queue: ${report.omissions.size} folder(s) could not be read.",
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (state.queue.entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("The queue is empty", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { actions.selectDestination(AppDestination.LIBRARY) }) { Text("Browse folders") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("queue-list")) {
                items(state.queue.entries.withIndex().toList(), key = { it.value.track.id.value }) { indexed ->
                    QueueRow(indexed.index, indexed.value.track, state, actions, compact = false)
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(state: AppUiState, actions: AppActions) {
    val current = state.queue.current?.track ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgerCloudMark(size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.playback.title.ifBlank { current.taggedTitle ?: current.filenameStem }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        current.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    TextButton(onClick = { actions.openContainingFolder(current) }) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open containing folder")
                    }
                }
            }
            val duration = state.playback.durationMillis.takeIf { it > 0 } ?: current.durationMillis ?: 0
            val progress = if (duration > 0) state.playback.positionMillis.toFloat() / duration else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(state.playback.positionMillis), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.skipPrevious) { Icon(Icons.Default.SkipPrevious, "Previous") }
                IconButton(onClick = { actions.seekBy(-15_000) }) { Icon(Icons.Default.FastRewind, "Rewind 15 seconds") }
                FilledIconButton(onClick = actions.playPause, Modifier.size(58.dp)) {
                    Icon(if (state.playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playback.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = { actions.seekBy(30_000) }) { Icon(Icons.Default.FastForward, "Forward 30 seconds") }
                IconButton(onClick = actions.skipNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun QueueRow(index: Int, track: AudioTrack, state: AppUiState, actions: AppActions, compact: Boolean) {
    val current = index == state.queue.currentIndex
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.selectQueueItem(index) }
            .semantics { contentDescription = "Queue item ${index + 1} of ${state.queue.entries.size}: ${track.name}" }
            .testTag("queue-item-$index"),
        headlineContent = { Text(track.taggedTitle ?: track.filenameStem, maxLines = if (compact) 1 else 2) },
        supportingContent = { Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (current) Icon(Icons.Default.CheckCircle, "Currently playing", tint = MaterialTheme.colorScheme.primary)
            else Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
        },
        trailingContent = {
            if (!compact) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Queue actions for ${track.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            enabled = index > 0,
                            onClick = {
                                menuOpen = false
                                actions.moveQueueItem(index, index - 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                            enabled = index < state.queue.entries.lastIndex,
                            onClick = {
                                menuOpen = false
                                actions.moveQueueItem(index, index + 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open containing folder") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = {
                                menuOpen = false
                                actions.openContainingFolder(track)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit tags") },
                            leadingIcon = { Icon(Icons.Default.EditNote, null) },
                            onClick = {
                                menuOpen = false
                                actions.openMetadataEditor(track)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Inspect metadata") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = {
                                menuOpen = false
                                actions.inspect(track)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                menuOpen = false
                                actions.removeQueueItem(index)
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(state: AppUiState, actions: AppActions, onAuthorizePCloud: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings-screen")) {
        item { TopAppBar(title = { Text("Settings") }) }
        item {
            SettingsSection("Source") {
                Text("The demo source is always available and never uses the network.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.selectSource(SourceKind.DEMO) }) {
                        Icon(Icons.Default.LibraryMusic, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Use demo")
                    }
                    if (state.pCloudConnected) {
                        Button(onClick = { actions.selectSource(SourceKind.PCLOUD) }) {
                            Icon(Icons.Default.Cloud, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Use pCloud")
                        }
                    }
                }
            }
        }
        item {
            SettingsSection("pCloud OAuth") {
                Text("Create an application in the pCloud developer console, then paste its client ID. Authorization happens on pCloud's surface; properpcloud never asks for your password.")
                OutlinedTextField(
                    value = state.clientId,
                    onValueChange = actions.updateClientId,
                    label = { Text("pCloud client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("client-id"),
                )
                if (state.pCloudConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Connected; token encrypted with Android Keystore.", Modifier.weight(1f))
                        TextButton(onClick = actions.disconnectPCloud) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null)
                            Text("Disconnect")
                        }
                    }
                } else {
                    Button(
                        onClick = { onAuthorizePCloud(state.clientId.trim()) },
                        enabled = state.clientId.isNotBlank(),
                        modifier = Modifier.testTag("connect-pcloud"),
                    ) {
                        Icon(Icons.Default.Cloud, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Connect pCloud")
                    }
                }
            }
        }
        item {
            SettingsSection("Metadata tools") {
                Bullet("Edit common embedded fields with visible originals and provenance")
                Bullet("Stage one file or a reviewed batch without modifying the source")
                Bullet("Review MusicBrainz candidates field by field before applying them")
                Bullet("Verify exact pCloud downloads with SHA-256 and pre/post revision checks")
                Text(
                    "Cloud replacement remains disabled because the current pCloud SDK has no atomic expected-revision overwrite operation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection("Privacy and resilience") {
                Bullet("No mandatory properpcloud backend or analytics")
                Bullet("OAuth token encrypted locally and excluded from backup")
                Bullet("Signed media links are resolved just in time and never persisted")
                Bullet("Empty or cancelled scans preserve the previous queue")
            }
        }
        item {
            SettingsSection("About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgerCloudMark(size = 54.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("properpcloud ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text("Folder-first cloud audio, badger-approved.")
                    }
                }
                Text("Original code: MIT License")
                Text("AndroidX, Kotlin, coroutines, Media3, and pCloud SDK: Apache License 2.0")
                Text("jaudiotagger metadata adapter: LGPL 2.1 or later")
                Text("Full notices are bundled in the APK and repository.")
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
    HorizontalDivider()
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", Modifier.width(20.dp), color = MaterialTheme.colorScheme.secondary)
        Text(text, Modifier.weight(1f))
    }
}

@Composable
private fun MiniPlayer(state: AppUiState, actions: AppActions) {
    val current = state.queue.current?.track ?: return
    val duration = state.playback.durationMillis.takeIf { it > 0 } ?: current.durationMillis ?: 0L
    val progress = if (duration > 0) state.playback.positionMillis.toFloat() / duration else 0f
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clickable { actions.selectDestination(AppDestination.PLAYER) }.testTag("mini-player"),
    ) {
        Column {
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgerCloudMark(size = 38.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.playback.title.ifBlank { current.taggedTitle ?: current.filenameStem }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = actions.playPause) {
                    Icon(if (state.playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playback.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = actions.skipNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataInspectionSheet(
    name: String,
    fields: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val groups = fields.entries.groupBy { metadataGroup(it.key) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Provider identity and file facts. Secret tokens and signed links are excluded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { (group, entries) ->
                item {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(entries) { (key, value) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(key, style = MaterialTheme.typography.labelMedium)
                        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun metadataGroup(key: String): String = when {
    key.contains("id", ignoreCase = true) || key.contains("hash", ignoreCase = true) -> "Identity"
    key.contains("created", ignoreCase = true) || key.contains("modified", ignoreCase = true) -> "Timeline"
    key.contains("size", ignoreCase = true) || key.contains("content", ignoreCase = true) -> "Media file"
    key.startsWith("can") || key.startsWith("is") -> "Access"
    else -> "Provider"
}

@Composable
private fun AppNavigationBar(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination.destination,
                onClick = { onSelect(destination.destination) },
                icon = { Icon(destination.icon, destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationRail {
        Spacer(Modifier.height(16.dp))
        BadgerCloudMark(size = 44.dp)
        Spacer(Modifier.height(18.dp))
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination.destination,
                onClick = { onSelect(destination.destination) },
                icon = { Icon(destination.icon, destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun SortMenu(selected: TrackSortKey, onSelect: (TrackSortKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${sortLabel(selected)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(sortLabel(key)) },
                    trailingIcon = { if (selected == key) Icon(Icons.Default.CheckCircle, null) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Folder unavailable", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = retry) { Text("Retry") }
    }
}

@Composable
private fun EmptyFolderState(folder: AudioFolder?, actions: AppActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Folder, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text("No playable audio directly in this folder", style = MaterialTheme.typography.titleMedium)
        folder?.let {
            TextButton(onClick = { actions.enqueueFolder(it, QueueOperation.REPLACE, true) }) {
                Text("Play subtree")
            }
        }
    }
}

private data class DestinationItem(val destination: AppDestination, val label: String, val icon: ImageVector)

private val destinations = listOf(
    DestinationItem(AppDestination.LIBRARY, "Library", Icons.Default.LibraryMusic),
    DestinationItem(AppDestination.QUEUE, "Queue", Icons.AutoMirrored.Filled.QueueMusic),
    DestinationItem(AppDestination.SETTINGS, "Settings", Icons.Default.Settings),
)

private fun sortLabel(key: TrackSortKey): String = when (key) {
    TrackSortKey.NATURAL_FILENAME -> "Natural filename"
    TrackSortKey.DISC_THEN_TRACK -> "Disc and track"
    TrackSortKey.TAGGED_TITLE -> "Tagged title"
    TrackSortKey.MODIFIED_TIME -> "Modified time"
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

data class AppActions(
    val selectDestination: (AppDestination) -> Unit,
    val openFolder: (AudioFolder) -> Unit,
    val navigateBreadcrumb: (Int) -> Unit,
    val refresh: () -> Unit,
    val setSort: (TrackSortKey) -> Unit,
    val playTrack: (AudioTrack) -> Unit,
    val enqueueTrack: (AudioTrack, QueueOperation) -> Unit,
    val enqueueFolder: (AudioFolder, QueueOperation, Boolean) -> Unit,
    val cancelQueueBuild: () -> Unit,
    val selectQueueItem: (Int) -> Unit,
    val removeQueueItem: (Int) -> Unit,
    val moveQueueItem: (Int, Int) -> Unit,
    val clearQueue: () -> Unit,
    val openContainingFolder: (AudioTrack) -> Unit,
    val inspect: (MediaNode) -> Unit,
    val closeInspection: () -> Unit,
    val openMetadataEditor: (AudioTrack) -> Unit,
    val toggleMetadataSelection: (AudioTrack) -> Unit,
    val clearMetadataSelection: () -> Unit,
    val openBatchMetadataEditor: () -> Unit,
    val closeMetadataEditor: () -> Unit,
    val updateMetadataField: (dev.properpcloud.core.model.TagField, String) -> Unit,
    val resetMetadataField: (dev.properpcloud.core.model.TagField) -> Unit,
    val searchMetadata: () -> Unit,
    val selectMetadataCandidate: (String?) -> Unit,
    val toggleMetadataCandidateField: (dev.properpcloud.core.model.TagField) -> Unit,
    val applyMetadataCandidate: () -> Unit,
    val stageMetadata: () -> Unit,
    val updateBatchField: (dev.properpcloud.core.model.TagField, dev.properpcloud.app.metadata.BatchFieldDraft) -> Unit,
    val updateBatchSequence: (Boolean, String, Boolean) -> Unit,
    val searchBatchMetadata: () -> Unit,
    val selectBatchCandidate: (AudioTrack, String?) -> Unit,
    val toggleBatchCandidateField: (AudioTrack, dev.properpcloud.core.model.TagField) -> Unit,
    val stageBatchMetadata: () -> Unit,
    val shareMetadataArtifact: () -> Unit,
    val updateClientId: (String) -> Unit,
    val selectSource: (SourceKind) -> Unit,
    val disconnectPCloud: () -> Unit,
    val consumeMessage: () -> Unit,
    val playPause: () -> Unit,
    val skipNext: () -> Unit,
    val skipPrevious: () -> Unit,
    val seekBy: (Long) -> Unit,
    val seekTo: (Long) -> Unit,
)
