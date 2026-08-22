package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

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
        assertTrue("audio-header duration should be available for EXTINF", (result.snapshot.durationMillis ?: 0L) > 0L)
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

    @Test
    fun changingOneFieldPreservesOtherModeledMetadata() {
        val source = temporary.newFile("preserve.wav").apply(::writeSilentWave)
        val toolkit = JAudioTaggerToolkit()
        val first = toolkit.stagePatch(
            source,
            temporary.newFolder("preserve-first"),
            TagPatch(
                mapOf(
                    TagField.TITLE to TagMutation.Set("First title"),
                    TagField.ARTIST to TagMutation.Set("Keep this artist"),
                    TagField.COMMENT to TagMutation.Set("Keep  deliberate   spacing"),
                ),
            ),
        )

        val second = toolkit.stagePatch(
            first.stagedFile,
            temporary.newFolder("preserve-second"),
            TagPatch(mapOf(TagField.TITLE to TagMutation.Set("Second title"))),
            expectedSourceSha256 = first.stagedSha256,
        )

        assertEquals("Second title", second.snapshot.fields[TagField.TITLE]?.value)
        assertEquals("Keep this artist", second.snapshot.fields[TagField.ARTIST]?.value)
        assertEquals("Keep  deliberate   spacing", second.snapshot.fields[TagField.COMMENT]?.value)
    }

    @Test
    fun id3PatchPreservesFramesOutsideProperpcloudsModeledFieldSet() {
        val source = temporary.newFile("id3-preserve.mp3").apply {
            writeBytes(Base64.getDecoder().decode(SILENT_MP3_BASE64))
        }
        val audio = AudioFileIO.read(source)
        audio.tagOrCreateAndSetDefault.apply {
            setField(FieldKey.TITLE, "Before")
            setField(FieldKey.BPM, "123")
            setField(FieldKey.CATALOG_NO, "CAT-KEEP-42")
        }
        audio.commit()
        val before = AudioFileIO.read(source).tag
        assertEquals("123", before.getFirst(FieldKey.BPM))
        assertEquals("CAT-KEEP-42", before.getFirst(FieldKey.CATALOG_NO))

        val result = JAudioTaggerToolkit().stagePatch(
            source,
            temporary.newFolder("id3-preserve-stage"),
            TagPatch(mapOf(TagField.TITLE to TagMutation.Set("After"))),
        )

        val after = AudioFileIO.read(result.stagedFile).tag
        assertEquals("After", after.getFirst(FieldKey.TITLE))
        assertEquals("123", after.getFirst(FieldKey.BPM))
        assertEquals("CAT-KEEP-42", after.getFirst(FieldKey.CATALOG_NO))
    }

    private fun writeSilentWave(file: File) {
        val sampleRate = 8_000
        // Keep this above one second because jaudiotagger's portable trackLength contract is
        // whole seconds; the playlist layer intentionally does not invent sub-second precision.
        val samples = ByteArray(sampleRate * 2 * 2)
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

    private companion object {
        // 120 ms mono silence, generated deterministically with:
        // ffmpeg -f lavfi -i anullsrc=r=8000:cl=mono -t 0.12 -codec:a libmp3lame -b:a 8k -write_xing 0 -id3v2_version 0
        const val SILENT_MP3_BASE64 =
            "/+MYxAAAAANIAAAAAExBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxDsAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxHYAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV/+MYxLEAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"
    }
}
