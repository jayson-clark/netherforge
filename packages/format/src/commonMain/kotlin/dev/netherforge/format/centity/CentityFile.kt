package dev.netherforge.format.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.image.Pixels
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `centities/<id>/centity.json`: a composed, scripted entity, as authored.
 *
 * The id is the folder name and is not repeated inside. Nodes and animations
 * are objects keyed by name rather than lists, so names are unique by
 * construction and two branches that each add a node merge cleanly.
 */
@Serializable
data class CentityFile(
    @SerialName("\$schema") val schema: String? = null,
    /** Display name for people. Optional; the id is what everything else uses. */
    val name: String? = null,
    val nodes: Map<String, NodeDef> = emptyMap(),
    val animations: Map<String, AnimationDef> = emptyMap(),
    /** A body that moves the whole centity: walks, falls, jumps and collides with blocks. Absent: it stays where it's put. */
    val body: BodyDef? = null,
    /** Health it loses to `damage` and dies at 0: a mob's, a target's. Absent: it has none. */
    val health: HealthDef? = null,
    /** The centity's one script: a copy runs per instance, with `this` the instance. */
    val script: ScriptDef? = null,
    /** Where and how often it appears by itself, near players. Absent: it only exists where a script spawns it. */
    val spawning: SpawningDef? = null,
    /**
     * The project's own facts about it, any JSON (`{ "damage": 12, "class": "melee" }`): what scripts read with
     * `Centity:properties()`, so data that belongs to the centity lives in its file rather than in a table a script keeps.
     * Nothing else reads it.
     */
    val properties: Map<String, JsonElement>? = null
) {
    companion object {
        const val FILE_NAME = "centity.json"
        const val SCHEMA = "../../.netherforge/schema/centity.schema.json"
    }
}

/**
 * The `spawning` block: the rules the natural spawner follows for this
 * centity. Every field is optional; [weightOrDefault] and the other
 * `...OrDefault`s are what an absent one means.
 *
 * Each pass the spawner picks a place around a player ([MIN_DISTANCE] to
 * [MAX_DISTANCE] blocks away), asks which of the project's centities may
 * appear there, and chooses among them by [weight]. A natural centity is
 * temporary: it isn't saved and goes when no player is within
 * [despawnDistance], until a script calls `keep()` or, with [keepOnInteract], a player clicks it.
 */
