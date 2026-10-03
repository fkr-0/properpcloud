package dev.properpcloud.desktop

import dev.properpcloud.desktop.metadata.DesktopLocalFolderBinding
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import java.io.File
import kotlinx.coroutines.runBlocking

internal data class PlaylistCliOptions(
    val rootDirectory: File,
    val recursive: Boolean = false,
    val onePlaylistPerAlbum: Boolean = false,
    val order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    val write: Boolean = false,
)

internal fun playlistCliRequested(args: Array<String>): Boolean =
    args.contains(PLAYLIST_GENERATE_FLAG)

internal fun parsePlaylistCliOptions(args: Array<String>): PlaylistCliOptions {
    val commandIndex = args.indexOf(PLAYLIST_GENERATE_FLAG)
    require(commandIndex >= 0) { "missing $PLAYLIST_GENERATE_FLAG" }
    val rootArgument = args.getOrNull(commandIndex + 1)
        ?.takeUnless { it.startsWith("--") }
        ?: throw IllegalArgumentException("$PLAYLIST_GENERATE_FLAG requires one local root directory")

    var recursive = false
    var onePlaylistPerAlbum = false
    var write = false
    var order = FolderPlaylistOrder.TAG_TRACK_NUMBER
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            PLAYLIST_GENERATE_FLAG -> index += 2
            "--recursive" -> {
                recursive = true
                index += 1
            }
            "--one-per-album" -> {
                onePlaylistPerAlbum = true
                index += 1
            }
            "--write" -> {
                write = true
                index += 1
            }
            "--order" -> {
                val value = args.getOrNull(index + 1)
                    ?: throw IllegalArgumentException("--order requires one of: filename, disc-track, tagged-title, title-number, modification-time")
                order = parsePlaylistOrder(value)
                index += 2
            }
            else -> throw IllegalArgumentException("unknown playlist option: $argument")
        }
    }
    require(!onePlaylistPerAlbum || recursive) {
        "--one-per-album requires --recursive so album/disc folders are reviewed as one explicit tree"
    }
    return PlaylistCliOptions(
        rootDirectory = File(rootArgument),
        recursive = recursive,
        onePlaylistPerAlbum = onePlaylistPerAlbum,
        order = order,
        write = write,
    )
}

internal fun runPlaylistCli(
    args: Array<String>,
    output: (String) -> Unit = ::println,
    errorOutput: (String) -> Unit = System.err::println,
    bindingFactory: (File, Boolean) -> DesktopLocalFolderBinding = { root, recursive ->
        DesktopLocalFolderBinding.createSelected(root, recursive = recursive)
    },
): Int {
    if (args.contains("--help")) {
        output(PLAYLIST_CLI_USAGE)
        return 0
    }
    val options = try {
        parsePlaylistCliOptions(args)
    } catch (error: IllegalArgumentException) {
        errorOutput("properpcloud playlist: ${error.message}")
        errorOutput(PLAYLIST_CLI_USAGE)
        return 2
    }

    return runBlocking {
        val binding = try {
            bindingFactory(options.rootDirectory, options.recursive)
        } catch (error: RuntimeException) {
            errorOutput("properpcloud playlist: local root rejected: ${error.message}")
            return@runBlocking 2
        }
        binding.use { selected ->
            val opened = selected.open()
            if (!opened.succeeded) {
                errorOutput("properpcloud playlist: ${opened.message}")
                return@runBlocking 3
            }

            val review = selected.reviewPlaylistBatch(
                recursivePlaylistOptIn = options.recursive,
                onePlaylistPerAlbum = options.onePlaylistPerAlbum,
                order = options.order,
            )
            val reviewed = review.value
            if (reviewed == null) {
                errorOutput("properpcloud playlist: ${review.message}")
                return@runBlocking 3
            }

            val projection = reviewed.projection
            output(
                "Playlist review checkpoint: revision=${projection.revision}, ${projection.playlistCount} file(s), " +
                    "${projection.entryCount} media entry/entries, order=${playlistOrderName(options.order)}.",
            )
            projection.files.forEach { playlist ->
                output("Target: ${playlist.targetRelativePath}")
                playlist.finalLines.forEach(output)
            }

            if (!options.write) {
                output("Preview only: no playlist bytes were written. Re-run with --write to materialize this reviewed batch.")
                return@runBlocking 0
            }

            val materialized = selected.materializePlaylistBatch(
                review = reviewed,
                confirmWrite = true,
            ) { progress ->
                output("Writing playlist ${progress.completed}/${progress.total} (${progress.entryCount} entries).")
            }
            if (!materialized.succeeded) {
                errorOutput("properpcloud playlist: ${materialized.message}")
                return@runBlocking 4
            }
            output("Wrote ${materialized.value!!.results.size} reviewed playlist file(s).")
            0
        }
    }
}

private fun parsePlaylistOrder(value: String): FolderPlaylistOrder = when (value) {
    "filename" -> FolderPlaylistOrder.NATURAL_FILENAME
    "disc-track" -> FolderPlaylistOrder.TAG_TRACK_NUMBER
    "tagged-title" -> FolderPlaylistOrder.TAGGED_TITLE
    "title-number" -> FolderPlaylistOrder.TITLE_NUMBER
    "modification-time", "mtime" -> FolderPlaylistOrder.MODIFICATION_TIME
    else -> throw IllegalArgumentException(
        "unknown playlist order '$value'; expected filename, disc-track, tagged-title, title-number, or modification-time",
    )
}

private fun playlistOrderName(order: FolderPlaylistOrder): String = when (order) {
    FolderPlaylistOrder.NATURAL_FILENAME -> "filename"
    FolderPlaylistOrder.TAG_TRACK_NUMBER -> "disc-track"
    FolderPlaylistOrder.TAGGED_TITLE -> "tagged-title"
    FolderPlaylistOrder.TITLE_NUMBER -> "title-number"
    FolderPlaylistOrder.MODIFICATION_TIME -> "modification-time"
}

private const val PLAYLIST_GENERATE_FLAG = "--generate-playlists"

private val PLAYLIST_CLI_USAGE = """
    Usage: properpcloud --generate-playlists <local-root> [options]

      --recursive          review the complete non-symlink subtree
      --one-per-album      group recognized CD/Disc/Disk/Part folders into album playlists; requires --recursive
      --order <mode>       filename | disc-track | tagged-title | title-number | modification-time
      --write              materialize the exact reviewed batch; omitted means preview only

    title-number orders the leading decimal integer in embedded TITLE numerically (01, 2, 10), then
    uses deterministic title/filename fallbacks for ties, non-numeric titles, and missing titles.
    Preview prints every exact proposed M3U8 line and writes zero playlist bytes. The command never writes
    media tags. Playlist entries remain ./-relative, and stale membership/content/revision evidence aborts
    materialization before the first output byte instead of writing from an old review.
""".trimIndent()
