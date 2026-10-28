package dev.netherforge.format.block

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `blocks/<id>/block.json`: a block of the project's own, which players
 * place, mine and click, and whose script hears them.
 *
 * It's drawn by the resource pack ([model]: an entry of a pack's `blocks`)
 * and held in the world as an unused state of one of the game's blocks, its
 * [carrier] ([BlockCarriers]): the server keeps that block, the pack draws it
 * as this one, and the carrier decides how it behaves physically (a solid
 * opaque cube, no collision, a see-through cube). A block can also name a
 * [centity] whose displays are drawn over it, keeping the carrier's
 * collision.
 *
 * Its one [script] runs once for the block (not per placed block), and hears
 * what players do with any of them.
 */
@Serializable
data class BlockFile(
    @SerialName("\$schema") val schema: String? = null,
    /**
     * `<pack>/<key>`: its look, an entry of one of the project's resource
     * packs' `blocks`. A block with a [centity] shows no cube: the model is
     * only the texture its breaking particles take. Without one, a block is
     * drawn as the plain note block it is held as, until it has a look.
     */
    @Ref(RefKind.BLOCK_MODEL) val model: ResourceRef? = null,
    /** What it's held as, which decides how it behaves physically ([BlockCarrier]). Default [BlockCarrier.NOTE_BLOCK]. */
    val carrier: BlockCarrier? = null,
    /**
     * How long it takes to mine, in the game's units (stone is 1.5, dirt 0.5,
     * obsidian 50): the time is this, divided by how well the tool mines it
     * ([tool]). 0 breaks at once and -1 can't be broken by hand. Default 1.5.
     */
    val hardness: Double? = null,
    /** The tool it's mined with: faster with it, and the only one that gets its drops when [requiresTool]. Default none. */
    val tool: BlockTool? = null,
    /** Whether it drops nothing unless mined with the right [tool]. Default false. */
    val requiresTool: Boolean? = null,
    /** `<id>`: the loot table rolled when it's broken, for its drops. Nothing drops when it's left out. */
    @Ref(RefKind.LOOT_TABLE) val drops: ResourceRef? = null,
    /**
     * The game's kind of block it sounds like when it's placed and broken (`stone`, `wood`, `gravel`, `grass`,
     * `sand`, `metal`, `glass`, `wool`, …): the game's `block.<sound>.place` and `block.<sound>.break`. Default
     * from its [tool] ([soundOrDefault]); [sounds] of its own play instead.
     */
    val sound: String? = null,
    /** Sounds of its own for when it's placed and broken, played instead of [sound]'s. */
    val sounds: BlockSounds? = null,
    /**
     * The project's own facts about it, any JSON (`{ "damage": 12, "class": "melee" }`): what scripts read with
     * `ProjectBlock:properties()`, so data that belongs to the block lives in its file rather than in a table a script keeps.
     * Nothing else reads it.
     */
    val properties: Map<String, JsonElement>? = null,
    /** `<id>`: a centity drawn over the block, for a shape that isn't a cube. It's spawned when the block is placed and goes with it. */
    @Ref(RefKind.CENTITY) val centity: ResourceRef? = null,
    /**
     * Whether it falls like sand when there's nothing under it: it drops as a falling block and lands as this block
     * again, or drops its [drops] where it can't land (on a torch, a slab). Only a note block or leaves block that no
     * [centity] is drawn over can fall. Default false.
     */
    val falls: Boolean? = null,
    /**
     * Which ways it can face ([BlockRotation]): its model is turned to each, and a player places it facing them,
     * as a furnace or a chair is. Each way is a state of its own, so a block that turns takes 4 (or 6) of its
     * carrier's states. Default [BlockRotation.NONE].
     */
    val rotation: BlockRotation? = null,
    /** Ticks between the block's `tick` events, one per placed block. It doesn't tick when this is left out. */
    val tick: Int? = null,
    /** The block's one script, with `this` the block: it hears what players do with every one of them. */
    val script: ScriptDef? = null
) {
    /** How long it takes to mine, [DEFAULT_HARDNESS] unless the file says. */
    val hardnessOrDefault: Double get() = hardness ?: DEFAULT_HARDNESS

    /** Whether it drops nothing without the right tool. */
    val requiresToolOrDefault: Boolean get() = requiresTool ?: false

    val carrierOrDefault: BlockCarrier get() = carrier ?: BlockCarrier.NOTE_BLOCK

    val rotationOrDefault: BlockRotation get() = rotation ?: BlockRotation.NONE

    /** Whether it falls when there's nothing under it. */
    val fallsOrDefault: Boolean get() = falls ?: false

    /** The game's kind of block it sounds like: [sound], else what the blocks its [tool] mines mostly are. */
    val soundOrDefault: String get() = sound ?: when (tool) {
        BlockTool.AXE -> "wood"
        BlockTool.SHOVEL -> "gravel"
        BlockTool.HOE -> "grass"
        BlockTool.PICKAXE, null -> "stone"
    }

    /** The game's sound of it being placed: [soundOrDefault]'s. */
    val placeSound: String get() = "minecraft:block.$soundOrDefault.place"

    /** The game's sound of it being broken: [soundOrDefault]'s. */
    val breakSound: String get() = "minecraft:block.$soundOrDefault.break"

    companion object {
        const val FILE_NAME = "block.json"
        const val SCHEMA = "../../.netherforge/schema/block.schema.json"

        const val DEFAULT_HARDNESS = 1.5

        /** The hardness that can't be broken by hand, as bedrock can't. */
        const val UNBREAKABLE = -1.0

        /** The most ticks between `tick` events: an hour. */
        const val MAX_TICK = 72_000
    }
}

