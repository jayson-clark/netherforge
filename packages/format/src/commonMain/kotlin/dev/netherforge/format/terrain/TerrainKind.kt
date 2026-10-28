package dev.netherforge.format.terrain

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.noise.NoiseDef
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef

/**
 * `terrain/<id>.json`: how the terrain of a world the project makes is
 * generated (heights from noise, layers, caves, ores, biome areas). The
 * server's generator and the editor's preview both run [TerrainCompiler]'s
 * result. Its companion, `terrain/<id>.lua`, is the script its `script`
 * hands stages to ([TerrainScript]): Lua format never reads itself, run in
 * isolated states of their own ([dev.netherforge.format.terrain.TerrainScripts]).
 */
object TerrainKind : DocumentResourceKind<TerrainFile, CompiledTerrain>(
    "terrain",
    "terrain",
    Layout.SingleFile(".json", companion = TerrainScript.EXTENSION),
    TerrainFile.serializer(),
    TerrainFile.SCHEMA
) {
    override fun canonical(value: TerrainFile) = value.copy(schema = schemaRef)

    /** The script beside terrain [id], `terrain/<id>.lua` (a package's at its package path), in its [group] folders. */
    fun scriptPathOf(id: String, group: String? = null): String = requireNotNull(companionPathOf(id, group))

    override fun validate(value: TerrainFile, ctx: ResourceContext) {
        TerrainValidator.validate(value, ctx.sink, ctx.game)
        val script = ctx.id + TerrainScript.EXTENSION
        if (value.script != null && script !in ctx.files) {
            ctx.sink.report(
                ProblemCodes.TERRAIN_SCRIPT_MISSING,
                "There's no ${scriptPathOf(ctx.id, ctx.group)} beside this file for its script to run",
                "$.script"
            )
        } else if (value.script == null && script in ctx.files) {
            ctx.problem(
                ProblemCodes.TERRAIN_SCRIPT_UNUSED,
                scriptPathOf(ctx.id, ctx.group),
                "${pathOf(ctx.id, ctx.group)} has no `script`, so this never runs: add one to hand it stages"
            )
        }
    }

    /**
     * What a new `terrain/<id>.lua` starts as: the stages, each leaving what the file made as it is, with the
     * lines that tell lua-language-server what `terrain` and the stages are.
     */
    fun scriptTemplate(): String =
        """
        |-- The stages this terrain hands to Lua. They run on the server's chunk threads (and in the
        |-- editor's preview) in Lua states of their own: no `nf`, and the same blocks for a seed every time.
        |---@type Terrain
        |local terrain = ...
        |
        |---@type TerrainStages
        |local stages = {}
        |
        |-- The y of a column's top block: `height` is the file's.
        |function stages.height(x, z, height)
        |  return math.min(height, terrain.max_y())
        |end
        |
        |-- After the file's terrain (stone, layers and the sea), before caves: `chunk:fill(...)`.
        |function stages.terrain(chunk) end
        |
        |-- After the file's decorations.
        |function stages.decorate(chunk) end
        |
        |return stages
        |
        """.trimMargin()

    /**
     * A custom block a terrain places (in a layer, the stone, the floor, an
     * ore or a decoration) is written into chunk data as its state, and a block
     * that's drawn by a centity needs the centity spawned too, which a
     * terrain can't do: such a block can't be placed. (Only the project's own
     * blocks can be looked at from here; a package's block is its package's to
     * keep a cube.)
     */
    override fun crossCheck(value: TerrainFile, ctx: KindContext) {
        val blocks = ctx.models(BlockKind)
        for ((at, reference) in customBlocks(value)) {
            val key = ctx.references.resolve(RefKind.BLOCK, reference.text)?.takeIf { it.namespace == ctx.references.home } ?: continue
            val block = blocks[key.path] ?: continue
            val centity = block.centity ?: continue
            ctx.sink.report(
                ProblemCodes.TERRAIN_CUSTOM_BLOCK,
                "Block \"$reference\" is drawn by the centity \"$centity\", so a terrain can't place it: use a block that's a plain cube",
                at,
                listOf(Location(ctx.placeOf(BlockKind, key.path).path, "$.centity"))
            )
        }
    }

    /** Every project block a file names, with the JSON path of its reference, in the file's order. */
    private fun customBlocks(file: TerrainFile): List<Pair<String, ResourceRef>> = buildList {
        fun choice(choice: BlockChoice, at: String) = choice.customBlock?.let { add("$at.customBlock" to it) }
        fun layers(list: List<Layer>?, at: String) = list?.forEachIndexed { i, layer -> choice(layer, "$at[$i]") }
        layers(file.layers, "$.layers")
        layers(file.underwater, "$.underwater")
        file.stone?.let { choice(it, "$.stone") }
        file.floor?.let { choice(it, "$.floor") }
        for ((name, ore) in file.ores) choice(ore, CanonicalJson.childPath("$.ores", name))
        for ((name, decoration) in file.decorations) choice(decoration, CanonicalJson.childPath("$.decorations", name))
        for ((name, area) in file.biomes) {
            val at = CanonicalJson.childPath("$.biomes", name)
            layers(area.layers, "$at.layers")
            layers(area.underwater, "$at.underwater")
        }
        file.script?.customBlocks?.forEachIndexed { i, block -> add("$.script.customBlocks[$i]" to block) }
    }

    override fun compile(id: String, value: TerrainFile, ctx: ResourceContext) = TerrainCompiler.compile(value, scriptPathOf(id, ctx.group))

    /** Terrains that fill containers from loot tables need the empty table the game unpacks them with ([GeneratedLoot]). */
    override val datapackAll = DatapackCollection<CompiledTerrain> { resources, _ ->
        if (resources.values.none { it.loot.isNotEmpty() }) emptyMap() else mapOf(GeneratedLoot.DATAPACK_PATH to GeneratedLoot.entry)
    }

    /**
     * Rolling hills and a flat sea, a grass and dirt top, caves, coal and
     * iron, patches of flowers, and a cold and a warm biome: starter content the user changes in
     * the editor, whose live preview shows it.
     */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            TerrainFile(
                terrain = Terrain(
                    base = 66,
                    seaLevel = 62,
                    noises = mapOf(
                        "hills" to TerrainNoise(NoiseDef(frequency = 0.004, octaves = 4), amplitude = 28.0),
                        "detail" to TerrainNoise(NoiseDef(frequency = 0.04, octaves = 2), amplitude = 3.0)
                    )
                ),
                layers = listOf(Layer(block = "minecraft:grass_block"), Layer(block = "minecraft:dirt", thickness = 3)),
                underwater = listOf(Layer(block = "minecraft:sand", thickness = 2), Layer(block = "minecraft:dirt", thickness = 2)),
                ores = mapOf(
                    "coal" to Ore(block = "minecraft:coal_ore", size = 12, veins = 16, minY = 0, maxY = 96),
                    "iron" to Ore(block = "minecraft:iron_ore", size = 8, veins = 10, minY = -32, maxY = 64)
                ),
                caves = mapOf("caverns" to Cave()),
                decorations = mapOf(
                    "flowers" to Decoration(
                        block = "minecraft:dandelion",
                        count = 6,
                        noise = NoiseDef(frequency = 0.05),
                        threshold = 0.2,
                        on = listOf("minecraft:grass_block")
                    )
                ),
                biomes = mapOf(
                    "plains" to BiomeArea("minecraft:plains", temperature = ClimateRange(min = -0.2), humidity = null),
                    "snowy" to BiomeArea(
                        "minecraft:snowy_plains",
                        temperature = ClimateRange(max = -0.2),
                        layers = listOf(Layer(block = "minecraft:snow_block"), Layer(block = "minecraft:dirt", thickness = 3))
                    )
                )
            )
        )
    )
}
