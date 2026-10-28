package dev.netherforge.format.terrain

import dev.netherforge.format.noise.NoiseDef
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `terrain/<id>.json`: how the terrain of a world the project makes is
 * generated. Nothing in it is a script: the same data is run by the server
 * ([TerrainGenerator]) and by the editor's live preview, from the same
 * code, so the preview is what the world will be (for a seed).
 *
 * A column of the world is shaped in order: the **terrain** (a height from
 * noise) is filled with **stone**, topped by **layers** from the surface
 * down and, below sea level, the sea; **caves** are carved out of it, the
 * **floor** (bedrock) is laid at the bottom, **ores** are scattered through
 * what's left, and **decorations** (single blocks and the project's
 * structures) are scattered on and in it. **Biomes** are chosen from two
 * climate noises (their borders jittered by a third) and give each place a
 * vanilla biome (its sky, grass and mobs) and, if they like, their own layers
 * and terrain, blended into their neighbours' near a border.
 *
 * Everything depends on the world's seed and its blocks' positions alone, never
 * on the order chunks are generated in, so a chunk comes out the same however
 * the world is explored.
 */
@Serializable
data class TerrainFile(
    @SerialName("\$schema") val schema: String? = null,
    val terrain: Terrain = Terrain(),
    /** What the ground is made of, from the surface down: each layer [Layer.thickness] blocks thick, then [stone]. Default: stone all the way up. */
    val layers: List<Layer> = emptyList(),
    /** [layers] for columns whose surface is below sea level (the sea floor). Default: the same as [layers]. */
    val underwater: List<Layer> = emptyList(),
    /** The block everything below the layers is. Default `minecraft:stone`. */
    val stone: Stone? = null,
    /** The unbreakable bottom of the world. Default none. */
    val floor: Floor? = null,
    /** Caves carved out of the ground, by name. */
    val caves: Map<String, Cave> = emptyMap(),
    /** Ores scattered through the ground, by name. */
    val ores: Map<String, Ore> = emptyMap(),
    /** Blocks and structures scattered on, in and under the ground, by name. */
    val decorations: Map<String, Decoration> = emptyMap(),
    /** The noise biomes are chosen from. */
    val climate: Climate = Climate(),
    /** Biome areas, by name. Default: all of the world is `minecraft:plains`. */
    val biomes: Map<String, BiomeArea> = emptyMap(),
    val structures: StructureRules = StructureRules(),
    /**
     * Hands stages to the Lua script beside the file, `terrain/<id>.lua`: a column's height, with a [Terrain.density]
     * the density at a point, and blocks filled into a chunk after the file's terrain and after its decorations.
     * Default none.
     */
    val script: TerrainScript? = null
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/terrain.schema.json"

        const val DEFAULT_STONE = "minecraft:stone"
        const val DEFAULT_FLUID = "minecraft:water"
        const val DEFAULT_BIOME = "minecraft:plains"
        const val AIR = "minecraft:air"

        /** Most layers in a list, and most of each of the named things in a file. */
        const val MAX_LAYERS = 32
        const val MAX_NAMED = 64
        const val MAX_NOISES = 16
        const val MAX_THICKNESS = 256
        const val MAX_AMPLITUDE = 4096.0
        const val MAX_ORE_SIZE = 64
        const val MAX_ORE_VEINS = 256
        const val MAX_DECORATION_COUNT = 256
        const val MAX_BLEND = 64
        const val MAX_SCALE = 16.0
        const val MAX_ON = 32
    }
}

/**
 * The heights a world's blocks go from ([minY]) up to but not including [maxY]. A generator is bound to a world's
 * own ([CompiledTerrain.bind]); a file is checked against [LIMITS], what any world can be, since one file can
 * shape worlds of different heights.
 */
data class WorldHeight(val minY: Int, val maxY: Int) {
    companion object {
        /** The game's own limit on how tall a world can be: every height a file names is inside it. */
        val LIMITS = WorldHeight(-2032, 2032)

        /** A normal overworld's: what the editor's preview draws until a world says otherwise. */
        val OVERWORLD = WorldHeight(-64, 320)
    }
}

