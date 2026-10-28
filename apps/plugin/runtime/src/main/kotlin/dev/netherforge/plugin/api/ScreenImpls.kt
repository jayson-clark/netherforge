package dev.netherforge.plugin.api
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.screen.Dither
import dev.netherforge.format.screen.MapScreen
import dev.netherforge.format.screen.ScreenFacing
import dev.netherforge.format.screen.ScreenFontFile
import dev.netherforge.format.screen.ScreenFontKind
import dev.netherforge.format.screen.ScreenKind
import dev.netherforge.format.screen.ScreenViewers
import dev.netherforge.format.screen.VideoKind
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.NfScreensApi
import dev.netherforge.plugin.binding.PlaybackApi
import dev.netherforge.plugin.binding.ScreenApi
import dev.netherforge.plugin.binding.ScreenImageOptions
import dev.netherforge.plugin.binding.ScreenOptions
import dev.netherforge.plugin.binding.ScreenPoint
import dev.netherforge.plugin.binding.ScreenTextOptions
import dev.netherforge.plugin.binding.ScreenTextSize
import dev.netherforge.plugin.binding.ShapeStyle
import dev.netherforge.plugin.binding.VideoPlayOptions
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.screen.Canvas
import dev.netherforge.plugin.screen.Placement
import dev.netherforge.plugin.screen.Playback
import dev.netherforge.plugin.screen.Raster
import dev.netherforge.plugin.screen.ScreenImages
import dev.netherforge.plugin.screen.ScreenState
import dev.netherforge.plugin.screen.ScreenText
import dev.netherforge.plugin.screen.ScreenVideos
import dev.netherforge.plugin.screen.Screens
import dev.netherforge.plugin.screen.TextLayout
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/** `nf.screens`. */
internal class NfScreensImpl(private val session: ProjectSession) : NfScreensApi {
    private val screens get() = session.screens

    override fun get(caller: Caller, screen: String): LuaHandle.Screen {
        val name = session.names.resource(ScreenKind, screen)
        val found = screens.find(name)?.takeIf { it.owner == null }
            ?: throw LuaApiException(notThere(name))
        return found.handle
    }

    private fun notThere(name: String): String = if (session.snapshot.placeOf(ScreenKind, name).let { place ->
            session.projectFiles.any(place::owns)
        }
    ) {
        "screen \"$name\" has errors, and there's no earlier version to run"
    } else {
        dev.netherforge.plugin.binding.notInProject("screen", name, screens.projectNames())
    }

    override fun all(caller: Caller): List<LuaHandle.Screen> = screens.all().map { it.handle }

    override fun create(caller: Caller, options: ScreenOptions): LuaHandle.Screen {
        val scope = caller.scope
        val columns = tiles("width", options.width)
        val rows = tiles("height", options.height)
        val range = options.range?.let {
            if (it !in 1..MapScreen.MAX_RANGE) throw LuaApiException("options.range is 1 to ${MapScreen.MAX_RANGE} blocks, not $it")
            it.toInt()
        } ?: MapScreen.DEFAULT_RANGE
        val clickRange = options.clickRange?.let {
            if (it.isNaN() || it < 0 || it > MapScreen.MAX_CLICK_RANGE) {
                throw LuaApiException("options.click_range is 0 to ${MapScreen.MAX_CLICK_RANGE.toInt()} blocks, not $it")
            }
            it
        } ?: MapScreen.DEFAULT_CLICK_RANGE
        val key = options.key?.also { if (it.isBlank()) throw LuaApiException("options.key is a name, not empty") }
        val settings = ScreenState.Settings(
            if (options.viewers == "script") ScreenViewers.SCRIPT else ScreenViewers.NEAR,
            range,
            options.glowing ?: true,
            clickRange,
            screens.color(options.background)
        )
        return screens.create(Screens.Made(columns, rows, key, settings), scope.id, scope.namespace).handle
    }

    private fun tiles(field: String, value: Long): Int {
        if (value !in 1..MapScreen.MAX_TILES) throw LuaApiException("options.$field is 1 to ${MapScreen.MAX_TILES} maps, not $value")
        return value.toInt()
    }

