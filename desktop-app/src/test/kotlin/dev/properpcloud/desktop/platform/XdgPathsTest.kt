package dev.properpcloud.desktop.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.Path

class XdgPathsTest {
    @Test
    fun `uses XDG roots and appends application directory`() {
        val paths = XdgPaths.resolve(
            environment = mapOf(
                "XDG_CONFIG_HOME" to "/x/config",
                "XDG_DATA_HOME" to "/x/data",
                "XDG_CACHE_HOME" to "/x/cache",
                "XDG_RUNTIME_DIR" to "/x/run",
            ),
            home = Path("/home/test"),
        )
        assertEquals(Path("/x/config/properpcloud"), paths.config)
        assertEquals(Path("/x/data/properpcloud"), paths.data)
        assertEquals(Path("/x/cache/properpcloud"), paths.cache)
        assertEquals(Path("/x/run/properpcloud"), paths.runtime)
    }
}
