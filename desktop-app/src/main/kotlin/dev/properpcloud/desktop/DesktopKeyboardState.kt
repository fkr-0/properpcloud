package dev.properpcloud.desktop

internal fun moveSelection(current: Int, delta: Int, itemCount: Int): Int {
    if (itemCount <= 0) return -1
    val base = current.takeIf { it in 0 until itemCount } ?: 0
    return (base + delta).coerceIn(0, itemCount - 1)
}
