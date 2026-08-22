package dev.properpcloud.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.pcloud.sdk.AuthorizationActivity
import com.pcloud.sdk.AuthorizationRequest
import com.pcloud.sdk.AuthorizationResult
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.playback.PlaybackConnection
import dev.properpcloud.app.ui.AppActions
import dev.properpcloud.app.ui.MainViewModel
import dev.properpcloud.app.ui.ProperpcloudApp
import dev.properpcloud.source.pcloud.PCloudSession

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val app = application as ProperpcloudApplication
        MainViewModel.Factory(application, app.container, app.container.playback)
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (data == null) {
            viewModel.showMessage("pCloud authorization was cancelled.")
            return@registerForActivityResult
        }
        runCatching { AuthorizationActivity.getResult(data) }
            .onSuccess { authorization ->
                when (authorization.result) {
                    AuthorizationResult.ACCESS_GRANTED -> {
                        val token = authorization.token
                        val apiHost = authorization.apiHost
                        if (token.isNullOrBlank() || apiHost.isNullOrBlank()) {
                            viewModel.showMessage("pCloud returned an incomplete authorization result.")
                        } else {
                            viewModel.onPCloudAuthorized(
                                PCloudSession(
                                    accessToken = token,
                                    apiHost = apiHost,
                                    userId = authorization.userId,
                                ),
                            )
                        }
                    }
                    AuthorizationResult.ACCESS_DENIED -> viewModel.showMessage("pCloud access was denied.")
                    AuthorizationResult.AUTH_ERROR -> viewModel.showMessage(
                        "pCloud authorization failed: ${authorization.errorMessage.orEmpty()}",
                    )
                    AuthorizationResult.CANCELLED -> viewModel.showMessage("pCloud authorization was cancelled.")
                    null -> viewModel.showMessage("pCloud returned an unknown authorization result.")
                }
            }
            .onFailure { viewModel.showMessage("Could not read pCloud authorization result.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            ProperpcloudApp(
                state = state,
                actions = AppActions(
                    selectDestination = viewModel::selectDestination,
                    openFolder = viewModel::openFolder,
                    navigateBreadcrumb = viewModel::navigateBreadcrumb,
                    refresh = viewModel::refresh,
                    setSort = viewModel::setSort,
                    playTrack = viewModel::playTrack,
                    enqueueTrack = viewModel::enqueueTrack,
                    enqueueFolder = viewModel::enqueueFolder,
                    cancelQueueBuild = viewModel::cancelQueueBuild,
                    selectQueueItem = viewModel::selectQueueItem,
                    removeQueueItem = viewModel::removeQueueItem,
                    moveQueueItem = viewModel::moveQueueItem,
                    clearQueue = viewModel::clearQueue,
                    openContainingFolder = viewModel::openContainingFolder,
                    inspect = viewModel::inspect,
                    closeInspection = viewModel::closeInspection,
                    openMetadataEditor = viewModel::openMetadataEditor,
                    toggleMetadataSelection = viewModel::toggleMetadataSelection,
                    clearMetadataSelection = viewModel::clearMetadataSelection,
                    openBatchMetadataEditor = viewModel::openBatchMetadataEditor,
                    closeMetadataEditor = viewModel::closeMetadataEditor,
                    updateMetadataField = viewModel::updateMetadataField,
                    resetMetadataField = viewModel::resetMetadataField,
                    searchMetadata = viewModel::searchMetadata,
                    selectMetadataCandidate = viewModel::selectMetadataCandidate,
                    toggleMetadataCandidateField = viewModel::toggleMetadataCandidateField,
                    applyMetadataCandidate = viewModel::applyMetadataCandidate,
                    stageMetadata = viewModel::stageMetadata,
                    updateBatchField = viewModel::updateBatchField,
                    updateBatchSequence = viewModel::updateBatchSequence,
                    updateBatchPlaylist = viewModel::updateBatchPlaylist,
                    searchBatchMetadata = viewModel::searchBatchMetadata,
                    selectBatchCandidate = viewModel::selectBatchCandidate,
                    toggleBatchCandidateField = viewModel::toggleBatchCandidateField,
                    stageBatchMetadata = viewModel::stageBatchMetadata,
                    shareMetadataArtifact = ::shareMetadataArtifact,
                    signInWithPCloudPassword = viewModel::signInWithPCloudPassword,
                    updateClientId = viewModel::updateClientId,
                    openPCloudDeveloperConsole = ::openPCloudDeveloperConsole,
                    selectSource = { kind ->
                        when (kind) {
                            SourceKind.DEMO -> viewModel.useDemoSource()
                            SourceKind.PCLOUD -> viewModel.usePCloudSource()
                        }
                    },
                    disconnectPCloud = viewModel::disconnectPCloud,
                    consumeMessage = viewModel::consumeMessage,
                    playPause = viewModel::playPause,
                    skipNext = viewModel::skipNext,
                    skipPrevious = viewModel::skipPrevious,
                    seekBy = viewModel::seekBy,
                    seekTo = viewModel::seekTo,
                ),
                onAuthorizePCloud = ::launchAuthorization,
            )
        }
    }

    override fun onStop() {
        viewModel.flushPlaybackProgress()
        super.onStop()
    }

    private fun launchAuthorization(clientId: String) {
        if (clientId.isBlank()) {
            viewModel.showMessage("This build has no pCloud application identity. Open advanced setup to add a client ID.")
            return
        }
        val request = AuthorizationRequest.create()
            .setType(AuthorizationRequest.Type.TOKEN)
            .setClientId(clientId)
            .build()
        authorizationLauncher.launch(AuthorizationActivity.createIntent(this, request))
    }

    private fun openPCloudDeveloperConsole() {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://docs.pcloud.com/my_apps/".toUri(),
            ),
        )
    }

    private fun shareMetadataArtifact() {
        val artifact = viewModel.currentMetadataArtifact()
        if (artifact == null || !artifact.file.isFile) {
            viewModel.showMessage("No verified metadata export is available.")
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.files",
            artifact.file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, artifact.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, "Share verified tag export"))
    }

}