/**
 * What a block is held as in the world: the game's block whose states carry it ([BlockCarriers]). That block is
 * what the game knows, so it decides the block's collision, what a player selects, whether the faces of the blocks
 * beside it are drawn, and how it's lit; the resource pack decides only how it looks.
 */
@Serializable
enum class BlockCarrier {
    /**
     * A note block: a solid, opaque full cube. The faces of the blocks beside it aren't drawn, so a look that isn't
     * a whole cube leaves see-through holes. Up to 600 or so blocks (the states left of the game's note block).
     */
    @SerialName("note_block")
    NOTE_BLOCK,

    /**
     * Tripwire: nothing to collide with and nothing it hides, for plants, flowers and flat decorations. It breaks at
     * once, whatever its hardness, and selects as a low slab. Up to 96 blocks.
     */
    @SerialName("tripwire")
    TRIPWIRE,

    /**
     * Leaves: a solid full cube the neighbours' faces show through, for glassy and leafy blocks and for shapes
     * that sit in a block (crystals, pots, altars), which collide as the cube. Up to six blocks per kind of leaves the
     * game has. The game draws falling-leaf and dripping particles from leaves, and players on Fast graphics see
     * leaves (and so these blocks) without their see-through parts.
     */
    @SerialName("leaves")
    LEAVES
}

/**
 * Which ways a block can face. Its model is drawn facing north (its front on the model's north side, as the game's
 * furnace is), and turned for each other way.
 */
@Serializable
enum class BlockRotation(val facings: List<BlockFacing>) {
    /** One way only: the model as it's drawn. */
    @SerialName("none")
    NONE(emptyList()),

    /** North, east, south or west: a chair, a furnace, a sign on the floor. */
    @SerialName("horizontal")
    HORIZONTAL(listOf(BlockFacing.NORTH, BlockFacing.EAST, BlockFacing.SOUTH, BlockFacing.WEST)),

    /** Those, up or down too: an observer, a statue on a wall or a ceiling. */
    @SerialName("all")
    ALL(listOf(BlockFacing.NORTH, BlockFacing.EAST, BlockFacing.SOUTH, BlockFacing.WEST, BlockFacing.UP, BlockFacing.DOWN))
}

/**
 * The way a block's front faces, and how its north-facing model is turned to face it: [x] and [y] are the
 * resource pack's blockstate rotation in degrees, as the game's turns its own blocks.
 */
@Serializable
enum class BlockFacing(val id: String, val x: Int, val y: Int) {
    @SerialName("north")
    NORTH("north", 0, 0),

    @SerialName("east")
    EAST("east", 0, 90),

    @SerialName("south")
    SOUTH("south", 0, 180),

    @SerialName("west")
    WEST("west", 0, 270),

    @SerialName("up")
    UP("up", 270, 0),

    @SerialName("down")
    DOWN("down", 90, 0);

    companion object {
        /** The facing called [id] (`"east"`), or null. */
        fun of(id: String): BlockFacing? = entries.firstOrNull { it.id == id }
    }
}

/** A block's own sounds. Each is a sound of one of the project's packs; the carrier's own sounds play for the rest (walking, hitting). */
@Serializable
data class BlockSounds(
    /** `<pack>/<key>`: played where it's placed, from a player placing it. */
    @Ref(RefKind.SOUND) val place: ResourceRef? = null,
    /** `<pack>/<key>`: played where it's broken, from a player breaking it. */
    @SerialName("break") @Ref(RefKind.SOUND) val destroy: ResourceRef? = null
)

/**
 * The kind of tool a block is mined with. [tag] is the game's block tag of
 * what that tool mines fastest, which the server asks about the tool a
 * player holds.
 */
@Serializable
enum class BlockTool(val tag: String) {
    @SerialName("pickaxe")
    PICKAXE("minecraft:mineable/pickaxe"),

    @SerialName("axe")
    AXE("minecraft:mineable/axe"),

    @SerialName("shovel")
    SHOVEL("minecraft:mineable/shovel"),

    @SerialName("hoe")
    HOE("minecraft:mineable/hoe")
}
