package dev.properpcloud.desktop.playback

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.properpcloud.core.model.StreamHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class MpvState(
    val running: Boolean = false,
    val paused: Boolean = true,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val idle: Boolean = true,
    val error: String? = null,
    val unexpectedExit: Boolean = false,
    val restartAvailable: Boolean = false,
    val streamFailure: Boolean = false,
)

class MpvController(
    private val runtimeDirectory: Path,
    private val scope: CoroutineScope,
    private val executable: String = "mpv",
    private val extraArguments: List<String> = emptyList(),
) : AutoCloseable {
    private val gson = Gson()
    private val socketPath = runtimeDirectory.resolve("mpv-${ProcessHandle.current().pid()}.sock")
    private var process: Process? = null
    private var polling: Job? = null
    private val closing = AtomicBoolean(false)
    private val expectedIdle = AtomicBoolean(true)
    private val processGeneration = AtomicLong(0)
    private val requestIds = AtomicLong(0)
    private val commandLock = Any()
    private val mutableState = MutableStateFlow(MpvState())
    val state: StateFlow<MpvState> = mutableState.asStateFlow()

    suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        val current = process
        if (current?.isAlive == true && Files.exists(socketPath)) return@withContext
        closing.set(false)
        val generation = processGeneration.incrementAndGet()
        polling?.cancel()
        current?.destroyForcibly()
        Files.createDirectories(runtimeDirectory)
        runCatching { Files.deleteIfExists(socketPath) }
        process = ProcessBuilder(buildList {
            add(executable)
            add("--no-config")
            add("--idle=yes")
            add("--terminal=no")
            add("--audio-display=no")
            add("--force-window=no")
            add("--input-ipc-server=${socketPath.toAbsolutePath()}")
            addAll(extraArguments)
        })
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        for (attempt in 0 until 100) {
            if (Files.exists(socketPath)) break
            if (process?.isAlive != true) error("mpv exited before its IPC socket became ready")
            Thread.sleep(25)
        }
        check(Files.exists(socketPath)) { "mpv IPC socket did not become ready" }
        mutableState.value = MpvState(running = true)
        val monitoredProcess = requireNotNull(process)
        polling = scope.launch(Dispatchers.IO) { pollState(monitoredProcess, generation) }
    }

    suspend fun load(handle: StreamHandle, resumeMillis: Long = 0) {
        ensureStarted()
        require(handle.url.startsWith("https://") || handle.url.startsWith("file:")) { "unsupported playback URL scheme" }
        expectedIdle.set(true)
        command(listOf("loadfile", handle.url, "replace"))
        if (resumeMillis > 0) {
            delay(80)
            command(listOf("seek", resumeMillis / 1_000.0, "absolute+exact"))
        }
        command(listOf("set_property", "pause", false))
        expectedIdle.set(false)
        mutableState.value = mutableState.value.copy(
            idle = false,
            error = null,
            unexpectedExit = false,
            restartAvailable = false,
            streamFailure = false,
        )
    }

    suspend fun togglePause() {
        ensureStarted()
        command(listOf("cycle", "pause"))
    }

    suspend fun pause(value: Boolean) {
        ensureStarted()
        command(listOf("set_property", "pause", value))
    }

    suspend fun seekRelative(milliseconds: Long) {
        ensureStarted()
        command(listOf("seek", milliseconds / 1_000.0, "relative+exact"))
    }

    suspend fun seekAbsolute(milliseconds: Long) {
        ensureStarted()
        command(listOf("seek", milliseconds / 1_000.0, "absolute+exact"))
    }

    suspend fun setSpeed(speed: Float) {
        require(speed in 0.5f..4f)
        ensureStarted()
        command(listOf("set_property", "speed", speed))
    }

    suspend fun stop() {
        expectedIdle.set(true)
        if (process?.isAlive == true) command(listOf("stop"))
    }

    internal fun terminateProcessForSmoke() {
        process?.takeIf { it.isAlive }?.destroyForcibly()
    }

    internal fun commandJson(arguments: List<Any?>, requestId: Long): String =
        gson.toJson(mapOf("command" to arguments, "request_id" to requestId)) + "\n"

    internal fun responseForRequest(line: String, requestId: Long): JsonObject? {
        val response = gson.fromJson(line, JsonObject::class.java)
        val responseId = response.get("request_id")?.takeUnless { it.isJsonNull }?.asLong ?: return null
        if (responseId != requestId) return null
        check(response.get("error")?.asString == "success") { "mpv command failed" }
        return response
    }

    private suspend fun pollState(monitoredProcess: Process, generation: Long) {
        while (scope.isActive && monitoredProcess.isAlive && generation == processGeneration.get()) {
            runCatching {
                val position = propertyDouble("time-pos")?.times(1_000)?.toLong() ?: 0
                val duration = propertyDouble("duration")?.times(1_000)?.toLong()
                val paused = propertyBoolean("pause") ?: true
                val idle = propertyBoolean("idle-active") ?: true
                val eofReached = propertyBoolean("eof-reached") ?: false
                mutableState.value = mpvPlaybackState(
                    previous = mutableState.value,
                    paused = paused,
                    positionMillis = position,
                    durationMillis = duration,
                    idle = idle,
                    eofReached = eofReached,
                    expectedIdle = expectedIdle.get(),
                )
            }.onFailure {
                mutableState.value = mutableState.value.copy(error = "mpv IPC became unavailable")
            }
            delay(500)
        }
        if (generation == processGeneration.get()) {
            mutableState.value = mpvExitState(mutableState.value, expected = closing.get())
        }
    }

    private fun propertyDouble(name: String): Double? = command(listOf("get_property", name))
        ?.get("data")?.takeUnless { it.isJsonNull }?.asDouble

    private fun propertyBoolean(name: String): Boolean? = command(listOf("get_property", name))
        ?.get("data")?.takeUnless { it.isJsonNull }?.asBoolean

    private fun command(arguments: List<Any?>): JsonObject? = synchronized(commandLock) {
        val requestId = requestIds.incrementAndGet()
        val address = UnixDomainSocketAddress.of(socketPath)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            channel.connect(address)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!channel.finishConnect()) {
                check(System.nanoTime() < deadline) { "timed out connecting to mpv" }
                Thread.sleep(5)
            }
            val request = ByteBuffer.wrap(commandJson(arguments, requestId).toByteArray(StandardCharsets.UTF_8))
            while (request.hasRemaining()) {
                check(System.nanoTime() < deadline) { "timed out writing to mpv" }
                if (channel.write(request) == 0) Thread.sleep(5)
            }
            val bytes = ByteArray(65_536)
            val buffer = ByteBuffer.wrap(bytes)
            var scanFrom = 0
            while (System.nanoTime() < deadline) {
                val read = channel.read(buffer)
                if (read < 0) break
                val length = buffer.position()
                var newline = bytes.indexOf('\n'.code.toByte(), scanFrom, length)
                while (newline >= 0) {
                    val line = String(bytes, scanFrom, newline - scanFrom, StandardCharsets.UTF_8)
                    responseForRequest(line, requestId)?.let { return it }
                    scanFrom = newline + 1
                    newline = bytes.indexOf('\n'.code.toByte(), scanFrom, length)
                }
                check(buffer.hasRemaining()) { "mpv response exceeded limit" }
                if (read == 0) Thread.sleep(5)
            }
            error("timed out waiting for mpv response")
        }
    }

    override fun close() {
        closing.set(true)
        processGeneration.incrementAndGet()
        polling?.cancel()
        process?.let { running ->
            runCatching { if (running.isAlive) running.destroy() }
            runCatching { if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly() }
        }
        Files.deleteIfExists(socketPath)
    }
}

internal fun mpvExitState(previous: MpvState, expected: Boolean): MpvState =
    previous.copy(
        running = false,
        paused = true,
        error = if (expected) null else "mpv exited unexpectedly",
        unexpectedExit = !expected,
        restartAvailable = !expected,
        streamFailure = false,
    )

internal fun mpvPlaybackState(
    previous: MpvState,
    paused: Boolean,
    positionMillis: Long,
    durationMillis: Long?,
    idle: Boolean,
    eofReached: Boolean,
    expectedIdle: Boolean,
): MpvState {
    val failed = previous.running && !previous.idle && idle && !eofReached && !expectedIdle
    val recoveryPending = previous.restartAvailable && idle && !expectedIdle
    val failureVisible = failed || recoveryPending
    return MpvState(
        running = true,
        paused = paused,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        idle = idle,
        error = if (failureVisible) previous.error ?: "mpv playback failed" else null,
        unexpectedExit = false,
        restartAvailable = failureVisible,
        streamFailure = failed,
    )
}

private fun ByteArray.indexOf(value: Byte, fromIndex: Int, toIndex: Int): Int {
    for (index in fromIndex until toIndex) if (this[index] == value) return index
    return -1
}
