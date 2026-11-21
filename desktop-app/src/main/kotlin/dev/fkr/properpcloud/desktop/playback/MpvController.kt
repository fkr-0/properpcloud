package dev.fkr.properpcloud.desktop.playback

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.fkr.properpcloud.core.model.StreamHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class MpvFailureKind {
    NONE,
    NO_AUDIO,
    UNSUPPORTED_CODEC,
    INVALID_MEDIA,
    TRANSIENT_NETWORK,
    UNKNOWN,
}

data class MpvState(
    val running: Boolean = false,
    val paused: Boolean = true,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val idle: Boolean = true,
    val eofReached: Boolean = false,
    val error: String? = null,
    val unexpectedExit: Boolean = false,
    val restartAvailable: Boolean = false,
    val streamFailure: Boolean = false,
    val resumeAfterRestart: Boolean = false,
    val failureKind: MpvFailureKind = MpvFailureKind.NONE,
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
    private var eventReader: Job? = null
    private val pendingLoad = AtomicReference<PendingMpvLoad?>(null)
    private val loadGeneration = AtomicLong(0)
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
        loadGeneration.incrementAndGet()
        pendingLoad.getAndSet(null)?.ready?.cancel(CancellationException("mpv process restarted"))
        polling?.cancel()
        eventReader?.cancel()
        terminate(current)
        Files.createDirectories(runtimeDirectory)
        runCatching { Files.deleteIfExists(socketPath) }
        val started = ProcessBuilder(buildList {
            add(executable)
            add("--no-config")
            add("--idle=yes")
            add("--terminal=no")
            add("--audio-display=no")
            add("--force-window=no")
            // This application is an audio player. Never create a second video surface or
            // decode an incidental video stream from an audio-bearing container.
            add("--vid=no")
            add("--input-ipc-server=${socketPath.toAbsolutePath()}")
            addAll(extraArguments)
        })
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // mpv is controlled exclusively through JSON IPC. Closing the inherited stdin pipe here
        // avoids retaining one parent-side file descriptor for every process generation.
        runCatching { started.outputStream.close() }
        process = started
        for (attempt in 0 until 100) {
            if (Files.exists(socketPath)) break
            if (process?.isAlive != true) error("mpv exited before its IPC socket became ready")
            Thread.sleep(25)
        }
        check(Files.exists(socketPath)) { "mpv IPC socket did not become ready" }
        mutableState.value = MpvState(running = true)
        val monitoredProcess = requireNotNull(process)
        val connected = CompletableDeferred<Boolean>()
        eventReader = scope.launch(Dispatchers.IO) { readEvents(monitoredProcess, generation, connected) }
        check(withTimeoutOrNull(EVENT_CONNECT_TIMEOUT_MILLIS) { connected.await() } == true) {
            "mpv event channel did not become ready"
        }
        polling = scope.launch(Dispatchers.IO) { pollState(monitoredProcess, generation) }
    }

    suspend fun reloadAudioOutput() {
        ensureStarted()
        command(listOf("ao-reload"))
    }

    suspend fun load(handle: StreamHandle, resumeMillis: Long = 0, play: Boolean = true) {
        ensureStarted()
        require(handle.url.startsWith("https://") || handle.url.startsWith("file:")) { "unsupported playback URL scheme" }
        val generation = loadGeneration.incrementAndGet()
        val pending = PendingMpvLoad(generation)
        pendingLoad.getAndSet(pending)?.ready?.cancel(CancellationException("superseded by a newer load"))
        expectedIdle.set(false)
        mutableState.value = mutableState.value.copy(
            idle = false,
            eofReached = false,
            error = null,
            unexpectedExit = false,
            restartAvailable = false,
            streamFailure = false,
            resumeAfterRestart = false,
            failureKind = MpvFailureKind.NONE,
        )
        try {
            val loadReply = command(listOf("loadfile", handle.url, "replace"))
            val entryId = loadReply?.get("data")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("playlist_entry_id")?.takeUnless { it.isJsonNull }?.asLong
                ?: throw MpvPlaybackException(MpvFailureKind.UNKNOWN)
            pending.gate.expectEntryId(entryId)
            val loaded = withTimeoutOrNull(LOAD_READY_TIMEOUT_MILLIS) {
                pending.ready.await()
                true
            } ?: false
            if (generation != loadGeneration.get()) throw CancellationException("superseded media load")
            if (!loaded) {
                // A timeout alone does not prove a temporary provider failure. Only an
                // explicit redacted mpv network diagnostic may trigger signed-link refresh.
                throw MpvPlaybackException(MpvFailureKind.UNKNOWN)
            }
            val tracks = command(listOf("get_property", "track-list"))
                ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            val hasAudio = mpvTrackListHasAudio(tracks)
            if (!hasAudio) {
                val failure = MpvPlaybackException(MpvFailureKind.NO_AUDIO)
                mutableState.value = mutableState.value.copy(
                    idle = true,
                    paused = true,
                    error = failure.message,
                    streamFailure = true,
                    restartAvailable = false,
                    failureKind = failure.kind,
                )
                expectedIdle.set(true)
                runCatching { command(listOf("stop")) }
                throw failure
            }
            if (resumeMillis > 0) command(listOf("seek", resumeMillis / 1_000.0, "absolute+exact"))
            command(listOf("set_property", "pause", !play))
            mutableState.value = mutableState.value.copy(
                idle = false,
                paused = !play,
                eofReached = false,
                error = null,
                unexpectedExit = false,
                restartAvailable = false,
                streamFailure = false,
                resumeAfterRestart = false,
                failureKind = MpvFailureKind.NONE,
            )
        } finally {
            pendingLoad.compareAndSet(pending, null)
        }
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
        require(speed in 0.5f..3f)
        ensureStarted()
        command(listOf("set_property", "speed", speed))
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    suspend fun setVolume(volume: Float) {
        require(volume in 0f..1f)
        ensureStarted()
        command(listOf("set_property", "volume", volume * 100f))
        mutableState.value = mutableState.value.copy(volume = volume)
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

    private suspend fun readEvents(monitoredProcess: Process, generation: Long, connected: CompletableDeferred<Boolean>) {
        var channel: SocketChannel? = null
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.configureBlocking(false)
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!channel.finishConnect()) {
                check(System.nanoTime() < deadline) { "timed out connecting to mpv events" }
                delay(5)
            }
            connected.complete(true)
            val buffer = ByteBuffer.allocate(16_384)
            val line = ByteArrayOutputStream()
            while (scope.isActive && monitoredProcess.isAlive && generation == processGeneration.get()) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    delay(10)
                    continue
                }
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val byte = buffer.get()
                    if (byte == '\n'.code.toByte()) {
                        val eventLine = line.toByteArray().toString(StandardCharsets.UTF_8)
                        line.reset()
                        handleEvent(eventLine, generation)
                    } else {
                        if (line.size() < MAX_EVENT_LINE_BYTES) line.write(byte.toInt())
                        else line.reset()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            connected.complete(false)
        } finally {
            runCatching { channel?.close() }
        }
    }

    private fun handleEvent(line: String, processGeneration: Long) {
        if (processGeneration != this.processGeneration.get()) return
        val event = runCatching { gson.fromJson(line, JsonObject::class.java) }.getOrNull() ?: return
        when (event.get("event")?.takeUnless { it.isJsonNull }?.asString) {
            "start-file" -> pendingLoad.get()?.takeIf { it.generation == loadGeneration.get() }
                ?.gate?.acceptStart(event.get("playlist_entry_id")?.takeUnless { it.isJsonNull }?.asLong)
            "file-loaded" -> pendingLoad.get()?.let { pending ->
                if (pending.generation == loadGeneration.get() && pending.gate.acceptFileLoaded()) {
                    pending.ready.complete(Unit)
                }
            }
            "end-file" -> {
                val reason = event.get("reason")?.takeUnless { it.isJsonNull }?.asString
                if (reason == "error") {
                    val diagnostic = event.get("file_error")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    val kind = classifyMpvFailure(diagnostic)
                    val failure = MpvPlaybackException(kind)
                    pendingLoad.get()?.takeIf { it.generation == loadGeneration.get() }?.let {
                        it.ready.completeExceptionally(failure)
                    }
                    mutableState.value = mutableState.value.copy(
                        paused = true,
                        idle = true,
                        error = failure.message,
                        streamFailure = true,
                        restartAvailable = kind == MpvFailureKind.TRANSIENT_NETWORK,
                        failureKind = kind,
                    )
                }
            }
        }
    }

    private suspend fun pollState(monitoredProcess: Process, generation: Long) {
        while (scope.isActive && monitoredProcess.isAlive && generation == processGeneration.get()) {
            runCatching {
                val position = propertyDouble("time-pos")?.times(1_000)?.toLong() ?: 0
                val duration = propertyDouble("duration")?.times(1_000)?.toLong()
                val paused = propertyBoolean("pause") ?: true
                val idle = propertyBoolean("idle-active") ?: true
                val eofReached = propertyBoolean("eof-reached") ?: false
                val speed = propertyDouble("speed")?.toFloat()?.coerceIn(0.5f, 3f) ?: mutableState.value.speed
                val volume = propertyDouble("volume")?.toFloat()?.div(100f)?.coerceIn(0f, 1f) ?: mutableState.value.volume
                mutableState.value = mpvPlaybackState(
                    previous = mutableState.value,
                    paused = paused,
                    positionMillis = position,
                    durationMillis = duration,
                    idle = idle,
                    eofReached = eofReached,
                    expectedIdle = expectedIdle.get(),
                    speed = speed,
                    volume = volume,
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
        eventReader?.cancel()
        pendingLoad.getAndSet(null)?.ready?.cancel(CancellationException("mpv closed"))
        terminate(process)
        process = null
        polling = null
        eventReader = null
        Files.deleteIfExists(socketPath)
    }

    private fun terminate(running: Process?) {
        if (running == null) return
        runCatching { running.outputStream.close() }
        runCatching { if (running.isAlive) running.destroy() }
        runCatching {
            if (!running.waitFor(2, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }
}

internal data class PendingMpvLoad(
    val generation: Long,
    val gate: MpvLoadEventGate = MpvLoadEventGate(),
    val ready: CompletableDeferred<Unit> = CompletableDeferred(),
)

internal class MpvLoadEventGate {
    private var expectedEntryId: Long? = null
    private var earlyStartEntryId: Long? = null
    private var startedEntryId: Long? = null

    /** The loadfile reply is authoritative for the playlist entry created by this load. */
    @Synchronized
    fun expectEntryId(entryId: Long) {
        expectedEntryId = entryId
        if (earlyStartEntryId == entryId) startedEntryId = entryId
        earlyStartEntryId = null
    }

    /** start-file carries the playlist_entry_id; stale or unrelated entries are rejected. */
    @Synchronized
    fun acceptStart(entryId: Long?): Boolean {
        if (entryId == null) return false
        val expected = expectedEntryId
        if (expected == null) {
            earlyStartEntryId = entryId
            return false
        }
        if (entryId != expected) return false
        startedEntryId = entryId
        return true
    }

    /** file-loaded has no entry ID in mpv IPC; it follows the matching start-file event. */
    @Synchronized
    fun acceptFileLoaded(): Boolean =
        expectedEntryId != null && startedEntryId == expectedEntryId
}

internal class MpvPlaybackException(val kind: MpvFailureKind) : IllegalStateException(
    when (kind) {
        MpvFailureKind.NO_AUDIO -> "media has no audio stream"
        MpvFailureKind.UNSUPPORTED_CODEC -> "unsupported or unavailable audio codec"
        MpvFailureKind.INVALID_MEDIA -> "invalid or unsupported media container"
        MpvFailureKind.TRANSIENT_NETWORK -> "temporary network playback failure"
        else -> "mpv playback failed"
    },
)

internal fun mpvTrackListHasAudio(tracks: JsonArray?): Boolean = tracks?.any { item ->
    item.isJsonObject && item.asJsonObject.get("type")?.takeUnless { it.isJsonNull }?.asString == "audio"
} == true

internal fun classifyMpvFailure(diagnostic: String): MpvFailureKind {
    val value = diagnostic.lowercase()
    return when {
        "no audio" in value || "audio stream" in value && ("missing" in value || "not found" in value) ->
            MpvFailureKind.NO_AUDIO
        listOf("codec", "decoder", "unsupported", "not supported", "failed to open decoder").any(value::contains) ->
            MpvFailureKind.UNSUPPORTED_CODEC
        listOf("network", "http", "connection", "timeout", "timed out", "host not found", "unreachable", "refused", "temporarily unavailable").any(value::contains) ->
            MpvFailureKind.TRANSIENT_NETWORK
        listOf("demux", "invalid", "corrupt", "malformed", "format", "unrecognized", "not a media file").any(value::contains) ->
            MpvFailureKind.INVALID_MEDIA
        else -> MpvFailureKind.UNKNOWN
    }
}

private const val EVENT_CONNECT_TIMEOUT_MILLIS = 3_000L
private const val LOAD_READY_TIMEOUT_MILLIS = 45_000L
private const val MAX_EVENT_LINE_BYTES = 65_536

internal fun mpvExitState(previous: MpvState, expected: Boolean): MpvState =
    previous.copy(
        running = false,
        paused = true,
        error = if (expected) null else "mpv exited unexpectedly",
        unexpectedExit = !expected,
        restartAvailable = !expected,
        streamFailure = false,
        resumeAfterRestart = !expected && previous.running && !previous.paused && !previous.idle,
        failureKind = if (expected) MpvFailureKind.NONE else previous.failureKind,
    )

internal fun mpvPlaybackState(
    previous: MpvState,
    paused: Boolean,
    positionMillis: Long,
    durationMillis: Long?,
    idle: Boolean,
    eofReached: Boolean,
    expectedIdle: Boolean,
    speed: Float = previous.speed,
    volume: Float = previous.volume,
): MpvState {
    val failed = previous.running && !previous.idle && idle && !eofReached && !expectedIdle
    val recoveryPending = previous.restartAvailable && idle && !expectedIdle
    val knownFailure = previous.failureKind != MpvFailureKind.NONE && idle && !expectedIdle
    val failureVisible = failed || recoveryPending || knownFailure
    return MpvState(
        running = true,
        paused = paused,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        speed = speed.coerceIn(0.5f, 3f),
        volume = volume.coerceIn(0f, 1f),
        idle = idle,
        eofReached = eofReached,
        error = if (failureVisible) previous.error ?: "mpv playback failed" else null,
        unexpectedExit = false,
        restartAvailable = failed || recoveryPending,
        streamFailure = failed,
        resumeAfterRestart = false,
        failureKind = if (failureVisible) previous.failureKind else MpvFailureKind.NONE,
    )
}

private fun ByteArray.indexOf(value: Byte, fromIndex: Int, toIndex: Int): Int {
    for (index in fromIndex until toIndex) if (this[index] == value) return index
    return -1
}