/**
 * Where a block a file names comes from: [block], a vanilla block state, or
 * [customBlock], one of the project's own [blocks](block.md) (a plain cube:
 * one drawn by a centity can't be placed by a generator). A file says one.
 */
interface BlockChoice {
    val block: String?
    val customBlock: ResourceRef?
}

/** The shape of the ground: a height (in blocks) of [base] plus every noise's contribution, with the sea at [seaLevel]. */
@Serializable
data class Terrain(
    /** The height where every noise is 0. Default 64. */
    val base: Int? = null,
    /** Columns whose surface is lower than this are filled with [fluid] up to it. Default 62. */
    val seaLevel: Int? = null,
    /** What the sea is made of. Default `minecraft:water`. */
    val fluid: String? = null,
    /** Noises added to [base], by name: each is the noise's value (-1 to 1) times its amplitude, in blocks. */
    val noises: Map<String, TerrainNoise> = emptyMap(),
    /**
     * How far, in blocks, the heights of biome areas whose terrain differs are blended across the border
     * between them, 0 (a cliff) to [TerrainFile.MAX_BLEND]. Default 16.
     */
    val blend: Int? = null,
    /**
     * Makes the ground 3D: solid wherever the [Density] around the height above is positive, so 3D noises can lean
     * it into overhangs, arches and cliffs, and islands can float. Default none: a column is solid up to its height.
     */
    val density: Density? = null
) {
    val baseOrDefault: Int get() = base ?: DEFAULT_BASE
    val seaLevelOrDefault: Int get() = seaLevel ?: DEFAULT_SEA_LEVEL
    val blendOrDefault: Int get() = blend ?: DEFAULT_BLEND

    companion object {
        const val DEFAULT_BASE = 64
        const val DEFAULT_SEA_LEVEL = 62
        const val DEFAULT_BLEND = 16
    }
}

/** One noise of the [Terrain]: [noise] spread over [amplitude] blocks of height. */
@Serializable
data class TerrainNoise(
    val noise: NoiseDef = NoiseDef(),
    /** The most it raises or lowers the ground: the noise's -1 to 1 becomes minus to plus this many blocks. Default 16. */
    val amplitude: Double? = null
) {
    val amplitudeOrDefault: Double get() = amplitude ?: DEFAULT_AMPLITUDE

    companion object {
        const val DEFAULT_AMPLITUDE = 16.0
    }
}

/**
 * A biome area's own terrain: its [base] height instead of the file's, the file's noises times [scale], and
 * [noises] of its own on top. Near a border its heights are blended with the neighbouring area's
 * ([Terrain.blend]).
 */
@Serializable
data class AreaTerrain(
    /** The height where every noise is 0 in this area. Default: the file's [Terrain.base]. */
    val base: Int? = null,
    /** What the file's own noises are multiplied by here, 0 (flat) to [TerrainFile.MAX_SCALE]. Default 1. */
    val scale: Double? = null,
    /** More noises added in this area only, by name. */
    val noises: Map<String, TerrainNoise> = emptyMap(),
    /** The area's own 3D noises, in a file with a [Terrain.density]. Default: the file's. */
    val density: AreaDensity? = null
) {
    val scaleOrDefault: Double get() = scale ?: 1.0
}

/**
 * The 3D shape of the ground ([Terrain.density]). A block is solid where its density is above 0: the height the
 * terrain's 2D noises make, less how far the block is above it, plus each of [noises] (a 3D noise times its
 * amplitude, in blocks). So a noise of amplitude 12 moves the ground's surface up to 12 blocks up or down, and
 * differently at each height, which is what leans it out over itself; deeper than the amplitudes reach, the ground is
 * solid, and above them it's air. [islands] add land floating in a band of their own.
 *
 * The noises are read on a grid of points 4 blocks apart across and 8 up, as the game reads its own, and blended
 * between them, so 3D terrain costs a chunk little more than heights do.
 */
