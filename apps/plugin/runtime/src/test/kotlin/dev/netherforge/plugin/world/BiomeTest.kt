package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.testkit.BlockAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Biomes and new chunks in scripts, against the fake server: `block:biome()`,
 * `world:locate_biome` (both forms, what it asks the server, the mistakes that
 * are errors and the searches that fail), and `chunk_generated`, heard only
 * while a script listens.
 */
class BiomeTest {
    @Test
    fun `a block's biome is the world's there, and nil where nothing can be read`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    check("default", world:block(vec3(0, 64, 0)):biome(), "minecraft:plains")
                    check("set", world:block(vec3(3.5, 70.2, -4.9)):biome(), "minecraft:desert")
                    check("unloaded", world:block(vec3(100, 64, 100)):biome(), nil)
                    check("another world", nf.worlds.get("nether"):block(vec3(0, 64, 0)):biome(), "minecraft:plains")
                    """
                )
            ),
            start = false
        ).use { server ->
            server.platform.worlds.biomes[BlockAt("world", 3, 70, -5)] = "minecraft:desert"
            server.platform.worlds.unloaded += Triple("world", 6, 6)
            server.start()
            assertEquals(listOf("done"), server.runChecks())
        }
    }

    @Test
    fun `the project's biomes are bare in scripts, the game's written in full`() {
        TestServer(
            mapOf(
                "biomes/ruby_grove.json" to "{}",
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    check("project", world:block(vec3(3, 70, -5)):biome(), "ruby_grove")
                    check("game", world:block(vec3(0, 64, 0)):biome(), "minecraft:plains")
                    world:locate_biome("ruby_grove", { near = vec3(0, 64, 0) }, function(position, err)
                      log("grove " .. tostring(position) .. " " .. tostring(err))
                    end)
                    world:locate_biome("minecraft:plains", { near = vec3(0, 64, 0), radius = 1 }, function(position, err)
                      log("plains " .. tostring(position))
                    end)
                    """
                )
            ),
            start = false
        ).use { server ->
            server.platform.worlds.biomes[BlockAt("world", 3, 70, -5)] = "test:ruby_grove"
            server.start()
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            assertEquals(listOf("done", "grove vec3(3, 70, -5) nil", "plains vec3(0, 64, 0)"), server.output())
            assertEquals(
                listOf("world 0 64 0 6400 test:ruby_grove", "world 0 64 0 1 minecraft:plains"),
                server.platform.worlds.biomeSearches
            )
        }
    }

    @Test
    fun `a biome search arrives at its callback on a later tick, from the spawn unless told where`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    world:set_spawn_location(vec3(10, 70, -20))
                    local result = world:locate_biome("minecraft:desert", nil, function(position, err)
                      log("desert " .. tostring(position) .. " " .. tostring(err))
                    end)
                    check("returns nothing", result, nil)
                    world:locate_biome("minecraft:forest", { near = vec3(1000.7, 64, 0), radius = 200 }, function(position, err)
                      log("forest " .. tostring(position) .. " " .. tostring(err))
                    end)
                    world:locate_biome("minecraft:plains", { radius = 1 }, function(position, err)
                      log("plains " .. tostring(position) .. " " .. tostring(err))
                    end)
                    """
                )
            ),
            start = false
        ).use { server ->
            server.platform.worlds.biomes[BlockAt("world", 300, 40, 90)] = "minecraft:desert"
            server.platform.worlds.biomes[BlockAt("world", 1150, 64, 0)] = "minecraft:forest"
            server.start()
            assertEquals(listOf("done"), server.runChecks(), "nothing arrives during the call")
            server.tick()
            assertEquals(
                listOf(
                    "done",
                    "desert vec3(300, 40, 90) nil",
                    "forest vec3(1150, 64, 0) nil",
                    "plains vec3(10, 70, -20) nil"
                ),
                server.output()
            )
            assertEquals(
                listOf(
                    "world 10 70 -20 6400 minecraft:desert",
                    "world 1000 64 0 200 minecraft:forest",
                    "world 10 70 -20 1 minecraft:plains"
                ),
                server.platform.worlds.biomeSearches
            )
        }
    }

    @Test
    fun `in a task a search waits, and what finds nothing fails with why`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    nf.task(function()
                      local position, err = world:locate_biome("minecraft:desert", { near = vec3(0, 64, 0) })
                      log("found " .. tostring(position) .. " " .. tostring(err))
                      position, err = world:locate_biome("minecraft:desert", { near = vec3(0, 64, 0), radius = 100 })
                      log("near " .. tostring(position) .. " " .. tostring(err))
                      position, err = world:locate_biome("minecraft:the_end")
                      log("nowhere " .. tostring(position) .. " " .. tostring(err))
                    end)
                    """
                )
            ),
            start = false
        ).use { server ->
            server.platform.worlds.biomes[BlockAt("world", -500, 64, 30)] = "minecraft:desert"
            server.start()
            assertEquals(listOf("done"), server.runChecks())
            server.tick(3)
            assertEquals(
                listOf(
                    "done",
                    "found vec3(-500, 64, 30) nil",
                    "near nil no minecraft:desert within 100 blocks",
                    "nowhere nil no minecraft:the_end within 6400 blocks"
                ),
                server.output()
            )
        }
    }

    @Test
    fun `a biome the server lacks, a radius out of range or a world that's gone`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    local function none() end
                    fails("unknown", function() world:locate_biome("minecraft:moon", nil, none) end, "no biome \"minecraft:moon\" on this server")
                    fails("not an id", function() world:locate_biome("Dark Forest", nil, none) end, "\"Dark Forest\" isn't a biome id")
                    fails("a bare id is the project's", function() world:locate_biome("desert", nil, none) end, "no biome \"desert\" in the project (a project biome is biomes/desert.json); the game's are written in full, \"minecraft:desert\"")
                    fails("another namespace", function() world:locate_biome("acme:grove", nil, none) end, "\"acme\" is neither this project's namespace")
                    fails("radius 0", function() world:locate_biome("minecraft:plains", { radius = 0 }, none) end, "options.radius must be 1 to 6400, not 0")
                    fails("radius too far", function() world:locate_biome("minecraft:plains", { radius = 6401 }, none) end, "options.radius must be 1 to 6400, not 6401")
                    fails("a bad option", function() world:locate_biome("minecraft:plains", { within = 5 }, none) end, "within")
                    fails("no callback, no task", function() world:locate_biome("minecraft:plains") end, "World:locate_biome only works inside a task")
                    local moon = nf.worlds.create("moon", { generator = "void" })
                    moon:unload({ save = false })
                    moon:locate_biome("minecraft:plains", nil, function(position, err)
                      log("gone " .. tostring(position) .. " " .. tostring(err))
                    end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            assertEquals(listOf("done", "gone nil world \"moon\" has gone"), server.output())
        }
    }

    @Test
    fun `a new chunk is heard on its world and then nf, and only while something listens`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local subscriptions = {}
                    nf.commands.register("listen", function()
                      table.insert(subscriptions, nf.worlds.default():on("chunk_generated", function(event)
                        log("world", event.world:name(), event.x, event.z)
                      end))
                      table.insert(subscriptions, nf.on("chunk_generated", function(event)
                        log("nf", event.world:name(), event.x, event.z)
                        if event.x == 1 then
                          event.world:set_block(vec3(event.x * 16 + 8, 70, event.z * 16 + 8), "minecraft:gold_block")
                        end
                      end))
                    end)
                    nf.commands.register("stop", function()
                      for _, subscription in ipairs(subscriptions) do subscription:cancel() end
                    end)
                """.trimIndent()
            )
        ).use { server ->
            assertFalse(WatchedEvent.CHUNK_GENERATED in server.platform.watched)
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.CHUNK_GENERATED in server.platform.watched)
            server.platform.raise.chunkGenerated(GameEvent.Chunk("world", 1, -2))
            server.platform.raise.chunkGenerated(GameEvent.Chunk("nether", 0, 0))
            server.platform.commands.runConsole("stop")
            assertFalse(WatchedEvent.CHUNK_GENERATED in server.platform.watched)
            assertEquals(listOf("world\tworld\t1\t-2", "nf\tworld\t1\t-2", "nf\tnether\t0\t0"), server.logs)
            assertEquals("minecraft:gold_block", server.platform.worlds.state("world", 24, 70, -24))
        }
    }
}
