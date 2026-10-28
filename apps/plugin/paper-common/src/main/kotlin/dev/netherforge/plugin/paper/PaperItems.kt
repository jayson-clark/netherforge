package dev.netherforge.plugin.paper

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.item.AttributeModifierDef
import dev.netherforge.format.item.AttributeSlot
import dev.netherforge.format.item.CooldownDef
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.FoodDef
import dev.netherforge.format.item.HoldDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.item.TooltipComponent
import dev.netherforge.format.item.UseAnimation
import dev.netherforge.format.item.UseEffectsDef
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.ItemView
import dev.netherforge.plugin.platform.StackFilter
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.Equippable
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers
import io.papermc.paper.datacomponent.item.TooltipDisplay
import io.papermc.paper.datacomponent.item.UseCooldown
import io.papermc.paper.datacomponent.item.UseEffects
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.persistence.PersistentDataContainerView
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [ItemData] and Paper's `ItemStack`, both ways.
 *
 * [toStack] builds from the fields, over the opaque [ItemData.raw] when
 * there is one (the whole stack, Paper's own serialisation), so a read,
 * change, write keeps everything the fields can't say. [toItem] reports only
 * what is set, and attaches `raw` only when rebuilding from the fields alone
 * would lose something (checked by building it and comparing with
 * `isSimilar`), so most items carry none.
 *
 * A script's own data ([ItemDef.data]) is JSON text under `netherforge:data` in
 * the stack's persistent data container, so it travels with the item. Its
 * typed values (`Vec3`s, handles) are already tagged JSON by then (the
 * runtime's saved-data encoder), so this side never needs to know them.
 *
 * A stack of a project item ([ItemDef.item]) is resolved against the item's
 * look ([looks], from the runtime) when it's built, and stamped in its
 * persistent data container with the item's namespaced id (`shop:ruby` under
 * `netherforge:item`) and the look's hash (`netherforge:item_look`), so it's
 * still that item wherever it goes, and two projects' rubies never meet.
 *
 * References are written to the game in the project's [namespace]
 * (`itemModel: "ui/gem"` is the item model `shop:ui/gem`, a modifier with no
 * id is `shop:<attribute>_<index>`) and read back as a file writes them, the
 * project's own without the namespace ([reference]). A stack built over `raw` that already carries a stamp for the same
 * item keeps it: a script that reads a stale stack and writes it back hasn't
 * given it the new look, so [restyled] must still find it stale.
 *
 * `canBreak` and `canPlaceOn` are the game's block predicates, one per entry: the game's block or `#tag`
 * as it is, `*` the predicate that matches every block, and a project block the state it's held as now
 * ([blockStates], `{ blocks: "minecraft:note_block", state: { … } }`). Paper's predicate API can't say a
 * state, so the component is parsed from the game's own item syntax ([predicate]). What the lists said
 * is kept beside it under `netherforge:adventure`, with the states they resolved to, so the stack reads
 * back as it was written and [restyled] finds it stale when a block's state has moved.
 *
 * Item and enchantment types come from the live registries, never a table.
 * Names and lore are MiniMessage both ways, with the italics Minecraft puts
 * on custom names turned off.
 */
object PaperItems {
    private val mini = PaperText.mini

    private val DATA_KEY = NamespacedKey("netherforge", "data")
    private val ITEM_KEY = NamespacedKey("netherforge", "item")
    private val LOOK_KEY = NamespacedKey("netherforge", "item_look")
    private val ADVENTURE_KEY = NamespacedKey("netherforge", "adventure")

    /** Each project item's current look, by reference: the runtime's answer, set when it binds. Any thread may ask. */
    @Volatile
    var looks: (String) -> ItemLook? = { null }

    /** A restyled stack as its item's `render` handlers finish it: the runtime's ([PlatformEvents.itemRendered]). */
    var render: (ItemData) -> ItemData = { it }

    /** The state each project block is held as now, by the name the server knows it by: the runtime's answer, set when it binds. */
    @Volatile
    var blockStates: (String) -> String? = { null }

    /** The project's namespace, set when the runtime binds. */
    @Volatile
    var namespace: () -> String = { "" }

    /** [reference] as the game names it: in the project's namespace unless it names another. */
    fun resolve(reference: ResourceRef): ResourceKey? = reference.resolve(namespace())

    /** A key the game holds (`shop:ruby`, `minecraft:diamond`) as a file would write it: the project's own without the namespace. */
    private fun reference(key: String): ResourceRef = ResourceKey.parse(key)?.relativeTo(namespace()) ?: ResourceRef(key)

    private val items get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM)
    private val enchantments get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
    private val attributes get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)

    /** Vanilla's eating time, which `food` leaves out when it's this. */
    private const val DEFAULT_EAT_SECONDS = 1.6

    /**
     * How long a `hold` lasts: a day, as good as no end. (The game plays a consumable's sound and particles only
     * after a fifth of it, and the platform cancels the consume if anyone ever gets there: [isHold].)
     */
    private const val HOLD_SECONDS = 86_400f

    private fun key(id: String): NamespacedKey? = NamespacedKey.fromString(GameIds.normalize(id))

    /** The stack [item] describes, or null for an item type the server doesn't have. */
    fun toStack(item: ItemData): ItemStack? {
        val look = item.def.item?.let { looks(it.text) }
        val def = if (look != null) ProjectItems.resolve(item.def, look.def) else item.def
        val type = key(def.kind)?.let { items.get(it) } ?: return null
        val base = item.raw?.let(::restore)?.takeIf { it.type.key == type.key }
        val stack = base ?: type.createItemStack()
        stack.amount = (def.count ?: base?.amount ?: 1).coerceIn(1, 99)
        val meta = stack.itemMeta ?: return stack
        def.name?.let { meta.displayName(text(it)) }
        def.lore?.let { lines -> meta.lore(lines.map(::text)) }
        def.enchantments?.forEach { (id, level) ->
            key(id)?.let { enchantments.get(it) }?.let { meta.addEnchant(it, level, true) }
        }
        def.glint?.let { meta.setEnchantmentGlintOverride(it) }
        def.hideTooltip?.let { meta.isHideTooltip = it }
        def.unbreakable?.let { meta.isUnbreakable = it }
        def.damage?.let { damage -> (meta as? Damageable)?.damage = damage }
        def.itemModel?.let(::key)?.let { meta.itemModel = it }
        def.tooltipStyle?.let(::key)?.let { meta.tooltipStyle = it }
        def.color?.removePrefix("#")?.toIntOrNull(16)?.let { rgb ->
            val color = Color.fromRGB(rgb)
            when (meta) {
                is LeatherArmorMeta -> meta.setColor(color)
                is PotionMeta -> meta.color = color
                is MapMeta -> meta.color = color
            }
        }
        def.profile?.let { owner ->
            (meta as? SkullMeta)?.let { skull ->
                val uuid = runCatching { UUID.fromString(owner) }.getOrNull()
                skull.playerProfile = if (uuid != null) Bukkit.createProfile(uuid) else Bukkit.createProfile(owner)
            }
        }
        def.data?.let { data ->
            val container = meta.persistentDataContainer
            if (data.isEmpty()) {
                container.remove(DATA_KEY)
            } else {
                container.set(DATA_KEY, PersistentDataType.STRING, Json.encodeToString(JsonObject.serializer(), JsonObject(data)))
            }
        }
        if (def.canBreak != null || def.canPlaceOn != null) {
            // Left out is left alone, as for every field: the other list stays as the stack had it.
            val kept = adventureOf(meta.persistentDataContainer)
            val lists = Adventure(def.canBreak ?: kept?.canBreak, def.canPlaceOn ?: kept?.canPlaceOn)
            meta.persistentDataContainer.set(ADVENTURE_KEY, PersistentDataType.STRING, lists.stamp())
        }
        def.item?.let(::resolve)?.toString()?.let { id ->
            val container = meta.persistentDataContainer
            val stamped = container.get(ITEM_KEY, PersistentDataType.STRING) == id && container.has(LOOK_KEY)
            container.set(ITEM_KEY, PersistentDataType.STRING, id)
            if (!stamped) {
                if (look != null) container.set(LOOK_KEY, PersistentDataType.STRING, look.hash) else container.remove(LOOK_KEY)
            }
        }
        stack.itemMeta = meta
        components(def, stack)
        return stack
    }

    /**
     * [stack]'s tooltip with exactly [hidden] of the lines an item can hide hidden ([TooltipComponent.ALL]).
     * The display's other settings (the whole tooltip, a hidden component of another kind) stay as the stack
     * had them. Only the display changes: a hidden component still does what it does. A line the server's
     * version doesn't have was the validator's to say, and is skipped.
     */
    private fun hideTooltipLines(stack: ItemStack, hidden: List<TooltipComponent>) {
        val current = stack.getData(DataComponentTypes.TOOLTIP_DISPLAY)
        val kept = current?.hiddenComponents().orEmpty() - tooltipLines.values.toSet()
        stack.setData(
            DataComponentTypes.TOOLTIP_DISPLAY,
            TooltipDisplay.tooltipDisplay()
                .hideTooltip(current?.hideTooltip() ?: false)
                .hiddenComponents(kept + hidden.mapNotNull { tooltipLines[it.id] })
                .build()
        )
    }

    /** The lines [stack] hides of those an item can hide, or null for none. */
    private fun hiddenTooltipComponentsOf(stack: ItemStack): List<TooltipComponent>? {
        val hidden = stack.takeIf { it.isDataOverridden(DataComponentTypes.TOOLTIP_DISPLAY) }
            ?.getData(DataComponentTypes.TOOLTIP_DISPLAY)?.hiddenComponents().orEmpty()
        return TooltipComponent.entries.filter { tooltipLines[it.id] in hidden }.takeIf { it.isNotEmpty() }
    }

    /** Each line an item can hide ([TooltipComponent.ALL]) that this server has, by id: its data component. */
    private val tooltipLines: Map<String, DataComponentType> by lazy {
        val components = RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE)
        TooltipComponent.entries.mapNotNull { line -> key(line.key)?.let(components::get)?.let { line.id to it } }.toMap()
    }

    /** The fields that are data components with no `ItemMeta` side: written onto the stack itself. */
    private fun components(def: ItemDef, stack: ItemStack) {
        def.maxStackSize?.let { stack.setData(DataComponentTypes.MAX_STACK_SIZE, it) }
        def.map?.let { stack.setData(DataComponentTypes.MAP_ID, io.papermc.paper.datacomponent.item.MapId.mapId(it)) }
        def.rarity?.let { stack.setData(DataComponentTypes.RARITY, ItemRarity.valueOf(it.name)) }
        def.attributeModifiers?.let { modifiers ->
            val builder = ItemAttributeModifiers.itemAttributes()
            modifiers.forEachIndexed { index, modifier ->
                // An attribute the server doesn't have is skipped, as an unknown enchantment is.
                val attribute = key(modifier.attribute)?.let { attributes.get(it) } ?: return@forEachIndexed
                val id = key(modifier.id ?: AttributeModifierDef.defaultId(namespace(), modifier.attribute, index)) ?: return@forEachIndexed
                val group = slotGroup(modifier.slot)
                builder.addModifier(attribute, AttributeModifier(id, modifier.amount, modifier.operation.paper(), group), group)
            }
            stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build())
        }
        def.canBreak?.let { stack.setData(DataComponentTypes.CAN_BREAK, predicate(DataComponentTypes.CAN_BREAK, it)) }
        def.canPlaceOn?.let { stack.setData(DataComponentTypes.CAN_PLACE_ON, predicate(DataComponentTypes.CAN_PLACE_ON, it)) }
        def.hiddenTooltipComponents?.let { hideTooltipLines(stack, it) }
        def.food?.let { food ->
            stack.setData(
                DataComponentTypes.FOOD,
                FoodProperties.food().nutrition(food.nutrition).saturation(food.saturation.toFloat()).canAlwaysEat(
                    food.canAlwaysEat ?: false
                )
            )
            // Food alone doesn't make an item edible since 1.21.2: eating is `consumable`.
            val consumable = stack.getData(DataComponentTypes.CONSUMABLE)
            if (consumable == null || food.eatSeconds != null) {
                val builder = consumable?.toBuilder() ?: Consumable.consumable()
                food.eatSeconds?.let { builder.consumeSeconds(it.toFloat()) }
                stack.setData(DataComponentTypes.CONSUMABLE, builder.build())
            }
        }
        def.equipment?.let { equipment ->
            // An asset the game can't name is skipped, as an unknown enchantment is.
            val asset = key(equipment.asset) ?: return@let
            stack.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(equipment.slot.paper()).assetId(asset))
        }
        def.cooldown?.let { cooldown ->
            val builder = UseCooldown.useCooldown(cooldown.seconds.toFloat())
            cooldown.group?.let(::key)?.let { builder.cooldownGroup(it) }
            stack.setData(DataComponentTypes.USE_COOLDOWN, builder.build())
        }
        val hold = def.hold?.on == true
        if (hold) {
            // A use with no end and nothing heard, posed as it says: the consumable a right click starts and letting go stops.
            stack.setData(
                DataComponentTypes.CONSUMABLE,
                Consumable.consumable()
                    .consumeSeconds(HOLD_SECONDS)
                    .animation(animation(def.hold?.animation ?: UseAnimation.NONE))
                    .hasConsumeParticles(false)
                    .sound(Sound.INTENTIONALLY_EMPTY.key())
                    .build()
            )
        }
        if (hold || def.useEffects != null) {
            val effects = (def.useEffects ?: UseEffectsDef()).filled(if (hold) UseEffectsDef.HOLD else UseEffectsDef.GAME)
            stack.setData(
                DataComponentTypes.USE_EFFECTS,
                UseEffects.useEffects()
                    .speedMultiplier(effects.speedMultiplier!!.toFloat())
                    .canSprint(effects.canSprint!!)
                    .interactVibrations(effects.interactVibrations!!)
                    .build()
            )
        }
    }

    /**
     * The game's animation [animation] names (the format's names are the game's, lower case). One this server
     * doesn't have, which validation reports first, draws nothing.
     */
    private fun animation(animation: UseAnimation): ItemUseAnimation =
        ItemUseAnimation.entries.firstOrNull { it.name.lowercase() == animation.id } ?: ItemUseAnimation.NONE

    /** Whether [stack] is a `hold` item: the consumable [components] gives one, which nothing else does. */
    fun isHold(stack: ItemStack?): Boolean = holdOf(stack) != null

    /** [stack]'s hold, posed as it is, or null when it isn't a `hold` item. */
    private fun holdOf(stack: ItemStack?): HoldDef? {
        if (stack == null || !stack.isDataOverridden(DataComponentTypes.CONSUMABLE)) return null
        val consumable = stack.getData(DataComponentTypes.CONSUMABLE) ?: return null
        if (consumable.consumeSeconds() != HOLD_SECONDS || consumable.hasConsumeParticles() || consumable.consumeEffects().isNotEmpty()) {
            return null
        }
        return HoldDef(animation = UseAnimation(consumable.animation().name.lowercase()).takeIf { it != UseAnimation.NONE })
    }

    /**
     * The `useEffects` that build [stack]'s component, with the fields that are [hold]'s or the game's default left
     * out: nothing for a hold that moves as holds do, `{}` for an item that sets the game's own.
     */
    private fun useEffectsOf(stack: ItemStack, hold: Boolean): UseEffectsDef? {
        if (!stack.isDataOverridden(DataComponentTypes.USE_EFFECTS)) return null
        val effects = stack.getData(DataComponentTypes.USE_EFFECTS) ?: return null
        val defaults = if (hold) UseEffectsDef.HOLD else UseEffectsDef.GAME
        val def = UseEffectsDef(
            speedMultiplier = decimal(effects.speedMultiplier()).takeIf { it != defaults.speedMultiplier },
            canSprint = effects.canSprint().takeIf { it != defaults.canSprint },
            interactVibrations = effects.interactVibrations().takeIf { it != defaults.interactVibrations }
        )
        return def.takeIf { !hold || it != UseEffectsDef() }
    }

    /** Each list's predicates, by the component and the game's text for them: the same lists are parsed once. */
    private val predicates = ConcurrentHashMap<String, ItemAdventurePredicate>()

    /**
     * The component for [blocks] (an item's `canBreak` or `canPlaceOn`): a predicate per entry the game can
     * say, parsed from the game's item syntax, which unlike Paper's API takes block states. An entry the
     * server can't hold (a project block with no state, a tag it doesn't have) matches nothing; with none
     * left, the item breaks or goes on nothing.
     */
    private fun predicate(type: DataComponentType.Valued<ItemAdventurePredicate>, blocks: List<String>): ItemAdventurePredicate {
        val entries = blocks.mapNotNull(::predicateText)
        if (entries.isEmpty()) return ItemAdventurePredicate.itemAdventurePredicate().build()
        val component = type.key().value()
        val text = entries.joinToString(",", "[", "]")
        predicates["$component=$text"]?.let { return it }
        val parsed = runCatching { Bukkit.getItemFactory().createItemStack("${GameIds.NAMESPACE}:stone[$component=$text]").getData(type) }
            .getOrNull()
            // One entry the server refuses (a tag it doesn't have) shouldn't cost the others theirs.
            ?: return entries.mapNotNull { single(type, component, it) }
                .fold(ItemAdventurePredicate.itemAdventurePredicate()) { builder, it -> builder.addPredicates(it.predicates()) }
                .build()
        predicates["$component=$text"] = parsed
        return parsed
    }

    private fun single(type: DataComponentType.Valued<ItemAdventurePredicate>, component: String, entry: String): ItemAdventurePredicate? =
        predicates.getOrPut("$component=[$entry]") {
            runCatching { Bukkit.getItemFactory().createItemStack("${GameIds.NAMESPACE}:stone[$component=[$entry]]").getData(type) }
                .getOrNull() ?: return null
        }

    /** One entry as the game's block predicate: null for a project block the server holds in no state. */
    private fun predicateText(entry: String): String? {
        val kind = RefKind.ADVENTURE_BLOCK
        return when {
            entry == kind.any -> "{}"
            entry.startsWith("#") -> "{blocks:\"#${GameIds.normalize(entry.removePrefix("#"))}\"}"
            kind.isGame(entry) -> "{blocks:\"${GameIds.normalize(entry)}\"}"
            else -> {
                val state = blockStates(entry)?.let(BlockState::parse) ?: return null
                val properties = state.properties.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:\"${it.value}\"" }
                "{blocks:\"${state.id}\",state:{$properties}}"
            }
        }
    }

    /** What an item's lists of blocks said, and the states its project blocks resolved to then ([states]). */
    private data class Adventure(val canBreak: List<String>?, val canPlaceOn: List<String>?, val states: String? = null) {
        /** The states its project blocks are held as now, one text for all of them, to tell a stale stack by. */
        fun statesNow(): String = (canBreak.orEmpty() + canPlaceOn.orEmpty())
            .filter { !it.startsWith("#") && !RefKind.ADVENTURE_BLOCK.isGame(it) }
            .distinct()
            .joinToString(";") { "$it=${blockStates(it) ?: ""}" }

        fun stamp(): String = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    canBreak?.let { put("can_break", JsonArray(it.map(::JsonPrimitive))) }
                    canPlaceOn?.let { put("can_place_on", JsonArray(it.map(::JsonPrimitive))) }
                    put("states", JsonPrimitive(statesNow()))
                }
            )
        )
    }

    /** The lists stamped on a stack, or null for none (or a stamp that isn't one). */
    private fun adventureOf(container: PersistentDataContainerView): Adventure? {
        val text = container.get(ADVENTURE_KEY, PersistentDataType.STRING) ?: return null
        val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun list(key: String) = (json[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return Adventure(list("can_break"), list("can_place_on"), (json["states"] as? JsonPrimitive)?.content)
    }

    /**
     * [stack] with its `canBreak` and `canPlaceOn` written for the states its project blocks are held as
     * now, or null when they're current (or it has none).
     */
    private fun readapted(stack: ItemStack): ItemStack? {
        val lists = adventureOf(stack.persistentDataContainer) ?: return null
        if (lists.states == lists.statesNow()) return null
        val fresh = stack.clone()
        lists.canBreak?.let { fresh.setData(DataComponentTypes.CAN_BREAK, predicate(DataComponentTypes.CAN_BREAK, it)) }
        lists.canPlaceOn?.let { fresh.setData(DataComponentTypes.CAN_PLACE_ON, predicate(DataComponentTypes.CAN_PLACE_ON, it)) }
        fresh.editPersistentDataContainer { it.set(ADVENTURE_KEY, PersistentDataType.STRING, lists.stamp()) }
        return fresh
    }

    private fun slotGroup(slot: AttributeSlot?): EquipmentSlotGroup =
        slot?.let { EquipmentSlotGroup.getByName(it.groupName) } ?: EquipmentSlotGroup.ANY

    /** Paper's name for a slot group: ours spell `main_hand` and `off_hand` like equipment slots, vanilla's don't. */
    private val AttributeSlot.groupName: String get() = name.lowercase().replace("_", "")

    /** The modifiers a stack carries in place of its kind's own, ids left out where they're the default. */
    private fun modifiersOf(stack: ItemStack): List<AttributeModifierDef>? {
        if (!stack.isDataOverridden(DataComponentTypes.ATTRIBUTE_MODIFIERS)) return null
        val entries = stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS)?.modifiers() ?: return emptyList()
        return entries.mapIndexed { index, entry ->
            val attribute = attributes.getKey(entry.attribute())?.toString() ?: entry.attribute().toString()
            val id = entry.modifier().key.toString()
            AttributeModifierDef(
                id = id.takeIf { it != AttributeModifierDef.defaultId(namespace(), attribute, index) },
                attribute = attribute,
                amount = entry.modifier().amount,
                operation = entry.modifier().operation.netherforge(),
                slot = AttributeSlot.entries.firstOrNull { it.groupName == entry.getGroup().toString() }
                    ?.takeIf { it != AttributeSlot.ANY }
            )
        }
    }

    private fun blocksOf(stack: ItemStack, type: DataComponentType.Valued<ItemAdventurePredicate>): List<String>? {
        if (!stack.isDataOverridden(type)) return null
        return stack.getData(type)?.predicates().orEmpty().flatMap { predicate ->
            predicate.blocks()?.values().orEmpty().map { it.key().toString() }
        }
    }

    private fun foodOf(stack: ItemStack): FoodDef? {
        if (!stack.isDataOverridden(DataComponentTypes.FOOD)) return null
        val food = stack.getData(DataComponentTypes.FOOD) ?: return null
        val seconds = stack.getData(DataComponentTypes.CONSUMABLE)?.consumeSeconds()?.let(::decimal)
        return FoodDef(
            nutrition = food.nutrition(),
            saturation = decimal(food.saturation()),
            canAlwaysEat = food.canAlwaysEat().takeIf { it },
            eatSeconds = seconds?.takeIf { it != DEFAULT_EAT_SECONDS }
        )
    }

    private fun equipmentOf(stack: ItemStack): EquipmentDef? {
        if (!stack.isDataOverridden(DataComponentTypes.EQUIPPABLE)) return null
        val equippable = stack.getData(DataComponentTypes.EQUIPPABLE) ?: return null
        val asset = equippable.assetId() ?: return null
        val slot = EquipSlot.entries.firstOrNull { it.paper() == equippable.slot() } ?: return null
        return EquipmentDef(reference(asset.asString()), slot)
    }

    private fun EquipSlot.paper(): EquipmentSlot = EquipmentSlot.valueOf(name)

    private fun cooldownOf(stack: ItemStack): CooldownDef? {
        if (!stack.isDataOverridden(DataComponentTypes.USE_COOLDOWN)) return null
        val cooldown = stack.getData(DataComponentTypes.USE_COOLDOWN) ?: return null
        return CooldownDef(decimal(cooldown.seconds()), cooldown.cooldownGroup()?.asString())
    }

    /** A component's float as the decimal it was written as (`0.8`, not `0.800000011920929`). */
    private fun decimal(value: Float): Double = value.toString().toDouble()

    /** What's in a slot, or null for an empty one. */
    /**
     * [toItem] of the stack in a place ([where]: an inventory's slot, an
     * entity's equipment slot), remembered: reading a stack in full is the
     * expensive part of every inventory call, and most stacks are what they
     * were the last time. Answered from the last read while the stack is still
     * the same (its components and count; a copy of it is kept to compare
     * with); a read only depends on the stack and the project's namespace, so
     * [bind][PaperPlatform.bind] forgets them all.
     */
    fun toItem(stack: ItemStack?, where: Any): ItemData? {
        if (stack == null || stack.isEmpty) {
            reads.remove(where)
            return null
        }
        val last = reads[where]
        if (last != null && last.stack.amount == stack.amount && last.stack.isSimilar(stack)) return last.item
        val item = toItem(stack) ?: return null
        reads[where] = Read(stack.clone(), item)
        return item
    }

    private class Read(val stack: ItemStack, val item: ItemData)

    /** The last read of each place, the most recently used last; at most [MAX_READS]. */
    private val reads = object : LinkedHashMap<Any, Read>(MAX_READS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Read>) = size > MAX_READS
    }

    /** Forgets every remembered read: the project (its namespace) changed. */
    fun forgetReads() = reads.clear()

    private const val MAX_READS = 4096

    fun toItem(stack: ItemStack?): ItemData? {
        if (stack == null || stack.isEmpty) return null
        val def = describe(stack)
        val lossless = !stack.hasItemMeta() || runCatching { toStack(ItemData(def))?.isSimilar(stack) == true }.getOrDefault(false)
        val look = def.item?.let { stack.persistentDataContainer.get(LOOK_KEY, PersistentDataType.STRING) }
        return ItemData(def, if (lossless) null else Base64.getEncoder().encodeToString(stack.serializeAsBytes()), look)
    }

    /**
     * Whether [stack] could be a stack of [filter]'s kind and project item, by
     * its type and its stamp alone: what's cheap to read, before [toItem]
     * reads it all.
     */
    fun admits(stack: ItemStack, filter: StackFilter): Boolean {
        if (filter.kind != null && stack.type.key.toString() != filter.kind) return false
        if (filter.item == null) return true
        return projectItem(stack)?.let(::reference)?.text == filter.item
    }

    /** A copy of [stack] with [view]'s name and lore in place of its own: how it looks to one player. */
    fun viewed(stack: ItemStack, view: ItemView): ItemStack {
        val shown = stack.clone()
        shown.editMeta { meta ->
            meta.displayName(view.name?.let(::text))
            meta.lore(view.lore?.map(::text))
        }
        return shown
    }

    /** The project item [stack] is a stack of (the namespaced id it carries: `shop:ruby`), or null; reads only the stamp. */
    fun projectItem(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty || !stack.hasItemMeta()) return null
        return stack.persistentDataContainer.get(ITEM_KEY, PersistentDataType.STRING)
    }

    /**
     * [stack] rewritten to its project item's current look, or null when it
     * needs nothing: it's no project item's, its item is gone, or its look is
     * current. Reading the stamp is all a current stack costs. The new stack
     * keeps the old one's count, damage and script data
     * ([ProjectItems.restyle]), and everything else in its persistent data
     * container (another plugin's data).
     */
    fun restyled(stack: ItemStack): ItemStack? {
        if (stack.isEmpty || !stack.hasItemMeta()) return null
        val stamps = stack.persistentDataContainer
        val id = stamps.get(ITEM_KEY, PersistentDataType.STRING)
        val look = id?.let(looks)
        if (look == null || stamps.get(LOOK_KEY, PersistentDataType.STRING) == look.hash) return readapted(stack)
        val fresh = toStack(render(ItemData(ProjectItems.restyle(describe(stack), reference(id), look.def)))) ?: return null
        fresh.editPersistentDataContainer { stamps.copyTo(it, false) }
        // The look's lists were just written; any it doesn't have, the stack kept, for the states of now.
        return readapted(fresh) ?: fresh
    }

    /** The script data a stack carries, or null for none (or text that isn't a JSON object). */
    private fun scriptData(meta: ItemMeta?): Map<String, JsonElement>? {
        val text = meta?.persistentDataContainer?.get(DATA_KEY, PersistentDataType.STRING) ?: return null
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun describe(stack: ItemStack): ItemDef {
        val meta = stack.itemMeta
        val adventure = meta?.persistentDataContainer?.let(::adventureOf)
        return ItemDef(
            kind = stack.type.key.toString(),
            count = stack.amount,
            name = meta?.takeIf { it.hasDisplayName() }?.displayName()?.let(mini::serialize),
            lore = meta?.lore()?.takeIf { it.isNotEmpty() }?.map(mini::serialize),
            enchantments = meta?.enchants?.takeIf { it.isNotEmpty() }?.entries?.associate { (it, level) -> it.key.toString() to level },
            glint = meta?.takeIf { it.hasEnchantmentGlintOverride() }?.enchantmentGlintOverride,
            hideTooltip = meta?.isHideTooltip?.takeIf { it },
            unbreakable = meta?.isUnbreakable?.takeIf { it },
            damage = (meta as? Damageable)?.takeIf { it.hasDamage() }?.damage,
            itemModel = meta?.takeIf { it.hasItemModel() }?.itemModel?.toString()?.let(::reference),
            tooltipStyle = meta?.takeIf { it.hasTooltipStyle() }?.tooltipStyle?.toString()?.let(::reference),
            color = when (meta) {
                is LeatherArmorMeta -> meta.color.asRGB()
                is PotionMeta -> meta.takeIf { it.hasColor() }?.color?.asRGB()
                is MapMeta -> meta.takeIf { it.hasColor() }?.color?.asRGB()
                else -> null
            }?.let { "#%06x".format(it and 0xFFFFFF) },
            profile = (meta as? SkullMeta)?.playerProfile?.let { it.name ?: it.id?.toString() },
            maxStackSize = stack.takeIf { it.isDataOverridden(DataComponentTypes.MAX_STACK_SIZE) }
                ?.getData(DataComponentTypes.MAX_STACK_SIZE),
            rarity = stack.takeIf { it.isDataOverridden(DataComponentTypes.RARITY) }?.getData(DataComponentTypes.RARITY)
                ?.let { rarity -> dev.netherforge.format.item.ItemRarity.valueOf(rarity.name) },
            map = stack.takeIf { it.isDataOverridden(DataComponentTypes.MAP_ID) }?.getData(DataComponentTypes.MAP_ID)?.id(),
            attributeModifiers = modifiersOf(stack),
            canBreak = adventure?.canBreak ?: blocksOf(stack, DataComponentTypes.CAN_BREAK),
            canPlaceOn = adventure?.canPlaceOn ?: blocksOf(stack, DataComponentTypes.CAN_PLACE_ON),
            hiddenTooltipComponents = hiddenTooltipComponentsOf(stack),
            food = foodOf(stack),
            cooldown = cooldownOf(stack),
            hold = holdOf(stack),
            useEffects = useEffectsOf(stack, holdOf(stack) != null),
            equipment = equipmentOf(stack),
            data = scriptData(meta),
            item = meta?.persistentDataContainer?.get(ITEM_KEY, PersistentDataType.STRING)?.let(::reference)
        )
    }

    /** A reference to a pack's entry (`ui/gem`) as the key the client resolves (`shop:ui/gem`). */
    private fun key(reference: ResourceRef): NamespacedKey? =
        resolve(reference)?.let { runCatching { NamespacedKey(it.namespace, it.path) }.getOrNull() }

    private fun restore(data: String): ItemStack? = runCatching { ItemStack.deserializeBytes(Base64.getDecoder().decode(data)) }.getOrNull()

    fun text(miniMessage: String): Component =
        mini.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
}
