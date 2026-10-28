package dev.netherforge.format.advancement

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names

/**
 * Checks an advancement on its own: its icon, its criteria's names and
 * triggers (against the game's, with game data), its requirements against
 * its criteria, and a background only a tree's root shows. That its parent
 * and icon item exist is the project's to check, as for every reference;
 * that its parents don't loop is the advancement kind's cross check.
 */
object AdvancementValidator {
    fun validate(file: AdvancementFile, sink: ProblemSink, game: GameData?) {
        file.display?.let { display(it, root = file.parent == null, sink, game) }
        if (file.criteria.isEmpty()) {
            sink.report(
                ProblemCodes.ADVANCEMENT_CRITERIA,
                "An advancement needs at least one criterion, or nothing can complete it",
                "$.criteria"
            )
        }
        for ((name, criterion) in file.criteria) criterion(name, criterion, CanonicalJson.childPath("$.criteria", name), sink, game)
        file.requirements?.let { requirements(it, file.criteria.keys, sink) }
        if (file.experience != null && file.experience < 0) {
            sink.report(ProblemCodes.ADVANCEMENT_EXPERIENCE, "experience can't be negative", "$.experience")
        }
    }

    private fun display(display: AdvancementDisplay, root: Boolean, sink: ProblemSink, game: GameData?) {
        val icon = display.icon
        val at = "$.display.icon"
        when {
            icon.kind == null && icon.item == null ->
                sink.report(
                    ProblemCodes.ADVANCEMENT_ICON,
                    "An icon needs a kind, like \"minecraft:diamond\", or an item: one of the project's own",
                    at
                )
            icon.kind != null && icon.item != null ->
                sink.report(ProblemCodes.ADVANCEMENT_ICON, "An icon is a game item (kind) or a project item (item), not both", "$at.kind")
            icon.kind != null && !GameIds.isValid(icon.kind) ->
                sink.report(ProblemCodes.ADVANCEMENT_ICON, "\"${icon.kind}\" isn't an item id", "$at.kind")
            icon.kind != null && game?.has(RegistryKey.ITEM, GameIds.normalize(icon.kind)) == false ->
                sink.report(
                    ProblemCodes.ADVANCEMENT_UNKNOWN_ICON,
                    "Minecraft ${game.minecraftVersion} has no item \"${GameIds.normalize(icon.kind)}\"",
                    "$at.kind"
                )
        }
        if (root && display.background == null) {
            sink.report(
                ProblemCodes.ADVANCEMENT_BACKGROUND,
                "A tree's root is its tab: without a background, the tab shows the missing texture",
                "$.display"
            )
        } else if (!root && display.background != null) {
            sink.report(ProblemCodes.ADVANCEMENT_BACKGROUND, "Only a tree's root (no parent) shows its background", "$.display.background")
        }
        if (display.background != null && (!GameIds.isValid(display.background) || ':' !in display.background)) {
            sink.report(
                ProblemCodes.ADVANCEMENT_BACKGROUND,
                "\"${display.background}\" isn't a texture's id, like \"minecraft:block/stone\"",
                "$.display.background"
            )
        }
    }

    private fun criterion(name: String, criterion: AdvancementCriterion, at: String, sink: ProblemSink, game: GameData?) {
        if (!Names.isId(name)) {
            sink.report(ProblemCodes.ADVANCEMENT_CRITERION_NAME, "\"$name\" isn't a usable criterion name (${Names.ID_RULE})", at)
        }
        val trigger = criterion.trigger
        when {
            trigger == null -> if (criterion.conditions != null) {
                sink.report(
                    ProblemCodes.ADVANCEMENT_TRIGGER,
                    "conditions are a trigger's: without a trigger only a script meets the criterion",
                    "$at.conditions"
                )
            }
            !GameIds.isValid(trigger) || ':' !in trigger ->
                sink.report(
                    ProblemCodes.ADVANCEMENT_TRIGGER,
                    "\"$trigger\" isn't a trigger's id, like \"minecraft:inventory_changed\"",
                    "$at.trigger"
                )
            game?.has(RegistryKey.TRIGGER_TYPE, trigger) == false ->
                sink.report(
                    ProblemCodes.ADVANCEMENT_UNKNOWN_TRIGGER,
                    "Minecraft ${game.minecraftVersion} has no trigger \"$trigger\"",
                    "$at.trigger"
                )
        }
    }

    private fun requirements(requirements: List<List<String>>, criteria: Set<String>, sink: ProblemSink) {
        requirements.forEachIndexed { index, group ->
            val at = "$.requirements[$index]"
            if (group.isEmpty()) sink.report(ProblemCodes.ADVANCEMENT_REQUIREMENTS, "A group needs at least one criterion", at)
            group.forEachIndexed { inner, name ->
                if (name !in criteria) {
                    sink.report(ProblemCodes.ADVANCEMENT_REQUIREMENTS, "There's no criterion \"$name\" in criteria", "$at[$inner]")
                }
            }
        }
        val required = requirements.flatten().toSet()
        for (name in criteria.sorted()) {
            if (name !in required) {
                sink.report(
                    ProblemCodes.ADVANCEMENT_REQUIREMENTS,
                    "Criterion \"$name\" is in no group, and the game refuses an advancement whose requirements leave one out",
                    "$.requirements"
                )
            }
        }
    }
}
