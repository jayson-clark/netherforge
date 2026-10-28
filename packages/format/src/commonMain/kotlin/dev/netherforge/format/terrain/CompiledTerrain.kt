package dev.netherforge.format.terrain

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.lua.LuaStrings
import dev.netherforge.format.noise.NoiseDef
import dev.netherforge.format.world.EmptyBlocks

/**
 * A block a generated chunk holds: a vanilla block state in canonical text
 * (`minecraft:grass_block`, `minecraft:oak_log[axis=y]`), or one of the
 * project's custom blocks by the name the project knows it by. The server
 * turns a [Custom] into the note block state it is held as; the preview just
 * shows its name.
 */
sealed interface TerrainBlock {
    data class Vanilla(val state: String) : TerrainBlock

    data class Custom(val name: String) : TerrainBlock

    /** A name for people: the block's id, or the custom block's name. */
    val label: String get() = when (this) {
        is Vanilla -> state
        is Custom -> name
    }
}

/**
 * A file's script as a generator runs it: the project (or package) path of its `.lua` ([file]), the instructions
 * each call may use, and the noises it asks for by name, sorted by name.
 */
data class CompiledScript(val file: String, val budget: Int, val noises: List<CompiledScriptNoise>)

data class CompiledScriptNoise(val name: String, val noise: NoiseDef)

/** [thickness] blocks of palette entry [block]. */
data class CompiledLayer(val block: Int, val thickness: Int)

data class CompiledTerrainNoise(val name: String, val noise: NoiseDef, val amplitude: Double)

/**
 * A biome area's terrain: [base] plus the file's noises times [scale] plus its own [noises]; in a file with a
 * density, the file's 3D noises times [densityScale] plus its own [densityNoises].
 */
data class CompiledAreaTerrain(
    val base: Int,
    val scale: Double,
    val noises: List<CompiledTerrainNoise>,
    val densityScale: Double = 1.0,
    val densityNoises: List<CompiledDensityNoise> = emptyList()
)

data class CompiledDensityNoise(val name: String, val noise: NoiseDef, val amplitude: Double, val squash: Double)

/** A file's 3D terrain: its own 3D [noises] (each area's are in its [CompiledAreaTerrain]) and its [islands]. */
data class CompiledDensity(val noises: List<CompiledDensityNoise>, val islands: CompiledIslands?)

data class CompiledIslands(
    val y: Int,
    val thickness: Int,
    val noise: NoiseDef,
    val threshold: Double,
    /** The areas they float over, sorted; empty for all. */
    val areas: List<Int>
)

data class CompiledCave(
    val name: String,
    val type: CaveType,
    val noise: NoiseDef,
    val threshold: Double,
    val minY: Int,
    val maxY: Int,
    val depth: Int,
    /** The indexes in [CompiledTerrain.areas] of the areas it's carved in, sorted; empty for all. */
    val areas: List<Int>,
    /** Blocks it narrows over at each end; 0 for a flat cut. */
    val fade: Int = 0
) {
    /**
     * The threshold at [y] (inside [minY]..[maxY]): [threshold] deeper than [fade] from both ends, tightening linearly
     * towards them until the block at each end carves nothing (a cheese cave's 1, which the noise never passes; a
     * spaghetti cave's 0).
     */
    fun thresholdAt(y: Int): Double {
        if (fade == 0) return threshold
        val inside = minOf(y - minY, maxY - y)
        if (inside >= fade) return threshold
        val open = inside.toDouble() / fade
        return when (type) {
            CaveType.CHEESE -> 1.0 - (1.0 - threshold) * open
            CaveType.SPAGHETTI -> threshold * open
        }
    }
}

data class CompiledOre(
    val name: String,
    /** The palette entry it places. */
    val block: Int,
    /** The palette entries it may replace, sorted. */
    val replace: List<Int>,
    val size: Int,
    val veins: Int,
    val minY: Int,
    val maxY: Int,
    val distribution: OreDistribution,
    /** The areas a vein may start in, sorted; empty for all. */
    val areas: List<Int>
)

/**
 * A decoration: a palette entry [block] (0 for a structure) or the [structure] (as the file names it, the key of
 * [CompiledTerrain.templates] once linked), tried [count] times a chunk.
 */
