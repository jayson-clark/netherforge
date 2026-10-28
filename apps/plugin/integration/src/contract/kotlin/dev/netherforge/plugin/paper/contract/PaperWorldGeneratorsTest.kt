package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.lua.LuajavaPlatform
import dev.netherforge.format.terrain.GeneratedLoot
import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.terrain.TerrainScriptFailure
import dev.netherforge.format.terrain.TerrainScripts
import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.ProjectGenerator
import dev.netherforge.plugin.platform.WorldSettings
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import org.bukkit.Bukkit
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A project's terrain on a real server (Paper alone: only a real server runs a chunk generator): the chunks of a
 * world made with one are what format's generator makes for the world's seed (columns' tops, layers, the sea, the floor,
 * an ore's custom block state, the biome areas, areas' own terrain blended across their borders, and decorations: blocks
 * and a structure read by the server's own loader, across chunk borders), the spawn is on dry ground, and publishing a
 * changed generator shapes only the chunks generated afterwards. A file's Lua stages run on the server's chunk threads and
 * make the same blocks as format's generator with the same script. Its areas are the project's biomes ([ContractBiomes],
 * registered by [ContractBootstrap] as a project's are): the server has them, `/locate biome` finds them, and the game
 * decorates the generator's ground with exactly the features a project biome lists, or a game biome's own.
 */
class PaperWorldGeneratorsTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    private val manager get() = platform.worldManager

    private fun generator(
        json: String,
        custom: Map<String, String> = emptyMap(),
        structures: Map<String, StructureTemplate> = emptyMap()
    ): ProjectGenerator {
        val file = (TerrainKind.parse(json, "terrain/test.json") as CanonicalJson.Parsed.Ok).value
        val terrain = TerrainCompiler.compile(file).withStructures(structures)
        val states = terrain.palette.map {
            when (it) {
                is TerrainBlock.Vanilla -> it.state
                is TerrainBlock.Custom -> custom.getValue(it.name)
            }
        }
        // The areas' biomes as the runtime resolves them: the project's in the contract server's namespace.
        return ProjectGenerator(
            terrain,
            states,
            terrain.biomes.associateWith {
                requireNotNull(BiomeKind.keyOf(it, ContractServer.NAMESPACE))
            }
        )
    }

    private val hills = """
        { "terrain": { "base": 70, "seaLevel": 66, "noises": { "hills": { "noise": { "frequency": 0.01, "octaves": 3 }, "amplitude": 12 } } },
          "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
          "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
          "floor": { "thickness": 3 },
          "ores": { "gold": { "block": "minecraft:gold_ore", "size": 12, "veins": 20, "minY": -30, "maxY": 40 },
                    "ruby": { "customBlock": "ruby_ore", "size": 8, "veins": 20, "minY": -30, "maxY": 40 } },
          "biomes": { "cold": { "biome": "${ContractBiomes.RICH}", "temperature": { "max": 0.0 } },
                      "warm": { "biome": "${ContractBiomes.BARE}", "temperature": { "min": 0.0 } } } }
    """.trimIndent()

    private val rubyState = "minecraft:note_block[instrument=custom_head,note=3,powered=true]"

    private fun worldNamed() = "nf_contract_gen_${WORLDS.incrementAndGet()}"

    private fun create(name: String, id: String, seed: Long) = main {
        assertTrue(
            manager.create(name, WorldSettings("normal", "normal", seed, structures = false, keepSpawnLoaded = false, terrain = id))
        )
    }

    private fun drop(name: String) {
        eventually("$name unloading") { manager.unload(name, save = false) }
        main { manager.delete(name) }.run()
    }

    @Test
    fun `a world made with a project generator has the chunks format's generator makes for its seed`() {
        val project = generator(hills, mapOf("ruby_ore" to rubyState))
        main { manager.publishGenerators(mapOf("contract_hills" to project)) }
        val name = worldNamed()
        create(name, "contract_hills", 20260714L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            assertEquals(-64, world.minHeight)
            val bound = project.terrain.bind(world.seed, world.minHeight, world.maxHeight)
            val expected = bound.generate(2, -3)
            val palette = project.terrain.palette
            var ores = 0
            var rubies = 0
            // A chunk nothing holds open is unloaded again: it's read in the same step it's loaded.
            main {
                assertTrue(platform.worlds.loadChunk(name, 2, -3))
                for (lx in 0 until 16 step 3) {
                    for (lz in 0 until 16 step 5) {
                        // Every block of the column, bottom to top.
                        for (y in -64 until 100) {
                            val want = project.states[expected[lx, y, lz]]
                            val got = assertNotNull(platform.blocks.get(name, 32 + lx, y, -48 + lz), "a block at $lx,$y,$lz")
                            if (want == "minecraft:air") {
                                assertTrue(got.air, "$lx,$y,$lz is air, not ${got.state}")
                            } else {
                                // The server may complete a state's default properties; compare the block, and a custom block's whole state.
                                assertEquals(want.substringBefore('['), got.state.substringBefore('['), "$lx,$y,$lz")
                                if (palette[expected[lx, y, lz]] is TerrainBlock.Custom) {
                                    assertEquals(rubyState, got.state)
                                    rubies++
                                }
                            }
                            if (want == "minecraft:gold_ore") ores++
                        }
                    }
                }
                // The bedrock floor (the lowest layer is all of it), and the top of the ground where the preview says.
                assertEquals("minecraft:bedrock", platform.blocks.get(name, 32, -64, -48)?.state)
                val top = bound.surfaceAt(33, -47)
                assertTrue(platform.blocks.get(name, 33, top, -47)?.air == false)
                assertTrue(platform.blocks.get(name, 33, maxOf(top, 66) + 1, -47)?.air == true)
            }
            assertTrue(ores + rubies > 0, "ores of both kinds are in the sampled columns ($ores, $rubies)")
            // Biomes are the areas' (the world's biome provider), the project's as the server's registry has them: sampled
            // where the generator says each is, through the platform as the runtime reads one.
            val areas = listOf(40 to -40, 300 to 900, -700 to 120, 1200 to -1500).map { (x, z) ->
                project.biomes.getValue(bound.biomeAt(x, z))
            }
            val found = listOf(40 to -40, 300 to 900, -700 to 120, 1200 to -1500).map { (x, z) ->
                main {
                    assertTrue(platform.worlds.loadChunk(name, x shr 4, z shr 4))
                    platform.worlds.biome(name, x, 70, z)
                }
            }
            assertEquals(areas, found)
        } finally {
            drop(name)
        }
    }

    @Test
    fun `a volume area is the biome of the places its ranges hold, height and all`() {
        val json = hills.replace(
            """"warm": { "biome": "${ContractBiomes.BARE}", "temperature": { "min": 0.0 } } }""",
            """"warm": { "biome": "${ContractBiomes.BARE}", "temperature": { "min": 0.0 } },
               "deep": { "biome": "${ContractBiomes.BARE}", "y": { "max": -16 }, "temperature": { "max": 0.0 } } }"""
        )
        val project = generator(json, mapOf("ruby_ore" to rubyState))
        main { manager.publishGenerators(mapOf("contract_volumes" to project)) }
        val name = worldNamed()
        create(name, "contract_volumes", 20260714L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = project.terrain.bind(world.seed, world.minHeight, world.maxHeight)
            // A cold column: the rich biome over the ground, the volume's below -16.
            val (x, z) = (0 until 400).map { it * 37 to it * -11 }.first { (x, z) -> bound.biomeAt(x, z) == ContractBiomes.RICH }
            val (above, below) = main {
                assertTrue(platform.worlds.loadChunk(name, x shr 4, z shr 4))
                platform.worlds.biome(name, x, 70, z) to platform.worlds.biome(name, x, -40, z)
            }
            assertEquals(project.biomes.getValue(ContractBiomes.RICH), above)
            assertEquals(project.biomes.getValue(ContractBiomes.BARE), below)
            assertEquals(project.biomes.getValue(bound.biomeAt(x, -40, z)), below)
        } finally {
            drop(name)
        }
    }

    @Test
    fun `a container a terrain generates is given the project's loot table as its chunk first loads`() {
        val json = hills.replace(
            """"floor": { "thickness": 3 },""",
            """"floor": { "thickness": 3 },
               "decorations": { "caches": { "block": "minecraft:barrel[facing=up]", "count": 6, "loot": "contract_loot" } },"""
        )
        val project = generator(json, mapOf("ruby_ore" to rubyState))
        main { manager.publishGenerators(mapOf("contract_loot" to project)) }
        val name = worldNamed()
        create(name, "contract_loot", 20260714L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = project.terrain.bind(world.seed, world.minHeight, world.maxHeight)
            val (cx, cz, places) = (0 until 64).asSequence().map { it % 8 to it / 8 }
                .map { (cx, cz) -> Triple(cx, cz, bound.generate(cx, cz).lootPlaces(cx, cz)) }
                .first { it.third.isNotEmpty() }
            val key = org.bukkit.NamespacedKey("netherforge", "loot")
            val tables = main {
                assertTrue(platform.worlds.loadChunk(name, cx, cz))
                places.map { place ->
                    val state = world.getBlockAt(place.x, place.y, place.z).state as org.bukkit.block.Container
                    state.persistentDataContainer.get(key, org.bukkit.persistence.PersistentDataType.STRING)
                }
            }
            assertEquals(places.map { "contract_loot" }, tables)
            // The game unpacks it as its own chests (here, broken): the runtime is asked for the project's table.
            val first = places.first()
            main {
                val state = world.getBlockAt(first.x, first.y, first.z).state as org.bukkit.loot.Lootable
                assertEquals(GeneratedLoot.KEY, state.lootTable?.key?.toString())
                world.getBlockAt(first.x, first.y, first.z).breakNaturally()
            }
            eventually("the runtime asked for the container's loot") {
                events.of("generatedLoot").any { it[0] == "contract_loot" }
            }
        } finally {
            drop(name)
        }
    }

    /** A file whose script raises ridges (height), caps them (terrain) and scatters lanterns (decorate) with its own noise and random. */
    private val scripted = """
        { "terrain": { "base": 70, "seaLevel": 40, "noises": { "hills": { "noise": { "frequency": 0.01 }, "amplitude": 6 } } },
          "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
          "script": { "blocks": ["minecraft:mossy_cobblestone", "minecraft:sea_lantern"], "noises": { "ridges": { "fractal": "ridged", "frequency": 0.02 } } },
          "biomes": { "only": { "biome": "${ContractBiomes.BARE}" } } }
    """.trimIndent()

    private val stages = """
        local terrain = ...
        local ridges = terrain.noise("ridges")
        local stages = {}
        function stages.height(x, z, height)
          return height + math.max(0, ridges:at(x, z)) * 10
        end
        function stages.terrain(chunk)
          for x = chunk:min_x(), chunk:min_x() + 15 do
            for z = chunk:min_z(), chunk:min_z() + 15 do
              local top = terrain.height(x, z)
              if top > 74 then chunk:fill(x, top - 1, z, x, top, z, "minecraft:mossy_cobblestone") end
            end
          end
        end
        function stages.decorate(chunk)
          for _ = 1, 6 do
            local x, z = chunk:min_x() + math.random(0, 15), chunk:min_z() + math.random(0, 15)
            chunk:set(x, terrain.height(x, z) + 1, z, "minecraft:sea_lantern")
          end
        end
        return stages
    """.trimIndent()

    @Test
    fun `a generator's Lua stages run on the server's chunk threads and make what format's generator makes`() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<TerrainScriptFailure>()
        val compiled = TerrainCompiler.compile(
            (TerrainKind.parse(scripted, "terrain/test.json") as CanonicalJson.Parsed.Ok).value,
            "terrain/test.lua"
        )
        val scripts = TerrainScripts(LuajavaPlatform, mapOf("terrain/test.lua" to stages)) { failures += it }
        val biomes = compiled.biomes.associateWith { requireNotNull(BiomeKind.keyOf(it, ContractServer.NAMESPACE)) }
        val project = ProjectGenerator(compiled, compiled.palette.map { (it as TerrainBlock.Vanilla).state }, biomes, scripts)
        main { manager.publishGenerators(mapOf("contract_scripted" to project)) }
        val name = worldNamed()
        create(name, "contract_scripted", 99L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = compiled.bind(world.seed, world.minHeight, world.maxHeight, TerrainScripts(LuajavaPlatform, scripts.sources) {})
            var scripted = 0
            for ((cx, cz) in listOf(0 to 0, 3 to -2, -5 to 4)) {
                val expected = bound.generate(cx, cz)
                main {
                    assertTrue(platform.worlds.loadChunk(name, cx, cz))
                    for (lx in 0 until 16) {
                        for (lz in 0 until 16) {
                            for (y in 30 until 130) {
                                val want = project.states[expected[lx, y, lz]]
                                val got = assertNotNull(platform.blocks.get(name, cx * 16 + lx, y, cz * 16 + lz))
                                assertEquals(
                                    want.substringBefore('['),
                                    got.state.substringBefore('['),
                                    "${cx * 16 + lx},$y,${cz * 16 + lz}"
                                )
                                if (want == "minecraft:mossy_cobblestone" || want == "minecraft:sea_lantern") scripted++
                            }
                        }
                    }
                }
            }
            assertTrue(scripted > 0, "the script's blocks are in the world")
            assertEquals(emptyList(), failures.toList())
        } finally {
            drop(name)
        }
    }

    private fun facing(state: String) = BlockState.parse(state)?.properties?.get("facing")

    /** Every block compared, so its areas are the contract's biomes without features: only the file decorates. */
    private val decorated = """
        { "terrain": { "base": 70, "seaLevel": 40, "noises": { "hills": { "noise": { "frequency": 0.01 }, "amplitude": 8 } }, "blend": 16 },
          "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
          "decorations": {
            "flowers": { "block": "minecraft:poppy", "count": 24, "biomes": ["low"], "on": ["minecraft:grass_block"] },
            "pillars": { "structure": "pillar", "count": 3 },
            "glow": { "block": "minecraft:glowstone", "placement": "underground", "count": 30, "maxY": 40 }
          },
          "climate": { "temperature": { "frequency": 0.02 }, "jitter": { "amplitude": 12 } },
          "biomes": { "low": { "biome": "${ContractBiomes.RICH}", "temperature": { "max": 0.0 } },
                      "high": { "biome": "${ContractBiomes.BARE}", "temperature": { "min": 0.0 }, "terrain": { "base": 90, "scale": 2.0 } } } }
    """.trimIndent()

    @Test
    fun `decorations and areas' own terrain are what format's generator makes, a structure as the server read it`() {
        // A pillar of three blocks, an L at its top, saved by the server and read back as a generator places it.
        val file = server.directory().resolve("pillar.nbt")
        val template = main {
            place(at(-20, 3, -8), "minecraft:oak_log[axis=y]")
            place(at(-20, 4, -8), "minecraft:oak_log[axis=y]")
            place(at(-20, 5, -8), "minecraft:gold_block")
            place(at(-19, 5, -8), "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
            place(at(-19, 3, -8), "minecraft:air")
            place(at(-19, 4, -8), "minecraft:air")
            val (x, y, z) = block(at(-20, 3, -8))
            assertTrue(platform.structures.save(file, world, BlockVector(x, y, z), BlockVector(x + 1, y + 2, z), entities = false))
            assertNotNull(platform.structures.template(file))
        }
        val project = generator(decorated, structures = mapOf("pillar" to template))
        main { manager.publishGenerators(mapOf("contract_decorated" to project)) }
        val name = worldNamed()
        create(name, "contract_decorated", 4242L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = project.terrain.bind(world.seed, world.minHeight, world.maxHeight)
            val counts = HashMap<String, Int>()
            for ((cx, cz) in listOf(0 to 0, 1 to 0, -3 to 5)) {
                val expected = bound.generate(cx, cz)
                main {
                    assertTrue(platform.worlds.loadChunk(name, cx, cz))
                    // Every block of every column from the bottom up to above the highest ground, by its whole state.
                    for (lx in 0 until 16) {
                        for (lz in 0 until 16) {
                            for (y in -64 until 140) {
                                val want = project.states[expected[lx, y, lz]]
                                val got = assertNotNull(platform.blocks.get(name, cx * 16 + lx, y, cz * 16 + lz)).state
                                val id = want.substringBefore('[')
                                assertEquals(id, got.substringBefore('['), "${cx * 16 + lx},$y,${cz * 16 + lz}")
                                // A turned structure's stairs face where format turned them.
                                if (id == "minecraft:oak_stairs") assertEquals(facing(want), facing(got), "the stairs' facing")
                                counts.merge(id, 1, Int::plus)
                            }
                        }
                    }
                }
            }
            for (id in listOf("minecraft:poppy", "minecraft:glowstone", "minecraft:gold_block")) {
                assertTrue((counts[id] ?: 0) > 0, "$id is in the chunks compared ($counts)")
            }
        } finally {
            drop(name)
        }
    }

    @Test
    fun `the spawn is on dry ground and the base height is the generator's`() {
        val project = generator(hills, mapOf("ruby_ore" to rubyState))
        main { manager.publishGenerators(mapOf("contract_hills" to project)) }
        val name = worldNamed()
        create(name, "contract_hills", 99L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = project.terrain.bind(world.seed, world.minHeight, world.maxHeight)
            val spawn = assertNotNull(main { platform.worlds.spawnLocation(name) })
            val column = bound.dryColumnNear(0, 0)
            if (column != null) {
                assertEquals(column.first, spawn.x.toInt())
                assertEquals(column.second, spawn.z.toInt())
            }
            assertTrue(spawn.y >= project.terrain.seaLevel, "the spawn is above the sea: ${spawn.y}")
        } finally {
            drop(name)
        }
    }

    /** 3D ground: leaning into overhangs, islands over it, caves and flowers on every top, and a density stage adding ledges. */
    private val threeD = """
        { "terrain": { "base": 70, "seaLevel": 40, "noises": { "hills": { "noise": { "frequency": 0.01 }, "amplitude": 6 } },
            "density": { "noises": { "o": { "noise": { "frequency": 0.03, "octaves": 2 }, "amplitude": 12, "squash": 2 } },
                         "islands": { "y": 140, "thickness": 24, "threshold": 0.1 } } },
          "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
          "caves": { "caverns": { "threshold": 0.4 } },
          "decorations": { "flowers": { "block": "minecraft:poppy", "count": 40 } },
          "script": {},
          "biomes": { "only": { "biome": "${ContractBiomes.BARE}" } } }
    """.trimIndent()

    private val densityStage = """
        return {
          density = function(x, y, z, value)
            if y >= 96 and y <= 104 and x % 32 == 0 then return value + 6 end
            return value
          end,
        }
    """.trimIndent()

    @Test
    fun `a 3D generator's chunks are what format's generator makes, islands and overhangs, and the heightmap is its topmost ground`() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<TerrainScriptFailure>()
        val compiled = TerrainCompiler.compile(
            (TerrainKind.parse(threeD, "terrain/test.json") as CanonicalJson.Parsed.Ok).value,
            "terrain/test.lua"
        )
        val scripts = TerrainScripts(LuajavaPlatform, mapOf("terrain/test.lua" to densityStage)) { failures += it }
        val biomes = compiled.biomes.associateWith { requireNotNull(BiomeKind.keyOf(it, ContractServer.NAMESPACE)) }
        val project = ProjectGenerator(compiled, compiled.palette.map { (it as TerrainBlock.Vanilla).state }, biomes, scripts)
        main { manager.publishGenerators(mapOf("contract_3d" to project)) }
        val name = worldNamed()
        create(name, "contract_3d", 31L)
        try {
            val world = main { Bukkit.getWorld(name)!! }
            val bound = compiled.bind(world.seed, world.minHeight, world.maxHeight, TerrainScripts(LuajavaPlatform, scripts.sources) {})
            var overhung = 0
            var flowers = 0
            for ((cx, cz) in listOf(0 to 0, 2 to -1, -3 to 3, 5 to 5)) {
                val expected = bound.generate(cx, cz)
                main {
                    assertTrue(platform.worlds.loadChunk(name, cx, cz))
                    for (lx in 0 until 16) {
                        for (lz in 0 until 16) {
                            val x = cx * 16 + lx
                            val z = cz * 16 + lz
                            var tops = 0
                            for (y in -63 until 200) {
                                val want = project.states[expected[lx, y, lz]]
                                val got = assertNotNull(platform.blocks.get(name, x, y, z)).state
                                assertEquals(want.substringBefore('['), got.substringBefore('['), "$x,$y,$z")
                                if (want == "minecraft:poppy") flowers++
                                val solid = want != "minecraft:air" && want != "minecraft:water" && want != "minecraft:poppy"
                                val above = project.states[expected[lx, y + 1, lz]]
                                if (solid && (above == "minecraft:air" || above == "minecraft:poppy")) tops++
                            }
                            if (tops > 1) overhung++
                            // The world's own heightmap (what the game asks where to put things) is the generator's topmost ground.
                            val top = bound.surfaceAt(x, z)
                            val flower = project.states[expected[lx, top + 1, lz]] == "minecraft:poppy"
                            assertEquals(
                                if (flower) top + 1 else top,
                                world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.WORLD_SURFACE),
                                "$x,$z"
                            )
                        }
                    }
                }
            }
            assertTrue(overhung > 0, "columns with more than one top")
            assertTrue(flowers > 0, "flowers on the tops")
            assertEquals(emptyList(), failures.toList())
            // The spawn is on the topmost ground of the column the generator picks.
            val spawn = assertNotNull(main { platform.worlds.spawnLocation(name) })
            val (sx, sz) = assertNotNull(bound.dryColumnNear(0, 0))
            assertEquals(sx, spawn.x.toInt())
            assertEquals(sz, spawn.z.toInt())
            assertEquals(bound.surfaceAt(sx, sz) + 1, spawn.y.toInt())
            bound.close()
        } finally {
            drop(name)
        }
    }

    @Test
    fun `a generator published again shapes only the chunks generated after it`() {
        main {
            manager.publishGenerators(
                mapOf(
                    "contract_flat" to generator("""{ "terrain": { "base": 64 }, "layers": [{ "block": "minecraft:dirt" }] }""")
                )
            )
        }
        val name = worldNamed()
        create(name, "contract_flat", 5L)
        try {
            main {
                assertTrue(platform.worlds.loadChunk(name, 5, 5))
                assertEquals("minecraft:dirt", platform.blocks.get(name, 80, 64, 80)?.state)
            }
            main {
                manager.publishGenerators(
                    mapOf(
                        "contract_flat" to generator("""{ "terrain": { "base": 90 }, "layers": [{ "block": "minecraft:sand" }] }""")
                    )
                )
            }
            // A chunk made now is the new file's; one made before is saved as it was, whatever is published.
            main {
                assertTrue(platform.worlds.loadChunk(name, 9, 9))
                assertEquals("minecraft:sand", platform.blocks.get(name, 150, 90, 150)?.state)
                assertTrue(platform.blocks.get(name, 150, 91, 150)?.air == true)
                // The chunk made before was saved when it unloaded, so it comes back as it was.
                assertTrue(platform.worlds.loadChunk(name, 5, 5))
                assertEquals("minecraft:dirt", platform.blocks.get(name, 80, 64, 80)?.state)
            }
            // A generator no longer published keeps generating with the last one the world had.
            main { manager.publishGenerators(emptyMap()) }
            main {
                assertTrue(platform.worlds.loadChunk(name, 12, 12))
                assertEquals("minecraft:sand", platform.blocks.get(name, 200, 90, 200)?.state)
            }
        } finally {
            drop(name)
        }
    }

    /** Flat grass at 64 over dirt, every column in one area of [biome]. */
    private fun flat(biome: String) = generator(
        """{ "terrain": { "base": 64 }, "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
             "biomes": { "all": { "biome": "$biome" } } }"""
    )

    /** What stands on the ground (y 65) in the chunks from 0,0 to 3,3 of a world, by block: the decorations. */
    private fun onTheGround(name: String): Map<String, Int> = main {
        val found = HashMap<String, Int>()
        for (cx in 0..3) {
            for (cz in 0..3) {
                assertTrue(platform.worlds.loadChunk(name, cx, cz))
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        val block = assertNotNull(platform.blocks.get(name, cx * 16 + x, 65, cz * 16 + z))
                        if (!block.air) found.merge(block.state.substringBefore('['), 1, Int::plus)
                    }
                }
            }
        }
        found
    }

    @Test
    fun `the game decorates the ground with exactly a project biome's features, or a game biome's own`() {
        main {
            manager.publishGenerators(
                mapOf(
                    "contract_bare" to flat(ContractBiomes.BARE),
                    "contract_meadow" to flat(ContractBiomes.MEADOW),
                    "contract_plains" to flat("minecraft:plains")
                )
            )
        }
        for ((id, expect) in listOf<Pair<String, (Map<String, Int>) -> Unit>>(
            // No features listed: nothing grows, whatever the game's own biomes have.
            "contract_bare" to { assertEquals(emptyMap(), it, "a biome without features has nothing on the ground") },
            // Its patches of grass, at least, in 16 chunks of grass (flowers and trees are rarer).
            "contract_meadow" to { assertTrue((it["minecraft:short_grass"] ?: 0) > 20, "the meadow's grass grows: $it") },
            // A game biome brings its own: plains has grass too.
            "contract_plains" to { assertTrue((it["minecraft:short_grass"] ?: 0) > 20, "the game's plains decorate: $it") }
        )) {
            val name = worldNamed()
            create(name, id, 11L)
            try {
                expect(onTheGround(name))
            } finally {
                drop(name)
            }
        }
        main { manager.publishGenerators(emptyMap()) }
    }

    @Test
    fun `locate biome finds a project biome in a world whose generator has it`() {
        val project = generator(hills, mapOf("ruby_ore" to rubyState))
        main { manager.publishGenerators(mapOf("contract_hills" to project)) }
        val name = worldNamed()
        create(name, "contract_hills", 20260714L)
        try {
            for (biome in listOf(ContractBiomes.RICH, ContractBiomes.BARE)) {
                val key = "${ContractServer.NAMESPACE}:$biome"
                val feedback = mutableListOf<Component>()
                val ran = main {
                    val sender = Bukkit.createCommandSender { feedback += it }
                    Bukkit.dispatchCommand(sender, "execute in ${Bukkit.getWorld(name)!!.key.asString()} run locate biome $key")
                }
                assertTrue(ran, "the command ran")
                val answer = feedback.filterIsInstance<TranslatableComponent>().map { it.key() }
                assertEquals(listOf("commands.locate.biome.success"), answer, "/locate biome $key: $feedback")
            }
        } finally {
            drop(name)
        }
    }

    @Test
    fun `a world of a project dimension type has its build limits, which the generator fills, and keeps it when loaded again`() {
        val deep = "${ContractServer.NAMESPACE}:${ContractBiomes.DEEP}"
        val odd = "${ContractServer.NAMESPACE}:${ContractBiomes.ODD}"
        main { assertTrue(deep in manager.dimensionTypes() && odd in manager.dimensionTypes(), "the datapack's dimension types") }
        val project = generator("""{ "terrain": { "base": 70 }, "layers": [{ "block": "minecraft:dirt", "thickness": 3 }], "floor": {} }""")
        main { manager.publishGenerators(mapOf("contract_deep" to project)) }
        val name = worldNamed()
        main {
            val settings = WorldSettings(
                "normal",
                "normal",
                7L,
                structures = false,
                keepSpawnLoaded = false,
                terrain = "contract_deep",
                dimensionType = deep
            )
            assertTrue(manager.create(name, settings))
        }
        try {
            fun check() = main {
                val world = Bukkit.getWorld(name)!!
                assertEquals(-128, world.minHeight)
                assertEquals(384, world.maxHeight)
                assertEquals(-128 to 384, platform.worlds.heights(name))
                assertTrue(platform.worlds.loadChunk(name, 0, 0))
                // The generator was bound to the world's own heights: its floor is at the bottom, stone above it, as format makes it.
                val expected = project.terrain.bind(world.seed, world.minHeight, world.maxHeight).generate(0, 0)
                for (y in listOf(-128, -127, -100, -65, 0, 40)) {
                    assertEquals(
                        project.states[expected[3, y, 5]].substringBefore('['),
                        platform.blocks.get(name, 3, y, 5)?.state?.substringBefore('['),
                        "y $y"
                    )
                }
                assertEquals("minecraft:bedrock", platform.blocks.get(name, 3, -128, 5)?.state)
                // Blocks can be set at both ends of the build limits.
                assertTrue(platform.blocks.set(name, 4, -128, 4, "minecraft:gold_block", false))
                assertTrue(platform.blocks.set(name, 4, 383, 4, "minecraft:gold_block", false))
                assertEquals("minecraft:gold_block", platform.blocks.get(name, 4, -128, 4)?.state)
                assertEquals("minecraft:gold_block", platform.blocks.get(name, 4, 383, 4)?.state)
            }
            check()
            // Unloaded and loaded again without being told: the server kept the dimension type with the world.
            eventually("$name unloading") { manager.unload(name, save = true) }
            main { assertTrue(manager.load(name, "normal", "contract_deep")) }
            check()
        } finally {
            drop(name)
        }
        // A world of the type with every field set is made too (the server read the whole type), and one the server
        // hasn't is refused before anything is written.
        val other = worldNamed()
        main {
            assertTrue(manager.create(other, WorldSettings("void", "normal", 1L, false, false, dimensionType = odd)))
            assertEquals(0 to 256, platform.worlds.heights(other))
        }
        drop(other)
    }

    private companion object {
        val WORLDS = AtomicInteger()
    }
}
