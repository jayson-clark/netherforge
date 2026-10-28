package dev.netherforge.plugin.screen

import dev.netherforge.format.Problem
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.screen.MapPalette
import dev.netherforge.format.screen.MapScreen
import dev.netherforge.format.screen.ScreenFile
import dev.netherforge.format.screen.ScreenFontFile
import dev.netherforge.format.screen.ScreenFontKind
import dev.netherforge.format.screen.ScreenKind
import dev.netherforge.format.screen.ScreenViewers
import dev.netherforge.format.screen.VideoKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.screen.ScreenVideos.EndReason
import dev.netherforge.plugin.screen.ScreenVideos.PlayOptions
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import dev.netherforge.plugin.store.Store
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Screens: the project's (`screens/<id>/screen.json`) and those scripts make (`nf.screens.create`), their fonts
 * (`screen_fonts/`) and the videos they play (`videos/`). The service keeps which screens there are, the numbers
 * of their maps, who each is drawn for, and their fonts and colours; the rest is its parts':
 *
 * - **Maps.** A screen is maps side by side, each a number the server never made itself
 *   ([dev.netherforge.plugin.platform.ScreenOps]): a project screen's numbers, and a keyed script screen's, are kept
 *   in the store for good ([Store.ScreenMaps]), from [MAP_BASE] up, so a map item or frame of one shows it again
 *   after a restart; a script's screen without a key takes numbers for the session. No number is given twice.
 * - **Sending** ([MapSending]): once a tick ([TickPhase.UPKEEP], after scripts and videos have drawn) each player
 *   it's drawn for ([Viewer]) is sent what changed, within their budget. A player who starts seeing it is sent it whole.
 * - **Frames** ([ScreenFrames]): a screen that hangs somewhere ([Placement]) is item frames, one a map, never saved
 *   with their chunk: hung again whenever their chunk is loaded and they aren't there, taken down when the session ends.
 * - **Clicks and aim** ([ScreenAim]): a click on a frame, a swing aimed from further away, and `hover`/`leave`.
 * - **Videos** ([ScreenVideos]): playbacks, their frames read ahead off the main thread, and their sound.
 */
