package dev.netherforge.format.resourcepack

/**
 * Whether a picture can stand in for one of the game's textures (a resource pack's `textures`), by their pixel sizes.
 *
 * Most of the game's textures are drawn by where things are on them as a share of the whole (a block's faces, a mob's
 * skin, a GUI sprite, the font's grid), so a replacement may be any size of the same shape: 32×32 for a 16×16 block
 * is the same art at twice the detail. The screens' sheets (`gui/…` besides `gui/sprites/`) are cut at fixed pixel
 * places, and the colour maps are read pixel by pixel, so those take exactly the game's size.
 *
 * A texture the game animates is its frames stacked (water: 16×512, 32 frames), and the animation is the `.mcmeta`
 * beside it in the **same** pack, so a replacement without one is a still picture: one square frame.
 */
object ReplacedTexture {
    enum class Fit {
        OK,

        /** Not exactly the size of a texture that has to be. */
        SIZE,

        /** Not the game's shape. */
        SHAPE,

        /** Not one square frame of a texture the game animates, with no `.mcmeta` of its own. */
        STILL
    }

    /** The game's textures that are read at fixed pixel places, by the folders they're in (under `textures/`). */
    private val EXACT = listOf("gui/", "colormap/")

    /** Textures under these are scaled like any other, though in a folder [EXACT] names. */
    private val SCALED = listOf("gui/sprites/")

    /** Whether [name]'s replacement must be exactly the game's size. */
    fun exact(name: String): Boolean = EXACT.any { name.startsWith(it) } && SCALED.none { name.startsWith(it) }

    /**
     * How a picture of [image] pixels (`[width, height]`) fits as [name], the game's being [game] pixels; [animated]
     * when the picture has a `.mcmeta` of its own, which then says what its frames are.
     */
    fun fit(name: String, game: List<Int>, image: List<Int>, animated: Boolean): Fit {
        val (gw, gh) = game
        val (w, h) = image
        return when {
            exact(name) -> if (image == game) Fit.OK else Fit.SIZE
            animated -> Fit.OK
            // A strip of square frames, the game's animation: without a .mcmeta of its own, the replacement is one frame.
            gw > 0 && gh > gw && gh % gw == 0 -> if (w == h) Fit.OK else Fit.STILL
            w.toLong() * gh == h.toLong() * gw -> Fit.OK
            else -> Fit.SHAPE
        }
    }
}