@Serializable
data class SpawningDef(
    /** World names it may appear in. Absent: every world. */
    val worlds: List<String>? = null,
    /**
     * The biomes it may appear in: the game's (`minecraft:plains`) or its `#tag`s, or the project's
     * (`ruby_grove`, `acme:grove` for a package's). Absent: any.
     */
    @Ref(RefKind.BIOME) val biomes: List<String>? = null,
    /** The block light level (0 to 15, the brighter of block and sky light) at the spot. */
    val light: SpawnRange? = null,
    /** The block it stands on: block ids, or `#tag`s of blocks. Absent: any solid block. */
    val blocks: List<String>? = null,
    /** The heights (block y) it may appear at. */
    val height: SpawnRange? = null,
    /** How likely it is, against the other centities that may appear at the same spot. Default [DEFAULT_WEIGHT]. */
    val weight: Int? = null,
    /** How many appear together. Default one. */
    val group: SpawnRange? = null,
    /** Most of this centity that may be natural and near one player at once. Default [DEFAULT_CAP]. */
    val cap: Int? = null,
    /** How far from every player a natural one is removed, in blocks. Default [DEFAULT_DESPAWN_DISTANCE]. */
    val despawnDistance: Int? = null,
    /** Whether a player interacting with (clicking) a natural one keeps it, as `keep()` does. Default false. */
    val keepOnInteract: Boolean? = null,
    /** The time of day: `"day"`, `"night"`, or `{ "min": 13000, "max": 23000 }` in ticks of the day. Absent: any time. */
    val time: SpawnTime? = null,
    /** The moon phases it may appear in. Absent: any. */
    val moonPhase: List<MoonPhase>? = null,
    /** The weather it may appear in: `clear`, `rain` (thunderstorms too) or `thunder`. Absent: any. */
    val weather: List<SpawnWeather>? = null,
    /**
     * A condition a script decides (`nf.spawning.condition(name, callback)`): it appears only while the
     * callback answers true for the place. The project's own by name, a package's as `ns:name`. Absent: no condition.
     */
    @SerialName("when") val condition: String? = null
) {
    val weightOrDefault: Int get() = weight ?: DEFAULT_WEIGHT
    val capOrDefault: Int get() = cap ?: DEFAULT_CAP
    val despawnDistanceOrDefault: Int get() = despawnDistance ?: DEFAULT_DESPAWN_DISTANCE
    val keepOnInteractOrDefault: Boolean get() = keepOnInteract ?: false
    val groupMin: Int get() = group?.min ?: 1
    val groupMax: Int get() = maxOf(groupMin, group?.max ?: groupMin)
    val lightMin: Int get() = light?.min ?: MIN_LIGHT
    val lightMax: Int get() = light?.max ?: MAX_LIGHT

    companion object {
        const val DEFAULT_WEIGHT = 10
        const val DEFAULT_CAP = 4
        const val DEFAULT_DESPAWN_DISTANCE = 96
        const val MIN_LIGHT = 0
        const val MAX_LIGHT = 15

        /** Whether [name] can name a spawn condition: an id (the package's own), or `ns:id` (another package's). */
        fun isConditionName(name: String): Boolean {
            val parts = name.split(':')
            return parts.size <= 2 && parts.all(Names::isId)
        }

        /** How near a player the spawner places one, and how far: the ring it picks from. */
        const val MIN_DISTANCE = 24
        const val MAX_DISTANCE = 48
    }
}

/** An inclusive range with either end optional: `{ "min": 0, "max": 7 }`. */
@Serializable
data class SpawnRange(val min: Int? = null, val max: Int? = null)

/**
 * When in the day a centity may appear: a [period] (`"day"` or `"night"`), or a [range] of ticks of the
 * day (0 to 23999, either end optional), which runs through midnight when its `min` is above its `max`
 * (`{ "min": 22000, "max": 2000 }`). Exactly one is set.
 */
@Serializable(with = SpawnTimeSerializer::class)
data class SpawnTime(val period: DayPeriod? = null, val range: SpawnRange? = null) {
    /** The first tick of the day it allows. */
    val start: Int get() = period?.start ?: range?.min ?: 0

    /** The last tick of the day it allows. */
    val end: Int get() = period?.end ?: range?.max ?: LAST_TICK

    /** Whether [timeOfDay] (0 to 23999) is in it. */
    operator fun contains(timeOfDay: Int): Boolean = if (start <= end) timeOfDay in start..end else timeOfDay >= start || timeOfDay <= end

    companion object {
        /** The serial name the contract generator knows this union by. */
        const val SERIAL_NAME = "dev.netherforge.format.centity.SpawnTime"

        /** Ticks in a day. */
        const val DAY_TICKS = 24_000

        const val LAST_TICK = DAY_TICKS - 1
    }
}

/**
 * The two halves of the day, by the ticks `/time set` uses: night from dusk (13000, `/time set night`)
 * to sunrise (23000), day the rest.
 */
@Serializable
enum class DayPeriod(val start: Int, val end: Int) {
    @SerialName("day")
    DAY(23_000, 12_999),

    @SerialName("night")
    NIGHT(13_000, 22_999)
}

