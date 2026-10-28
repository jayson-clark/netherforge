package dev.netherforge.plugin.paper.version

import net.minecraft.util.BitStorage
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.PalettedContainer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/**
 * A paletted container's storage and palette, which the server keeps private (`PalettedContainer.data`, a record
 * of its configuration, storage and palette): read through handles found once. Null where this server's container
 * isn't laid out that way, so a caller falls back to the API rather than failing.
 */
internal class PaletteData(val storage: BitStorage, val palette: Palette<*>) {
    companion object {
        private val handles: Triple<MethodHandle, MethodHandle, MethodHandle>? = runCatching {
            val lookup = MethodHandles.lookup()
            val field = PalettedContainer::class.java.getDeclaredField("data").apply { isAccessible = true }
            val data = field.type
            val storage = data.getDeclaredMethod("storage").apply { isAccessible = true }
            val palette = data.getDeclaredMethod("palette").apply { isAccessible = true }
            Triple(lookup.unreflectGetter(field), lookup.unreflect(storage), lookup.unreflect(palette))
        }.getOrNull()

        fun of(container: PalettedContainer<*>): PaletteData? {
            val (data, storage, palette) = handles ?: return null
            return runCatching {
                val held = data.invoke(container)
                PaletteData(storage.invoke(held) as BitStorage, palette.invoke(held) as Palette<*>)
            }.getOrNull()
        }
    }
}
