package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.MapScreen

/**
 * A screen's pixels: a map colour a pixel, [columns] by [rows] maps of [MapScreen.TILE] pixels a side, and, for
 * each map, the rectangle that changed since it was last [taken][take]. Every write goes through [set] (or
 * [write], for a run of pixels), which is what keeps that rectangle: a pixel set to the colour it already has
 * changes nothing and is sent to nobody.
 */
class Canvas(val columns: Int, val rows: Int) {
    val width: Int = columns * TILE
    val height: Int = rows * TILE

    /** Row by row across the whole screen. */
    val pixels = ByteArray(width * height)

    /** Per map: the changed rectangle's left, top, right and bottom (inclusive), or [CLEAN] for none. */
    private val dirty = IntArray(columns * rows * 4).also { reset(it) }

    /** Whether anything changed since the last [take] of every map: a quick answer for a screen nobody draws on. */
    var changed = false
        private set

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** Sets a pixel; one off the screen is left alone. */
    fun set(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val at = y * width + x
        val b = color.toByte()
        if (pixels[at] == b) return
        pixels[at] = b
        mark(x, y, x, y)
    }

    /** Sets [count] pixels of row [y] from [x] to [colors] from [from] ([SKIP] leaves one alone). */
    fun write(x: Int, y: Int, colors: IntArray, from: Int, count: Int) {
        if (y < 0 || y >= height) return
        var left = Int.MAX_VALUE
        var right = -1
        val row = y * width
        for (i in 0 until count) {
            val px = x + i
            if (px < 0 || px >= width) continue
            val c = colors[from + i]
            if (c == SKIP) continue
            val b = c.toByte()
            if (pixels[row + px] == b) continue
            pixels[row + px] = b
            if (px < left) left = px
            right = px
        }
        if (right >= 0) mark(left, y, right, y)
    }

    /** Fills a rectangle (clipped to the screen) with one colour. */
    fun fill(x: Int, y: Int, width: Int, height: Int, color: Int) {
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        val x1 = minOf(this.width, x + width) - 1
        val y1 = minOf(this.height, y + height) - 1
        if (x0 > x1 || y0 > y1) return
        val b = color.toByte()
        for (py in y0..y1) {
            val row = py * this.width
            var left = -1
            var right = -1
            for (px in x0..x1) {
                if (pixels[row + px] == b) continue
                pixels[row + px] = b
                if (left < 0) left = px
                right = px
            }
            if (left >= 0) mark(left, py, right, py)
        }
    }

    /** Fills the span of row [y] from [x0] to [x1], both included and clipped. */
    fun span(y: Int, x0: Int, x1: Int, color: Int) {
        if (y < 0 || y >= height) return
        fill(x0, y, x1 - x0 + 1, 1, color)
    }

    /** Copies a rectangle to ([toX], [toY]), as if through a copy of it: overlapping is fine. */
    fun copy(x: Int, y: Int, width: Int, height: Int, toX: Int, toY: Int) {
        if (width <= 0 || height <= 0) return
        val block = IntArray(width * height) { i ->
            val px = x + i % width
            val py = y + i / width
            if (inside(px, py)) get(px, py) else SKIP
        }
        for (row in 0 until height) write(toX, toY + row, block, row * width, width)
    }

    /** Every pixel of [other] (as big as this) where it differs: what a player's own view starts from, or goes back to. */
    fun copyFrom(other: Canvas) {
        require(other.width == width && other.height == height) { "canvases differ in size" }
        for (y in 0 until height) {
            val row = y * width
            var left = -1
            var right = -1
            for (x in 0 until width) {
                val b = other.pixels[row + x]
                if (pixels[row + x] == b) continue
                pixels[row + x] = b
                if (left < 0) left = x
                right = x
            }
            if (left >= 0) mark(left, y, right, y)
        }
    }

    private fun mark(x0: Int, y0: Int, x1: Int, y1: Int) {
        changed = true
        for (row in y0 / TILE..y1 / TILE) {
            for (column in x0 / TILE..x1 / TILE) {
                val at = (row * columns + column) * 4
                val left = maxOf(x0, column * TILE) - column * TILE
                val top = maxOf(y0, row * TILE) - row * TILE
                val right = minOf(x1, column * TILE + TILE - 1) - column * TILE
                val bottom = minOf(y1, row * TILE + TILE - 1) - row * TILE
                if (dirty[at] == CLEAN) {
                    dirty[at] = left
                    dirty[at + 1] = top
                    dirty[at + 2] = right
                    dirty[at + 3] = bottom
                } else {
                    if (left < dirty[at]) dirty[at] = left
                    if (top < dirty[at + 1]) dirty[at + 1] = top
                    if (right > dirty[at + 2]) dirty[at + 2] = right
                    if (bottom > dirty[at + 3]) dirty[at + 3] = bottom
                }
            }
        }
    }

    /** Map [tile]'s changed rectangle since the last take (map pixels: left, top, right, bottom), or null; forgets it. */
    fun take(tile: Int): Rect? {
        val at = tile * 4
        if (dirty[at] == CLEAN) return null
        val rect = Rect(dirty[at], dirty[at + 1], dirty[at + 2], dirty[at + 3])
        dirty[at] = CLEAN
        return rect
    }

    /** Forgets every change: they've all been taken (or nobody needs them). */
    fun settle() {
        reset(dirty)
        changed = false
    }

    /** The pixels of map [tile] in [rect] (map pixels), row by row: what a map packet carries. */
    fun region(tile: Int, rect: Rect): ByteArray {
        val left = (tile % columns) * TILE + rect.left
        val top = (tile / columns) * TILE + rect.top
        val w = rect.width
        val out = ByteArray(w * rect.height)
        for (row in 0 until rect.height) pixels.copyInto(out, row * w, (top + row) * width + left, (top + row) * width + left + w)
        return out
    }

    companion object {
        const val TILE = MapScreen.TILE

        /** In a list of colours: leave this pixel as it is. */
        const val SKIP = -1

        private const val CLEAN = Int.MIN_VALUE

        private fun reset(dirty: IntArray) {
            for (i in dirty.indices step 4) dirty[i] = CLEAN
        }
    }
}

/** A rectangle of a map's pixels: [left] to [right], [top] to [bottom], all included. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
    val area: Int get() = width * height

    fun union(other: Rect?): Rect = if (other == null) {
        this
    } else {
        Rect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
    }

    companion object {
        /** A whole map. */
        val WHOLE = Rect(0, 0, Canvas.TILE - 1, Canvas.TILE - 1)
    }
}
