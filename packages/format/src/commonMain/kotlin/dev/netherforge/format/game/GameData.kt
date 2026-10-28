package dev.netherforge.format.game

import dev.netherforge.format.math.Vec3
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * What validation needs to know about one Minecraft version: facts the
 * server has, so the plugin answers them from its live registries.
 *
 * `format` owns this interface and no data. The editor answers it with a
 * [GameDataBundle] from its per-user cache (exported by the dev server);
 * tests use bundles written by hand. Client-only facts (model shapes, font
 * advances) are deliberately not here: the plugin never needs one, and the
 * editor reads them from the bundle or its own import directly.
 *
 * Most facts are "is this id in that registry" ([registry], [has]) or "what's
 * in that tag" ([tag]), asked by the game's own registry names, so a new kind
 * of id costs nothing here. The rest are facts that aren't plain id sets:
 * block properties, collision shapes, a particle's options.
 *
 * Every lookup is by namespaced id (`minecraft:stone`). Callers normalise ids
 * with [GameIds.normalize] first.
 */
interface GameData {
    val minecraftVersion: String

    /**
     * Every id in the registry [key], or null when this game has no such
     * registry (or the data doesn't say): then say nothing about its ids, as
     * with no game data at all.
     */
    fun registry(key: RegistryKey): Set<String>?

    /**
     * What the tag [id] of the registry [key] holds (`minecraft:logs` of
     * `minecraft:item`), with tags inside it resolved: null when there's no
     * such tag, or no such registry (ask [registry] to tell those apart).
     */
    fun tag(key: RegistryKey, id: String): Set<String>?

    /** The block with this id, or null if the game has no such block. */
    fun block(id: String): BlockInfo?

    /**
     * How much damage an item of this kind takes before it breaks (its
     * default `max_damage`): 0 for one without durability, null when unknown.
     * The live server answers it; cached game data doesn't, and checks that
     * need it are then skipped.
     */
    fun itemDurability(id: String): Int? = null

    /**
     * The collision boxes of one block state, in block units relative to the
     * block's corner. Null when unknown (data not available), empty when the
     * block has no collision (a torch).
     */
    fun collisionBoxes(state: BlockState): List<Box>?

    /** The resource pack format as `[major, minor]` (from the game's `version.json`), or null when unknown. */
    val resourcePackFormat: List<Int>? get() = null

    /**
     * The data pack format as `[major, minor]` (from the game's `version.json`), or null when unknown: what a
     * project's datapacks must be written for ([dev.netherforge.format.datapack.PackMeta]'s range).
     */
    val dataPackFormat: List<Int>? get() = null

    /** The particle with this id, or null if the game has no such particle. */
    fun particle(id: String): ParticleInfo?

    /**
     * The colours a map pixel can be, by the byte it holds (`base * 4 + shade`, 256 of them) as `0xAARRGGBB`: alpha 0
     * for the game's "no colour" (indices 0 to 3) and for any index it has no colour for. Null when unknown. What
     * screens match colours against ([dev.netherforge.format.screen.MapPalette]).
     */
    val mapColors: List<Int>? get() = null
}

/**
 * Whether the registry [key] has [id], or null when that isn't known (see
 * [GameData.registry]): checks say nothing then, so they ask `== false`.
 */
fun GameData.has(key: RegistryKey, id: String): Boolean? = registry(key)?.contains(id)

/**
 * One of the game's registries, by its own name (`minecraft:item`,
 * `minecraft:worldgen/biome`): what [GameData.registry] and [GameData.tag]
 * are asked by. The constants are the registries NetherForge's checks ask
 * about; any other name works the same way.
 */
@JvmInline
value class RegistryKey(val id: String) {
    override fun toString(): String = id

    companion object {
        val ATTRIBUTE = RegistryKey("minecraft:attribute")

        /** Biomes, and by their tags (`minecraft:is_forest`) where a structure may generate. */
        val BIOME = RegistryKey("minecraft:worldgen/biome")
        val BLOCK = RegistryKey("minecraft:block")

        /** An item stack's data components: `minecraft:unbreakable`, `minecraft:painting/variant`. */
        val DATA_COMPONENT_TYPE = RegistryKey("minecraft:data_component_type")
        val DIMENSION_TYPE = RegistryKey("minecraft:dimension_type")
        val ENCHANTMENT = RegistryKey("minecraft:enchantment")
        val ENTITY_TYPE = RegistryKey("minecraft:entity_type")
        val ITEM = RegistryKey("minecraft:item")

        /** Loot tables: the game's and its datapacks' (a reloadable registry, which the export carries too). */
        val LOOT_TABLE = RegistryKey("minecraft:loot_table")
        val PARTICLE_TYPE = RegistryKey("minecraft:particle_type")

        /** The game's features as a biome decorates with them (`minecraft:trees_plains`): a feature with where and how often. */
        val PLACED_FEATURE = RegistryKey("minecraft:worldgen/placed_feature")
        val SOUND_EVENT = RegistryKey("minecraft:sound_event")

        /** What sets off an advancement's criterion: `minecraft:inventory_changed`, `minecraft:tick`. */
        val TRIGGER_TYPE = RegistryKey("minecraft:trigger_type")
    }
}

