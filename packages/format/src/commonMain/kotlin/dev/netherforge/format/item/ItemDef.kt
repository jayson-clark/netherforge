package dev.netherforge.format.item

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * One item stack, as an author writes it and a script reads it back.
 *
 * Flat rather than Minecraft's nested component form, because this is what a
 * script builds by hand: `{ kind = "diamond", count = 3, name = "<gold>Shiny" }`
 * is the whole of the common case. Every field is optional and an absent one
 * means "leave it alone", so reading an item, changing [count] and writing it
 * back never strips its name.
 */
@Serializable
data class ItemDef(
    /**
     * The item type: `minecraft:diamond_sword`, or `diamond_sword`. Required,
     * unless [item] names a project item: then it's that item's, and left out
     * (`""`) until the item is resolved.
     */
    val kind: String = "",
    /**
     * A project item (`items/<id>/item.json`, or a package's as
     * `<namespace>:<id>`): the stack is one of those. The item's definition
     * supplies every field this leaves out, and the stack carries the item's
     * namespaced id (in its persistent data container) wherever it goes.
     */
    @Ref(RefKind.ITEM) val item: ResourceRef? = null,
    /** How many. At least one, at most the stack's own limit. */
    val count: Int? = null,
    /** Display name, MiniMessage. */
    @MiniMessage val name: String? = null,
    /** Tooltip lines under the name, each MiniMessage. */
    @MiniMessage val lore: List<String>? = null,
    /** Enchantment id → level: `{ "minecraft:sharpness": 5 }`. */
    val enchantments: Map<String, Int>? = null,
    /** The enchanted shimmer without an enchantment, as menus use for "selected". */
    val glint: Boolean? = null,
    /** Hide the whole tooltip; for decorative filler panes. */
    val hideTooltip: Boolean? = null,
    val unbreakable: Boolean? = null,
    /** Durability used, not left: 0 is pristine. */
    val damage: Int? = null,
    /** `<pack>/<key>`: a custom look from one of the project's resource packs (the `item_model` component). */
    @Ref(RefKind.ITEM_MODEL) val itemModel: ResourceRef? = null,
    /** `<pack>/<key>`: a custom tooltip frame from one of the project's packs (the `tooltip_style` component). */
    @Ref(RefKind.TOOLTIP) val tooltipStyle: ResourceRef? = null,
    /** Worn as armour or equipment, drawn as a pack's equipment look (the `equippable` component). */
    val equipment: EquipmentDef? = null,
    /** `#RRGGBB`, for dyed leather, potions, maps. */
    val color: String? = null,
    /** Whose head, for `minecraft:player_head`: a name or a UUID. */
    val profile: String? = null,
    /** How many fit in one stack, 1–99 (the `max_stack_size` component). More than 1 can't go with durability. */
    val maxStackSize: Int? = null,
    /** The colour of its name (the `rarity` component). */
    val rarity: ItemRarity? = null,
    /** Attribute changes while it's worn or held (the `attribute_modifiers` component), in order. */
    val attributeModifiers: List<AttributeModifierDef>? = null,
    /**
     * The blocks it can break in adventure mode (the `can_break` component): the project's (`ruby_ore`,
     * a package's `acme:gem_ore`), the game's (`minecraft:stone`), `#tags` of blocks (the game's, or one a
     * project datapack defines: `#showcase:soft`), or `*` for any block.
     */
    @Ref(RefKind.ADVENTURE_BLOCK) val canBreak: List<String>? = null,
    /** The blocks it can be placed on in adventure mode (the `can_place_on` component), written as [canBreak]'s. */
    @Ref(RefKind.ADVENTURE_BLOCK) val canPlaceOn: List<String>? = null,
    /**
     * The tooltip lines to hide (the `tooltip_display` component), by the component that draws them:
     * `unbreakable`, `attribute_modifiers`, `can_break` ([TooltipComponent.ALL]). The components still
     * apply when hidden. Left out leaves the tooltip as it is; `[]` shows all.
     */
    val hiddenTooltipComponents: List<TooltipComponent>? = null,
    /** Eating it (the `food` component, plus `consumable` so it can be eaten at all). */
    val food: FoodDef? = null,
    /** A cooldown after using it (the `use_cooldown` component). */
    val cooldown: CooldownDef? = null,
    /**
     * Held down with no end: a right click starts a use that lasts until the player lets go, never finishes and uses
     * nothing up. Letting go is `player_stop_using_item`, with how long it was held. `true` draws nothing (no zoom, no
     * pose); `{ "animation": "trident" }` poses the player as that use of the game's does ([HoldDef.animation]). It's
     * a `consumable` component of its own, so it can't go with [food], and only works on an item kind with no use of
     * its own (`minecraft:paper`, not a bow or a shield). Unless [useEffects] says otherwise, the player keeps their
     * speed and can sprint while holding it, whatever the animation.
     */
    val hold: HoldDef? = null,
    /**
     * How a player moves while using it, a bow drawn or food eaten as much as a [hold] (the `use_effects`
     * component). Left out, the game's (a fifth of the speed, no sprinting), or for a [hold], full speed.
     */
    val useEffects: UseEffectsDef? = null,
    /**
     * The map a filled map shows (the `map_id` component): a screen's map (`screen:item`), or any of the game's, by
     * its number. Only a `minecraft:filled_map` draws it.
     */
    val map: Int? = null,
    /**
     * Script data saved on the stack (its persistent data container), JSON
     * values by key: `{ "coin": true }`. Scripts read it back as `item.data`.
     */
    val data: Map<String, JsonElement>? = null
)