data class CompiledDecoration(
    val name: String,
    val block: Int,
    val structure: String?,
    val placement: DecorationPlacement,
    val count: Int,
    val chance: Double,
    val noise: NoiseDef?,
    val threshold: Double,
    /** The areas it's placed in, sorted; empty for all. */
    val areas: List<Int>,
    /** Null for the world's own bottom and top. */
    val minY: Int?,
    val maxY: Int?,
    /** Block ids (canonical) it sits on, hangs from or replaces; empty for the default (when [onCustom] is too). */
    val on: List<String>,
    val rotate: Boolean,
    /** The loot table its containers are filled from (an index into [CompiledTerrain.loot]), or -1 for none. */
    val loot: Int = -1,
    /** The project's blocks it sits on, hangs from or replaces besides [on], as [TerrainBlock.Custom] names them, sorted. */
    val onCustom: List<String> = emptyList()
)

/**
 * A biome area: its [biome] as the file names it (the game's `minecraft:plains`,
 * or the project's `ruby_grove`, `acme:grove`, which the server resolves in
 * the project's namespace) and the climate box it's found in. [layers] and
 * [underwater] are null where the file's own apply; [terrain] is the file's
 * when the area has none of its own.
 */
data class CompiledArea(
    val name: String,
    val biome: String,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val humidityMin: Double,
    val humidityMax: Double,
    val layers: List<CompiledLayer>?,
    val underwater: List<CompiledLayer>?,
    val terrain: CompiledAreaTerrain,
    /** Its range of each of the file's other climate values, in [CompiledTerrain.climate]'s order: min, max, min, max... */
    val climate: List<Double> = emptyList(),
    /** Where it is, when it's a volume inside the columns' areas; null for a column's own area. */
    val volume: CompiledVolume? = null
) {
    /** How much climate the box covers: a smaller box is the more specific. */
    val span: Double
        get() {
            var span = (temperatureMax - temperatureMin) * (humidityMax - humidityMin)
            for (i in climate.indices step 2) span *= climate[i + 1] - climate[i]
            return span
        }
}

/** A volume area's ranges, both ends included: heights, blocks below the column's surface, and the column's surface. */
data class CompiledVolume(
    val minY: Int = Int.MIN_VALUE,
    val maxY: Int = Int.MAX_VALUE,
    val minDepth: Int = Int.MIN_VALUE,
    val maxDepth: Int = Int.MAX_VALUE,
    val minSurface: Int = Int.MIN_VALUE,
    val maxSurface: Int = Int.MAX_VALUE
) {
    fun contains(y: Int, surface: Int): Boolean =
        y in minY..maxY && (surface - y) in minDepth..maxDepth && surface in minSurface..maxSurface
}

/** One of the file's own climate values ([Climate.noises]). */
data class CompiledClimateNoise(val name: String, val noise: NoiseDef)

/**
 * A project structure's blocks as a decoration places them: [palette] block states in canonical text, and
 * [blocks] four numbers per block (x, y, z from the smallest corner, and its index in [palette]). Format never
 * reads a `structures/<id>.nbt` itself (it's Minecraft's binary format): the server reads it with the game's own
 * structure loader, the editor with its NBT reader, and each hands the result to [CompiledTerrain.withStructures].
 */
class StructureTemplate(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val blocks: IntArray,
    /**
     * The indexes in [palette] of states the structure's file gives a block entity (a chest, a barrel): where a
     * decoration's [loot][Decoration.loot] can go. Empty when the reader doesn't say.
     */
    val withEntity: Set<Int> = emptySet()
) {
    override fun equals(other: Any?): Boolean = other is StructureTemplate &&
        sizeX == other.sizeX &&
        sizeY == other.sizeY &&
        sizeZ == other.sizeZ &&
        palette == other.palette &&
        blocks.contentEquals(other.blocks) &&
        withEntity == other.withEntity

    override fun hashCode(): Int = ((sizeX * 31 + sizeY) * 31 + sizeZ) * 31 + palette.hashCode() * 31 + blocks.contentHashCode()

    override fun toString(): String = "StructureTemplate(${sizeX}x${sizeY}x$sizeZ, ${blocks.size / 4} blocks)"
}