@Serializable
data class Density(
    /** 3D noises moving the ground's surface, by name. */
    val noises: Map<String, DensityNoise> = emptyMap(),
    /** Land floating above the ground. Default none. */
    val islands: Islands? = null
)

/** One 3D noise of a [Density]: [noise] times [amplitude] blocks, its pattern squashed up and down by [squash]. */
@Serializable
data class DensityNoise(
    val noise: NoiseDef = NoiseDef(),
    /** How far it moves the ground's surface, in blocks, either way. Default 16. */
    val amplitude: Double? = null,
    /**
     * How much flatter its pattern is up and down than across: 2 makes ledges and shelves half as tall, 0.5 cliffs and
     * pillars twice as tall. More than 0, at most [TerrainFile.MAX_SCALE]. Default 1.
     */
    val squash: Double? = null
) {
    val amplitudeOrDefault: Double get() = amplitude ?: TerrainNoise.DEFAULT_AMPLITUDE
    val squashOrDefault: Double get() = squash ?: 1.0
}

/** A biome area's own 3D noises: the file's ([Density.noises]) times [scale], and [noises] of its own on top. */
@Serializable
data class AreaDensity(
    /** What the file's 3D noises are multiplied by here, 0 (none) to [TerrainFile.MAX_SCALE]. Default 1. */
    val scale: Double? = null,
    /** More 3D noises in this area only, by name. */
    val noises: Map<String, DensityNoise> = emptyMap()
) {
    val scaleOrDefault: Double get() = scale ?: 1.0
}

/**
 * Land floating in a band [thickness] blocks tall around height [y]: islands where the 3D [noise] is above
 * [threshold], thickest in the band's middle and thinning to nothing at its top and bottom, in the biome areas
 * [biomes] names (fading out across their borders as heights blend).
 */
@Serializable
data class Islands(
    /** The middle of the band. Default 160. */
    val y: Int? = null,
    /** How tall the band is, so the most an island can be, 2 to [TerrainFile.MAX_THICKNESS]. Default 32. */
    val thickness: Int? = null,
    /** The islands' pattern. Default: `openSimplex2` at 0.012, 2 octaves. */
    val noise: NoiseDef? = null,
    /** Where islands start, -1 up to but not including 1: higher is fewer, smaller islands. Default 0.4. */
    val threshold: Double? = null,
    /** The biome areas (by name) they float over. Default: all of them. */
    val biomes: List<String> = emptyList()
) {
    val yOrDefault: Int get() = y ?: DEFAULT_Y
    val thicknessOrDefault: Int get() = thickness ?: DEFAULT_THICKNESS
    val thresholdOrDefault: Double get() = threshold ?: DEFAULT_THRESHOLD

    companion object {
        const val DEFAULT_Y = 160
        const val DEFAULT_THICKNESS = 32
        const val MIN_THICKNESS = 2
        const val DEFAULT_THRESHOLD = 0.4
        val DEFAULT_NOISE = NoiseDef(frequency = 0.012, octaves = 2)
    }
}

/** [thickness] blocks of [block] or [customBlock]. */
@Serializable
data class Layer(
    /** A block state: `minecraft:grass_block`, or `minecraft:oak_log[axis=y]`. */
    override val block: String? = null,
    @Ref(RefKind.BLOCK) override val customBlock: ResourceRef? = null,
    /** How many blocks thick, 1 or more. Default 1. */
    val thickness: Int? = null
) : BlockChoice {
    val thicknessOrDefault: Int get() = thickness ?: 1
}

/** What everything below the layers is: a vanilla [block] or one of the project's ([customBlock]). */
@Serializable
data class Stone(override val block: String? = null, @Ref(RefKind.BLOCK) override val customBlock: ResourceRef? = null) : BlockChoice