/** Where an item can be worn, and what it looks like there (the `equippable` component). */
@Serializable
data class EquipmentDef(
    /** `<pack>/<key>`: a look from one of the project's resource packs (`equipment` in `pack.json`). */
    @Ref(RefKind.EQUIPMENT) val asset: ResourceRef,
    /** The slot it's worn in. */
    val slot: EquipSlot
)

/** The equipment slots an item can be worn in (vanilla's, minus the hands). */
@Serializable
enum class EquipSlot {
    @SerialName("head")
    HEAD,

    @SerialName("chest")
    CHEST,

    @SerialName("legs")
    LEGS,

    @SerialName("feet")
    FEET,

    /** A horse's or wolf's armour. */
    @SerialName("body")
    BODY,

    @SerialName("saddle")
    SADDLE
}

/**
 * One line of an item's tooltip that [ItemDef.hiddenTooltipComponents] can hide (the `tooltip_display`
 * component's `hidden_components`): the data component that draws it, by its id without the namespace
 * (`unbreakable`, `painting/variant`). Hiding the line leaves the component working.
 *
 * [ALL] is every component the game draws a tooltip line for, on any supported version, read off the
 * servers' own tooltip code (`ItemStack.addDetailsToTooltip`, `BundleItem`, `HangingEntityItem`), not a
 * list of every data component. It's the one list the schema, the editor, the Lua API's type and the
 * docs show; a value outside it is `item.tooltip-component`, and one the target version doesn't have
 * (by the game's `data_component_type` registry) is `item.unknown-tooltip-component`. A Minecraft
 * version that draws a new line adds it here.
 */
@Serializable(with = TooltipComponentSerializer::class)
data class TooltipComponent(val id: String) {
    /** The component's id in the game: `minecraft:unbreakable`. */
    val key: String get() = "${GameIds.NAMESPACE}:$id"

    override fun toString(): String = id

    companion object {
        const val SERIAL_NAME = "dev.netherforge.format.item.TooltipComponent"

        /** Every line that can be hidden, by component id, in name order. */
        val ALL: List<String> = listOf(
            "attribute_modifiers",
            "banner_patterns",
            "bees",
            "block_entity_data",
            "block_state",
            "bundle_contents",
            "can_break",
            "can_place_on",
            "charged_projectiles",
            "container",
            "container_loot",
            "damage",
            "dyed_color",
            "enchantments",
            "entity_data",
            "firework_explosion",
            "fireworks",
            "instrument",
            "intangible_projectile",
            "jukebox_playable",
            "lore",
            "map_id",
            "ominous_bottle_amplifier",
            "painting/variant",
            "pot_decorations",
            "potion_contents",
            "profile",
            "sign_text_back",
            "sign_text_front",
            "stored_enchantments",
            "sulfur_cube_content",
            "suspicious_stew_effects",
            "trim",
            "tropical_fish/pattern",
            "unbreakable",
            "written_book_content"
        )

        /** Every line that can be hidden. */
        val entries: List<TooltipComponent> = ALL.map(::TooltipComponent)
    }
}

