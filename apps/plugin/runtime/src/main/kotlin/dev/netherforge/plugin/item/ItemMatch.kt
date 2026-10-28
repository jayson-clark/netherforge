package dev.netherforge.plugin.item

import dev.netherforge.format.game.GameIds
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.StackFilter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Which stacks `inventory:remove_item` and friends pick: an
 * item kind, or a partial item that matches a stack when every field it gives
 * matches. Fields compare as a script reads them ([LuaItems.write]), so a
 * match says things the way an item table does; `data` matches as a subset,
 * so `{ data = { coin = true } }` finds coins whatever else they carry. Both
 * sides' `data` is the Lua↔JSON codec's tagged JSON, so typed values
 * (`Vec3`s, handles) compare too.
 */
class ItemMatch private constructor(private val kind: String?, private val fields: Map<String, Any?>, private val data: JsonObject?) {
    /** What a stack must be to be worth reading in full: its kind and project item, when the match gives them. */
    val filter: StackFilter = StackFilter(kind, fields["item"] as? String)

    fun matches(item: ItemData): Boolean {
        if (kind != null && GameIds.normalize(item.def.kind) != kind) return false
        if (fields.isEmpty() && data == null) return true
        val written = LuaItems.write(item)
        for ((key, value) in fields) if (written[key] != value) return false
        if (data != null && !subset(data, item.def.data?.let(::JsonObject) ?: JsonObject(emptyMap()))) return false
        return true
    }

    companion object {
        /** A bare item kind. */
        fun kind(kind: String) = ItemMatch(GameIds.normalize(kind), emptyMap(), null)

        /** A partial item, as [LuaItems.read] with `partial` read it; [given] are the fields the script gave. */
        fun of(item: ItemData, given: Set<String>): ItemMatch {
            val written = LuaItems.write(item)
            val fields = (given - setOf("kind", "data", "count", "raw")).associateWith { written[it] }
            val kind = item.def.kind.takeIf { "kind" in given }?.let(GameIds::normalize)
            val data = if ("data" in given) JsonObject(item.def.data.orEmpty()) else null
            return ItemMatch(kind, fields, data)
        }

        /** Whether every key of [part] is in [whole] with the same value, objects compared the same way, all the way down. */
        fun subset(part: JsonElement, whole: JsonElement): Boolean {
            if (part !is JsonObject) return part == whole
            if (whole !is JsonObject) return false
            return part.all { (key, value) -> whole[key]?.let { subset(value, it) } == true }
        }
    }
}

/**
 * Taking, counting and finding matching stacks over any row of slots: what a
 * real inventory has, and what a menu window could share.
 */
object ItemSlots {
    /** How many of [contents]' items match. */
    fun count(contents: List<ItemData?>, match: ItemMatch): Int =
        contents.sumOf { item -> if (item != null && match.matches(item)) item.def.count ?: 1 else 0 }

    /** The first slot holding a match, or null. */
    fun first(contents: List<ItemData?>, match: ItemMatch): Int? =
        contents.indexOfFirst { it != null && match.matches(it) }.takeIf { it >= 0 }

    /**
     * Takes up to [limit] (every one, for null) matching items from [contents],
     * first slot first, writing each changed slot with [write]. How many it took.
     */
    fun remove(contents: List<ItemData?>, match: ItemMatch, limit: Int?, write: (Int, ItemData?) -> Unit): Int {
        var left = limit ?: Int.MAX_VALUE
        var taken = 0
        for ((slot, item) in contents.withIndex()) {
            if (left <= 0) break
            if (item == null || !match.matches(item)) continue
            val count = item.def.count ?: 1
            val take = minOf(count, left)
            write(slot, if (take == count) null else item.copy(def = item.def.copy(count = count - take)))
            left -= take
            taken += take
        }
        return taken
    }
}
