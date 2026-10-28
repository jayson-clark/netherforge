package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.VideoFile
import dev.netherforge.plugin.binding.LuaHandle
import java.util.UUID
import kotlin.math.floor

/**
 * A video playing on a screen: [reader]'s frames drawn onto the canvas of [screen] it was played on ([viewer]'s
 * own view, or the screen everyone sees), with their top left at ([x], [y]). Its [feed] reads them ahead, off the
 * main thread.
 *
 * [position] is where it is in seconds; each tick moves it by [speed] / 20 and draws the frames it passed (from
 * the key frame before when it jumped). Its sound is [Audio]'s.
 */
class Playback(
    val number: Long,
    /** The video as the project names it (`trailer`, `acme:trailer`). */
    val video: String,
    val screen: ScreenState,
    /** Whose own view it plays on; null for the screen everyone sees. */
    val viewer: UUID?,
    val reader: NfvReader,
    val feed: FrameFeed,
    /** The scope that played it, or null for the screen's file (`video` in `screen.json`). */
    val owner: Int?,
    val x: Int,
    val y: Int,
    var loop: Boolean,
    var speed: Double,
    var volume: Double,
    val audioRange: Double,
    /** The video's sounds, or null for none. */
    val audio: Audio?
) {
    /** The video's sounds in the resource pack: [whole] from its start, and [chunks] pieces of [Audio.CHUNK] seconds each. */
    class Audio(val whole: String, val chunks: List<String>) {
        companion object {
            const val CHUNK = VideoFile.AUDIO_CHUNK_SECONDS.toDouble()
        }
    }

    val handle = LuaHandle.Playback(number, video)

    var position = 0.0
    var paused = false
    var ended = false

    /** The frame last drawn; -1 before the first. */
    var shown = -1

    /** Who hears it from its start, the whole sound, and who hears it a piece at a time (and which piece). */
    val wholeFor = HashSet<UUID>()
    val pieceFor = HashMap<UUID, Int>()

    /**
     * Whether its sound may play from the start now: it's at its start and hasn't been moved through, so the
     * whole sound lines up. Anything that moves it ([seek], a pause, a speed) puts everyone on pieces.
     */
    var fromStart = true

    val header: NfvReader.Header get() = reader.header

    /** The frame due at [position]. */
    fun frameAt(position: Double): Int = floor(position * header.fps + 1e-9).toInt()

    /**
     * Draws every frame up to [target] onto [canvas]: on from the one shown when that's within a key interval,
     * else from the key frame at or before [target]. The frames drawn, the last one last; none while the feed
     * hasn't read them yet (it's asked to), so the picture holds until they're there. What the feed couldn't read
     * is thrown here, on the main thread.
     */
    fun drawTo(target: Int, canvas: Canvas): Int {
        if (header.frames == 0) return 0
        feed.failure?.let { throw it }
        val last = target.coerceIn(0, header.frames - 1)
        if (last == shown) {
            feed.want(last + 1, last + FrameFeed.AHEAD)
            return 0
        }
        val from = if (last > shown && last - shown <= header.keyInterval && shown >= 0) shown + 1 else reader.keyBefore(last)
        val frames = (from..last).map { index ->
            feed.ready(index) ?: run {
                feed.want(from, last + FrameFeed.AHEAD)
                return 0
            }
        }
        for (frame in frames) reader.draw(frame, canvas, x, y)
        shown = last
        // What comes next, read while this tick goes on.
        feed.want(last + 1, last + FrameFeed.AHEAD)
        return frames.size
    }

    /** Moves to [seconds] (held to the video), as a jump: the sound picks up again at the next piece. */
    fun seek(seconds: Double) {
        position = seconds.coerceIn(0.0, header.length)
        fromStart = false
    }
}