    override fun fonts(caller: Caller): List<String> = screens.fontNames().map {
        if (it ==
            ScreenFontFile.DEFAULT
        ) {
            it
        } else {
            session.names.spell(it)
        }
    }

    override fun measureText(caller: Caller, text: String, options: ScreenTextOptions?): ScreenTextSize {
        val (width, height) = ScreenText.measure(text, layout(session, options))
        return ScreenTextSize(width.toLong(), height.toLong())
    }

    override fun mapColor(caller: Caller, color: String): String = screens.colorName(screens.color(color))

    override fun lookingAt(caller: Caller, player: LuaHandle.Player, distance: Double?): ScreenPoint? {
        val uuid = player.uuidOrNull() ?: return null
        val eye = session.platform.players.eye(uuid) ?: return null
        val reach = distance?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_LOOK
        val (screen, hit) = screens.aim(uuid, eye, reach) ?: return null
        return ScreenPoint(screen.handleFor(uuid.takeIf { it in screen.personal }), hit.x.toLong(), hit.y.toLong(), hit.distance)
    }

    companion object {
        const val DEFAULT_LOOK = 64.0
    }
}

/** How text options read, checked: the font named in the calling package, sizes in range. */
internal fun layout(session: ProjectSession, options: ScreenTextOptions?): TextLayout {
    val screens = session.screens
    val fontName =
        options?.font?.let { if (it == ScreenFontFile.DEFAULT) it else session.names.resource(ScreenFontKind, it) }
            ?: ScreenFontFile.DEFAULT
    val scale = options?.scale ?: 1
    if (scale !in 1..8) throw LuaApiException("options.scale is 1 to 8, not $scale")
    val color = options?.color?.let { rgb("options.color", it) } ?: 0xFFFFFF
    val shadow = options?.shadow?.let { rgb("options.shadow", it) }
    val width = options?.width?.let {
        if (it < 1) throw LuaApiException("options.width is 1 pixel or more, not $it")
        it.toInt()
    }
    val lineHeight = options?.lineHeight?.let {
        if (it !in
            1..ScreenFontFile.MAX_LINE_HEIGHT
        ) {
            throw LuaApiException("options.line_height is 1 to ${ScreenFontFile.MAX_LINE_HEIGHT}, not $it")
        }
        it.toInt()
    }
    val align = when (options?.align) {
        "center" -> TextLayout.Align.CENTER
        "right" -> TextLayout.Align.RIGHT
        else -> TextLayout.Align.LEFT
    }
    return TextLayout(screens.font(fontName), color, scale.toInt(), align, width, shadow, lineHeight)
}

private fun rgb(where: String, text: String): Int =
    dev.netherforge.format.screen.MapPalette.parse(text) ?: throw LuaApiException("$where isn't a colour written #rrggbb: \"$text\"")

/** `Screen`: a screen everyone sees, or a player's own view of one; once gone, its drawing does nothing. */
internal class ScreenImpl(private val session: ProjectSession) : ScreenApi {
    private val screens get() = session.screens

    private fun of(self: LuaHandle.Screen): Pair<ScreenState, UUID?>? = screens.resolve(self)

    /** The canvas [self] draws on, or null once it's gone. */
    private fun canvas(self: LuaHandle.Screen): Canvas? = of(self)?.let { (screen, player) -> screen.canvasFor(player) }

    override fun name(self: LuaHandle.Screen): String? {
        val screen = of(self)?.first ?: return null
        return if (screen.owner == null) session.names.spell(screen.id) else screen.name
    }

    override fun exists(self: LuaHandle.Screen): Boolean = of(self) != null

    override fun width(self: LuaHandle.Screen): Long = (of(self)?.first?.columns ?: 0).toLong() * MapScreen.TILE

    override fun height(self: LuaHandle.Screen): Long = (of(self)?.first?.rows ?: 0).toLong() * MapScreen.TILE

    override fun clear(self: LuaHandle.Screen, color: String?) {
        val index = screens.color(color)
        canvas(self)?.let { it.fill(0, 0, it.width, it.height, index) }
    }

