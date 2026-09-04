package dev.properpcloud.server

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = ServerCli.run(args)
    if (exitCode != 0) exitProcess(exitCode)
}

object ServerCli {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun run(args: Array<String>, environment: Map<String, String> = System.getenv()): Int {
        val command = args.toList()
        return try {
            when {
                command.isEmpty() || command.first() == "serve" -> serve(environment)
                command.take(2) == listOf("library", "scan") -> withRuntime(environment) { runtime ->
                    println(gson.toJson(runtime.scanner.scan()))
                    0
                }
                command.take(2) == listOf("library", "status") -> withRuntime(environment) { runtime ->
                    println(gson.toJson(runtime.repository.status()))
                    0
                }
                command.take(2) == listOf("library", "search") -> {
                    val query = command.drop(2).joinToString(" ").trim()
                    require(query.isNotBlank()) { "usage: properpcloud library search <query>" }
                    withRuntime(environment) { runtime ->
                        println(gson.toJson(runtime.repository.search(query)))
                        0
                    }
                }
                else -> {
                    System.err.println("usage: properpcloud-server [serve | library scan | library status | library search <query>]")
                    2
                }
            }
        } catch (error: Throwable) {
            System.err.println("properpcloud server command failed: ${error::class.simpleName}")
            1
        }
    }

    private fun serve(environment: Map<String, String>): Int = withRuntime(environment) { runtime ->
        val server = LibraryHttpServer(runtime.config, runtime.repository, runtime.scanner, runtime.pCloud)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        server.start()
        if (environment["PROPERPCLOUD_SCAN_ON_START"] != "0") server.requestBackgroundScan()
        val intervalMinutes = environment["PROPERPCLOUD_SCAN_INTERVAL_MINUTES"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
        server.scheduleScans(Duration.ofMinutes(intervalMinutes))
        println("properpcloud server listening on ${runtime.config.bindHost}:${runtime.config.port}")
        CountDownLatch(1).await()
        0
    }

    private fun <T> withRuntime(environment: Map<String, String>, block: (ServerRuntime) -> T): T {
        val runtime = ServerRuntime.open(environment)
        return runtime.use(block)
    }
}

data class ServerRuntime(
    val config: ServerConfig,
    val repository: CatalogRepository,
    val pCloud: PCloudRestClient?,
    val scanner: LibraryScanner,
) : AutoCloseable {
    override fun close() = repository.close()

    companion object {
        fun open(environment: Map<String, String> = System.getenv()): ServerRuntime {
            val config = ServerConfig.fromEnvironment(environment)
            val repository = CatalogRepository(config.database)
            val pCloud = config.pCloudSessionFile
                ?.takeIf(Files::isRegularFile)
                ?.let { PCloudRestClient(PCloudSessionFile(it)) }
            return ServerRuntime(
                config = config,
                repository = repository,
                pCloud = pCloud,
                scanner = LibraryScanner(config.mountRoot, repository, pCloud),
            )
        }
    }
}
