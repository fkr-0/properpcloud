package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class JAudioTaggerToolkitTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun writesAndVerifiesTagsOnlyOnStagedCopy() {
        val source = temporary.newFile("chapter.wav").apply(::writeSilentWave)
        val staging = temporary.newFolder("staging")
        val toolkit = JAudioTaggerToolkit()

        val result = toolkit.stagePatch(
            source = source,
            stagingDirectory = staging,
            patch = TagPatch(
                mapOf(
                    TagField.TITLE to TagMutation.Set("A staged title"),
                    TagField.ARTIST to TagMutation.Set("Test Artist"),
                ),
            ),
        )

        assertEquals("A staged title", result.snapshot.fields[TagField.TITLE]?.value)
        assertEquals("Test Artist", result.snapshot.fields[TagField.ARTIST]?.value)
        assertFalse(toolkit.inspect(source).fields.containsKey(TagField.TITLE))
        assertNotEquals(result.sourceSha256, result.stagedSha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChangedSourceHashBeforeStaging() {
        val source = temporary.newFile("changed.wav").apply(::writeSilentWave)
        JAudioTaggerToolkit().stagePatch(
            source,
            temporary.newFolder("changed-staging"),
            TagPatch(mapOf(TagField.TITLE to TagMutation.Set("Title"))),
            expectedSourceSha256 = "00",
        )
    }

    private fun writeSilentWave(file: File) {
        val sampleRate = 8_000
        val samples = ByteArray(sampleRate / 4 * 2)
        DataOutputStream(FileOutputStream(file)).use { out ->
            fun ascii(value: String) = out.writeBytes(value)
            fun littleInt(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte(value ushr 8 and 0xff)
                out.writeByte(value ushr 16 and 0xff)
                out.writeByte(value ushr 24 and 0xff)
            }
            fun littleShort(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte(value ushr 8 and 0xff)
            }
            ascii("RIFF")
            littleInt(36 + samples.size)
            ascii("WAVE")
            ascii("fmt ")
            littleInt(16)
            littleShort(1)
            littleShort(1)
            littleInt(sampleRate)
            littleInt(sampleRate * 2)
            littleShort(2)
            littleShort(16)
            ascii("data")
            littleInt(samples.size)
            out.write(samples)
        }
    }
}
