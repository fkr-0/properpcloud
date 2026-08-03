package dev.properpcloud.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class MpvControllerTest {
    @Test
    fun `encodes arguments as one JSON IPC command`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals(
            "{\"command\":[\"seek\",15.0,\"relative+exact\"],\"request_id\":7}\n",
            controller.commandJson(listOf("seek", 15.0, "relative+exact"), 7),
        )
    }

    @Test
    fun `ignores events and unmatched command responses`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))

        assertNull(controller.responseForRequest("{\"event\":\"file-loaded\"}", 7))
        assertNull(controller.responseForRequest("{\"request_id\":6,\"error\":\"success\",\"data\":1}", 7))
        assertEquals(
            42.5,
            requireNotNull(
                controller.responseForRequest("{\"request_id\":7,\"error\":\"success\",\"data\":42.5}", 7)
                    ?.get("data")?.asDouble,
            ),
            0.0,
        )
    }

    @Test
    fun `reports command failure without returning provider or stream details`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))

        val error = assertThrows(IllegalStateException::class.java) {
            controller.responseForRequest(
                "{\"request_id\":7,\"error\":\"loading failed\",\"data\":\"https://secret.invalid\"}",
                7,
            )
        }
        assertEquals("mpv command failed", error.message)
    }

    @Test
    fun `unexpected process exit requires an explicit manual restart`() {
        val failed = mpvExitState(MpvState(running = true, paused = false, positionMillis = 12_000), expected = false)
        assertFalse(failed.running)
        assertTrue(failed.paused)
        assertTrue(failed.unexpectedExit)
        assertTrue(failed.restartAvailable)
        assertEquals("mpv exited unexpectedly", failed.error)

        val closed = mpvExitState(failed, expected = true)
        assertFalse(closed.unexpectedExit)
        assertFalse(closed.restartAvailable)
        assertNull(closed.error)
    }
}
