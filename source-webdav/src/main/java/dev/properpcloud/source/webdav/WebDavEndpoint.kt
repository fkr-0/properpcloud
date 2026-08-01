package dev.properpcloud.source.webdav

import java.net.URI

data class WebDavEndpoint(
    val baseUrl: String,
    val username: String,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https") { "WebDAV endpoint must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "WebDAV endpoint must have a host" }
        require(username.isNotBlank()) { "username must not be blank" }
    }
}

enum class PCloudDataRegion(val webDavBaseUrl: String) {
    EUROPE("https://ewebdav.pcloud.com"),
    UNITED_STATES("https://webdav.pcloud.com"),
}
