package dev.properpcloud.core.model

enum class TrackSortKey {
    NATURAL_FILENAME,
    DISC_THEN_TRACK,
    TAGGED_TITLE,
    MODIFIED_TIME,
}

data class TrackSortPolicy(
    val keys: List<TrackSortKey> = listOf(
        TrackSortKey.DISC_THEN_TRACK,
        TrackSortKey.NATURAL_FILENAME,
    ),
    val foldersFirst: Boolean = true,
)

object FolderQueueBuilder {
    fun sortNodes(
        nodes: Iterable<MediaNode>,
        policy: TrackSortPolicy = TrackSortPolicy(),
    ): List<MediaNode> = nodes.sortedWith(nodeComparator(policy))

    fun tracksOnly(
        nodes: Iterable<MediaNode>,
        policy: TrackSortPolicy = TrackSortPolicy(),
    ): List<AudioTrack> = sortNodes(nodes, policy).filterIsInstance<AudioTrack>()

    private fun nodeComparator(policy: TrackSortPolicy): Comparator<MediaNode> = Comparator { left, right ->
        if (policy.foldersFirst) {
            val leftFolder = left is AudioFolder
            val rightFolder = right is AudioFolder
            if (leftFolder != rightFolder) return@Comparator if (leftFolder) -1 else 1
        }

        if (left is AudioTrack && right is AudioTrack) {
            for (key in policy.keys.distinct()) {
                val result = compareTracks(left, right, key)
                if (result != 0) return@Comparator result
            }
        }

        val byName = NaturalTextComparator.compare(left.name, right.name)
        if (byName != 0) byName else compareValuesBy(left, right, { it.sourceId.value }, { it.id.value })
    }

    private fun compareTracks(left: AudioTrack, right: AudioTrack, key: TrackSortKey): Int = when (key) {
        TrackSortKey.NATURAL_FILENAME -> NaturalTextComparator.compare(left.name, right.name)
        TrackSortKey.DISC_THEN_TRACK -> compareValuesBy(
            left,
            right,
            { it.discNumber ?: Int.MAX_VALUE },
            { it.trackNumber ?: Int.MAX_VALUE },
        )
        TrackSortKey.TAGGED_TITLE -> NaturalTextComparator.compare(
            left.taggedTitle ?: left.filenameStem,
            right.taggedTitle ?: right.filenameStem,
        )
        TrackSortKey.MODIFIED_TIME -> compareValues(
            left.modifiedAtEpochMillis ?: Long.MAX_VALUE,
            right.modifiedAtEpochMillis ?: Long.MAX_VALUE,
        )
    }
}

object NaturalTextComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]

            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = left.consumeDigits(leftIndex)
                val rightEnd = right.consumeDigits(rightIndex)
                val leftNumber = left.substring(leftIndex, leftEnd).trimStart('0').ifEmpty { "0" }
                val rightNumber = right.substring(rightIndex, rightEnd).trimStart('0').ifEmpty { "0" }

                val lengthComparison = leftNumber.length.compareTo(rightNumber.length)
                if (lengthComparison != 0) return lengthComparison

                val numberComparison = leftNumber.compareTo(rightNumber)
                if (numberComparison != 0) return numberComparison

                leftIndex = leftEnd
                rightIndex = rightEnd
                continue
            }

            val charComparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (charComparison != 0) return charComparison

            leftIndex++
            rightIndex++
        }

        return left.length.compareTo(right.length)
    }

    private fun String.consumeDigits(start: Int): Int {
        var index = start
        while (index < length && this[index].isDigit()) index++
        return index
    }
}
