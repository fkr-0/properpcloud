package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ArtworkSummary
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.security.MessageDigest
import java.util.UUID

interface AudioTagToolkit {
    fun inspect(file: File): TagSnapshot

    fun stagePatch(
        source: File,
        stagingDirectory: File,
        patch: TagPatch,
        expectedSourceSha256: String? = null,
    ): StagedTagResult
}

data class StagedTagResult(
    val stagedFile: File,
    val sourceSha256: String,
    val stagedSha256: String,
    val snapshot: TagSnapshot,
    val changedFields: Set<TagField>,
)

class JAudioTaggerToolkit : AudioTagToolkit {
    override fun inspect(file: File): TagSnapshot {
        requireReadableAudioFile(file)
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag
        val fields = linkedMapOf<TagField, MetadataValue>()
        fieldKeys.forEach { (field, key) ->
            runCatching { tag?.getFirst(key).orEmpty().trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { fields[field] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
        }
        val allArtwork = tag?.artworkList.orEmpty()
        val artwork = allArtwork.take(MAX_ARTWORK_SUMMARIES).map { image ->
            ArtworkSummary(
                mimeType = image.mimeType,
                byteCount = image.binaryData?.size?.toLong() ?: 0L,
                description = image.description,
            )
        }
        return TagSnapshot(
            format = buildString {
                append(audioFile.audioHeader?.format?.takeIf(String::isNotBlank) ?: file.extension.uppercase())
                tag?.javaClass?.simpleName?.takeIf(String::isNotBlank)?.let { append(" / ").append(it) }
            },
            fields = fields,
            artwork = artwork,
            warnings = if (allArtwork.size > MAX_ARTWORK_SUMMARIES) {
                listOf("Artwork summary truncated to $MAX_ARTWORK_SUMMARIES entries")
            } else {
                emptyList()
            },
            durationMillis = audioFile.audioHeader?.trackLength
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1_000L),
        )
    }

    override fun stagePatch(
        source: File,
        stagingDirectory: File,
        patch: TagPatch,
        expectedSourceSha256: String?,
    ): StagedTagResult {
        requireReadableAudioFile(source)
        require(stagingDirectory.exists() || stagingDirectory.mkdirs()) { "could not create staging directory" }
        require(stagingDirectory.isDirectory) { "staging path must be a directory" }

        val sourceHash = source.sha256ForTagToolkit()
        if (expectedSourceSha256 != null) {
            require(sourceHash.equals(expectedSourceSha256, ignoreCase = true)) {
                "source content changed before metadata staging"
            }
        }

        val original = inspect(source)
        val changedFields = patch.changedFields(original)
        val siblingTransaction = runCatching {
            stagingDirectory.canonicalFile == source.parentFile?.canonicalFile
        }.getOrDefault(false)
        val prefix = if (siblingTransaction) ".properpcloud-stage-" else ""
        val staged = File(
            stagingDirectory,
            "$prefix${source.nameWithoutExtension.take(80)}-${UUID.randomUUID()}.${source.extension.lowercase()}",
        )
        source.copyTo(staged, overwrite = false)

        try {
            if (changedFields.isNotEmpty()) {
                val audioFile = AudioFileIO.read(staged)
                val tag = audioFile.tagOrCreateAndSetDefault
                patch.mutations.forEach { (field, mutation) ->
                    val key = fieldKeys.getValue(field)
                    when (mutation) {
                        TagMutation.Keep -> Unit
                        TagMutation.Clear -> if (tag.hasField(key)) tag.deleteField(key)
                        is TagMutation.Set -> tag.setField(key, mutation.value)
                    }
                }
                audioFile.commit()
            }

            val verified = inspect(staged)
            verifyPatch(patch, verified)
            verifyUnchangedModeledMetadata(original, patch, verified)
            return StagedTagResult(
                stagedFile = staged,
                sourceSha256 = sourceHash,
                stagedSha256 = staged.sha256ForTagToolkit(),
                snapshot = verified,
                changedFields = changedFields,
            )
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    /**
     * A metadata repair is a surgical edit, not a tag rewrite.  Even when the underlying
     * container library needs to rewrite the tag block, every modeled field that the user did
     * not select must round-trip unchanged.  Artwork is also outside the current mutation
     * surface and therefore must survive byte-affecting staging intact.
     *
     * ID3v2 is deliberately extensible, so this check supplements rather than replaces the
     * library's preservation of unknown/native frames: properpcloud never reconstructs a tag
     * from only the fields it understands.
     */
    private fun verifyUnchangedModeledMetadata(
        original: TagSnapshot,
        patch: TagPatch,
        verified: TagSnapshot,
    ) {
        val selected = patch.mutations
            .filterValues { it !is TagMutation.Keep }
            .keys
        fieldKeys.keys
            .filterNot { it in selected }
            .forEach { field ->
                val before = original.fields[field]?.value
                val after = verified.fields[field]?.value
                check(before == after) {
                    "tag verification changed unselected field $field"
                }
            }
        check(original.artwork == verified.artwork) {
            "tag verification changed artwork outside the approved mutation surface"
        }
    }

    private fun verifyPatch(patch: TagPatch, snapshot: TagSnapshot) {
        patch.mutations.forEach { (field, mutation) ->
            val actual = snapshot.fields[field]?.value
            when (mutation) {
                TagMutation.Keep -> Unit
                TagMutation.Clear -> check(actual == null) { "tag verification failed while clearing $field" }
                is TagMutation.Set -> check(actual == mutation.value) { "tag verification failed while setting $field" }
            }
        }
    }

    private fun requireReadableAudioFile(file: File) {
        require(file.isFile) { "audio source must be a regular file" }
        require(file.canRead()) { "audio source is not readable" }
        require(file.length() > 0) { "audio source is empty" }
    }

    private companion object {
        const val MAX_ARTWORK_SUMMARIES = 32
        val fieldKeys = mapOf(
            TagField.TITLE to FieldKey.TITLE,
            TagField.ARTIST to FieldKey.ARTIST,
            TagField.ALBUM to FieldKey.ALBUM,
            TagField.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
            TagField.GENRE to FieldKey.GENRE,
            TagField.YEAR to FieldKey.YEAR,
            TagField.TRACK_NUMBER to FieldKey.TRACK,
            TagField.TRACK_TOTAL to FieldKey.TRACK_TOTAL,
            TagField.DISC_NUMBER to FieldKey.DISC_NO,
            TagField.DISC_TOTAL to FieldKey.DISC_TOTAL,
            TagField.COMMENT to FieldKey.COMMENT,
            TagField.COMPOSER to FieldKey.COMPOSER,
            TagField.ISRC to FieldKey.ISRC,
            TagField.MUSICBRAINZ_RECORDING_ID to FieldKey.MUSICBRAINZ_TRACK_ID,
            TagField.MUSICBRAINZ_RELEASE_ID to FieldKey.MUSICBRAINZ_RELEASEID,
            TagField.LYRICS to FieldKey.LYRICS,
        )
    }
}

private fun File.sha256ForTagToolkit(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
