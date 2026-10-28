package dev.netherforge.format.sky

import dev.netherforge.format.biome.BiomeJson
import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.dimensiontype.DimensionTypeJson
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.ref.ResourceKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `skies/<id>.json`: a sky scripts set over a world or a player's view of it. One file, nothing beside it. Its
 * twin biomes ([SkyTwins]) go in the start-up datapack, so a new or changed sky needs a restart, as a biome does.
 */
object SkyKind : DocumentResourceKind<SkyFile, SkyFile>(
    "sky",
    "skies",
    Layout.SingleFile(".json"),
    SkyFile.serializer(),
    SkyFile.SCHEMA
) {
    override fun canonical(value: SkyFile) = value.copy(schema = schemaRef)

    override fun validate(value: SkyFile, ctx: ResourceContext) = SkyValidator.validate(value, ctx.sink, ctx::requireFeature)

    override fun compile(id: String, value: SkyFile, ctx: ResourceContext) = value

    /**
     * Each sky's twin of every biome the server has (the game's, from the server, and the project's own and its
     * packages'), per step of its fade, in the project's namespace (`showcase:sky/blood_moon/1/minecraft/plains`).
     */
    override val datapackAll = DatapackCollection<SkyFile> { skies, ctx ->
        if (skies.isEmpty()) return@DatapackCollection emptyMap()
        val biomes = LinkedHashMap<ResourceKey, JsonObject>()
        for ((key, json) in ctx.game.biomes) biomes[key] = json
        for ((name, file) in ctx.snapshot.running(BiomeKind)) {
            if (BiomeJson.writes(file, ctx)) (BiomeJson.of(file, ctx) as? JsonObject)?.let { biomes[ctx.key(name)] = it }
        }
        val defaults = SkyTwins.defaultsOf(ctx.game.overworld)
        val clocks = ctx.formatAtLeast(DimensionTypeJson.CLOCK_FORMAT)
        val timelines = mutableListOf<ResourceKey>()
        buildMap {
            for ((sky, file) in skies) {
                if (ctx.leftOut(sky)) continue
                if (clocks && file.heldAtNight.isNotEmpty()) {
                    val key = SkyNight.keyOf(ctx.home, sky)
                    put("data/${key.namespace}/${SkyNight.CLOCK_FOLDER}/${key.path}.json", DatapackEntry.json(JsonObject(emptyMap())))
                    put(
                        "data/${key.namespace}/${SkyNight.TIMELINE_FOLDER}/${key.path}.json",
                        DatapackEntry.json(SkyNight.timeline(key, file))
                    )
                    timelines += key
                }
                for (step in 1..file.fadeOrDefault) {
                    for ((biome, json) in biomes) {
                        val twin = SkyTwins.keyOf(ctx.home, sky, step, biome)
                        put(
                            "data/${twin.namespace}/${BiomeJson.FOLDER}/${twin.path}.json",
                            DatapackEntry.json(SkyTwins.twin(json, file, step, defaults))
                        )
                    }
                }
            }
            // Each added after the game's own (the day's), as a tag's values from another pack are.
            if (timelines.isNotEmpty()) {
                val values = buildJsonObject { put("values", JsonArray(timelines.map { JsonPrimitive(it.toString()) })) }
                for (tag in SkyNight.TAGS) put("data/minecraft/tags/${SkyNight.TIMELINE_FOLDER}/$tag.json", DatapackEntry.json(values))
            }
        }
    }

    /** A blood moon: the sky, the fog, the water and the light on the land turn red. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(SkyFile(sky = "#5a0a0a", fog = "#3a0606", water = "#8a1010", waterFog = "#4a0808", light = "#ff8080"))
    )
}
