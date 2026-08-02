package dev.properpcloud.app.auth

enum class PCloudClientIdSource {
    BUNDLED,
    CUSTOM,
    MISSING,
}

data class PCloudOAuthConfiguration(
    val clientId: String,
    val source: PCloudClientIdSource,
) {
    val isConfigured: Boolean = clientId.isNotBlank()
    val usesBundledClientId: Boolean = source == PCloudClientIdSource.BUNDLED
    val usesCustomClientId: Boolean = source == PCloudClientIdSource.CUSTOM

    companion object {
        fun resolve(bundledClientId: String, customClientId: String): PCloudOAuthConfiguration {
            val custom = customClientId.trim()
            if (custom.isNotEmpty()) {
                return PCloudOAuthConfiguration(custom, PCloudClientIdSource.CUSTOM)
            }

            val bundled = bundledClientId.trim()
            if (bundled.isNotEmpty()) {
                return PCloudOAuthConfiguration(bundled, PCloudClientIdSource.BUNDLED)
            }

            return PCloudOAuthConfiguration("", PCloudClientIdSource.MISSING)
        }

        fun redirectUri(applicationId: String): String = "pcloud-oauth://${applicationId.trim()}"
    }
}