    override fun pixel(self: LuaHandle.Screen, x: Long, y: Long): String? {
        val canvas = canvas(self) ?: return null
        if (!canvas.inside(x.toInt(), y.toInt()) || x !in 0..Int.MAX_VALUE || y !in 0..Int.MAX_VALUE) return null
        return screens.colorName(canvas.get(x.toInt(), y.toInt()))
    }

    override fun setPixel(self: LuaHandle.Screen, x: Long, y: Long, color: String?) {
        val index = screens.color(color)
        if (x !in Int.MIN_VALUE..Int.MAX_VALUE || y !in Int.MIN_VALUE..Int.MAX_VALUE) return
        canvas(self)?.set(x.toInt(), y.toInt(), index)
    }

    override fun pixels(self: LuaHandle.Screen, x: Long, y: Long, width: Long, height: Long): List<String> {
        if (width < 0 ||
            height < 0 ||
            width * height > MAX_PIXELS
        ) {
            throw LuaApiException("a block of pixels is at most $MAX_PIXELS, not $width by $height")
        }
        val canvas = canvas(self) ?: return emptyList()
        val out = ArrayList<String>((width * height).toInt())
        for (py in 0 until height) {
            for (px in 0 until width) {
                val ax = x + px
                val ay = y + py
                out +=
                    if (ax in 0 until canvas.width &&
                        ay in 0 until canvas.height
                    ) {
                        screens.colorName(canvas.get(ax.toInt(), ay.toInt()))
                    } else {
                        Screens.CLEAR
                    }
            }
        }
        return out
    }

    override fun setPixels(self: LuaHandle.Screen, x: Long, y: Long, width: Long, colors: List<String>) {
        if (width < 1) throw LuaApiException("width is 1 pixel or more, not $width")
        if (colors.size > MAX_PIXELS) throw LuaApiException("set_pixels takes at most $MAX_PIXELS colours, not ${colors.size}")
        val indices = IntArray(colors.size) { screens.color(colors[it]) }
        val canvas = canvas(self) ?: return
        val w = width.toInt()
        var row = 0
        while (row * w < indices.size) {
            canvas.write(x.toInt(), y.toInt() + row, indices, row * w, minOf(w, indices.size - row * w))
            row++
        }
    }

    override fun fill(self: LuaHandle.Screen, x: Long, y: Long, width: Long, height: Long, color: String) {
        val index = screens.color(color)
        canvas(self)?.fill(x.int, y.int, width.int, height.int, index)
    }

    /** A shape's fill and outline as map colours ([Canvas.SKIP] for none), and the outline's thickness. */
    private fun style(style: ShapeStyle): Triple<Int, Int, Int> {
        if (style.fill == null && style.outline == null) throw LuaApiException("a style has a fill, an outline or both")
        val thickness = style.thickness ?: 1
        if (thickness !in 1..MAX_THICKNESS) throw LuaApiException("style.thickness is 1 to $MAX_THICKNESS, not $thickness")
        return Triple(
            style.fill?.let(screens::color) ?: Canvas.SKIP,
            style.outline?.let(screens::color) ?: Canvas.SKIP,
            thickness.toInt()
        )
    }

    override fun rectangle(self: LuaHandle.Screen, x: Long, y: Long, width: Long, height: Long, style: ShapeStyle) {
        val (fill, outline, thickness) = style(style)
        val radius = style.radius ?: 0
        if (radius < 0) throw LuaApiException("style.radius is 0 or more, not $radius")
        val canvas = canvas(self) ?: return
        Raster.rectangle(canvas, x.int, y.int, width.int, height.int, radius.int, fill, outline, thickness)
    }

    override fun line(self: LuaHandle.Screen, x1: Long, y1: Long, x2: Long, y2: Long, color: String, thickness: Long?) {
        val index = screens.color(color)
        val t = thickness ?: 1
        if (t !in 1..MAX_THICKNESS) throw LuaApiException("thickness is 1 to $MAX_THICKNESS, not $t")
        val canvas = canvas(self) ?: return
        Raster.line(canvas, x1.int, y1.int, x2.int, y2.int, index, t.toInt())
    }

