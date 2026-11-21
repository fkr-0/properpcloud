package dev.fkr.properpcloud.core.model

/**
 * Evidence classification for a media filename and an optional MIME hint.
 *
 * AUDIO_CANDIDATE means the name and/or allowlisted MIME type is plausible audio input.
 * It is deliberately not a codec validation result: only the actual demuxer/decoder can
 * determine whether the bytes are playable on a particular device.
 */
enum class MediaFormatEvidence {
    AUDIO_CANDIDATE,
    AMBIGUOUS_CONTAINER,
    UNKNOWN,
    NON_AUDIO,
    CONFLICTING,
}

data class MediaFormatClassification(
    val evidence: MediaFormatEvidence,
    val extension: String?,
    /** A syntactically valid MIME type, normalized to ASCII lowercase and without parameters. */
    val normalizedMimeType: String?,
    /** A deterministic playback hint when the available evidence is internally consistent. */
    val playbackMimeType: String?,
) {
    val isAudioCandidate: Boolean
        get() = evidence == MediaFormatEvidence.AUDIO_CANDIDATE
}

/**
 * Source-neutral, deterministic registry of audio filename/MIME hints.
 *
 * This object intentionally has no dependency on platform MIME databases, provider SDKs,
 * filesystem probes, or codec implementations. A match is an admission hint only.
 */
object MediaFormatRegistry {
    private data class Format(val mimeType: String)

    private val formatsByExtension: Map<String, Format> = mapOf(
        "mp3" to Format("audio/mpeg"),
        "flac" to Format("audio/flac"),
        "ogg" to Format("audio/ogg"),
        "oga" to Format("audio/ogg"),
        "opus" to Format("audio/opus"),
        "webm" to Format("audio/webm"),
        "m4a" to Format("audio/mp4"),
        "m4b" to Format("audio/mp4"),
        "aac" to Format("audio/aac"),
        "wav" to Format("audio/wav"),
        "aif" to Format("audio/aiff"),
        "aiff" to Format("audio/aiff"),
        "alac" to Format("audio/alac"),
        "ape" to Format("audio/x-ape"),
        "wma" to Format("audio/x-ms-wma"),
        "asf" to Format("audio/x-ms-asf"),
        "mpc" to Format("audio/x-musepack"),
        "wv" to Format("audio/wavpack"),
        "dsf" to Format("audio/dsd"),
        "dff" to Format("audio/dsd"),
    )

    private val aliasesByMimeType: Map<String, Set<String>> = mapOf(
        "audio/mpeg" to setOf("mp3"),
        "audio/flac" to setOf("flac"),
        "audio/ogg" to setOf("ogg", "oga"),
        "audio/opus" to setOf("opus"),
        "audio/webm" to setOf("webm"),
        "audio/mp4" to setOf("m4a", "m4b", "mp4"),
        "audio/aac" to setOf("aac"),
        "audio/wav" to setOf("wav"),
        "audio/x-wav" to setOf("wav"),
        "audio/aiff" to setOf("aif", "aiff"),
        "audio/x-aiff" to setOf("aif", "aiff"),
        "audio/alac" to setOf("alac"),
        "audio/x-ape" to setOf("ape"),
        "audio/ape" to setOf("ape"),
        "audio/x-ms-wma" to setOf("wma"),
        "audio/x-ms-asf" to setOf("asf"),
        "audio/x-musepack" to setOf("mpc"),
        "audio/wavpack" to setOf("wv"),
        "audio/x-wavpack" to setOf("wv"),
        "audio/dsd" to setOf("dsf", "dff"),
    )

    /** Extensions admitted as audio candidates, excluding ambiguous containers such as mp4. */
    val audioExtensions: Set<String> = formatsByExtension.keys

    /**
     * Canonical MIME hints represented by the registry. This is not a promise that every
     * platform build has the codec installed; codec support is established at playback time.
     */
    val supportedMimeTypes: List<String> = formatsByExtension.values
        .map(Format::mimeType)
        .distinct()
        .sorted()

