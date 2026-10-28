package dev.netherforge.plugin.screen

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The shapes scripts draw, onto a [Canvas], in map colours (an index; [Canvas.SKIP] draws nothing). Every shape is
 * whole-pixel and clipped to the canvas, so a shape partly or wholly off it is fine.
 */
object Raster {
    /** A line from ([x1], [y1]) to ([x2], [y2]), both ends drawn, [thickness] pixels wide (a round brush past 2). */
    fun line(canvas: Canvas, x1: Int, y1: Int, x2: Int, y2: Int, color: Int, thickness: Int = 1) {
        if (color == Canvas.SKIP) return
        val brush = brush(thickness)
        var x = x1
        var y = y1
        val dx = abs(x2 - x1)
        val dy = -abs(y2 - y1)
        val sx = if (x1 < x2) 1 else -1
        val sy = if (y1 < y2) 1 else -1
        var err = dx + dy
        while (true) {
            stamp(canvas, x, y, brush, color)
            if (x == x2 && y == y2) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    /** The rows of a round brush [thickness] pixels across, as (dy, from dx, to dx). */
    private fun brush(thickness: Int): List<Triple<Int, Int, Int>> {
        if (thickness <= 1) return listOf(Triple(0, 0, 0))
        val r = thickness / 2.0
        val low = -(thickness - 1) / 2
        val high = low + thickness - 1
        if (thickness == 2) return (low..high).map { Triple(it, low, high) }
        return (low..high).mapNotNull { dy ->
            val cy = dy - (low + high) / 2.0
            val half = sqrt(maxOf(0.0, r * r - cy * cy))
            val from = ceil((low + high) / 2.0 - half - 0.0001).toInt()
            val to = floor((low + high) / 2.0 + half + 0.0001).toInt()
            if (from > to) null else Triple(dy, from, to)
        }
    }

    private fun stamp(canvas: Canvas, x: Int, y: Int, brush: List<Triple<Int, Int, Int>>, color: Int) {
        for ((dy, from, to) in brush) canvas.span(y + dy, x + from, x + to, color)
    }

    /**
     * A rectangle with corners rounded by [radius] (held to half its shorter side): [fill] inside, [outline]
     * [thickness] pixels wide along its edge inside it. Either may be [Canvas.SKIP].
     */
    fun rectangle(canvas: Canvas, x: Int, y: Int, width: Int, height: Int, radius: Int, fill: Int, outline: Int, thickness: Int) {
        if (width <= 0 || height <= 0) return
        val r = radius.coerceIn(0, minOf(width, height) / 2)
        val t = thickness.coerceIn(1, maxOf(1, minOf(width, height) / 2 + 1))
        for (row in 0 until height) {
            val (outerFrom, outerTo) = roundedSpan(row, width, height, r)
            if (outerFrom > outerTo) continue
            if (outline == Canvas.SKIP) {
                if (fill != Canvas.SKIP) canvas.span(y + row, x + outerFrom, x + outerTo, fill)
                continue
            }
            // The inside: the rectangle inset by the outline's thickness, its corners rounded as much less.
            val inside = row - t in 0 until height - 2 * t && width - 2 * t > 0
            if (!inside) {
                canvas.span(y + row, x + outerFrom, x + outerTo, outline)
                continue
            }
            val (innerFrom, innerTo) = roundedSpan(row - t, width - 2 * t, height - 2 * t, maxOf(0, r - t))
            if (innerFrom > innerTo) {
                canvas.span(y + row, x + outerFrom, x + outerTo, outline)
                continue
            }
            canvas.span(y + row, x + outerFrom, x + t + innerFrom - 1, outline)
            canvas.span(y + row, x + t + innerTo + 1, x + outerTo, outline)
            if (fill != Canvas.SKIP) canvas.span(y + row, x + t + innerFrom, x + t + innerTo, fill)
        }
    }

    /** The span of row [row] of a [width] by [height] rectangle with corners of [radius]: (first, last) column. */
    private fun roundedSpan(row: Int, width: Int, height: Int, radius: Int): Pair<Int, Int> {
        if (radius == 0) return 0 to width - 1
        val fromEdge = when {
            row < radius -> radius - row - 0.5
            row >= height - radius -> row - (height - radius) + 0.5
            else -> return 0 to width - 1
        }
        val inset = radius - sqrt(maxOf(0.0, radius.toDouble() * radius - fromEdge * fromEdge))
        val cut = floor(inset + 0.5).toInt()
        return cut to width - 1 - cut
    }

    /** An ellipse round ([cx], [cy]) with radii [rx] and [ry]: [fill] inside, [outline] [thickness] pixels inside its edge. */
    fun ellipse(canvas: Canvas, cx: Int, cy: Int, rx: Int, ry: Int, fill: Int, outline: Int, thickness: Int) {
        if (rx < 0 || ry < 0) return
        if (rx == 0 || ry == 0) {
            val color = if (outline != Canvas.SKIP) outline else fill
            if (color != Canvas.SKIP) canvas.fill(cx - rx, cy - ry, 2 * rx + 1, 2 * ry + 1, color)
            return
        }
        val t = thickness.coerceAtLeast(1)
        val irx = rx - t
        val iry = ry - t
        for (dy in -ry..ry) {
            val outer = halfWidth(rx + 0.5, ry + 0.5, dy)
            if (outer < 0) continue
            if (outline == Canvas.SKIP) {
                if (fill != Canvas.SKIP) canvas.span(cy + dy, cx - outer, cx + outer, fill)
                continue
            }
            val inner = if (irx >= 0 && iry >= 0 && abs(dy) <= iry) halfWidth(irx + 0.5, iry + 0.5, dy) else -1
            if (inner < 0) {
                canvas.span(cy + dy, cx - outer, cx + outer, outline)
                continue
            }
            canvas.span(cy + dy, cx - outer, cx - inner - 1, outline)
            canvas.span(cy + dy, cx + inner + 1, cx + outer, outline)
            if (fill != Canvas.SKIP) canvas.span(cy + dy, cx - inner, cx + inner, fill)
        }
    }

    /** How far right of the middle row [dy] of an ellipse of radii [a] and [b] reaches; -1 when it doesn't reach that row. */
    private fun halfWidth(a: Double, b: Double, dy: Int): Int {
        val f = 1.0 - (dy * dy) / (b * b)
        if (f < 0) return -1
        return floor(a * sqrt(f) - 0.5 + 0.0001).toInt().coerceAtLeast(0)
    }

    /**
     * A polygon through [points] (x, y, x, y, …): [fill] inside (even-odd, sampled at pixel middles), then
     * [outline] [thickness] pixels wide along each edge.
     */
    fun polygon(canvas: Canvas, points: IntArray, fill: Int, outline: Int, thickness: Int) {
        val n = points.size / 2
        if (n < 3) return
        if (fill != Canvas.SKIP) {
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            for (i in 0 until n) {
                top = minOf(top, points[i * 2 + 1])
                bottom = maxOf(bottom, points[i * 2 + 1])
            }
            val crossings = DoubleArray(n)
            for (y in maxOf(top, 0)..minOf(bottom, canvas.height - 1)) {
                val sy = y + 0.5
                var count = 0
                for (i in 0 until n) {
                    val ax = points[i * 2].toDouble()
                    val ay = points[i * 2 + 1].toDouble()
                    val bx = points[((i + 1) % n) * 2].toDouble()
                    val by = points[((i + 1) % n) * 2 + 1].toDouble()
                    if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                        crossings[count++] = ax + (sy - ay) / (by - ay) * (bx - ax)
                    }
                }
                crossings.sort(0, count)
                var i = 0
                while (i + 1 < count) {
                    val from = ceil(crossings[i] - 0.5).toInt()
                    val to = floor(crossings[i + 1] - 0.5).toInt()
                    if (from <= to) canvas.span(y, from, to, fill)
                    i += 2
                }
            }
        }
        if (outline != Canvas.SKIP) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                line(canvas, points[i * 2], points[i * 2 + 1], points[j * 2], points[j * 2 + 1], outline, thickness)
            }
        }
    }

    /**
     * Pixels [colors] ([width] a row, [Canvas.SKIP] for none) with their top left at ([x], [y]), each drawn
     * [scale] by [scale] pixels.
     */
    fun blit(canvas: Canvas, x: Int, y: Int, colors: IntArray, width: Int, scale: Int = 1) {
        if (width <= 0) return
        val height = colors.size / width
        if (scale == 1) {
            for (row in 0 until height) canvas.write(x, y + row, colors, row * width, width)
            return
        }
        val wide = IntArray(width * scale)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val c = colors[row * width + column]
                for (k in 0 until scale) wide[column * scale + k] = c
            }
            for (k in 0 until scale) canvas.write(x, y + row * scale + k, wide, 0, wide.size)
        }
    }
}
