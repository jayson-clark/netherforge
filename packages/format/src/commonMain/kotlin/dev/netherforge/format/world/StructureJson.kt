package dev.netherforge.format.world

import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.datapack.DatapackContext
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Structures that generate, in the game's own datapack format, for the
 * start-up datapack. A structure `ns:id` becomes:
 *
 * - `worldgen/structure/id.json`, a `minecraft:jigsaw` structure starting from
 *   the pool `ns:id/start`;
 * - `worldgen/structure_set/id.json`, a `random_spread` placement of just it;
 * - `worldgen/template_pool/id/start.json` (its template alone) and
 *   `worldgen/template_pool/id/<name>.json` for each of its pools;
 * - `structure/<id>.nbt`, a copy of the template, and of every structure its
 *   pools pick from (the game finds a template at its resource path).
 *
 * Built from the files alone: the salt is made from the structure's name, so
 * building twice gives the same bytes and every server of a project places
 * it in the same squares.
 */
object StructureJson {
    /** Where the game reads a datapack's structure templates, under `data/<namespace>/`. */
    const val TEMPLATE_FOLDER = "structure"

    private const val STRUCTURES = "worldgen/structure"
    private const val STRUCTURE_SETS = "worldgen/structure_set"
    private const val POOLS = "worldgen/template_pool"

    /** The registry entries a structure that generates as [generation] is, as [id] in its namespace: its structure, set and pools. */
    fun entries(id: String, generation: StructureGeneration): Map<String, Set<String>> = mapOf(
        STRUCTURES to setOf(id),
        STRUCTURE_SETS to setOf(id),
        POOLS to (listOf(StructureGeneration.START_POOL) + generation.pools.keys).map { "$id/$it" }.toSet()
    )

    private const val NO_POOL = "minecraft:empty"

    /** The game's processor list that changes nothing; a single piece must name one. */
    private const val NO_PROCESSORS = "minecraft:empty"

    fun files(resources: Map<ResourceKey, StructureFile>, ctx: DatapackContext): Map<String, DatapackEntry> {
        val out = LinkedHashMap<String, DatapackEntry>()
        val templates = LinkedHashSet<ResourceKey>()
        val running = ctx.biomes
        for ((key, file) in resources.entries.sortedBy { it.key.toString() }) {
            val generation = file.generation ?: continue
            // A piece that isn't running (it has errors) would leave a pool the game can't load: the structure waits for it.
            val pieces = generation.pools.values.flatMap { pool -> pool.elements.map { ResourceKey(key.namespace, it.structure) } }
            if (pieces.any { it !in resources }) continue
            // So would a biome of the project's that isn't in the pack: the game refuses the whole pack over it.
            val biomes = generation.biomes.filterNot(RefKind.BIOME::isGame).map { ResourceRef(it).resolve(key.namespace) }
            if (biomes.any { it == null || it !in running }) continue
            val id = key.path
            val namespace = key.namespace
            val data = "data/$namespace"
            out["$data/$STRUCTURES/$id.json"] = DatapackEntry.json(structure(key, generation))
            out["$data/$STRUCTURE_SETS/$id.json"] = DatapackEntry.json(structureSet(key, generation))
            out["$data/$POOLS/$id/${StructureGeneration.START_POOL}.json"] =
                DatapackEntry.json(pool(listOf(key to 1), PoolProjection.RIGID))
            for ((name, pool) in generation.pools) {
                val elements = pool.elements.map { ResourceKey(namespace, it.structure) to (it.weight ?: 1) }
                out["$data/$POOLS/$id/$name.json"] =
                    DatapackEntry.json(pool(elements, pool.projection ?: PoolProjection.RIGID))
            }
            templates += key
            templates += pieces
        }
        for (key in templates) {
            val path = ctx.snapshot.placeOf(StructureKind, key.relativeTo(ctx.home).text).path
            out["data/${key.namespace}/$TEMPLATE_FOLDER/${key.path}.nbt"] = DatapackEntry.Copy(path)
        }
        return out
    }

    private fun structure(key: ResourceKey, generation: StructureGeneration): JsonElement = buildJsonObject {
        put("type", "minecraft:jigsaw")
        put("biomes", biomes(generation.biomes, key.namespace))
        put("step", (generation.step ?: GenerationStep.SURFACE_STRUCTURES).id)
        put("terrain_adaptation", (generation.terrainAdaptation ?: TerrainAdaptation.NONE).id)
        putJsonObject("spawn_overrides") {}
        put("start_pool", "$key/${StructureGeneration.START_POOL}")
        val pools = generation.pools.isNotEmpty()
        put("size", generation.depth ?: if (pools) StructureGeneration.DEFAULT_DEPTH_WITH_POOLS else 1)
        putJsonObject("start_height") { put("absolute", generation.startHeight ?: 0) }
        (generation.heightmap ?: StructureHeightmap.WORLD_SURFACE_WG).game?.let { put("project_start_to_heightmap", it) }
        put("max_distance_from_center", generation.maxDistance ?: StructureGeneration.DEFAULT_MAX_DISTANCE)
        put("use_expansion_hack", false)
    }

    /**
     * A tag stands alone as a string; ids are a list, as the game takes them, a project biome's its key in the
     * game (named from the structure's own namespace, [home]).
     */
    private fun biomes(biomes: List<String>, home: String): JsonElement {
        val first = biomes.first()
        if (first.startsWith("#")) return JsonPrimitive(BiomeKind.keyOf(first, home))
        return JsonArray(biomes.map { JsonPrimitive(requireNotNull(BiomeKind.keyOf(it, home)) { "\"$it\" isn't a biome" }) })
    }

    private fun structureSet(key: ResourceKey, generation: StructureGeneration): JsonElement = buildJsonObject {
        putJsonArray("structures") {
            add(
                buildJsonObject {
                    put("structure", key.toString())
                    put("weight", 1)
                }
            )
        }
        putJsonObject("placement") {
            put("type", "minecraft:random_spread")
            put("salt", generation.salt ?: saltOf(key))
            put("separation", generation.separation ?: StructureGeneration.DEFAULT_SEPARATION)
            put("spacing", generation.spacing ?: StructureGeneration.DEFAULT_SPACING)
        }
    }

    private fun pool(elements: List<Pair<ResourceKey, Int>>, projection: PoolProjection): JsonElement = buildJsonObject {
        put("fallback", NO_POOL)
        put(
            "elements",
            buildJsonArray {
                for ((structure, weight) in elements) {
                    add(
                        buildJsonObject {
                            put("weight", weight)
                            putJsonObject("element") {
                                put("element_type", "minecraft:single_pool_element")
                                put("location", structure.toString())
                                put("processors", NO_PROCESSORS)
                                put("projection", projection.id)
                            }
                        }
                    )
                }
            }
        )
    }

    /** A structure's own salt: FNV-1a over its name, non-negative, the same on every platform. */
    fun saltOf(key: ResourceKey): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in key.toString().encodeToByteArray()) {
            hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
        }
        return hash and Int.MAX_VALUE
    }
}
