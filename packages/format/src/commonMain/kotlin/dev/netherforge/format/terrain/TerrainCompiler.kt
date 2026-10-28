package dev.netherforge.format.terrain

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds

/** Compiles a file that validated into its [CompiledTerrain]. */
object TerrainCompiler {
    /** [scriptPath] is where the file's script is, `terrain/<id>.lua` ([dev.netherforge.format.terrain.TerrainKind.scriptPathOf]): needed when it has one. */
    fun compile(file: TerrainFile, scriptPath: String? = null): CompiledTerrain {
        val palette = Palette()
        fun block(choice: BlockChoice, default: String): Int =
            choice.customBlock?.let { palette.custom(it.text) } ?: palette.vanilla(choice.block ?: default)
        fun layers(list: List<Layer>) = list.map { CompiledLayer(block(it, TerrainFile.AIR), it.thicknessOrDefault) }
        fun noises(map: Map<String, TerrainNoise>) =
            map.entries.sortedBy { it.key }.map { (name, n) -> CompiledTerrainNoise(name, n.noise, n.amplitudeOrDefault) }
        fun densityNoises(map: Map<String, DensityNoise>) = map.entries.sortedBy { it.key }.map { (name, n) ->
            CompiledDensityNoise(name, n.noise, n.amplitudeOrDefault, n.squashOrDefault)
        }

        val terrain = file.terrain
        val fluid = palette.vanilla(terrain.fluid ?: TerrainFile.DEFAULT_FLUID)
        val fileTerrain = CompiledAreaTerrain(terrain.baseOrDefault, 1.0, emptyList())
        val climate = file.climate.noises.entries.sortedBy { it.key }.map { (name, noise) -> CompiledClimateNoise(name, noise) }
        val loot = (file.decorations.values.mapNotNull { it.loot?.text } + file.script?.loot.orEmpty().map { it.text }).distinct().sorted()
        val areas = file.biomes.entries.sortedBy { it.key }.map { (name, area) ->
            CompiledArea(
                name,
                area.biome,
                area.temperature?.minOrDefault ?: -1.0,
                area.temperature?.maxOrDefault ?: 1.0,
                area.humidity?.minOrDefault ?: -1.0,
                area.humidity?.maxOrDefault ?: 1.0,
                area.layers?.let(::layers),
                area.underwater?.let(::layers),
                area.terrain?.let {
                    CompiledAreaTerrain(
                        it.base ?: terrain.baseOrDefault,
                        it.scaleOrDefault,
                        noises(it.noises),
                        it.density?.scaleOrDefault ?: 1.0,
                        it.density?.noises?.let(::densityNoises).orEmpty()
                    )
                }
                    ?: fileTerrain,
                climate.flatMap { listOf(area.climate[it.name]?.minOrDefault ?: -1.0, area.climate[it.name]?.maxOrDefault ?: 1.0) },
                if (area.isVolume) {
                    CompiledVolume(
                        area.y?.min ?: Int.MIN_VALUE,
                        area.y?.max ?: Int.MAX_VALUE,
                        area.depth?.min ?: Int.MIN_VALUE,
                        area.depth?.max ?: Int.MAX_VALUE,
                        area.surface?.min ?: Int.MIN_VALUE,
                        area.surface?.max ?: Int.MAX_VALUE
                    )
                } else {
                    null
                }
            )
        }.ifEmpty {
            listOf(CompiledArea("default", TerrainFile.DEFAULT_BIOME, -1.0, 1.0, -1.0, 1.0, null, null, fileTerrain))
        }
        val areaIndex = areas.withIndex().associate { it.value.name to it.index }
        fun areasOf(names: List<String>) = names.mapNotNull { areaIndex[it] }.distinct().sorted()

        val stone = file.stone?.let { block(it, TerrainFile.DEFAULT_STONE) } ?: palette.vanilla(TerrainFile.DEFAULT_STONE)
        val jitter = file.climate.jitter
        val compiled = CompiledTerrain(
            palette = palette.entries,
            base = terrain.baseOrDefault,
            seaLevel = terrain.seaLevelOrDefault,
            fluid = fluid,
            noises = noises(terrain.noises),
            layers = layers(file.layers),
            underwater = layers(file.underwater).ifEmpty { layers(file.layers) },
            stone = stone,
            floorBlock = file.floor?.let { block(it, Floor.DEFAULT_BLOCK) } ?: 0,
            floorThickness = file.floor?.thicknessOrDefault ?: 0,
            caves = file.caves.entries.sortedBy { it.key }.map { (name, c) ->
                CompiledCave(
                    name,
                    c.typeOrDefault,
                    c.noise ?: Cave.DEFAULT_NOISE,
                    c.thresholdOrDefault(),
                    c.minYOrDefault,
                    c.maxYOrDefault,
                    c.depthOrDefault,
                    areasOf(c.biomes),
                    c.fadeOrDefault
                )
            },
            ores = file.ores.entries.sortedBy { it.key }.map { (name, o) ->
                val replace = (
                    o.replace.map {
                        palette.vanilla(it)
                    } + o.replaceCustomBlocks.map { palette.custom(it.text) }
                    ).ifEmpty { listOf(stone) }
                CompiledOre(
                    name,
                    block(o, TerrainFile.AIR),
                    replace.distinct().sorted(),
                    o.sizeOrDefault,
                    o.veinsOrDefault,
                    o.minYOrDefault,
                    o.maxYOrDefault,
                    o.distributionOrDefault,
                    areasOf(o.biomes)
                )
            },
            decorations = file.decorations.entries.sortedBy { it.key }.map { (name, d) ->
                CompiledDecoration(
                    name,
                    if (d.structure != null) 0 else block(d, TerrainFile.AIR),
                    d.structure?.text,
                    d.placementOrDefault,
                    d.countOrDefault,
                    d.chanceOrDefault,
                    d.noise,
                    d.thresholdOrDefault,
                    areasOf(d.biomes),
                    d.minY,
                    d.maxY,
                    d.on.map { GameIds.normalize(it) }.distinct().sorted(),
                    d.structure != null && d.rotateOrDefault,
                    d.loot?.let { loot.indexOf(it.text) } ?: -1,
                    d.onCustomBlocks.map { it.text }.distinct().sorted()
                )
            },
            temperature = file.climate.temperature ?: Climate.DEFAULT_NOISE,
            humidity = file.climate.humidity ?: Climate.DEFAULT_NOISE,
            jitter = jitter?.noise ?: Jitter.DEFAULT_NOISE,
            jitterAmplitude = jitter?.amplitudeOrDefault ?: Jitter.DEFAULT_AMPLITUDE,
            blend = terrain.blendOrDefault,
            areas = areas,
            vanillaStructures = file.structures.vanillaOrDefault,
            climate = climate,
            loot = loot,
            density = terrain.density?.let { density ->
                CompiledDensity(
                    densityNoises(density.noises),
                    density.islands?.let {
                        CompiledIslands(
                            it.yOrDefault,
                            it.thicknessOrDefault,
                            it.noise ?: Islands.DEFAULT_NOISE,
                            it.thresholdOrDefault,
                            areasOf(it.biomes)
                        )
                    }
                )
            }
        )
        val script = file.script ?: return compiled
        // Last, so the file's own blocks keep their places whatever the script lists.
        script.blocks.forEach { palette.vanilla(it) }
        script.customBlocks.forEach { palette.custom(it.text) }
        return compiled.copy(
            palette = palette.entries.toList(),
            script = CompiledScript(
                requireNotNull(scriptPath) { "a file with a script compiles with its script's path" },
                script.budgetOrDefault,
                script.noises.entries.sortedBy { it.key }.map { (name, noise) -> CompiledScriptNoise(name, noise) }
            )
        )
    }

    /** The palette as it's built: air is always 0, and a block takes one place however often a file says it. */
    private class Palette {
        val entries = mutableListOf<TerrainBlock>(TerrainBlock.Vanilla(TerrainFile.AIR))

        fun vanilla(text: String): Int {
            val state = BlockState.parse(text)?.toString() ?: GameIds.normalize(text)
            return of(TerrainBlock.Vanilla(state))
        }

        fun custom(name: String): Int = of(TerrainBlock.Custom(name))

        private fun of(block: TerrainBlock): Int {
            val at = entries.indexOf(block)
            if (at >= 0) return at
            entries += block
            return entries.size - 1
        }
    }
}
