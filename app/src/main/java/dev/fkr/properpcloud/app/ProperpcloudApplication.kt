@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.fkr.properpcloud.app

import android.app.Application
import dev.fkr.properpcloud.app.data.AppPreferencesRepository
import dev.fkr.properpcloud.app.data.DisconnectedAudioSource
import dev.fkr.properpcloud.app.data.SourceRegistry
import dev.fkr.properpcloud.app.metadata.MetadataEditingWorkspace
import dev.fkr.properpcloud.app.playback.PlaybackConnection
import dev.fkr.properpcloud.app.security.EncryptedTokenVault
import dev.fkr.properpcloud.app.security.EncryptedServerCatalogVault
import dev.fkr.properpcloud.core.model.AudioSource
import dev.fkr.properpcloud.metadata.online.MusicBrainzMetadataProvider
import dev.fkr.properpcloud.metadata.tags.JAudioTaggerToolkit
import dev.fkr.properpcloud.source.pcloud.PCloudDirectLoginClient
import dev.fkr.properpcloud.source.pcloud.PCloudSessionRevoker
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