    override fun circle(self: LuaHandle.Screen, x: Long, y: Long, radius: Long, style: ShapeStyle) {
        if (radius < 0) throw LuaApiException("radius is 0 or more, not $radius")
        val (fill, outline, thickness) = style(style)
        val canvas = canvas(self) ?: return
        Raster.ellipse(canvas, x.int, y.int, radius.int, radius.int, fill, outline, thickness)
    }

    override fun ellipse(self: LuaHandle.Screen, x: Long, y: Long, radiusX: Long, radiusY: Long, style: ShapeStyle) {
        if (radiusX < 0 || radiusY < 0) throw LuaApiException("an ellipse's radii are 0 or more, not $radiusX and $radiusY")
        val (fill, outline, thickness) = style(style)
        val canvas = canvas(self) ?: return
        Raster.ellipse(canvas, x.int, y.int, radiusX.int, radiusY.int, fill, outline, thickness)
    }

    override fun polygon(self: LuaHandle.Screen, points: List<Long>, style: ShapeStyle) {
        if (points.size < 6 || points.size % 2 != 0) {
            throw LuaApiException("a polygon's points are x, y pairs, at least three of them: ${points.size} numbers isn't")
        }
        if (points.size > MAX_POINTS * 2) throw LuaApiException("a polygon has at most $MAX_POINTS points")
        val (fill, outline, thickness) = style(style)
        val canvas = canvas(self) ?: return
        Raster.polygon(canvas, IntArray(points.size) { points[it].int }, fill, outline, thickness)
    }

    override fun text(self: LuaHandle.Screen, x: Long, y: Long, text: String, options: ScreenTextOptions?): ScreenTextSize {
        val layout = layout(session, options)
        val canvas = canvas(self) ?: return ScreenTextSize(0, 0)
        val (width, height) = ScreenText.draw(canvas, screens.palette, x.int, y.int, text, layout)
        return ScreenTextSize(width.toLong(), height.toLong())
    }

    override fun image(self: LuaHandle.Screen, x: Long, y: Long, image: String, options: ScreenImageOptions?): Boolean {
        val path = texturePath(image)
        val width = options?.width?.also {
            if (it !in
                1..ScreenImages.MAX_SIDE
            ) {
                throw LuaApiException("options.width is 1 to ${ScreenImages.MAX_SIDE}, not $it")
            }
        }
        val height = options?.height?.also {
            if (it !in
                1..ScreenImages.MAX_SIDE
            ) {
                throw LuaApiException("options.height is 1 to ${ScreenImages.MAX_SIDE}, not $it")
            }
        }
        val dither = when (options?.dither) {
            "none" -> Dither.NONE
            "ordered" -> Dither.ORDERED
            else -> Dither.FLOYD_STEINBERG
        }
        val canvas = canvas(self) ?: return false
        val picture = screens.images.load(path, width?.toInt(), height?.toInt(), dither, screens.palette) ?: return false
        Raster.blit(canvas, x.int, y.int, picture.colors, picture.width)
        return true
    }

    /** A resource pack's texture by `<pack>/<path>` (a package's as `ns:<pack>/<path>`): its project path. */
    private fun texturePath(image: String): String {
        val from = session.names.calling()
        val key = ResourceRef(image).resolve(from)?.takeIf { '/' in it.path }
            ?: throw LuaApiException("\"$image\" isn't a picture: name a resource pack's texture as <pack>/<path>, like \"ui/logo\"")
        val pack = key.path.substringBefore('/')
        val name = if (key.namespace == session.namespace) pack else PackagePaths.of(key.namespace, pack)
        if (name !in session.snapshot.everywhere(ResourcePackKind)) {
            throw LuaApiException(
                "there's no resource pack \"$pack\"" + if (key.namespace != from) " in package \"${key.namespace}\"" else ""
            )
        }
        val path = CompiledResourcePack.texturePath(name, key.path.substringAfter('/') + ".png")
        if (path !in
            session.projectFiles
        ) {
            throw LuaApiException("resource pack \"$pack\" has no texture ${key.path.substringAfter('/')}.png")
        }
        return path
    }

    override fun copy(self: LuaHandle.Screen, x: Long, y: Long, width: Long, height: Long, toX: Long, toY: Long) {
        if (width * height > MAX_PIXELS * 64L) throw LuaApiException("a copy is at most a screen's pixels")
        canvas(self)?.copy(x.int, y.int, width.int, height.int, toX.int, toY.int)
    }

