package dev.netherforge.plugin.centity
import dev.netherforge.format.bridge.Log
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Location
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The natural spawner: rules, weights, caps per player, temporary instances, `keep` and the `centity_natural_spawn` event. */
class NaturalSpawningTest {
    /** A centity with one stone block and this `spawning` block; [script] names a Lua file beside it. */
    private fun centity(spawning: String, script: Boolean = false) = """
        {
          "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } },
          "spawning": $spawning${if (script) """, "script": { "file": "script.lua" }""" else ""}
        }
    """.trimIndent()

    /** Centities by id with their `spawning` JSON, and the scripts some have (by id). */
    private fun project(
        vararg centities: Pair<String, String>,
        scripts: Map<String, String> = emptyMap(),
        more: Map<String, String> = emptyMap()
    ): Map<String, Any> = buildMap {
        for ((id, spawning) in centities) put("centities/$id/centity.json", centity(spawning, id in scripts))
        for ((id, lua) in scripts) put("centities/$id/script.lua", lua)
        putAll(more)
    }

    private fun TestServer.seeded(): TestServer = apply { runtime.session.naturalSpawner.random = Random(7) }

    private fun TestServer.naturals(id: String? = null) = runtime.session.centities.naturals().filter { id == null || it.centity == id }

    private fun TestServer.alex(at: Location = Location("world", 0.5, 64.0, 0.5)) = platform.players.add("Alex", at)

    private fun distance(instance: Instance, from: Location) = hypot(instance.anchor.x - from.x, instance.anchor.z - from.z)

    @Test
    fun `centities appear 24 to 48 blocks from a player, up to their cap, and are temporary`() {
        TestServer(project("a" to """{ "cap": 3 }""")).use { server ->
            server.seeded()
            val player = server.alex()
            server.tick(400)
            val spawned = server.naturals("a")
            assertEquals(3, spawned.size, "the cap is per player")
            for (instance in spawned) {
                assertTrue(instance.natural)
                assertEquals(player.location.world, instance.anchor.world)
                val away = distance(instance, player.location)
                assertTrue(away in 23.5..48.8, "$away blocks away")
                assertEquals(64.0, instance.anchor.y, "on the ground, not in it")
                assertTrue(server.platform.entities.of(instance.id).all { !it.persistent }, "its entities aren't saved with the chunk")
            }
        }
    }

    @Test
    fun `a centity with no spawning block never appears, and a restart drops the temporary ones`() {
        val plain = """{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } } }"""
        TestServer(project("a" to """{ "cap": 2 }""", more = mapOf("centities/plain/centity.json" to plain))).use { server ->
            server.seeded()
            server.alex()
            server.tick(200)
            assertEquals(2, server.naturals().size)
            assertTrue(server.runtime.session.centities.all().none { it.centity == "plain" })
            assertEquals(2, server.platform.entities.displays.size)

            server.restart()
            assertEquals(emptyList(), server.runtime.session.centities.all(), "nothing temporary was saved")
            assertEquals(0, server.platform.entities.all.size, "and the entities left in the world went as strays")
        }
    }

    @Test
    fun `weights decide which of several is chosen`() {
        TestServer(project("rare" to """{ "weight": 1, "cap": 1000 }""", "common" to """{ "weight": 9, "cap": 1000 }""")).use { server ->
            server.seeded()
            server.alex()
            server.tick(2400)
            val rare = server.naturals("rare").size
            val common = server.naturals("common").size
            assertTrue(rare > 0, "even the rare one appears")
            assertTrue(common > rare * 5, "$common common against $rare rare")
        }
    }

    @Test
    fun `groups appear together, within the cap`() {
        TestServer(project("herd" to """{ "group": { "min": 3, "max": 3 }, "cap": 7 }""")).use { server ->
            server.seeded()
            server.alex()
            server.tick(200)
            assertEquals(7, server.naturals().size, "groups of three stop at the cap")
            val first = server.naturals().first()
            assertTrue(server.naturals().count { hypot(it.anchor.x - first.anchor.x, it.anchor.z - first.anchor.z) <= 8 } >= 3)
        }
    }

    @Test
    fun `the cap counts natural centities near each player, not ones a script made or ones kept`() {
        TestServer(project("a" to """{ "cap": 2 }""")).use { server ->
            server.seeded()
            val alex = server.alex()
            val centities = server.runtime.session.centities
            repeat(5) { assertNotNull(centities.spawn("a", alex.location)) }
            server.tick(200)
            assertEquals(2, server.naturals().size, "five by hand don't count")

            // A kept one is a normal centity from then on: room for another.
            assertTrue(centities.keep(server.naturals().first()))
            server.tick(200)
            assertEquals(2, server.naturals().size)

            // Another player far away has a cap of their own.
            server.platform.players.add("Blake", Location("world", 5000.5, 64.0, 5000.5))
            server.tick(200)
            assertEquals(4, server.naturals().size)
        }
    }

