package dev.properpcloud.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownloadDone
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.properpcloud.app.metadata.BatchFieldDraft
import dev.properpcloud.app.metadata.MetadataDraftPlanner
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.TagField
import dev.properpcloud.metadata.tags.FolderPlaylistOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEditorScreen(state: AppUiState, actions: AppActions) {
    Column(Modifier.fillMaxSize().testTag("metadata-editor")) {
        CenterAlignedTopAppBar(
            title = { Text("Tag studio") },
            navigationIcon = {
                IconButton(onClick = actions.closeMetadataEditor) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close tag studio")
                }
            },
        )
        when (val editor = state.metadataEditor) {
            null -> MetadataFailure("Tag studio", "No metadata session is active.", actions.closeMetadataEditor)
            is MetadataEditorUiState.Loading -> MetadataLoading(editor)
            is MetadataEditorUiState.Failure -> MetadataFailure(editor.title, editor.message, actions.closeMetadataEditor)
            is MetadataEditorUiState.Single -> SingleMetadataEditor(editor, actions)
            is MetadataEditorUiState.Batch -> BatchMetadataEditor(editor, actions)
        }
    }
}

private fun playlistOrderLabel(order: FolderPlaylistOrder): String = when (order) {
    FolderPlaylistOrder.NATURAL_FILENAME -> "Natural filename"
    FolderPlaylistOrder.TAG_TRACK_NUMBER -> "Disc and track tags"
    FolderPlaylistOrder.TAGGED_TITLE -> "Tagged title"
    FolderPlaylistOrder.MODIFICATION_TIME -> "Modification time"
}

@Composable
private fun MetadataLoading(editor: MetadataEditorUiState.Loading) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text(editor.title, style = MaterialTheme.typography.titleMedium)
        if (editor.total > 1) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { editor.completed.toFloat() / editor.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${editor.completed} of ${editor.total}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MetadataFailure(title: String, message: String, close: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.EditNote, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = close) { Text("Close") }
    }
}

