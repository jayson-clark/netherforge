package dev.netherforge.format.biome

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.centity.SpawnRange
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.DatapackFiles
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.world.GenerationStep

/**
 * `biomes/<id>.json`: one of the project's biomes. One file, nothing beside
 * it. The game learns biomes only as it loads, from the start-up datapack,
 * so a change needs a restart; the game knows it as `<namespace>:<id>`.
 */
object BiomeKind : DocumentResourceKind<BiomeFile, BiomeFile>(
    "biome",
    "biomes",
    Layout.SingleFile(".json"),
    BiomeFile.serializer(),
    BiomeFile.SCHEMA
) {
    override fun canonical(value: BiomeFile) = value.copy(schema = schemaRef)

    override fun validate(value: BiomeFile, ctx: ResourceContext) = BiomeValidator.validate(value, ctx.sink, ctx.game)

    /**
     * The game sorts every feature of a step of all a world's biomes into one
     * order, and refuses a world whose biomes list two of them in opposite
     * orders: of the project's own biomes, two that do would break any world
     * that has both. (The game's own biomes can't be looked at from here; the
     * docs say to keep their order.)
     */
    override fun crossCheck(value: BiomeFile, ctx: KindContext) {
        fun keys(features: List<ResourceRef>) = features.map {
            ctx.references.resolve(RefKind.PLACED_FEATURE, it.text)?.toString()
                ?: it.text
        }
        for ((step, features) in value.features) {
            val mine = keys(features)
            for ((other, file) in ctx.models(BiomeKind).entries.sortedBy { it.key }) {
                if (other == ctx.id) continue
                val theirs = keys(file.features[step].orEmpty())
                val inversion = inversion(mine, theirs) ?: continue
                val (first, second) = inversion
                ctx.sink.report(
                    ProblemCodes.BIOME_FEATURE_ORDER,
                    "\"$first\" comes before \"$second\" here, but after it in biome \"$other\": " +
                        "the game can't place both in one world, so list them in the same order",
                    CanonicalJson.childPath("$.features", step.id),
                    listOf(Location(ctx.placeOf(this, other).path, CanonicalJson.childPath("$.features", step.id)))
                )
                break
            }
        }
    }

    /** The first two features [mine] has in one order and [theirs] in the other, or null when they agree. */
    private fun inversion(mine: List<String>, theirs: List<String>): Pair<String, String>? {
        val at = theirs.withIndex().associate { (index, feature) -> feature to index }
        for (i in mine.indices) {
            val a = at[mine[i]] ?: continue
            for (j in i + 1 until mine.size) {
                val b = at[mine[j]] ?: continue
                if (b < a) return mine[i] to mine[j]
            }
        }
        return null
    }

    override fun compile(id: String, value: BiomeFile, ctx: ResourceContext) = value

    /**
     * The name the server knows a biome by, as a file in namespace [home] writes it: the game's as they are
     * (`minecraft:plains`, a tag `#minecraft:is_forest`), the project's and its packages' as `<namespace>:<id>`
     * (`ruby_grove` in `showcase` is `showcase:ruby_grove`). Null when it isn't shaped like a biome.
     */
    fun keyOf(text: String, home: String): String? = when {
        text.startsWith("#") -> "#" + GameIds.normalize(text.removePrefix("#"))
        RefKind.BIOME.isGame(text) -> text
        else -> ResourceRef(text).resolve(home)?.toString()
    }

    /** Its file in the game's format; none while a datapack's feature it names isn't in the pack ([BiomeJson.writes]). */
    override val datapack = DatapackFiles<BiomeFile> { key, value, ctx ->
        if (!BiomeJson.writes(value, ctx)) return@DatapackFiles emptyMap()
        mapOf("data/${key.namespace}/${BiomeJson.FOLDER}/${key.path}.json" to DatapackEntry.json(BiomeJson.of(value, ctx)))
    }

    /** It's the biome `<namespace>:<id>`. */
    override fun datapackEntries(id: String, value: BiomeFile) = mapOf(BiomeJson.FOLDER to setOf(id))

    /** A green, flowery meadow with sheep: starter content the user changes in the biome editor. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            BiomeFile(
                climate = BiomeClimate(temperature = BiomeClimate.DEFAULT_TEMPERATURE, downfall = BiomeClimate.DEFAULT_DOWNFALL),
                colors = BiomeColors(sky = "#78a7ff", water = BiomeColors.DEFAULT_WATER, grass = "#79c05a", foliage = "#59ae30"),
                spawns = mapOf(
                    SpawnCategory.ANIMAL to listOf(BiomeSpawn("minecraft:sheep", weight = 12, group = SpawnRange(min = 2, max = 4)))
                ),
                features = mapOf(
                    GenerationStep.VEGETAL_DECORATION to
                        listOf(ResourceRef("minecraft:flower_default"), ResourceRef("minecraft:patch_grass_plain"))
                )
            )
        )
    )
}