    /**
     * Normalize an RFC-style type/subtype token pair. Parameters are excluded; malformed,
     * wildcard, non-ASCII, or internally-whitespace-containing values are rejected.
     */
    fun normalizeMimeType(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val essence = value.substringBefore(';').trim()
        val slash = essence.indexOf('/')
        if (slash <= 0 || slash != essence.lastIndexOf('/') || slash == essence.lastIndex) return null
        val type = essence.substring(0, slash)
        val subtype = essence.substring(slash + 1)
        if (!isMimeToken(type) || !isMimeToken(subtype)) return null
        return asciiLower(type) + "/" + asciiLower(subtype)
    }

    fun classify(fileName: String?, mimeType: String?): MediaFormatClassification {
        val extension = extensionOf(fileName)
        val normalizedMime = normalizeMimeType(mimeType)
        val hasMime = !mimeType.isNullOrBlank()
        val extensionMime = extension?.let(formatsByExtension::get)?.mimeType

        fun result(evidence: MediaFormatEvidence, playbackMime: String? = null) =
            MediaFormatClassification(evidence, extension, normalizedMime, playbackMime)

        if (hasMime && normalizedMime == null) {
            return result(
                if (extensionMime != null || extension == "mp4") {
                    MediaFormatEvidence.CONFLICTING
                } else {
                    MediaFormatEvidence.UNKNOWN
                },
            )
        }

        if (normalizedMime == GENERIC_BINARY_MIME) {
            return when {
                extensionMime != null -> result(MediaFormatEvidence.AUDIO_CANDIDATE, extensionMime)
                extension == "mp4" -> result(MediaFormatEvidence.AMBIGUOUS_CONTAINER)
                else -> result(MediaFormatEvidence.UNKNOWN)
            }
        }

        if (normalizedMime != null) {
            val mimeExtensions = aliasesByMimeType[normalizedMime]
            if (mimeExtensions != null) {
                return when {
                    extension == null -> result(MediaFormatEvidence.AUDIO_CANDIDATE, normalizedMime)
                    extension in mimeExtensions -> result(
                        MediaFormatEvidence.AUDIO_CANDIDATE,
                        extensionMime ?: normalizedMime,
                    )
                    else -> result(MediaFormatEvidence.CONFLICTING)
                }
            }

            // Unknown audio/* labels are not a whitelist. In particular, a MIME label alone
            // cannot turn an executable/unknown-suffix file into an audio candidate.
            if (normalizedMime.startsWith("audio/")) {
                return result(
                    if (extensionMime != null || extension == "mp4") {
                        MediaFormatEvidence.CONFLICTING
                    } else {
                        MediaFormatEvidence.UNKNOWN
                    },
                )
            }

            return result(
                when {
                    extensionMime != null || extension == "mp4" -> MediaFormatEvidence.CONFLICTING
                    else -> MediaFormatEvidence.NON_AUDIO
                },
            )
        }

        return when {
            extensionMime != null -> result(MediaFormatEvidence.AUDIO_CANDIDATE, extensionMime)
            extension == "mp4" -> result(MediaFormatEvidence.AMBIGUOUS_CONTAINER)
            else -> result(MediaFormatEvidence.UNKNOWN)
        }
    }

    private fun extensionOf(fileName: String?): String? {
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1)
        return extension.takeIf { it.isNotBlank() }?.let(::asciiLower)
    }

    private fun isMimeToken(value: String): Boolean =
        value.isNotEmpty() && value != "*" && value.all { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char in "!#$%&'*+-.^_\u0060|~"
        }

    private fun asciiLower(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(if (char in 'A'..'Z') (char.code + ('a'.code - 'A'.code)).toChar() else char)
        }
    }

    private const val GENERIC_BINARY_MIME = "application/octet-stream"
}
