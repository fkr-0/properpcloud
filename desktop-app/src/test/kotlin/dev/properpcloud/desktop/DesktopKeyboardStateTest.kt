package dev.properpcloud.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopKeyboardStateTest {
    @Test
    fun `selection remains bounded and handles empty lists`() {
        assertEquals(-1, moveSelection(0, 1, 0))
        assertEquals(0, moveSelection(-1, 0, 3))
        assertEquals(2, moveSelection(2, 1, 3))
        assertEquals(0, moveSelection(0, -1, 3))
    }
}
