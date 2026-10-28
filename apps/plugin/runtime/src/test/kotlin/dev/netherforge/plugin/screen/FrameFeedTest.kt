package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.ScreenViewers
import dev.netherforge.plugin.NfvFiles
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A playback's frames are read on its lane, ahead of where it plays, never on the thread that draws them. */
class FrameFeedTest {
    /** A lane that runs only when the test says, so what ran where is plain. */
    private class HeldLane : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued += command
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private fun reader(frames: Int): NfvReader {
        val file = Files.createTempFile("feed", ".nfv")
        Files.write(file, NfvFiles.write(1, 1, 20, NfvFiles.solid(frames, 4, 10), keyInterval = 10))
        return NfvReader.open(file)
    }

    @Test
    fun `frames are read when the lane runs, ahead of the one wanted, and the rest forgotten`() {
        val lane = HeldLane()
        reader(40).use { reader ->
            val feed = FrameFeed(reader, lane)
            feed.want(0, FrameFeed.AHEAD)
            assertNull(feed.ready(0), "nothing is read on the caller's thread")
            lane.runAll()
            for (index in 0..FrameFeed.AHEAD) assertNotNull(feed.ready(index), "frame $index")
            assertNull(feed.ready(FrameFeed.AHEAD + 1))
            feed.want(3, 8)
            assertNull(feed.ready(0), "behind the window: forgotten")
            lane.runAll()
            assertNotNull(feed.ready(8))
            feed.close()
            assertNull(feed.ready(8))
        }
    }

    @Test
    fun `a playback holds its picture until its frames are there`() {
        val lane = HeldLane()
        reader(40).use { reader ->
            val feed = FrameFeed(reader, lane)
            val canvas = Canvas(1, 1)
            val settings = ScreenState.Settings(ScreenViewers.NEAR, 64, false, 24.0, 0)
            val state = ScreenState("#1", null, "t", 1, settings, 1, 1, intArrayOf(0))
            val playback = Playback(1, "clip", state, null, reader, feed, null, 0, 0, false, 1.0, 1.0, 32.0, null)
            assertEquals(0, playback.drawTo(1, canvas), "not read yet")
            lane.runAll()
            assertEquals(2, playback.drawTo(1, canvas), "frames 0 and 1")
            assertEquals(1, playback.shown)
            assertTrue(lane.queued.isNotEmpty() || feed.ready(2) != null, "and the next ones asked for")
        }
    }
}
