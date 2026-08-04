package dev.properpcloud.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.source.pcloud.PCloudAccountRegion
import java.awt.SystemColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(controller: DesktopController) {
    val state by controller.state.collectAsState()
    var accountDialog by remember { mutableStateOf(false) }
    var keyboardHelp by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf(DesktopFocusTarget.LIBRARY) }
    var librarySelection by remember { mutableStateOf(0) }
    var queueSelection by remember { mutableStateOf(0) }
    val dark = SystemColor.window.rgb and 0xff < 128
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val shortcut = resolveDesktopShortcut(event, focusTarget, accountDialog || keyboardHelp)) {
                    DesktopShortcut.PlayPause -> controller.playPause()
                    DesktopShortcut.Next -> controller.next()
                    DesktopShortcut.Previous -> controller.previous()
                    DesktopShortcut.FocusLibrary -> focusTarget = DesktopFocusTarget.LIBRARY
                    DesktopShortcut.FocusQueue -> focusTarget = DesktopFocusTarget.QUEUE
                    DesktopShortcut.ShowHelp -> keyboardHelp = true
                    is DesktopShortcut.SelectLibrary -> {
                        focusTarget = DesktopFocusTarget.LIBRARY
                        librarySelection = moveSelection(librarySelection, shortcut.delta, state.nodes.size)
                    }
                    is DesktopShortcut.OpenLibrary -> {
                        val node = state.nodes.getOrNull(librarySelection) ?: return@onPreviewKeyEvent true
                        when (shortcut.operation) {
                            LibraryKeyboardOperation.OPEN_OR_PLAY -> controller.open(node)
                            LibraryKeyboardOperation.APPEND -> when (node) {
                                is AudioTrack -> controller.enqueue(node)
                                is AudioFolder -> controller.enqueueFolder(node, recursive = true, QueueOperation.APPEND)
                            }
                            LibraryKeyboardOperation.PLAY_REPLACE -> when (node) {
                                is AudioTrack -> controller.play(node)
                                is AudioFolder -> controller.enqueueFolder(node, recursive = false, QueueOperation.REPLACE)
                            }
                            LibraryKeyboardOperation.INSPECT -> controller.inspect(node)
                        }
                    }
                    is DesktopShortcut.SelectQueue -> {
                        focusTarget = DesktopFocusTarget.QUEUE
                        queueSelection = moveSelection(queueSelection, shortcut.delta, state.queue.entries.size)
                    }
                    DesktopShortcut.PlayQueueSelection -> state.queue.entries.getOrNull(queueSelection)?.let {
                        controller.playIndex(queueSelection)
                    }
                    DesktopShortcut.RemoveQueueSelection -> if (state.queue.entries.getOrNull(queueSelection) != null) {
                        controller.removeQueue(queueSelection)
                        queueSelection = moveSelection(queueSelection, 0, state.queue.entries.size - 1)
                    }
                    is DesktopShortcut.MoveQueueSelection -> if (state.queue.entries.getOrNull(queueSelection) != null) {
                        val destination = (queueSelection + shortcut.delta).coerceIn(0, state.queue.entries.lastIndex)
                        controller.moveQueue(queueSelection, shortcut.delta)
                        queueSelection = destination
                    }
                    null -> return@onPreviewKeyEvent false
                }
                true
            },
            topBar = {
                TopAppBar(
                    title = { Text("properpcloud · ${state.sourceName}") },
                    actions = {
                        TextButton(onClick = controller::useDemo) { Text("Demo") }
                        TextButton(onClick = controller::usePCloud) { Text("pCloud") }
                        IconButton(onClick = { accountDialog = true }) {
                            Icon(if (state.connectedToPCloud) Icons.Default.Logout else Icons.Default.Login, "Account")
                        }
                        IconButton(onClick = controller::openDocumentation) { Icon(Icons.Default.Info, "Documentation") }
                    },
                )
            },
            bottomBar = { PlayerBar(state, controller) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) CircularProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    NavigationPane(state, controller, Modifier.width(250.dp).fillMaxHeight())
                    Divider(Modifier.fillMaxHeight().width(1.dp))
                    LibraryPane(
                        state = state,
                        controller = controller,
                        selectedIndex = librarySelection,
                        keyboardFocused = focusTarget == DesktopFocusTarget.LIBRARY,
                        onSelected = { librarySelection = it; focusTarget = DesktopFocusTarget.LIBRARY },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    Divider(Modifier.fillMaxHeight().width(1.dp))
                    QueuePane(
                        state = state,
                        controller = controller,
                        selectedIndex = queueSelection,
                        keyboardFocused = focusTarget == DesktopFocusTarget.QUEUE,
                        onSelected = { queueSelection = it; focusTarget = DesktopFocusTarget.QUEUE },
                        modifier = Modifier.widthIn(min = 320.dp, max = 440.dp).fillMaxHeight(),
                    )
                }
                Surface(tonalElevation = 2.dp) {
                    Text(state.status, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (accountDialog) AccountDialog(state, controller, onDismiss = { accountDialog = false })
        if (keyboardHelp) KeyboardHelpDialog(onDismiss = { keyboardHelp = false })
    }
}