/** The bottom of the world: [block] (or [customBlock]) in the lowest [thickness] layers, thinning out upwards as the game's bedrock does. */
@Serializable
data class Floor(
    /** Default `minecraft:bedrock` (when there's no [customBlock] either). */
    override val block: String? = null,
    @Ref(RefKind.BLOCK) override val customBlock: ResourceRef? = null,
    /** How many layers it spreads over, 1 or more: the lowest is solid, the one above it mostly. Default 5. */
    val thickness: Int? = null
) : BlockChoice {
    val thicknessOrDefault: Int get() = thickness ?: DEFAULT_THICKNESS

    companion object {
        const val DEFAULT_BLOCK = "minecraft:bedrock"
        const val DEFAULT_THICKNESS = 5
    }
}

/** How a [Cave] is shaped. */
@Serializable
enum class CaveType {
    /** Big rooms with pillars, where the noise is above the threshold. */
    @SerialName("cheese")
    CHEESE,

    /** Winding tunnels, where two noises are both near zero. */
    @SerialName("spaghetti")
    SPAGHETTI
}

/**
 * A kind of cave, carved from [minY] to [maxY] and never within [depth] blocks of the surface. With a [fade], it
 * narrows to nothing over that many blocks at each end instead of stopping in a flat cut, so bands blend.
 */
@Serializable
data class Cave(
    val type: CaveType? = null,
    /** The pattern of the cave. Default: `openSimplex2` at 0.03, with 2 octaves. */
    val noise: NoiseDef? = null,
    /**
     * `cheese`: carved where the noise is above this (-1 to 1: higher is smaller rooms), default 0.55.
     * `spaghetti`: carved where both noises are within this of zero (0 to 1: higher is wider tunnels), default 0.08.
     */
    val threshold: Double? = null,
    /** The lowest it goes. Default -56. */
    val minY: Int? = null,
    /** The highest it goes. Default 56. */
    val maxY: Int? = null,
    /** How far below the surface it must stay, 0 or more, so caves don't open the ground everywhere. Default 4. */
    val depth: Int? = null,
    /**
     * How many blocks above [minY] and below [maxY] it narrows over, 0 (a flat cut) to [TerrainFile.MAX_THICKNESS]:
     * rooms shrink and tunnels thin towards each end until none is carved there. Default 0.
     */
    val fade: Int? = null,
    /** The biome areas (by name) whose columns it's carved in. Default: all of them. */
    val biomes: List<String> = emptyList()
) {
    val typeOrDefault: CaveType get() = type ?: CaveType.CHEESE
    val fadeOrDefault: Int get() = fade ?: 0
    val minYOrDefault: Int get() = minY ?: DEFAULT_MIN_Y
    val maxYOrDefault: Int get() = maxY ?: DEFAULT_MAX_Y
    val depthOrDefault: Int get() = depth ?: DEFAULT_DEPTH

    fun thresholdOrDefault(): Double = threshold ?: if (typeOrDefault == CaveType.CHEESE) DEFAULT_CHEESE else DEFAULT_SPAGHETTI

    companion object {
        const val DEFAULT_MIN_Y = -56
        const val DEFAULT_MAX_Y = 56
        const val DEFAULT_DEPTH = 4
        const val DEFAULT_CHEESE = 0.55
        const val DEFAULT_SPAGHETTI = 0.08

        /** What a cave's [noise] is without one. */
        val DEFAULT_NOISE = NoiseDef(frequency = 0.03, octaves = 2)
    }
}

/** How an [Ore]'s veins are spread between its lowest and highest height. */
@Serializable
enum class OreDistribution {
    /** Equally likely at any height. */
    @SerialName("uniform")
    UNIFORM,

    /** Likeliest in the middle of the range, rarer towards its ends. */
    @SerialName("triangle")
    TRIANGLE
}

/**
 * Veins of one block ([block]: a vanilla block state; [customBlock]: one of
 * the project's own, which must be a cube: one drawn by a centity can't be an
 * ore) scattered through the ground, replacing [replace] and [replaceCustomBlocks] only.
 */
