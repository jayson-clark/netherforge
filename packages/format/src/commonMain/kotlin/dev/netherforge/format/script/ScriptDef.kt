package dev.netherforge.format.script

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import kotlinx.serialization.Serializable

/**
 * A resource's one script (a centity's, a menu's or a dialog's), in a sibling
 * `.lua` file. Validated by `Rules.script` wherever it appears.
 */
@Serializable
data class ScriptDef(
    /** Path relative to the resource's folder. */
    @Ref(RefKind.SCRIPT) val file: String,
    /** Lua instructions one call into the script (its body, a handler, a timer) may use before it's aborted. */
    val budget: Int? = null
) {
    /** The budget a call actually gets: [budget] or the default, raised to at least [MIN_BUDGET]. */
    val effectiveBudget: Int get() = (budget ?: DEFAULT_BUDGET).coerceAtLeast(MIN_BUDGET)

    companion object {
        const val DEFAULT_BUDGET = 200_000
        const val MIN_BUDGET = 500
    }
}
