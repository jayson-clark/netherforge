package dev.netherforge.plugin.world
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.BlockData
import dev.netherforge.format.particle.DustData
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.netherforge.format.particle.ItemData as ItemSpawnData

/**
 * `World`, `Block`, `nf.worlds`, particles and sounds, against the fake
 * server: what scripts read and change, what reaches the platform, and the
 * mistakes that are errors.
 */
class WorldTest {
    @Test
    fun `nf worlds and what a world says about itself`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            check("default", world:name(), "world")
            check("same handle", nf.worlds.get("world"), world)
            check("all, default first", #nf.worlds.all(), 2)
            check("all names", nf.worlds.all()[2]:name(), "nether")
            check("unknown", nf.worlds.get("moon"), nil)
            check("exists", world:exists(), true)
            check("environment", world:environment(), "normal")
            check("nether", nf.worlds.get("nether"):environment(), "nether")
            check("heights", world:min_height() .. " " .. world:max_height(), "-64 320")
            check("spawn", world:spawn_location(), world:location(vec3(0, 64, 0), 0, 0))
            check("set spawn", world:set_spawn_location(vec3(5, 70, 5)), true)
            check("moved spawn", world:spawn_location().position, vec3(5, 70, 5))
            fails("spawn elsewhere", function() world:set_spawn_location(nf.worlds.get("nether"):location(vec3(0, 0, 0))) end, "is in nether, not world")
            check("time", world:time_of_day(), 0)
            check("set time", world:set_time_of_day(30000), true)
            check("time wraps", world:time_of_day(), 6000)
            check("days", world:day_count(), 0)
            world:set_time_of_day(-1)
            check("before sunrise", world:time_of_day(), 23999)
            check("weather", world:weather(), "clear")
            check("set weather", world:set_weather("thunder", { ticks = 100 }), true)
            check("thunder", world:weather(), "thunder")
            fails("bad weather", function() world:set_weather("snow") end, "bad argument 'weather'")
            fails("bad ticks", function() world:set_weather("rain", { ticks = 0 }) end, "ticks must be at least 1")
            check("game rule", world:game_rule("keep_inventory"), false)
            check("set game rule", world:set_game_rule("keep_inventory", true), true)
            check("game rule set", world:game_rule("minecraft:keep_inventory"), true)
            check("integer rule", world:game_rule("random_tick_speed"), 3)
            world:set_game_rule("random_tick_speed", 10)
            check("integer rule set", math.type(world:game_rule("random_tick_speed")), "integer")
            fails("unknown rule", function() world:game_rule("keep_inventroy") end, 'no game rule "keep_inventroy"')
            fails("wrong type", function() world:set_game_rule("keep_inventory", 1) end, "takes a boolean")
            fails("not whole", function() world:set_game_rule("random_tick_speed", 1.5) end, "takes a whole number")
            check("spawn limit", world:spawn_limit("monster"), 70)
            check("set spawn limit", world:set_spawn_limit("monster", 30), true)
            check("spawn limit set", world:spawn_limit("monster"), 30)
            check("limit is an integer", math.type(world:spawn_limit("monster")), "integer")
            check("other category untouched", world:spawn_limit("animal"), 10)
            check("other world untouched", nf.worlds.get("nether"):spawn_limit("monster"), 70)
            check("negative goes back", world:set_spawn_limit("monster", -1), true)
            check("server's again", world:spawn_limit("monster"), 70)
            check("spawn interval", world:spawn_interval("animal"), 400)
            check("set spawn interval", world:set_spawn_interval("monster", 2), true)
            check("spawn interval set", world:spawn_interval("monster"), 2)
            fails("misc has no cap", function() world:spawn_limit("misc") end, "bad argument 'category'")
            fails("limit not whole", function() world:set_spawn_limit("monster", 1.5) end, "bad argument 'limit'")
            fails("interval not a number", function() world:set_spawn_interval("monster", "fast") end, "bad argument 'ticks'")
            check("players", #world:players(), 1)
            check("nobody in the nether", #nf.worlds.get("nether"):players(), 0)
            check("player world", nf.players.get("Alex"):world(), world)
            check("explode", world:explode(vec3(1, 64, 1), 4, { fire = true }), true)
            fails("negative power", function() world:explode(vec3(1, 64, 1), -1) end, "power can't be below 0")
            check("lightning", world:strike_lightning(vec3(2, 64, 2), { effect_only = true }), true)
            fails("option typo", function() world:strike_lightning(vec3(2, 64, 2), { effect = true }) end, "unknown field 'options.effect'")
            check("chunk loaded", world:is_chunk_loaded(vec3(100, 0, 100)), false)
            check("load chunk", world:load_chunk(vec3(100, 0, 100)), true)
            check("now loaded", world:is_chunk_loaded(vec3(100, 0, 100)), true)
            local tostring_world = tostring(world:location(vec3(1, 2, 3)))
            check("location tostring", tostring_world, 'location("world", vec3(1, 2, 3))')
            fails("pitch without yaw", function() world:location(vec3(1, 2, 3), nil, 10) end, "a pitch needs a yaw")
            """,
            setup = { server ->
                server.player("Alex")
                server.platform.worlds.unloaded += Triple("world", 6, 6)
            },
            after = { server ->
                assertEquals(listOf("world Vec3(x=1.0, y=64.0, z=1.0) power=4.0 fire=true break=true"), server.platform.worlds.explosions)
                assertEquals(listOf("world Vec3(x=2.0, y=64.0, z=2.0) effect_only=true"), server.platform.worlds.lightning)
                assertEquals("thunder" to 100, server.platform.worlds.weathers["world"])
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a world that has gone answers nil and false`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    -- Handles are names: one kept from before the world unloaded still answers.
                    local nether = nf.worlds.get("nether")
                    ${LuaChecks.HELPERS}
                    nf.commands.register("check", function()
                      log(nf.worlds.get("nether"), nether:exists(), nether:environment(), nether:time_of_day(), nether:block(vec3(0, 0, 0)))
                      log(nether:set_block(vec3(0, 0, 0), "minecraft:stone"), nether:fill_blocks(vec3(0, 0, 0), vec3(1, 1, 1), "minecraft:stone"))
                      log(nether:raycast(vec3(0, 70, 0), vec3.down, 10), nether:spawn_particle("minecraft:flame", vec3(0, 0, 0)), #nether:players())
                      fails("still checks the particle", function() nether:spawn_particle("minecraft:flam", vec3(0, 0, 0)) end, 'no particle "minecraft:flam"')
                      fails("still checks the block state", function() nether:set_block(vec3(0, 0, 0), "minecraft:nope") end, 'no block "minecraft:nope"')
                    end)
                """
            )
        ).use { server ->
            server.platform.worlds.worldNames -= "nether"
            server.platform.commands.runConsole("check")
            assertEquals(listOf("nil\tfalse\tnil\tnil\tnil", "false\t0", "nil\tfalse\t0"), server.errors.map { it.message } + server.logs)
        }
    }