@Serializable
data class Ore(
    override val block: String? = null,
    @Ref(RefKind.BLOCK) override val customBlock: ResourceRef? = null,
    /** The blocks it may replace: block ids. Default: the [TerrainFile.stone] (when [replaceCustomBlocks] is empty too). */
    val replace: List<String> = emptyList(),
    /** The project's blocks it may replace, by id, besides [replace]: `ebonstone` in a layer, the stone or an area's ground. */
    @Ref(RefKind.BLOCK) val replaceCustomBlocks: List<ResourceRef> = emptyList(),
    /** Most blocks in one vein, 1 to [TerrainFile.MAX_ORE_SIZE]. Default 8. */
    val size: Int? = null,
    /** Veins attempted in each chunk, 0 to [TerrainFile.MAX_ORE_VEINS]. Default 8. */
    val veins: Int? = null,
    /** The lowest a vein starts. Default -64. */
    val minY: Int? = null,
    /** The highest a vein starts. Default 64. */
    val maxY: Int? = null,
    val distribution: OreDistribution? = null,
    /** The biome areas (by name) a vein may start in. Default: all of them. */
    val biomes: List<String> = emptyList()
) : BlockChoice {
    val sizeOrDefault: Int get() = size ?: DEFAULT_SIZE
    val veinsOrDefault: Int get() = veins ?: DEFAULT_VEINS
    val minYOrDefault: Int get() = minY ?: DEFAULT_MIN_Y
    val maxYOrDefault: Int get() = maxY ?: DEFAULT_MAX_Y
    val distributionOrDefault: OreDistribution get() = distribution ?: OreDistribution.UNIFORM

    companion object {
        const val DEFAULT_SIZE = 8
        const val DEFAULT_VEINS = 8
        const val DEFAULT_MIN_Y = -64
        const val DEFAULT_MAX_Y = 64
    }
}

/** Where a [Decoration] goes in a column. */
@Serializable
enum class DecorationPlacement {
    /** On the top block of dry ground, in the air above it. */
    @SerialName("surface")
    SURFACE,

    /** On the sea floor, in the sea. */
    @SerialName("underwater")
    UNDERWATER,

    /** In the ground, replacing a block of it, at a height between the decoration's lowest and highest. */
    @SerialName("underground")
    UNDERGROUND,

    /** On the floor of a cave: the first floor below a height between the lowest and highest. */
    @SerialName("caveFloor")
    CAVE_FLOOR,

    /** Hanging from the ceiling of a cave: the first ceiling above a height between the lowest and highest. */
    @SerialName("caveCeiling")
    CAVE_CEILING
}

/**
 * Something scattered through the world at a density: one block ([block], a vanilla block state, or
 * [customBlock], a plain-cube block of the project's) or one of the project's [structure]s (`structures/<id>.nbt`,
 * a tree or a rock). Each chunk tries [count] places, each kept at [chance] (and, with a [noise], only where the
 * noise is above [threshold]), in the [biomes] it names, between [minY] and [maxY], on a block [on] lists.
 */
@Serializable
data class Decoration(
    override val block: String? = null,
    @Ref(RefKind.BLOCK) override val customBlock: ResourceRef? = null,
    /** One of the project's structures: its blocks (not its air) are placed with its lowest layer at the place. */
    @Ref(RefKind.STRUCTURE) val structure: ResourceRef? = null,
    /** Default `surface`. A structure goes on the `surface`, `underwater` or `underground`. */
    val placement: DecorationPlacement? = null,
    /** Places tried in each chunk, 0 to [TerrainFile.MAX_DECORATION_COUNT]. Default 1. */
    val count: Int? = null,
    /** How likely each place tried is kept, 0 to 1. Default 1. */
    val chance: Double? = null,
    /** A pattern the [chance] follows: none where the noise is at most [threshold], rising to [chance] where it's 1. Default none. */
    val noise: NoiseDef? = null,
    /** With a [noise]: where it starts, -1 up to but not including 1. Default 0. */
    val threshold: Double? = null,
    /** The biome areas (by name) it's placed in. Default: all of them. */
    val biomes: List<String> = emptyList(),
    /** The lowest it's placed. Default: the bottom of the world. */
    val minY: Int? = null,
    /** The highest it's placed. Default: the top of the world. */
    val maxY: Int? = null,
    /**
     * Block ids it may be placed on (`surface`, `underwater`, `caveFloor`), hang from (`caveCeiling`) or replace
     * (`underground`). Default: any block that isn't air or the sea (`underground`: the file's stone), when
     * [onCustomBlocks] is empty too.
     */
    val on: List<String> = emptyList(),
    /** The project's blocks, by id, it may be placed on, hang from or replace, besides [on]: `ebonstone`. */
    @Ref(RefKind.BLOCK) val onCustomBlocks: List<ResourceRef> = emptyList(),
    /** For a [structure]: whether each one is turned a random quarter turn. Default true. */
    val rotate: Boolean? = null,
    /**
     * One of the project's loot tables: every chest, barrel and other container the decoration places is filled from
     * it, rolled the first time it's opened (or broken, or a hopper takes from it). Default none.
     */
    @Ref(RefKind.LOOT_TABLE) val loot: ResourceRef? = null
) : BlockChoice {
    val placementOrDefault: DecorationPlacement get() = placement ?: DecorationPlacement.SURFACE
    val countOrDefault: Int get() = count ?: 1
    val chanceOrDefault: Double get() = chance ?: 1.0
    val thresholdOrDefault: Double get() = threshold ?: 0.0
    val rotateOrDefault: Boolean get() = rotate ?: true
}