@Composable
private fun KeyboardHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard controls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Ctrl+L · focus library")
                Text("Ctrl+Q · focus queue")
                Text("↑/↓ · select an item")
                Text("Enter · open/play selected item")
                Text("Shift+Enter · append selected library item")
                Text("Ctrl+Enter · replace queue and play")
                Text("Alt+Enter · inspect selected library item")
                Text("Alt+↑/↓ · move selected queue item")
                Text("Delete · remove selected queue item")
                Text("Space · play/pause · Ctrl+←/→ · previous/next")
                Text("All queue operations have non-drag alternatives.", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NavigationPane(state: DesktopUiState, controller: DesktopController, modifier: Modifier) {
    Column(modifier.padding(12.dp)) {
        Text("Folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.breadcrumbs) { index, folder ->
                Surface(
                    tonalElevation = if (index == state.breadcrumbs.lastIndex) 2.dp else 0.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable { controller.navigateTo(folder) },
                ) {
                    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null)
                        Spacer(Modifier.width(8.dp))
                        Text(folder.name, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Inspector", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        LazyColumn {
            state.inspection.forEach { (name, value) ->
                item {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPane(
    state: DesktopUiState,
    controller: DesktopController,
    selectedIndex: Int,
    keyboardFocused: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.currentFolder?.name ?: "Library", style = MaterialTheme.typography.headlineSmall)
                Text("Double-click to open or play; right-click for queue actions", style = MaterialTheme.typography.bodySmall)
                if (keyboardFocused) Text("Keyboard focus · ↑/↓ select · Enter open/play · F1 help", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            state.currentFolder?.let { folder ->
                FilledTonalButton(onClick = { controller.enqueueFolder(folder, recursive = false, QueueOperation.REPLACE) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play folder")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(state.nodes, key = { _, node -> node.sourceId.value + node.id.value }) { index, node ->
                var menu by remember(node.id) { mutableStateOf(false) }
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onSelected(index); controller.inspect(node) },
                        onDoubleClick = { onSelected(index); controller.open(node) },
                        onLongClick = { onSelected(index); menu = true },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (keyboardFocused && index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (node is AudioFolder) Icons.Default.Folder else Icons.Default.LibraryMusic, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(node.name, fontWeight = FontWeight.Medium)
                            if (node is AudioTrack) {
                                Text(listOfNotNull(node.taggedTitle, node.durationMillis?.let(::formatDuration)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.QueueMusic, "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            when (node) {
                                is AudioTrack -> {
                                    DropdownMenuItem({ Text("Play now") }, onClick = { menu = false; controller.play(node) })
                                    DropdownMenuItem({ Text("Play next") }, onClick = { menu = false; controller.enqueue(node, QueueOperation.PLAY_NEXT) })
                                    DropdownMenuItem({ Text("Append") }, onClick = { menu = false; controller.enqueue(node) })
                                }
                                is AudioFolder -> {
                                    DropdownMenuItem({ Text("Open") }, onClick = { menu = false; controller.open(node) })
                                    DropdownMenuItem({ Text("Play direct children") }, onClick = { menu = false; controller.enqueueFolder(node, false, QueueOperation.REPLACE) })
                                    DropdownMenuItem({ Text("Append subtree") }, onClick = { menu = false; controller.enqueueFolder(node, true, QueueOperation.APPEND) })
                                }
                            }
                            DropdownMenuItem({ Text("Inspect") }, onClick = { menu = false; controller.inspect(node) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePane(
    state: DesktopUiState,
    controller: DesktopController,
    selectedIndex: Int,
    keyboardFocused: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AssistChip(onClick = controller::revealContainingFolder, label = { Text("Show folder") })
        }
        if (keyboardFocused) Text("Keyboard focus · ↑/↓ select · Enter play · Alt+↑/↓ move · Delete remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.queue.entries, key = { _, entry -> entry.track.sourceId.value + entry.track.id.value }) { index, entry ->
                Surface(
                    tonalElevation = if (index == state.queue.currentIndex) 3.dp else 0.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable { onSelected(index); controller.playIndex(index) },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (keyboardFocused && index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == state.queue.currentIndex) Icon(Icons.Default.PlayArrow, null)
                        Text(entry.track.name, Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { controller.moveQueue(index, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton(onClick = { controller.moveQueue(index, 1) }, enabled = index < state.queue.entries.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                        IconButton(onClick = { controller.removeQueue(index) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(state: DesktopUiState, controller: DesktopController) {
    val current = state.queue.current?.track
    Surface(shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(260.dp)) {
                Text(current?.taggedTitle ?: current?.filenameStem ?: "Nothing playing", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(current?.name ?: "Choose a track", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = controller::previous) { Icon(Icons.Default.SkipPrevious, "Previous") }
            IconButton(onClick = controller::playPause) { Icon(if (state.playback.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Play or pause") }
            if (state.playback.restartAvailable) {
                IconButton(onClick = controller::restartPlayer) {
                    Icon(Icons.Default.Refresh, "Restart player and resume")
                }
            }
            IconButton(onClick = controller::next) { Icon(Icons.Default.SkipNext, "Next") }
            IconButton(onClick = { controller.seek(-15_000) }) { Icon(Icons.Default.KeyboardArrowLeft, "Back 15 seconds") }
            IconButton(onClick = { controller.seek(30_000) }) { Icon(Icons.Default.KeyboardArrowRight, "Forward 30 seconds") }
            val duration = state.playback.durationMillis ?: current?.durationMillis ?: 0
            Slider(
                value = state.playback.positionMillis.coerceAtMost(duration).toFloat(),
                onValueChange = { controller.seekAbsolute(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = current != null,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("${formatDuration(state.playback.positionMillis)} / ${formatDuration(duration)}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AccountDialog(state: DesktopUiState, controller: DesktopController, onDismiss: () -> Unit) {
    if (state.connectedToPCloud) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("pCloud account") },
            text = { Text("The session token is stored in the desktop Secret Service. Disconnect removes it locally.") },
            confirmButton = { Button(onClick = { controller.disconnectPCloud(); onDismiss() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var revealPassword by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(PCloudAccountRegion.EUROPE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect pCloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Interim direct sign-in. Credentials go once to the selected regional pCloud HTTPS API; only the returned token is stored in Secret Service.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Google, Apple, or Facebook accounts need a regular pCloud password. Create one through pCloud's Forgot password flow before using direct sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Choose where the account was originally created. A generic login failure does not identify which credential or regional selection was wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PCloudAccountRegion.entries.forEach { candidate ->
                        OutlinedButton(
                            onClick = { region = candidate },
                            enabled = !state.busy && region != candidate,
                        ) { Text(candidate.displayName) }
                    }
                }
                OutlinedTextField(
                    email,
                    { email = it },
                    label = { Text("Account email") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealPassword = !revealPassword }) {
                            Icon(
                                if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (revealPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (!state.busy && email.isNotBlank() && password.isNotEmpty()) {
                            val secret = password.toCharArray(); password = ""; revealPassword = false
                            controller.connectPCloud(email, secret, region)
                        }
                    }),
                )
                if (state.busy || state.status.contains("pCloud", ignoreCase = true)) {
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val secret = password.toCharArray(); password = ""; revealPassword = false
                controller.connectPCloud(email, secret, region)
            }, enabled = email.isNotBlank() && password.isNotEmpty() && !state.busy) {
                Text(if (state.busy) "Connecting…" else "Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
