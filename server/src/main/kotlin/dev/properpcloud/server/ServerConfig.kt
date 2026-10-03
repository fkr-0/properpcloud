package dev.properpcloud.server

import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path

data class ServerConfig(
    val bindHost: String,
    val port: Int,
    val publicBaseUrl: String,
    val mountRoot: Path,
    val database: Path,
    val pCloudSessionFile: Path?,
    val apiTokenFile: Path?,
) {
    init {
        require(port in 1..65535) { "invalid server port" }
        val loopbackBind = InetAddress.getByName(bindHost).isLoopbackAddress
        require(loopbackBind || apiTokenFile != null) { "non-loopback server bind requires an API token file" }
        val publicUri = URI(publicBaseUrl)
        require(publicUri.scheme == "https" || (publicUri.scheme == "http" && loopbackBind)) {
            "public server URL must use HTTPS unless the server is loopback-only"
        }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            home: Path = Path(System.getProperty("user.home")),
        ): ServerConfig {
            val dataHome = environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)?.let(::Path)
                ?: home.resolve(".local/share")
            val configHome = environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(::Path)
                ?: home.resolve(".config")
            val bind = environment["PROPERPCLOUD_SERVER_BIND"]?.takeIf(String::isNotBlank) ?: "127.0.0.1"
            val port = environment["PROPERPCLOUD_SERVER_PORT"]?.toIntOrNull() ?: 8787
            val defaultBase = "http://$bind:$port"
            return ServerConfig(
                bindHost = bind,
                port = port,
                publicBaseUrl = environment["PROPERPCLOUD_PUBLIC_BASE_URL"]?.trimEnd('/')?.takeIf(String::isNotBlank)
                    ?: defaultBase,
                mountRoot = environment["PROPERPCLOUD_MOUNT_ROOT"]?.takeIf(String::isNotBlank)?.let(::Path)
                    ?: dataHome.resolve("properpcloud/mount/pcloud"),
                database = environment["PROPERPCLOUD_LIBRARY_DB"]?.takeIf(String::isNotBlank)?.let(::Path)
                    ?: dataHome.resolve("properpcloud/library.db"),
                pCloudSessionFile = environment["PROPERPCLOUD_PCLOUD_SESSION_FILE"]?.takeIf(String::isNotBlank)?.let(::Path)
                    ?: configHome.resolve("properpcloud/server/pcloud-session.json"),
                apiTokenFile = environment["PROPERPCLOUD_SERVER_API_TOKEN_FILE"]?.takeIf(String::isNotBlank)?.let(::Path),
            )
        }
    }
}

data class PCloudServerSession(
    val accessToken: String,
    val apiHost: String,
    val userId: Long? = null,
) {
    init {
        require(accessToken.isNotBlank() && accessToken.length <= 4096) { "invalid pCloud access token" }
        require(apiHost in setOf("api.pcloud.com", "eapi.pcloud.com")) { "unsupported pCloud API host" }
        require(userId == null || userId >= 0) { "invalid pCloud user id" }
    }

    override fun toString(): String = "PCloudServerSession(accessToken=<redacted>, apiHost=$apiHost, userId=$userId)"
}

class SecretFile(private val path: Path) {
    fun readText(): String {
        require(Files.isRegularFile(path)) { "secret file is missing" }
        requirePrivatePermissions(path)
        val value = Files.readString(path).trim()
        require(value.isNotBlank()) { "secret file is empty" }
        require(value.length <= 16 * 1024) { "secret file is too large" }
        return value
    }

    private fun requirePrivatePermissions(path: Path) {
        runCatching { Files.getPosixFilePermissions(path) }.getOrNull()?.let { permissions ->
            val forbidden = setOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )
            require(permissions.intersect(forbidden).isEmpty()) { "secret file must be owner-only (0600 or stricter)" }
        }
    }
}

class PCloudSessionFile(private val path: Path) {
    @Volatile private var cachedModifiedMillis: Long = Long.MIN_VALUE
    @Volatile private var cached: PCloudServerSession? = null

    @Synchronized
    fun load(force: Boolean = false): PCloudServerSession {
        require(Files.isRegularFile(path)) { "pCloud session file is missing" }
        val modified = Files.getLastModifiedTime(path).toMillis()
        if (!force && modified == cachedModifiedMillis) return requireNotNull(cached)
        val json = JsonParser.parseString(SecretFile(path).readText()).asJsonObject
        val session = PCloudServerSession(
            accessToken = json.get("accessToken")?.asString ?: error("pCloud session file needs accessToken"),
            apiHost = json.get("apiHost")?.asString ?: error("pCloud session file needs apiHost"),
            userId = json.get("userId")?.takeUnless { it.isJsonNull }?.asLong,
        )
        cachedModifiedMillis = modified
        cached = session
        return session
    }
}
