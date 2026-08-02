package dev.properpcloud.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

data class XdgPaths(
    val config: Path,
    val data: Path,
    val cache: Path,
    val runtime: Path,
) {
    fun create(): XdgPaths = apply {
        listOf(config, data, cache, runtime).forEach(Files::createDirectories)
        runCatching {
            Files.setPosixFilePermissions(runtime, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ))
        }
    }

    companion object {
        fun resolve(
            environment: Map<String, String> = System.getenv(),
            home: Path = Path(System.getProperty("user.home")),
        ): XdgPaths {
            fun envPath(name: String, fallback: Path): Path =
                environment[name]?.takeIf(String::isNotBlank)?.let(::Path) ?: fallback

            val configHome = envPath("XDG_CONFIG_HOME", home.resolve(".config"))
            val dataHome = envPath("XDG_DATA_HOME", home.resolve(".local/share"))
            val cacheHome = envPath("XDG_CACHE_HOME", home.resolve(".cache"))
            val runtimeHome = environment["XDG_RUNTIME_DIR"]
                ?.takeIf(String::isNotBlank)
                ?.let(::Path)
                ?: Path(System.getProperty("java.io.tmpdir")).resolve("properpcloud-${System.getProperty("user.name")}")
            return XdgPaths(
                config = configHome.resolve("properpcloud"),
                data = dataHome.resolve("properpcloud"),
                cache = cacheHome.resolve("properpcloud"),
                runtime = runtimeHome.resolve("properpcloud"),
            )
        }
    }
}
