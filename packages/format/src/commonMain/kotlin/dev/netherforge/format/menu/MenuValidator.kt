package dev.netherforge.format.menu

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.validate.Rules

object MenuValidator {

    fun validate(file: MenuFile, sink: ProblemSink, files: Set<String>?, game: GameData?) {
        val type = file.type ?: MenuType.CHEST
        if (type == MenuType.CHEST) {
            if (file.rows != null && file.rows !in 1..MenuType.MAX_ROWS) {
                sink.report(ProblemCodes.MENU_ROWS, "A chest has 1 to ${MenuType.MAX_ROWS} rows", "$.rows")
            }
        } else if (file.rows != null) {
            sink.report(
                ProblemCodes.MENU_ROWS_FIXED,
                "A ${type.id} is always ${type.fixedSlots} slots; rows only applies to a chest",
                "$.rows"
            )
        }
        val size = type.size((file.rows ?: MenuType.DEFAULT_ROWS).coerceIn(1, MenuType.MAX_ROWS))

        file.script?.let { Rules.script(it, "$.script", sink, files) }

        for ((key, slot) in file.slots) {
            val at = CanonicalJson.childPath("$.slots", key)
            val index = key.toIntOrNull()
            if (index == null || index.toString() != key) {
                sink.report(ProblemCodes.MENU_SLOT_KEY, "Slots are keyed by index: \"0\", \"13\", …", at)
                continue
            }
            if (index !in 0 until size) {
                sink.report(ProblemCodes.MENU_SLOT_RANGE, "This menu has $size slots (0–${size - 1})", at)
            }
            if (slot.item == null) {
                sink.report(ProblemCodes.MENU_SLOT_EMPTY, "Slot $key has no item", at)
            }
            slot.item?.let { Rules.item(it, "$at.item", sink, game) }
        }
    }

    /** From a file with no errors. */
    fun compile(id: String, file: MenuFile): CompiledMenu {
        val type = file.type ?: MenuType.CHEST
        val rows = if (type == MenuType.CHEST) file.rows ?: MenuType.DEFAULT_ROWS else type.fixedSlots / type.columns
        return CompiledMenu(
            id = id,
            name = file.name,
            type = type,
            rows = rows,
            size = type.size(rows),
            title = file.title,
            skin = file.skin,
            shared = file.shared ?: false,
            locked = file.locked ?: true,
            slots = file.slots.entries.map { it.key.toInt() to it.value }.sortedBy { it.first }.toMap(),
            script = file.script
        )
    }
}
