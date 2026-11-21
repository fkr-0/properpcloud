package dev.fkr.properpcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class LibraryHttpStreamingTest {
    @Test
    fun absentRangeStreamsWholeFile() {
        assertNull(parseByteRange(null, 10))
        assertNull(parseByteRange("", 10))
    }

    @Test
    fun validOpenEndedAndSuffixRangesAreNormalized() {
        assertEquals(2L..9L, parseByteRange("bytes=2-", 10))
        assertEquals(2L..5L, parseByteRange("bytes=2-5", 10))
        assertEquals(7L..9L, parseByteRange("bytes=-3", 10))
        assertEquals(0L..9L, parseByteRange("bytes=-20", 10))
    }

    @Test
    fun unsatisfiableRangesAreDistinguishedFromMalformedHeaders() {
        assertThrows(RangeNotSatisfiableException::class.java) { parseByteRange("bytes=10-", 10) }
        assertThrows(RangeNotSatisfiableException::class.java) { parseByteRange("bytes=5-2", 10) }
        assertThrows(IllegalArgumentException::class.java) { parseByteRange("items=0-2", 10) }
        assertThrows(IllegalArgumentException::class.java) { parseByteRange("bytes=0-1,4-5", 10) }
    }

    @Test
    fun rangeAgainstEmptyFileIsUnsatisfiable() {
        assertThrows(RangeNotSatisfiableException::class.java) { parseByteRange("bytes=0-", 0) }
        assertThrows(RangeNotSatisfiableException::class.java) { parseByteRange("bytes=-1", 0) }
    }

    @Test
    fun boundedCopyWritesExactlyTheAdvertisedBytes() {
        val path = Files.createTempFile("properpcloud-stream-", ".bin")
        try {
            Files.write(path, byteArrayOf(1, 2, 3, 4, 5))
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                channel.position(1)
                val output = ByteArrayOutputStream()
                copyBounded(channel, output, 3)
                assertEquals(listOf<Byte>(2, 3, 4), output.toByteArray().toList())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun boundedCopyFailsWhenSourceIsShorterThanAdvertisedLength() {
        val path = Files.createTempFile("properpcloud-short-read-", ".bin")
        try {
            Files.write(path, byteArrayOf(1, 2))
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                assertThrows(EOFException::class.java) { copyBounded(channel, ByteArrayOutputStream(), 3) }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