class Screens(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val store: Store.ScreenMaps,
    config: ScreensConfig,
    /** Where videos' frames are read: the workers' `"videos"` lane, never the main thread. */
    videoLane: Executor,
    private val readSource: (String) -> String?,
    private val readBytes: (String) -> ByteArray?,
    resolve: (String) -> Path?,
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "screens"

    private val screens = LinkedHashMap<String, ScreenState>()
    private var nextScreen = 1L

    /** The map numbers kept for good, by (namespace, screen key), each by its tile's stable number. */
    private val kept = HashMap<Pair<String, String>, MutableMap<Int, Int>>()
    private var nextMap = MAP_BASE

    private val fontFiles = HashMap<String, ScreenFontFile>()
    private val fonts = HashMap<String, ScreenFont>()
    private var gameFont: ScreenFont? = null

    private var ticks = 0L

    private val frames = ScreenFrames(platform)
    private val sending = MapSending(platform, config)
    private val aim = ScreenAim(platform, scripts, frames, { screens.values }, { ticks })
    private val videos = ScreenVideos(platform, scripts, log, videoLane, resolve, placeOf, namespace)

    val images = ScreenImages(readBytes)

    private var cachedPalette: MapPalette? = null

    /** The game's map colours, read once a session. */
    val palette: MapPalette get() = cachedPalette ?: MapPalette.of(platform.game).also { cachedPalette = it }

    // ---- the registry ----------------------------------------------------------------

    fun find(id: String): ScreenState? = screens[id]?.takeIf { !it.removed }

    /** The screen a handle is, and whose own view it is (null for everyone's): null when either is gone. */
    fun resolve(handle: LuaHandle.Screen): Pair<ScreenState, UUID?>? {
        val screen = find(handle.id) ?: return null
        if (handle.player.isEmpty()) return screen to null
        val player = runCatching { UUID.fromString(handle.player) }.getOrNull() ?: return null
        return if (player in screen.personal) screen to player else null
    }

    /** Every project screen, then every script's, that's still there. */
    fun all(): List<ScreenState> = screens.values.filter { !it.removed }

    /** The project's screens' names, for messages. */
    fun projectNames(): List<String> = screens.values.filter { it.owner == null }.map { it.id }.sorted()

    fun fontNames(): List<String> = listOf(ScreenFontFile.DEFAULT) + fontFiles.keys.sorted()

    fun videoNames(): List<String> = videos.names()

    fun findPlayback(number: Long): Playback? = videos.find(number)

    override fun define(project: SessionProject) {
        kept.clear()
        for ((key, maps) in store.all()) kept[key] = maps.toMutableMap()
        // Past every number ever given, so an item or frame of a screen that's gone never shows another.
        nextMap = maxOf(MAP_BASE, store.next() ?: 0, (kept.values.flatMap { it.values }.maxOrNull() ?: (MAP_BASE - 1)) + 1)
        fontFiles.clear()
        fontFiles += project.running(ScreenFontKind)
        fonts.clear()
        videos.load(project.everywhere(VideoKind))
        screens.clear()
        for ((name, file) in project.running(ScreenKind)) screens[name] = projectScreen(name, file)
    }

    override fun start() {
        // Who sees each is known before its script draws, so its first frame reaches them on the first tick.
        for (screen in screens.values) view(screen)
        for (screen in screens.values.toList()) startScript(screen)
        for (screen in screens.values.toList()) autoplay(screen)
    }

    override fun stop() {
        for (playback in videos.all().toList()) videos.end(playback, EndReason.UNLOADED)
        for (screen in screens.values) {
            screen.scope?.let(scripts::close)
            screen.scope = null
            frames.takeDown(screen)
        }
        screens.clear()
        images.forget()
    }

    override fun tick(phase: TickPhase) {
        when (phase) {
            TickPhase.EFFECTS -> videos.advance()
            TickPhase.UPKEEP -> {
                ticks++
                if (ticks % HANG_EVERY == 0L) for (screen in screens.values) screen.rehang = true
                for (screen in screens.values.toList()) {
                    if (screen.removed) continue
                    if (screen.rehang) frames.hang(screen)
                    if (ticks % VIEW_EVERY == 0L) view(screen)
                }
                aim.hover()
                sending.flush(all(), ticks)
            }
            else -> {}
        }
    }

    override fun scopeReleased(scope: Scope) {
        for (playback in videos.all().filter { it.owner == scope.id }) videos.end(playback, EndReason.UNLOADED)
        for (screen in screens.values.filter { it.owner == scope.id }) remove(screen)
    }

    override fun playerJoined(player: PlayerRef) {
        for (screen in screens.values) view(screen)
    }

    override fun playerQuit(player: PlayerRef) {
        aim.forget(player.uuid)
        for (screen in screens.values.toList()) {
            aim.leave(screen, player.uuid)
            screen.viewers.remove(player.uuid)
            if (player.uuid in screen.personal) release(screen, player.uuid)
        }
        for (playback in videos.all()) videos.silence(playback, player.uuid)
    }

    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) = frames.loaded(tagged)

    override fun entityGone(id: UUID) = frames.gone(id)

    override fun costs(): Map<String, (Scope) -> Int> = mapOf(
        "screens" to { scope -> screens.values.count { it.owner == scope.id && !it.removed } },
        "videos" to { scope -> videos.all().count { it.owner == scope.id } }
    )

    override fun liveness() = listOf(
        liveness<LuaHandle.Screen> { resolve(it) != null },
        liveness<LuaHandle.Playback> { videos.find(it.number) != null }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ScreenFontKind, VideoKind, ScreenKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            when (kind) {
                ScreenFontKind -> batch.resource(Resource(ScreenFontKind, id), batch.snapshot.running(ScreenFontKind)[id]) { next ->
                    if (next != null) fontFiles[id] = next else fontFiles.remove(id)
                    fonts.remove(id)
                    0
                }
                VideoKind -> {
                    val loaded = batch.snapshot.everywhere(VideoKind)[id]
                    batch.resource(Resource(VideoKind, id), loaded?.takeIf { it.compiled != null }) { next ->
                        videos.changed(id, next)
                        0
                    }
                }
                ScreenKind -> batch.resource(Resource(ScreenKind, id), batch.snapshot.running(ScreenKind)[id]) { next ->
                    reloadScreen(id, next)
                    0
                }
                else -> {}
            }
        }
        images.forget()
    }

    override fun problems(): List<Problem> = videos.stale()

    // ---- project screens -------------------------------------------------------------

    private fun projectScreen(name: String, file: ScreenFile): ScreenState {
        val (pkg, bare) = PackagePaths.split(name)
        val ns = pkg ?: namespace()
        val maps = maps(ns, "screen:$bare", file.width, file.height)
        val screen = ScreenState(name, name, ns, null, settingsOf(file), file.width, file.height, maps, file)
        screen.moveTo(placementOf(file))
        return screen
    }

    private fun placementOf(file: ScreenFile) = file.place?.let { Placement(it.world, it.x, it.y, it.z, it.facing) }

    private fun settingsOf(file: ScreenFile) = ScreenState.Settings(
        file.viewersOrDefault,
        file.rangeOrDefault,
        file.glowingOrDefault,
        file.clickRangeOrDefault,
        file.background?.let(::color) ?: MapPalette.TRANSPARENT
    )

    private fun startScript(screen: ScreenState) {
        val script = screen.file?.script ?: return
        val path = placeOf(ScreenKind, screen.id).file(script.file)
        val source = readSource(path) ?: return
        screen.scope = scripts.start(ScopeOwner.ScreenScript(screen.id, path), script, source).first
    }

    /** A project screen's `video`: played from the start, belonging to the screen. */
    private fun autoplay(screen: ScreenState) {
        val video = screen.file?.video ?: return
        // A package's file is read with its references written in full, so this is the video as the server names it.
        val name = video.video.nameIn(namespace()) ?: return
        try {
            if (play(screen, null, name, null, PlayOptions(loop = video.loopOrDefault, volume = video.volumeOrDefault)) == null) {
                log.warn("Screen ${screen.id} can't play video $name: it hasn't been converted, or its frames can't be read")
            }
        } catch (e: LuaApiException) {
            log.warn("Screen ${screen.id} can't play video $name: ${e.message}")
        }
    }

    private fun reloadScreen(id: String, next: ScreenFile?) {
        val previous = screens[id]
        if (previous == null) {
            if (next == null) return
            val screen = projectScreen(id, next)
            screens[id] = screen
            view(screen)
            startScript(screen)
            autoplay(screen)
            return
        }
        previous.scope?.let(scripts::close)
        previous.scope = null
        for (playback in previous.playbacks.values.filter { it.owner == null }.toList()) videos.end(playback, EndReason.UNLOADED)
        if (next == null) {
            remove(previous)
            screens.remove(id)
            return
        }
        previous.reconfigure(next, settingsOf(next))
        if (next.width != previous.columns || next.height != previous.rows) {
            for (playback in previous.playbacks.values.toList()) videos.end(playback, EndReason.REMOVED)
            frames.takeDown(previous)
            previous.resize(
                next.width,
                next.height,
                maps(previous.namespace, "screen:${PackagePaths.split(id).second}", next.width, next.height)
            )
        } else {
            previous.canvas.fill(0, 0, previous.canvas.width, previous.canvas.height, previous.settings.background)
        }
        val placement = placementOf(next)
        if (placement != previous.placement) frames.takeDown(previous)
        previous.moveTo(placement)
        view(previous)
        startScript(previous)
        autoplay(previous)
    }

    // ---- scripts' screens -------------------------------------------------------------

    /** What `nf.screens.create` takes, checked. */
    data class Made(val columns: Int, val rows: Int, val key: String?, val settings: ScreenState.Settings)

    /** Makes a screen for scope [owner] of package [namespace]. A key another screen still there has is an error. */
    fun create(made: Made, owner: Int, namespace: String): ScreenState {
        if (made.key != null &&
            screens.values.any { !it.removed && it.owner != null && it.namespace == namespace && it.name == made.key }
        ) {
            throw LuaApiException("a screen with the key \"${made.key}\" is still there: remove it first, or use another key")
        }
        val maps = maps(namespace, made.key?.let { "key:$it" }, made.columns, made.rows)
        val screen = ScreenState("#${nextScreen++}", made.key, namespace, owner, made.settings, made.columns, made.rows, maps)
        screens[screen.id] = screen
        view(screen)
        return screen
    }

    /**
     * Takes [screen] away: its frames, its videos, everyone's own views of it, every handler on it. With [forget]
     * (a script removing its own screen), a keyed one gives up its maps' numbers, so its key can be used again
     * without the store keeping every key a script ever made.
     */
    fun remove(screen: ScreenState, forget: Boolean = false) {
        if (screen.removed) return
        if (forget && screen.owner != null && screen.name != null) {
            val key = "key:${screen.name}"
            for (tile in kept.remove(screen.namespace to key)?.keys.orEmpty()) store.forget(screen.namespace, key, tile)
        }
        for (playback in screen.playbacks.values.toList()) videos.end(playback, EndReason.REMOVED)
        frames.takeDown(screen)
        for (player in screen.hovering.keys.toList()) aim.leave(screen, player)
        screen.markRemoved()
        screen.viewers.clear()
        for (player in screen.personal.keys.toList()) scripts.dropTarget(screen.handleFor(player))
        screen.personal.clear()
        scripts.dropTarget(screen.handle)
        if (screen.owner != null) screens.remove(screen.id)
    }

    /** The map numbers of a [columns] by [rows] screen: kept for good under [key] in [namespace], or for now with none. */
    private fun maps(namespace: String, key: String?, columns: Int, rows: Int): IntArray {
        val keep = key?.let { kept.getOrPut(namespace to it) { HashMap() } }
        return IntArray(columns * rows) { tile ->
            val stable = (tile / columns) * MapScreen.MAX_TILES + tile % columns
            keep?.get(stable) ?: nextMap++.also { map ->
                store.setNext(nextMap)
                if (keep != null && key != null) {
                    keep[stable] = map
                    store.put(namespace, key, stable, map)
                }
            }
        }
    }

    // ---- colours, fonts and pictures -----------------------------------------------

    /** [text] as a map colour: `#rrggbb`'s nearest, or transparent for `"clear"` and null. */
    fun color(text: String?): Int {
        if (text == null || text == CLEAR) return MapPalette.TRANSPARENT
        val rgb = MapPalette.parse(text) ?: throw LuaApiException("\"$text\" isn't a colour: write #rrggbb, or \"$CLEAR\"")
        return palette.nearest(rgb)
    }

    /** A map colour as a script reads it: `#rrggbb`, or `"clear"`. */
    fun colorName(index: Int): String = palette.rgb(index)?.let { "#%06x".format(it) } ?: CLEAR

    /** The font [name] (as the project names it, or `default`): loaded the first time it's asked for. */
    fun font(name: String): ScreenFont {
        if (name == ScreenFontFile.DEFAULT) return gameFont ?: ScreenFont.of(platform.screens.font()).also { gameFont = it }
        fonts[name]?.let { return it }
        val file = fontFiles[name] ?: throw LuaApiException(notInProject("screen font", name, fontNames()))
        val bytes = file.file?.let { path ->
            readBytes(placeOf(ScreenFontKind, name).file(path)) ?: throw LuaApiException("screen font \"$name\" has no $path")
        }
        val font = try {
            ScreenFont.of(file, bytes)
        } catch (e: Exception) {
            throw LuaApiException("screen font \"$name\" can't be read: ${e.message ?: e.javaClass.simpleName}")
        }
        fonts[name] = font
        return font
    }

    // ---- hanging and who sees it --------------------------------------------------------

    /** Hangs [screen] at [placement] (null: nowhere), taking down where it hung. */
    fun place(screen: ScreenState, placement: Placement?) {
        frames.takeDown(screen)
        for (player in screen.hovering.keys.toList()) aim.leave(screen, player)
        screen.moveTo(placement)
        frames.hang(screen)
        view(screen)
    }

    /** Works out who [screen] is drawn for now: those it's new to are sent it whole, those it isn't any more forgotten. */
    fun view(screen: ScreenState) {
        if (screen.removed) return
        val wanted = HashMap<UUID, String?>()
        val placement = screen.placement
        for (player in platform.players.online()) {
            val uuid = player.uuid
            if (screen.isHiddenFrom(uuid)) continue
            val at = platform.players.location(uuid) ?: continue
            val near = when {
                screen.isShownTo(uuid) -> true
                screen.settings.viewers == ScreenViewers.SCRIPT -> false
                placement == null -> true
                else -> at.world == placement.world && screen.isNear(at.x, at.y, at.z, screen.settings.range.toDouble())
            }
            if (near) wanted[uuid] = at.world
        }
        for (player in screen.viewers.keys.toList()) {
            val viewer = screen.viewers.getValue(player)
            if (player !in wanted || wanted[player] != viewer.world) {
                screen.viewers.remove(player)
                aim.leave(screen, player)
            }
        }
        for ((player, world) in wanted) {
            // Someone new, or back from another world (their game forgets maps it's no longer in), is sent it whole.
            if (player !in screen.viewers) screen.viewers[player] = Viewer(player, world, screen.tiles)
        }
    }

    fun showTo(screen: ScreenState, player: UUID) {
        screen.showTo(player)
        view(screen)
    }

    fun hideFrom(screen: ScreenState, player: UUID) {
        screen.hideFrom(player)
        view(screen)
    }

    /** [player]'s own view of [screen], made from what everyone sees now when they have none. */
    fun personal(screen: ScreenState, player: UUID): Canvas = screen.personal.getOrPut(player) {
        // It starts as what everyone sees, which is what they have (or are about to be sent): only what's drawn on it from now on is news.
        Canvas(screen.columns, screen.rows).also {
            it.copyFrom(screen.canvas)
            it.settle()
        }
    }

    /** Ends [player]'s own view of [screen]: they're sent what everyone sees. */
    fun release(screen: ScreenState, player: UUID): Boolean {
        screen.personal.remove(player) ?: return false
        screen.playbacks[player]?.let { videos.end(it, EndReason.REMOVED) }
        screen.viewers[player]?.resend(screen.tiles)
        scripts.dropTarget(screen.handleFor(player))
        return true
    }

    // ---- clicks, aim and videos (their parts' own) ----------------------------------------

    /** A click on one of our frames: the screen's, when it's one of theirs. True when the frame is a screen's. */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean = aim.click(entity, player, button, sight)

    /** A swing aimed at a screen within its click range: its click, and true; false when it was the world's. */
    fun swing(player: PlayerRef, button: ClickButton): Boolean = aim.swing(player, button)

    /** The nearest screen [player]'s [eye] meets that's drawn for them, with nothing solid in front of it. */
    fun aim(player: UUID, eye: Ray, reach: Double?): Pair<ScreenState, Placement.Hit>? = aim.aim(player, eye, reach)

    fun play(screen: ScreenState, viewer: UUID?, video: String, owner: Int?, options: PlayOptions): Playback? =
        videos.play(screen, viewer, video, owner, options)

    fun pause(playback: Playback) = videos.pause(playback)

    fun resume(playback: Playback) = videos.resume(playback)

    fun seek(playback: Playback, seconds: Double) = videos.seek(playback, seconds)

    fun setSpeed(playback: Playback, speed: Double) = videos.setSpeed(playback, speed)

    fun setVolume(playback: Playback, volume: Double) = videos.setVolume(playback, volume)

    fun end(playback: Playback, reason: EndReason) = videos.end(playback, reason)

    companion object {
        /** The first map number screens take: far past any the server will make itself. */
        const val MAP_BASE = 1_000_000_000

        const val CLEAR = "clear"

        /** Ticks between checks that every frame is up (one goes with its chunk). */
        const val HANG_EVERY = 20L

        /** Ticks between working out who's near each screen. */
        const val VIEW_EVERY = 10L
    }
}