/** A [TooltipComponent] as JSON: its id, as a string. Any string reads; the validator says which aren't lines. */
object TooltipComponentSerializer : KSerializer<TooltipComponent> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(TooltipComponent.SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TooltipComponent) = encoder.encodeString(value.id)

    override fun deserialize(decoder: Decoder): TooltipComponent = TooltipComponent(decoder.decodeString())
}

/** Minecraft's item rarities: the colour of the item's name. */
@Serializable
enum class ItemRarity {
    @SerialName("common")
    COMMON,

    @SerialName("uncommon")
    UNCOMMON,

    @SerialName("rare")
    RARE,

    @SerialName("epic")
    EPIC
}

/**
 * One attribute modifier. [id] defaults to `<namespace>:<attribute's path>_<index>`
 * (the project's namespace, and its place in the list), which is what
 * Minecraft needs to tell modifiers apart.
 */
@Serializable
data class AttributeModifierDef(
    /** A namespaced id, unique on the item. */
    val id: String? = null,
    /** The attribute: `minecraft:attack_damage`, or `attack_damage`. */
    val attribute: String,
    val amount: Double,
    val operation: AttributeOperation,
    /** Where it must be for the modifier to count. Absent is anywhere. */
    val slot: AttributeSlot? = null
) {
    companion object {
        /** The id a modifier gets in a project with [namespace] when it has none: `shop:attack_damage_0`. */
        fun defaultId(namespace: String, attribute: String, index: Int): String = "$namespace:${attribute.substringAfter(':')}_$index"
    }
}

/** How a modifier's amount is applied, in vanilla's words. */
@Serializable
enum class AttributeOperation {
    /** Adds the amount. */
    @SerialName("add_value")
    ADD_VALUE,

    /** Adds the amount times the base value. */
    @SerialName("add_multiplied_base")
    ADD_MULTIPLIED_BASE,

    /** Multiplies the total by one plus the amount. */
    @SerialName("add_multiplied_total")
    ADD_MULTIPLIED_TOTAL
}

/** Where an item has to be for its attribute modifier to count (vanilla's equipment slot groups). */
@Serializable
enum class AttributeSlot {
    @SerialName("any")
    ANY,

    @SerialName("hand")
    HAND,

    @SerialName("main_hand")
    MAIN_HAND,

    @SerialName("off_hand")
    OFF_HAND,

    @SerialName("armor")
    ARMOR,

    @SerialName("head")
    HEAD,

    @SerialName("chest")
    CHEST,

    @SerialName("legs")
    LEGS,

    @SerialName("feet")
    FEET,

    @SerialName("body")
    BODY,

    @SerialName("saddle")
    SADDLE
}

/** What eating an item does. */
@Serializable
data class FoodDef(
    /** Hunger points restored. */
    val nutrition: Int,
    /** Saturation added. */
    val saturation: Double,
    /** Eaten even when full. Default false. */
    val canAlwaysEat: Boolean? = null,
    /** How long eating takes. Default 1.6. */
    val eatSeconds: Double? = null
)

/**
 * How a player moves while using an item (the `use_effects` component). A field left out is the game's default,
 * or for an item that's a [ItemDef.hold], [HOLD]'s.
 */
@Serializable
data class UseEffectsDef(
    /** Their speed while using it, from 0 to 1. The game's default is 0.2; a hold's 1. */
    val speedMultiplier: Double? = null,
    /** Whether they can sprint while using it. The game's default is false; a hold's true. */
    val canSprint: Boolean? = null,
    /** Whether starting to use it sets off sculk sensors. The game's default is true; a hold's false. */
    val interactVibrations: Boolean? = null
) {
    /** Every field filled in: this one's, else [defaults]'s. */
    fun filled(defaults: UseEffectsDef): UseEffectsDef = UseEffectsDef(
        speedMultiplier ?: defaults.speedMultiplier,
        canSprint ?: defaults.canSprint,
        interactVibrations ?: defaults.interactVibrations
    )

    companion object {
        /** The game's own, for an item that doesn't set the component. */
        val GAME = UseEffectsDef(speedMultiplier = 0.2, canSprint = false, interactVibrations = true)

        /** A [ItemDef.hold]'s: holding it doesn't slow the player, and a sculk sensor doesn't hear it. */
        val HOLD = UseEffectsDef(speedMultiplier = 1.0, canSprint = true, interactVibrations = false)
    }
}

