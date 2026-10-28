package dev.netherforge.format.recipe

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.ref.ResourceRefSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `recipes/<id>.json`: one recipe the server learns, for a crafting table, a
 * furnace and its kin, a smithing table or a stonecutter.
 *
 * One flat shape for every [type]: each type uses the fields it needs
 * ([RecipeValidator] says which, and refuses the rest), so a recipe reads
 * like vanilla's recipe JSON. Ingredients are vanilla item ids, item tags or
 * the project's own items ([Ingredient]); the result is an item, which can
 * name a project item too.
 */
@Serializable
data class RecipeFile(
    @SerialName("\$schema") val schema: String? = null,
    val type: RecipeType,
    /** `shaped`: up to three rows of up to three characters, each a [key] or a space for an empty square. */
    val pattern: List<String>? = null,
    /** `shaped`: what each character of the [pattern] stands for. */
    val key: Map<String, Ingredient>? = null,
    /** `shapeless`: one to nine ingredients, in any order and place. */
    val ingredients: List<Ingredient>? = null,
    /** The cooking types and `stonecutting`: what goes in. */
    val ingredient: Ingredient? = null,
    /** `smithing_transform`: the smithing template. */
    val template: Ingredient? = null,
    /** `smithing_transform`: the item being upgraded; its components carry over to the result. */
    val base: Ingredient? = null,
    /** `smithing_transform`: the material. */
    val addition: Ingredient? = null,
    /** What it makes. */
    val result: ItemDef,
    /** The cooking types: experience it gives. Default 0. */
    val experience: Double? = null,
    /** The cooking types: ticks it takes. Defaults: 200 in a furnace, 100 in a blast furnace or smoker, 600 on a campfire. */
    val cookingTime: Int? = null,
    /** Recipes with the same group share one entry in the recipe book. */
    val group: String? = null,
    /** The recipe book tab. Crafting: `building`, `redstone`, `equipment` or `misc`; cooking: `food`, `blocks` or `misc`. */
    val category: RecipeCategory? = null,
    /** `station`: what it takes from the crafter's inventory, each ingredient with how many. */
    val materials: List<Material>? = null,
    /**
     * `station`: the blocks one of which must be near the crafter, each an id (`"minecraft:crafting_table"`), a
     * `#tag` or a project block (`"work_bench"`), or several between `|` of which any will do (`"furnace|hellforge"`);
     * all of the list, when it names several. Absent: none needed.
     */
    val stations: List<String>? = null,
    /** `station`: how near a station must be, in blocks. Default [Material.DEFAULT_STATION_RANGE]. */
    val stationRange: Int? = null
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/recipe.schema.json"
    }
}

@Serializable
enum class RecipeType(
    /** The categories its recipe book tab takes. */
    val categories: Set<RecipeCategory>
) {
    @SerialName("shaped")
    SHAPED(RecipeCategory.CRAFTING),

    @SerialName("shapeless")
    SHAPELESS(RecipeCategory.CRAFTING),

    @SerialName("furnace")
    FURNACE(RecipeCategory.COOKING),

    @SerialName("blasting")
    BLASTING(RecipeCategory.COOKING),

    @SerialName("smoking")
    SMOKING(RecipeCategory.COOKING),

    @SerialName("campfire_cooking")
    CAMPFIRE_COOKING(RecipeCategory.COOKING),

    @SerialName("smithing_transform")
    SMITHING_TRANSFORM(emptySet()),

    @SerialName("stonecutting")
    STONECUTTING(emptySet()),

    /** Made by scripts (`nf.recipes.craft`), never by the game: materials from the inventory, stations nearby. */
    @SerialName("station")
    STATION(emptySet());

    val cooking: Boolean get() = this in COOKING

    /** The ingredient and number fields a recipe of this type must have. */
    val requiredFields: Set<String>
        get() = when (this) {
            SHAPED -> setOf("pattern", "key")
            SHAPELESS -> setOf("ingredients")
            FURNACE, BLASTING, SMOKING, CAMPFIRE_COOKING, STONECUTTING -> setOf("ingredient")
            SMITHING_TRANSFORM -> setOf("template", "base", "addition")
            STATION -> setOf("materials")
        }

    /** The ingredient and number fields it may have besides those. */
    val optionalFields: Set<String>
        get() = when {
            cooking -> setOf("experience", "cookingTime")
            this == STATION -> setOf("stations", "stationRange")
            else -> emptySet()
        }

    /** Whether the game makes it (a crafting table, a furnace...): every type but [STATION], which only scripts do. */
    val inGame: Boolean get() = this != STATION

    /** Whether it takes a recipe book `group`: everything but smithing. */
    val takesGroup: Boolean get() = this != SMITHING_TRANSFORM

    /** Vanilla's cooking time for this type, in ticks; 0 for one that doesn't cook. */
    val defaultCookingTime: Int
        get() = when (this) {
            FURNACE -> 200
            BLASTING, SMOKING -> 100
            CAMPFIRE_COOKING -> 600
            else -> 0
        }

    companion object {
        val COOKING = setOf(FURNACE, BLASTING, SMOKING, CAMPFIRE_COOKING)
    }
}

