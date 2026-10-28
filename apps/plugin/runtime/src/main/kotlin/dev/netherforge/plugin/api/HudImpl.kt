package dev.netherforge.plugin.api
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.resourcepack.BarFill
import dev.netherforge.format.resourcepack.CompiledHud
import dev.netherforge.plugin.binding.HudApi
import dev.netherforge.plugin.binding.HudBar
import dev.netherforge.plugin.binding.HudFillOptions
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.hud.kindOf
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID
import kotlin.math.ceil

/** `Hud`: one player's HUD, kept by [Huds]. Names are `<pack>/<key>` in the calling script's package. */
internal class HudImpl(private val session: ProjectSession) : HudApi {
    private val huds get() = session.huds

    /** The player, when they're online. */
    private fun online(self: LuaHandle.Hud): UUID? =
        runCatching { UUID.fromString(self.player) }.getOrNull()?.takeIf { session.platform.players.get(it) != null }

    /** [name] as the server knows it (`ui/mana`, `library:ui/mana`), its pack's HUD, and its key there. */
    private fun element(name: String, wanted: String, has: (CompiledHud, String) -> Boolean): String {
        val known = session.names.entryName(RefKind.HUD, name)
        val pack = known.substringBeforeLast('/')
        val key = known.substringAfterLast('/')
        val hud = session.snapshot.compiledResourcePacks[pack]?.hud
            ?: throw LuaApiException("resource pack \"$pack\" has errors, so its HUD isn't built")
        if (!has(hud, key)) throw LuaApiException("\"$name\" is ${hud.kindOf(key) ?: "no HUD element"}, not $wanted")
        return known
    }

    override fun player(self: LuaHandle.Hud): LuaHandle.Player = LuaHandle.Player(self.player)

    /** Bar [name] as the server knows it, and how many segments it has. */
    private fun bar(name: String): Pair<String, Int> {
        var segments = 0
        val known = element(name, "a bar") { hud, key -> hud.bars[key]?.also { segments = it.def.segments } != null }
        return known to segments
    }

    private fun checkFill(value: Double, max: Double) {
        if (!(max > 0) || !max.isFinite()) throw LuaApiException("bad argument 'max' (a bar's max is more than 0, got $max)")
        if (!value.isFinite()) throw LuaApiException("bad argument 'value' (a number, got $value)")
    }

    override fun fillBar(self: LuaHandle.Hud, name: String, value: Double, max: Double, options: HudFillOptions?): Boolean {
        val (known, own) = bar(name)
        checkFill(value, max)
        val shown = options?.segments?.let {
            if (it !in 1..own) throw LuaApiException("bad argument 'options.segments' (1 to $own for \"$name\", got $it)")
            it.toInt()
        }
        val player = online(self) ?: return false
        huds.change(player) { it.copy(bars = it.bars + (known to BarFill(value, max, shown))) }
        return true
    }

    override fun fillBars(self: LuaHandle.Hud, names: List<String>, value: Double, max: Double, perSegment: Double): Long {
        val bars = names.map(::bar)
        checkFill(value, max)
        if (!(perSegment > 0) || !perSegment.isFinite()) {
            throw LuaApiException("bad argument 'per_segment' (more than 0, got $perSegment)")
        }
        val player = online(self) ?: return 0
        // As many segments as the max needs (a hair under a whole one doesn't make another), as far as the bars go.
        var left = minOf(ceil(max / perSegment - 1e-9).toInt(), bars.sumOf { it.second })
        val total = left
        var from = 0.0
        huds.change(player) { state ->
            var fills = state.bars
            for ((known, own) in bars) {
                val count = minOf(own, left)
                left -= count
                fills = if (count == 0) {
                    fills - known
                } else {
                    val span = count * perSegment
                    (fills + (known to BarFill((value - from).coerceIn(0.0, span), span, count))).also { from += span }
                }
            }
            state.copy(bars = fills)
        }
        return total.toLong()
    }

    override fun bar(self: LuaHandle.Hud, name: String): HudBar? {
        val (known, own) = bar(name)
        val player = online(self) ?: return null
        return huds.state(player).bars[known]?.let { HudBar(it.value, it.max, (it.segments ?: own).toLong()) }
    }

    override fun setIcons(self: LuaHandle.Hud, name: String, icons: List<String>): Boolean {
        var row: CompiledHud.Icons? = null
        val known = element(name, "a row of icons") { hud, key -> hud.icons[key]?.also { row = it } != null }
        icons.withIndex().firstOrNull { it.value !in row!!.icons }?.let { (index, icon) ->
            throw LuaApiException(
                "bad argument 'icons[${index + 1}]' (\"$name\" has no icon \"$icon\": ${row!!.icons.keys.joinToString()})"
            )
        }
        val player = online(self) ?: return false
        huds.change(player) { it.copy(icons = it.icons + (known to icons.toList())) }
        return true
    }

    override fun icons(self: LuaHandle.Hud, name: String): List<String>? {
        val known = element(name, "a row of icons") { hud, key -> key in hud.icons }
        val player = online(self) ?: return null
        return huds.state(player).icons[known]
    }

    override fun setText(self: LuaHandle.Hud, name: String, text: String): Boolean {
        val known = element(name, "a line of text") { hud, key -> key in hud.texts }
        val player = online(self) ?: return false
        huds.change(player) { it.copy(texts = it.texts + (known to text)) }
        return true
    }

    override fun text(self: LuaHandle.Hud, name: String): String? {
        val known = element(name, "a line of text") { hud, key -> key in hud.texts }
        val player = online(self) ?: return null
        return huds.state(player).texts[known]
    }

    override fun remove(self: LuaHandle.Hud, name: String): Boolean {
        val known = element(name, "a HUD element") { hud, key -> hud.kindOf(key) != null }
        val player = online(self) ?: return false
        val before = huds.state(player)
        if (known !in before.bars && known !in before.icons && known !in before.texts) return false
        huds.change(player) { it.copy(bars = it.bars - known, icons = it.icons - known, texts = it.texts - known) }
        return true
    }

    override fun clear(self: LuaHandle.Hud) {
        val player = online(self) ?: return
        huds.change(player) { dev.netherforge.format.resourcepack.HudState() }
    }
}
