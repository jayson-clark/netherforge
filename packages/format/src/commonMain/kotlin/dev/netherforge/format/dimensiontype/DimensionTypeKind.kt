package dev.netherforge.format.dimensiontype

import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.DatapackFiles
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.ref.ResourceRef

/**
 * `dimension_types/<id>.json`: one of the project's dimension types. One file,
 * nothing beside it. The game learns dimension types only as it loads, from
 * the start-up datapack, so a change needs a restart; the game knows it as
 * `<namespace>:<id>`, and a world keeps the one it was made with.
 */
object DimensionTypeKind : DocumentResourceKind<DimensionTypeFile, DimensionTypeFile>(
    "dimension_type",
    "dimension_types",
    Layout.SingleFile(".json"),
    DimensionTypeFile.serializer(),
    DimensionTypeFile.SCHEMA
) {
    override fun canonical(value: DimensionTypeFile) = value.copy(schema = schemaRef)

    override fun validate(value: DimensionTypeFile, ctx: ResourceContext) = DimensionTypeValidator.validate(value, ctx.sink, ctx.game)

    override fun compile(id: String, value: DimensionTypeFile, ctx: ResourceContext) = value

    /**
     * The name the server knows a dimension type by, as a file in namespace [home] writes it: always
     * `<namespace>:<id>` (`deep` in `showcase` is `showcase:deep`). Null when it isn't shaped like a reference.
     */
    fun keyOf(text: String, home: String): String? = ResourceRef(text).resolve(home)?.toString()

    override val datapack = DatapackFiles<DimensionTypeFile> { key, value, ctx ->
        mapOf("data/${key.namespace}/${DimensionTypeJson.FOLDER}/${key.path}.json" to DatapackEntry.json(DimensionTypeJson.of(value, ctx)))
    }

    /** It's the dimension type `<namespace>:<id>`, which a datapack's world preset may name. */
    override fun datapackEntries(id: String, value: DimensionTypeFile) = mapOf(DimensionTypeJson.FOLDER to setOf(id))

    /**
     * The main world's dimension: the server makes its main world itself, as
     * an overworld, before any plugin could make it otherwise, so a main world
     * that `netherforge.json` names a dimension for gets it by the game's own
     * overworld type being replaced with it (every world of the overworld type
     * that names none of its own has it too). Only when the pack knows the main
     * world's name and the manifest names a running dimension for it.
     */
    override val datapackAll = DatapackCollection<DimensionTypeFile> { resources, ctx ->
        val main = ctx.mainWorld ?: return@DatapackCollection emptyMap()
        val named = ctx.snapshot.manifest?.worlds?.get(main)?.dimensionType ?: return@DatapackCollection emptyMap()
        val file = named.resolve(ctx.home)?.let(resources::get) ?: return@DatapackCollection emptyMap()
        val overworld = requireNotNull(ResourceRef(DimensionTypeJson.OVERWORLD).resolve(ctx.home))
        mapOf(
            "data/${overworld.namespace}/${DimensionTypeJson.FOLDER}/${overworld.path}.json" to
                DatapackEntry.json(DimensionTypeJson.of(file, ctx))
        )
    }

    /** A deep world: twice the overworld's depth below 0, the rest as the overworld. Starter content the user changes in the editor. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(DimensionTypeFile(minY = -128, height = 448))
    )
}
