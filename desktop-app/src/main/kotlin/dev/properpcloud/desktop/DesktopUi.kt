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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.FolderTagReviewTransition
import dev.properpcloud.metadata.tags.FolderTagReviewValue
import dev.properpcloud.metadata.tags.FolderTagReviewValueKind
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchWatchState
import dev.properpcloud.source.pcloud.PCloudAccountRegion
import java.awt.SystemColor

private fun playlistOrderLabel(order: FolderPlaylistOrder): String = when (order) {
    FolderPlaylistOrder.NATURAL_FILENAME -> "natural filename"
    FolderPlaylistOrder.TAG_TRACK_NUMBER -> "disc and track tags"
    FolderPlaylistOrder.TAGGED_TITLE -> "tagged title"
    FolderPlaylistOrder.TITLE_NUMBER -> "title number"
    FolderPlaylistOrder.MODIFICATION_TIME -> "modification time"
}

private fun tagReviewValueLabel(value: FolderTagReviewValue): String = when (value.kind) {
    FolderTagReviewValueKind.EMPTY -> "Empty"
    FolderTagReviewValueKind.PRESENT -> value.value!!
}

private fun tagTransitionLabel(transition: FolderTagReviewTransition): String = when (transition) {
    FolderTagReviewTransition.CHANGED -> "Changed value"
    FolderTagReviewTransition.ADDED_FROM_EMPTY -> "Added from empty"
    FolderTagReviewTransition.REMOVAL_TO_EMPTY -> "Removal to empty — destructive"
    FolderTagReviewTransition.UNCHANGED -> "No byte-level value change"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(controller: DesktopController) {
    val state by controller.state.collectAsState()
    var accountDialog by remember { mutableStateOf(false) }
    var keyboardHelp by remember {
        mutableStateOf(System.getenv("PROPERPCLOUD_SHOW_KEYBOARD_HELP") == "1")
    }
    var focusTarget by remember { mutableStateOf(DesktopFocusTarget.LIBRARY) }
    var librarySelection by remember { mutableStateOf(0) }
    var queueSelection by remember { mutableStateOf(0) }
    val dark = SystemColor.window.rgb and 0xff < 128
    val highContrast = System.getenv("PROPERPCLOUD_HIGH_CONTRAST") == "1" ||
        System.getenv("GTK_THEME").orEmpty().contains("HighContrast", ignoreCase = true)
    MaterialTheme(colorScheme = desktopColorScheme(dark, highContrast)) {
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
                    title = { Text("properpcloud · ${state.sourceName}", Modifier.semantics { heading() }) },
                    actions = {
                        TextButton(onClick = controller::useDemo) { Text("Demo") }
                        TextButton(onClick = controller::usePCloud) { Text("pCloud") }
                        TextButton(onClick = { controller.chooseLocalFolder(recursive = false) }) { Text("Local") }
                        TextButton(onClick = { controller.chooseLocalFolder(recursive = true) }) { Text("Local tree") }
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
                if (state.localWorkbench.active) {
                    Divider()
                    LocalWorkbenchPane(state.localWorkbench, controller)
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
private fun LocalWorkbenchPane(
    state: DesktopLocalWorkbenchUiState,
    controller: DesktopController,
) {
    var selected by remember(state.sessionRevision, state.proposals) {
        mutableStateOf(
            state.proposals.filter(DesktopLocalTagProposal::autoPreselected).fold(emptySet<DesktopLocalTagProposal>()) { accepted, proposal ->
                accepted.filterNot { it.nodeId == proposal.nodeId && it.field == proposal.field }.toSet() + proposal
            },
        )
    }
    var recursiveTagOptIn by remember(state.sessionRevision) { mutableStateOf(false) }
    var recursivePlaylistOptIn by remember(state.sessionRevision) { mutableStateOf(false) }
    var onePlaylistPerAlbum by remember(state.sessionRevision) { mutableStateOf(false) }
    var playlistOrder by remember(state.sessionRevision) { mutableStateOf(FolderPlaylistOrder.TAG_TRACK_NUMBER) }
    var orderMenu by remember { mutableStateOf(false) }
    var confirmTagWrite by remember { mutableStateOf(false) }
    var confirmPlaylistWrite by remember { mutableStateOf(false) }
    var confirmTagRollback by remember { mutableStateOf(false) }
    val live = state.hostState == LocalFolderWorkbenchWatchState.LIVE && !state.recoveryRequired

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).semantics {
                    stateDescription = if (state.recoveryRequired) {
                        "Recovery required before additional metadata writes"
                    } else {
                        "${state.hostState.name.lowercase().replace('_', ' ')} at revision ${state.sessionRevision}"
                    }
                },
            ) {
                Text(
                    "Local metadata workbench",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${state.hostState.name.lowercase().replace('_', '-')} · revision ${state.sessionRevision} · ${state.fileCount} audio file(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.recoveryRequired) {
                    Text(
                        "Recovery required: further tag and playlist writes are disabled until the interrupted outcome is resolved or explicitly abandoned by closing this selected-root session.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(state.message, style = MaterialTheme.typography.labelSmall)
                state.operationLabel?.let { label ->
                    Text(
                        "$label · ${state.operationCompleted}/${state.operationTotal}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = controller::refreshLocalWorkbench) { Text("Reconcile") }
                if (state.rollbackAvailableCount > 0) {
                    OutlinedButton(onClick = { confirmTagRollback = true }) {
                        Text("Rollback latest (${state.rollbackAvailableCount})")
                    }
                }
            }
        }

        if (state.tagOutcomes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Tag apply and recovery results", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.fillMaxWidth().height(92.dp)) {
                itemsIndexed(
                    state.tagOutcomes,
                    key = { index, outcome -> "${outcome.filename}:${outcome.status}:$index" },
                ) { _, outcome ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {
                            contentDescription = buildString {
                                append("Tag result ${outcome.filename}. ${outcome.status.name.lowercase().replace('_', ' ')}. ")
                                append(outcome.message)
                                if (outcome.rollbackAvailable) append(" Guarded rollback available.")
                            }
                            stateDescription = outcome.status.name.lowercase().replace('_', ' ')
                        },
                    ) {
                        Text("${outcome.filename} · ${outcome.status.name.lowercase().replace('_', '-')}", style = MaterialTheme.typography.bodySmall)
                        Text(outcome.message, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (state.proposals.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Review local proposals", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                itemsIndexed(
                    state.proposals,
                    key = { _, proposal -> "${proposal.nodeId.value}:${proposal.field}:${proposal.ruleId}" },
                ) { _, proposal ->
                    val checked = proposal in selected
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            contentDescription = buildString {
                                append("${proposal.filename}, ${proposal.field}. Current ${proposal.currentValue ?: "empty"}. Proposed ${proposal.proposedValue ?: "empty"}. Rule ${proposal.ruleId}.")
                                if (proposal.warnings.isNotEmpty()) append(" Warning: ${proposal.warnings.joinToString("; ")}")
                            }
                            stateDescription = if (checked) "Selected for review" else "Not selected for review"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                selected = if (enabled) {
                                    selected.filterNot { it.nodeId == proposal.nodeId && it.field == proposal.field }.toSet() + proposal
                                } else {
                                    selected - proposal
                                }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${proposal.filename} · ${proposal.field}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Earlier: ${proposal.currentValue?.takeUnless(String::isBlank) ?: "Empty"} · " +
                                    "Later: ${proposal.proposedValue?.takeUnless(String::isBlank) ?: "Empty (proposed removal)"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text("Rule ${proposal.ruleId} · confidence ${proposal.confidence}", style = MaterialTheme.typography.labelSmall)
                            proposal.warnings.forEach { warning ->
                                Text("Warning: $warning", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.recursiveScope) {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Allow recursive tag plan"
                            stateDescription = if (recursiveTagOptIn) "Selected" else "Not selected"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = recursiveTagOptIn, onCheckedChange = { recursiveTagOptIn = it })
                        Text("Allow recursive tag plan", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { controller.reviewLocalTags(selected, state.sessionRevision, recursiveTagOptIn) },
                        enabled = live && selected.isNotEmpty(),
                    ) { Text("Review selected") }
                    OutlinedButton(
                        onClick = controller::dryRunReviewedLocalTags,
                        enabled = live && state.reviewedTagCount > 0,
                    ) { Text("Dry run") }
                    Button(
                        onClick = { confirmTagWrite = true },
                        enabled = live && state.tagDryRunReady,
                    ) { Text("Apply reviewed tags") }
                }
            }
        }

        state.tagReview?.let { review ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Frozen Earlier / Later review",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Revision ${review.revision} · ${review.changedFieldCount} changed field(s)" +
                    if (review.hasDestructiveChanges) " · includes destructive removal" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
                itemsIndexed(review.files, key = { _, file -> file.nodeId.value }) { _, file ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(file.relativePath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            file.fields.forEach { field ->
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 5.dp).semantics(mergeDescendants = true) {
                                        contentDescription = buildString {
                                            append("${file.relativePath}, ${field.field}. ")
                                            append("Earlier ${tagReviewValueLabel(field.earlier)}. ")
                                            append("Later ${tagReviewValueLabel(field.later)}. ")
                                            append("${tagTransitionLabel(field.transition)}. Rule ${field.ruleId}. ")
                                            append("Confidence ${field.confidence}.")
                                            if (field.conflictsWithExistingValue) append(" Conflicts with existing value.")
                                            if (field.warnings.isNotEmpty()) append(" Warning: ${field.warnings.joinToString("; ")}")
                                        }
                                        stateDescription = tagTransitionLabel(field.transition)
                                    },
                                ) {
                                    Text("${field.field} · ${tagTransitionLabel(field.transition)}", style = MaterialTheme.typography.labelSmall)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Earlier", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            Text(tagReviewValueLabel(field.earlier), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text("Later", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (field.transition == FolderTagReviewTransition.REMOVAL_TO_EMPTY) {
                                                    "Empty — will remove value"
                                                } else {
                                                    tagReviewValueLabel(field.later)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    Text("Rule ${field.ruleId} · confidence ${field.confidence}", style = MaterialTheme.typography.labelSmall)
                                    Text(field.explanation, style = MaterialTheme.typography.labelSmall)
                                    if (field.conflictsWithExistingValue) {
                                        Text("Conflict: existing embedded value will change only after confirmation.", style = MaterialTheme.typography.labelSmall)
                                    }
                                    field.warnings.forEach { warning -> Text("Warning: $warning", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { orderMenu = true }, enabled = live) { Text("Playlist: ${playlistOrderLabel(playlistOrder)}") }
                    DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                        FolderPlaylistOrder.values().forEach { order ->
                            DropdownMenuItem(
                                text = { Text(playlistOrderLabel(order)) },
                                onClick = { playlistOrder = order; orderMenu = false },
                            )
                        }
                    }
                }
                if (state.recursiveScope) {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Recursive playlists"
                            stateDescription = if (recursivePlaylistOptIn) "Selected" else "Not selected"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = recursivePlaylistOptIn, onCheckedChange = { recursivePlaylistOptIn = it }, enabled = live)
                        Text("Recursive playlists", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "One playlist per album"
                            stateDescription = if (onePlaylistPerAlbum) "Selected" else "Not selected"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = onePlaylistPerAlbum, onCheckedChange = { onePlaylistPerAlbum = it }, enabled = live)
                        Text("One per album", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        controller.reviewLocalPlaylist(
                            recursivePlaylistOptIn = recursivePlaylistOptIn,
                            onePlaylistPerAlbum = onePlaylistPerAlbum,
                            order = playlistOrder,
                        )
                    },
                    enabled = live,
                ) { Text("Review playlist") }
                Button(
                    onClick = { confirmPlaylistWrite = true },
                    enabled = live && state.playlistReview != null,
                ) { Text("Write reviewed playlist") }
            }
        }
        state.playlistReview?.let { review ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Exact playlist checkpoint · revision ${review.revision} · ${playlistOrderLabel(review.order)}",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            LazyColumn(Modifier.fillMaxWidth().height(160.dp)) {
                itemsIndexed(review.files, key = { _, file -> file.targetRelativePath }) { _, file ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {
                            contentDescription = "Playlist target ${file.targetRelativePath}. Exact final content: ${file.finalLines.joinToString(". ")}"
                        },
                    ) {
                        Text("Target ${file.targetRelativePath}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        file.finalLines.forEach { line -> Text(line, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            Text("Reviewing writes zero playlist bytes; Write reviewed playlist revalidates this exact checkpoint first.", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (confirmTagWrite) {
        AlertDialog(
            onDismissRequest = { confirmTagWrite = false },
            title = { Text("Replace reviewed tag bytes?") },
            text = { Text("The dry run passed for the frozen Earlier / Later review at revision ${state.tagReview?.revision ?: state.sessionRevision}. Only those exact reviewed fields and hashes will be applied; watcher reconciliation runs afterwards.") },
            confirmButton = {
                Button(onClick = { confirmTagWrite = false; controller.applyReviewedLocalTags(confirmWrite = true) }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmTagWrite = false }) { Text("Cancel") } },
        )
    }
    if (confirmTagRollback) {
        AlertDialog(
            onDismissRequest = { confirmTagRollback = false },
            title = { Text("Restore the latest verified original bytes?") },
            text = { Text("Rollback is allowed only while the current file still matches the exact previously verified apply result. A later external edit causes a conflict instead of being overwritten.") },
            confirmButton = {
                Button(onClick = { confirmTagRollback = false; controller.rollbackLatestLocalTag(confirmRollback = true) }) { Text("Rollback") }
            },
            dismissButton = { TextButton(onClick = { confirmTagRollback = false }) { Text("Cancel") } },
        )
    }
    if (confirmPlaylistWrite) {
        AlertDialog(
            onDismissRequest = { confirmPlaylistWrite = false },
            title = { Text("Write reviewed playlist?") },
            text = { Text("Only the exact revision-bound playlist review will be materialized inside the selected local root.") },
            confirmButton = {
                Button(onClick = { confirmPlaylistWrite = false; controller.materializeReviewedLocalPlaylist(confirmWrite = true) }) { Text("Write playlist") }
            },
            dismissButton = { TextButton(onClick = { confirmPlaylistWrite = false }) { Text("Cancel") } },
        )
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
        Text("Folders", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        Text("Inspector", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
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
                Text(state.currentFolder?.name ?: "Library", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
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
                val keyboardSelected = keyboardFocused && index == selectedIndex
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                        .semantics {
                            selected = keyboardSelected
                            stateDescription = if (keyboardSelected) "Keyboard selected" else "Not selected"
                            contentDescription = if (node is AudioFolder) {
                                "Folder ${node.name}"
                            } else {
                                "Track ${node.name}"
                            }
                        }
                        .combinedClickable(
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
                            if (keyboardSelected) {
                                Text("Selected", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
            Text("Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            AssistChip(onClick = controller::revealContainingFolder, label = { Text("Show folder") })
        }
        if (keyboardFocused) Text("Keyboard focus · ↑/↓ select · Enter play · Alt+↑/↓ move · Delete remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.queue.entries, key = { _, entry -> entry.track.sourceId.value + entry.track.id.value }) { index, entry ->
                val keyboardSelected = keyboardFocused && index == selectedIndex
                val currentTrack = index == state.queue.currentIndex
                Surface(
                    tonalElevation = if (currentTrack) 3.dp else 0.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                        .semantics {
                            selected = keyboardSelected
                            stateDescription = when {
                                currentTrack && keyboardSelected -> "Current track and keyboard selected"
                                currentTrack -> "Current track"
                                keyboardSelected -> "Keyboard selected"
                                else -> "Queued track"
                            }
                            contentDescription = "Queued track ${entry.track.name}"
                        }
                        .clickable { onSelected(index); controller.playIndex(index) },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (keyboardFocused && index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentTrack) Icon(Icons.Default.PlayArrow, null)
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(entry.track.name, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            if (currentTrack || keyboardSelected) {
                                Text(
                                    listOfNotNull(
                                        "Current".takeIf { currentTrack },
                                        "Selected".takeIf { keyboardSelected },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
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
        Row(
            Modifier.fillMaxWidth()
                .semantics {
                    contentDescription = current?.let { "Player for ${it.name}" } ?: "Player with no selected track"
                    stateDescription = when {
                        current == null -> "Nothing playing"
                        state.playback.restartAvailable -> "Playback failed; restart available"
                        state.playback.paused -> "Paused"
                        else -> "Playing"
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

private fun desktopColorScheme(dark: Boolean, highContrast: Boolean) = when {
    highContrast -> darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        secondary = Color.Yellow,
        onSecondary = Color.Black,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xff202020),
        onSurfaceVariant = Color.White,
        secondaryContainer = Color(0xff303030),
        onSecondaryContainer = Color.White,
        outline = Color.White,
    )
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
