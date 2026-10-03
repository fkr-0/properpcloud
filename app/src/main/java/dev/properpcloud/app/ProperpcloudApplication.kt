@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.properpcloud.app

import android.app.Application
import dev.properpcloud.app.data.AppPreferencesRepository
import dev.properpcloud.app.data.DisconnectedAudioSource
import dev.properpcloud.app.data.SourceRegistry
import dev.properpcloud.app.metadata.MetadataEditingWorkspace
import dev.properpcloud.app.playback.PlaybackConnection
import dev.properpcloud.app.security.EncryptedTokenVault
import dev.properpcloud.app.security.EncryptedServerCatalogVault
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.metadata.online.MusicBrainzMetadataProvider
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit
import dev.properpcloud.source.pcloud.PCloudDirectLoginClient
import dev.properpcloud.source.pcloud.PCloudSessionRevoker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ProperpcloudApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(
    application: Application,
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    disconnectedSource: AudioSource = DisconnectedAudioSource,
) {
    val preferences = AppPreferencesRepository(application)
    val tokenVault = EncryptedTokenVault(application)
    val serverCatalogVault = EncryptedServerCatalogVault(application)
    val sources = SourceRegistry(
        disconnectedSource = disconnectedSource,
        tokenVault = tokenVault,
        serverVault = serverCatalogVault,
    )
    val pCloudDirectLogin = PCloudDirectLoginClient()
    val pCloudSessionRevoker = PCloudSessionRevoker()
    val metadata = MetadataEditingWorkspace(
        context = application,
        tagToolkit = JAudioTaggerToolkit(),
        onlineProvider = MusicBrainzMetadataProvider(
            applicationName = "properpcloud",
            applicationVersion = BuildConfig.VERSION_NAME,
            contactUrl = "https://github.com/fkr-0/properpcloud",
        ),
    )
    val playback: PlaybackConnection by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackConnection(application)
    }
}