/** A [SpawnTime] as JSON: `"day"`, `"night"`, or `{ "min": …, "max": … }`. */
object SpawnTimeSerializer : KSerializer<SpawnTime> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(SpawnTime.SERIAL_NAME) {
        element<Int>("min", isOptional = true)
        element<Int>("max", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: SpawnTime) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Spawn times are JSON only")
        val element = value.period?.let { JsonPrimitive(CanonicalJson.serialName(it)) } ?: buildJsonObject {
            value.range?.min?.let { put("min", JsonPrimitive(it)) }
            value.range?.max?.let { put("max", JsonPrimitive(it)) }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): SpawnTime {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Spawn times are JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) throw SerializationException(MESSAGE)
                val period = DayPeriod.entries.firstOrNull { CanonicalJson.serialName(it) == element.content }
                    ?: throw SerializationException(MESSAGE)
                SpawnTime(period = period)
            }
            is JsonObject -> SpawnTime(range = json.json.decodeFromJsonElement(SpawnRange.serializer(), element))
            else -> throw SerializationException(MESSAGE)
        }
    }

    private const val MESSAGE = "A time is \"day\", \"night\" or { \"min\": 13000, \"max\": 23000 } in ticks of the day"
}

/** The moon's phases, in the order the game goes through them (one a night). */
@Serializable
enum class MoonPhase {
    @SerialName("full_moon")
    FULL_MOON,

    @SerialName("waning_gibbous")
    WANING_GIBBOUS,

    @SerialName("last_quarter")
    LAST_QUARTER,

    @SerialName("waning_crescent")
    WANING_CRESCENT,

    @SerialName("new_moon")
    NEW_MOON,

    @SerialName("waxing_crescent")
    WAXING_CRESCENT,

    @SerialName("first_quarter")
    FIRST_QUARTER,

    @SerialName("waxing_gibbous")
    WAXING_GIBBOUS
}

/** A world's weather, as natural spawning asks it. */
@Serializable
enum class SpawnWeather {
    @SerialName("clear")
    CLEAR,

    /** Any rain: a thunderstorm is rain too. */
    @SerialName("rain")
    RAIN,

    @SerialName("thunder")
    THUNDER
}

/**
 * One node of the tree. Every field is optional: a node with nothing but a
 * parent is a pivot other nodes hang from.
 */
@Serializable
data class NodeDef(
    /** The parent node's name. Absent for a root. */
    val parent: String? = null,
    val transform: Transform? = null,
    val display: DisplayDef? = null,
    val hitbox: HitboxDef? = null,
    val physics: PhysicsDef? = null,
    /** A rope or chain: its [display] drawn as links that a script strings between two points (`node:rope_to`). */
    val rope: RopeDef? = null
)

/**
 * A rope node: its display drawn [links] times, each link [linkLength] blocks long, laid by the server along a
 * line from one end to the other that sags with slack. A link's display is one block long along Z, centred on its
 * origin, and stretched to its length; only as many links as the ends' distance needs are shown.
 */
@Serializable
data class RopeDef(
    /** The most links, 1 to [MAX_LINKS]. */
    val links: Int,
    /** Each link's length, in blocks. Default [DEFAULT_LINK_LENGTH]. */
    val linkLength: Double? = null
) {
    companion object {
        const val MAX_LINKS = 64
        const val DEFAULT_LINK_LENGTH = 0.5
        const val MAX_LINK_LENGTH = 8.0

        /** A link's node name: `chain[3]`, from 1. */
        fun linkName(rope: String, link: Int) = "$rope[$link]"
    }
}

/**
 * A local transform relative to the parent. Rotation is XYZ euler degrees,
 * applied X then Y then Z. An absent channel is the identity.
 */
@Serializable
data class Transform(val translation: Vec3? = null, val rotation: Vec3? = null, val scale: Vec3? = null) {
    val translationOrDefault: Vec3 get() = translation ?: Vec3.ZERO
    val rotationOrDefault: Vec3 get() = rotation ?: Vec3.ZERO
    val scaleOrDefault: Vec3 get() = scale ?: Vec3.ONE

    companion object {
        val IDENTITY = Transform()
    }
}

/**
 * What a node draws. One per node, because a node backs at most one display
 * entity; the `type` key says which kind.
 */
@Serializable
sealed interface DisplayDef

