package dev.netherforge.format.terrain

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.noise.NoiseDef
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.validate.Rules

/**
 * Checks a `terrain/<id>.json` on its own: shapes and ranges, and with game
 * data that its blocks and biomes exist. That a custom block or a structure
 * exists is the loader's reference check, and that a custom block is a cube
 * the kind's cross check.
 *
 * Heights are held to [height]: by default [WorldHeight.LIMITS], every height
 * any world can have, since a file doesn't know which worlds it will shape. A
 * caller that knows the world (a dimension type with its own build limits)
 * passes that world's.
 */
object TerrainValidator {
    private val BLOCK = Rules.BlockStateCodes(ProblemCodes.TERRAIN_BLOCK, ProblemCodes.TERRAIN_BLOCK, ProblemCodes.TERRAIN_BLOCK)

    fun validate(file: TerrainFile, sink: ProblemSink, game: GameData?, height: WorldHeight = WorldHeight.LIMITS) {
        val check = Check(sink, game, height, file.biomes.keys, file.climate.noises.keys, file.biomes.filterValues { it.isVolume }.keys)
        check.terrain(file.terrain)
        file.terrain.density?.let { check.density(it, "$.terrain.density") }
        for ((name, area) in file.biomes) {
            val density = area.terrain?.density ?: continue
            val at = CanonicalJson.childPath("$.biomes", name) + ".terrain.density"
            if (file.terrain.density == null) {
                sink.report(
                    ProblemCodes.TERRAIN_DENSITY,
                    "An area's density is of the file's: add a terrain.density to the file (`{}` for no noises of its own)",
                    at
                )
            }
            check.areaDensity(density, at)
        }
        check.layers(file.layers, "$.layers")
        check.layers(file.underwater, "$.underwater")
        file.stone?.let { check.choice(it, "$.stone", required = true) }
        file.floor?.let { floor ->
            check.choice(floor, "$.floor", required = false)
            check.thickness(floor.thickness, "$.floor.thickness", "floor thickness")
        }
        check.named(file.caves.keys, "$.caves", "caves")
        for ((name, cave) in file.caves) check.cave(cave, CanonicalJson.childPath("$.caves", name))
        check.named(file.ores.keys, "$.ores", "ores")
        for ((name, ore) in file.ores) check.ore(ore, CanonicalJson.childPath("$.ores", name))
        check.named(file.decorations.keys, "$.decorations", "decorations")
        for ((name, decoration) in file.decorations) check.decoration(decoration, CanonicalJson.childPath("$.decorations", name))
        file.climate.temperature?.let { check.noise(it, "$.climate.temperature") }
        file.climate.humidity?.let { check.noise(it, "$.climate.humidity") }
        file.climate.jitter?.let { check.jitter(it, "$.climate.jitter") }
        check.named(file.climate.noises.keys, "$.climate.noises", "climate values", Climate.MAX_NOISES)
        for ((name, noise) in file.climate.noises) check.noise(noise, CanonicalJson.childPath("$.climate.noises", name))
        check.named(file.biomes.keys, "$.biomes", "biome areas")
        if (file.biomes.isNotEmpty() && file.biomes.values.all { it.isVolume }) {
            sink.report(
                ProblemCodes.TERRAIN_VOLUME,
                "Every biome area here is limited by height, so no column has an area: add one without `y`, `depth` or `surface`",
                "$.biomes"
            )
        }
        for ((name, area) in file.biomes) check.area(area, CanonicalJson.childPath("$.biomes", name))
        file.script?.let { check.script(it, "$.script") }
    }

