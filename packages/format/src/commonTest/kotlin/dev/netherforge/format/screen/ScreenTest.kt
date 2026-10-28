package dev.netherforge.format.screen

import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.resourcepack.VideoSound
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens' colours (matching and dithering, the same on the JVM and in JS, and the editor's converter's: its Rust
 * tests pin the same distances), what only a picture's size can say of a bitmap screen font, and videos' sounds
 * in the resource pack.
 */
class ScreenTest {
    /** A made-up palette (no game's): transparent, then black, white, red, grey. */
    private val palette = MapPalette(listOf(0, 0, 0, 0, 0xFF000000.toInt(), -1, 0xFFFF0000.toInt(), 0xFF808080.toInt()))

    private fun load(vararg files: Pair<String, String?>, sizes: Map<String, List<Int>>? = null): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files, sizes = sizes), null)

    @Test
    fun coloursMatchTheNearestDrawableOne() {
        assertEquals(4, palette.nearest(0x101010))
        assertEquals(5, palette.nearest(0xF0F0F0))
        assertEquals(6, palette.nearest(0xE02020))
        assertEquals(7, palette.nearest(0x7A7A7A))
        assertNull(palette.rgb(0), "the game's no colour")
        assertEquals(0xFF0000, palette.rgb(6))
        assertTrue(MapPalette(emptyList()).isEmpty)
        // The Rust converter's tests pin the same numbers.
        assertEquals(584_970, MapPalette.distance(0, 0, 0, 255, 255, 255))
        assertEquals(324_870, MapPalette.distance(255, 0, 0, 0, 0, 255))
        assertEquals(0xFF8800, MapPalette.parse("#ff8800"))
        assertNull(MapPalette.parse("red"))
    }

    @Test
    fun ditheringLeavesWhatsLessThanHalfOpaqueAndSpreadsAGrey() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0x10FF0000)
        for (dither in Dither.entries) assertEquals(listOf(6, Dither.SKIP), dither.apply(pixels, 2, 1, palette).toList(), "$dither")
        val grey = IntArray(64) { 0xFF404040.toInt() }
        val spread = Dither.FLOYD_STEINBERG.apply(grey, 8, 8, palette)
        // Half way from black to the palette's grey: about half of each.
        assertTrue(spread.all { it == 4 || it == 7 }, spread.toList().toString())
        assertTrue(spread.count { it == 7 } in 24..40, spread.toList().toString())
        assertTrue(Dither.NONE.apply(grey, 8, 8, palette).all { it == 7 })
    }

    @Test
    fun aBitmapFontsPictureMustCutIntoItsGrid() {
        val font = "screen_fonts/letters/font.json" to """{ "type": "bitmap", "file": "letters.png", "chars": ["ab", "cd"] }"""
        val picture = "screen_fonts/letters/letters.png" to ""
        val fits = load(font, picture, sizes = mapOf("screen_fonts/letters/letters.png" to listOf(16, 16)))
        assertEquals(emptyList(), fits.problems)
        val off = load(font, picture, sizes = mapOf("screen_fonts/letters/letters.png" to listOf(15, 16)))
        assertEquals(listOf("screen_font.grid"), off.problems.map { it.code })
        // Without the picture's size, nothing is said.
        assertEquals(emptyList(), load(font, picture).problems)
    }

    @Test
    fun placedScreensSayWhereTheyOverlap() {
        val first =
            "screens/a/screen.json" to
                """{ "width": 2, "height": 2, "place": { "world": "world", "x": 0, "y": 64, "z": 0, "facing": "north" } }"""
        val apart =
            "screens/b/screen.json" to
                """{ "width": 1, "height": 1, "place": { "world": "world", "x": 1, "y": 64, "z": 0, "facing": "north" } }"""
        // Facing north, its maps go west: (0, 64, 0) and (-1, 64, 0) along the top row, so (1, 64, 0) is clear.
        assertEquals(emptyList(), load(first, apart).problems)
        val under =
            "screens/b/screen.json" to
                """{ "width": 1, "height": 1, "place": { "world": "world", "x": -1, "y": 63, "z": 0, "facing": "north" } }"""
        assertEquals(listOf("screens/b/screen.json"), load(first, under).problems.filter { it.code == "screen.place" }.map { it.file })
        assertEquals(setOf("a", "b"), load(first, under).compiled(ScreenKind).keys)
    }

    @Test
    fun aVideosSoundGoesInTheResourcePackWholeAndInPieces() {
        val snapshot = load(
            "videos/clip/video.json" to """{ "source": "source.mp4", "width": 1, "height": 1 }""",
            "videos/clip/video.nfv" to "",
            "videos/clip/audio.ogg" to "",
            "videos/clip/audio/0.ogg" to "",
            "videos/clip/audio/1.ogg" to "",
            // A piece after a gap is never reached in order, so it's left out.
            "videos/clip/audio/3.ogg" to "",
            "videos/quiet/video.json" to """{ "source": "source.mp4", "width": 1, "height": 1, "audio": false }""",
            "videos/quiet/video.nfv" to ""
        )
        assertEquals(emptyList(), snapshot.problems)
        val sounds = VideoSound.of(snapshot)
        assertEquals(
            listOf(VideoSound("test", "clip", "videos/clip/audio.ogg", listOf("videos/clip/audio/0.ogg", "videos/clip/audio/1.ogg"))),
            sounds
        )
        val files = PackLayout.build(emptyList(), listOf(75, 0), "Test", videos = sounds) { null }
        assertEquals(PackLayout.Entry.Copy("videos/clip/audio.ogg"), files["assets/test/sounds/video.clip.ogg"])
        assertEquals(PackLayout.Entry.Copy("videos/clip/audio/1.ogg"), files["assets/test/sounds/video.clip/1.ogg"])
        val json = (files["assets/test/sounds.json"] as PackLayout.Entry.Text).text
        assertTrue("\"video.clip\"" in json && "\"video.clip.1\"" in json && "\"stream\": true" in json, json)
    }
}