@Serializable
@SerialName("block")
data class BlockDisplay(
    /** A block state: `minecraft:oak_stairs[facing=east]`. */
    val block: String
) : DisplayDef

@Serializable
@SerialName("item")
data class ItemDisplay(
    val item: String,
    /** How the item is posed (Minecraft's item display context). */
    val itemTransform: ItemTransform? = null
) : DisplayDef

/**
 * One of the project's resource pack looks, shown as an item display: an entry of a pack's `items`
 * (`<pack>/<key>`), whatever it's drawn as, a model with elements included. One display entity draws the
 * whole model however complex, and a script swaps it for another cheaply (`node:set_model`, the same
 * entity showing another look), which is how a flipbook animates.
 */
@Serializable
@SerialName("model")
data class ModelDisplay(
    /** `<pack>/<key>`: the look, an entry of a resource pack's `items`. */
    @Ref(RefKind.ITEM_MODEL) val model: ResourceRef,
    /** Which of the model's `display` transforms applies. Default [ItemTransform.NONE]: as it was modelled. */
    val itemTransform: ItemTransform? = null
) : DisplayDef {
    companion object {
        /** The item kind the display holds under the look, which replaces its model: never seen. */
        const val CARRIER = "minecraft:paper"
    }
}

/** Minecraft's item display contexts: how an item display poses its item. */
@Serializable
enum class ItemTransform {
    @SerialName("none")
    NONE,

    @SerialName("thirdperson_lefthand")
    THIRDPERSON_LEFTHAND,

    @SerialName("thirdperson_righthand")
    THIRDPERSON_RIGHTHAND,

    @SerialName("firstperson_lefthand")
    FIRSTPERSON_LEFTHAND,

    @SerialName("firstperson_righthand")
    FIRSTPERSON_RIGHTHAND,

    @SerialName("head")
    HEAD,

    @SerialName("gui")
    GUI,

    @SerialName("ground")
    GROUND,

    @SerialName("fixed")
    FIXED
}

@Serializable
@SerialName("text")
data class TextDisplay(
    /** MiniMessage. */
    @MiniMessage val text: String,
    /** Text that isn't fixed turns to face the viewer. Default [DEFAULT_BILLBOARD]. */
    val billboard: Billboard? = null,
    val alignment: TextAlignment? = null,
    /** `#AARRGGBB`. */
    val background: String? = null,
    /** Pixels before wrapping. */
    val lineWidth: Int? = null,
    val seeThrough: Boolean? = null,
    val shadow: Boolean? = null,
    /**
     * Where the node's origin is on the text: placed by that point of its lines, the server measuring it with the
     * game's font (`fonts/default.json`). Lines then break only at line breaks, never at [lineWidth]. Default none:
     * the text stands on the origin, centred across it, as the game draws it.
     */
    val anchor: Anchor? = null,
    /**
     * What draws an image's text, run by run: never in a file, only what the engine draws an [ImageDisplay]
     * as. [text] says the same as MiniMessage; a platform draws these instead, each run a piece of its own,
     * since a picture's text is far longer than one piece of the game's text may be, and parsing it would
     * cost the server's tick.
     */
    @Transient val runs: List<TextRun>? = null
) : DisplayDef {
    companion object {
        const val DEFAULT_LINE_WIDTH = 200
        val DEFAULT_BILLBOARD = Billboard.CENTER
    }
}

/** A run of an image's text: characters, in one colour ([rgb], `0xRRGGBB`), or none for moves and line breaks. */
data class TextRun(val text: String, val rgb: Int? = null)

/**
 * A flat rectangle of one colour, exactly [size] blocks, in the node's XY plane facing -Z as text does. Drawn by
 * the server as a text display's background, so it needs nothing in a resource pack but the space characters every
 * built pack has.
 */
