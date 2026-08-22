package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.FileTagProposals
import dev.properpcloud.core.model.FolderStructureTagConfig
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.NaturalTextComparator
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagFieldProposal
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.text.Normalizer

/**
 * Pure deterministic rules engine that operates on one immutable folder snapshot generation.
 *
 * This engine has no I/O, no side effects, and no Compose/provider imports.
 * It reads a list of file identity + tag snapshot pairs and produces field-level proposals
 * for each file. Rules are divided into three categories:
 *
 * - **Safe preselected** (autoPreselected = true, confidence >= 0.9): applied automatically.
 * - **Review required** (autoPreselected = false, confidence 0.5–0.8): user must approve.
 * - **Forbidden**: never emitted; encoded as structural invariants.
 */
class TagProposalEngine {

    private data class StructureContext(
        val album: String? = null,
        val artist: String? = null,
        val discNumber: Int? = null,
        val inferTitleFromFilename: Boolean = false,
        val inferTrackNumber: Boolean = false,
        val inferTrackTotal: Boolean = false,
    )

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Generate field-level proposals for every file in a folder.
     *
     * @param files (identity, snapshot) pairs; caller order is ignored and natural filename
     *              order is used for sequence-from-position rules.
     * @param folderName the bare directory name (not the full path), used by
     *                   inferAlbumFromFolder.
     */
    fun generateProposals(
        files: List<Pair<LocalFileIdentity, TagSnapshot>>,
        folderName: String,
    ): List<FileTagProposals> {
        return generateOrderedProposals(
            files = files,
            structure = StructureContext(
                album = folderName.trim().takeIf(::isPlausibleFolderValue),
                artist = null,
                discNumber = null,
                inferTitleFromFilename = false,
                inferTrackNumber = true,
                inferTrackTotal = true,
            ),
        )
    }

    /**
     * Generate proposals using an explicit folder-structure inference profile. Files are
     * always sequenced in natural filename order; caller/listing order is never interpreted
     * as track order.
     */
    fun generateProposals(
        files: List<Pair<LocalFileIdentity, TagSnapshot>>,
        folderPath: File,
        config: FolderStructureTagConfig,
    ): List<FileTagProposals> {
        val canonicalFolder = folderPath.canonicalFile
        val recognizedDiscNumber = if (config.enabled && config.recognizeDiscFolders) {
            inferDiscNumber(canonicalFolder.name)
        } else {
            null
        }
        val discNumber = recognizedDiscNumber.takeIf { config.inferDiscNumber }
        val effectiveTrackFolder = if (recognizedDiscNumber != null) {
            canonicalFolder.parentFile ?: canonicalFolder
        } else {
            canonicalFolder
        }
        val structure = if (config.enabled) {
            StructureContext(
                album = ancestorName(effectiveTrackFolder, config.albumAncestorDepth),
                artist = ancestorName(effectiveTrackFolder, config.artistAncestorDepth),
                discNumber = discNumber,
                inferTitleFromFilename = config.inferTitleFromFilename,
                inferTrackNumber = config.inferTrackNumberFromNaturalOrder,
                inferTrackTotal = config.inferTrackTotal,
            )
        } else {
            StructureContext()
        }
        return generateOrderedProposals(files, structure)
    }

