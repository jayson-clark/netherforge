package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * Showing a player the world they're in under a sky (format's `SkyTwins`): the biomes of every chunk they have, and
 * of every one they're sent later, told them as the sky's twins at a step of its fade, until shown otherwise; and a
 * sky held through the night, its clock at that step ([SkyView.clock]). The world itself never changes: only what
 * the player is told it's made of, and what time its clock is.
 */
interface SkyOps {
    /** Shows [player] [sky]'s twins (null: the world's own biomes again). Nothing for one who's offline. */
    fun show(player: UUID, sky: SkyView?)

    companion object {
        val NONE = object : SkyOps {
            override fun show(player: UUID, sky: SkyView?) {}
        }
    }
}

/**
 * Which twins a player is shown: those of the sky [sky] (`showcase:blood_moon`) at [step] of its fade, in the
 * project's namespace [home] (where every sky's twins are). [clock] is the world clock of the sky's timeline when
 * it holds its colours through the night (format's `SkyNight`) and the server started with one: the player is told
 * it's at [step], and at 0 again once they're shown anything else.
 */
data class SkyView(val home: String, val sky: String, val step: Int, val clock: String? = null)