@Serializable
@SerialName("rect")
data class RectDisplay(
    /** `#RRGGBB`, or `#AARRGGBB` for one you can see through. */
    val color: String,
    /** Width and height in blocks, before the node's scale. Default `[1, 1]`. */
    val size: List<Double>? = null,
    /** Which point of it is at the node's origin. Default [Anchor.CENTER]. */
    val anchor: Anchor? = null
) : DisplayDef {
    companion object {
        val DEFAULT_SIZE = listOf(1.0, 1.0)
        val DEFAULT_ANCHOR = Anchor.CENTER
    }
}

/**
 * A picture [width] by [height] pixels that a script gives at runtime (`node:set_image`), filling [size] blocks
 * in the node's XY plane facing -Z as text does. Drawn as rows of the engine's pixel glyphs, one colour per run,
 * so it moves and eases like any display. Blank (fully transparent) until a script sets one.
 */
@Serializable
@SerialName("image")
data class ImageDisplay(
    val width: Int,
    val height: Int,
    /** Width and height in blocks, before the node's scale. Default 16 pixels a block. */
    val size: List<Double>? = null,
    /** Which point of it is at the node's origin. Default [Anchor.CENTER]. */
    val anchor: Anchor? = null,
    /** What it shows now: never in the file, only what a script set. Null is blank. */
    @Transient val pixels: Pixels? = null
) : DisplayDef {
    /** [size], or its default. */
    val sizeOrDefault: List<Double> get() = size ?: listOf(width / PIXELS_PER_BLOCK, height / PIXELS_PER_BLOCK)

    companion object {
        /** The most pixels either way. */
        const val MAX_SIDE = 256

        /** The most pixels in all: what one display's text can carry. */
        const val MAX_PIXELS = 128 * 128

        const val PIXELS_PER_BLOCK = 16.0
        val DEFAULT_ANCHOR = Anchor.CENTER
    }
}

/** A point of a display's rectangle: a corner, the middle of a side, or the centre. */
@Serializable
enum class Anchor(
    /** From the left edge, as a share of the width. */
    val x: Double,
    /** From the bottom edge, as a share of the height. */
    val y: Double
) {
    @SerialName("top_left")
    TOP_LEFT(0.0, 1.0),

    @SerialName("top")
    TOP(0.5, 1.0),

    @SerialName("top_right")
    TOP_RIGHT(1.0, 1.0),

    @SerialName("left")
    LEFT(0.0, 0.5),

    @SerialName("center")
    CENTER(0.5, 0.5),

    @SerialName("right")
    RIGHT(1.0, 0.5),

    @SerialName("bottom_left")
    BOTTOM_LEFT(0.0, 0.0),

    @SerialName("bottom")
    BOTTOM(0.5, 0.0),

    @SerialName("bottom_right")
    BOTTOM_RIGHT(1.0, 0.0)
}

/** Which axes a display turns on to face its viewer. */
@Serializable
enum class Billboard {
    @SerialName("fixed")
    FIXED,

    @SerialName("vertical")
    VERTICAL,

    @SerialName("horizontal")
    HORIZONTAL,

    @SerialName("center")
    CENTER
}

@Serializable
enum class TextAlignment {
    @SerialName("center")
    CENTER,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT
}

/**
 * Where a node can be clicked.
 *
 * Either explicit [boxes] in the node's space (the default is the unit cube
 * from `[0, 0, 0]` to `[1, 1, 1]` that a block display fills), or `shape: "collision"`
 * to follow the collision shape of the node's block display live, which the
 * server knows for any block. Shapes only the client knows (a stair's model, a
 * text quad) are fitted into explicit boxes by the editor; [fittedTo] records
 * what they were fitted to so the validator can say when they've gone stale.
 */
@Serializable
data class HitboxDef(
    val boxes: List<Box>? = null,
    /** Follow a shape the server knows instead of [boxes]. */
    val shape: HitboxShape? = null,
    /** Reject clicks that land inside the interaction entity but outside the boxes. Defaults on for shaped hitboxes. */
    val raycast: Boolean? = null,
    val fittedTo: String? = null
) {
    val followsCollision: Boolean get() = shape == HitboxShape.COLLISION
    val raycastEnabled: Boolean get() = raycast ?: (followsCollision || (boxes?.size ?: 0) > 1)
}