    private class Check(
        val sink: ProblemSink,
        val game: GameData?,
        val height: WorldHeight,
        val areas: Set<String>,
        val climateNoises: Set<String>,
        val volumes: Set<String>
    ) {
        private val lowest = height.minY
        private val highest = height.maxY - 1

        fun terrain(terrain: Terrain) {
            height(terrain.base, "$.terrain.base", "base")
            height(terrain.seaLevel, "$.terrain.seaLevel", "seaLevel")
            terrain.fluid?.let { Rules.blockState(it, "$.terrain.fluid", BLOCK, sink, game) }
            noises(terrain.noises, "$.terrain.noises")
            terrain.blend?.let {
                if (it !in 0..TerrainFile.MAX_BLEND) {
                    sink.report(
                        ProblemCodes.TERRAIN_BORDER,
                        "blend is from 0 to ${TerrainFile.MAX_BLEND} blocks, not $it",
                        "$.terrain.blend"
                    )
                }
            }
        }

        fun noises(noises: Map<String, TerrainNoise>, at: String) {
            named(noises.keys, at, "terrain noises", TerrainFile.MAX_NOISES)
            for ((name, entry) in noises) {
                val path = CanonicalJson.childPath(at, name)
                noise(entry.noise, "$path.noise")
                val amplitude = entry.amplitude
                if (amplitude != null &&
                    !(amplitude.isFinite() && amplitude >= -TerrainFile.MAX_AMPLITUDE && amplitude <= TerrainFile.MAX_AMPLITUDE)
                ) {
                    sink.report(
                        ProblemCodes.TERRAIN_HEIGHT,
                        "amplitude must be a number from -${TerrainFile.MAX_AMPLITUDE.toInt()} to ${TerrainFile.MAX_AMPLITUDE.toInt()}",
                        "$path.amplitude"
                    )
                }
            }
        }

        fun density(density: Density, at: String) {
            densityNoises(density.noises, "$at.noises")
            density.islands?.let { islands(it, "$at.islands") }
        }

        fun areaDensity(density: AreaDensity, at: String) {
            scale(density.scale, "$at.scale", ProblemCodes.TERRAIN_DENSITY)
            densityNoises(density.noises, "$at.noises")
        }

        private fun densityNoises(noises: Map<String, DensityNoise>, at: String) {
            named(noises.keys, at, "3D noises", TerrainFile.MAX_NOISES)
            for ((name, entry) in noises) {
                val path = CanonicalJson.childPath(at, name)
                noise(entry.noise, "$path.noise")
                amplitude(entry.amplitude, "$path.amplitude")
                entry.squash?.let {
                    if (!(it.isFinite() && it > 0.0 && it <= TerrainFile.MAX_SCALE)) {
                        sink.report(
                            ProblemCodes.TERRAIN_DENSITY,
                            "squash must be more than 0 and at most ${TerrainFile.MAX_SCALE.toInt()}",
                            "$path.squash"
                        )
                    }
                }
            }
        }

        private fun islands(islands: Islands, at: String) {
            islands.y?.let {
                if (it !in
                    lowest..highest
                ) {
                    sink.report(ProblemCodes.TERRAIN_DENSITY, "y must be from $lowest to $highest, not $it", "$at.y")
                }
            }
            islands.thickness?.let {
                if (it !in Islands.MIN_THICKNESS..TerrainFile.MAX_THICKNESS) {
                    sink.report(
                        ProblemCodes.TERRAIN_DENSITY,
                        "thickness is from ${Islands.MIN_THICKNESS} to ${TerrainFile.MAX_THICKNESS} blocks, not $it",
                        "$at.thickness"
                    )
                }
            }
            islands.noise?.let { noise(it, "$at.noise") }
            islands.threshold?.let {
                if (!(it.isFinite() && it >= -1.0 && it < 1.0)) {
                    sink.report(ProblemCodes.TERRAIN_DENSITY, "threshold is from -1 up to but not including 1", "$at.threshold")
                }
            }
            areaFilter(islands.biomes, "$at.biomes")
            islands.biomes.forEachIndexed { i, name ->
                if (name in volumes) {
                    sink.report(
                        ProblemCodes.TERRAIN_VOLUME,
                        "\"$name\" is limited by height: islands float over columns' areas",
                        "$at.biomes[$i]"
                    )
                }
            }
        }

        private fun amplitude(amplitude: Double?, at: String) {
            if (amplitude != null &&
                !(amplitude.isFinite() && amplitude >= -TerrainFile.MAX_AMPLITUDE && amplitude <= TerrainFile.MAX_AMPLITUDE)
            ) {
                sink.report(
                    ProblemCodes.TERRAIN_HEIGHT,
                    "amplitude must be a number from -${TerrainFile.MAX_AMPLITUDE.toInt()} to ${TerrainFile.MAX_AMPLITUDE.toInt()}",
                    at
                )
            }
        }

        fun scale(scale: Double?, at: String, code: dev.netherforge.format.ProblemCode) {
            if (scale != null && !(scale.isFinite() && scale >= 0.0 && scale <= TerrainFile.MAX_SCALE)) {
                sink.report(code, "scale is from 0 to ${TerrainFile.MAX_SCALE.toInt()}", at)
            }
        }

        fun height(value: Int?, at: String, noun: String) {
            if (value != null && value !in lowest..highest) {
                sink.report(ProblemCodes.TERRAIN_HEIGHT, "$noun must be from $lowest to $highest, not $value", at)
            }
        }

        /** A noise's numbers, which [FastNoiseLite][dev.netherforge.format.noise.FastNoiseLite] would take anything of but the pattern would be nonsense. */
        fun noise(noise: NoiseDef, at: String) {
            noise.frequency?.let {
                if (!(it.isFinite() && it > 0.0 && it <= NoiseDef.MAX_FREQUENCY)) {
                    sink.report(
                        ProblemCodes.TERRAIN_NOISE,
                        "frequency must be more than 0 and at most ${NoiseDef.MAX_FREQUENCY.toInt()}",
                        "$at.frequency"
                    )
                }
            }
            noise.octaves?.let {
                if (it !in 1..NoiseDef.MAX_OCTAVES) {
                    sink.report(ProblemCodes.TERRAIN_NOISE, "octaves is from 1 to ${NoiseDef.MAX_OCTAVES}, not $it", "$at.octaves")
                }
            }
            noise.lacunarity?.let {
                if (!(it.isFinite() && it >= 1.0 && it <= 4.0)) {
                    sink.report(ProblemCodes.TERRAIN_NOISE, "lacunarity is from 1 to 4", "$at.lacunarity")
                }
            }
            noise.gain?.let {
                if (!(it.isFinite() && it >= 0.0 && it <= 1.0)) {
                    sink.report(ProblemCodes.TERRAIN_NOISE, "gain is from 0 to 1", "$at.gain")
                }
            }
        }

        fun jitter(jitter: Jitter, at: String) {
            jitter.noise?.let { noise(it, "$at.noise") }
            jitter.amplitude?.let {
                if (!(it.isFinite() && it >= 0.0 && it <= TerrainFile.MAX_AMPLITUDE)) {
                    sink.report(
                        ProblemCodes.TERRAIN_BORDER,
                        "amplitude is from 0 to ${TerrainFile.MAX_AMPLITUDE.toInt()} blocks",
                        "$at.amplitude"
                    )
                }
            }
        }

        fun layers(layers: List<Layer>, at: String) {
            if (layers.size > TerrainFile.MAX_LAYERS) {
                sink.report(ProblemCodes.TERRAIN_LIMIT, "A list of layers holds at most ${TerrainFile.MAX_LAYERS}", at)
            }
            layers.forEachIndexed { i, layer ->
                choice(layer, "$at[$i]", required = true)
                thickness(layer.thickness, "$at[$i].thickness", "thickness")
            }
        }

        /** A block or a custom block, not both; [required] when neither isn't allowed either (a floor has a default). */
        fun choice(choice: BlockChoice, at: String, required: Boolean, structure: Boolean = false) {
            val block = choice.block
            val given = listOfNotNull(block, choice.customBlock, if (structure) "structure" else null).size
            if (given > 1) {
                val names = if (structure) "`block`, `customBlock` and `structure`, not more" else "`block` and `customBlock`, not both"
                sink.report(ProblemCodes.TERRAIN_ONE_BLOCK, "Name one of $names", at)
            } else if (given == 0 && required) {
                val names = if (choice is Decoration) "a `block`, a `customBlock` or a `structure`" else "a `block` or a `customBlock`"
                sink.report(ProblemCodes.TERRAIN_ONE_BLOCK, "Name $names", at)
            }
            block?.let { Rules.blockState(it, "$at.block", BLOCK, sink, game) }
        }

        fun thickness(value: Int?, at: String, noun: String) {
            if (value != null && value !in 1..TerrainFile.MAX_THICKNESS) {
                sink.report(ProblemCodes.TERRAIN_LAYER, "$noun is from 1 to ${TerrainFile.MAX_THICKNESS} blocks, not $value", at)
            }
        }

        /** Keys of a map are ids (they're file names in the editor and in messages), and there aren't too many. */
        fun named(names: Collection<String>, at: String, noun: String, limit: Int = TerrainFile.MAX_NAMED) {
            if (names.size > limit) sink.report(ProblemCodes.TERRAIN_LIMIT, "A file holds at most $limit $noun", at)
            for (name in names) {
                if (!Names.isId(name)) {
                    sink.report(
                        ProblemCodes.TERRAIN_NAME,
                        "\"$name\" can't name one of the $noun (${Names.ID_RULE})",
                        CanonicalJson.childPath(at, name)
                    )
                }
            }
        }

        /** A `biomes` filter names areas of this file. */
        fun areaFilter(names: List<String>, at: String) {
            names.forEachIndexed { i, name ->
                if (name !in areas) {
                    val known = if (areas.isEmpty()) "the file has none" else "its areas are ${areas.sorted().joinToString(", ")}"
                    sink.report(ProblemCodes.TERRAIN_AREA, "\"$name\" isn't one of the file's biome areas ($known)", "$at[$i]")
                }
            }
        }

        /** A pair of heights a thing names, each inside the world and the lower not above the higher. */
        fun heights(minY: Int?, maxY: Int?, min: Int, max: Int, at: String, code: dev.netherforge.format.ProblemCode) {
            for ((value, key) in listOf(minY to "minY", maxY to "maxY")) {
                if (value != null && value !in lowest..highest) {
                    sink.report(code, "$key must be from $lowest to $highest", "$at.$key")
                }
            }
            if (min > max) sink.report(code, "minY ($min) is above maxY ($max)", "$at.minY")
        }

        fun cave(cave: Cave, at: String) {
            cave.noise?.let { noise(it, "$at.noise") }
            cave.threshold?.let {
                val cheese = cave.typeOrDefault == CaveType.CHEESE
                val ok = if (cheese) it.isFinite() && it >= -1.0 && it <= 1.0 else it.isFinite() && it > 0.0 && it <= 1.0
                if (!ok) {
                    val range = if (cheese) "from -1 to 1" else "more than 0 and at most 1"
                    sink.report(
                        ProblemCodes.TERRAIN_CAVE,
                        "threshold of a ${cave.typeOrDefault.name.lowercase()} cave must be $range",
                        "$at.threshold"
                    )
                }
            }
            heights(cave.minY, cave.maxY, cave.minYOrDefault, cave.maxYOrDefault, at, ProblemCodes.TERRAIN_CAVE)
            cave.fade?.let {
                if (it !in 0..TerrainFile.MAX_THICKNESS) {
                    sink.report(ProblemCodes.TERRAIN_CAVE, "fade is from 0 to ${TerrainFile.MAX_THICKNESS} blocks, not $it", "$at.fade")
                }
            }
            cave.depth?.let {
                if (it < 0 || it > TerrainFile.MAX_THICKNESS) {
                    sink.report(ProblemCodes.TERRAIN_CAVE, "depth is from 0 to ${TerrainFile.MAX_THICKNESS}", "$at.depth")
                }
            }
            areaFilter(cave.biomes, "$at.biomes")
        }

        fun blockIds(ids: List<String>, at: String) {
            ids.forEachIndexed { i, id ->
                val path = "$at[$i]"
                if (!GameIds.isValid(id)) {
                    sink.report(ProblemCodes.TERRAIN_BLOCK, "\"$id\" isn't a block id", path)
                } else if (game != null && game.block(GameIds.normalize(id)) == null) {
                    sink.report(
                        ProblemCodes.TERRAIN_BLOCK,
                        "Minecraft ${game.minecraftVersion} has no block \"${GameIds.normalize(id)}\"",
                        path
                    )
                }
            }
        }

        fun ore(ore: Ore, at: String) {
            val block = ore.block
            if ((block == null) == (ore.customBlock == null)) {
                sink.report(
                    ProblemCodes.TERRAIN_ORE,
                    if (block == null) {
                        "An ore needs a `block` (a vanilla one) or a `customBlock` (one of the project's)"
                    } else {
                        "An ore is a `block` or a `customBlock`, not both"
                    },
                    at
                )
            }
            block?.let { Rules.blockState(it, "$at.block", BLOCK, sink, game) }
            blockIds(ore.replace, "$at.replace")
            ore.size?.let {
                if (it !in 1..TerrainFile.MAX_ORE_SIZE) {
                    sink.report(ProblemCodes.TERRAIN_ORE, "size is from 1 to ${TerrainFile.MAX_ORE_SIZE} blocks, not $it", "$at.size")
                }
            }
            ore.veins?.let {
                if (it !in 0..TerrainFile.MAX_ORE_VEINS) {
                    sink.report(ProblemCodes.TERRAIN_ORE, "veins is from 0 to ${TerrainFile.MAX_ORE_VEINS} a chunk, not $it", "$at.veins")
                }
            }
            heights(ore.minY, ore.maxY, ore.minYOrDefault, ore.maxYOrDefault, at, ProblemCodes.TERRAIN_ORE)
            areaFilter(ore.biomes, "$at.biomes")
        }

        fun decoration(decoration: Decoration, at: String) {
            choice(decoration, at, required = true, structure = decoration.structure != null)
            if (decoration.structure != null && decoration.placementOrDefault !in Decorations.STRUCTURE_PLACEMENTS) {
                sink.report(
                    ProblemCodes.TERRAIN_DECORATION,
                    "A structure goes on the surface, underwater or underground, not on a cave's floor or ceiling",
                    "$at.placement"
                )
            }
            if (decoration.loot != null && decoration.customBlock != null) {
                sink.report(ProblemCodes.TERRAIN_DECORATION, "A project block holds no items to fill from a loot table", "$at.loot")
            }
            if (decoration.structure == null && decoration.rotate != null) {
                sink.report(ProblemCodes.TERRAIN_DECORATION, "Only a structure is turned", "$at.rotate")
            }
            decoration.count?.let {
                if (it !in 0..TerrainFile.MAX_DECORATION_COUNT) {
                    sink.report(
                        ProblemCodes.TERRAIN_DECORATION,
                        "count is from 0 to ${TerrainFile.MAX_DECORATION_COUNT} a chunk, not $it",
                        "$at.count"
                    )
                }
            }
            decoration.chance?.let {
                if (!(it.isFinite() && it >= 0.0 && it <= 1.0)) {
                    sink.report(
                        ProblemCodes.TERRAIN_DECORATION,
                        "chance is from 0 to 1",
                        "$at.chance"
                    )
                }
            }
            decoration.noise?.let { noise(it, "$at.noise") }
            decoration.threshold?.let {
                if (decoration.noise == null) {
                    sink.report(
                        ProblemCodes.TERRAIN_DECORATION,
                        "A threshold is of a noise: add one, or leave the threshold out",
                        "$at.threshold"
                    )
                } else if (!(it.isFinite() && it >= -1.0 && it < 1.0)) {
                    sink.report(ProblemCodes.TERRAIN_DECORATION, "threshold is from -1 up to but not including 1", "$at.threshold")
                }
            }
            heights(
                decoration.minY,
                decoration.maxY,
                decoration.minY ?: lowest,
                decoration.maxY ?: highest,
                at,
                ProblemCodes.TERRAIN_DECORATION
            )
            if (decoration.on.size > TerrainFile.MAX_ON) {
                sink.report(ProblemCodes.TERRAIN_LIMIT, "`on` lists at most ${TerrainFile.MAX_ON} blocks", "$at.on")
            }
            if (decoration.onCustomBlocks.size > TerrainFile.MAX_ON) {
                sink.report(ProblemCodes.TERRAIN_LIMIT, "`onCustomBlocks` lists at most ${TerrainFile.MAX_ON} blocks", "$at.onCustomBlocks")
            }
            blockIds(decoration.on, "$at.on")
            areaFilter(decoration.biomes, "$at.biomes")
        }

        fun area(area: BiomeArea, at: String) {
            val biome = area.biome
            // The project's own biomes are references, which the loader checks; the game's are checked here.
            if (biome.startsWith("#")) {
                sink.report(ProblemCodes.TERRAIN_BIOME, "An area is one biome, not a tag: \"$biome\"", "$at.biome")
            } else if (RefKind.BIOME.isGame(biome)) {
                if (!GameIds.isValid(biome)) {
                    sink.report(ProblemCodes.TERRAIN_BIOME, "\"$biome\" isn't a biome id, like \"minecraft:plains\"", "$at.biome")
                } else if (game?.registry(RegistryKey.BIOME)?.contains(biome) == false) {
                    sink.report(ProblemCodes.TERRAIN_BIOME, "Minecraft ${game.minecraftVersion} has no biome \"$biome\"", "$at.biome")
                }
            }
            range(area.temperature, "$at.temperature")
            range(area.humidity, "$at.humidity")
            for ((name, range) in area.climate) {
                val path = CanonicalJson.childPath("$at.climate", name)
                if (name !in climateNoises) {
                    val known = if (climateNoises.isEmpty()) {
                        "it declares none"
                    } else {
                        "it declares ${climateNoises.sorted().joinToString(
                            ", "
                        )}"
                    }
                    sink.report(ProblemCodes.TERRAIN_CLIMATE, "\"$name\" isn't one of the file's climate.noises ($known)", path)
                }
                range(range, path)
            }
            if (area.isVolume) {
                blockRange(area.y, "$at.y", inWorld = true)
                blockRange(area.depth, "$at.depth", inWorld = false)
                blockRange(area.surface, "$at.surface", inWorld = true)
                for ((value, key) in listOf(area.layers to "layers", area.underwater to "underwater", area.terrain to "terrain")) {
                    if (value != null) {
                        sink.report(
                            ProblemCodes.TERRAIN_VOLUME,
                            "An area limited by height gives its places a biome and nothing else: it has no $key",
                            "$at.$key"
                        )
                    }
                }
            }
            area.layers?.let { layers(it, "$at.layers") }
            area.underwater?.let { layers(it, "$at.underwater") }
            area.terrain?.let { terrain ->
                height(terrain.base, "$at.terrain.base", "base")
                terrain.scale?.let {
                    if (!(it.isFinite() && it >= 0.0 && it <= TerrainFile.MAX_SCALE)) {
                        sink.report(
                            ProblemCodes.TERRAIN_HEIGHT,
                            "scale is from 0 to ${TerrainFile.MAX_SCALE.toInt()}",
                            "$at.terrain.scale"
                        )
                    }
                }
                noises(terrain.noises, "$at.terrain.noises")
            }
        }

        /** The script's own declarations: whether its `.lua` is there is the kind's to say, which sees the files. */
        fun script(script: TerrainScript, at: String) {
            script.budget?.let {
                if (it !in TerrainScript.MIN_BUDGET..TerrainScript.MAX_BUDGET) {
                    sink.report(
                        ProblemCodes.TERRAIN_SCRIPT,
                        "budget is from ${TerrainScript.MIN_BUDGET} to ${TerrainScript.MAX_BUDGET} instructions a call, not $it",
                        "$at.budget"
                    )
                }
            }
            named(script.noises.keys, "$at.noises", "script's noises", TerrainFile.MAX_NOISES)
            for ((name, noise) in script.noises) noise(noise, CanonicalJson.childPath("$at.noises", name))
            if (script.blocks.size + script.customBlocks.size > TerrainScript.MAX_BLOCKS) {
                sink.report(ProblemCodes.TERRAIN_SCRIPT, "A script lists at most ${TerrainScript.MAX_BLOCKS} blocks", at)
            }
            if (script.loot.size > TerrainScript.MAX_LOOT) {
                sink.report(ProblemCodes.TERRAIN_SCRIPT, "A script lists at most ${TerrainScript.MAX_LOOT} loot tables", "$at.loot")
            }
            script.blocks.forEachIndexed { i, block -> Rules.blockState(block, "$at.blocks[$i]", BLOCK, sink, game) }
        }

        /** A volume's range: inside the world (a [depth][BiomeArea.depth] needn't be), and not empty. */
        fun blockRange(range: BlockRange?, at: String, inWorld: Boolean) {
            if (range == null) return
            val limit = height.maxY - height.minY
            for ((value, key) in listOf(range.min to "min", range.max to "max")) {
                if (value == null) continue
                val ok = if (inWorld) value in lowest..highest else value in -limit..limit
                if (!ok) {
                    val span = if (inWorld) "from $lowest to $highest" else "from -$limit to $limit"
                    sink.report(ProblemCodes.TERRAIN_VOLUME, "$key must be $span, not $value", "$at.$key")
                }
            }
            val min = range.min
            val max = range.max
            if (min != null &&
                max != null &&
                min > max
            ) {
                sink.report(ProblemCodes.TERRAIN_VOLUME, "min ($min) is above max ($max)", "$at.min")
            }
        }

        fun range(range: ClimateRange?, at: String) {
            if (range == null) return
            val values = listOf("min" to range.minOrDefault, "max" to range.maxOrDefault)
            for ((key, value) in values) {
                if (!(value.isFinite() && value >= -1.0 && value <= 1.0)) {
                    sink.report(ProblemCodes.TERRAIN_CLIMATE, "$key must be from -1 to 1", "$at.$key")
                }
            }
            if (range.minOrDefault > range.maxOrDefault) {
                sink.report(
                    ProblemCodes.TERRAIN_CLIMATE,
                    "min (${num(range.minOrDefault)}) is above max (${num(range.maxOrDefault)})",
                    "$at.min"
                )
            }
        }

        /** A number as the canonical writer prints it, the same on every platform. */
        private fun num(value: Double) = CanonicalJson.formatNumber(value.toString())
    }
}
