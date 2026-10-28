package dev.netherforge.plugin.item

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.Severity
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.AttributeModifierDef
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.item.AttributeSlot
import dev.netherforge.format.item.CooldownDef
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.FoodDef
import dev.netherforge.format.item.HoldDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemRarity
import dev.netherforge.format.item.TooltipComponent
import dev.netherforge.format.item.UseAnimation
import dev.netherforge.format.item.UseEffectsDef
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.validate.Rules
import dev.netherforge.plugin.binding.luaSpelling
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ItemData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Items as Lua tables, both ways.
 *
 * The table is [ItemDef] with Lua's spelling (`hide_tooltip`, `item_model`,
 * `tooltip_style`; `data` is the script's own data, which may hold what a
 * `data()` table may: [ItemDef.data] keeps it as the Lua↔JSON codec's
 * tagged JSON, which the caller encodes and [write] hands back to be typed again),
 * plus `raw`: whatever else the stack carries, opaque, so a script that reads
 * an item, changes its count and writes it back keeps the book's pages and the
 * other plugin's data it never knew about. What a script reads is exactly what
 * it could write.
 *
 * Reading is strict, because a typo is an error in this API: an unknown field,
 * a field of the wrong type, or an item the server doesn't have fails at the
 * script's line rather than leaving a slot mysteriously empty.
 */
object LuaItems {
    private val FIELDS = listOf(
        "kind", "item", "count", "name", "lore", "enchantments", "glint", "hide_tooltip", "unbreakable", "damage",
        "item_model", "tooltip_style", "equipment", "color", "profile", "max_stack_size", "rarity", "attribute_modifiers",
        "can_break", "can_place_on", "hidden_tooltip_components", "food", "cooldown", "hold", "use_effects", "map", "data", "raw"
    )
    private val MODIFIER_FIELDS = listOf("id", "attribute", "amount", "operation", "slot")
    private val FOOD_FIELDS = listOf("nutrition", "saturation", "can_always_eat", "eat_seconds")
    private val COOLDOWN_FIELDS = listOf("seconds", "group")
    private val USE_EFFECTS_FIELDS = listOf("speed_multiplier", "can_sprint", "interact_vibrations")
    private val EQUIPMENT_FIELDS = listOf("asset", "slot")
    private val HOLD_FIELDS = listOf("animation")

    /** What an item match ([read] with `partial`) can't say. */
    private val NOT_MATCHED = setOf("count", "raw")

    private val INT_RANGE = Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()

