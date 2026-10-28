package dev.netherforge.format.item

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `items/<id>/item.json`: a project item, an item kind of the project's own.
 *
 * It holds an item's look and behaviour, the fields an [ItemDef] has that say
 * what the item _is_ (its kind, name, lore, model, components), but none that
 * say what one stack holds (`count`, `damage`, `data`): those belong to each
 * stack. A stack made from it (`{ "item": "ruby" }` anywhere an item goes, or
 * `nf.items.create("ruby")`) carries the id, so the server can tell it apart
 * from any other stack of the same kind, and rewrites its look when the
 * definition changes ([ProjectItems.restyle]).
 *
 * Its one [script] runs once for the item (not per stack), and hears what
 * players do with any stack of it.
 */
@Serializable
data class ItemFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The item type it's built on: `minecraft:paper`. */
    val kind: String,
    /** Display name, MiniMessage. */
    @MiniMessage val name: String? = null,
    /** Tooltip lines under the name, each MiniMessage. */
    @MiniMessage val lore: List<String>? = null,
    /** Enchantment id → level. */
    val enchantments: Map<String, Int>? = null,
    /** The enchanted shimmer without an enchantment. */
    val glint: Boolean? = null,
    /** Hide the whole tooltip. */
    val hideTooltip: Boolean? = null,
    val unbreakable: Boolean? = null,
    /** `<pack>/<key>`: its look, from one of the project's resource packs. */
    @Ref(RefKind.ITEM_MODEL) val itemModel: ResourceRef? = null,
    /** `<pack>/<key>`: its tooltip frame, from one of the project's resource packs. */
    @Ref(RefKind.TOOLTIP) val tooltipStyle: ResourceRef? = null,
    /** Worn as armour or equipment, drawn as a pack's equipment look. */
    val equipment: EquipmentDef? = null,
    /** `#RRGGBB`, for dyed leather, potions, maps. */
    val color: String? = null,
    /** Whose head, for `minecraft:player_head`. */
    val profile: String? = null,
    /** How many fit in one stack, 1–99. */
    val maxStackSize: Int? = null,
    /** The colour of its name. */
    val rarity: ItemRarity? = null,
    /** Attribute changes while it's worn or held. */
    val attributeModifiers: List<AttributeModifierDef>? = null,
    /** Blocks it can break in adventure mode: project blocks, the game's, `#tags` of blocks, or `*`. */
    @Ref(RefKind.ADVENTURE_BLOCK) val canBreak: List<String>? = null,
    /** Blocks it can be placed on in adventure mode, written as [canBreak]'s. */
    @Ref(RefKind.ADVENTURE_BLOCK) val canPlaceOn: List<String>? = null,
    /** The tooltip lines to hide, by the component that draws them: `unbreakable`, `attribute_modifiers`, `can_break`. */
    val hiddenTooltipComponents: List<TooltipComponent>? = null,
    /** Eating it. */
    val food: FoodDef? = null,
    /** A cooldown after using it. */
    val cooldown: CooldownDef? = null,
    /**
     * Held down with no end, until the player lets go (`player_stop_using_item`), nothing used up: `true` draws
     * nothing, `{ "animation": "trident" }` poses the player.
     */
    val hold: HoldDef? = null,
    /** How a player moves while using it: `speedMultiplier`, `canSprint`, `interactVibrations`. */
    val useEffects: UseEffectsDef? = null,
    /**
     * A bow draws and shoots with no ammo: it needs no arrow in the inventory, and uses none up. The arrow is
     * the server's own, and `entity_shoot_bow` hears the shot, so a script can cancel it or hand out its own
     * ammo. Only a bow (`minecraft:bow`) can.
     */
    val drawWithoutAmmo: Boolean? = null,
    /** `<id>`: a block of the project's that a player places by right-clicking with it (one is used up each time, but in creative). */
    @Ref(RefKind.BLOCK) val block: ResourceRef? = null,
    /**
     * The project's own facts about it, any JSON (`{ "damage": 12, "class": "melee" }`): what scripts read with
     * `ProjectItem:properties()`, so data that belongs to the item lives in its file rather than in a table a script keeps.
     * Nothing else reads it.
     */
    val properties: Map<String, JsonElement>? = null,
    /** The item's one script, with `this` the item: it hears what players do with every stack of it. */
    val script: ScriptDef? = null
) {
    /** The look every stack of it takes: the definition as an item, with no count and no id. */
    fun look(): ItemDef = ItemDef(
        kind = kind,
        name = name,
        lore = lore,
        enchantments = enchantments,
        glint = glint,
        hideTooltip = hideTooltip,
        unbreakable = unbreakable,
        itemModel = itemModel,
        tooltipStyle = tooltipStyle,
        equipment = equipment,
        color = color,
        profile = profile,
        maxStackSize = maxStackSize,
        rarity = rarity,
        attributeModifiers = attributeModifiers,
        canBreak = canBreak,
        canPlaceOn = canPlaceOn,
        hiddenTooltipComponents = hiddenTooltipComponents,
        food = food,
        cooldown = cooldown,
        hold = hold,
        useEffects = useEffects
    )

    companion object {
        const val FILE_NAME = "item.json"
        const val SCHEMA = "../../.netherforge/schema/item.schema.json"

        /** [ItemDef]'s fields a definition doesn't have: they belong to each stack, or name the definition itself. */
        val STACK_FIELDS = setOf("item", "count", "damage", "map", "data")
    }
}
