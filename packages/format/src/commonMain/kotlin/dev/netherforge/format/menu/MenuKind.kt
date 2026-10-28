package dev.netherforge.format.menu

import dev.netherforge.format.game.GameData
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.ScriptSpec
import dev.netherforge.format.project.Templates
import dev.netherforge.format.text.DefaultFontValidator

/** `menus/<id>/menu.json`: an inventory window. */
object MenuKind : DocumentResourceKind<MenuFile, CompiledMenu>(
    "menu",
    "menus",
    Layout.Folder(MenuFile.FILE_NAME),
    MenuFile.serializer(),
    MenuFile.SCHEMA
) {
    override fun canonical(value: MenuFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("Menu") {
        "-- Runs once per window, before anyone sees it: fill it in and listen here.\n\nthis:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: MenuFile) = value.script

    override fun validate(value: MenuFile, ctx: ResourceContext) {
        MenuValidator.validate(value, ctx.sink, ctx.files, ctx.game)
    }

    override fun crossCheck(value: MenuFile, ctx: KindContext) {
        // A centred title moves the skin by the words' width, which the server measures with the default font.
        DefaultFontValidator.checkCentredTitle(
            value.type ?: MenuType.CHEST,
            value.skin?.text,
            value.title,
            ctx.defaultFont,
            ctx.minecraft,
            ctx.sink,
            "$.skin"
        )
        for ((key, slot) in value.slots) {
            slot.item?.let { ItemKind.checkStack(it, CanonicalJson.childPath("$.slots", key) + ".item", ctx) }
        }
    }

    override fun compile(id: String, value: MenuFile, ctx: ResourceContext) = MenuValidator.compile(id, value)

    /** A three-row chest titled after its id, with nothing in it yet. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(MenuFile(title = Templates.titleOf(id))))
}
