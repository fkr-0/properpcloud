package dev.properpcloud.app

import android.app.Application
import dev.properpcloud.app.data.AppPreferencesRepository
import dev.properpcloud.app.data.DemoAudioSource
import dev.properpcloud.app.data.SourceRegistry
import dev.properpcloud.app.metadata.MetadataEditingWorkspace
import dev.properpcloud.app.playback.PlaybackConnection
import dev.properpcloud.app.security.EncryptedTokenVault
import dev.properpcloud.metadata.online.MusicBrainzMetadataProvider
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit

class ProperpcloudApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val preferences = AppPreferencesRepository(application)
    val tokenVault = EncryptedTokenVault(application)
    val sources = SourceRegistry(
        demoSource = DemoAudioSource(application),
        tokenVault = tokenVault,
    )
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
