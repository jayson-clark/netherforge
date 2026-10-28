package dev.netherforge.format.advancement

import dev.netherforge.format.datapack.DatapackContext
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.item.ItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An advancement in the game's own datapack format, for the start-up
 * datapack: its parent and icon resolved in the project's namespace, its
 * text as the server's JSON text, a criterion without a trigger as one only
 * a grant meets.
 */
object AdvancementJson {
    /** The game's trigger that never fires: a criterion only `grant_advancement` meets. */
    const val SCRIPT_TRIGGER = "minecraft:impossible"

    /** The icon's components NetherForge sets: its look from a pack, and the shimmer. */
    const val ITEM_MODEL_COMPONENT = "minecraft:item_model"
    const val GLINT_COMPONENT = "minecraft:enchantment_glint_override"

    /**
     * [file] in the game's format; null when its icon names a project item
     * that can't be drawn (a package's item with errors of its own), which
     * the game would refuse anyway.
     */
    fun of(file: AdvancementFile, ctx: DatapackContext): JsonObject? {
        val display = file.display?.let { display(it, ctx) ?: return null }
        return buildJsonObject {
            file.parent?.let { put("parent", ctx.resolve(it).toString()) }
            display?.let { put("display", it) }
            put("criteria", criteria(file))
            file.requirements?.let { groups ->
                put(
                    "requirements",
                    buildJsonArray {
                        for (group in groups) add(buildJsonArray { group.forEach { add(JsonPrimitive(it)) } })
                    }
                )
            }
            file.experience?.let { put("rewards", buildJsonObject { put("experience", it) }) }
        }
    }

    private fun criteria(file: AdvancementFile): JsonObject = buildJsonObject {
        for ((name, criterion) in file.criteria.entries.sortedBy { it.key }) {
            put(
                name,
                buildJsonObject {
                    put("trigger", criterion.trigger ?: SCRIPT_TRIGGER)
                    criterion.conditions?.let { put("conditions", JsonObject(it)) }
                }
            )
        }
    }

    private fun display(display: AdvancementDisplay, ctx: DatapackContext): JsonObject? {
        val icon = icon(display.icon, ctx) ?: return null
        return buildJsonObject {
            put("icon", icon)
            put("title", ctx.text(display.title))
            // The game requires a description; an empty one draws nothing.
            put("description", ctx.text(display.description.orEmpty()))
            display.frame?.let { put("frame", it.name.lowercase()) }
            display.background?.let { put("background", it) }
            display.toast?.let { put("show_toast", it) }
            display.announce?.let { put("announce_to_chat", it) }
            display.hidden?.let { put("hidden", it) }
        }
    }

    /** An item stack as the game writes one: its kind, and the components its look needs. Null when its project item isn't running. */
    private fun icon(icon: AdvancementIcon, ctx: DatapackContext): JsonObject? {
        // A running item is written in full (a package's too), so its references resolve in the project's namespace.
        val item = icon.item?.let { ref -> ctx.snapshot.running(ItemKind)[ctx.resolve(ref).relativeTo(ctx.home).text] ?: return null }
        val kind = icon.kind ?: item?.kind ?: return null
        val model = icon.itemModel ?: item?.itemModel
        val glint = icon.glint ?: item?.glint
        return buildJsonObject {
            put("id", GameIds.normalize(kind))
            if (model != null || glint != null) {
                put(
                    "components",
                    buildJsonObject {
                        model?.let { put(ITEM_MODEL_COMPONENT, ctx.resolve(it).toString()) }
                        glint?.let { put(GLINT_COMPONENT, it) }
                    }
                )
            }
        }
    }
}
