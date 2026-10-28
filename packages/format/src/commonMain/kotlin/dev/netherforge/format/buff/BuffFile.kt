package dev.netherforge.format.buff

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `buffs/<id>.json`: a buff or a debuff scripts put on a player or a centity for a while (`player:add_buff`): what
 * it adds to their stats while it lasts, what it cures and what keeps it off, and how it's shown (a name, an icon
 * glyph, a colour) for a HUD to draw. The server counts it down; scripts read the stats it adds (`player:stat`).
 */
@Serializable
data class BuffFile(
    @SerialName("\$schema") val schema: String? = null,
    /** What players read it as. */
    @MiniMessage val name: String? = null,
    /** `<pack>/<key>`: a glyph of the project's resource packs to draw it by. */
    @Ref(RefKind.GLYPH) val icon: ResourceRef? = null,
    /** Its colour, `#rrggbb`, for a HUD or a bar. */
    val color: String? = null,
    /** Whether it's bad for whoever has it. Default false. */
    val debuff: Boolean? = null,
    /** What it adds to stats while it lasts: `{ "defense": 8, "move": 0.25 }`, negative to take away. */
    val stats: Map<String, Double>? = null,
    /** Buffs it takes away as it comes. */
    @Ref(RefKind.BUFF) val cures: List<ResourceRef>? = null,
    /** Buffs that keep it from taking hold while they last. */
    @Ref(RefKind.BUFF) val blockedBy: List<ResourceRef>? = null,
    /** What another of it does to one that's still going. Default [BuffStacking.LONGEST]. */
    val stacking: BuffStacking? = null,
    /** The most ticks it can have left. Default: no limit. */
    val maxTicks: Int? = null
) {
    val debuffOrDefault: Boolean get() = debuff ?: false
    val stackingOrDefault: BuffStacking get() = stacking ?: BuffStacking.LONGEST

    companion object {
        const val SCHEMA = "../.netherforge/schema/buff.schema.json"

        /** A stat's name: what `player:stat` takes, as Lua names are written. */
        val STAT_NAME = Regex("[a-z][a-z0-9_]{0,63}")

        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        fun validate(file: BuffFile, sink: ProblemSink) {
            file.color?.let { if (!COLOR.matches(it)) sink.report(ProblemCodes.BUFF_COLOR, "color must be #rrggbb", "$.color") }
            for ((stat, value) in file.stats.orEmpty()) {
                if (!STAT_NAME.matches(stat)) {
                    sink.report(ProblemCodes.BUFF_STAT, "\"$stat\" isn't a stat name: lowercase letters, digits and _", "$.stats")
                } else if (!value.isFinite()) {
                    sink.report(ProblemCodes.BUFF_STAT, "$stat must be a number", "$.stats.$stat")
                }
            }
            file.maxTicks?.let { if (it < 1) sink.report(ProblemCodes.BUFF_TICKS, "maxTicks must be at least 1", "$.maxTicks") }
        }
    }
}

/** What adding a buff someone already has does: keep the longer time, add the times, or start again. */
@Serializable
enum class BuffStacking {
    /** The longer of what it has left and what's added. */
    @SerialName("longest")
    LONGEST,

    /** What's added goes on top of what's left (up to `maxTicks`). */
    @SerialName("add")
    ADD,

    /** Starts again with what's added, shorter or not. */
    @SerialName("replace")
    REPLACE
}