    private fun generateOrderedProposals(
        files: List<Pair<LocalFileIdentity, TagSnapshot>>,
        structure: StructureContext,
    ): List<FileTagProposals> {
        if (files.isEmpty()) return emptyList()

        val orderedFiles = files.sortedWith { left, right ->
            NaturalTextComparator.compare(left.first.filename, right.first.filename)
        }

        val unanimousAlbum = findUnanimousNonBlankValue(orderedFiles, TagField.ALBUM)
        val unanimousAlbumArtist = findUnanimousNonBlankValue(orderedFiles, TagField.ALBUM_ARTIST)
        val totalFiles = orderedFiles.size

        return orderedFiles.mapIndexed { index, (identity, snapshot) ->
            val proposals = mutableListOf<TagFieldProposal>()

            // ── Safe preselected rules (confidence >= 0.9) ────────────
            proposals += ruleSafeNormalizeFields(snapshot)
            proposals += ruleCopyUnanimousValue(snapshot, TagField.ALBUM, unanimousAlbum)
            proposals += ruleCopyUnanimousValue(snapshot, TagField.ALBUM_ARTIST, unanimousAlbumArtist)

            // ── Review required rules (confidence 0.5–0.8) ────────────
            val filenameProposals = ruleParseFilenameGroups(identity, snapshot)
            proposals += filenameProposals
            if (structure.inferTitleFromFilename && filenameProposals.none { it.field == TagField.TITLE }) {
                proposals += ruleInferTitleFromFilename(identity, snapshot)
            }
            structure.album?.let { album ->
                proposals += rulePreviewDerivedField(snapshot, TagField.ALBUM, album, RULE_INFER_ALBUM_FOLDER, "folder hierarchy")
            }
            structure.artist?.let { artist ->
                proposals += rulePreviewDerivedField(snapshot, TagField.ARTIST, artist, RULE_INFER_ARTIST_FOLDER, "folder hierarchy")
            }
            structure.discNumber?.let { disc ->
                proposals += rulePreviewDerivedField(snapshot, TagField.DISC_NUMBER, disc.toString(), RULE_INFER_DISC_FOLDER, "disc folder")
            }
            if (structure.inferTrackNumber || structure.inferTrackTotal) {
                proposals += ruleSequenceTracksFromOrder(
                    snapshot,
                    index,
                    totalFiles,
                    inferTrackNumber = structure.inferTrackNumber,
                    inferTrackTotal = structure.inferTrackTotal,
                )
            }

            FileTagProposals(
                identity = identity,
                originalSnapshot = snapshot,
                fieldProposals = proposals,
                formatWarnings = snapshot.warnings,
            )
        }
    }

    /**
     * Compute folder-level consistency metrics.
     */
    fun computeFolderConsistency(
        files: List<Pair<LocalFileIdentity, TagSnapshot>>,
    ): FolderConsistency {
        val albumValues = files
            .map { (_, snap) -> snap.fields[TagField.ALBUM]?.value }
            .groupingBy { it }
            .eachCount()

        val albumArtistValues = files
            .map { (_, snap) -> snap.fields[TagField.ALBUM_ARTIST]?.value }
            .groupingBy { it }
            .eachCount()

        val distinctAlbums = albumValues.keys.filterNotNull().distinct()
        val distinctAlbumArtists = albumArtistValues.keys.filterNotNull().distinct()

        return FolderConsistency(
            albumConsistent = distinctAlbums.size <= 1,
            albumArtistConsistent = distinctAlbumArtists.size <= 1,
            albumValues = albumValues,
            albumArtistValues = albumArtistValues,
        )
    }

    // ── Safe preselected rules ─────────────────────────────────────────────

    /**
     * Compose lossless/low-risk normalization into at most one proposal per field.  This is
     * intentionally conservative: free-form comments/lyrics keep whitespace, and duplicate
     * separator cleanup is limited to fields where repeated identical names are clearly an
     * encoding artefact rather than plausible prose.
     */
    private fun ruleSafeNormalizeFields(snapshot: TagSnapshot): List<TagFieldProposal> =
        snapshot.fields.mapNotNull { (field, meta) ->
            val original = meta.value
            var normalized = Normalizer.normalize(original, Normalizer.Form.NFC)
            val changes = mutableListOf<String>()

            if (normalized != original) changes += "Unicode NFC"

            if (field !in FREEFORM_FIELDS) {
                val trimmed = normalized.trim()
                if (trimmed != normalized) changes += "outer whitespace"
                normalized = trimmed
            }

            if (field in COLLAPSIBLE_TEXT_FIELDS) {
                val collapsed = normalized.replace(INTERNAL_WHITESPACE, " ")
                if (collapsed != normalized) changes += "repeated whitespace"
                normalized = collapsed
            }

            if (field in TRACK_DISC_FIELDS) {
                val numeric = normalizeNumericField(normalized)
                if (numeric != normalized) changes += "numeric form"
                normalized = numeric
            }

            if (field in DEDUPLICATABLE_FIELDS) {
                val deduplicated = deduplicateVariantValue(normalized)
                if (deduplicated != normalized) changes += "duplicate identical value"
                normalized = deduplicated
            }

            if (normalized == original || normalized.isBlank()) return@mapNotNull null
            TagFieldProposal(
                field = field,
                ruleId = RULE_SAFE_NORMALIZE,
                currentValue = original,
                proposedValue = normalized,
                confidence = 0.95,
                autoPreselected = true,
                explanation = "Normalize ${field.name}: ${changes.distinct().joinToString()}.",
                warnings = if ("duplicate identical value" in changes) {
                    listOf("Verify that repeated separator-delimited content is accidental.")
                } else {
                    emptyList()
                },
            )
        }