    @Test
    fun `a natural centity is removed when no player is within its despawn distance`() {
        TestServer(project("a" to """{ "cap": 2, "despawnDistance": 60 }""")).use { server ->
            server.seeded()
            val alex = server.alex()
            server.tick(100)
            val spawned = server.naturals()
            assertEquals(2, spawned.size)

            // Everything stands within 48 of where Alex was, so within 60 of five blocks away: nothing goes.
            alex.location = Location("world", 5.5, 64.0, 0.5)
            server.tick(60)
            assertTrue(spawned.all { server.runtime.session.centities.find(it.id) != null })

            alex.location = Location("world", 9000.5, 64.0, 9000.5)
            val before = spawned.map { it.id }
            server.tick(25)
            assertTrue(server.naturals().none { it.id in before }, "all gone once nobody is near")
            assertTrue(server.platform.entities.all.values.none { it.tag.instance in before })

            // With nobody online they go too.
            server.platform.players.byId.clear()
            server.tick(25)
            assertEquals(0, server.naturals().size)
        }
    }

    @Test
    fun `a player in another world doesn't keep a centity there, and a world filter is respected`() {
        TestServer(project("a" to """{ "worlds": ["nether"], "cap": 2 }""")).use { server ->
            server.seeded()
            val alex = server.alex()
            server.tick(200)
            assertEquals(0, server.naturals().size, "the player is in world, the centity is for the nether")
            alex.location = Location("nether", 0.5, 64.0, 0.5)
            server.tick(200)
            assertEquals(2, server.naturals().size)
            assertTrue(server.naturals().all { it.anchor.world == "nether" })
        }
    }

    @Test
    fun `light, height, biome and the block stood on are asked of the place`() {
        TestServer(
            project(
                "dark" to """{ "light": { "max": 7 } }""",
                "high" to """{ "height": { "min": 100 } }""",
                "low" to """{ "height": { "max": 70 } }""",
                "desert" to """{ "biomes": ["minecraft:desert"] }""",
                "sand" to """{ "blocks": ["minecraft:sand"] }""",
                "stone" to """{ "blocks": ["stone"] }"""
            )
        ).use { server ->
            server.seeded()
            server.alex()
            server.tick(300)
            val spawned = server.naturals().map { it.centity }.toSet()
            // Plains, full light, stone ground at y 64.
            assertEquals(setOf("low", "stone"), spawned)

            val worlds = server.platform.worlds
            worlds.defaultLight = 3
            worlds.defaultBiome = "minecraft:desert"
            server.tick(600)
            assertTrue("dark" in server.naturals().map { it.centity }, "dark places now")
            assertTrue("desert" in server.naturals().map { it.centity }, "a desert now")
            assertFalse("high" in server.naturals().map { it.centity })
            assertFalse("sand" in server.naturals().map { it.centity }, "no sand underfoot")
        }
    }

    @Test
    fun `a project biome is named by its id and is the namespaced biome on the server`() {
        TestServer(
            project("grove" to """{ "biomes": ["ruby_grove"] }""", more = mapOf("biomes/ruby_grove.json" to "{}"))
        ).use { server ->
            server.seeded()
            server.alex()
            server.tick(300)
            assertEquals(emptyList(), server.naturals(), "plains isn't the grove")
            // The server knows the project's biome as test:ruby_grove; `ruby_grove` in the file means that one.
            server.platform.worlds.defaultBiome = "minecraft:ruby_grove"
            server.tick(300)
            assertEquals(emptyList(), server.naturals(), "the game's namespace isn't the project's")
            server.platform.worlds.defaultBiome = "test:ruby_grove"
            server.tick(300)
            assertTrue(server.naturals("grove").isNotEmpty(), "spawned in the project's biome")
        }
    }