    override fun place(self: LuaHandle.Screen, location: LuaLocation, facing: String): Boolean {
        val screen = of(self)?.first ?: return false
        val world = location.world.name
        if (!session.platform.worlds.exists(world)) return false
        val p = location.position
        val face = ScreenFacing.entries.first { it.name.lowercase() == facing }
        screens.place(
            screen,
            Placement(world, kotlin.math.floor(p.x).toInt(), kotlin.math.floor(p.y).toInt(), kotlin.math.floor(p.z).toInt(), face)
        )
        return true
    }

    override fun takeDown(self: LuaHandle.Screen): Boolean {
        val screen = of(self)?.first ?: return false
        if (screen.placement == null) return false
        screens.place(screen, null)
        return true
    }

    override fun location(self: LuaHandle.Screen): LuaLocation? {
        val placement = of(self)?.first?.placement ?: return null
        return LuaLocation(LuaHandle.World(placement.world), Vec3(placement.x.toDouble(), placement.y.toDouble(), placement.z.toDouble()))
    }

    override fun facing(self: LuaHandle.Screen): String? = of(self)?.first?.placement?.facing?.name?.lowercase()

    override fun item(self: LuaHandle.Screen, column: Long?, row: Long?): ItemData {
        val screen = of(self)?.first ?: throw LuaApiException("the screen is gone")
        val c = column ?: 0
        val r = row ?: 0
        if (c !in 0 until screen.columns || r !in 0 until screen.rows) {
            throw LuaApiException("the screen is ${screen.columns} by ${screen.rows} maps: there's no map at column $c, row $r")
        }
        return session.platform.screens.mapItem(screen.maps[(r * screen.columns + c).toInt()])
    }

    override fun showTo(self: LuaHandle.Screen, player: LuaHandle.Player): Boolean {
        val screen = of(self)?.first ?: return false
        val uuid = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return false
        screens.showTo(screen, uuid)
        return true
    }

    override fun hideFrom(self: LuaHandle.Screen, player: LuaHandle.Player): Boolean {
        val screen = of(self)?.first ?: return false
        val uuid = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return false
        screens.hideFrom(screen, uuid)
        return true
    }

    override fun isShownTo(self: LuaHandle.Screen, player: LuaHandle.Player): Boolean {
        val screen = of(self)?.first ?: return false
        return player.uuidOrNull()?.let { it in screen.viewers } == true
    }

    override fun viewers(self: LuaHandle.Screen): List<LuaHandle.Player> =
        of(self)?.first?.viewers?.keys?.map { LuaHandle.Player(it.toString()) }.orEmpty()

