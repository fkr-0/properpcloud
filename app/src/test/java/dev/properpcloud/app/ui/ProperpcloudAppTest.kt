package dev.properpcloud.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableStateOf
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.playback.PlaybackUiState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProperpcloudAppTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun libraryShowsFolderAndPlayableTrack() {
        compose.setContent {
            ProperpcloudApp(sampleState(), noOpActions(), onAuthorizePCloud = {})
        }
        compose.onNodeWithText("properpcloud").assertIsDisplayed()
        compose.onNodeWithText("Audiobooks").assertIsDisplayed()
        compose.onNodeWithText("A Door in the Rain").assertIsDisplayed()
        compose.onNodeWithTag("library-list").assertIsDisplayed()
    }

    @Test
    fun libraryShowsBatchSelectionBar() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        compose.setContent {
            ProperpcloudApp(
                state = state.copy(metadataSelection = listOf(track)),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("1 selected for tags").assertIsDisplayed()
        compose.onNodeWithText("Edit batch").assertIsDisplayed()
    }

    @Test
    fun singleMetadataEditorShowsOriginalAndChangedDraft() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        val original = TagSnapshot(
            format = "WAV",
            fields = mapOf(
                TagField.TITLE to MetadataValue("Original title", MetadataProvenance.EMBEDDED),
            ),
        )
        compose.setContent {
            ProperpcloudApp(
                state = state.copy(
                    destination = AppDestination.METADATA,
                    metadataEditor = MetadataEditorUiState.Single(
                        track = track,
                        original = original,
                        draft = mapOf(TagField.TITLE to "Changed title"),
                        sourceRevision = "revision-1",
                        sourceHash = "abcdef0123456789abcdef0123456789",
                    ),
                ),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor").assertIsDisplayed()
        compose.onNodeWithText("Tag studio").assertIsDisplayed()
        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("metadata-field-TITLE"))
        compose.onNodeWithTag("metadata-field-TITLE").assertIsDisplayed()
        compose.onNodeWithText("Original: Original title · EMBEDDED").assertIsDisplayed()
        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("stage-metadata"))
        compose.onNodeWithTag("stage-metadata").assertIsDisplayed()
    }

    private fun samplePlayingState(): AppUiState {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        return state.copy(
            queue = PlaybackQueue(
                generation = 1,
                entries = listOf(QueueEntry(track)),
                currentIndex = 0,
            ),
            playback = PlaybackUiState(
                connected = true,
                title = track.taggedTitle.orEmpty(),
                subtitle = track.name,
                positionMillis = 2_000,
                durationMillis = track.durationMillis ?: 0,
            ),
        )
    }

    @Test
    fun compactNavigationOpensSettingsAndShowsOAuthControl() {
        val selected = mutableStateOf(AppDestination.LIBRARY)
        compose.setContent {
            ProperpcloudApp(
                state = sampleState().copy(destination = selected.value),
                actions = noOpActions().copy(selectDestination = { selected.value = it }),
                onAuthorizePCloud = {},
            )
        }
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("pCloud account").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Fallback direct sign-in"))
        compose.onNodeWithText("Fallback direct sign-in").assertIsDisplayed()
        if (compose.onAllNodesWithTag("client-id").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("toggle-advanced-oauth"))
            compose.onNodeWithTag("toggle-advanced-oauth").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("client-id"))
        compose.onNodeWithTag("client-id").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Metadata tools"))
        compose.onNodeWithText("Metadata tools").assertIsDisplayed()
    }

    @Test
    fun settingsExposeClearlyLabelledFallbackDirectLogin() {
        compose.setContent {
            ProperpcloudApp(
                state = sampleState().copy(destination = AppDestination.SETTINGS),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("direct-login-card"))
        compose.onNodeWithText("Fallback direct sign-in").assertIsDisplayed()
        if (compose.onAllNodesWithTag("direct-login-email").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("toggle-direct-login").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("direct-login-email"))
        compose.onNodeWithTag("direct-login-email").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("direct-login-password"))
        compose.onNodeWithTag("direct-login-password").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("direct-login-submit"))
        compose.onNodeWithText("Sign in directly").assertIsDisplayed()
    }

    @Test
    fun miniPlayerOpensDedicatedSeekableNowPlayingScreen() {
        val selected = mutableStateOf(AppDestination.LIBRARY)
        compose.setContent {
            ProperpcloudApp(
                state = samplePlayingState().copy(destination = selected.value),
                actions = noOpActions().copy(selectDestination = { selected.value = it }),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("mini-player").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Now playing").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("player-artwork").assertIsDisplayed()
        compose.onNodeWithTag("player-seek").performScrollTo().assertIsDisplayed()
    }

    private fun sampleState(): AppUiState {
        val source = SourceId("demo")
        val root = AudioFolder(source, NodeId("root"), null, "Demo library")
        val folder = AudioFolder(source, NodeId("folder"), root.id, "Audiobooks")
        val track = AudioTrack(
            sourceId = source,
            id = NodeId("track"),
            parentId = root.id,
            name = "01 - A Door in the Rain.wav",
            taggedTitle = "A Door in the Rain",
            durationMillis = 8_000,
        )
        return AppUiState(
            sourceKind = SourceKind.DEMO,
            sourceName = root.name,
            currentFolder = root,
            breadcrumbs = listOf(root),
            nodes = listOf(folder, track),
            loading = false,
        )
    }

    private fun noOpActions() = AppActions(
        selectDestination = {},
        openFolder = {},
        navigateBreadcrumb = {},
        refresh = {},
        setSort = {},
        playTrack = {},
        enqueueTrack = { _, _: QueueOperation -> },
        enqueueFolder = { _, _, _ -> },
        cancelQueueBuild = {},
        selectQueueItem = {},
        removeQueueItem = {},
        moveQueueItem = { _, _ -> },
        clearQueue = {},
        openContainingFolder = {},
        inspect = {},
        closeInspection = {},
        openMetadataEditor = {},
        toggleMetadataSelection = {},
        clearMetadataSelection = {},
        openBatchMetadataEditor = {},
        closeMetadataEditor = {},
        updateMetadataField = { _, _ -> },
        resetMetadataField = {},
        searchMetadata = {},
        selectMetadataCandidate = {},
        toggleMetadataCandidateField = {},
        applyMetadataCandidate = {},
        stageMetadata = {},
        updateBatchField = { _, _ -> },
        updateBatchSequence = { _, _, _ -> },
        searchBatchMetadata = {},
        selectBatchCandidate = { _, _ -> },
        toggleBatchCandidateField = { _, _ -> },
        stageBatchMetadata = {},
        shareMetadataArtifact = {},
        signInWithPCloudPassword = { _, _, _ -> },
        updateClientId = {},
        openPCloudDeveloperConsole = {},
        selectSource = {},
        disconnectPCloud = {},
        consumeMessage = {},
        playPause = {},
        skipNext = {},
        skipPrevious = {},
        seekBy = {},
        seekTo = {},
    )
}
