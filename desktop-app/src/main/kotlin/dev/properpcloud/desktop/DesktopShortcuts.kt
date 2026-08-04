package dev.properpcloud.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

enum class DesktopFocusTarget {
    LIBRARY,
    QUEUE,
}

sealed interface DesktopShortcut {
    data object PlayPause : DesktopShortcut
    data object Next : DesktopShortcut
    data object Previous : DesktopShortcut
    data object FocusLibrary : DesktopShortcut
    data object FocusQueue : DesktopShortcut
    data object ShowHelp : DesktopShortcut
    data class SelectLibrary(val delta: Int) : DesktopShortcut
    data class OpenLibrary(val operation: LibraryKeyboardOperation) : DesktopShortcut
    data class SelectQueue(val delta: Int) : DesktopShortcut
    data object PlayQueueSelection : DesktopShortcut
    data object RemoveQueueSelection : DesktopShortcut
    data class MoveQueueSelection(val delta: Int) : DesktopShortcut
}

enum class LibraryKeyboardOperation {
    OPEN_OR_PLAY,
    APPEND,
    PLAY_REPLACE,
    INSPECT,
}

fun resolveDesktopShortcut(
    event: KeyEvent,
    focus: DesktopFocusTarget,
    modalOpen: Boolean,
): DesktopShortcut? = resolveDesktopShortcut(
    key = event.key,
    focus = focus,
    modalOpen = modalOpen,
    ctrl = event.isCtrlPressed,
    alt = event.isAltPressed,
    shift = event.isShiftPressed,
)

internal fun resolveDesktopShortcut(
    key: Key,
    focus: DesktopFocusTarget,
    modalOpen: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
): DesktopShortcut? {
    if (modalOpen) return null
    return when {
        key == Key.F1 -> DesktopShortcut.ShowHelp
        ctrl && key == Key.L -> DesktopShortcut.FocusLibrary
        ctrl && key == Key.Q -> DesktopShortcut.FocusQueue
        key == Key.Spacebar -> DesktopShortcut.PlayPause
        ctrl && key == Key.DirectionRight -> DesktopShortcut.Next
        ctrl && key == Key.DirectionLeft -> DesktopShortcut.Previous
        focus == DesktopFocusTarget.LIBRARY && key == Key.DirectionDown -> DesktopShortcut.SelectLibrary(1)
        focus == DesktopFocusTarget.LIBRARY && key == Key.DirectionUp -> DesktopShortcut.SelectLibrary(-1)
        focus == DesktopFocusTarget.LIBRARY && key == Key.Enter && alt ->
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.INSPECT)
        focus == DesktopFocusTarget.LIBRARY && key == Key.Enter && ctrl ->
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.PLAY_REPLACE)
        focus == DesktopFocusTarget.LIBRARY && key == Key.Enter && shift ->
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.APPEND)
        focus == DesktopFocusTarget.LIBRARY && key == Key.Enter ->
            DesktopShortcut.OpenLibrary(LibraryKeyboardOperation.OPEN_OR_PLAY)
        focus == DesktopFocusTarget.QUEUE && key == Key.DirectionDown && !alt -> DesktopShortcut.SelectQueue(1)
        focus == DesktopFocusTarget.QUEUE && key == Key.DirectionUp && !alt -> DesktopShortcut.SelectQueue(-1)
        focus == DesktopFocusTarget.QUEUE && key == Key.Enter -> DesktopShortcut.PlayQueueSelection
        focus == DesktopFocusTarget.QUEUE && key == Key.Delete -> DesktopShortcut.RemoveQueueSelection
        focus == DesktopFocusTarget.QUEUE && key == Key.DirectionUp && alt -> DesktopShortcut.MoveQueueSelection(-1)
        focus == DesktopFocusTarget.QUEUE && key == Key.DirectionDown && alt -> DesktopShortcut.MoveQueueSelection(1)
        else -> null
    }
}
