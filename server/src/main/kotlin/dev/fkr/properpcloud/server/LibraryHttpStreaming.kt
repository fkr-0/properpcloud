package dev.fkr.properpcloud.server

import java.io.EOFException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class RangeNotSatisfiableException(message: String) : IllegalArgumentException(message)

internal fun parseByteRange(header: String?, size: Long): LongRange? {
    require(size >= 0) { "file size must not be negative" }
    if (header.isNullOrBlank()) return null
    require(header.startsWith("bytes=") && ',' !in header) { "unsupported Range header" }

    val raw = header.removePrefix("bytes=")
    val separator = raw.indexOf('-')
    require(separator >= 0 && raw.indexOf('-', separator + 1) < 0) { "invalid Range header" }
    val left = raw.substring(0, separator)
    val right = raw.substring(separator + 1)

    if (left.isBlank()) {
        val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("invalid Range")
        if (size == 0L) throw RangeNotSatisfiableException("range cannot be satisfied by an empty file")
        val start = (size - suffix).coerceAtLeast(0)
        return start..(size - 1)
    }

    val start = left.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("invalid Range")
    if (start >= size) throw RangeNotSatisfiableException("range starts beyond end of file")
    val end = if (right.isBlank()) size - 1 else {
        right.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("invalid Range")
    }
    if (end < start) throw RangeNotSatisfiableException("range end precedes range start")
    return start..end.coerceAtMost(size - 1)
}

/**
 * Copies exactly [count] bytes. A shorter source is an I/O failure, not a
 * successful shorter response: callers have already advertised that length.
 */
internal fun copyBounded(channel: FileChannel, output: OutputStream, count: Long) {
    require(count >= 0) { "copy count must not be negative" }
    var remaining = count
    val buffer = ByteBuffer.allocate(64 * 1024)
    while (remaining > 0) {
        buffer.clear()
        buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
        val read = channel.read(buffer)
        if (read < 0) throw EOFException("source file ended before the advertised stream length")
        if (read == 0) continue
        buffer.flip()
        output.write(buffer.array(), 0, read)
        remaining -= read
    }
}
