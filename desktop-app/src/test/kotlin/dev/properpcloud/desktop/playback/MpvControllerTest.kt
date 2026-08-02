package dev.properpcloud.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class MpvControllerTest {
    @Test
    fun `encodes arguments as one JSON IPC command`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals("{\"command\":[\"seek\",15.0,\"relative+exact\"]}\n", controller.commandJson(listOf("seek", 15.0, "relative+exact")))
    }
}