    @Test
    fun `the handler may refuse a centity or move it`() {
        val scripts = mapOf(
            "a" to """
                this:on("spawn", function() log("spawned", this:is_natural()) end)
            """.trimIndent()
        )
        val rules = """
                local refusals = 0
                nf.on("centity_natural_spawn", function(event)
                  log("asked", event.centity, event.player:name(), event.location.world:name())
                  if refusals < 3 then
                    refusals = refusals + 1
                    event:cancel()
                  else
                    event.location = event.location:offset(vec3(0, 2, 0))
                  end
                end)
        """.trimIndent()
        TestServer(project("a" to """{ "cap": 1 }""", scripts = scripts, more = mapOf("modules/rules/init.lua" to rules))).use { server ->
            server.seeded()
            server.alex()
            server.tick(300)
            val asked = server.logs.filter { it.startsWith("asked") }
            assertTrue(asked.size >= 4, "asked again after a refusal: $asked")
            assertTrue(asked.all { it == "asked\ta\tAlex\tworld" }, "$asked")
            val only = server.naturals().single()
            assertEquals(66.0, only.anchor.y, "moved up by the handler")
            assertEquals(listOf("spawned\ttrue"), server.logs.filter { it.startsWith("spawned") })
        }
    }

    @Test
    fun `keep makes a centity a normal one that is saved, stays, and survives a restart`() {
        val scripts = mapOf(
            "a" to """
                this:on("click", function(event)
                  log("keep", this:is_natural(), this:keep(), this:is_natural(), this:keep())
                end)
            """.trimIndent()
        )
        TestServer(project("a" to """{ "cap": 2, "despawnDistance": 60 }""", scripts = scripts)).use { server ->
            server.seeded()
            val alex = server.alex()
            server.tick(100)
            val (kept, other) = server.naturals()
            val hitbox = server.platform.entities.hitbox(kept.id, "root")
            assertTrue(server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null))
            assertEquals(listOf("keep\ttrue\ttrue\tfalse\tfalse"), server.logs)
            assertFalse(kept.natural)
            assertTrue(server.platform.entities.of(kept.id).all { it.persistent }, "saved with its chunk now")
            assertFalse(server.runtime.session.centities.keep(kept), "already normal")

            alex.location = Location("world", 9000.5, 64.0, 9000.5)
            server.tick(50)
            assertTrue(server.runtime.session.centities.find(kept.id) != null, "never removed for being far")
            assertEquals(null, server.runtime.session.centities.find(other.id), "the temporary one went")

            server.restart()
            assertEquals(listOf(kept.id), server.runtime.session.centities.all().map { it.id })
            assertFalse(server.runtime.session.centities.all().single().natural)
        }
    }

    @Test
    fun `keepOnInteract keeps a natural centity the first time a player clicks it, and only then`() {
        TestServer(project("a" to """{ "cap": 2, "keepOnInteract": true }""", "b" to """{ "cap": 1 }""")).use { server ->
            server.seeded()
            val alex = server.alex()
            server.tick(200)
            val (first, second) = server.naturals("a")
            val plain = server.naturals("b").single()
            fun click(instance: Instance): Boolean {
                val hitbox = server.platform.entities.hitbox(instance.id, "root")
                return server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            }

            assertTrue(first.natural && second.natural && plain.natural, "temporary until something keeps them")
            assertTrue(click(first))
            assertFalse(first.natural, "the click kept it")
            assertTrue(server.platform.entities.of(first.id).all { it.persistent }, "saved with its chunk now")
            assertTrue(second.natural, "only the clicked one")
            assertTrue(click(plain))
            assertTrue(plain.natural, "without the option a click keeps nothing")
        }
    }

    @Test
    fun `a reload keeps natural centities temporary`() {
        TestServer(project("a" to """{ "cap": 2 }""")).use { server ->
            server.seeded()
            server.alex()
            server.tick(100)
            val ids = server.naturals().map { it.id }.toSet()
            server.write("centities/a/centity.json", centity("""{ "cap": 2, "weight": 3 }"""))
            server.reload("centities/a/centity.json")
            assertEquals(ids, server.naturals().map { it.id }.toSet())
            assertTrue(server.naturals().all { it.natural })
        }
    }

    @Test
    fun `the work in one tick is bounded however many players there are`() {
        TestServer(project("a" to """{ "cap": 50 }""")).use { server ->
            server.seeded()
            repeat(40) { server.platform.players.add("p$it", Location("world", it * 1000.5, 64.0, 0.5)) }
            val before = server.platform.entities.spawned
            server.tick()
            // Two players' passes of four places each, at most, and a spawn makes three entities (anchor, display, hitbox).
            assertTrue(
                server.platform.entities.spawned - before <= 2 * 4 * 3,
                "${server.platform.entities.spawned - before} entities in one tick"
            )
        }
    }

    @Test
    fun `time of day, moon and weather are the world's, asked before any place is looked at`() {
        TestServer(
            project(
                "owl" to """{ "time": "night" }""",
                "lark" to """{ "time": { "min": 1000, "max": 2000 } }""",
                "wolf" to """{ "moonPhase": ["full_moon"], "time": "night" }""",
                "frog" to """{ "weather": ["rain"] }""",
                "storm" to """{ "weather": ["thunder"] }"""
            )
        ).use { server ->
            server.seeded()
            server.alex()
            val worlds = server.platform.worlds
            worlds.fullTimes["world"] = 6000L
            server.tick(300)
            assertEquals(emptySet(), server.naturals().map { it.centity }.toSet(), "noon, clear: none of them")

            worlds.fullTimes["world"] = 1500L
            server.tick(200)
            assertEquals(setOf("lark"), server.naturals().map { it.centity }.toSet())

            // Night of the second day: a waning gibbous moon, not a full one.
            worlds.fullTimes["world"] = 24_000L + 14_000L
            server.tick(200)
            assertTrue("owl" in server.naturals().map { it.centity })
            assertFalse("wolf" in server.naturals().map { it.centity })
            assertEquals(1, worlds.moonPhase("world"))

            // Eight days on, the moon is full again.
            worlds.fullTimes["world"] = 8 * 24_000L + 14_000L
            server.tick(400)
            assertTrue("wolf" in server.naturals().map { it.centity })

            // A thunderstorm is rain too, but rain isn't a thunderstorm.
            worlds.weathers["world"] = "rain" to null
            server.tick(400)
            assertTrue("frog" in server.naturals().map { it.centity })
            assertFalse("storm" in server.naturals().map { it.centity })
            worlds.weathers["world"] = "thunder" to null
            server.tick(400)
            assertTrue("storm" in server.naturals().map { it.centity })
        }
    }

    @Test
    fun `a when condition is asked last, by its name in the centity's package, and nothing appears without it`() {
        val events = """
                local blood_moon = false
                nf.spawning.condition("blood_moon", function(check)
                  log("checked", check.centity, check.player:name(), check.location.world:name(), check.location.position.y)
                  return blood_moon
                end)
        """.trimIndent()
        TestServer(
            project(
                "ghoul" to """{ "when": "blood_moon", "blocks": ["stone"] }""",
                "lost" to """{ "when": "nobody_registered_this" }""",
                "never" to """{ "when": "blood_moon", "blocks": ["minecraft:sand"] }""",
                more = mapOf("modules/events/init.lua" to events)
            )
        ).use { server ->
            server.seeded()
            server.alex()
            server.tick(200)
            assertEquals(0, server.naturals().size, "the condition says no")
            val checked = server.logs.filter { it.startsWith("checked") }
            assertTrue(checked.isNotEmpty() && checked.all { it == "checked\tghoul\tAlex\tworld\t64.0" }, "$checked")
            val warnings = server.sent.filterIsInstance<Log>().map { it.message }.filter { "nobody_registered_this" in it }
            assertEquals(1, warnings.size, "a missing condition is said once: $warnings")

            server.write("modules/events/init.lua", events.replace("local blood_moon = false", "local blood_moon = true"))
            server.reload("modules/events/init.lua")
            server.tick(200)
            assertEquals(setOf("ghoul"), server.naturals().map { it.centity }.toSet(), "a rule that doesn't fit is never asked about")
        }
    }

    @Test
    fun `a condition that errors counts as no, and one registered twice or badly named is an error`() {
        val events = """
                nf.spawning.condition("broken", function(check) error("boom") end)
                local ok, err = pcall(nf.spawning.condition, "broken", function() return true end)
                log("twice", ok, err)
                ok, err = pcall(nf.spawning.condition, "Bad Name", function() return true end)
                log("named", ok, err)
        """.trimIndent()
        TestServer(project("a" to """{ "when": "broken" }""", more = mapOf("modules/events/init.lua" to events))).use { server ->
            server.seeded()
            server.alex()
            server.tick(200)
            assertEquals(0, server.naturals().size)
            assertTrue(server.errors.any { "boom" in it.message }, "${server.errors}")
            val twice = server.logs.single { it.startsWith("twice") }
            assertTrue("already registered" in twice, twice)
            val named = server.logs.single { it.startsWith("named") }
            assertTrue("can't name a spawn condition" in named, named)
        }
    }

    @Test
    fun `a condition goes with the script that registered it`() {
        val events = """nf.spawning.condition("open", function() return true end)"""
        TestServer(project("a" to """{ "when": "open", "cap": 1 }""", more = mapOf("modules/events/init.lua" to events))).use { server ->
            server.seeded()
            server.alex()
            server.tick(200)
            assertEquals(1, server.naturals().size)
            server.write("modules/events/init.lua", "-- no condition any more")
            server.reload("modules/events/init.lua")
            server.runtime.session.centities.remove(server.naturals().single())
            server.tick(200)
            assertEquals(0, server.naturals().size)
        }
    }
}
