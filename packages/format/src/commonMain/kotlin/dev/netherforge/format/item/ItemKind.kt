package dev.netherforge.format.item

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.ScriptSpec
import dev.netherforge.format.project.Templates
import dev.netherforge.format.ref.RefKind

/** `items/<id>/item.json`: a project item. What stacks are made from needs no compiling beyond validation. */
object ItemKind : DocumentResourceKind<ItemFile, ItemFile>(
    "item",
    "items",
    Layout.Folder(ItemFile.FILE_NAME),
    ItemFile.serializer(),
    ItemFile.SCHEMA
) {
    override fun canonical(value: ItemFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("ProjectItem") {
        "-- Runs once for the item, when the server loads it. Hears what players do with any stack of it.\n\n" +
            "this:on(\"use\", function(event)\nend)\n"
    }

    override fun scriptOf(value: ItemFile) = value.script

    override fun validate(value: ItemFile, ctx: ResourceContext) = ItemValidator.validate(value, ctx.sink, ctx.files, ctx.game)

    /**
     * A stack naming one of the project's items ([ItemDef.item]) that gives a
     * `kind` too must give the item's: the item's file, the reference's other
     * end, says what it is. (Whether the item exists is the loader's check, as
     * for every reference.)
     */
    fun checkStack(item: ItemDef, at: String, ctx: KindContext) {
        val reference = item.item ?: return
        if (item.kind.isEmpty() || !GameIds.isValid(item.kind)) return
        val key = ctx.references.resolve(RefKind.ITEM, reference.text)?.takeIf { it.namespace == ctx.references.home } ?: return
        val definition = ctx.models(ItemKind)[key.path]?.takeIf { GameIds.isValid(it.kind) } ?: return
        val kind = GameIds.normalize(item.kind)
        val itemKind = GameIds.normalize(definition.kind)
        if (kind == itemKind) return
        ctx.sink.report(
            ProblemCodes.ITEM_KIND_MISMATCH,
            "Item \"$reference\" is a $itemKind, not a $kind: leave kind out",
            "$at.kind",
            listOf(Location(ctx.placeOf(this, key.path).path, "$.kind"))
        )
    }

    override fun compile(id: String, value: ItemFile, ctx: ResourceContext) = value

    /**
     * A project item named after its id, built on paper. The kind is starter
     * content the user changes, like the particle template's.
     */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(ItemFile(kind = "minecraft:paper", name = Templates.titleOf(id)))
    )
}