    override fun forPlayer(self: LuaHandle.Screen, player: LuaHandle.Player): LuaHandle.Screen {
        val (screen, own) = of(self) ?: throw LuaApiException("the screen is gone")
        val uuid = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null }
            ?: throw LuaApiException("a player's own view is for someone online")
        if (own != null &&
            own != uuid
        ) {
            throw LuaApiException("this is another player's own view: call for_player on the screen everyone sees")
        }
        screens.personal(screen, uuid)
        return screen.handleFor(uuid)
    }

    override fun player(self: LuaHandle.Screen): LuaHandle.Player? = of(self)?.second?.let { LuaHandle.Player(it.toString()) }

    override fun release(self: LuaHandle.Screen): Boolean {
        val (screen, player) = of(self) ?: return false
        return player != null && screens.release(screen, player)
    }

    override fun play(self: LuaHandle.Screen, video: String, options: VideoPlayOptions?): LuaHandle.Playback? {
        val name = session.names.resource(VideoKind, video)
        val speed = options?.speed ?: 1.0
        if (speed.isNaN() || speed <= 0) throw LuaApiException("options.speed is more than 0, not $speed")
        val volume = options?.volume ?: 1.0
        if (volume.isNaN() || volume < 0 || volume > 1) throw LuaApiException("options.volume is 0 to 1, not $volume")
        val start = options?.start ?: 0.0
        if (start.isNaN() || start < 0) throw LuaApiException("options.start is 0 seconds or more, not $start")
        val audioRange = options?.audioRange ?: ScreenVideos.DEFAULT_AUDIO_RANGE
        if (audioRange.isNaN() || audioRange < 0) throw LuaApiException("options.audio_range is 0 blocks or more, not $audioRange")
        val (screen, player) = of(self) ?: return null
        val play = ScreenVideos.PlayOptions(
            loop = options?.loop ?: false,
            speed = speed,
            start = start,
            volume = volume,
            x = options?.x?.int ?: 0,
            y = options?.y?.int ?: 0,
            audioRange = audioRange
        )
        return screens.play(screen, player, name, session.scripts.host?.runningScope(), play)?.handle
    }

    override fun playback(self: LuaHandle.Screen): LuaHandle.Playback? {
        val (screen, player) = of(self) ?: return null
        return screen.playbacks[player]?.handle
    }

    override fun remove(self: LuaHandle.Screen): Boolean {
        val screen = of(self)?.first ?: return false
        if (screen.owner == null) {
            throw LuaApiException(
                "screen \"${session.names.spell(screen.id)}\" is the project's: its file says it's there, so it can't be removed"
            )
        }
        screens.remove(screen, forget = true)
        return true
    }

    companion object {
        /** The most pixels one call reads or sets: four maps' worth. */
        const val MAX_PIXELS = 65536
        const val MAX_THICKNESS = 32
        const val MAX_POINTS = 1024

        /** A coordinate as a canvas takes it: far-off ones held where they can't overflow. */
        private val Long.int: Int get() = coerceIn(-1_000_000L, 1_000_000L).toInt()
    }
}

/** `Playback`: a video playing on a screen, by number; once it has ended, nil and false. */
internal class PlaybackImpl(private val session: ProjectSession) : PlaybackApi {
    private val screens get() = session.screens

    private fun of(self: LuaHandle.Playback): Playback? = screens.findPlayback(self.number)?.takeIf { !it.ended }

    override fun video(self: LuaHandle.Playback): String = session.names.spell(self.video)

    override fun screen(self: LuaHandle.Playback): LuaHandle.Screen? = of(self)?.let { it.screen.handleFor(it.viewer) }

    override fun length(self: LuaHandle.Playback): Double = screens.findPlayback(self.number)?.header?.length ?: 0.0

    override fun exists(self: LuaHandle.Playback): Boolean = screens.findPlayback(self.number) != null

    override fun isPaused(self: LuaHandle.Playback): Boolean = of(self)?.paused == true

    override fun pause(self: LuaHandle.Playback): Boolean = of(self)?.let(screens::pause) == true

    override fun resume(self: LuaHandle.Playback): Boolean = of(self)?.let(screens::resume) == true

    override fun stop(self: LuaHandle.Playback): Boolean {
        val playback = of(self) ?: return false
        screens.end(playback, ScreenVideos.EndReason.STOPPED)
        return true
    }

    override fun time(self: LuaHandle.Playback): Double? = of(self)?.position

    override fun seek(self: LuaHandle.Playback, seconds: Double): Boolean {
        if (seconds.isNaN()) throw LuaApiException("seconds is a number, not NaN")
        return of(self)?.let { screens.seek(it, seconds) } == true
    }

    override fun speed(self: LuaHandle.Playback): Double? = of(self)?.speed

    override fun setSpeed(self: LuaHandle.Playback, speed: Double?): Boolean {
        if (speed != null && speed.isNaN()) throw LuaApiException("speed is a number, not NaN")
        return of(self)?.let { screens.setSpeed(it, speed ?: 1.0) } == true
    }

    override fun volume(self: LuaHandle.Playback): Double? = of(self)?.volume

    override fun setVolume(self: LuaHandle.Playback, volume: Double?): Boolean {
        if (volume != null && volume.isNaN()) throw LuaApiException("volume is a number, not NaN")
        return of(self)?.let { screens.setVolume(it, volume ?: 1.0) } == true
    }

    override fun isLooping(self: LuaHandle.Playback): Boolean = of(self)?.loop == true

    override fun setLooping(self: LuaHandle.Playback, looping: Boolean): Boolean {
        val playback = of(self) ?: return false
        playback.loop = looping
        return true
    }
}