/** The two noises climate is read from, each between -1 and 1. A pattern of its own is made for each from the world's seed. */
@Serializable
data class Climate(
    /** Default: `openSimplex2` at 0.002 (areas a few hundred blocks across), 2 octaves. */
    val temperature: NoiseDef? = null,
    /** Default: as [temperature], with a pattern of its own. */
    val humidity: NoiseDef? = null,
    /**
     * More climate values, by name (`continentalness`, `evil`): each a noise of its own from the world's seed, read
     * where [temperature] and [humidity] are, which areas pick ranges of in their [BiomeArea.climate].
     */
    val noises: Map<String, NoiseDef> = emptyMap(),
    /** How the borders between biome areas wander, so they aren't the climate's smooth curves. */
    val jitter: Jitter? = null
) {
    companion object {
        val DEFAULT_NOISE = NoiseDef(frequency = 0.002, octaves = 2)

        /** Most climate values of a file's own ([noises]). */
        const val MAX_NOISES = 8
    }
}

/**
 * The climate is read up to [amplitude] blocks away from a column, in a direction two noises of [noise]'s pattern
 * say, so the border between two areas wanders instead of following the climate's smooth curves.
 */
@Serializable
data class Jitter(
    /** Default: `openSimplex2` at 0.02, 2 octaves. */
    val noise: NoiseDef? = null,
    /** How far a border moves either way, in blocks, 0 (not at all) to [TerrainFile.MAX_AMPLITUDE]. Default 16. */
    val amplitude: Double? = null
) {
    val amplitudeOrDefault: Double get() = amplitude ?: DEFAULT_AMPLITUDE

    companion object {
        const val DEFAULT_AMPLITUDE = 16.0
        val DEFAULT_NOISE = NoiseDef(frequency = 0.02, octaves = 2)
    }
}

/** A range of a climate value, -1 to 1. */
@Serializable
data class ClimateRange(val min: Double? = null, val max: Double? = null) {
    val minOrDefault: Double get() = min ?: -1.0
    val maxOrDefault: Double get() = max ?: 1.0
}

/** A range of heights, in blocks, both ends included: each end open when it's left out. */
@Serializable
data class BlockRange(val min: Int? = null, val max: Int? = null)

/**
 * A kind of place: the vanilla [biome] it is, and which temperature and
 * humidity (and the file's other climate values) it's found at. Each column is
 * the area whose ranges it fits best (the one it's nearest the middle of), so
 * areas needn't tile the climate exactly. [layers] and [underwater] replace the
 * file's for the columns in it, and [terrain] shapes its ground.
 *
 * An area with a [y], [depth] or [surface] range is a **volume**: not a column's area but a biome inside the
 * columns' (a cave biome under a forest, the sky over the mountains), at every place its ranges hold exactly. It
 * gives those places its biome (their sky, fog, music and mobs) and nothing else, so it has no layers or terrain.
 */
