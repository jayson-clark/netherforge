package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.dimensiontype.DimensionTypeJson
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.datapack.StartupDatapackFiles
import dev.netherforge.plugin.paper.PaperDatapacks
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

/**
 * Gives the contract server the biomes and dimension types its terrain suites use ([ContractBiomes]), before the worlds load:
 * built the way the NetherForge plugin builds a project's start-up datapack (format's own biome files, for this
 * server's data pack format) and found through Paper's datapack discovery, so the server reads exactly what it would
 * read of a project's biomes and dimension types.
 */
class ContractBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY) { event ->
            val format = requireNotNull(PaperDatapacks().format) { "the server says which data pack format it reads" }
            val snapshot = Projects.load(MapProjectSource(ContractBiomes.project(format)), null)
            check(snapshot.problems.isEmpty()) { "the contract biomes are valid: ${snapshot.problems}" }
            val files = StartupDatapackFiles.build(snapshot, format, { _, _ -> "\"\"" }, mainWorld = null, readBytes = { null })
            val folder = context.dataDirectory.resolve("datapack")
            StartupDatapackFiles.write(folder, files)
            event.registrar().discoverPack(folder, "nf_contract") { it.autoEnableOnServerStart(true) }
        }
    }
}

/** The project biomes on the contract server, in [ContractServer.NAMESPACE]: what [ContractBootstrap] registers. */
object ContractBiomes {
    /** No features, so a world of it is exactly what its generator makes. */
    const val BARE = "bare"

    /** Every field but features: the server has to read them all. */
    const val RICH = "rich"

    /** A plains-like set of the game's features, in the order the game's plains lists them. */
    const val MEADOW = "meadow"

    /** A dimension type twice the overworld's depth and taller: -128 to 383. */
    const val DEEP = "deep"

    /** A dimension type with every field set: the server has to read them all. */
    const val ODD = "odd"

    /** A sky held through the night, its light tinted: its twins, and from 26.1 its world clock and timeline. */
    const val BLOOD_MOON = "blood_moon"

    /** Its clock's key, on a server with world clocks. */
    val BLOOD_MOON_CLOCK = "${ContractServer.NAMESPACE}:sky/${ContractServer.NAMESPACE}/$BLOOD_MOON"

    /**
     * The project for a server of data pack [format]: it targets 26.1.2 where the server has world clocks (a sky held
     * at night needs them), 1.21.11 before, where its sky darkens at night as the game's.
     */
    fun project(format: List<Int>): Map<String, String?> {
        val clocks = format.first() >= DimensionTypeJson.CLOCK_FORMAT
        val minecraft = if (clocks) "26.1.2" else "1.21.11"
        val darkens = if (clocks) """, "darkens": false""" else ""
        return base + mapOf(
            "netherforge.json" to
                """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "Contract", "namespace": "${ContractServer.NAMESPACE}", "version": "1.0.0", "minecraft": "$minecraft" }""",
            "skies/$BLOOD_MOON.json" to """{ "sky": "#5a0a0a", "fog": "#3a0606", "clouds": "#cc802020", "light": "#ff8080"$darkens }"""
        )
    }

    private val base: Map<String, String?> = mapOf(
        "biomes/$BARE.json" to "{}",
        // A terrain that fills containers: the start-up datapack then has the empty table generated containers are given.
        "loot/contract_loot.json" to
            """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:stick" } }] } } }""",
        "terrain/caches.json" to """{ "decorations": { "caches": { "block": "minecraft:barrel", "loot": "contract_loot" } } }""",
        "biomes/$RICH.json" to
            """
            { "climate": { "temperature": -0.5, "downfall": 0.9, "precipitation": true, "temperatureModifier": "frozen" },
              "colors": { "sky": "#f2a7c3", "fog": "#f7d3e0", "water": "#d94f7a", "waterFog": "#5a1028", "grass": "#c2406a",
                          "foliage": "#e0587f", "dryFoliage": "#8a4b2a", "grassModifier": "swamp" },
              "particle": { "particle": "minecraft:white_ash", "probability": 0.01 },
              "sounds": { "ambient": "minecraft:ambient.cave",
                          "mood": { "sound": "minecraft:ambient.cave", "tickDelay": 3000, "blockSearchExtent": 6, "offset": 1.5 },
                          "additions": { "sound": "minecraft:ambient.basalt_deltas.additions", "chance": 0.01 },
                          "music": { "sound": "minecraft:music.overworld.cherry_grove", "minDelay": 600, "maxDelay": 1200 } },
              "spawns": { "monster": [{ "entity": "minecraft:zombie", "weight": 100, "group": { "min": 2, "max": 4 } }],
                          "animal": [{ "entity": "minecraft:sheep", "weight": 12, "group": { "min": 2, "max": 2 } }],
                          "ambient": [{ "entity": "minecraft:bat" }] },
              "spawnCosts": { "minecraft:zombie": { "charge": 0.7, "energyBudget": 0.15 } } }
            """.trimIndent(),
        "dimension_types/$DEEP.json" to """{ "minY": -128, "height": 512 }""",
        "dimension_types/$ODD.json" to
            """
            { "minY": 0, "height": 256, "logicalHeight": 128, "skyLight": false, "ceiling": true, "ambientLight": 0.1,
              "fixedTime": true, "sky": "none", "colors": { "sky": "#102030", "fog": "#405060", "clouds": "#c0ffffff" },
              "cloudHeight": 300, "bedWorks": false, "respawnAnchorWorks": true, "piglinSafe": true, "raids": false,
              "ultrawarm": true, "monsterSpawnLight": { "min": 7, "max": 7 }, "monsterSpawnBlockLight": 15,
              "infiniburn": "#minecraft:infiniburn_nether", "coordinateScale": 8 }
            """.trimIndent(),
        "biomes/$MEADOW.json" to
            """{ "features": { "vegetal_decoration": ["minecraft:trees_plains", "minecraft:flower_plains", "minecraft:patch_grass_plain"] } }"""
    )
}
