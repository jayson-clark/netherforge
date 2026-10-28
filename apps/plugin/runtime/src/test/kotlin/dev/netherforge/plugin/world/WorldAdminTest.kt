package dev.netherforge.plugin.world
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.store.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Managed worlds (`nf.worlds.create`, `load`, `copy`, `world:unload`),
 * borders and structures, against the fake server: which worlds a project may
 * take away, copies that finish later (to a callback or a waiting task),
 * what survives a reload or a restart, and the mistakes that are errors.
 */
class WorldAdminTest {
    private fun manifest(managed: String) =
        """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "managedWorlds": [$managed] }"""

    private val arenaMap = mapOf("maps/arena/level.dat" to "a level", "maps/arena/region/r.0.0.mca" to "chunks")

    @Test
    fun `a project creates, loads and unloads its own worlds, and only those`() {
        TestServer(
            mapOf(
                TestServer.MANIFEST to manifest("\"nether\""),
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local arena = nf.worlds.create("arena_1", { generator = "void", environment = "nether", seed = 7, structures = false })
                    check("created", arena:name(), "arena_1")
                    check("found", nf.worlds.get("arena_1"), arena)
                    check("managed", arena:is_managed(), true)
                    check("main world isn't", nf.worlds.default():is_managed(), false)
                    check("named in managedWorlds", nf.worlds.get("nether"):is_managed(), true)
                    fails("taken", function() nf.worlds.create("arena_1") end, "already a world named \"arena_1\"")
                    fails("not an id", function() nf.worlds.create("Arena") end, "can't be a new world's name")
                    fails("bad generator", function() nf.worlds.create("x", { generator = "moon" }) end, "generator")
                    fails("bad terrain", function() nf.worlds.create("x", { terrain = "moon" }) end, "isn't one of the project's terrains")
                    fails("unknown option", function() nf.worlds.create("x", { size = 3 }) end, "size")
                    fails("main world", function() nf.worlds.default():unload() end, "main world can't be unloaded")
                    fails("not managed", function() nf.worlds.get("other"):unload() end, "isn't this project's to unload")
                    fails("into itself", function() arena:unload({ move_players_to = arena }) end, "move_players_to is the world being unloaded")
                    fails("load a bad name", function() nf.worlds.load("../up") end, "isn't a world's name")
                    check("load what isn't saved", nf.worlds.load("nowhere"), nil)
                    check("load a loaded one", nf.worlds.load("arena_1"), arena)
                    """
                )
            ),
            start = false
        ).use { server ->
            server.platform.worlds.worldNames += "other"
            server.start()
            assertEquals(listOf("done"), server.runChecks())
            val manager = server.platform.worldManager
            assertEquals(
                "void nether 7 false false",
                manager.created.getValue("arena_1").let {
                    "${it.generator} ${it.environment} ${it.seed} ${it.structures} ${it.keepSpawnLoaded}"
                }
            )

            // A player in it is moved out (to the spawn of move_players_to) before it goes.
            val alex = server.platform.players.add("Alex", Location("arena_1", 3.0, 70.0, 3.0))
            server.write(
                "modules/t/init.lua",
                LuaChecks.command(
                    """
                    local arena = nf.worlds.get("arena_1")
                    check("unload", arena:unload({ move_players_to = nf.worlds.get("nether") }), true)
                    check("gone", arena:exists(), false)
                    check("still the project's", arena:is_managed(), true)
                    check("unload again", arena:unload(), false)
                    check("load it back", nf.worlds.load("arena_1"), arena)
                    check("delete", arena:unload({ delete = true }), true)
                    check("forgotten", arena:is_managed(), false)
                    check("deleted", nf.worlds.load("arena_1"), nil)
                    """
                )
            )
            server.reload("modules/t/init.lua")
            assertEquals(listOf("done", "done"), server.runChecks())
            assertEquals("nether", alex.location.world)
            assertEquals(listOf("arena_1 save=true", "arena_1 save=false"), manager.unloads)
            assertEquals(listOf("arena_1"), manager.deleted)
            // Loaded back as what it was made as: the server doesn't keep a world's environment.
            assertEquals("arena_1 nether", manager.loads.first())
        }
    }

    @Test
    fun `a world the server keeps isn't unloaded`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local arena = nf.worlds.create("kept")
                    check("kept", arena:unload(), false)
                    check("still there", arena:exists(), true)
                    """
                )
            )
        ).use { server ->
            server.platform.worldManager.kept += "kept"
            assertEquals(listOf("done"), server.runChecks())
        }
    }

    @Test
    fun `worlds a project created stay its own across restarts`() {
        TestServer(mapOf("modules/t/init.lua" to LuaChecks.command("nf.worlds.create(\"arena_1\")"))).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            assertEquals(mapOf("arena_1" to Store.OwnedWorld("normal")), server.runtime.store.worlds.of("test"))
            server.write("modules/t/init.lua", LuaChecks.command("check(\"managed\", nf.worlds.get(\"arena_1\"):is_managed(), true)"))
            server.restart()
            assertEquals(listOf("done", "done"), server.runChecks())
        }
    }

    @Test
    fun `a copy of a map arrives at its callback, on a later tick`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local result = nf.worlds.copy("arena", "arena_2", function(world, err)
                      log("copied " .. world:name() .. " " .. tostring(world:is_managed()) .. " " .. tostring(err))
                    end)
                    check("returns nothing", result, nil)
                    check("not yet", nf.worlds.get("arena_2"), nil)
                    fails("copying already", function() nf.worlds.copy("arena", "arena_2", function() end) end, "being copied already")
                    fails("unknown map", function() nf.worlds.copy("arenas", "x", function() end) end, "no map \"arenas\" in this project (did you mean \"arena\"?)")
                    fails("taken", function() nf.worlds.copy("arena", "world", function() end) end, "already a world named \"world\"")
                    fails("not an id", function() nf.worlds.copy("arena", "Arena 2", function() end) end, "can't be a new world's name")
                    fails("no callback, no task", function() nf.worlds.copy("arena", "arena_3") end, "nf.worlds.copy only works inside a task")
                    fails("a callback that isn't", function() nf.worlds.copy("arena", "arena_3", 5) end, "bad argument 'callback' (function or nil expected, got number)")
                    fails("a bad argument", function() nf.worlds.copy(7, "arena_3", function() end) end, "bad argument 'map'")
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            assertEquals(listOf("done", "copied arena_2 true nil"), server.output())
            val (folder, name) = server.platform.worldManager.copies.single()
            assertEquals("arena_2", name)
            assertEquals(server.project.resolve("maps/arena").toRealPath(), folder.toRealPath())
            // A mistake is located at the script's line, not inside the bindings.
            server.write("modules/t/init.lua", "-- one\n-- two\nnf.worlds.copy(\"arena\", \"Arena 2\", function() end)")
            server.reload("modules/t/init.lua")
            assertEquals("modules/t/init.lua", server.errors.last().source?.file)
            assertEquals(3, server.errors.last().source?.line)
        }
    }

    @Test
    fun `in a task, a copy waits and returns the world`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.task(function()
                      local world, err = nf.worlds.copy("arena", "arena_2")
                      log("task got " .. world:name() .. " " .. tostring(err))
                      local again = nf.worlds.copy("arena", "arena_3")
                      log("and " .. again:name())
                    end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            assertEquals(listOf("done"), server.output())
            server.tick()
            assertEquals(listOf("done", "task got arena_2 nil"), server.output())
            // The second copy started on that tick; it arrives on the next.
            server.tick()
            assertEquals(listOf("done", "task got arena_2 nil", "and arena_3"), server.output())
        }
    }

    @Test
    fun `a failed copy answers nil and why, and isn't the project's`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.task(function()
                      local world, err = nf.worlds.copy("arena", "arena_2")
                      log("got " .. tostring(world) .. ": " .. err)
                      check("not managed", nf.worlds.get("world"):is_managed(), false)
                    end)
                    nf.worlds.copy("arena", "arena_3", function(world, err) log("callback got " .. tostring(world) .. ": " .. err) end)
                    """
                )
            )
        ).use { server ->
            server.platform.worldManager.copyFailure = "disk full"
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            val why = "couldn't copy map \"arena\" into world \"arena_2\": couldn't copy its files (disk full)"
            assertEquals(listOf("done", "got nil: $why", "callback got nil: ${why.replace("arena_2", "arena_3")}"), server.output())
            assertTrue(
                server.platform.log.lines.any {
                    "Couldn't copy map \"arena\" into world \"arena_2\": couldn't copy its files (disk full)" in
                        it
                }
            )
            assertFalse("arena_2" in server.runtime.store.worlds.of("test"))
        }
    }

    @Test
    fun `a script that stops before its copy is done isn't called, but the world is still made`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.worlds.copy("arena", "arena_2", function(world) log("too late") end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.write("modules/t/init.lua", LuaChecks.command("check(\"made\", nf.worlds.get(\"arena_2\"):is_managed(), true)"))
            server.reload("modules/t/init.lua")
            server.tick()
            assertEquals(listOf("done", "done"), server.runChecks())
        }
    }

    @Test
    fun `a cancelled task's copy is still made, and nothing wakes it`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local task = nf.task(function()
                      nf.worlds.copy("arena", "arena_2")
                      log("woke")
                    end)
                    check("waiting", task:is_active(), true)
                    task:cancel()
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            assertEquals(0, server.runtime.session.async.waits(server.runtime.session.scripts.scopes().single()))
            server.tick(2)
            assertEquals(listOf("done"), server.output())
            assertTrue(server.platform.worlds.exists("arena_2"))
        }
    }

    @Test
    fun `a whole-project reload during a copy drops what the old session waited for, leaving the copy to load`() {
        TestServer(
            arenaMap + mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.worlds.copy("arena", "arena_2", function(world) log("too late") end)
                    nf.task(function() nf.worlds.copy("arena", "arena_3") log("task too late") end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.reload(TestServer.MANIFEST)
            server.tick()
            assertEquals(listOf("done"), server.output())
            // The files were copied, but loading them was the old session's next step: the copy is saved, and still the project's.
            assertFalse(server.platform.worlds.exists("arena_2"))
            server.write(
                "modules/t/init.lua",
                LuaChecks.command(
                    "check(\"loaded\", nf.worlds.load(\"arena_2\"):is_managed(), true) check(\"and\", nf.worlds.load(\"arena_3\") ~= nil, true)"
                )
            )
            server.reload("modules/t/init.lua")
            assertEquals(listOf("done", "done"), server.runChecks())
        }
    }

    @Test
    fun `a world's border`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local border = nf.worlds.default():border()
                    check("same handle", nf.worlds.default():border(), border)
                    check("size", border:size(), 59999968)
                    check("center", border:center(), vec3(0, 0, 0))
                    check("set center", border:set_center(vec3(10, 64, -20)), true)
                    check("moved", border:center(), vec3(10, 0, -20))
                    check("set size", border:set_size(100), true)
                    check("set size over time", border:set_size(50, { ticks = 600 }), true)
                    check("sized", border:size(), 50)
                    check("damage", border:damage().amount .. " " .. border:damage().buffer, "0.2 5.0")
                    check("set damage", border:set_damage({ amount = 1.5 }), true)
                    check("damage kept the buffer", border:damage().amount .. " " .. border:damage().buffer, "1.5 5.0")
                    check("set warning", border:set_warning({ ticks = 100 }), true)
                    check("warning", border:warning().distance .. " " .. border:warning().ticks, "5 100")
                    check("inside", border:contains(vec3(30, 64, -40)), true)
                    check("outside", border:contains(vec3(40, 64, -20)), false)
                    check("another world's place", border:contains(nf.worlds.get("nether"):location(vec3(10, 64, -20))), false)
                    fails("too small", function() border:set_size(0.5) end, "size must be from 1 to 59999968")
                    fails("negative time", function() border:set_size(10, { ticks = -1 }) end, "ticks can't be negative")
                    fails("too far out", function() border:set_center(vec3(4e7, 0, 0)) end, "can't be more than 29999984 blocks out")
                    fails("negative damage", function() border:set_damage({ buffer = -1 }) end, "damage.buffer can't be negative")
                    fails("negative warning", function() border:set_warning({ distance = -1 }) end, "warning.distance must be from 0")
                    local arena = nf.worlds.create("arena")
                    local gone = arena:border()
                    arena:unload()
                    check("gone world's border", gone:size(), nil)
                    check("gone world's set", gone:set_size(10), false)
                    check("gone world's handle", arena:border(), nil)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            assertEquals(listOf("World(name=world) 100.0 over 0", "World(name=world) 50.0 over 600"), server.platform.borders.sizes)
        }
    }

    @Test
    fun `a player's own border`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local player = nf.players.get("Alex")
                    nf.worlds.default():border():set_size(500)
                    check("none yet", player:has_own_border(), false)
                    local own = player:border()
                    check("now", player:has_own_border(), true)
                    check("same handle", player:border(), own)
                    check("a copy of the world's", own:size(), 500)
                    check("set", own:set_size(16), true)
                    check("theirs", own:size(), 16)
                    check("world's untouched", nf.worlds.default():border():size(), 500)
                    check("no world, so only the position counts", own:contains(nf.worlds.get("nether"):location(vec3(1, 0, 1))), true)
                    check("reset", player:reset_border(), true)
                    check("reset again", player:reset_border(), false)
                    check("handle answers nil", own:size(), nil)
                    check("and false", own:set_size(3), false)
                    """
                )
            ),
            start = false
        ).use { server ->
            server.player("Alex")
            server.start()
            assertEquals(listOf("done"), server.runChecks())
        }
    }

    @Test
    fun `structures saved by scripts and the project's, placed in any world`() {
        TestServer(
            mapOf(
                "structures/house.nbt" to "size 2 1 1\n0 0 0 minecraft:oak_planks\n1 0 0 minecraft:glass",
                "structures/broken.nbt" to "not a structure",
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    world:set_block(vec3(0, 70, 0), "minecraft:gold_block")
                    world:set_block(vec3(1, 71, 0), "minecraft:stone")
                    check("save", world:save_structure("reset", vec3(1, 71, 1), vec3(0, 70, 0), { entities = true }), true)
                    check("exists", nf.structures.exists("reset"), true)
                    check("size", nf.structures.size("reset"), vec3(2, 2, 2))
                    check("project's exists", nf.structures.exists("house"), true)
                    check("project's size", nf.structures.size("house"), vec3(2, 1, 1))
                    check("unknown", nf.structures.exists("nope"), false)
                    check("unknown size", nf.structures.size("nope"), nil)
                    check("place saved", world:place_structure("reset", vec3(10.7, 70, 10)), true)
                    check("placed", world:block(vec3(10, 70, 10)):kind(), "minecraft:gold_block")
                    check("place the project's", nf.worlds.get("nether"):place_structure("house", vec3(0, 80, 0), { rotation = -90, mirror = "front_back", integrity = 0.5, entities = false }), true)
                    fails("save over the project's", function() world:save_structure("house", vec3(0, 0, 0), vec3(1, 1, 1)) end, "is one of the project's structures (structures/house.nbt)")
                    fails("save a bad id", function() world:save_structure("Bad", vec3(0, 0, 0), vec3(1, 1, 1)) end, "can't name a structure")
                    fails("too big", function() world:save_structure("big", vec3(0, 0, 0), vec3(48, 1, 1)) end, "a structure is at most 48")
                    fails("place unknown", function() world:place_structure("hous", vec3(0, 0, 0)) end, "no structure \"hous\"")
                    fails("rotation", function() world:place_structure("house", vec3(0, 0, 0), { rotation = 45 }) end, "rotation must be a multiple of 90")
                    fails("integrity", function() world:place_structure("house", vec3(0, 0, 0), { integrity = 2 }) end, "integrity must be between 0 and 1")
                    fails("unreadable", function() world:place_structure("broken", vec3(0, 0, 0)) end, "structures/broken.nbt can't be read as a structure")
                    fails("unreadable size", function() nf.structures.size("broken") end, "structures/broken.nbt can't be read")
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            val saved = server.project.resolve(".netherforge/data/.nf/structures/reset.nbt")
            assertTrue(Files.isRegularFile(saved), "saved outside the project's files")
            assertEquals(
                listOf(
                    "reset.nbt world 10 70 10 rotation=0 mirror=none integrity=1.0 entities=true",
                    "house.nbt nether 0 80 0 rotation=270 mirror=front_back integrity=0.5 entities=false"
                ),
                server.platform.structures.placed
            )
            assertFalse(Files.exists(server.project.resolve("structures/reset.nbt")))
        }
    }

    @Test
    fun `saving a structure reloads it, and a map's change waits for the next copy`() {
        TestServer(
            arenaMap + mapOf(
                "structures/house.nbt" to "size 1 1 1\n0 0 0 minecraft:oak_planks",
                "modules/t/init.lua" to LuaChecks.command("nf.worlds.default():place_structure(\"house\", vec3(0, 90, 0))")
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.write("structures/house.nbt", "size 1 1 1\n0 0 0 minecraft:glass")
            val structure = server.reload("structures/house.nbt").resources.single()
            assertEquals("structure:house", structure.label)
            assertTrue(structure.ok)
            assertEquals(listOf(server.project.resolve("structures/house.nbt")), server.platform.structures.forgotten)
            assertEquals(listOf("done", "done"), server.runChecks())
            assertEquals("minecraft:glass", server.platform.worlds.state("world", 0, 90, 0))

            server.write("maps/arena/level.dat", "a new level")
            val world = server.reload("maps/arena/level.dat").resources.single()
            assertEquals("map:arena", world.label)
            assertTrue(world.ok)
            assertEquals(0, world.reattached)
        }
    }

    private fun spawnManifest(worlds: String) =
        """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "worlds": $worlds }"""

    @Test
    fun `netherforge json sets the spawn rates of the worlds it names, when they load and on reload`() {
        TestServer(
            mapOf(
                TestServer.MANIFEST to spawnManifest(
                    """{ "world": { "spawnLimits": { "monster": 30 }, "spawnIntervals": { "monster": 2 } },
                         "arena_1": { "spawnLimits": { "animal": 0 } },
                         "elsewhere": { "spawnLimits": { "ambient": 1 } } }"""
                ),
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    check("limit", world:spawn_limit("monster"), 30)
                    check("interval", world:spawn_interval("monster"), 2)
                    check("not named", world:spawn_limit("animal"), 10)
                    check("not set elsewhere", nf.worlds.get("nether"):spawn_limit("monster"), 70)
                    -- A script changes it; a reload puts the file's back.
                    world:set_spawn_limit("monster", 5)
                    local arena = nf.worlds.create("arena_1")
                    check("applied when created", arena:spawn_limit("animal"), 0)
                    check("only what's named", arena:spawn_limit("monster"), 70)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            val worlds = server.platform.worlds
            assertEquals(5, worlds.spawnLimit("world", SpawnCategory.MONSTER))
            server.write(
                TestServer.MANIFEST,
                spawnManifest("""{ "world": { "spawnLimits": { "monster": 31 } }, "arena_1": { "spawnIntervals": { "animal": 9 } } }""")
            )
            server.reload(TestServer.MANIFEST)
            assertEquals(31, worlds.spawnLimit("world", SpawnCategory.MONSTER))
            assertEquals(9, worlds.spawnInterval("arena_1", SpawnCategory.ANIMAL))
            // A world the file names that isn't loaded is configured when anyone loads it.
            server.write(
                TestServer.MANIFEST,
                spawnManifest("""{ "elsewhere": { "spawnLimits": { "ambient": 1 } } }""")
            )
            server.reload(TestServer.MANIFEST)
            worlds.worldNames += "elsewhere"
            server.platform.raise.worldLoad(GameEvent.World("elsewhere"))
            assertEquals(1, worlds.spawnLimit("elsewhere", SpawnCategory.AMBIENT))
        }
    }

    @Test
    fun `netherforge json sets the game rules of the worlds it names, and says which it can't`() {
        TestServer(
            mapOf(
                TestServer.MANIFEST to spawnManifest(
                    """{ "world": { "gameRules": { "keep_inventory": true, "minecraft:random_tick_speed": 0, "do_insomnia": false } } }"""
                )
            )
        ).use { server ->
            val worlds = server.platform.worlds
            assertEquals(true, worlds.gameRule("world", "minecraft:keep_inventory"))
            assertEquals(0, worlds.gameRule("world", "minecraft:random_tick_speed"))
            assertTrue(
                server.platform.log.lines.any {
                    "do_insomnia" in it && "doesn't have" in it
                },
                server.platform.log.lines.joinToString("\n")
            )
        }
    }
}
