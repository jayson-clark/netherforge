package dev.netherforge.format.recipe

import dev.netherforge.format.game.GameData
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/** `recipes/<id>.json`: a recipe the server learns. One file, nothing beside it. */
object RecipeKind : DocumentResourceKind<RecipeFile, RecipeFile>(
    "recipe",
    "recipes",
    Layout.SingleFile(".json"),
    RecipeFile.serializer(),
    RecipeFile.SCHEMA
) {
    // Pattern rows and shapeless ingredients keep their order: it's the recipe.
    override fun canonical(value: RecipeFile) = value.copy(schema = schemaRef)

    override fun validate(value: RecipeFile, ctx: ResourceContext) {
        RecipeValidator.validate(value, ctx.sink, ctx.game)
    }

    override fun crossCheck(value: RecipeFile, ctx: KindContext) {
        ItemKind.checkStack(value.result, "$.result", ctx)
    }

    override fun compile(id: String, value: RecipeFile, ctx: ResourceContext) = value

    /**
     * A one-square shaped recipe: a stick makes a stick. Starter content the
     * user replaces in the recipe editor; it's no vanilla recipe, so it hides none.
     */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            RecipeFile(
                type = RecipeType.SHAPED,
                pattern = listOf("#"),
                key = mapOf("#" to Ingredient(kind = "minecraft:stick")),
                result = ItemDef(kind = "minecraft:stick")
            )
        )
    )
}
