package dev.netherforge.format.item

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.ref.ResourceRef

/**
 * The rules for stacks of project items (`items/<id>/item.json`), shared by
 * every platform so the server and the tests' fake agree.
 *
 * A stack names its item by id ([ItemDef.item]) and carries that id, plus a
 * [hash] of the look it was given, in its persistent data container. The
 * definition fills in what a stack leaves out ([resolve]); when the
 * definition changes, a stack whose hash is stale is rewritten to the new
 * look the next time the server sees it ([restyle]).
 */
object ProjectItems {

    /**
     * [def] as a stack of the project item whose look is [look]: every field
     * [def] sets wins, the look fills in the rest, and the kind is always the
     * look's. [def]'s own `kind`, when it has one, has been checked to match.
     */
    fun resolve(def: ItemDef, look: ItemDef): ItemDef = ItemDef(
        kind = look.kind,
        item = def.item,
        count = def.count,
        name = def.name ?: look.name,
        lore = def.lore ?: look.lore,
        enchantments = def.enchantments ?: look.enchantments,
        glint = def.glint ?: look.glint,
        hideTooltip = def.hideTooltip ?: look.hideTooltip,
        unbreakable = def.unbreakable ?: look.unbreakable,
        damage = def.damage,
        itemModel = def.itemModel ?: look.itemModel,
        tooltipStyle = def.tooltipStyle ?: look.tooltipStyle,
        equipment = def.equipment ?: look.equipment,
        color = def.color ?: look.color,
        profile = def.profile ?: look.profile,
        maxStackSize = def.maxStackSize ?: look.maxStackSize,
        rarity = def.rarity ?: look.rarity,
        attributeModifiers = def.attributeModifiers ?: look.attributeModifiers,
        canBreak = def.canBreak ?: look.canBreak,
        canPlaceOn = def.canPlaceOn ?: look.canPlaceOn,
        hiddenTooltipComponents = def.hiddenTooltipComponents ?: look.hiddenTooltipComponents,
        food = def.food ?: look.food,
        cooldown = def.cooldown ?: look.cooldown,
        hold = def.hold ?: look.hold,
        useEffects = def.useEffects ?: look.useEffects,
        map = def.map,
        data = def.data
    )

    /**
     * A stack [old] of project item [item], rewritten to its definition's new
     * [look]: it takes the look whole, and keeps what belongs to the stack
     * itself, its count, damage and script data. Its enchantments stay too
     * when the look sets none, so a sword enchanted at a table keeps them.
     * (Anything else the platform keeps on a stack that this can't say,
     * another plugin's data, the platform carries over itself.)
     */
    fun restyle(old: ItemDef, item: ResourceRef, look: ItemDef): ItemDef = look.copy(
        item = item,
        count = old.count,
        damage = old.damage,
        map = old.map,
        data = old.data,
        enchantments = look.enchantments ?: old.enchantments
    )

    /**
     * What a stack records as the look it was given: a short hash of [look]'s
     * canonical JSON, the same on every platform. A stack recording another
     * one is out of date.
     */
    fun hash(look: ItemDef): String {
        var hash = FNV_OFFSET
        for (byte in CanonicalJson.write(ItemDef.serializer(), look).encodeToByteArray()) {
            hash = (hash xor (byte.toULong() and 0xFFu)) * FNV_PRIME
        }
        return hash.toString(16).padStart(16, '0')
    }

    private const val FNV_OFFSET: ULong = 0xcbf29ce484222325u
    private const val FNV_PRIME: ULong = 0x100000001b3u
}
