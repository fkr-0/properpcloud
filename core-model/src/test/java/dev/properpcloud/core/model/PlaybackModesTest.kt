package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModesTest {
    @Test
    fun `player targets deduplicate by stable provider identity and prefer live observations`() {
        val id = PlayerTargetId("server:a:kitchen")
        val stale = PlayerTargetSnapshot(
            id = id,
            providerId = "server:a",
            providerName = "Server",
            displayName = "Kitchen",
            connectivity = PlayerConnectivity.DEGRADED,
            stale = true,
            observedAtEpochMillis = 20,
        )
        val connected = stale.copy(
            connectivity = PlayerConnectivity.CONNECTED,
            playbackState = PlayerPlaybackState.PLAYING,
            currentMediaTitle = "Chapter 4",
            stale = false,
            observedAtEpochMillis = 10,
        )

        val merged = PlayerTargetMergePolicy.merge(listOf(stale, connected))

        assertEquals(1, merged.size)
        assertEquals(PlayerConnectivity.CONNECTED, merged.single().connectivity)
        assertEquals("Chapter 4", merged.single().currentMediaTitle)
        assertFalse(merged.single().stale)
    }

    @Test
    fun `audiobook resume is book scoped and clamps to chapter duration`() {
        val point = AudiobookResumePoint(
            bookId = AudiobookBookId(SourceId("pcloud"), NodeId("book:1")),
            chapterNodeId = NodeId("chapter:7"),
            positionMillis = 95_000,
            durationMillis = 120_000,
            playbackSpeed = 1.5f,
            updatedAtEpochMillis = 123,
        )

        assertEquals(80_000, point.clampedPositionMillis(80_000))
        assertEquals(NodeId("chapter:7"), point.chapterNodeId)
        assertEquals(1.5f, point.playbackSpeed)
    }

    @Test
    fun `manual chapter intent ignores same-media restart and only matches expected transition within window`() {
        val now = 10_000L
        assertEquals(null, AudiobookPlaybackPolicy.manualNavigationIntent("chapter:1", "chapter:1", now))

        val intent = requireNotNull(
            AudiobookPlaybackPolicy.manualNavigationIntent("chapter:1", "chapter:2", now),
        )
        assertTrue(
            AudiobookPlaybackPolicy.matchesManualTransition(
                intent,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:2",
                nowEpochMillis = now + 1_000,
            ),
        )
        assertFalse(
            AudiobookPlaybackPolicy.matchesManualTransition(
                intent,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:3",
                nowEpochMillis = now + 1_000,
            ),
        )
        assertFalse(
            AudiobookPlaybackPolicy.matchesManualTransition(
                intent,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:2",
                nowEpochMillis = now + AudiobookPlaybackPolicy.MANUAL_NAVIGATION_WINDOW_MILLIS + 1,
            ),
        )
    }

    @Test
    fun `chapter previous restarts after threshold and otherwise navigates chapters`() {
        assertFalse(AudiobookPlaybackPolicy.previousRestartsChapter(4_999))
        assertTrue(AudiobookPlaybackPolicy.previousRestartsChapter(5_000))
    }

    @Test
    fun `end of chapter stop applies only to automatic audiobook transition`() {
        assertTrue(
            AudiobookPlaybackPolicy.shouldStopAtChapterBoundary(
                enabled = true,
                wasPlaying = true,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:2",
                manualNavigation = false,
            ),
        )
        assertFalse(
            AudiobookPlaybackPolicy.shouldStopAtChapterBoundary(
                enabled = true,
                wasPlaying = true,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:2",
                manualNavigation = true,
            ),
        )
        assertFalse(
            AudiobookPlaybackPolicy.shouldStopAtChapterBoundary(
                enabled = false,
                wasPlaying = true,
                previousMediaId = "chapter:1",
                nextMediaId = "chapter:2",
                manualNavigation = false,
            ),
        )
    }

    @Test
    fun `mode switch preserves audiobook book timer resume and queue independently from music`() {
        val source = SourceId("pcloud")
        val book = AudiobookBookId(source, NodeId("book:1"))
        val chapter = AudioTrack(
            sourceId = source,
            id = NodeId("chapter:2"),
            parentId = book.bookNodeId,
            name = "02.m4b",
        )
        var collection = AudioTabDefaults.collection()
        collection = AudioTabReducer.updateActive(collection) { tab ->
            tab.copy(
                queue = PlaybackQueue(entries = listOf(QueueEntry(chapter)), currentIndex = 0),
                playbackPositionMillis = 41_000,
                playbackSpeed = 1.5f,
                activeAudiobookBookId = book,
                sleepTimerEndsAtEpochMillis = 999_000,
            )
        }
        collection = AudioTabReducer.upsertAudiobookResume(
            collection,
            AudiobookResumePoint(
                bookId = book,
                chapterNodeId = chapter.id,
                positionMillis = 41_000,
                durationMillis = 100_000,
                playbackSpeed = 1.5f,
                updatedAtEpochMillis = 123,
            ),
        )

        val music = AudioTabReducer.switch(collection, AudioTabId("music"), 41_000)

        assertEquals(PlaybackContentMode.MUSIC, music.active.definition.contentMode)
        assertTrue(music.active.queue.entries.isEmpty())
        val parkedBook = music.tabs.first { it.definition.id == AudioTabId("audiobooks") }
        assertEquals(book, parkedBook.activeAudiobookBookId)
        assertEquals(999_000L, parkedBook.sleepTimerEndsAtEpochMillis)
        assertEquals(1.5f, parkedBook.playbackSpeed)
        assertEquals(chapter.id, parkedBook.queue.current?.track?.id)
        assertEquals(chapter.id, AudioTabReducer.audiobookResume(music, book)?.chapterNodeId)

        val restored = AudioTabReducer.switch(music, AudioTabId("audiobooks"), 0)

        assertEquals(PlaybackContentMode.AUDIOBOOK, restored.active.definition.contentMode)
        assertEquals(book, restored.active.activeAudiobookBookId)
        assertEquals(999_000L, restored.active.sleepTimerEndsAtEpochMillis)
        assertEquals(chapter.id, restored.active.queue.current?.track?.id)
        assertTrue(restored.tabs.first { it.definition.id == AudioTabId("music") }.queue.entries.isEmpty())
    }

    @Test
    fun `audiobook defaults are isolated from music mode`() {
        val collection = AudioTabDefaults.collection()

        assertEquals(PlaybackContentMode.AUDIOBOOK, collection.tabs.first { it.definition.id.value == "audiobooks" }.definition.contentMode)
        assertEquals(PlaybackContentMode.AUDIOBOOK, collection.tabs.first { it.definition.id.value == "sci-fi" }.definition.contentMode)
        assertEquals(PlaybackContentMode.MUSIC, collection.tabs.first { it.definition.id.value == "music" }.definition.contentMode)
        assertEquals(AudiobookPlaybackPolicy.DEFAULT_BACK_SKIP_MILLIS, collection.active.audiobookSkipBackMillis)
        assertEquals(AudiobookPlaybackPolicy.DEFAULT_FORWARD_SKIP_MILLIS, collection.active.audiobookSkipForwardMillis)
        assertTrue(collection.active.stopAtChapterEnd)
    }
}
