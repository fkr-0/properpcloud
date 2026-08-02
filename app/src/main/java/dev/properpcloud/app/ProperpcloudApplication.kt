package dev.properpcloud.app

import android.app.Application
import dev.properpcloud.app.data.AppPreferencesRepository
import dev.properpcloud.app.data.DemoAudioSource
import dev.properpcloud.app.data.SourceRegistry
import dev.properpcloud.app.playback.PlaybackConnection
import dev.properpcloud.app.security.EncryptedTokenVault

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
    val playback: PlaybackConnection by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackConnection(application)
    }
}