/** What a particle type is: which options a spawn of it carries. */
@Serializable
data class ParticleInfo(val data: ParticleDataKind)

/**
 * The options a particle type takes, read off the server's particle registry
 * (Paper's `Particle.dataType`). Which ids have which kind is a game fact:
 * nothing in NetherForge lists them.
 */
@Serializable
enum class ParticleDataKind {
    /** No options: flame, end rod, flash. */
    @SerialName("none")
    NONE,

    /** A colour and a size. */
    @SerialName("dust")
    DUST,

    /** A colour, the colour it fades to, and a size. */
    @SerialName("dust_transition")
    DUST_TRANSITION,

    /** A colour: entity effects, tinted leaves. */
    @SerialName("color")
    COLOR,

    /** A block state: block, falling dust, block markers. */
    @SerialName("block")
    BLOCK,

    /** An item stack. */
    @SerialName("item")
    ITEM,

    /** Anything else (vibrations, trails, sculk charges): not playable in effects. */
    @SerialName("other")
    OTHER
}

@Serializable
data class BlockInfo(
    /** Each property's allowed values, in the game's order. */
    val properties: Map<String, List<String>> = emptyMap(),
    /** The property values the game places this block with. */
    val defaults: Map<String, String> = emptyMap()
)

/** An axis-aligned box: [min] and [max] corners, in blocks. */
@Serializable
data class Box(val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min
    val center: Vec3 get() = (min + max) * 0.5
}

/**
 * A [GameData] backed by plain data: the editor's cache file, or a test fixture.
 *
 * [registries] and [tags] hold every registry and tag the server has, as the
 * dev server exported them. A registry missing from them is one the data
 * doesn't know ([registry] answers null), which is how a fixture leaves out
 * what a test doesn't need.
 *
 * Collision and model boxes are keyed by the block state's canonical string
 * (`minecraft:oak_stairs[facing=east,half=bottom,...]`, properties sorted); a
 * state with no entry falls back to the block's bare id, so a fixture can say
 * "every state of stone is a full cube" once.
 *
 * [schema] says which shape of this class wrote it. A change to the shape or
 * to what the export puts in it raises [SCHEMA], and the editor exports its
 * cache again rather than read one of another shape.
 */
@Serializable
data class GameDataBundle(
    val minecraft: String,
    /** Each registry's name → its ids, sorted. */
    val registries: Map<String, List<String>> = emptyMap(),
    /** Each registry's name → its tags (without `#`) → what each holds, nested tags resolved, sorted. */
    val tags: Map<String, Map<String, List<String>>> = emptyMap(),
    val blocks: Map<String, BlockInfo> = emptyMap(),
    val collision: Map<String, List<Box>> = emptyMap(),
    val models: Map<String, List<Box>> = emptyMap(),
    val glyphAdvances: Map<String, Int> = emptyMap(),
    val packFormat: List<Int>? = null,
    /** The data pack format, `[major, minor]`. */
    override val dataPackFormat: List<Int>? = null,
    /** Particle id → its data kind. */
    val particles: Map<String, ParticleDataKind> = emptyMap(),
    /** Each map colour by its index, `0xAARRGGBB` (see [GameData.mapColors]). */
    override val mapColors: List<Int>? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val schema: Int = SCHEMA
) : GameData {
    override val minecraftVersion: String get() = minecraft

    /** Each registry's ids as a set, made when first asked for. */
    private val registrySets = HashMap<String, Set<String>>()

    override fun registry(key: RegistryKey): Set<String>? {
        registrySets[key.id]?.let { return it }
        val ids = registries[key.id] ?: return null
        return ids.toHashSet().also { registrySets[key.id] = it }
    }

    override fun tag(key: RegistryKey, id: String): Set<String>? = tags[key.id]?.get(id)?.toSet()

    override fun block(id: String): BlockInfo? = blocks[id]

    override fun collisionBoxes(state: BlockState): List<Box>? = collision[state.toString()] ?: collision[state.id]

    /** The boxes a block state is drawn from (client-only; the editor fills it in). */
    fun modelBoxes(state: BlockState): List<Box>? = models[state.toString()] ?: models[state.id]

    override val resourcePackFormat: List<Int>? get() = packFormat

    override fun particle(id: String): ParticleInfo? = particles[id]?.let { ParticleInfo(it) }

    companion object {
        /** The shape the export writes today; see [schema]. */
        const val SCHEMA = 3
    }
}
