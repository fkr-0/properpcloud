package dev.properpcloud.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import dev.properpcloud.app.data.DirectoryBookmark
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.metadata.BatchFieldDraft
import dev.properpcloud.app.playback.PlaybackUiState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.AudiobookBookId
import dev.properpcloud.core.model.AudioTabReducer
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.PlayerConnectivity
import dev.properpcloud.core.model.PlayerPlaybackState
import dev.properpcloud.core.model.PlayerTargetId
import dev.properpcloud.core.model.PlayerTargetSnapshot
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("A Door in the Rain"))
        compose.onNodeWithText("A Door in the Rain").assertIsDisplayed()
        compose.onNodeWithText("Folder").assertIsDisplayed()
        compose.onNodeWithText("Folder · stable source identity").assertDoesNotExist()
    }

    @Test
    fun playFolderPrimaryActionRecursesIntoSubdirectories() {
        val state = sampleState()
        var request: Triple<AudioFolder, QueueOperation, Boolean>? = null
        compose.setContent {
            ProperpcloudApp(
                state = state,
                actions = noOpActions().copy(
                    enqueueFolder = { folder, operation, recursive ->
                        request = Triple(folder, operation, recursive)
                    },
                ),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithText("Play folder").performClick()

        assertEquals(state.currentFolder, request?.first)
        assertEquals(QueueOperation.REPLACE, request?.second)
        assertTrue(request?.third == true)
    }

    @Test
    fun queuePreviewHasProminentOpenActionAndBookmarksAreActionable() {
        val state = samplePlayingState().copy(
            directoryBookmarks = listOf(DirectoryBookmark(SourceId("pcloud"), NodeId("folder"), "Dub crates")),
        )
        var destination: AppDestination? = null
        var opened: DirectoryBookmark? = null
        compose.setContent {
            ProperpcloudApp(
                state = state,
                actions = noOpActions().copy(
                    selectDestination = { destination = it },
                    openDirectoryBookmark = { opened = it },
                ),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithText("Dub crates").performClick()
        assertEquals(state.directoryBookmarks.single(), opened)
        compose.onNodeWithTag("open-queue").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(AppDestination.QUEUE, destination)
    }

    @Test
    fun bitrateInspectionFormattingUsesHumanReadableKbitPerSecond() {
        assertEquals("128 kbit/s", formatMetadataValue("bitrateKbps", "128"))
        assertEquals("320 kbit/s", formatMetadataValue("audio_bitrate", "320000"))
        assertEquals("44100", formatMetadataValue("sampleRate", "44100"))
    }

    @Test
    fun disconnectedLibraryShowsExplicitEmptyState() {
        compose.setContent {
            ProperpcloudApp(
                state = AppUiState(loading = false),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithText("No library connected").assertIsDisplayed()
    }

    @Test
    fun settingsDoNotOfferDemoMode() {
        compose.setContent {
            ProperpcloudApp(
                state = AppUiState(destination = AppDestination.SETTINGS, loading = false),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithText("No library is currently selected.").assertIsDisplayed()
        compose.onNodeWithText("Use demo").assertDoesNotExist()
    }

    @Test
    fun filenameSearchExpandsCollapsesAndFilterInteractionReachesAction() {
        val state = mutableStateOf(sampleState())
        var toggled: SearchMatchType? = null
        compose.setContent {
            ProperpcloudApp(
                state = state.value,
                actions = noOpActions().copy(
                    toggleLibrarySearch = {
                        val search = state.value.search
                        state.value = state.value.copy(
                            search = if (search.expanded) {
                                search.copy(expanded = false, query = "", results = emptyList())
                            } else {
                                search.copy(expanded = true)
                            },
                        )
                    },
                    toggleSearchMatchType = { toggled = it },
                ),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("library-search-field").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search filenames").performClick()
        compose.onNodeWithTag("library-search-field").assertIsDisplayed()
        compose.onNodeWithTag("search-filter-audio_files").performClick()
        assertTrue(toggled == SearchMatchType.AUDIO_FILES)
        compose.onNodeWithContentDescription("Close filename search").performClick()
        compose.onNodeWithTag("library-search-field").assertDoesNotExist()
    }

    @Test
    fun batchPlaylistOptionsAreExplicitAndReachApplicationAction() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        var included = true
        var order = FolderPlaylistOrder.TAG_TRACK_NUMBER
        compose.setContent {
            ProperpcloudApp(
                state.copy(
                    destination = AppDestination.METADATA,
                    metadataEditor = MetadataEditorUiState.Batch(
                        items = listOf(MetadataEditorUiState.BatchItem(track, TagSnapshot("ID3"))),
                        commonFields = mapOf(
                            TagField.ALBUM to BatchFieldDraft(enabled = true, value = "Reviewed album"),
                        ),
                    ),
                ),
                noOpActions().copy(updateBatchPlaylist = { include, selectedOrder ->
                    included = include
                    order = selectedOrder
                }),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("batch-playlist-options"))
        compose.onNodeWithTag("include-batch-playlist").performClick()
        assertFalse(included)

        compose.onNodeWithTag("batch-playlist-order-MODIFICATION_TIME").performClick()
        assertTrue(included)
        assertTrue(order == FolderPlaylistOrder.MODIFICATION_TIME)
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
        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("review-metadata"))
        compose.onNodeWithTag("review-metadata").assertIsDisplayed()
    }

    @Test
    fun singleMetadataReviewMustBeConfirmedBeforeStaging() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        val original = TagSnapshot(
            "ID3v2.4",
            mapOf(TagField.TITLE to MetadataValue("Before", MetadataProvenance.EMBEDDED)),
        )
        var staged = false
        compose.setContent {
            ProperpcloudApp(
                state.copy(
                    destination = AppDestination.METADATA,
                    metadataEditor = MetadataEditorUiState.Single(
                        track = track,
                        original = original,
                        draft = mapOf(TagField.TITLE to "After"),
                        sourceRevision = "rev",
                        sourceHash = "abcdef0123456789abcdef0123456789",
                    ),
                ),
                noOpActions().copy(stageMetadata = { staged = true }),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("review-metadata"))
        compose.onNodeWithTag("review-metadata").performClick()
        compose.onNodeWithTag("metadata-review-dialog").assertIsDisplayed()
        compose.onNodeWithText("Before: Before").assertIsDisplayed()
        compose.onNodeWithText("After: After").assertIsDisplayed()
        assertFalse(staged)

        compose.onNodeWithTag("dismiss-metadata-review").performClick()
        compose.onNodeWithTag("metadata-review-dialog").assertDoesNotExist()
        assertFalse(staged)

        compose.onNodeWithTag("review-metadata").performClick()
        compose.onNodeWithTag("confirm-stage-metadata").performClick()
        assertTrue(staged)
    }

    @Test
    fun onlineCandidateStartsWithNoFieldsSelected() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        val candidate = MetadataCandidate(
            id = "recording-1",
            provider = MetadataProvenance.MUSICBRAINZ,
            score = 0.95,
            fields = mapOf(
                TagField.TITLE to MetadataValue("Suggested", MetadataProvenance.MUSICBRAINZ, 0.95),
                TagField.ARTIST to MetadataValue("Artist", MetadataProvenance.MUSICBRAINZ, 0.94),
            ),
        )
        compose.setContent {
            ProperpcloudApp(
                state.copy(
                    destination = AppDestination.METADATA,
                    metadataEditor = MetadataEditorUiState.Single(
                        track = track,
                        original = TagSnapshot("ID3"),
                        draft = emptyMap(),
                        sourceRevision = "rev",
                        sourceHash = "abcdef0123456789abcdef0123456789",
                        candidates = listOf(candidate),
                        selectedCandidateId = candidate.id,
                        acceptedCandidateFields = emptySet(),
                    ),
                ),
                noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasText("Suggested"))
        compose.onNodeWithTag("candidate-fields-unselected", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("apply-candidate-fields").assertIsNotEnabled()
    }

    @Test
    fun batchMetadataReviewSummarizesScopeBeforeStaging() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        var staged = false
        compose.setContent {
            ProperpcloudApp(
                state.copy(
                    destination = AppDestination.METADATA,
                    metadataEditor = MetadataEditorUiState.Batch(
                        items = listOf(MetadataEditorUiState.BatchItem(track, TagSnapshot("ID3"))),
                        commonFields = mapOf(
                            TagField.ALBUM to BatchFieldDraft(enabled = true, value = "Reviewed album"),
                        ),
                        sequenceTracks = true,
                        sequenceStart = "1",
                        includeTrackTotal = true,
                    ),
                ),
                noOpActions().copy(stageBatchMetadata = { staged = true }),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("review-batch-metadata"))
        compose.onNodeWithTag("review-batch-metadata").performClick()
        compose.onNodeWithTag("batch-metadata-review-dialog").assertIsDisplayed()
        compose.onNodeWithText("Common fields: Album").assertIsDisplayed()
        compose.onNodeWithText("Track sequence: start 1; write total: yes").assertIsDisplayed()
        compose.onNodeWithText("Playlist export: included · Disc and track tags").assertIsDisplayed()
        assertFalse(staged)
        compose.onNodeWithTag("confirm-stage-batch-metadata").performClick()
        assertTrue(staged)
    }

    @Test
    fun batchMetadataProgressAndFailureStatusRemainVisible() {
        val state = sampleState()
        val track = state.nodes.filterIsInstance<AudioTrack>().single()
        val editor = mutableStateOf(
            MetadataEditorUiState.Batch(
                items = listOf(MetadataEditorUiState.BatchItem(track, TagSnapshot("ID3"))),
                commonFields = mapOf(
                    TagField.ALBUM to BatchFieldDraft(enabled = true, value = "Reviewed album"),
                ),
                phase = MetadataPhase.STAGING,
                progressCompleted = 1,
                progressTotal = 2,
            ),
        )
        compose.setContent {
            ProperpcloudApp(
                state.copy(destination = AppDestination.METADATA, metadataEditor = editor.value),
                noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasTestTag("batch-metadata-progress"))
        compose.onNodeWithTag("batch-metadata-progress").assertIsDisplayed()

        compose.runOnIdle {
            editor.value = editor.value.copy(
                phase = MetadataPhase.READY,
                status = "Batch metadata export failed: disk full",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("metadata-editor-list").performScrollToNode(hasText("Batch metadata export failed: disk full"))
        compose.onNodeWithText("Batch metadata export failed: disk full").assertIsDisplayed()
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
    fun compactNavigationOpensPolishedSettingsWithoutDeveloperControls() {
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
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Sign in with email and password"))
        compose.onNodeWithText("Sign in with email and password").assertIsDisplayed()
        compose.onNodeWithTag("toggle-advanced-oauth").assertDoesNotExist()
        compose.onNodeWithTag("client-id").assertDoesNotExist()
        compose.onNodeWithTag("open-pcloud-console").assertDoesNotExist()
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
        compose.onNodeWithText("Sign in with email and password").assertIsDisplayed()
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
    fun connectedSettingsAvoidImplementationSecurityBanners() {
        compose.setContent {
            ProperpcloudApp(
                state = sampleState().copy(
                    destination = AppDestination.SETTINGS,
                    pCloudConnected = true,
                    serverConnected = true,
                    serverBaseUrl = "https://library.example",
                ),
                actions = noOpActions(),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Connected to pCloud"))
        compose.onNodeWithText("Ready to browse and play your library.").assertIsDisplayed()
        compose.onNodeWithText(
            "The session token is encrypted with Android Keystore and excluded from backup.",
        ).assertDoesNotExist()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText("Remote servers must use HTTPS."))
        compose.onNodeWithText("Remote servers must use HTTPS.").assertIsDisplayed()
        compose.onNodeWithText(
            "Remote servers must use HTTPS. The bearer token is encrypted with Android Keystore and is never used as queue or media identity.",
        ).assertDoesNotExist()
    }

    @Test
    fun settingsSubmitServerCatalogUrlAndBearerWithoutPuttingBearerInState() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            ProperpcloudApp(
                state = sampleState().copy(destination = AppDestination.SETTINGS),
                actions = noOpActions().copy(connectServer = { url, token -> submitted = url to token }),
                onAuthorizePCloud = {},
            )
        }

        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("server-base-url"))
        compose.onNodeWithTag("server-base-url").performTextInput("https://library.example")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("server-api-token"))
        compose.onNodeWithTag("server-api-token").performTextInput("device-secret")
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("server-connect"))
        compose.onNodeWithTag("server-connect").performClick()

        assertEquals("https://library.example" to "device-secret", submitted)
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

    @Test
    fun playersSurfaceShowsLocalAndRemoteTargetsWithExplicitAvailability() {
        val localId = PlayerTargetId("local:media3")
        val remoteId = PlayerTargetId("server:test:living-room")
        val state = samplePlayingState().copy(
            destination = AppDestination.PLAYERS,
            activePlayerId = localId,
            players = listOf(
                PlayerTargetSnapshot(
                    id = localId,
                    providerId = "local",
                    providerName = "This device",
                    displayName = "ProperPCloud local player",
                    connectivity = PlayerConnectivity.CONNECTED,
                    playbackState = PlayerPlaybackState.PAUSED,
                    controllable = true,
                    local = true,
                ),
                PlayerTargetSnapshot(
                    id = remoteId,
                    providerId = "server:test",
                    providerName = "Server",
                    displayName = "Living room",
                    connectivity = PlayerConnectivity.UNAVAILABLE,
                    playbackState = PlayerPlaybackState.UNKNOWN,
                    stale = true,
                ),
            ),
        )

        compose.setContent {
            ProperpcloudApp(state, noOpActions(), onAuthorizePCloud = {})
        }

        compose.onNodeWithTag("players-screen").assertIsDisplayed()
        compose.onNodeWithText("ProperPCloud local player").assertIsDisplayed()
        compose.onNodeWithTag("players-list").performScrollToNode(hasText("Living room"))
        compose.onNodeWithText("Living room").assertIsDisplayed()
        compose.onNodeWithText("Server • Unavailable • stale").assertIsDisplayed()
        compose.onNodeWithText("Controls unavailable while this target is offline.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun audiobookPlayerExposesModeContextAndDedicatedSkipControls() {
        val base = samplePlayingState()
        val localId = PlayerTargetId("local:media3")
        val current = requireNotNull(base.queue.current?.track)
        val tabs = AudioTabReducer.updateActive(base.audioTabs) { tab ->
            tab.copy(
                playbackSpeed = 1.5f,
                activeAudiobookBookId = AudiobookBookId(current.sourceId, current.parentId),
                stopAtChapterEnd = true,
            )
        }
        val state = base.copy(
            destination = AppDestination.PLAYER,
            audioTabs = tabs,
            players = listOf(
                PlayerTargetSnapshot(
                    id = localId,
                    providerId = "local",
                    providerName = "This device",
                    displayName = "ProperPCloud local player",
                    connectivity = PlayerConnectivity.CONNECTED,
                    playbackState = PlayerPlaybackState.PAUSED,
                    currentMediaTitle = current.taggedTitle,
                    controllable = true,
                    local = true,
                ),
            ),
            activePlayerId = localId,
            sleepTimerEndsAtEpochMillis = System.currentTimeMillis() + 60_000,
        )

        compose.setContent {
            ProperpcloudApp(state, noOpActions(), onAuthorizePCloud = {})
        }

        compose.onNodeWithTag("playback-context").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mode · Audiobook").assertIsDisplayed()
        compose.onNodeWithText("Back skip · 15s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Forward skip · 30s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shuffle").assertDoesNotExist()
    }

    private fun sampleState(): AppUiState {
        val source = SourceId("pcloud")
        val root = AudioFolder(source, NodeId("root"), null, "pCloud")
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
            sourceKind = SourceKind.PCLOUD,
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
        toggleDirectoryBookmark = {},
        openDirectoryBookmark = {},
        refresh = {},
        toggleLibrarySearch = {},
        updateLibrarySearchQuery = {},
        toggleSearchMatchType = {},
        setSort = {},
        switchAudioTab = {},
        addAudioTab = { _, _ -> },
        updateAudioTab = { _, _, _ -> },
        removeAudioTab = {},
        playTrack = {},
        resumeTrack = {},
        enqueueTrack = { _, _: QueueOperation -> },
        enqueueFolder = { _, _, _ -> },
        cancelQueueBuild = {},
        selectQueueItem = {},
        removeQueueItem = {},
        moveQueueItem = { _, _ -> },
        clearQueue = {},
        saveCurrentPlaylist = {},
        loadSavedPlaylist = {},
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
        updateBatchPlaylist = { _, _ -> },
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
        connectServer = { _, _ -> },
        disconnectServer = {},
        setPlaybackHistoryEnabled = {},
        setPlaybackHistoryRetention = {},
        consumeMessage = {},
        playPause = {},
        skipNext = {},
        skipPrevious = {},
        seekBy = {},
        seekTo = {},
        setPlaybackSpeed = {},
        setVolume = {},
        toggleShuffle = {},
        cycleRepeatMode = {},
        setSleepTimer = {},
    )
}
