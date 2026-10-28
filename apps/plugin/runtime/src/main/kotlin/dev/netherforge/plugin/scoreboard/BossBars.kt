package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * The boss bars scripts make (`nf.bossbars.create`): what each shows and to
 * whom, by UUID, so a viewer who leaves sees it again when they come back
 * (the server forgets a bar's viewers on quit). Each belongs to the scope
 * that made it and goes when that scope is released (unloaded or disabled).
 */
internal class BossBars(
    private val platform: Platform,
    /** Where a centity is and how much of its health it has (0 to 1, null without any), or null once it's gone. */
    private val locate: (UUID) -> Located? = { null }
) : RuntimeService {
    override val name get() = "boss bars"

    /** A centity a bar is attached to, as [locate] finds it. */
    data class Located(val world: String, val x: Double, val y: Double, val z: Double, val health: Double?)

    /** A bar's tie to a centity (`attach`), and who it shows to for being near it. */
    class Attachment(val centity: UUID, val range: Double, val health: Boolean, val removeWith: Boolean) {
        val near = LinkedHashSet<UUID>()
    }

    /** A bar: its [look] as the script set it, and the glyph at its left end ([icon], as the server names it). */
    class Bar(val id: Int, val scope: Scope, var look: BossBarLook, var icon: String? = null) {
        val viewers = LinkedHashSet<UUID>()
        var attachment: Attachment? = null

        /** What the game shows: the look, the icon placed in front of its text. */
        val shown: BossBarLook get() = icon?.let { look.copy(text = iconText(it) + look.text) } ?: look
    }

    private var nextId = 1
    private val bars = LinkedHashMap<Int, Bar>()

    fun create(scope: Scope, look: BossBarLook, icon: String? = null): Bar {
        val bar = Bar(nextId++, scope, look, icon)
        bars[bar.id] = bar
        platform.bossBars.create(bar.id, bar.shown)
        return bar
    }

    fun get(id: Long): Bar? = bars[id.toInt()]?.takeIf { id <= Int.MAX_VALUE }

    fun update(bar: Bar, look: BossBarLook) {
        bar.look = look
        platform.bossBars.update(bar.id, bar.shown)
    }

    fun setIcon(bar: Bar, icon: String?) {
        bar.icon = icon
        platform.bossBars.update(bar.id, bar.shown)
    }

    fun show(bar: Bar, player: UUID) {
        bar.viewers += player
        platform.bossBars.show(bar.id, player)
    }

    fun hide(bar: Bar, player: UUID): Boolean {
        if (!bar.viewers.remove(player)) return false
        platform.bossBars.hide(bar.id, player)
        return true
    }

    fun remove(bar: Bar) {
        if (bars.remove(bar.id) == null) return
        platform.bossBars.remove(bar.id)
    }

    fun attach(bar: Bar, attachment: Attachment) {
        detach(bar)
        bar.attachment = attachment
        follow(bar, attachment)
    }

    fun detach(bar: Bar): Boolean {
        val attachment = bar.attachment ?: return false
        bar.attachment = null
        for (player in attachment.near) if (player !in bar.viewers) platform.bossBars.hide(bar.id, player)
        return true
    }

    /** Every tick: attached bars show to who's near their centity, and follow its health; one whose centity went goes. */
    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.EFFECTS) return
        for (bar in bars.values.toList()) follow(bar, bar.attachment ?: continue)
    }

    private fun follow(bar: Bar, attachment: Attachment) {
        val at = locate(attachment.centity)
        if (at == null) {
            if (attachment.removeWith) remove(bar) else detach(bar)
            return
        }
        if (attachment.health &&
            at.health != null &&
            at.health != bar.look.progress
        ) {
            update(bar, bar.look.copy(progress = at.health.coerceIn(0.0, 1.0)))
        }
        val range = attachment.range * attachment.range
        val now = platform.players.online().mapNotNullTo(HashSet()) { player ->
            val p = platform.players.location(player.uuid) ?: return@mapNotNullTo null
            val d = (p.x - at.x) * (p.x - at.x) + (p.y - at.y) * (p.y - at.y) + (p.z - at.z) * (p.z - at.z)
            player.uuid.takeIf { p.world == at.world && d <= range }
        }
        for (player in now) if (attachment.near.add(player) && player !in bar.viewers) platform.bossBars.show(bar.id, player)
        for (player in attachment.near.toList()) {
            if (player in now) continue
            attachment.near -= player
            if (player !in bar.viewers) platform.bossBars.hide(bar.id, player)
        }
    }

    /** The bars [player] is among the viewers of. */
    fun of(player: UUID): List<Bar> = bars.values.filter { player in it.viewers }

    /** Someone joined: every bar they're a viewer of shows again. */
    override fun playerJoined(player: PlayerRef) {
        for (bar in of(player.uuid)) platform.bossBars.show(bar.id, player.uuid)
    }

    /** A scope is going: its bars go with it. */
    override fun scopeReleased(scope: Scope) {
        for (bar in bars.values.filter { it.scope == scope }) remove(bar)
    }

    /** The session is ending: every bar goes. */
    override fun stop() {
        for (bar in bars.values.toList()) remove(bar)
    }
}

/** Half a boss bar's width: its left end is this far left of the screen's middle, where the game centres its text. */
private const val HALF_BAR = 91

/**
 * The text that draws glyph [icon] just left of a boss bar's left end:
 * counted from the text's centre, which the game puts in the middle of the
 * screen, its right edge (the glyph's one-pixel gap included) a pixel short
 * of the bar.
 */
private fun iconText(icon: String): String = "<at:${-(HALF_BAR + 1)}:from=center:align=right><glyph:$icon></at>"
