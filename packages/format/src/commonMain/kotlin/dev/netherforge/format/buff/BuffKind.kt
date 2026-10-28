package dev.netherforge.format.buff

import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.Templates

/** `buffs/<id>.json`: a buff or debuff scripts give players and centities. One file, nothing beside it. */
object BuffKind : DocumentResourceKind<BuffFile, BuffFile>(
    "buff",
    "buffs",
    Layout.SingleFile(".json"),
    BuffFile.serializer(),
    BuffFile.SCHEMA
) {
    override fun canonical(value: BuffFile) = value.copy(schema = schemaRef)

    override fun validate(value: BuffFile, ctx: ResourceContext) = BuffFile.validate(value, ctx.sink)

    override fun compile(id: String, value: BuffFile, ctx: ResourceContext) = value

    /** Ironskin: a starter buff the user changes. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(BuffFile(name = Templates.titleOf(id), color = "#8a8a98", stats = mapOf("defense" to 8.0)))
    )
}