/**
 * An item's [ItemDef.hold]: `true` (or `false`, no hold), or `{ "animation": "trident" }`, a hold that poses the
 * player as one of the game's uses does. Everything but the pose is the same either way. Written back, a hold with
 * no [animation] is `true` again.
 */
@Serializable(with = HoldDefSerializer::class)
data class HoldDef(
    /** Whether it's a hold at all: `false` is how a stack turns off its project item's hold. */
    val on: Boolean = true,
    /** The pose while it's held (the `consumable` component's `animation`). Left out, [UseAnimation.NONE]. */
    val animation: UseAnimation? = null
) {
    companion object {
        /** The serial name the contract generator knows this union by. */
        const val SERIAL_NAME = "dev.netherforge.format.item.HoldDef"
    }
}

/** A [HoldDef] as JSON: a boolean, or `{ "animation": "…" }`. */
object HoldDefSerializer : KSerializer<HoldDef> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(HoldDef.SERIAL_NAME) {
        element("animation", UseAnimationSerializer.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: HoldDef) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("A hold is JSON only")
        val animation = value.animation?.takeIf { value.on }
        json.encodeJsonElement(
            if (animation == null) JsonPrimitive(value.on) else buildJsonObject { put("animation", JsonPrimitive(animation.id)) }
        )
    }

    override fun deserialize(decoder: Decoder): HoldDef {
        val json = decoder as? JsonDecoder ?: throw SerializationException("A hold is JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> HoldDef(on = element.takeUnless { it.isString }?.booleanOrNull ?: throw SerializationException(MESSAGE))
            is JsonObject -> {
                val unknown = element.keys - setOf("animation")
                if (unknown.isNotEmpty()) throw SerializationException("A hold has only \"animation\", not \"${unknown.first()}\"")
                val animation = element["animation"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }
                if (element["animation"] != null && animation == null) throw SerializationException(MESSAGE)
                HoldDef(animation = animation?.content?.let(::UseAnimation))
            }
            else -> throw SerializationException(MESSAGE)
        }
    }

    private const val MESSAGE = "hold is true, or { \"animation\": \"trident\" }"
}

/**
 * The pose a [HoldDef] holds the player in: one of the `consumable` component's animations, by the game's own name
 * (`trident`, `bow`). Which are what is read off the servers' `ItemUseAnimation` (every supported version has the
 * same ones), and a contract test holds [ALL] to each server's. A value outside it is `item.hold-animation`. A
 * Minecraft version that adds or drops one changes [ALL], and one some supported versions lack needs the feature
 * table.
 */
@Serializable(with = UseAnimationSerializer::class)
data class UseAnimation(val id: String) {
    override fun toString(): String = id

    companion object {
        const val SERIAL_NAME = "dev.netherforge.format.item.UseAnimation"

        /** Every animation, in the game's order. */
        val ALL: List<String> = listOf(
            "none",
            "eat",
            "drink",
            "block",
            "bow",
            "trident",
            "crossbow",
            "spyglass",
            "toot_horn",
            "brush",
            "bundle",
            "spear"
        )

        /** No pose: what a hold has when it names none. */
        val NONE = UseAnimation("none")

        val entries: List<UseAnimation> = ALL.map(::UseAnimation)
    }
}

/** A [UseAnimation] as JSON: its name. Any string reads; the validator says which aren't animations. */
object UseAnimationSerializer : KSerializer<UseAnimation> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(UseAnimation.SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UseAnimation) = encoder.encodeString(value.id)

    override fun deserialize(decoder: Decoder): UseAnimation = UseAnimation(decoder.decodeString())
}

/** A cooldown after using an item. */
@Serializable
data class CooldownDef(
    /** How long, more than 0. */
    val seconds: Double,
    /** Items sharing a group share a cooldown: a namespaced id. Absent is the item's own kind. */
    val group: String? = null
)