    /**
     * trimLeadingTrailingWhitespace – confidence 0.95
     *
     * Trim leading/trailing Unicode whitespace from every text field that currently
     * holds a value. If trimming would leave the field blank, the proposal is skipped
     * (forbidden: never clear a field via whitespace trim alone).
     */
    private fun ruleTrimLeadingTrailingWhitespace(
        snapshot: TagSnapshot,
    ): List<TagFieldProposal> {
        val proposals = mutableListOf<TagFieldProposal>()
        for ((field, meta) in snapshot.fields) {
            val original = meta.value
            val trimmed = original.trim()
            if (trimmed != original && trimmed.isNotBlank()) {
                proposals += TagFieldProposal(
                    field = field,
                    ruleId = RULE_TRIM_WHITESPACE,
                    currentValue = original,
                    proposedValue = trimmed,
                    confidence = 0.95,
                    autoPreselected = true,
                    explanation = "Remove leading/trailing whitespace from ${field.name}.",
                )
            }
        }
        return proposals
    }

    /**
     * normalizeTrackDiscNumbers – confidence 0.95
     *
     * Normalize TRACK_NUMBER, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL to canonical
     * numeric text: strip leading zeros, remove surrounding whitespace. Values like
     * "05" become "5"; " 03/12 " stays as "03/12" (compound values are left intact
     * unless only whitespace differs).
     */
    private fun ruleNormalizeTrackDiscNumbers(
        snapshot: TagSnapshot,
    ): List<TagFieldProposal> {
        val proposals = mutableListOf<TagFieldProposal>()
        for (field in TRACK_DISC_FIELDS) {
            val meta = snapshot.fields[field] ?: continue
            val original = meta.value
            val normalized = normalizeNumericField(original)
            if (normalized != original && normalized.isNotBlank()) {
                proposals += TagFieldProposal(
                    field = field,
                    ruleId = RULE_NORMALIZE_TRACK_DISC,
                    currentValue = original,
                    proposedValue = normalized,
                    confidence = 0.95,
                    autoPreselected = true,
                    explanation = "Normalize ${field.name} from \"$original\" to \"$normalized\".",
                )
            }
        }
        return proposals
    }

    /**
     * removeDuplicateTagVariants – confidence 0.95
     *
     * Detect values that contain the same content repeated with a common separator
     * (e.g., "Artist / Artist" from dual ID3v1/v2 writes). Propose the deduplicated form.
     */
    private fun ruleRemoveDuplicateTagVariants(
        snapshot: TagSnapshot,
    ): List<TagFieldProposal> {
        val proposals = mutableListOf<TagFieldProposal>()
        for ((field, meta) in snapshot.fields) {
            val original = meta.value
            val deduplicated = deduplicateVariantValue(original)
            if (deduplicated != original && deduplicated.isNotBlank()) {
                proposals += TagFieldProposal(
                    field = field,
                    ruleId = RULE_REMOVE_DUPLICATE_VARIANTS,
                    currentValue = original,
                    proposedValue = deduplicated,
                    confidence = 0.95,
                    autoPreselected = true,
                    explanation = "Remove duplicate tag variant content from ${field.name}.",
                    warnings = listOf("Verify that the deduplicated value is correct."),
                )
            }
        }
        return proposals
    }

    /**
     * copyUnanimousAlbumValue – confidence 0.90
     *
     * If every other file in the folder shares exactly one non-blank value for a given
     * field and the current file is missing that field, propose copying it.
     */
    private fun ruleCopyUnanimousValue(
        snapshot: TagSnapshot,
        field: TagField,
        unanimousValue: String?,
    ): List<TagFieldProposal> {
        if (unanimousValue == null) return emptyList()
        val current = snapshot.fields[field]?.value
        if (!current.isNullOrBlank()) return emptyList() // never overwrite (forbidden)

        return listOf(
            TagFieldProposal(
                field = field,
                ruleId = RULE_COPY_UNANIMOUS_ALBUM,
                currentValue = null,
                proposedValue = unanimousValue,
                confidence = 0.90,
                autoPreselected = true,
                explanation = "All other files in this folder share $field \"$unanimousValue\"; copying it here.",
            ),
        )
    }