/**
 * A [StructureTemplate] as a generator places it: its blocks, and for each quarter turn (0 to 3, clockwise seen
 * from above) the palette entry each of its states becomes (turned: `facing`, `axis`, `rotation` and the four side
 * properties), 0 for the air it doesn't place.
 */
class LinkedTemplate(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blocks: IntArray,
    val turns: List<IntArray>,
    /** For each of the template's own palette entries, whether it holds a block entity ([StructureTemplate.withEntity]). */
    val withEntity: BooleanArray = BooleanArray(0)
) {
    override fun equals(other: Any?): Boolean = other is LinkedTemplate &&
        sizeX == other.sizeX &&
        sizeY == other.sizeY &&
        sizeZ == other.sizeZ &&
        blocks.contentEquals(other.blocks) &&
        turns.size == other.turns.size &&
        turns.indices.all { turns[it].contentEquals(other.turns[it]) }

    override fun hashCode(): Int = ((sizeX * 31 + sizeY) * 31 + sizeZ) * 31 + blocks.contentHashCode()

    override fun toString(): String = "LinkedTemplate(${sizeX}x${sizeY}x$sizeZ, ${blocks.size / 4} blocks)"
}

/**
 * A `terrain/<id>.json` ready to run: every block is an index into
 * [palette] (0 is air) and everything is sorted by name, so what a generator
 * does depends on the file's content alone. Immutable, so the server's
 * generator threads share one and a reload swaps it for the chunks
 * generated afterwards ([bind] makes the generator for a world).
 *
 * The project's structures its decorations place are linked in afterwards
 * ([withStructures]), by whoever can read them: until then such a decoration
 * places nothing.
 */
