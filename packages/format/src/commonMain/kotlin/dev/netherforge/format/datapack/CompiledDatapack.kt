package dev.netherforge.format.datapack

/**
 * A datapack passed through, as the start-up datapack takes it: the formats it's for, its overlays in the order the
 * game applies them, and its files. [filesFor] is what a server of one format gets, so the start-up datapack holds
 * only the files that server reads, with no overlays of its own.
 */
data class CompiledDatapack(val formats: ClosedRange<PackFormat>, val overlays: List<Overlay>, val files: List<DatapackPath>) {
    /** One overlay: its folder and the formats it's for. */
    data class Overlay(val directory: String, val formats: ClosedRange<PackFormat>)

    /**
     * The files a server reading [format] gets, by where they go in a datapack without overlays (`data/…`): none
     * when the pack isn't for [format]; otherwise its own `data/`, then each overlay for [format] in order, a later
     * one's file in place of an earlier one's, as the game applies them.
     */
    fun filesFor(format: PackFormat): Map<String, DatapackPath> {
        if (format !in formats) return emptyMap()
        val out = LinkedHashMap<String, DatapackPath>()
        for (path in files) if (path.overlay == null) out[path.target] = path
        for (overlay in overlays) {
            if (format !in overlay.formats) continue
            for (path in files) if (path.overlay == overlay.directory) out[path.target] = path
        }
        return out
    }

    companion object {
        /** [meta] with [files] (paths in its folder), compiled; null when its formats aren't readable (a datapack with errors). */
        fun of(meta: PackMeta, files: Collection<String>): CompiledDatapack? {
            val formats = PackFormat.range(meta.pack.minFormat, meta.pack.maxFormat) ?: return null
            val overlays = meta.overlays?.entries.orEmpty().map { overlay ->
                Overlay(overlay.directory, PackFormat.range(overlay.minFormat, overlay.maxFormat) ?: return null)
            }
            return CompiledDatapack(formats, overlays, DatapackLayout.entries(meta, files).sortedBy { it.file })
        }
    }
}