    // ── Review required rules ──────────────────────────────────────────────

    /**
     * parseFilenameGroups – confidence 0.7
     *
     * Parse track number, artist, and title from the filename using common patterns:
     *   - "NN - Artist - Title"
     *   - "NN. Title"
     *   - "NN Title"
     *   - "Artist - Title"
     *
     * Only proposes fields that are currently missing (blank or absent).
     */
    private fun ruleParseFilenameGroups(
        identity: LocalFileIdentity,
        snapshot: TagSnapshot,
    ): List<TagFieldProposal> {
        val stem = identity.filename.substringBeforeLast('.', identity.filename)
        val proposals = mutableListOf<TagFieldProposal>()

        // Pattern: "NN - Artist - Title"
        val tripleMatch = TRIPLE_PATTERN.matchEntire(stem)
        if (tripleMatch != null) {
            val (trackStr, artist, title) = tripleMatch.destructured
            val trackNum = trackStr.trimStart('0').ifBlank { "0" }
            maybeAddFilenameProposal(proposals, snapshot, TagField.TRACK_NUMBER, trackNum, stem)
            maybeAddFilenameProposal(proposals, snapshot, TagField.ARTIST, artist.trim(), stem)
            maybeAddFilenameProposal(proposals, snapshot, TagField.TITLE, title.trim(), stem)
            return proposals
        }

        // Pattern: "NN. Title" or "NN Title"
        val trackTitleMatch = TRACK_TITLE_PATTERN.matchEntire(stem)
        if (trackTitleMatch != null) {
            val (trackStr, title) = trackTitleMatch.destructured
            val trackNum = trackStr.trimStart('0').ifBlank { "0" }
            maybeAddFilenameProposal(proposals, snapshot, TagField.TRACK_NUMBER, trackNum, stem)
            maybeAddFilenameProposal(proposals, snapshot, TagField.TITLE, title.trim(), stem)
            return proposals
        }

        // Pattern: "Artist - Title"
        val dualMatch = DUAL_PATTERN.matchEntire(stem)
        if (dualMatch != null) {
            val (artist, title) = dualMatch.destructured
            maybeAddFilenameProposal(proposals, snapshot, TagField.ARTIST, artist.trim(), stem)
            maybeAddFilenameProposal(proposals, snapshot, TagField.TITLE, title.trim(), stem)
            return proposals
        }

        return proposals
    }

    private fun ruleInferTitleFromFilename(
        identity: LocalFileIdentity,
        snapshot: TagSnapshot,
    ): List<TagFieldProposal> {
        val stem = identity.filename.substringBeforeLast('.', identity.filename).trim()
        val candidate = stem.replace(LEADING_TRACK_PREFIX, "").trim().takeIf(String::isNotBlank) ?: return emptyList()
        return rulePreviewDerivedField(
            snapshot = snapshot,
            field = TagField.TITLE,
            candidate = candidate,
            ruleId = RULE_INFER_TITLE_FILENAME,
            provenanceLabel = "filename",
        )
    }

    /**
     * Emit a visible comparison whenever deterministic structure evidence differs from the
     * embedded tag. Conflicts are never preselected: replacing a non-empty value therefore
     * requires the caller to explicitly approve this exact field/rule pair.
     */
    private fun rulePreviewDerivedField(
        snapshot: TagSnapshot,
        field: TagField,
        candidate: String,
        ruleId: String,
        provenanceLabel: String,
    ): List<TagFieldProposal> {
        val proposed = candidate.trim().takeIf(String::isNotBlank) ?: return emptyList()
        val current = snapshot.fields[field]?.value?.takeIf(String::isNotBlank)
        if (current == proposed) return emptyList()
        val conflict = current != null
        return listOf(
            TagFieldProposal(
                field = field,
                ruleId = ruleId,
                currentValue = current,
                proposedValue = proposed,
                confidence = when (field) {
                    TagField.DISC_NUMBER -> 0.80
                    TagField.TRACK_NUMBER, TagField.TRACK_TOTAL -> 0.50
                    else -> 0.65
                },
                autoPreselected = false,
                explanation = "Derived ${field.name} \"$proposed\" from $provenanceLabel.",
                warnings = if (conflict) {
                    listOf("Embedded value \"$current\" conflicts with the derived value; keep it unless you explicitly approve replacement.")
                } else {
                    listOf("Derived metadata is review-only; verify it before applying.")
                },
            ),
        )
    }