@Serializable
data class BiomeArea(
    /** One of the game's biomes, `minecraft:desert`, or one of the project's: `ruby_grove`, `acme:grove` for a package's. */
    @Ref(RefKind.BIOME) val biome: String,
    /** Default: any temperature. */
    val temperature: ClimateRange? = null,
    /** Default: any humidity. */
    val humidity: ClimateRange? = null,
    /** Ranges of the file's other climate values ([Climate.noises]), by name. Default: any of each. */
    val climate: Map<String, ClimateRange> = emptyMap(),
    /** A volume between these heights. */
    val y: BlockRange? = null,
    /** A volume this many blocks below the column's surface (0 is the top block, negative above it). */
    val depth: BlockRange? = null,
    /** A volume in columns whose surface is between these heights (an ocean's: below the sea). */
    val surface: BlockRange? = null,
    /** Default: the file's [TerrainFile.layers]. */
    val layers: List<Layer>? = null,
    /** Default: the file's [TerrainFile.underwater]. */
    val underwater: List<Layer>? = null,
    /** Default: the file's terrain. */
    val terrain: AreaTerrain? = null
) {
    /** Whether it's a volume inside the columns' areas rather than a column's own. */
    val isVolume: Boolean get() = y != null || depth != null || surface != null
}

/**
 * The file's Lua stages: the script `terrain/<id>.lua` returns a table of stage functions (`height`, `terrain`,
 * `decorate`), which run in a Lua state of their own on each of the server's chunk threads (and in the editor's
 * preview), with no `nf`. The script is the file's [Layout.SingleFile.companion][dev.netherforge.format.project.Layout],
 * so it's named by the file's id and goes with it when it's renamed.
 *
 * What the script may use is declared here, so it's checked with the file and known before any chunk is made:
 * the [noises] it samples (format's noise, seeded from the world's like the file's own) and the blocks it places
 * besides the file's ([blocks], [customBlocks]).
 */
@Serializable
data class TerrainScript(
    /** Lua instructions each call into the script may use (its body, one column's height, one chunk's stage). Default 1,000,000. */
    val budget: Int? = null,
    /** Noises the script asks for by name (`terrain.noise("ridges")`), each with a pattern of its own from the world's seed. */
    val noises: Map<String, NoiseDef> = emptyMap(),
    /** Block states of the game's the script places besides those the file names: `minecraft:cobblestone`. */
    val blocks: List<String> = emptyList(),
    /** The project's blocks the script places besides those the file names, by id: plain cubes, as anywhere in the file. */
    @Ref(RefKind.BLOCK) val customBlocks: List<ResourceRef> = emptyList(),
    /** The project's loot tables the script fills containers from (`chunk:set_loot`), by id. */
    @Ref(RefKind.LOOT_TABLE) val loot: List<ResourceRef> = emptyList()
) {
    val budgetOrDefault: Int get() = budget ?: DEFAULT_BUDGET

    companion object {
        const val DEFAULT_BUDGET = 1_000_000
        const val MIN_BUDGET = 1_000
        const val MAX_BUDGET = 100_000_000
        const val MAX_BLOCKS = 1024
        const val MAX_LOOT = 64

        /** The extension of the script beside the file. */
        const val EXTENSION = ".lua"
    }
}

/**
 * What the game's own structures (villages, ruins, and the project's [structures that generate](worlds.md#structures-that-generate)) do in the world.
 */
@Serializable
data class StructureRules(
    /**
     * Whether the game's structures generate in it, as the game decides: in the biomes each is allowed in
     * (so the [biomes] decide), on the ground this file made. The game places a structure's pieces while it
     * decorates a chunk, so this also lets it decorate each biome as it does elsewhere: its trees, flowers,
     * ores and lakes. Default false. The server's own `structures` option of a world still turns structures off.
     */
    val vanilla: Boolean? = null
) {
    val vanillaOrDefault: Boolean get() = vanilla ?: false
}
