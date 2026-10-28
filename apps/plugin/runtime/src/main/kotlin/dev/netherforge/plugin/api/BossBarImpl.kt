package dev.netherforge.plugin.api

import dev.netherforge.format.ref.RefKind
import dev.netherforge.plugin.binding.BossBarApi
import dev.netherforge.plugin.binding.BossBarAttachOptions
import dev.netherforge.plugin.binding.BossBarOptions
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfBossbarsApi
import dev.netherforge.plugin.binding.SidebarApi
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.scoreboard.BossBars
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

private fun checkProgress(progress: Double, name: String) {
    if (progress.isNaN() || progress !in 0.0..1.0) throw LuaApiException("$name must be from 0 to 1, not $progress")
}

/** `nf.bossbars`. */
internal class NfBossbarsImpl(private val session: ProjectSession) : NfBossbarsApi {
    override fun create(caller: Caller, options: BossBarOptions?): LuaHandle.BossBar {
        options?.progress?.let { checkProgress(it, "options.progress") }
        val look = BossBarLook(options?.text.orEmpty(), options?.progress ?: 1.0, options?.color ?: "pink", options?.style ?: "progress")
        val icon = options?.icon?.let { session.names.entryName(RefKind.GLYPH, it) }
        return LuaHandle.BossBar(session.bossBars.create(caller.scope, look, icon).id.toLong())
    }
}

/** `BossBar`: answers nil or false once it's removed or its script has unloaded. */
internal class BossBarImpl(private val session: ProjectSession) : BossBarApi {
    private val bars get() = session.bossBars

    private fun bar(self: LuaHandle.BossBar): BossBars.Bar? = bars.get(self.id)

    override fun attach(self: LuaHandle.BossBar, centity: LuaHandle.Centity, options: BossBarAttachOptions?): Boolean {
        val range = options?.range ?: DEFAULT_ATTACH_RANGE
        if (!(range > 0) || !range.isFinite()) throw LuaApiException("bad argument 'options.range' (more than 0 expected, got $range)")
        val bar = bar(self) ?: return false
        val id = runCatching { java.util.UUID.fromString(centity.id) }.getOrNull() ?: return false
        if (session.centities.find(id)?.removed != false) return false
        bars.attach(bar, BossBars.Attachment(id, range, options?.health != false, options?.removeWith != false))
        return true
    }

    override fun detach(self: LuaHandle.BossBar) {
        bar(self)?.let(bars::detach)
    }

    override fun attached(self: LuaHandle.BossBar): LuaHandle.Centity? = bar(self)?.attachment?.let {
        LuaHandle.Centity(it.centity.toString())
    }

    private fun change(self: LuaHandle.BossBar, change: (BossBarLook) -> BossBarLook): Boolean {
        val bar = bar(self) ?: return false
        bars.update(bar, change(bar.look))
        return true
    }

    override fun exists(self: LuaHandle.BossBar): Boolean = bar(self) != null

    override fun showTo(self: LuaHandle.BossBar, player: LuaHandle.Player): Boolean {
        val bar = bar(self) ?: return false
        val uuid = player.uuidOrNull() ?: return false
        bars.show(bar, uuid)
        return true
    }

    override fun hideFrom(self: LuaHandle.BossBar, player: LuaHandle.Player): Boolean {
        val bar = bar(self) ?: return false
        return bars.hide(bar, player.uuidOrNull() ?: return false)
    }

    override fun viewers(self: LuaHandle.BossBar): List<LuaHandle.Player> = bar(self)?.let {
        it.viewers + it.attachment?.near.orEmpty()
    }.orEmpty().distinct().mapNotNull { session.platform.players.known(it.toString()) }.map(::playerHandle)

    override fun text(self: LuaHandle.BossBar): String? = bar(self)?.look?.text

    override fun setText(self: LuaHandle.BossBar, text: String): Boolean = change(self) { it.copy(text = text) }

    override fun icon(self: LuaHandle.BossBar): String? = bar(self)?.icon?.let(session.names::spell)

    override fun setIcon(self: LuaHandle.BossBar, icon: String?): Boolean {
        val name = icon?.let { session.names.entryName(RefKind.GLYPH, it) }
        val bar = bar(self) ?: return false
        bars.setIcon(bar, name)
        return true
    }

    override fun progress(self: LuaHandle.BossBar): Double? = bar(self)?.look?.progress

    override fun setProgress(self: LuaHandle.BossBar, progress: Double): Boolean {
        checkProgress(progress, "progress")
        return change(self) { it.copy(progress = progress) }
    }

    override fun color(self: LuaHandle.BossBar): String? = bar(self)?.look?.color

    override fun setColor(self: LuaHandle.BossBar, color: String): Boolean = change(self) { it.copy(color = color) }

    override fun style(self: LuaHandle.BossBar): String? = bar(self)?.look?.style

    override fun setStyle(self: LuaHandle.BossBar, style: String): Boolean = change(self) { it.copy(style = style) }

    override fun remove(self: LuaHandle.BossBar): Boolean {
        val bar = bar(self) ?: return false
        bars.remove(bar)
        return true
    }
}

/** At most this many lines on a sidebar: what Minecraft shows. */
internal const val SIDEBAR_LINES = 15

/** `Sidebar`: one online player's. Answers nil, false or nothing while they're offline. */
internal class SidebarImpl(private val session: ProjectSession) : SidebarApi {
    private val sidebars get() = session.sidebars

    /** Their UUID while they're online. */
    private fun online(self: LuaHandle.Sidebar): UUID? =
        runCatching { UUID.fromString(self.player) }.getOrNull()?.takeIf { session.platform.players.get(it) != null }

    override fun title(self: LuaHandle.Sidebar): String? = online(self)?.let(sidebars::state)?.title

    override fun setTitle(self: LuaHandle.Sidebar, text: String): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) {
            title = text
            visible = true
        }
        return true
    }

    override fun lines(self: LuaHandle.Sidebar): List<String> = online(self)?.let(sidebars::state)?.lines.orEmpty()

    override fun setLines(self: LuaHandle.Sidebar, lines: List<String>): Boolean {
        if (lines.size > SIDEBAR_LINES) throw LuaApiException("a sidebar has at most $SIDEBAR_LINES lines, not ${lines.size}")
        val player = online(self) ?: return false
        sidebars.change(player) {
            this.lines = lines
            visible = true
        }
        return true
    }

    override fun isVisible(self: LuaHandle.Sidebar): Boolean = online(self)?.let(sidebars::state)?.visible == true

    override fun setVisible(self: LuaHandle.Sidebar, visible: Boolean): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) { this.visible = visible }
        return true
    }

    override fun clear(self: LuaHandle.Sidebar): Boolean {
        val player = online(self) ?: return false
        sidebars.change(player) {
            title = null
            lines = emptyList()
            visible = false
        }
        return true
    }
}

/** How near a player must be to an attached bar's centity to see it, unless told. */
private const val DEFAULT_ATTACH_RANGE = 48.0
