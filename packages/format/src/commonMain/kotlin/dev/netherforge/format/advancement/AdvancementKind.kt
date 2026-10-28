package dev.netherforge.format.advancement

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.DatapackFiles
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.Templates
import dev.netherforge.format.ref.RefKind

/**
 * `advancements/<id>.json`: one of the project's advancements. One file,
 * nothing beside it. The server learns them from the start-up datapack, so
 * a change needs a restart.
 */
object AdvancementKind : DocumentResourceKind<AdvancementFile, AdvancementFile>(
    "advancement",
    "advancements",
    Layout.SingleFile(".json"),
    AdvancementFile.serializer(),
    AdvancementFile.SCHEMA
) {
    /** Where the game reads a datapack's advancements, under `data/<namespace>/`. */
    const val DATAPACK_FOLDER = "advancement"

    // Requirements keep their order (the game's), criteria and conditions are keyed and print sorted.
    override fun canonical(value: AdvancementFile) = value.copy(schema = schemaRef)

    override fun validate(value: AdvancementFile, ctx: ResourceContext) = AdvancementValidator.validate(value, ctx.sink, ctx.game)

    /**
     * An advancement whose parents lead back to it would hang from itself.
     * (A package's can't name the project's: packages don't depend on the
     * projects that use them.)
     */
    override fun crossCheck(value: AdvancementFile, ctx: KindContext) {
        val parent = value.parent ?: return
        val home = ctx.references.home
        val advancements = ctx.models(AdvancementKind)
        fun own(file: AdvancementFile) = file.parent?.let { ctx.references.resolve(RefKind.ADVANCEMENT, it.text) }
            ?.takeIf { it.namespace == home }?.path
        val start = own(value) ?: return
        val seen = mutableSetOf<String>()
        var next: String? = start
        while (next != null && seen.add(next)) {
            if (next == ctx.id) {
                ctx.sink.report(
                    ProblemCodes.ADVANCEMENT_CYCLE,
                    "\"$parent\" leads back to this advancement${if (start == ctx.id) "" else " through its parents"}, so it would hang from itself",
                    "$.parent",
                    listOf(Location(ctx.placeOf(this, start).path))
                )
                return
            }
            next = advancements[next]?.let(::own)
        }
    }

    override fun compile(id: String, value: AdvancementFile, ctx: ResourceContext) = value

    override val datapack = DatapackFiles<AdvancementFile> { key, value, ctx ->
        val json = AdvancementJson.of(value, ctx) ?: return@DatapackFiles emptyMap()
        mapOf("data/${key.namespace}/$DATAPACK_FOLDER/${key.path}.json" to DatapackEntry.json(json))
    }

    /** A tree's root that a script completes: starter content the user fills in in the advancement editor. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            AdvancementFile(
                display = AdvancementDisplay(
                    icon = AdvancementIcon(kind = "minecraft:book"),
                    title = Templates.titleOf(id),
                    description = "What to do for it",
                    background = "minecraft:block/stone"
                ),
                criteria = mapOf("done" to AdvancementCriterion())
            )
        )
    )
}
