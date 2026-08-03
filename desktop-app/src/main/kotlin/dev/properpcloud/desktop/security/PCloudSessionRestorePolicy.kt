package dev.properpcloud.desktop.security

internal object PCloudSessionRestorePolicy {
    const val SETTING_KEY = "pcloud-session-state"
    const val ACTIVE = "active"
    const val DISCONNECTED = "disconnected"

    fun permitsRestore(persistedState: String?): Boolean = persistedState != DISCONNECTED
}
