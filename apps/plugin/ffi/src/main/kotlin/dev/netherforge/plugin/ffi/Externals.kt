package dev.netherforge.plugin.ffi

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.ChunkGrid
import dev.netherforge.plugin.platform.GameEvents
import java.nio.file.Path

// The types the wire writes by hand on both sides (the rest are generated, `Codecs.kt`): format's
// numbers, a path, a chunk's grid.

internal fun putVec3(w: Writer, v: Vec3) {
    w.double(v.x)
    w.double(v.y)
    w.double(v.z)
}

internal fun takeVec3(r: Reader) = Vec3(r.double(), r.double(), r.double())

internal fun putBox(w: Writer, v: Box) {
    putVec3(w, v.min)
    putVec3(w, v.max)
}

internal fun takeBox(r: Reader) = Box(takeVec3(r), takeVec3(r))

/** Its sixteen numbers, column by column. */
internal fun putMatrix4(w: Writer, v: Matrix4) = v.values.forEach(w::double)

internal fun takeMatrix4(r: Reader) = Matrix4().also { m -> for (k in 0 until 16) m.values[k] = r.double() }

internal fun putPath(w: Writer, v: Path) = w.string(v.toString())

internal fun takePath(r: Reader): Path = Path.of(r.string())

/** Its positions that hold something: their count, then each one's x, y, z and value (an unsigned short). */
internal fun putChunkGrid(w: Writer, v: ChunkGrid) {
    w.count(v.size)
    v.forEach { x, y, z, value ->
        w.int(x)
        w.int(y)
        w.int(z)
        w.short(value.toShort())
    }
}

internal fun takeChunkGrid(r: Reader): ChunkGrid {
    val grid = ChunkGrid()
    repeat(r.count()) {
        val x = r.int()
        val y = r.int()
        val z = r.int()
        grid[x, y, z] = r.short().toInt() and 0xFFFF
    }
    return grid
}

/** The runtime's `GameEvents`, which the server raises its events through. */
internal fun takeGameEvents(r: Reader): GameEvents = RustGameEvents(r.rust())