@Composable
private fun SingleMetadataEditor(editor: MetadataEditorUiState.Single, actions: AppActions) {
    val patch = MetadataDraftPlanner.patch(editor.original, editor.draft)
    val changed = patch.changedFields(editor.original)
    var reviewOpen by remember(editor) { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("metadata-editor-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MetadataSourceCard(
                title = editor.track.taggedTitle ?: editor.track.filenameStem,
                filename = editor.track.name,
                format = editor.original.format,
                revision = editor.sourceRevision,
                hash = editor.sourceHash,
            )
        }
        item {
            SafetyNotice(
                "Editing always produces a separate verified file. The source and any pCloud object remain unchanged.",
            )
        }
        item { SectionTitle("Embedded fields", "${changed.size} changed") }
        items(MetadataDraftPlanner.editableFields, key = TagField::name) { field ->
            MetadataFieldEditor(
                field = field,
                value = editor.draft[field].orEmpty(),
                original = editor.original.fields[field]?.value,
                provenance = editor.original.fields[field]?.provenance?.name,
                changed = field in changed,
                enabled = editor.phase != MetadataPhase.STAGING,
                onValueChange = { actions.updateMetadataField(field, it) },
                onReset = { actions.resetMetadataField(field) },
            )
        }
        item {
            SectionTitle("Online proposals", "MusicBrainz · review before apply")
            Text(
                "The search sends title, artist, album, ISRC, and duration. It never uploads audio bytes.",
                Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions.searchMetadata,
                enabled = editor.phase != MetadataPhase.SEARCHING && editor.phase != MetadataPhase.STAGING,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp).testTag("metadata-search"),
            ) {
                if (editor.phase == MetadataPhase.SEARCHING) CircularProgressIndicator(Modifier.width(20.dp))
                else Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (editor.candidates.isEmpty()) "Find matches" else "Search again")
            }
        }
        items(editor.candidates, key = MetadataCandidate::id) { candidate ->
            CandidateCard(
                candidate = candidate,
                selected = editor.selectedCandidateId == candidate.id,
                acceptedFields = editor.acceptedCandidateFields,
                onSelect = { actions.selectMetadataCandidate(candidate.id) },
                onToggleField = actions.toggleMetadataCandidateField,
                onApply = actions.applyMetadataCandidate,
            )
        }
        editor.status?.let { status -> item { StatusCard(status) } }
        editor.artifact?.let { artifact ->
            item { ArtifactCard(artifact, actions.shareMetadataArtifact) }
        }
        item {
            Button(
                onClick = { reviewOpen = true },
                enabled = changed.isNotEmpty() && editor.phase != MetadataPhase.STAGING,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("review-metadata"),
            ) {
                if (editor.phase == MetadataPhase.STAGING) CircularProgressIndicator(Modifier.width(20.dp))
                else Icon(Icons.Default.EditNote, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Review ${changed.size} change(s)")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (reviewOpen) {
        SingleMetadataReviewDialog(
            editor = editor,
            changed = changed,
            onDismiss = { reviewOpen = false },
            onConfirm = {
                reviewOpen = false
                actions.stageMetadata()
            },
        )
    }
}

@Composable
private fun BatchMetadataEditor(editor: MetadataEditorUiState.Batch, actions: AppActions) {
    var reviewOpen by remember(editor) { mutableStateOf(false) }
    val hasPlannedChanges = editor.sequenceTracks ||
        editor.commonFields.values.any { it.enabled } ||
        editor.items.any { it.acceptedCandidateFields.isNotEmpty() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("metadata-editor-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SafetyNotice(
                "${editor.items.size} exact source copies are prepared privately. Batch output is a verified file or ZIP; cloud files are not overwritten.",
            )
        }
        item { SectionTitle("Common fields", "Enable only fields to change") }
        items(MetadataDraftPlanner.commonBatchFields, key = TagField::name) { field ->
            val edit = editor.commonFields[field] ?: BatchFieldDraft()
            BatchFieldEditor(field, edit, editor.phase != MetadataPhase.STAGING) {
                actions.updateBatchField(field, it)
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editor.sequenceTracks,
                            onCheckedChange = {
                                actions.updateBatchSequence(it, editor.sequenceStart, editor.includeTrackTotal)
                            },
                        )
                        Text("Sequence track numbers in selection order", Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = editor.sequenceStart,
                        onValueChange = {
                            actions.updateBatchSequence(editor.sequenceTracks, it, editor.includeTrackTotal)
                        },
                        enabled = editor.sequenceTracks,
                        label = { Text("Start at") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editor.includeTrackTotal,
                            enabled = editor.sequenceTracks,
                            onCheckedChange = {
                                actions.updateBatchSequence(editor.sequenceTracks, editor.sequenceStart, it)
                            },
                        )
                        Text("Write track total")
                    }
                }
            }
        }
        item {
            SectionTitle("Playlist export", "Derived file inside the verified ZIP")
            Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("batch-playlist-options")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = editor.includePlaylist,
                            enabled = editor.phase != MetadataPhase.STAGING,
                            onCheckedChange = { actions.updateBatchPlaylist(it, editor.playlistOrder) },
                            modifier = Modifier.testTag("include-batch-playlist"),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Include relative UTF-8 playlist")
                            Text(
                                "The playlist is derived from the reviewed export names; it never stores provider URLs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (editor.includePlaylist) {
                        FolderPlaylistOrder.entries.forEach { order ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { actions.updateBatchPlaylist(true, order) }
                                    .testTag("batch-playlist-order-${order.name}"),
                            ) {
                                RadioButton(
                                    selected = editor.playlistOrder == order,
                                    onClick = { actions.updateBatchPlaylist(true, order) },
                                )
                                Text(playlistOrderLabel(order))
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("Online proposals", "Optional, reviewed per file")
            OutlinedButton(
                onClick = actions.searchBatchMetadata,
                enabled = editor.phase != MetadataPhase.SEARCHING && editor.phase != MetadataPhase.STAGING,
                modifier = Modifier.padding(horizontal = 18.dp).testTag("batch-metadata-search"),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Find suggestions for ${editor.items.size} files")
            }
            if (editor.phase == MetadataPhase.SEARCHING || editor.phase == MetadataPhase.STAGING) {
                LinearProgressIndicator(
                    progress = { editor.progressCompleted.toFloat() / editor.progressTotal.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .testTag("batch-metadata-progress"),
                )
            }
        }
        items(editor.items, key = { "${it.track.sourceId.value}:${it.track.id.value}" }) { item ->
            BatchItemCard(item, actions)
        }
        editor.status?.let { item { StatusCard(it) } }
        editor.artifact?.let { item { ArtifactCard(it, actions.shareMetadataArtifact) } }
        item {
            Button(
                onClick = { reviewOpen = true },
                enabled = hasPlannedChanges && editor.phase != MetadataPhase.STAGING && editor.phase != MetadataPhase.SEARCHING,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("review-batch-metadata"),
            ) {
                Icon(Icons.Default.EditNote, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Review batch changes")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (reviewOpen) {
        BatchMetadataReviewDialog(
            editor = editor,
            onDismiss = { reviewOpen = false },
            onConfirm = {
                reviewOpen = false
                actions.stageBatchMetadata()
            },
        )
    }
}

@Composable
private fun SingleMetadataReviewDialog(
    editor: MetadataEditorUiState.Single,
    changed: Set<TagField>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("metadata-review-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
        title = { Text("Review tag changes") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${changed.size} field(s) will change in a separate candidate copy.")
                changed.sortedBy(::metadataFieldLabel).forEach { field ->
                    Column(Modifier.testTag("metadata-review-${field.name}")) {
                        Text(metadataFieldLabel(field), fontWeight = FontWeight.SemiBold)
                        Text(
                            "Before: ${editor.original.fields[field]?.value ?: "Empty"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "After: ${editor.draft[field]?.takeIf(String::isNotBlank) ?: "Clear field"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    "The source file and cloud object stay unchanged. The candidate is reread and verified before it can be shared or saved.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-stage-metadata")) {
                Text("Stage verified copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss-metadata-review")) {
                Text("Keep editing")
            }
        },
    )
}

@Composable
private fun BatchMetadataReviewDialog(
    editor: MetadataEditorUiState.Batch,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val common = editor.commonFields.filterValues { it.enabled }.keys.sortedBy(::metadataFieldLabel)
    val onlineFieldCount = editor.items.sumOf { it.acceptedCandidateFields.size }
    AlertDialog(
        modifier = Modifier.testTag("batch-metadata-review-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
        title = { Text("Review batch tag changes") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${editor.items.size} prepared file(s)", fontWeight = FontWeight.SemiBold)
                Text(
                    if (common.isEmpty()) "Common fields: none" else
                        "Common fields: ${common.joinToString { metadataFieldLabel(it) }}",
                )
                Text(
                    if (editor.sequenceTracks) {
                        "Track sequence: start ${editor.sequenceStart}; write total: ${if (editor.includeTrackTotal) "yes" else "no"}"
                    } else {
                        "Track sequence: unchanged"
                    },
                )
                Text("Online candidate fields explicitly selected: $onlineFieldCount")
                Text(
                    if (editor.includePlaylist) {
                        "Playlist export: included · ${playlistOrderLabel(editor.playlistOrder)}"
                    } else {
                        "Playlist export: not included"
                    },
                    modifier = Modifier.testTag("batch-playlist-review"),
                )
                Text(
                    "Each changed item is staged and reread independently. No cloud object or source file is overwritten by this Tag studio export.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-stage-batch-metadata")) {
                Text("Stage verified copies")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss-batch-metadata-review")) {
                Text("Keep editing")
            }
        },
    )
}

@Composable
private fun MetadataSourceCard(title: String, filename: String, format: String, revision: String?, hash: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(filename, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(format, style = MaterialTheme.typography.labelLarge)
            Text("Revision: ${revision ?: "content hash only"}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256: ${hash.take(16)}…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SafetyNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetadataFieldEditor(
    field: TagField,
    value: String,
    original: String?,
    provenance: String?,
    changed: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(metadataFieldLabel(field)) },
                enabled = enabled,
                minLines = if (field == TagField.LYRICS || field == TagField.COMMENT) 3 else 1,
                modifier = Modifier.fillMaxWidth().testTag("metadata-field-${field.name}"),
                trailingIcon = {
                    if (changed) {
                        IconButton(onClick = onReset) {
                            Icon(Icons.Default.Restore, contentDescription = "Reset ${metadataFieldLabel(field)}")
                        }
                    }
                },
            )
            Text(
                "Original: ${original ?: "—"}${provenance?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatchFieldEditor(
    field: TagField,
    edit: BatchFieldDraft,
    enabled: Boolean,
    onChange: (BatchFieldDraft) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = edit.enabled,
                    enabled = enabled,
                    onCheckedChange = { onChange(edit.copy(enabled = it)) },
                )
                Text(metadataFieldLabel(field), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(
                    enabled = edit.enabled && enabled,
                    onClick = { onChange(edit.copy(clear = !edit.clear, value = if (!edit.clear) "" else edit.value)) },
                ) {
                    Text(if (edit.clear) "Clear enabled" else "Clear")
                }
            }
            OutlinedTextField(
                value = edit.value,
                onValueChange = { onChange(edit.copy(value = it, clear = false)) },
                enabled = edit.enabled && !edit.clear && enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (edit.clear) "Field will be removed" else "Value for all selected files") },
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: MetadataCandidate,
    selected: Boolean,
    acceptedFields: Set<TagField>,
    onSelect: () -> Unit,
    onToggleField: (TagField) -> Unit,
    onApply: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(candidate.fields[TagField.TITLE]?.value ?: candidate.id, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            candidate.fields[TagField.ARTIST]?.value,
                            candidate.fields[TagField.ALBUM]?.value,
                        ).joinToString(" · ").ifBlank { "MusicBrainz recording" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${(candidate.score * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            if (selected) {
                if (acceptedFields.isEmpty()) {
                    Text(
                        "Choose individual fields below. Selecting a match never selects metadata automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("candidate-fields-unselected"),
                    )
                }
                candidate.fields.filterKeys { it in MetadataDraftPlanner.onlineCandidateFields }.forEach { (field, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = field in acceptedFields,
                            onCheckedChange = { onToggleField(field) },
                        )
                        Text("${metadataFieldLabel(field)}: ${value.value}", Modifier.weight(1f))
                    }
                }
                Button(
                    onClick = onApply,
                    enabled = acceptedFields.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("apply-candidate-fields"),
                ) {
                    Text("Copy selected fields to draft")
                }
            }
        }
    }
}

@Composable
private fun BatchItemCard(item: MetadataEditorUiState.BatchItem, actions: AppActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.track.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.original.format, style = MaterialTheme.typography.labelMedium)
            item.candidates.forEach { candidate ->
                val selected = candidate.id == item.selectedCandidateId
                Row(
                    Modifier.fillMaxWidth().clickable { actions.selectBatchCandidate(item.track, if (selected) null else candidate.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { actions.selectBatchCandidate(item.track, if (selected) null else candidate.id) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(candidate.fields[TagField.TITLE]?.value ?: candidate.id)
                        Text(candidate.fields[TagField.ARTIST]?.value.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${(candidate.score * 100).toInt()}%")
                }
                if (selected) {
                    if (item.acceptedCandidateFields.isEmpty()) {
                        Text(
                            "No fields selected yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    candidate.fields.filterKeys { it in MetadataDraftPlanner.onlineCandidateFields }.forEach { (field, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = field in item.acceptedCandidateFields,
                                onCheckedChange = { actions.toggleBatchCandidateField(item.track, field) },
                            )
                            Text("${metadataFieldLabel(field)}: ${value.value}", Modifier.weight(1f))
                        }
                    }
                }
            }
            item.status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text(status, Modifier.padding(14.dp))
    }
}

@Composable
private fun ArtifactCard(artifact: MetadataArtifactUi, share: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("metadata-artifact"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FileDownloadDone, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Verified export", style = MaterialTheme.typography.titleMedium)
            }
            Text(artifact.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${artifact.itemCount} file(s) · ${artifact.sizeBytes} bytes")
            Text("SHA-256 ${artifact.sha256.take(20)}…", style = MaterialTheme.typography.bodySmall)
            Button(onClick = share, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share or save export")
            }
        }
    }
}

private fun metadataFieldLabel(field: TagField): String = when (field) {
    TagField.TITLE -> "Title"
    TagField.ARTIST -> "Artist"
    TagField.ALBUM -> "Album"
    TagField.ALBUM_ARTIST -> "Album artist"
    TagField.GENRE -> "Genre"
    TagField.YEAR -> "Year"
    TagField.TRACK_NUMBER -> "Track number"
    TagField.TRACK_TOTAL -> "Track total"
    TagField.DISC_NUMBER -> "Disc number"
    TagField.DISC_TOTAL -> "Disc total"
    TagField.COMMENT -> "Comment"
    TagField.COMPOSER -> "Composer"
    TagField.ISRC -> "ISRC"
    TagField.MUSICBRAINZ_RECORDING_ID -> "MusicBrainz recording ID"
    TagField.MUSICBRAINZ_RELEASE_ID -> "MusicBrainz release ID"
    TagField.LYRICS -> "Lyrics"
}
