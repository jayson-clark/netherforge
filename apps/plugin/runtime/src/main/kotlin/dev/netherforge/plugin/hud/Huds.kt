package dev.netherforge.plugin.hud

import dev.netherforge.format.resourcepack.CompiledHud
import dev.netherforge.format.resourcepack.HudLine
import dev.netherforge.format.resourcepack.HudState
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * Every online player's HUD (`player:hud()`): what scripts set of the resource packs' HUD elements, drawn in the
 * player's action bar ([HudLine]).
 *
 * The action bar fades a few seconds after it's sent, so a player whose HUD shows anything is sent it again every
 * [RESEND] ticks, and at once (on the next tick, once a tick at most) when something changed. Anything else that
 * puts a line there (the game's own messages, another plugin) shows until then. A script's own line
 * (`player:send_actionbar`) is drawn in the middle of the HUD's line for [MESSAGE] ticks, when the server can
 * measure it; otherwise it's shown on its own for that long, and the HUD comes back after.
 *
 * Nothing here is saved: a player's HUD goes when they leave, and a script sets it again when they join. A resource
 * pack's change renumbers its pictures, so every HUD is drawn again after the packs rebuild.
 */
internal class Huds(
    private val platform: Platform,
    /** The packs' HUDs as built now, by pack (as the project names it: `ui`, `library:ui`). */
    private val packs: () -> Map<String, CompiledHud>,
    /** How wide a line of text is in the game's font, or null when it can't be told. */
    private val measure: (String) -> Int?,
    private val ticks: () -> Long
) : RuntimeService {
    override val name get() = "huds"

    private class Shown(var state: HudState = HudState()) {
        var dirty = true
        var sent = Long.MIN_VALUE
        var message: String? = null
        var messageUntil = 0L
    }

    private val shown = HashMap<UUID, Shown>()

    /** What [player]'s HUD shows. */
    fun state(player: UUID): HudState = shown[player]?.state ?: HudState()

    /** Changes what [player]'s HUD shows; drawn again on the next tick. */
    fun change(player: UUID, change: (HudState) -> HudState) {
        val entry = shown.getOrPut(player) { Shown() }
        val next = change(entry.state)
        if (next == entry.state) return
        entry.state = next
        entry.dirty = true
        if (next.isEmpty && entry.message == null) {
            shown.remove(player)
            // What was drawn stays until it fades; nothing more is sent.
            platform.players.actionbar(player, "")
        }
    }

    /**
     * A script's action bar line for [player]: shown with their HUD when they have one (true), else for the caller
     * to send as it is (false).
     */
    fun message(player: UUID, text: String): Boolean {
        val entry = shown[player]?.takeIf { !it.state.isEmpty } ?: return false
        entry.message = text
        entry.messageUntil = ticks() + MESSAGE
        entry.dirty = true
        return true
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.EFFECTS || shown.isEmpty()) return
        val now = ticks()
        val huds = packs()
        for ((player, entry) in shown.entries.toList()) {
            if (entry.message != null && now >= entry.messageUntil) {
                entry.message = null
                entry.dirty = true
            }
            if (!entry.dirty && now - entry.sent < RESEND) continue
            entry.dirty = false
            entry.sent = now
            platform.players.actionbar(player, line(huds, entry))
        }
    }

    /** The line [entry] draws: every pack's elements, and a script's line in the middle when there is one. */
    private fun line(huds: Map<String, CompiledHud>, entry: Shown): String {
        val message = entry.message
        val wide = message?.let(measure)
        // A line that can't be measured would move every element by half its width: it's shown on its own.
        if (message != null && wide == null) return message
        val out = StringBuilder()
        for ((pack, hud) in huds.entries.sortedBy { it.key }) {
            val own = entry.state.of(pack)
            if (!own.isEmpty) out.append(HudLine.compose(hud, own, measure))
        }
        if (message != null && wide != null) {
            val font = huds.values.firstOrNull()?.font
            if (font == null) return message
            fun move(px: Int) = "<white><!shadow><font:$font>${PackFonts.offset(px)}</font></!shadow></white>"
            out.append(move(-wide / 2)).append(message).append("<reset>").append(move(-(wide - wide / 2)))
        }
        return out.toString()
    }

    /** The part of a state naming [pack]'s elements, by their key in it. */
    private fun HudState.of(pack: String): HudState {
        fun <V> Map<String, V>.mine() = entries.filter { it.key.substringBeforeLast('/') == pack }.associate {
            it.key.substringAfterLast('/') to
                it.value
        }
        return HudState(bars.mine(), icons.mine(), texts.mine())
    }

    override fun playerQuit(player: PlayerRef) {
        shown.remove(player.uuid)
    }

    override val follows: Set<String> get() = setOf(ResourcePackKind.id)

    /** The packs rebuilt: their pictures may be other characters now. */
    override fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) {
        for (entry in shown.values) entry.dirty = true
    }

    override fun stop() {
        shown.clear()
    }

    companion object {
        /** Ticks between sending a HUD again: well before the action bar fades. */
        const val RESEND = 20L

        /** Ticks a script's action bar line shows with the HUD. */
        const val MESSAGE = 60L
    }
}

/** What to say of an element that's the wrong kind: `ui/mana` is a bar. */
internal fun CompiledHud.kindOf(key: String): String? = when (key) {
    in bars -> "a bar"
    in icons -> "a row of icons"
    in texts -> "a line of text"
    else -> null
}
