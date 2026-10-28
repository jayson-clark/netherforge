package dev.netherforge.format.datapack

import dev.netherforge.format.biome.BiomeJson
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.TemplateNeedsGame
import dev.netherforge.format.project.Templates
import kotlinx.serialization.json.JsonPrimitive

/**
 * `datapacks/<id>/`: a datapack of the game's own, for experts: worldgen JSON (density functions, noise settings,
 * biomes, features, structures, presets…) written by hand, passed through into the start-up datapack beside what
 * NetherForge writes itself. It's laid out as any datapack is (`pack.mcmeta`, `data/<namespace>/worldgen/…`, overlay
 * folders), so the game's documentation and the community's tools apply, and `pack.mcmeta` says which data pack
 * formats its files are for: a server outside them leaves it out (`datapack.version`).
 *
 * Format reads its files as JSON ([readsFile]) and checks what [DatapackValidator] says, not every field of the
 * game's codecs: the server does that as it starts. The game learns worldgen only as it starts, so a change needs a
 * restart. The id is only the folder's: what's in it is named as the game names it (`showcase:my_trees`).
 */
object DatapackKind : DocumentResourceKind<PackMeta, CompiledDatapack>(
    "datapack",
    "datapacks",
    Layout.Folder(PackMeta.FILE_NAME),
    PackMeta.serializer(),
    PackMeta.SCHEMA
) {
    override fun canonical(value: PackMeta) = value.copy(schema = schemaRef)

    /**
     * An empty datapack for the server's data pack format alone (any minor of its major), which its author widens as
     * they write files for other versions. Only the server knows its format, so a new one needs the game data.
     */
    override fun template(id: String, game: GameData?): Map<String, String> {
        val format = game?.dataPackFormat?.firstOrNull() ?: throw TemplateNeedsGame(
            "A new datapack's pack.mcmeta says which data pack format it's for, and only the server knows yours: " +
                "start the dev server once, then create it again"
        )
        val meta = PackMeta(pack = PackSection(JsonPrimitive(Templates.titleOf(id)), JsonPrimitive(format), JsonPrimitive(format)))
        return mapOf(pathOf(id) to write(meta))
    }

    /** Every JSON file of it: its worldgen, and what isn't (which [DatapackValidator] reports). */
    override fun readsFile(file: String): Boolean = file.endsWith(".json")

    override fun validate(value: PackMeta, ctx: ResourceContext) = DatapackValidator.validate(value, ctx)

    override fun crossCheck(value: PackMeta, ctx: KindContext) = DatapackValidator.crossCheck(value, ctx)

    override fun compile(id: String, value: PackMeta, ctx: ResourceContext): CompiledDatapack =
        requireNotNull(CompiledDatapack.of(value, ctx.files)) { "datapack $id compiled with unreadable formats" }

    /** Its files for the server's data pack format, as they are: none when it isn't written for that format. */
    override val datapack = DatapackFiles<CompiledDatapack> { key, value, ctx ->
        if (!ctx.passThrough) return@DatapackFiles emptyMap()
        val name = key.relativeTo(ctx.home).text
        value.filesFor(ctx.packFormat).mapValues { (_, path) -> DatapackEntry.Copy(ctx.snapshot.placeOf(this, name).file(path.file)) }
    }

    /**
     * The biomes the running datapacks of [snapshot] (the project's and its packages') define, as the server names
     * them (`showcase:caves/deep`), whatever the server's format: what a terrain's area may name besides the
     * project's own biomes.
     */
    fun biomes(snapshot: ProjectSnapshot): Set<String> =
        snapshot.running(this).values.flatMap { it.files }.filter { !it.tag && it.registry == BiomeJson.FOLDER }
            .map { it.key.toString() }.toSet()
}