/** A recipe book tab. */
@Serializable
enum class RecipeCategory {
    @SerialName("building")
    BUILDING,

    @SerialName("redstone")
    REDSTONE,

    @SerialName("equipment")
    EQUIPMENT,

    @SerialName("misc")
    MISC,

    @SerialName("food")
    FOOD,

    @SerialName("blocks")
    BLOCKS;

    companion object {
        val CRAFTING = setOf(BUILDING, REDSTONE, EQUIPMENT, MISC)
        val COOKING = setOf(FOOD, BLOCKS, MISC)
    }
}

/** A `station` recipe's material: an [ingredient] and how many of it ([count], default 1). */
@Serializable
data class Material(val ingredient: Ingredient, val count: Int? = null) {
    val countOrDefault: Int get() = count ?: 1

    companion object {
        /** How near a station must be by default: Terraria's reach round a crafting station. */
        const val DEFAULT_STATION_RANGE = 4

        /** The furthest a station may be asked to be. */
        const val MAX_STATION_RANGE = 16
    }
}

/**
 * What fits in one place of a recipe: exactly one of a vanilla item [kind]
 * (`"minecraft:stick"`), an item [tag] (`"#minecraft:planks"`) or one of the
 * project's [item]s (`{ "item": "ruby" }`).
 *
 * Written the way vanilla's recipe JSON writes the first two (a string,
 * a tag with `#`), and as an object for a project item. A project item is
 * matched by the id its stacks carry, never by its look; a vanilla kind or a
 * tag never matches a project item's stack, though it's that kind.
 */
@Serializable(with = IngredientSerializer::class)
data class Ingredient(val kind: String? = null, val tag: String? = null, val item: ResourceRef? = null) {
    override fun toString(): String = item?.let { "item $it" } ?: tag?.let { "#$it" } ?: kind.orEmpty()

    companion object {
        /** The serial name the contract generator knows this union by. */
        const val SERIAL_NAME = "dev.netherforge.format.recipe.Ingredient"

        fun of(text: String): Ingredient = if (text.startsWith("#")) Ingredient(tag = text.removePrefix("#")) else Ingredient(kind = text)
    }
}

/**
 * An [Ingredient] as JSON: a string (an item id, or a tag after `#`), or `{ "item": "<project item>" }`.
 *
 * Described as the object form, so the reference walker finds the project
 * item in it like any other `@Ref`; the contract generator knows the union by
 * [Ingredient.SERIAL_NAME].
 */
object IngredientSerializer : KSerializer<Ingredient> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(Ingredient.SERIAL_NAME) {
        element("item", ResourceRefSerializer.descriptor, listOf(Ref(RefKind.ITEM)), isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: Ingredient) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Ingredients are JSON only")
        val element = when {
            value.item != null -> buildJsonObject { put("item", JsonPrimitive(value.item.text)) }
            value.tag != null -> JsonPrimitive("#${value.tag}")
            else -> JsonPrimitive(value.kind.orEmpty())
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Ingredient {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Ingredients are JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) throw SerializationException(MESSAGE)
                Ingredient.of(element.content)
            }
            is JsonObject -> {
                val unknown = element.keys - "item"
                if (unknown.isNotEmpty()) throw SerializationException("An ingredient object has only \"item\", not \"${unknown.first()}\"")
                val item = element["item"] as? JsonPrimitive
                if (item == null || !item.isString) throw SerializationException(MESSAGE)
                Ingredient(item = ResourceRef(item.content))
            }
            else -> throw SerializationException(MESSAGE)
        }
    }

    private const val MESSAGE =
        "An ingredient is an item id (\"minecraft:stick\"), a tag (\"#minecraft:planks\") or a project item ({ \"item\": \"ruby\" })"
}
