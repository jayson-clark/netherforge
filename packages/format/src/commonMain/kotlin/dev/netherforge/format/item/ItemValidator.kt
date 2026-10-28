package dev.netherforge.format.item

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.validate.Rules

/** Checks an `item.json` on its own; what it names in packs is checked by the project, which sees them all. */
object ItemValidator {
    fun validate(file: ItemFile, sink: ProblemSink, files: Set<String>?, game: GameData?) {
        // The look is an item like any other, at the same paths: `$.kind`, `$.itemModel`.
        Rules.item(file.look(), "$", sink, game)
        if (file.drawWithoutAmmo == true && GameIds.normalize(file.kind) != "minecraft:bow") {
            sink.report(ProblemCodes.ITEM_DRAW_WITHOUT_AMMO, "Only a bow can draw without ammo", "$.drawWithoutAmmo")
        }
        file.script?.let { Rules.script(it, "$.script", sink, files) }
    }
}