    /**
     * An item table (as JSON, the way the host reads Lua values) to an item.
     * Its names (`item`, `item_model`, `tooltip_style`) are as the server
     * knows them, already checked (`PackageNames.item`). [projectItem]
     * answers a project item's look by id, or null when the project has no
     * such item.
     *
     * A table naming a project item (`item`) may leave `kind` out: it's the
     * item's (the platform fills in what the table leaves out when it builds
     * the stack). A [partial] item is an item match ([ItemMatch]): `kind` may
     * be left out (it reads as `""`), and `count` and `raw` have no place in it.
     *
     * [projectBlock] says whether the project has a block by the name the server knows it by (`ruby_ore`,
     * `library:gem_ore`): what `can_break` and `can_place_on` name besides the game's blocks and tags.
     */
    fun read(
        value: JsonElement,
        game: GameData?,
        partial: Boolean = false,
        projectBlock: (name: String) -> Boolean = { false },
        projectItem: (id: String) -> ItemDef? = { null }
    ): ItemData {
        val table = value as? JsonObject ?: throw LuaApiException("an item is a table like { kind = \"minecraft:bread\", count = 4 }")
        for (key in table.keys) {
            if (key !in FIELDS) throw LuaApiException("items have no field \"$key\" (fields: ${FIELDS.joinToString()})")
            if (partial && key in NOT_MATCHED) {
                throw LuaApiException("a match has no $key: it matches stacks whatever their $key")
            }
        }
        val fields = Fields(table, "item")
        fun string(key: String) = fields.string(key)
        fun integer(key: String) = fields.integer(key)
        fun boolean(key: String) = fields.boolean(key)

        val lore = when (val it = table["lore"]) {
            null, JsonNull -> null
            // An empty Lua table reads as an object: the empty list.
            is JsonObject -> if (it.isEmpty()) emptyList() else throw LuaApiException("item.lore must be a list of strings")
            is JsonArray -> it.map { line ->
                (line as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw LuaApiException("item.lore must be a list of strings")
            }
            else -> throw LuaApiException("item.lore must be a list of strings")
        }
        val enchantments = when (val it = table["enchantments"]) {
            null, JsonNull -> null
            is JsonObject -> it.mapValues { (name, level) ->
                (level as? JsonPrimitive)?.doubleOrNull?.takeIf { d -> !level.isString && d == Math.floor(d) }?.toInt()
                    ?: throw LuaApiException("item.enchantments[\"$name\"] must be a whole-number level")
            }
            else -> throw LuaApiException("item.enchantments must be a table of enchantment id = level")
        }

        val data = when (val it = table["data"]) {
            null, JsonNull -> null
            // An empty table clears the stack's data; leaving the field out leaves it alone.
            is JsonObject -> it
            else -> throw LuaApiException("item.data must be a table of your own values, like { coin = true }")
        }

        val modifiers = list(table["attribute_modifiers"], "item.attribute_modifiers", "a list of { attribute, amount, operation }") {
                index,
                entry
            ->
            val at = "item.attribute_modifiers[${index + 1}]"
            val modifier = Fields(entry as? JsonObject ?: throw LuaApiException("$at must be a table"), at, MODIFIER_FIELDS)
            AttributeModifierDef(
                id = modifier.string("id"),
                attribute = modifier.string("attribute") ?: throw LuaApiException("$at needs an attribute"),
                amount = modifier.number("amount") ?: throw LuaApiException("$at needs an amount"),
                operation = modifier.enum("operation", AttributeOperation.entries) ?: throw LuaApiException("$at needs an operation"),
                slot = modifier.enum("slot", AttributeSlot.entries)
            )
        }
        val food = table["food"]?.takeIf { it != JsonNull }?.let {
            val food = Fields(it as? JsonObject ?: throw LuaApiException("item.food must be a table"), "item.food", FOOD_FIELDS)
            FoodDef(
                nutrition = food.integer("nutrition") ?: throw LuaApiException("item.food needs a nutrition"),
                saturation = food.number("saturation") ?: throw LuaApiException("item.food needs a saturation"),
                canAlwaysEat = food.boolean("can_always_eat"),
                eatSeconds = food.number("eat_seconds")
            )
        }
        val cooldown = table["cooldown"]?.takeIf { it != JsonNull }?.let {
            val cooldown =
                Fields(it as? JsonObject ?: throw LuaApiException("item.cooldown must be a table"), "item.cooldown", COOLDOWN_FIELDS)
            CooldownDef(
                seconds = cooldown.number("seconds") ?: throw LuaApiException("item.cooldown needs seconds"),
                group = cooldown.string("group")
            )
        }
        val useEffects = table["use_effects"]?.takeIf { it != JsonNull }?.let {
            val table = it as? JsonObject ?: throw LuaApiException("item.use_effects must be a table")
            val effects = Fields(table, "item.use_effects", USE_EFFECTS_FIELDS)
            UseEffectsDef(
                speedMultiplier = effects.number("speed_multiplier"),
                canSprint = effects.boolean("can_sprint"),
                interactVibrations = effects.boolean("interact_vibrations")
            )
        }
        // true, or { animation = "trident" }: which names are animations is the rules' to say (below), as in a file.
        val hold = table["hold"]?.takeIf { it != JsonNull }?.let {
            when {
                it is JsonPrimitive && !it.isString && it.booleanOrNull != null -> HoldDef(on = it.booleanOrNull!!)
                // `none` is a plain hold, which reads back `true` as the server's does.
                it is JsonObject -> HoldDef(
                    animation = Fields(it, "item.hold", HOLD_FIELDS).string("animation")?.let(::UseAnimation)?.takeIf {
                        it !=
                            UseAnimation.NONE
                    }
                )
                else -> throw LuaApiException("item.hold must be a boolean or a table")
            }
        }
        val equipment = table["equipment"]?.takeIf { it != JsonNull }?.let {
            val equipment =
                Fields(it as? JsonObject ?: throw LuaApiException("item.equipment must be a table"), "item.equipment", EQUIPMENT_FIELDS)
            EquipmentDef(
                asset = equipment.string("asset")?.let(::ResourceRef) ?: throw LuaApiException("item.equipment needs an asset"),
                slot = equipment.enum("slot", EquipSlot.entries) ?: throw LuaApiException("item.equipment needs a slot")
            )
        }
        fun blocks(key: String) = list(table[key], "item.$key", "a list of block kinds") { _, entry ->
            (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw LuaApiException("item.$key must be a list of block kinds")
        }

        val item = string("item")
        val def = ItemDef(
            kind = string("kind") ?: if (partial || item != null) {
                ""
            } else {
                throw LuaApiException("an item needs a kind, like \"minecraft:bread\", or an item: one of the project's own")
            },
            item = item?.let(::ResourceRef),
            count = integer("count"),
            name = string("name"),
            lore = lore,
            enchantments = enchantments,
            glint = boolean("glint"),
            hideTooltip = boolean("hide_tooltip"),
            unbreakable = boolean("unbreakable"),
            damage = integer("damage"),
            itemModel = string("item_model")?.let(::ResourceRef),
            tooltipStyle = string("tooltip_style")?.let(::ResourceRef),
            equipment = equipment,
            color = string("color"),
            profile = string("profile"),
            maxStackSize = integer("max_stack_size"),
            map = integer("map"),
            rarity = fields.enum("rarity", ItemRarity.entries),
            attributeModifiers = modifiers,
            canBreak = blocks("can_break"),
            canPlaceOn = blocks("can_place_on"),
            // Which strings are lines is the rules' to say (below), as in a file.
            hiddenTooltipComponents = list(
                table["hidden_tooltip_components"],
                "item.hidden_tooltip_components",
                "a list of tooltip lines"
            ) {
                    _,
                    entry
                ->
                (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::TooltipComponent)
                    ?: throw LuaApiException("item.hidden_tooltip_components must be a list of strings")
            },
            food = food,
            cooldown = cooldown,
            hold = hold,
            useEffects = useEffects,
            data = data
        )
        val sink = ProblemSink("item")
        Rules.item(def, "item", sink, game)
        // A match without a kind matches every kind.
        val kindless = partial && def.kind.isEmpty()
        sink.problems.firstOrNull { it.severity == Severity.ERROR && !(kindless && it.path == "item.kind") }
            ?.let { throw LuaApiException(luaSpelling(it.message)) }
        for ((key, blocks) in listOf("can_break" to def.canBreak, "can_place_on" to def.canPlaceOn)) {
            blocks?.forEachIndexed { index, block -> checkBlock(block, "item.$key[${index + 1}]", game, projectBlock) }
        }
        if (item != null) {
            val look = projectItem(item) ?: throw LuaApiException("item.item: there's no item \"$item\"")
            if (def.kind.isNotEmpty() && GameIds.normalize(def.kind) != GameIds.normalize(look.kind)) {
                throw LuaApiException(
                    "item.kind: item \"$item\" is a ${GameIds.normalize(look.kind)}, not a ${GameIds.normalize(def.kind)} (leave kind out)"
                )
            }
        }
        return ItemData(def, string("raw"))
    }

    /**
     * What [Rules.item] leaves to whoever knows the project: a project block must be one of its blocks, and
     * a `#tag` in another namespace than the game's one the server has (the start-up datapack gave it the
     * project's datapacks' tags).
     */
    private fun checkBlock(block: String, where: String, game: GameData?, projectBlock: (String) -> Boolean) {
        val kind = RefKind.ADVENTURE_BLOCK
        when {
            kind.isGame(block) -> {}
            kind.isDatapackTag(block) -> {
                val id = block.removePrefix("#")
                if (!GameIds.isValid(id)) throw LuaApiException("$where: \"$block\" isn't a tag: write #<namespace>:<path>")
                if (game?.registry(RegistryKey.BLOCK) != null && game.tag(RegistryKey.BLOCK, GameIds.normalize(id)) == null) {
                    throw LuaApiException("$where: the server has no block tag \"$block\" (a project datapack's data/<ns>/tags/block/)")
                }
            }
            !projectBlock(block) -> throw LuaApiException(
                "$where: there's no block \"$block\" (the game's are written \"${GameIds.NAMESPACE}:${block.substringAfter(':')}\", " +
                    "a tag \"#${GameIds.NAMESPACE}:…\", any block \"${kind.any}\")"
            )
        }
    }

    /** An item as the table a script gets: only the fields that are set. */
    fun write(item: ItemData): Map<String, Any?> {
        val def = item.def
        return buildMap {
            put("kind", def.kind)
            def.item?.let { put("item", it.text) }
            put("count", def.count ?: 1)
            def.name?.let { put("name", it) }
            def.lore?.let { put("lore", it) }
            def.enchantments?.let { put("enchantments", it) }
            def.glint?.let { put("glint", it) }
            def.hideTooltip?.let { put("hide_tooltip", it) }
            def.unbreakable?.let { put("unbreakable", it) }
            def.damage?.let { put("damage", it) }
            def.itemModel?.let { put("item_model", it.text) }
            def.tooltipStyle?.let { put("tooltip_style", it.text) }
            def.equipment?.let { equipment ->
                put("equipment", mapOf("asset" to equipment.asset.text, "slot" to serialName(equipment.slot)))
            }
            def.color?.let { put("color", it) }
            def.profile?.let { put("profile", it) }
            def.maxStackSize?.let { put("max_stack_size", it) }
            def.map?.let { put("map", it) }
            def.rarity?.let { put("rarity", serialName(it)) }
            def.attributeModifiers?.let { modifiers ->
                put(
                    "attribute_modifiers",
                    modifiers.map { modifier ->
                        buildMap {
                            modifier.id?.let { put("id", it) }
                            put("attribute", modifier.attribute)
                            put("amount", modifier.amount)
                            put("operation", serialName(modifier.operation))
                            modifier.slot?.let { put("slot", serialName(it)) }
                        }
                    }
                )
            }
            def.canBreak?.let { put("can_break", it) }
            def.canPlaceOn?.let { put("can_place_on", it) }
            def.hiddenTooltipComponents?.let { put("hidden_tooltip_components", it.map(TooltipComponent::id)) }
            def.food?.let { food ->
                put(
                    "food",
                    buildMap {
                        put("nutrition", food.nutrition)
                        put("saturation", food.saturation)
                        food.canAlwaysEat?.let { put("can_always_eat", it) }
                        food.eatSeconds?.let { put("eat_seconds", it) }
                    }
                )
            }
            def.cooldown?.let { cooldown ->
                put(
                    "cooldown",
                    buildMap {
                        put("seconds", cooldown.seconds)
                        cooldown.group?.let { put("group", it) }
                    }
                )
            }
            def.hold?.let { hold ->
                val animation = hold.animation?.takeIf { hold.on }
                put("hold", if (animation == null) hold.on else mapOf("animation" to animation.id))
            }
            def.useEffects?.let { effects ->
                put(
                    "use_effects",
                    buildMap {
                        effects.speedMultiplier?.let { put("speed_multiplier", it) }
                        effects.canSprint?.let { put("can_sprint", it) }
                        effects.interactVibrations?.let { put("interact_vibrations", it) }
                    }
                )
            }
            def.data?.takeIf { it.isNotEmpty() }?.let { put("data", JsonObject(it)) }
            item.raw?.let { put("raw", it) }
        }
    }

    /** A list field (an empty Lua table reads as an empty object: the empty list), each entry read by [read]. */
    private fun <T> list(value: JsonElement?, at: String, what: String, read: (Int, JsonElement) -> T): List<T>? = when (value) {
        null, JsonNull -> null
        is JsonObject -> if (value.isEmpty()) emptyList() else throw LuaApiException("$at must be $what")
        is JsonArray -> value.mapIndexed(read)
        else -> throw LuaApiException("$at must be $what")
    }

    /** An enum's name as the format and scripts spell it (`add_value`). */
    private fun serialName(value: Enum<*>): String = value.name.lowercase()

    /** One table's fields, read strictly: [allowed] names every key it may have (null to skip the check). */
    private class Fields(private val table: JsonObject, private val at: String, allowed: List<String>? = null) {
        init {
            if (allowed != null) {
                for (key in table.keys) {
                    if (key !in allowed) throw LuaApiException("$at has no field \"$key\" (fields: ${allowed.joinToString()})")
                }
            }
        }

        fun string(key: String): String? = when (val it = table[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (it.isString) it.content else throw LuaApiException("$at.$key must be a string")
            else -> throw LuaApiException("$at.$key must be a string")
        }

        fun number(key: String): Double? {
            val it = table[key]
            if (it == null || it == JsonNull) return null
            return (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: throw LuaApiException("$at.$key must be a number")
        }

        fun integer(key: String): Int? {
            val number = runCatching { number(key) }.getOrElse { throw LuaApiException("$at.$key must be a whole number") } ?: return null
            if (number != Math.floor(number) || number !in INT_RANGE) throw LuaApiException("$at.$key must be a whole number")
            return number.toInt()
        }

        fun boolean(key: String): Boolean? {
            val it = table[key]
            if (it == null || it == JsonNull) return null
            return (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
                ?: throw LuaApiException("$at.$key must be true or false")
        }

        /** One of [values], by its lowercase name. */
        fun <E : Enum<E>> enum(key: String, values: List<E>): E? {
            val name = string(key) ?: return null
            return values.firstOrNull { it.name.lowercase() == name }
                ?: throw LuaApiException("$at.$key must be one of ${values.joinToString { "\"${it.name.lowercase()}\"" }}, not \"$name\"")
        }
    }
}