    @Test
    fun `blocks read and change the world live`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local ground = world:block(vec3(0.5, 63.9, 0.5))
            check("floored", ground:position(), vec3(0, 63, 0))
            check("same handle", world:block(vec3(0, 63, 0)), ground)
            check("location", ground:location(), world:location(vec3(0, 63, 0)))
            check("world", ground:world(), world)
            check("kind", ground:kind(), "minecraft:stone")
            check("solid", ground:is_solid(), true)
            check("air above", ground:relative("up"):is_air(), true)
            check("relative by a vector", ground:relative(vec3(1.5, 0, -1)):position(), vec3(1, 63, -1))
            check("relative down", ground:relative("down"):position(), vec3(0, 62, 0))
            fails("relative typo", function() ground:relative("upp") end, "bad argument 'offset'")
            check("light", ground:light_level(), 15)
            check("sky light", ground:sky_light_level(), 15)
            check("no inventory yet", ground:inventory(), nil)

            local spot = world:block(vec3(3, 64, 3))
            check("set", world:set_block(vec3(3, 64, 3), "oak_stairs[facing=east]"), true)
            check("defaults filled in", spot:state(), "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
            check("property", spot:property("facing"), "east")
            check("no such property", spot:property("color"), nil)
            check("properties", spot:properties().half, "bottom")
            check("set property", spot:set_property("facing", "north"), true)
            check("changed", spot:property("facing"), "north")
            fails("bad property", function() spot:set_property("colour", "red") end, 'has no property "colour"')
            fails("bad value", function() spot:set_property("facing", "up") end, "facing can't be \"up\"")
            check("quietly", spot:set_state("minecraft:gold_block", { update = false }), true)
            fails("unknown block", function() world:set_block(vec3(3, 64, 3), "minecraft:stonee") end, 'no block "minecraft:stonee"')
            fails("unknown property", function() world:set_block(vec3(3, 64, 3), "minecraft:stone[lit=true]") end, 'has no property "lit"')
            fails("not a state", function() world:set_block(vec3(3, 64, 3), "[]") end, "isn't a block state")
            fails("option typo", function() world:set_block(vec3(3, 64, 3), "minecraft:stone", { updates = false }) end, "unknown field 'options.updates'")
            fails("outside the world", function() world:block(vec3(0, 99999, 0)) end, "outside any world")

            check("break", spot:break_naturally({ kind = "minecraft:diamond" }), true)
            check("broken", spot:is_air(), true)
            check("air doesn't break", spot:break_naturally(), false)

            check("highest", world:highest_block(vec3(3, 200, 3)):position(), vec3(3, 63, 3))
            world:set_block(vec3(3, 80, 3), "minecraft:glass")
            check("highest now", world:highest_block(vec3(3, 0, 3)):kind(), "minecraft:glass")

            check("fill", world:fill_blocks(vec3(10, 64, 10), vec3(12, 65, 11), "minecraft:stone_bricks"), 12)
            check("filled", world:block(vec3(11, 65, 11)):kind(), "minecraft:stone_bricks")
            fails("too big", function() world:fill_blocks(vec3(0, 0, 0), vec3(100, 100, 100), "minecraft:air") end, "at most 32768")

            local far = world:block(vec3(100, 64, 100))
            check("unloaded kind", far:kind(), nil)
            check("unloaded air", far:is_air(), false)
            check("unloaded set", far:set_state("minecraft:stone"), false)
            check("unloaded highest", world:highest_block(vec3(100, 0, 100)), nil)
            check("unloaded data", far:data(), nil)
            check("unloaded fill", world:fill_blocks(vec3(99, 64, 99), vec3(100, 64, 100), "minecraft:stone"), 0)
            """,
            setup = { server -> server.platform.worlds.unloaded += Triple("world", 6, 6) },
            after = { server ->
                val changes = server.platform.blocks.changes
                assertTrue("world 3 64 3 minecraft:gold_block (no update)" in changes, changes.toString())
                assertEquals(listOf("world 3 64 3 minecraft:gold_block with minecraft:diamond"), server.platform.blocks.broken)
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `block data is one live table per position, saved with its chunk`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("use", function()
                      local block = nf.worlds.default():block(vec3(1, 64, 2))
                      local data = block:data()
                      data.uses = (data.uses or 0) + 1
                      log(data.uses, data == nf.worlds.default():block(vec3(1.5, 64.5, 2.5)):data())
                    end)
                """
            )
        ).use { server ->
            server.platform.commands.runConsole("use")
            server.platform.commands.runConsole("use")
            assertEquals(emptyMap(), server.platform.blocks.data, "nothing is written until the world saves")
            server.runtime.events.worldSaving("world")
            assertEquals("""{"uses":2}""", server.platform.blocks.data.values.single())
            server.platform.commands.runConsole("use")
            server.platform.raise.chunkUnload(GameEvent.Chunk("world", 0, 0))
            assertEquals("""{"uses":3}""", server.platform.blocks.data.values.single())
            server.platform.commands.runConsole("use")
            server.restart()
            assertEquals("""{"uses":4}""", server.platform.blocks.data.values.single(), "saved when the runtime stops")
            server.platform.commands.runConsole("use")
            assertEquals(listOf("1\ttrue", "2\ttrue", "3\ttrue", "4\ttrue", "5\ttrue"), server.logs)
        }
    }

    @Test
    fun `block data keeps typed values, the world by name, and gives them back typed`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local function data() return nf.worlds.default():block(vec3(1, 64, 2)):data() end
                    nf.commands.register("save", function(event)
                      local p = event.player
                      local d = data()
                      d.spot = vec3(1, 2.5, -3)
                      d.home = p:location()
                      d.realm = nf.worlds.default()
                      d.by = p
                      d.item = { kind = "minecraft:diamond", data = { at = vec3(0, 1, 0) } }
                    end)
                    nf.commands.register("show", function(event)
                      local p = event.player
                      local d = data()
                      log(tostring(d.spot), d.home == p:location(), d.home.world == nf.worlds.default())
                      log(d.realm == nf.worlds.default(), d.realm:name(), d.by == p, tostring(d.item.data.at))
                    end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "save")
            server.platform.commands.run(alex, "show")
            server.restart()
            server.platform.commands.run(alex, "show")
            val shown = listOf("vec3(1, 2.5, -3)\ttrue\ttrue", "true\tworld\ttrue\tvec3(0, 1, 0)")
            assertEquals(shown + shown, server.logs)
            val saved = server.platform.blocks.data.values.single()
            assertTrue("\"realm\":{\"${'$'}world\":[\"world\"]}" in saved, saved)
            assertTrue("\"home\":{\"${'$'}location\":[\"world\"," in saved, saved)
            assertTrue("\"spot\":{\"${'$'}vec3\":[1,2.5,-3]}" in saved, saved)
        }
    }

    @Test
    fun `what block data can't hold is reported and skipped`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("bad", function()
                      local data = nf.worlds.default():block(vec3(0, 64, 0)):data()
                      data.fn = function() end
                      data.kept = 1
                    end)
                """
            )
        ).use { server ->
            server.platform.commands.runConsole("bad")
            server.runtime.events.worldSaving("world")
            assertEquals("""{"kept":1}""", server.platform.blocks.data.values.single())
            assertTrue(
                server.platform.log.lines.any {
                    "Saving block:data() at world 0 64 0: data.fn: a function can't be saved" in it
                },
                server.platform.log.lines.toString()
            )
        }
    }

    @Test
    fun `a ray hits the first block or centity hitbox in its way`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local hit = world:raycast(vec3(0.5, 70, 0.5), vec3(0, -1, 0), 20)
            check("block", hit.block, world:block(vec3(0, 63, 0)))
            check("where", hit.position, vec3(0.5, 64, 0.5))
            check("normal", hit.normal, vec3.up)
            check("distance", hit.distance, 6)
            check("no centity", hit.centity, nil)
            check("too short", world:raycast(vec3(0.5, 70, 0.5), vec3(0, -1, 0), 5), nil)
            check("blocks off", world:raycast(vec3(0.5, 70, 0.5), vec3(0, -1, 0), 20, { blocks = false }), nil)

            local crate = nf.centities.spawn("crate", vec3(5, 64, 0))
            local sight = world:raycast(vec3(0, 64.5, 0.5), vec3(2, 0, 0), 20)
            check("centity", sight.centity, crate)
            check("node", sight.node, crate:node("root"))
            check("its face", sight.normal, vec3.west)
            check("hit point", sight.position, vec3(5, 64.5, 0.5))
            check("centities off", world:raycast(vec3(0, 64.5, 0.5), vec3(1, 0, 0), 20, { centities = false }), nil)
            crate:node("root"):set_clickable(false)
            check("not clickable", world:raycast(vec3(0, 64.5, 0.5), vec3(1, 0, 0), 20, { blocks = false }), nil)
            fails("no direction", function() world:raycast(vec3(0, 64, 0), vec3.zero, 5) end, "direction can't be zero")
            """,
            files = mapOf(
                "centities/crate/centity.json" to """
                    {
                      "nodes": {
                        "root": {
                          "display": { "type": "block", "block": "minecraft:stone" },
                          "hitbox": { "raycast": true }
                        }
                      }
                    }
                """
            )
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `particles are checked against the particle's kind and sent to who is near`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    ${LuaChecks.HELPERS}
                    nf.commands.register("fx", function()
                      local world = nf.worlds.default()
                      local alex = nf.players.get("Alex")
                      world:spawn_particle("flame", vec3(0, 65, 0), { count = 5, spread = vec3(0.2, 0.2, 0.2), speed = 0.01 })
                      world:spawn_particle("minecraft:dust", vec3(0, 65, 0), { color = "#FF8800", size = 2, viewers = { alex } })
                      world:spawn_particle("minecraft:block", vec3(0, 65, 0), { block_state = "oak_slab" })
                      world:spawn_particle("minecraft:item", vec3(0, 65, 0), { item = { kind = "minecraft:diamond", data = { at = vec3(1, 2, 3) } } })
                      world:spawn_particle("minecraft:flame", vec3(0, 65, 60), { force = true })
                      alex:spawn_particle("minecraft:entity_effect", vec3(1, 65, 1), { color = "#00ff00" })
                      fails("unknown", function() world:spawn_particle("minecraft:flam", vec3.zero) end, 'no particle "minecraft:flam"')
                      fails("not taken", function() world:spawn_particle("minecraft:flame", vec3.zero, { color = "#ffffff" }) end, "doesn't take options.color")
                      fails("needs a block", function() world:spawn_particle("minecraft:block", vec3.zero) end, "needs options.block_state")
                      fails("bad block", function() world:spawn_particle("minecraft:block", vec3.zero, { block_state = "minecraft:nope" }) end, 'no block "minecraft:nope"')
                      fails("needs an item", function() world:spawn_particle("minecraft:item", vec3.zero) end, "needs options.item")
                      fails("odd data", function() world:spawn_particle("minecraft:vibration", vec3.zero) end, "takes data NetherForge can't send")
                      fails("bad colour", function() world:spawn_particle("minecraft:dust", vec3.zero, { color = "orange" }) end, 'must be "#rrggbb"')
                      fails("bad size", function() world:spawn_particle("minecraft:dust", vec3.zero, { size = 9 }) end, "options.size must be 0.01 to 4")
                      fails("bad viewers", function() world:spawn_particle("minecraft:flame", vec3.zero, { viewers = { "Alex" } }) end, "bad argument 'options.viewers[1]' (Player expected, got string)")
                      fails("option typo", function() world:spawn_particle("minecraft:flame", vec3.zero, { colour = "#ffffff" }) end, "unknown field 'options.colour'")
                      fails("viewers for one", function() alex:spawn_particle("minecraft:flame", vec3.zero, { viewers = { alex } }) end, "only for that player")
                      log("done")
                    end)
                """
            ),
            start = false
        ).use { server ->
            server.player("Alex")
            server.platform.players.add("Sam", Location("world", 10.0, 64.0, 0.0))
            server.platform.players.add("Far", Location("world", 0.0, 64.0, 100.0))
            server.platform.players.add("Elsewhere", Location("nether", 0.0, 64.0, 0.0))
            server.start()
            server.platform.commands.runConsole("fx")
            assertEquals(listOf("done"), server.errors.map { it.message } + server.logs)
            val sent = server.platform.particles.sent
            fun names(i: Int) = sent[i].third.map { it.name }
            assertEquals(6, sent.size)
            val flame = sent[0].second.single()
            assertEquals("minecraft:flame", flame.particle)
            assertEquals(5, flame.count)
            assertEquals(Vec3(0.2, 0.2, 0.2), flame.offset)
            assertEquals(listOf("Alex", "Sam"), names(0), "everyone within 32 blocks, in that world")
            assertEquals(DustData(0xFF8800, 2.0), sent[1].second.single().data)
            assertEquals(listOf("Alex"), names(1))
            assertEquals(BlockData("minecraft:oak_slab[type=bottom,waterlogged=false]"), sent[2].second.single().data)
            // An item in an option table keeps typed values in its data, as an item argument does.
            val item = (sent[3].second.single().data as ItemSpawnData).item
            assertEquals("""{"${'$'}vec3":[1,2,3]}""", item.data?.get("at").toString())
            assertEquals(listOf("Alex", "Sam", "Far"), names(4), "forced: within 128 blocks")
            assertEquals(listOf("Alex"), names(5))
        }
    }

    @Test
    fun `sounds are a pack's or the server's, and an unknown one is an error`() {
        TestServer(
            mapOf(
                "resource_packs/fx/pack.json" to """{ "name": "FX", "description": "Sounds" }""",
                "resource_packs/fx/sounds/ding.ogg" to byteArrayOf(0x4f, 0x67, 0x67, 0x53),
                "modules/t/init.lua" to """
                    ${LuaChecks.HELPERS}
                    nf.commands.register("sfx", function()
                      local world = nf.worlds.default()
                      local alex = nf.players.get("Alex")
                      world:play_sound("block.note_block.pling", vec3(0, 64, 0), { pitch = 1.5, category = "block" })
                      world:play_sound("fx/ding", vec3(0, 64, 0))
                      alex:play_sound("minecraft:ui.button.click", { volume = 0.5 })
                      alex:stop_sound("fx/ding")
                      alex:stop_sound()
                      fails("unknown", function() world:play_sound("minecraft:nope", vec3.zero) end, 'no sound "minecraft:nope"')
                      fails("unknown pack sound", function() alex:play_sound("fx/dong") end, 'pack "fx" has no sound "dong"')
                      fails("unknown to stop", function() alex:stop_sound("fx/dong") end, 'pack "fx" has no sound "dong"')
                      fails("bad pitch", function() alex:play_sound("fx/ding", { pitch = 3 }) end, "options.pitch must be 0.5 to 2")
                      fails("bad category", function() alex:play_sound("fx/ding", { category = "loud" }) end, "bad argument 'options.category'")
                      log("done")
                    end)
                """
            ),
            start = false
        ).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runConsole("sfx")
            assertEquals(listOf("done"), server.errors.map { it.message } + server.logs)
            assertEquals(
                listOf(
                    "world minecraft:block.note_block.pling block 1.0 1.5",
                    "world test:fx/ding master 1.0 1.0",
                    "Alex minecraft:ui.button.click master 0.5 1.0"
                ),
                server.platform.sounds.played
            )
            assertEquals(listOf("Alex test:fx/ding", "Alex everything"), server.platform.sounds.stopped)
        }
    }

    @Test
    fun `a world's block events come before nf's, with a Block and the state then`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    world:on("block_break", function(event)
                      log("world", event.state, event.block:position(), event.block:world():name())
                      if event.state == "minecraft:bedrock" then
                        event:cancel()
                        event:stop()
                      end
                    end)
                    nf.on("block_break", function(event)
                      log("nf", event.state, event.cancelled)
                    end)
                    world:on("block_place", function(event)
                      log("placed", event.state)
                    end)
                    nf.worlds.get("nether"):on("block_place", function() log("never") end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val stone = BlockRef("world", 1, 2, 3, "minecraft:stone", "minecraft:stone")
            assertFalse(server.breaks(alex, stone))
            assertTrue(server.breaks(alex, stone.copy(id = "minecraft:bedrock", state = "minecraft:bedrock")))
            assertFalse(server.platform.raise.blockPlace(GameEvent.BlockPlace(alex.ref, stone, stone.state, stone)))
            assertEquals(
                listOf(
                    "world\tminecraft:stone\tvec3(1, 2, 3)\tworld",
                    "nf\tminecraft:stone\tfalse",
                    "world\tminecraft:bedrock\tvec3(1, 2, 3)\tworld",
                    "placed\tminecraft:stone"
                ),
                server.logs
            )
        }
    }
}