@Serializable
enum class HitboxShape {
    /** The live collision shape of the node's block display. */
    @SerialName("collision")
    COLLISION
}

/**
 * A rigid body: falls, tips, bounces, rolls. Rates are per second.
 * [ResolvedPhysics] holds the defaults and what runs.
 */
@Serializable
data class PhysicsDef(
    /** Downward acceleration, blocks per second squared. */
    val gravity: Double? = null,
    /** Matters only between bodies. Defaults to the collider's volume. */
    val mass: Double? = null,
    /** 0 lands dead, 1 bounces forever. */
    val bounciness: Double? = null,
    /** Coulomb coefficient. 0 is ice, ~0.6 wood on stone. */
    val friction: Double? = null,
    /** Fraction of speed shed per second in the air. */
    val drag: Double? = null,
    val angularDrag: Double? = null,
    /** Blocks per second. */
    val maxSpeed: Double? = null,
    /** Degrees per second. */
    val maxSpin: Double? = null,
    /** Own collider in the node's space; defaults to the hitbox, or the unit cube without one. */
    val collider: Box? = null,
    /** Absent follows the hitbox. */
    val shape: PhysicsShape? = null,
    val rotates: Boolean? = null,
    val sleeps: Boolean? = null,
    /** Collide with the world's blocks. */
    val blocks: Boolean? = null,
    /** Collide with other centities' hitboxes. */
    val entities: Boolean? = null
)

/**
 * The `body` block: an upright box, [width] square and [height] tall, standing with its bottom middle at the
 * centity's position, that the runtime moves every tick: gravity, drag, walking, jumping and the blocks'
 * collision shapes. It moves the whole centity (its anchor), unlike a node's `physics`, which moves one node.
 * Rates are per second, as [PhysicsDef]'s; [ResolvedBody] is what an absent field means.
 */
@Serializable
data class BodyDef(
    /** Blocks across, square. */
    val width: Double? = null,
    /** Blocks tall. */
    val height: Double? = null,
    /** Downward acceleration, blocks per second squared. */
    val gravity: Double? = null,
    /** The fastest it falls, blocks per second. */
    val maxFallSpeed: Double? = null,
    /** Fraction of its speed shed per second in the air (flying: on every axis). */
    val drag: Double? = null,
    /** Fraction of its speed shed per second in water or lava. */
    val liquidDrag: Double? = null,
    /** How much of [gravity] pulls it in water or lava: 0 floats where it is, 1 sinks as in air. */
    val liquidGravity: Double? = null,
    /** How fast it reaches the speed it walks at on the ground (and flying), blocks per second squared. */
    val acceleration: Double? = null,
    /** The same in the air, when it isn't flying: how much it can steer while it falls or jumps. */
    val airAcceleration: Double? = null,
    /** How fast it slows to a stop on the ground when it isn't walking, blocks per second squared. */
    val friction: Double? = null,
    /** The tallest step it walks straight up, blocks. 0: it must jump. */
    val stepHeight: Double? = null,
    /** 0 stops dead against what it hits, 1 bounces off at the speed it came. */
    val bounce: Double? = null,
    /** Flies: no gravity, and walking steers it in any direction. */
    val fly: Boolean? = null,
    /** Collides with the world's blocks. */
    val blocks: Boolean? = null
)

/**
 * The `health` block: how much it takes (`max`), whether it's removed when it dies, and a bar drawn over it.
 * [ResolvedHealth] is what an absent field means.
 */
@Serializable
data class HealthDef(
    /** Its health when whole, more than 0. */
    val max: Double,
    /** Removed when it dies, after its `death` handlers. Default true. */
    val removeOnDeath: Boolean? = null,
    /** A bar over it showing how much it has left. Absent: none. */
    val bar: HealthBarDef? = null
)

