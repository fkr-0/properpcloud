package dev.properpcloud.desktop.platform

import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

sealed interface LocalFolderSelection {
    data class Selected(val directory: File) : LocalFolderSelection
    data object Cancelled : LocalFolderSelection
    data class Unavailable(val reason: String) : LocalFolderSelection
}

fun interface LocalFolderSelector {
    fun selectDirectory(): LocalFolderSelection
}

/**
 * Native JVM directory picker for unsandboxed desktop distributions.
 *
 * The current Flatpak package deliberately has no document-portal lease implementation and
 * grants no host/home filesystem permission. Reporting the feature unavailable there keeps the
 * package fail-closed instead of making a sandbox-visible path look like a user library root.
 */
class NativeLocalFolderSelector(
    private val environment: Map<String, String> = System.getenv(),
    private val flatpakInfoExists: () -> Boolean = { Files.exists(Path.of("/.flatpak-info")) },
) : LocalFolderSelector {
    override fun selectDirectory(): LocalFolderSelection {
        if (environment["FLATPAK_ID"] != null || flatpakInfoExists()) {
            return LocalFolderSelection.Unavailable(
                "Local folders are unavailable in the current Flatpak package until a document-portal directory lease is implemented.",
            )
        }
        if (GraphicsEnvironment.isHeadless()) {
            return LocalFolderSelection.Unavailable("Local folder selection requires a graphical desktop session.")
        }

        var selection: LocalFolderSelection = LocalFolderSelection.Cancelled
        val choose = Runnable {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose local audio folder"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                approveButtonText = "Use folder"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selection = chooser.selectedFile?.let(LocalFolderSelection::Selected)
                    ?: LocalFolderSelection.Cancelled
            }
        }
        if (SwingUtilities.isEventDispatchThread()) choose.run() else SwingUtilities.invokeAndWait(choose)
        return selection
    }
}
