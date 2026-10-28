package dev.netherforge.plugin.screen

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.screen.VideoFile
import dev.netherforge.format.screen.VideoKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.PlaybackEndEvent
import dev.netherforge.plugin.binding.PlaybackFrameEvent
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.script.Scripts
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.floor

/**
 * The videos screens play (`videos/`): each video's converted frames and sounds, and every [Playback] going. A
 * playback draws a video's frames onto a screen's canvas as it goes, read ahead off the main thread ([FrameFeed],
 * one reader a video file however many play it); its sound is played to the players it's drawn for near it, the
 * whole sound from its start, or a piece at a time after it was paused or moved through, since the game can't start
 * a sound mid-file.
 */
class ScreenVideos internal constructor(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    /** Where videos' frames are read: the workers' `"videos"` lane, never the main thread. */
    private val videoLane: Executor,
    private val resolve: (String) -> Path?,
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val namespace: () -> String
) {
    /**
     * A video as the server runs it: its file, where its frames are (null until converted) and its sounds, and why
     * its frames don't match its file, when they don't ([stale]).
     */
    class Video(val file: VideoFile, val frames: String?, val audio: Playback.Audio?, val stale: Problem? = null)

    /** What `screen:play` takes, checked. */
    data class PlayOptions(
        val loop: Boolean = false,
        val speed: Double = 1.0,
        val start: Double = 0.0,
        val volume: Double = 1.0,
        val x: Int = 0,
        val y: Int = 0,
        val audioRange: Double = DEFAULT_AUDIO_RANGE
    )

    /** Why a video ended, as `event.reason` says it. */
    enum class EndReason(val luaName: String) {
        FINISHED("finished"),
        STOPPED("stopped"),
        REPLACED("replaced"),
        REMOVED("removed"),
        UNLOADED("unloaded")
    }

    private val videos = HashMap<String, Video>()

    /** A video's frames file, open while anything plays it: one reader for every playback of that file. */
    private class OpenVideo(val reader: NfvReader) {
        var playing = 0
    }

    /** Each video open now, by the [Video] it was opened for (a reload makes a new one), and each playback's. */
    private val open = HashMap<Video, OpenVideo>()
    private val playing = HashMap<Long, Pair<Video, OpenVideo>>()

    private val playbacks = LinkedHashMap<Long, Playback>()
    private var nextPlayback = 1L

    fun names(): List<String> = videos.keys.sorted()

    fun find(number: Long): Playback? = playbacks[number]

    /** Every playback going, in the order they started. */
    fun all(): Collection<Playback> = playbacks.values

    fun stale(): List<Problem> = videos.values.mapNotNull { it.stale }

    /** The project's videos, everywhere: by the name the server knows each by. */
    fun load(all: Map<String, Loaded<VideoFile, VideoFile>>) {
        videos.clear()
        for ((name, loaded) in all) videoOf(name, loaded)?.let { videos[name] = it }
    }

    /** Video [id] changed (null: it's gone). Those playing keep the frames they opened; the next play takes the new ones. */
    fun changed(id: String, loaded: Loaded<VideoFile, VideoFile>?) {
        val video = loaded?.let { videoOf(id, it) }
        if (video != null) videos[id] = video else videos.remove(id)
    }

    private fun videoOf(name: String, loaded: Loaded<VideoFile, VideoFile>): Video? {
        val file = loaded.compiled ?: return null
        val place = placeOf(VideoKind, name)
        val frames = place.file(VideoFile.FRAMES).takeIf { VideoFile.FRAMES in loaded.files }
        val (pkg, id) = PackagePaths.split(name)
        val ns = pkg ?: namespace()
        val audio = if (file.audioOrDefault && VideoFile.AUDIO in loaded.files) {
            val pieces = loaded.files.mapNotNull(VideoFile::chunkOf).toSet()
            val chunks = generateSequence(0) { it + 1 }.takeWhile { it in pieces }.map { "$ns:${VideoFile.chunkKey(id, it)}" }.toList()
            Playback.Audio("$ns:${VideoFile.soundKey(id)}", chunks)
        } else {
            null
        }
        return Video(file, frames, audio, frames?.let { staleness(name, file, it) })
    }

    /** Why the frames at [frames] weren't converted for [file] as it is now (or can't be read), or null when they were. */
    private fun staleness(name: String, file: VideoFile, frames: String): Problem? {
        val path = resolve(frames) ?: return null
        val main = placeOf(VideoKind, name).path
        val header = try {
            NfvReader.open(path).use { it.header }
        } catch (e: IOException) {
            return ProblemCodes.VIDEO_STALE.at(main, "Its converted frames can't be read (${e.message}): convert it again")
        }
        val differs = buildList {
            if (header.columns != file.width || header.rows != file.height) add("${header.columns} by ${header.rows} maps")
            if (header.fps != file.fpsOrDefault) add("${header.fps} frames a second")
            if (header.dither != file.ditherOrDefault) add("dithered ${header.dither.name.lowercase()}")
            if (header.fit != file.fitOrDefault) add("fit ${header.fit.name.lowercase()}")
            if (header.audio != file.audioOrDefault) add(if (header.audio) "with sound" else "without sound")
        }
        if (differs.isEmpty()) return null
        return ProblemCodes.VIDEO_STALE.at(
            main,
            "Its frames were converted ${differs.joinToString(", ")}: convert it again to match its file"
        )
    }

    /**
     * Plays [video] (as the project names it) on [screen] ([viewer]'s own view, or everyone's), for [owner]. Null
     * when it hasn't been converted, or its frames can't be read; a video the project doesn't have is an error.
     */
    fun play(screen: ScreenState, viewer: UUID?, video: String, owner: Int?, options: PlayOptions): Playback? {
        val found = videos[video] ?: throw LuaApiException(notInProject("video", video, names()))
        val path = found.frames?.let(resolve) ?: return null
        // Its header and index are read once, as it's first played; its frames on the lane as it plays.
        val opened = open[found] ?: try {
            OpenVideo(NfvReader.open(path)).also { open[found] = it }
        } catch (e: IOException) {
            log.warn("Video $video can't be played: ${e.message}")
            return null
        }
        val reader = opened.reader
        val playback = Playback(
            nextPlayback++,
            video,
            screen,
            viewer,
            reader,
            FrameFeed(reader, videoLane),
            owner,
            options.x,
            options.y,
            options.loop,
            options.speed.coerceIn(MIN_SPEED, MAX_SPEED),
            options.volume.coerceIn(0.0, 1.0),
            options.audioRange,
            found.audio
        )
        playback.position = options.start.coerceIn(0.0, reader.header.length)
        playback.fromStart = playback.position == 0.0
        opened.playing++
        playing[playback.number] = found to opened
        val previous = screen.playbacks[viewer]
        screen.playbacks[viewer] = playback
        playbacks[playback.number] = playback
        previous?.let { end(it, EndReason.REPLACED) }
        try {
            playback.drawTo(playback.frameAt(playback.position), screen.canvasFor(viewer))
        } catch (e: IOException) {
            log.warn("Video $video can't be played: ${e.message}")
            end(playback, EndReason.STOPPED)
            return null
        }
        if (playback.fromStart) startWhole(playback)
        return playback
    }

    /** Each tick: every video moves on, draws the frames it passed, and plays its sound. */
    fun advance() {
        for (playback in playbacks.values.toList()) {
            if (playback.ended) continue
            try {
                step(playback)
            } catch (e: Exception) {
                log.error("Video ${playback.video} failed, so it was stopped", e)
                end(playback, EndReason.STOPPED)
            }
        }
    }

    private fun step(playback: Playback) {
        if (playback.paused) return
        val screen = playback.screen
        if (screen.removed || (playback.viewer != null && playback.viewer !in screen.personal)) return end(playback, EndReason.REMOVED)
        val before = playback.position
        playback.position += playback.speed / TICKS_PER_SECOND
        val header = playback.header
        if (playback.frameAt(playback.position) >= header.frames) {
            if (!playback.loop) {
                playback.drawTo(header.frames - 1, screen.canvasFor(playback.viewer))
                return end(playback, EndReason.FINISHED)
            }
            playback.position = (playback.position - header.length).coerceIn(0.0, header.length)
            silence(playback)
            playback.fromStart = playback.speed == 1.0
            if (playback.fromStart) startWhole(playback)
            sound(playback, playback.position, playback.position)
        } else {
            sound(playback, before, playback.position)
        }
        val target = playback.frameAt(playback.position)
        if (playback.drawTo(target, screen.canvasFor(playback.viewer)) > 0 && scripts.listening(playback.handle, Events.PLAYBACK_FRAME)) {
            scripts.emit(
                Events.PLAYBACK_FRAME,
                playback.handle,
                PlaybackFrameEvent(
                    playback.handle,
                    target.toLong(),
                    target.toDouble() / header.fps
                )
            )
        }
    }

    fun pause(playback: Playback): Boolean {
        if (playback.ended || playback.paused) return false
        playback.paused = true
        silence(playback)
        playback.fromStart = false
        return true
    }

    fun resume(playback: Playback): Boolean {
        if (playback.ended || !playback.paused) return false
        playback.paused = false
        return true
    }

    fun seek(playback: Playback, seconds: Double): Boolean {
        if (playback.ended) return false
        playback.seek(seconds)
        silence(playback)
        playback.drawTo(playback.frameAt(playback.position), playback.screen.canvasFor(playback.viewer))
        return true
    }

    fun setSpeed(playback: Playback, speed: Double): Boolean {
        if (playback.ended) return false
        val next = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (next != playback.speed) {
            playback.speed = next
            silence(playback)
            playback.fromStart = false
        }
        return true
    }

    fun setVolume(playback: Playback, volume: Double): Boolean {
        if (playback.ended) return false
        playback.volume = volume.coerceIn(0.0, 1.0)
        silence(playback)
        playback.fromStart = false
        return true
    }

    /** Ends [playback], its `end` heard while it still answers; the screen keeps its last frame. */
    fun end(playback: Playback, reason: EndReason) {
        if (playback.ended) return
        playback.ended = true
        silence(playback)
        val screen = playback.screen
        if (screen.playbacks[playback.viewer] === playback) screen.playbacks.remove(playback.viewer)
        playback.feed.close()
        playing.remove(playback.number)?.let { (video, opened) -> release(video, opened) }
        try {
            scripts.emit(Events.PLAYBACK_END, playback.handle, PlaybackEndEvent(playback.handle, reason.luaName))
        } finally {
            playbacks.remove(playback.number)
            scripts.dropTarget(playback.handle)
        }
    }

    /** One playback of [video] fewer: the last closes its file, on the lane, after any read still going. */
    private fun release(video: Video, opened: OpenVideo) {
        if (--opened.playing > 0) return
        if (open[video] === opened) open.remove(video)
        val close = Runnable { runCatching { opened.reader.close() } }
        try {
            videoLane.execute(close)
        } catch (_: RejectedExecutionException) {
            close.run()
        }
    }

    /** Who hears [playback] now: who it's drawn for, near where its screen hangs. */
    private fun listeners(playback: Playback): Set<UUID> {
        val screen = playback.screen
        return screen.viewers.keys.filter { player ->
            (playback.viewer == null || playback.viewer == player) &&
                (
                    screen.placement == null ||
                        platform.players.location(player)?.let { screen.isNear(it.x, it.y, it.z, playback.audioRange) } == true
                    )
        }.toSet()
    }

    private fun audible(playback: Playback) = playback.audio != null && !playback.paused && playback.speed == 1.0 && playback.volume > 0.0

    /** Plays the whole sound from its start to everyone who hears it now. */
    private fun startWhole(playback: Playback) {
        val audio = playback.audio ?: return
        if (!audible(playback)) return
        for (player in listeners(playback)) {
            if (platform.sounds.playTo(player, SoundPlay(audio.whole, SOUND_CATEGORY, playback.volume, 1.0))) playback.wholeFor += player
        }
    }

    /** Its sound as it moved from [before] to [now]: those out of earshot stopped, and a new piece started at each boundary. */
    private fun sound(playback: Playback, before: Double, now: Double) {
        val audio = playback.audio ?: return
        if (!audible(playback)) return silence(playback)
        val hearing = listeners(playback)
        for (player in (playback.wholeFor + playback.pieceFor.keys) - hearing) silence(playback, player)
        val piece = floor(now / Playback.Audio.CHUNK + 1e-9).toInt()
        val crossed = piece != floor(before / Playback.Audio.CHUNK + 1e-9).toInt() || (before == now && now == 0.0)
        if (!crossed || piece !in audio.chunks.indices) return
        for (player in hearing) {
            if (player in playback.wholeFor) continue
            playback.pieceFor[player]?.let { platform.sounds.stop(player, audio.chunks.getOrNull(it)) }
            if (platform.sounds.playTo(player, SoundPlay(audio.chunks[piece], SOUND_CATEGORY, playback.volume, 1.0))) {
                playback.pieceFor[player] = piece
            }
        }
    }

    /** Stops [playback]'s sound for [player], or for everyone. */
    fun silence(playback: Playback, player: UUID? = null) {
        val audio = playback.audio ?: return
        val who = player?.let(::setOf) ?: (playback.wholeFor + playback.pieceFor.keys)
        for (p in who) {
            if (playback.wholeFor.remove(p)) platform.sounds.stop(p, audio.whole)
            playback.pieceFor.remove(p)?.let { audio.chunks.getOrNull(it)?.let { name -> platform.sounds.stop(p, name) } }
        }
    }

    companion object {
        const val DEFAULT_AUDIO_RANGE = 32.0
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
        private const val TICKS_PER_SECOND = 20.0
        private const val SOUND_CATEGORY = "record"
    }
}
