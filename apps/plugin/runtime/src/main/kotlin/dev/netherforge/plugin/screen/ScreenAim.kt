package dev.netherforge.plugin.screen

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.ScreenClickEvent
import dev.netherforge.plugin.binding.ScreenHoverEvent
import dev.netherforge.plugin.binding.ScreenLeaveEvent
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.script.Scripts
import java.util.UUID
import kotlin.math.sqrt

/**
 * Where players aim at screens, and what they click: a click on a screen's frame (the pixel from the player's sight
 * through [Placement.hit]), or a swing of the arm aimed at one from further away, a block in front in the way; one
 * click a player a tick, however the server heard of it. `hover` and `leave` run only while something listens.
 */
internal class ScreenAim(
    private val platform: Platform,
    private val scripts: Scripts,
    private val frames: ScreenFrames,
    /** Every screen there is now. */
    private val screens: () -> Collection<ScreenState>,
    /** The screens' tick count. */
    private val tick: () -> Long
) {
    /** The tick each player last clicked a screen in. */
    private val clicked = HashMap<UUID, Long>()

    /** A click on one of our frames: the screen's, when it's one of theirs. True when the frame is a screen's. */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean {
        val (screen, tile) = frames.owner(entity) ?: return false
        if (player.uuid !in screen.viewers) return true
        val placement = screen.placement ?: return true
        // Where along their sight they aimed, or the middle of the map when the server didn't say.
        val hit = sight?.let { placement.hit(it, screen.columns, screen.rows, CLICK_REACH) }
        val x = hit?.x ?: ((tile % screen.columns) * Canvas.TILE + Canvas.TILE / 2)
        val y = hit?.y ?: ((tile / screen.columns) * Canvas.TILE + Canvas.TILE / 2)
        clickAt(screen, player.uuid, x, y, button)
        return true
    }

    /**
     * A swing of the arm (or a right click in the air) by [player], aimed at a screen within its click range: its
     * click, and true. False when they aimed at none, so the swing is the world's.
     */
    fun swing(player: PlayerRef, button: ClickButton): Boolean {
        val eye = platform.players.eye(player.uuid) ?: return false
        val (screen, hit) = aim(player.uuid, eye, null) ?: return false
        clickAt(screen, player.uuid, hit.x, hit.y, button)
        return true
    }

    private fun clickAt(screen: ScreenState, player: UUID, x: Int, y: Int, button: ClickButton) {
        val now = tick()
        if (clicked[player] == now) return
        clicked[player] = now
        val handle = screen.handleFor(player.takeIf { it in screen.personal })
        val event = ScreenClickEvent(handle, LuaHandle.Player(player.toString()), x.toLong(), y.toLong(), button.luaName)
        scripts.emit(screen.path(Events.SCREEN_CLICK, player), event)
    }

    /**
     * The nearest screen [player]'s [eye] meets that's drawn for them, within [reach] blocks (each screen's click
     * range when null), with nothing solid in front of it.
     */
    fun aim(player: UUID, eye: Ray, reach: Double?): Pair<ScreenState, Placement.Hit>? {
        var best: Pair<ScreenState, Placement.Hit>? = null
        for (screen in screens()) {
            if (screen.removed || player !in screen.viewers) continue
            val placement = screen.placement ?: continue
            val hit = placement.hit(eye, screen.columns, screen.rows, reach ?: screen.settings.clickRange) ?: continue
            if (best == null || hit.distance < best.second.distance) best = screen to hit
        }
        val found = best ?: return null
        val block = platform.worlds.raycastBlocks(
            eye.world,
            Vec3(eye.x, eye.y, eye.z),
            Vec3(eye.dx, eye.dy, eye.dz),
            found.second.distance,
            false
        )
        if (block != null) {
            val p = block.position
            val d = sqrt((p.x - eye.x) * (p.x - eye.x) + (p.y - eye.y) * (p.y - eye.y) + (p.z - eye.z) * (p.z - eye.z))
            if (d < found.second.distance - OCCLUSION_SLACK) return null
        }
        return found
    }

    /**
     * Each tick: who aims at which pixel of a screen whose `hover` or `leave` someone hears. A player's aim is
     * worked out once a tick (every screen they see, then a ray against the blocks), however many screens listen.
     */
    fun hover() {
        val aims = HashMap<UUID, Pair<ScreenState, Placement.Hit>?>()
        fun aimOf(player: UUID) = if (player in aims) {
            aims[player]
        } else {
            platform.players.eye(player)?.let { aim(player, it, null) }.also { aims[player] = it }
        }
        for (screen in screens().toList()) {
            if (screen.removed || screen.placement == null || !hearsAim(screen)) continue
            for (player in screen.viewers.keys.toList()) {
                val hit = aimOf(player)?.takeIf { it.first === screen }?.second
                val before = screen.hovering[player]
                if (hit == null) {
                    if (before != null) leave(screen, player)
                    continue
                }
                val packed = (hit.x shl 16) or hit.y
                if (before == packed) continue
                screen.hovering[player] = packed
                val handle = screen.handleFor(player.takeIf { it in screen.personal })
                scripts.emit(
                    screen.path(Events.SCREEN_HOVER, player),
                    ScreenHoverEvent(handle, LuaHandle.Player(player.toString()), hit.x.toLong(), hit.y.toLong())
                )
                if (screen.removed) break
            }
        }
    }

    private fun hearsAim(screen: ScreenState): Boolean {
        val handles = listOf(screen.handle) + screen.personal.keys.map(screen::handleFor)
        return handles.any { scripts.listening(it, Events.SCREEN_HOVER) || scripts.listening(it, Events.SCREEN_LEAVE) }
    }

    /** [player] no longer aims at [screen]: its `leave`, when a `hover` was heard for them. */
    fun leave(screen: ScreenState, player: UUID) {
        screen.hovering.remove(player) ?: return
        val handle = screen.handleFor(player.takeIf { it in screen.personal })
        scripts.emit(screen.path(Events.SCREEN_LEAVE, player), ScreenLeaveEvent(handle, LuaHandle.Player(player.toString())))
    }

    /** [player] left: when they last clicked doesn't matter any more. */
    fun forget(player: UUID) {
        clicked.remove(player)
    }

    companion object {
        /** How far a click on a frame is looked for along the player's sight: past any reach the game allows. */
        const val CLICK_REACH = 16.0

        /** How much nearer than a screen a block must be to be in front of it (the wall it hangs on is just behind). */
        const val OCCLUSION_SLACK = 0.05
    }
}
