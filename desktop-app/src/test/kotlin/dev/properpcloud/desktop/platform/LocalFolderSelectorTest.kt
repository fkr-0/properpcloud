package dev.properpcloud.desktop.platform

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFolderSelectorTest {
    @Test
    fun `flatpak environment fails closed before opening a native chooser`() {
        val selection = NativeLocalFolderSelector(
            environment = mapOf("FLATPAK_ID" to "dev.properpcloud.app"),
            flatpakInfoExists = { false },
        ).selectDirectory()

        assertTrue(selection is LocalFolderSelection.Unavailable)
    }

    @Test
    fun `flatpak marker fails closed even when environment is unavailable`() {
        val selection = NativeLocalFolderSelector(
            environment = emptyMap(),
            flatpakInfoExists = { true },
        ).selectDirectory()

        assertTrue(selection is LocalFolderSelection.Unavailable)
    }
}