/** A health bar drawn over a centity, as a text display: [segments] of [glyph], the full ones [filled], the rest [empty]. */
@Serializable
data class HealthBarDef(
    /** How many pieces it's drawn in, 1 to 40. Default 10. */
    val segments: Int? = null,
    /** Ticks it shows after damage or healing, then hides; 0 shows it always. Default 100. */
    val showTicks: Int? = null,
    /** Blocks above the top of its hitboxes (or its position, without any) it's drawn at. Default 0.35. */
    val offset: Double? = null,
    /** One piece, as text. Default `▮`. */
    val glyph: String? = null,
    /** MiniMessage before the full pieces. Default `<red>`. */
    @MiniMessage val filled: String? = null,
    /** MiniMessage before the empty ones. Default `<dark_gray>`. */
    @MiniMessage val empty: String? = null
)

@Serializable
enum class PhysicsShape {
    /** Collapses a multi-box hitbox to the one box around it. */
    @SerialName("box")
    BOX,

    /** Rolls. */
    @SerialName("sphere")
    SPHERE
}

/**
 * A named clip. Tracks are keyed by node name, then by channel, so a clip
 * drives each channel of each node at most once.
 */
@Serializable
data class AnimationDef(
    /** Seconds. Defaults to the last keyframe's time. */
    val length: Double? = null,
    val loop: LoopMode? = null,
    /** Start when the centity loads, with no script asking. */
    val autoplay: Boolean? = null,
    /** How its tracks meet the pose under them. Default [AnimationBlend.REPLACE]. */
    val blend: AnimationBlend? = null,
    val tracks: Map<String, Map<Channel, List<Keyframe>>> = emptyMap()
)

/** How an animation's tracks meet the pose under them: the rest pose, what scripts set, and the other clips. */
@Serializable
enum class AnimationBlend {
    /** A track owns its channel: the newest clip driving it wins. */
    @SerialName("replace")
    REPLACE,

    /**
     * On top of whatever else poses the node, after every replacing clip: translation and rotation add to it, scale
     * multiplies it. Keys are changes, so `[0, 0, 0]` (`[1, 1, 1]` for scale) leaves the node as it is.
     */
    @SerialName("additive")
    ADDITIVE
}

@Serializable
enum class LoopMode {
    /** Play through, then hand the channels back to the base pose. */
    @SerialName("once")
    ONCE,

    @SerialName("loop")
    LOOP,

    /** Play through, then hold the final pose. */
    @SerialName("hold")
    HOLD
}

@Serializable
enum class Channel(val key: String) {
    @SerialName("translation")
    TRANSLATION("translation"),

    @SerialName("rotation")
    ROTATION("rotation"),

    @SerialName("scale")
    SCALE("scale");

    val identity: Vec3 get() = if (this == SCALE) Vec3.ONE else Vec3.ZERO

    companion object {
        fun parse(key: String): Channel? = entries.firstOrNull { it.key == key }
    }
}

/** One pose on a track. [easing] shapes the segment leaving this key. */
@Serializable
data class Keyframe(val time: Double, val value: Vec3, val easing: Easing? = null)

@Serializable
enum class Easing {
    @SerialName("linear")
    LINEAR,

    /** Hold this key's value until the next one. */
    @SerialName("step")
    STEP,

    @SerialName("ease_in")
    EASE_IN,

    @SerialName("ease_out")
    EASE_OUT,

    @SerialName("ease_in_out")
    EASE_IN_OUT;

    /** Reshapes a normalised segment position; 0 maps to 0 and 1 to 1 for every curve except STEP. */
    fun apply(t: Double): Double = when (this) {
        LINEAR -> t
        STEP -> 0.0
        EASE_IN -> t * t
        EASE_OUT -> 1 - (1 - t) * (1 - t)
        EASE_IN_OUT -> if (t < 0.5) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)
    }
}
