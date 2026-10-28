package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.ScreenFile
import dev.netherforge.format.screen.ScreenViewers
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.script.Scope
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * One screen: its pixels ([canvas], and a player's own view in [personal]), the maps they're drawn on ([maps],
 * a number each), where it hangs, and who it's drawn for. [id] keys its handle: a project screen's name (`lobby`,
 * `acme:lobby`), or `#<n>` for one a script made, which no name can be.
 *
 * Only the screen package changes one ([Screens] and the parts it's made of); what scripts reach reads it.
 */
class ScreenState internal constructor(
    val id: String,
    /** The project's screen it is, or the key a script made it with. */
    val name: String?,
    /** The package it's the project's, or whose script made it. */
    val namespace: String,
    /** The scope that made it; null for a project screen. */
    val owner: Int?,
    settings: Settings,
    columns: Int,
    rows: Int,
    maps: IntArray,
    /** The project's file it's made from; null for a script's. */
    file: ScreenFile? = null
) {
    /** How it shows: who for, how near, glowing or not, how far a click reaches, its first colour. */
    data class Settings(val viewers: ScreenViewers, val range: Int, val glowing: Boolean, val clickRange: Double, val background: Int)

    var settings = settings
        private set
    var file = file
        private set
    var columns = columns
        private set
    var rows = rows
        private set
    var maps = maps
        private set
    var canvas = Canvas(columns, rows).also { it.fill(0, 0, it.width, it.height, settings.background) }
        private set

    /** What its item frames are tagged with: a project screen's and a keyed one's the same every time. */
    val uuid: UUID = UUID.nameUUIDFromBytes("screen:$namespace:${name ?: id}:${owner ?: ""}".toByteArray(StandardCharsets.UTF_8))

    /** Where it hangs; null for nowhere. */
    var placement: Placement? = null
        private set

    /** Its item frames by tile, while they hang ([ScreenFrames] hangs them and takes them down). */
    var frames: Array<UUID?> = arrayOfNulls(columns * rows)
        private set

    /** Whether its frames need hanging again (it moved, or one may have gone with its chunk). */
    var rehang = true

    private val shownTo = HashSet<UUID>()
    private val hiddenFrom = HashSet<UUID>()

    /** Who it's drawn for now, and what of it each still has to be sent. */
    val viewers = HashMap<UUID, Viewer>()

    /** Each player's own view of it, while they have one. */
    val personal = HashMap<UUID, Canvas>()

    /** The video playing on each canvas: the one everyone sees under null, a player's own view under them. */
    val playbacks = HashMap<UUID?, Playback>()

    /** The pixel each player is aiming at, packed `x << 16 | y`, while a `hover` has been heard for them. */
    val hovering = HashMap<UUID, Int>()

    /** Its script's scope, for a project screen with a script. */
    var scope: Scope? = null

    var removed = false
        private set

    val tiles: Int get() = columns * rows
    val handle: LuaHandle.Screen get() = LuaHandle.Screen(id, "")

    fun handleFor(player: UUID?): LuaHandle.Screen = LuaHandle.Screen(id, player?.toString() ?: "")

    /** What [player] is shown: their own view, or what everyone sees. */
    fun canvasFor(player: UUID?): Canvas = player?.let { personal[it] } ?: canvas

    /** Whether a script showed it to [player] (`show_to`), which draws it for them however far they are. */
    fun isShownTo(player: UUID): Boolean = player in shownTo

    fun isHiddenFrom(player: UUID): Boolean = player in hiddenFrom

    fun showTo(player: UUID) {
        hiddenFrom.remove(player)
        shownTo += player
    }

    fun hideFrom(player: UUID) {
        shownTo.remove(player)
        hiddenFrom += player
    }

    /** Its project file changed: what it says now, and how it shows. */
    internal fun reconfigure(file: ScreenFile, settings: Settings) {
        this.file = file
        this.settings = settings
    }

    /** It hangs at [placement] now (null: nowhere): its frames are hung again there. */
    internal fun moveTo(placement: Placement?) {
        this.placement = placement
        rehang = true
    }

    internal fun markRemoved() {
        removed = true
    }

    /** Whether ([x], [y], [z]) is within [range] blocks of its middle; false while it hangs nowhere. */
    fun isNear(x: Double, y: Double, z: Double, range: Double): Boolean {
        val (mx, my, mz) = placement?.middle(columns, rows) ?: return false
        val dx = x - mx
        val dy = y - my
        val dz = z - mz
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    /** A new size: everything drawn goes, and every viewer is sent it whole again. */
    internal fun resize(columns: Int, rows: Int, maps: IntArray) {
        this.columns = columns
        this.rows = rows
        this.maps = maps
        canvas = Canvas(columns, rows).also { it.fill(0, 0, it.width, it.height, settings.background) }
        personal.replaceAll { _, _ -> Canvas(columns, rows).also { it.copyFrom(canvas) } }
        frames = arrayOfNulls(columns * rows)
        rehang = true
        for (viewer in viewers.values) viewer.resend(tiles)
    }

    /** The events' path for something [player] did to it: their own view's handlers first, then everyone's. */
    fun <P : LuaEvent> path(event: EventType<P>, player: UUID) =
        listOfNotNull(personal[player]?.let { event to handleFor(player) }, event to handle)
}

/** A player a screen is drawn for, and what of each of its maps they haven't been sent yet. */
class Viewer(val player: UUID, val world: String?, tiles: Int) {
    var pending: Array<Rect?> = Array(tiles) { Rect.WHOLE }
        private set

    fun add(tile: Int, rect: Rect) {
        pending[tile] = rect.union(pending[tile])
    }

    fun resend(tiles: Int) {
        pending = Array(tiles) { Rect.WHOLE }
    }
}
