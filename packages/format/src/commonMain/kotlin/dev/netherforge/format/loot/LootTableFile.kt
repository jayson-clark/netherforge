package dev.netherforge.format.loot

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `loot/<id>.json`: what a roll of a loot table gives (a chest's contents, a
 * mob's or a block's drops, a reward), in NetherForge's own format, rolled by
 * the runtime ([LootRoller]).
 *
 * Shaped like vanilla's loot tables, cut down to what people write by hand:
 * [pools] each pick a few weighted [LootEntry]s, and a few [LootCondition]s
 * decide what may be picked. Entries name the project's own items, vanilla
 * items, other loot tables of the project's, and the game's own loot tables
 * by id.
 */
@Serializable
data class LootTableFile(
    @SerialName("\$schema") val schema: String? = null,
    /**
     * The pools, by name. A roll rolls every pool in name order, each adding
     * what it picks; a pool's name is only for people (and the editor).
     */
    val pools: Map<String, LootPool> = emptyMap()
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/loot_table.schema.json"
    }
}

/** One pool: it picks an entry [rolls] times, each pick weighted among the entries whose conditions pass. */
@Serializable
data class LootPool(
    /** How many picks: a number, or `{ "min": 1, "max": 3 }`, any whole number in between. Default 1. */
    val rolls: LootRange? = null,
    /** More picks per point of the roll's luck, rounded down. Default 0. */
    val bonusRolls: Double? = null,
    /** Every one must pass for the pool to pick anything. */
    val conditions: List<LootCondition>? = null,
    /** What it picks from, in order. */
    val entries: List<LootEntry> = emptyList()
)

/**
 * A whole number, or a range of them picked from uniformly: written `2`, or
 * `{ "min": 1, "max": 3 }` (both ends included). A range whose ends are equal
 * is written as the number.
 */
@Serializable(with = LootRangeSerializer::class)
data class LootRange(val min: Int, val max: Int = min) {
    override fun toString(): String = if (min == max) "$min" else "$min–$max"

    companion object {
        /** The serial name the contract generator knows this union by. */
        const val SERIAL_NAME = "dev.netherforge.format.loot.LootRange"
    }
}

/** A [LootRange] as JSON: a whole number, or `{ "min": …, "max": … }`. */
object LootRangeSerializer : KSerializer<LootRange> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(LootRange.SERIAL_NAME) {
        element<Int>("min")
        element<Int>("max")
    }

    override fun serialize(encoder: Encoder, value: LootRange) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Loot ranges are JSON only")
        val element = if (value.min == value.max) {
            JsonPrimitive(value.min)
        } else {
            buildJsonObject {
                put("min", JsonPrimitive(value.min))
                put("max", JsonPrimitive(value.max))
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): LootRange {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Loot ranges are JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> LootRange(whole(element))
            is JsonObject -> {
                val unknown = element.keys - setOf("min", "max")
                if (unknown.isNotEmpty()) throw SerializationException("A range has only \"min\" and \"max\", not \"${unknown.first()}\"")
                val min = element["min"] as? JsonPrimitive ?: throw SerializationException(MESSAGE)
                val max = element["max"] as? JsonPrimitive ?: throw SerializationException(MESSAGE)
                LootRange(whole(min), whole(max))
            }
            else -> throw SerializationException(MESSAGE)
        }
    }

    private fun whole(primitive: JsonPrimitive): Int {
        if (primitive.isString) throw SerializationException(MESSAGE)
        val long = primitive.longOrNull ?: throw SerializationException(MESSAGE)
        return primitive.intOrNull ?: (if (long > 0) Int.MAX_VALUE else Int.MIN_VALUE)
    }

    private const val MESSAGE = "A range is a whole number (2) or { \"min\": 1, \"max\": 3 }"
}

/**
 * One thing a pool can pick. Every entry has a [weight] (how likely a pick
 * lands on it, against the others that may be picked), a [quality] that
 * shifts that weight with the roll's luck, and [conditions] that must all
 * pass for it to be picked at all.
 */
@Serializable
sealed interface LootEntry {
    /** How likely it is to be picked, against the other entries that may be. At least 1. Default 1. */
    val weight: Int?

    /** Added to [weight] per point of the roll's luck (negative for a worse chance with luck). Default 0. */
    val quality: Int?

    /** Every one must pass for it to be picked. */
    val conditions: List<LootCondition>?
}

/** An item stack: the project's own item or a vanilla one, styled as any stack can be. */
@Serializable
@SerialName("item")
data class ItemEntry(
    /** The stack, without a `count`: [count] says how many. */
    val item: ItemDef,
    /** How many: a number or a range. Default 1. More than a stack holds is given as several stacks. */
    val count: LootRange? = null,
    override val weight: Int? = null,
    override val quality: Int? = null,
    override val conditions: List<LootCondition>? = null
) : LootEntry

/** Another of the project's loot tables (or a package's, `ns:id`): picking it rolls that table whole. */
@Serializable
@SerialName("table")
data class TableEntry(
    @Ref(RefKind.LOOT_TABLE) val table: ResourceRef,
    override val weight: Int? = null,
    override val quality: Int? = null,
    override val conditions: List<LootCondition>? = null
) : LootEntry

/**
 * One of the game's loot tables, by id (`minecraft:chests/simple_dungeon`):
 * picking it rolls that table on the server, as the game would.
 */
@Serializable
@SerialName("vanilla")
data class VanillaEntry(
    val table: String,
    override val weight: Int? = null,
    override val quality: Int? = null,
    override val conditions: List<LootCondition>? = null
) : LootEntry

/** Nothing: a pick that lands here gives nothing, so its weight is the chance of no loot. */
@Serializable
@SerialName("empty")
data class EmptyEntry(
    override val weight: Int? = null,
    override val quality: Int? = null,
    override val conditions: List<LootCondition>? = null
) : LootEntry

/**
 * Something about the roll that must hold for a pool or an entry to count.
 * [invert] turns any of them around: "unless".
 */
@Serializable
sealed interface LootCondition {
    /** Passes when the condition doesn't. Default false. */
    val invert: Boolean?
}

/** Passes with this probability, rolled afresh every time it's asked. */
@Serializable
@SerialName("chance")
data class ChanceCondition(
    /** 0 to 1. */
    val chance: Double,
    override val invert: Boolean? = null
) : LootCondition

/**
 * Passes when a player is behind the roll: the one who killed the mob, broke
 * the block or opened the chest (vanilla's `killed_by_player`).
 */
@Serializable
@SerialName("player")
data class PlayerCondition(override val invert: Boolean? = null) : LootCondition

/**
 * Passes when the roll's tool (what the player killed or broke it with) is
 * this: a vanilla item id, an item tag (`#minecraft:pickaxes`) or one of the
 * project's items (`{ "item": "ruby_pick" }`), matched as a recipe matches
 * an ingredient.
 */
@Serializable
@SerialName("tool")
data class ToolCondition(val tool: Ingredient, override val invert: Boolean? = null) : LootCondition

/** Passes when the roll's tool carries an enchantment at [level] or above: silk touch, fortune, looting. */
@Serializable
@SerialName("enchantment")
data class EnchantmentCondition(
    val enchantment: String,
    /** The lowest level that passes. Default 1. */
    val level: Int? = null,
    override val invert: Boolean? = null
) : LootCondition
