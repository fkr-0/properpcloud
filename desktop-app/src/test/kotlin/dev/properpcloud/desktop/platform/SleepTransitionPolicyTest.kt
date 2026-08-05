package dev.properpcloud.desktop.platform

import dev.properpcloud.desktop.playback.MpvState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTransitionPolicyTest {
    @Test
    fun `active playback checkpoints pauses and refreshes exactly once after wake`() {
        val policy = SleepTransitionPolicy()
        val playing = MpvState(running = true, paused = false, idle = false, positionMillis = 12_000)

        val sleep = policy.transition(preparingForSleep = true, playback = playing)
        val wake = policy.transition(preparingForSleep = false, playback = playing.copy(paused = true))
        val duplicateWake = policy.transition(preparingForSleep = false, playback = playing.copy(paused = true))

        assertTrue(sleep.forceCheckpoint)
        assertTrue(sleep.pausePlayback)
        assertTrue(wake.refreshAndResume)
        assertFalse(duplicateWake.refreshAndResume)
    }

    @Test
    fun `paused idle or failed playback is not automatically resumed`() {
        val states = listOf(
            MpvState(running = true, paused = true, idle = false),
            MpvState(running = true, paused = false, idle = true),
            MpvState(running = false, paused = true, idle = true, unexpectedExit = true),
        )

        states.forEach { playback ->
            val policy = SleepTransitionPolicy()
            val sleep = policy.transition(preparingForSleep = true, playback = playback)
            val wake = policy.transition(preparingForSleep = false, playback = playback)
            assertTrue(sleep.forceCheckpoint)
            assertFalse(sleep.pausePlayback)
            assertFalse(wake.refreshAndResume)
        }
    }
}