data class CompiledTerrain(
    val palette: List<TerrainBlock>,
    val base: Int,
    val seaLevel: Int,
    val fluid: Int,
    val noises: List<CompiledTerrainNoise>,
    val layers: List<CompiledLayer>,
    val underwater: List<CompiledLayer>,
    val stone: Int,
    val floorBlock: Int,
    val floorThickness: Int,
    val caves: List<CompiledCave>,
    val ores: List<CompiledOre>,
    val decorations: List<CompiledDecoration>,
    val temperature: NoiseDef,
    val humidity: NoiseDef,
    val jitter: NoiseDef,
    val jitterAmplitude: Double,
    /** How far heights blend across a border between areas whose terrain differs, in blocks; 0 for none. */
    val blend: Int,
    /** At least one: a file with no biomes is all plains. */
    val areas: List<CompiledArea>,
    /** Whether the game's own structures generate. */
    val vanillaStructures: Boolean,
    /** The script the file hands stages to, or null for none. */
    val script: CompiledScript? = null,
    /** The file's 3D terrain, or null when a column is solid up to its height. */
    val density: CompiledDensity? = null,
    /** The structures linked in by [withStructures], by the name the file gives them. */
    val templates: Map<String, LinkedTemplate> = emptyMap(),
    /** The file's own climate values, sorted by name. */
    val climate: List<CompiledClimateNoise> = emptyList(),
    /** Every loot table it fills containers from (its decorations' and its script's), as the file names them, sorted. */
    val loot: List<String> = emptyList()
) {
    /** The areas that are a column's own (not volumes), as indexes into [areas]. */
    val columnAreas: List<Int> get() = areas.indices.filter { areas[it].volume == null }

    /** The volume areas, as indexes into [areas]. */
    val volumeAreas: List<Int> get() = areas.indices.filter { areas[it].volume != null }

    /** Every biome the areas use, once each, in area order: what a world's biome provider offers. */
    val biomes: List<String> get() = areas.map { it.biome }.distinct()

    /** The custom blocks it places: what the server must have a state for. */
    val customBlocks: List<String> get() = palette.filterIsInstance<TerrainBlock.Custom>().map { it.name }.distinct()

    /** The project's structures its decorations place, as the file names them, sorted: what [withStructures] is given. */
    val structures: List<String> get() = decorations.mapNotNull { it.structure }.distinct().sorted()

    /**
     * The generator of the world seeded [seed] with blocks from [minY] up to but not including [maxY]. A file with a
     * [script] runs it with [scripts] (the platform's Lua and the script's files); without them, only the file's own
     * stages run, which is never what a world gets: the server and the preview always pass them.
     */
    fun bind(seed: Long, minY: Int, maxY: Int, scripts: TerrainScripts? = null): TerrainGenerator =
        TerrainGenerator(this, seed, minY, maxY, scripts?.takeIf { script != null })

    /**
     * This generator with the blocks its script's [sources] (the script and the modules it may `require`) name in
     * full as string literals added to its palette, after every other: a game block state (`"minecraft:oak_stairs[facing=north]"`)
     * whose block [isGameBlock] knows, or a project block's id that [isProjectBlock] knows. So a script places them
     * without listing them in `script.blocks`; one whose id it puts together from pieces still has to be listed.
     */
    fun withScriptBlocks(
        sources: Collection<String>,
        isGameBlock: (String) -> Boolean,
        isProjectBlock: (String) -> Boolean
    ): CompiledTerrain {
        if (script == null) return this
        val entries = palette.toMutableList()
        for (literal in sources.flatMap(LuaStrings::literals).distinct().sorted()) {
            val block = when {
                literal.startsWith("minecraft:") ->
                    BlockState.parse(literal)?.takeIf { isGameBlock(it.id) }?.let { TerrainBlock.Vanilla(it.toString()) }
                PROJECT_BLOCK.matches(literal) && isProjectBlock(literal) -> TerrainBlock.Custom(literal)
                else -> null
            } ?: continue
            if (block !in entries) entries += block
        }
        return if (entries.size == palette.size) this else copy(palette = entries)
    }

    /**
     * This generator with the structures its decorations place: each of [structures] (by the name the file gives
     * it) that one names has its states added to the palette, once in each quarter turn. A structure left out
     * isn't placed. Air, cave air and structure voids in a template are never placed: what's there stays.
     */
    fun withStructures(structures: Map<String, StructureTemplate>): CompiledTerrain {
        val entries = palette.toMutableList()
        fun indexOf(state: String): Int {
            val block = TerrainBlock.Vanilla(state)
            val at = entries.indexOf(block)
            if (at >= 0) return at
            entries += block
            return entries.size - 1
        }
        val linked = LinkedHashMap<String, LinkedTemplate>()
        for (name in this.structures) {
            val template = structures[name] ?: continue
            val canonical = template.palette.map { BlockState.parse(it)?.toString() ?: GameIds.normalize(it) }
            val turns = (0 until 4).map { turn ->
                IntArray(canonical.size) { i ->
                    val state = canonical[i]
                    if (BlockState.parse(state)?.id in NOT_PLACED) 0 else indexOf(StateTurns.turn(state, turn))
                }
            }
            val withEntity = BooleanArray(template.palette.size) { it in template.withEntity }
            linked[name] = LinkedTemplate(template.sizeX, template.sizeY, template.sizeZ, template.blocks.copyOf(), turns, withEntity)
        }
        return copy(palette = entries.toList(), templates = linked)
    }

    private companion object {
        /** What a template holds that a decoration leaves as the world has it. */
        val NOT_PLACED = EmptyBlocks.IDS

        /** What a project block's id written in a script looks like: the project's own (a package's is listed). */
        val PROJECT_BLOCK = Regex("[a-z0-9_]+")
    }
}

/** A block state turned a number of quarter turns clockwise (seen from above), as a structure turned with it holds it. */
object StateTurns {
    private val SIDES = listOf("north", "east", "south", "west")

    fun turn(state: String, turns: Int): String {
        val quarter = ((turns % 4) + 4) % 4
        if (quarter == 0) return state
        val parsed = BlockState.parse(state) ?: return state
        val out = LinkedHashMap<String, String>()
        for ((key, value) in parsed.properties) {
            val side = SIDES.indexOf(key)
            when {
                key == "facing" && value in SIDES -> out[key] = SIDES[(SIDES.indexOf(value) + quarter) % 4]
                key == "axis" && quarter % 2 == 1 && (value == "x" || value == "z") -> out[key] = if (value == "x") "z" else "x"
                key == "rotation" && value.toIntOrNull() in 0..15 -> out[key] = ((value.toInt() + 4 * quarter) % 16).toString()
                side >= 0 -> out[SIDES[(side + quarter) % 4]] = value
                else -> out[key] = value
            }
        }
        return BlockState(parsed.id, out).toString()
    }
}
