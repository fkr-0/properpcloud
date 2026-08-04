package dev.properpcloud.desktop

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopShortcutsTest {
    @Test
    fun `global focus and playback shortcuts are deterministic`() {
        assertEquals(
            DesktopShortcut.FocusQueue,
            resolveDesktopShortcut(Key.Q, DesktopFocusTarget.LIBRARY, ctrl = true),
        )
        assertEquals(
            DesktopShortcut.FocusLibrary,
            resolveDesktopShortcut(Key.L, DesktopFocusTarget.QUEUE, ctrl = true),
        )
        assertEquals(
            DesktopShortcut.PlayPause,
            resolveDesktopShortcut(Key.Spacebar, DesktopFocusTarget.LIBRARY),
        )
    }

    @Test
    fun `queue has complete non drag keyboard operations`() {
        assertEquals(
            DesktopShortcut.SelectQueue(1),
            resolveDesktopShortcut(Key.DirectionDown, DesktopFocusTarget.QUEUE),
        )
        assertEquals(
            DesktopShortcut.MoveQueueSelection(-1),
            resolveDesktopShortcut(Key.DirectionUp, DesktopFocusTarget.QUEUE, alt = true),
        )
        assertEquals(
            DesktopShortcut.RemoveQueueSelection,
            resolveDesktopShortcut(Key.Delete, DesktopFocusTarget.QUEUE),
        )
        assertEquals(
            DesktopShortcut.PlayQueueSelection,
            resolveDesktopShortcut(Key.Enter, DesktopFocusTarget.QUEUE),
        )
    }

    @Test
    fun `library modifiers expose play append and inspect alternatives`() {
        assertEquals(
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.OPEN_OR_PLAY),
            resolveDesktopShortcut(Key.Enter, DesktopFocusTarget.LIBRARY),
        )
        assertEquals(
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.APPEND),
            resolveDesktopShortcut(Key.Enter, DesktopFocusTarget.LIBRARY, shift = true),
        )
        assertEquals(
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.PLAY_REPLACE),
            resolveDesktopShortcut(Key.Enter, DesktopFocusTarget.LIBRARY, ctrl = true),
        )
        assertEquals(
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.INSPECT),
            resolveDesktopShortcut(Key.Enter, DesktopFocusTarget.LIBRARY, alt = true),
        )
    }

    @Test
    fun `modal content suppresses global playback shortcuts`() {
        assertNull(resolveDesktopShortcut(Key.Spacebar, DesktopFocusTarget.LIBRARY, modalOpen = true))
        assertNull(resolveDesktopShortcut(Key.Q, DesktopFocusTarget.LIBRARY, modalOpen = true, ctrl = true))
    }
}
