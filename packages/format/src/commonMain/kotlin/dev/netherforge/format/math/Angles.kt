package dev.netherforge.format.math

/** Angle maths shared by everything that turns smoothly: a centity's rotation channel, a cutscene's camera. */
object Angles {
    /** Degrees the short way round: 350° to 10° travels 20°; exactly opposite angles travel +180. */
    fun shortestDelta(from: Double, to: Double): Double {
        val raw = (to - from) % 360.0
        return when {
            raw > 180.0 -> raw - 360.0
            raw <= -180.0 -> raw + 360.0
            else -> raw
        }
    }

    /** [degrees] as an angle in `[-180, 180)`, which is how the game keeps a facing. */
    fun normalize(degrees: Double): Double {
        val wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0
        return wrapped - 180.0
    }
}