    /**
     * sequenceTracksFromOrder – confidence 0.5
     *
     * If TRACK_NUMBER is missing, assign sequential numbers based on the file's
     * position in the directory listing (1-based). Also propose TRACK_TOTAL if missing.
     */
    private fun ruleSequenceTracksFromOrder(
        snapshot: TagSnapshot,
        index: Int,
        totalFiles: Int,
        inferTrackNumber: Boolean = true,
        inferTrackTotal: Boolean = true,
    ): List<TagFieldProposal> {
        if (totalFiles < 2) return emptyList() // only meaningful when multiple files
        val proposals = mutableListOf<TagFieldProposal>()

        if (inferTrackNumber) {
            proposals += rulePreviewDerivedField(
                snapshot,
                TagField.TRACK_NUMBER,
                (index + 1).toString(),
                RULE_SEQUENCE_TRACKS,
                "natural filename order",
            )
        }

        if (inferTrackTotal) {
            proposals += rulePreviewDerivedField(
                snapshot,
                TagField.TRACK_TOTAL,
                totalFiles.toString(),
                RULE_SEQUENCE_TRACKS,
                "direct-folder audio count",
            )
        }

        return proposals
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Find the single non-blank value shared by all files that have a value for the
     * given field. Returns null if any file disagrees or if all files are blank.
     */
    private fun findUnanimousNonBlankValue(
        files: List<Pair<LocalFileIdentity, TagSnapshot>>,
        field: TagField,
    ): String? {
        val values = files
            .mapNotNull { (_, snap) -> snap.fields[field]?.value?.takeIf(String::isNotBlank) }
        // A single tagged file is weak evidence, not folder consensus.  Require at least two
        // independent exact observations before preselecting a missing album-level value.
        if (values.size < 2) return null
        val distinct = values.distinct()
        return distinct.singleOrNull()
    }

    /**
     * Normalize a numeric/total field: trim, strip leading zeros from a pure integer,
     * or from both parts of an "N/M" compound.
     */
    private fun normalizeNumericField(value: String): String {
        val trimmed = value.trim()
        // Compound "N/M" pattern
        val compound = COMPOUND_NUMBER_PATTERN.matchEntire(trimmed)
        if (compound != null) {
            val (num, den) = compound.destructured
            val normNum = stripLeadingZeros(num)
            val normDen = stripLeadingZeros(den)
            return "$normNum/$normDen"
        }
        // Do not reinterpret arbitrary text such as "03 of 12" as a number.
        return if (PURE_NUMBER_PATTERN.matches(trimmed)) stripLeadingZeros(trimmed) else trimmed
    }

    private fun stripLeadingZeros(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        val stripped = trimmed.trimStart('0')
        return stripped.ifBlank { "0" }
    }

    /**
     * Deduplicate values where the same content is repeated with a separator.
     * Handles " / ", " ; ", " | ", " & " separators and exact repetition.
     */
    private fun deduplicateVariantValue(value: String): String {
        for (sep in VARIANT_SEPARATORS) {
            val parts = value.split(sep).map(String::trim)
            if (parts.size >= 2) {
                val distinct = parts.distinct()
                if (distinct.size == 1 && distinct[0].isNotBlank()) {
                    return distinct[0]
                }
            }
        }
        return value
    }

    private fun ancestorName(start: File, depth: Int): String? {
        var current: File? = start
        repeat(depth) { current = current?.parentFile }
        return current?.name?.trim()?.takeIf(::isPlausibleFolderValue)
    }

    private fun inferDiscNumber(folderName: String): Int? =
        DISC_FOLDER_PATTERN.matchEntire(folderName.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    private fun isPlausibleFolderValue(value: String): Boolean {
        val candidate = value.trim()
        return candidate.length >= 2 && !FOLDER_CODE_PATTERN.matches(candidate)
    }

    /**
     * Add a filename-parsed proposal only when the target field is currently missing.
     * This enforces the forbidden rule: never overwrite a non-empty field.
     */
    private fun maybeAddFilenameProposal(
        proposals: MutableList<TagFieldProposal>,
        snapshot: TagSnapshot,
        field: TagField,
        proposedValue: String,
        filenameStem: String,
    ) {
        val current = snapshot.fields[field]?.value
        if (!current.isNullOrBlank()) return // FORBIDDEN: never overwrite non-empty field
        if (proposedValue.isBlank()) return

        proposals += TagFieldProposal(
            field = field,
            ruleId = RULE_PARSE_FILENAME,
            currentValue = null,
            proposedValue = proposedValue,
            confidence = 0.70,
            autoPreselected = false,
            explanation = "Parsed $field \"$proposedValue\" from filename \"$filenameStem\".",
            warnings = listOf("Filename parsing may be incorrect; verify manually."),
        )
    }

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        // Rule IDs
        const val RULE_TRIM_WHITESPACE = "trimLeadingTrailingWhitespace"
        const val RULE_NORMALIZE_TRACK_DISC = "normalizeTrackDiscNumbers"
        const val RULE_REMOVE_DUPLICATE_VARIANTS = "removeDuplicateTagVariants"
        const val RULE_SAFE_NORMALIZE = "safeNormalizeTextAndNumbers"
        const val RULE_COPY_UNANIMOUS_ALBUM = "copyUnanimousAlbumValue"
        const val RULE_PARSE_FILENAME = "parseFilenameGroups"
        const val RULE_INFER_TITLE_FILENAME = "inferTitleFromFilenameStem"
        const val RULE_INFER_ALBUM_FOLDER = "inferAlbumFromFolder"
        const val RULE_INFER_ARTIST_FOLDER = "inferArtistFromFolderHierarchy"
        const val RULE_INFER_DISC_FOLDER = "inferDiscNumberFromFolder"
        const val RULE_SEQUENCE_TRACKS = "sequenceTracksFromOrder"

        // Track/disc fields subject to numeric normalization
        private val TRACK_DISC_FIELDS = setOf(
            TagField.TRACK_NUMBER,
            TagField.TRACK_TOTAL,
            TagField.DISC_NUMBER,
            TagField.DISC_TOTAL,
        )
        private val FREEFORM_FIELDS = setOf(TagField.COMMENT, TagField.LYRICS)
        private val COLLAPSIBLE_TEXT_FIELDS = setOf(
            TagField.TITLE,
            TagField.ARTIST,
            TagField.ALBUM,
            TagField.ALBUM_ARTIST,
            TagField.GENRE,
            TagField.COMPOSER,
        )
        private val DEDUPLICATABLE_FIELDS = setOf(
            TagField.ARTIST,
            TagField.ALBUM_ARTIST,
            TagField.GENRE,
            TagField.COMPOSER,
        )
        private val INTERNAL_WHITESPACE = Regex("[\\p{Z}\\t]{2,}")

        // "NN - Artist - Title" or "NN- Artist - Title"
        private val TRIPLE_PATTERN = Regex("""(\d+)\s*[-.]\s*(.+?)\s*[-]\s*(.+)""")

        // "NN. Title" or "NN Title"
        private val TRACK_TITLE_PATTERN = Regex("""(\d+)\s*[.\- ]\s*(.+)""")

        // "Artist - Title"
        private val DUAL_PATTERN = Regex("""(.+?)\s*[-]\s*(.+)""")

        // N/M compound number
        private val COMPOUND_NUMBER_PATTERN = Regex("""(\d+)\s*/\s*(\d+)""")
        private val PURE_NUMBER_PATTERN = Regex("""\d+""")

        // Folder names that look like codes, dates, or pure numbers
        private val FOLDER_CODE_PATTERN = Regex("""[\d._\- ]{1,8}""")
        private val DISC_FOLDER_PATTERN = Regex("(?i)^(?:cd|disc|disk|part)\\s*[-_. ]*0*(\\d{1,3})$")
        private val LEADING_TRACK_PREFIX = Regex("^\\s*\\d+\\s*[-._ ]+\\s*")

        // Separators commonly emitted by duplicate tag variant writes
        private val VARIANT_SEPARATORS = listOf(" / ", " ; ", " | ", " & ")
    }
}

/**
 * Folder-level consistency report.
 */
data class FolderConsistency(
    /** True when all non-null ALBUM values in the folder are identical. */
    val albumConsistent: Boolean,
    /** True when all non-null ALBUM_ARTIST values in the folder are identical. */
    val albumArtistConsistent: Boolean,
    /** Value frequency map for ALBUM (null represents missing). */
    val albumValues: Map<String?, Int>,
    /** Value frequency map for ALBUM_ARTIST (null represents missing). */
    val albumArtistValues: Map<String?, Int>,
)
