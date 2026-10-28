package dev.netherforge.plugin.session
import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.bridge.ReloadedResource
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private fun scripted(script: String) = mapOf(
        "centities/c/centity.json" to TestServer.scriptedCentity(),
        "centities/c/script.lua" to script
    )

    @Test
    fun `saving a centity's script reattaches live instances to it`() {
        TestServer(scripted("log('v1 load')\nnf.on('unload', function() log('v1 unload') end)")).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            val entities = server.platform.entities.of(instance.id).map { it.id }.toSet()

            server.write("centities/c/script.lua", "log('v2 load')")
            val result = server.reload("centities/c/script.lua")

            assertEquals(listOf(ReloadedResource("test", "centity", "c", ok = true, reattached = 1)), result.resources)
            assertEquals(listOf("v1 load", "v1 unload", "v2 load"), server.logs)
            assertEquals(entities, server.platform.entities.of(instance.id).map { it.id }.toSet(), "same entities, not new ones")
        }
    }

    @Test
    fun `several saves to one centity reload it once`() {
        TestServer(scripted("log('load')")).use { server ->
            server.runtime.session.centities.spawn("c", at)
            val result = server.reload("centities/c/script.lua", "centities/c/centity.json", "README.md", ".netherforge/data/x")
            assertEquals(listOf("centity:c"), result.resources.map { it.label })
            assertEquals(listOf("load", "load"), server.logs)
        }
    }

    @Test
    fun `editing the definition adds, removes and respawns entities to match`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val tower = server.runtime.session.centities.spawn("tower", at)!!
            val base = server.platform.entities.display(tower.id, "base").id
            server.write(
                "centities/tower/centity.json",
                """
                {
                  "nodes": {
                    "base": {
                      "display": { "type": "text", "text": "now text" },
                      "hitbox": {}
                    },
                    "extra": { "parent": "base", "display": { "type": "block", "block": "minecraft:glass" } }
                  },
                  "script": { "file": "script.lua" }
                }
                """
            )
            val result = server.reload("centities/tower/centity.json")
            assertTrue(result.resources.single().ok, "${result.resources}")
            val entities = server.platform.entities.of(tower.id)
            assertEquals(setOf("base", "extra"), entities.filter { it.role == EntityRole.DISPLAY }.map { it.tag.node }.toSet())
            assertEquals(setOf("base"), entities.filter { it.role == EntityRole.HITBOX }.map { it.tag.node }.toSet())
            val newRoot = server.platform.entities.display(tower.id, "base")
            assertTrue(newRoot.id != base, "a block display can't become a text display; it's respawned")
            assertEquals("now text", (newRoot.display as TextDisplay).text)
        }
    }

    @Test
    fun `a broken script is reported at its line and fixed by the next save`() {
        TestServer(scripted("log('fine')")).use { server ->
            server.runtime.session.centities.spawn("c", at)
            server.write("centities/c/script.lua", "do\n  log('broken'\nend\n")
            val broken = server.reload("centities/c/script.lua").resources.single()

            assertFalse(broken.ok)
            val problem = broken.problems.single()
            assertEquals("centities/c/script.lua", problem.file)
            assertEquals(3, problem.line)
            assertEquals("script.error", problem.code)
            assertEquals(SourceRef("centities/c/script.lua", 3), server.errors.single().source)

            server.write("centities/c/script.lua", "log('fixed')")
            assertTrue(server.reload("centities/c/script.lua").resources.single().ok)
            assertEquals(listOf("fine", "fixed"), server.logs)
        }
    }

    @Test
    fun `a centity file that doesn't parse keeps the last good version running`() {
        TestServer(scripted("this:on('tick', function() end)\nthis:on('click', function() log('still here') end)")).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.write("centities/c/centity.json", "{ \"nodes\": ")
            val result = server.reload("centities/c/centity.json").resources.single()
            assertFalse(result.ok)
            assertTrue(result.problems.any { it.file == "centities/c/centity.json" && it.code == "parse" }, "${result.problems}")
            assertTrue(server.sent.filterIsInstance<Problems>().last().problems.isNotEmpty(), "the editor hears about it")

            val hitbox = server.platform.entities.hitbox(instance.id, "root").id
            server.runtime.events.entityClicked(hitbox, server.player().ref, dev.netherforge.plugin.platform.ClickButton.RIGHT, null)
            assertEquals(listOf("still here"), server.logs)
        }
    }

    @Test
    fun `deleting a centity leaves its instances inert until it's back`() {
        TestServer(scripted("log('load')")).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            val definition = server.project.resolve("centities/c/centity.json").toFile().readText()
            server.delete("centities/c/centity.json")
            server.delete("centities/c/script.lua")
            assertTrue(server.reload("centities/c/centity.json").resources.single().ok)
            assertTrue(server.runtime.session.centities.all().isEmpty())
            assertEquals(1, server.runtime.session.centities.count(), "kept as an inert record")
            assertTrue(server.platform.entities.of(instance.id).isNotEmpty(), "its entities stay")

            server.write("centities/c/centity.json", definition)
            server.write("centities/c/script.lua", "log('back')")
            assertEquals(1, server.reload("centities/c/centity.json").resources.single().reattached)
            assertEquals(listOf("load", "back"), server.logs)
        }
    }

    @Test
    fun `reloading a module restarts it and everything that required it`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to """
                    nf.on("unload", function() log("a unloading") end)
                    nf.commands.register("ping", function(ctx) ctx.sender:send_message("pong") end)
                    log("a v1")
                    return { version = 1 }
                """,
                "modules/b/init.lua" to """log("b sees " .. require("a").version)""",
                "modules/c/init.lua" to """log("c is independent")"""
            )
        ).use { server ->
            server.write(
                "modules/a/init.lua",
                """
                nf.commands.register("ping", function(ctx) ctx.sender:send_message("pong v2") end)
                return { version = 2 }
                """
            )
            val result = server.reload("modules/a/init.lua")
            assertEquals(listOf("module:a", "module:b"), result.resources.map { it.label })
            assertTrue(result.resources.all { it.ok })
            assertEquals(listOf("a v1", "b sees 1", "c is independent", "a unloading", "b sees 2"), server.logs)
            assertEquals(listOf("pong v2"), server.platform.commands.run(server.player(), "ping"))
        }
    }

    @Test
    fun `reloading a module restarts the centity scripts that required it`() {
        TestServer(
            mapOf(
                "modules/lib/init.lua" to "return { n = 1 }",
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "local lib = require('lib')\nlog('n=' .. lib.n)"
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", at)
            server.write("modules/lib/init.lua", "return { n = 2 }")
            val result = server.reload("modules/lib/init.lua")
            assertEquals(listOf("module:lib", "centity:c"), result.resources.map { it.label })
            assertEquals(listOf("n=1", "n=2"), server.logs)
        }
    }

    @Test
    fun `saving the manifest restarts everything`() {
        TestServer(TestServer.example("showcase")).use { server ->
            server.runtime.session.centities.spawn("tower", at)
            val result = server.reload("netherforge.json")
            val project = result.resources.single()
            assertEquals(Triple("showcase", null, null), Triple(project.pkg, project.kind, project.id), "the whole package")
            assertTrue(project.ok)
            assertEquals(1, project.reattached)
            assertTrue("tower" in server.platform.commands.registered)
        }
    }

    @Test
    fun `a project folder that isn't there is refused, not a crash`() {
        TestServer(emptyMap(), start = false).use { server ->
            server.project.toFile().deleteRecursively()
            server.start()
            assertFalse(server.runtime.session.running)
            assertEquals("project.no-manifest", server.runtime.currentProblems().single().code)
        }
    }

    @Test
    fun `a project in a newer format is refused, naming its version`() {
        TestServer(TestServer.example("showcase") + mapOf(TestServer.MANIFEST to TestServer.manifest(formatVersion = 2))).use { server ->
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == "project.format-version" }
            assertTrue("uses format 2" in problem.message, problem.message)
            assertTrue(server.platform.log.lines.any { "Not running" in it && "uses format 2" in it }, "${server.platform.log.lines}")
            assertFalse("tower" in server.platform.commands.registered, "nothing in it runs")
        }
    }

    @Test
    fun `a project for another Minecraft version is refused, and runs once it's fixed`() {
        val example = TestServer.example("showcase")
        val manifest = example.getValue(TestServer.MANIFEST) as String
        TestServer(example + mapOf(TestServer.MANIFEST to manifest.replace("\"26.3\"", "\"26.4\""))).use { server ->
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == "runtime.minecraft-unsupported" }
            assertTrue("targets Minecraft 26.4" in problem.message, problem.message)
            assertFalse("tower" in server.platform.commands.registered, "nothing in it runs")

            server.write(TestServer.MANIFEST, manifest)
            assertTrue(server.reload(TestServer.MANIFEST).resources.single().ok)
            assertTrue(server.runtime.session.running)
            assertTrue("tower" in server.platform.commands.registered)
        }
    }

    /** One flame a tick at the effect's origin, plus an end rod when [second] is set. */
    private fun effect(duration: Int, second: Boolean = false): String {
        val more = if (second) """, "b": { "particle": "minecraft:end_rod", "burst": 1, "every": 1 }""" else ""
        return """{ "duration": $duration, "emitters": { "a": { "particle": "minecraft:flame", "burst": 1, "every": 1 }$more } }"""
    }

    private fun effectServer() = TestServer(
        mapOf(
            "particles/p/effect.json" to effect(10),
            "modules/m/init.lua" to """
                local effect = nf.particles.play("p", vec3(0, 64, 0))
                effect:on("end", function(event) log("end " .. event.reason) end)
                nf.commands.register("again", function()
                  local ok, err = pcall(nf.particles.play, "p", vec3(0, 64, 0))
                  log(tostring(ok) .. " " .. tostring(err))
                end)
            """
        )
    ).also { it.player("Alex") }

    @Test
    fun `saving a particle effect moves live effects onto it, keeping their tick`() {
        effectServer().use { server ->
            server.tick(4)
            server.write("particles/p/effect.json", effect(10, second = true))
            val result = server.reload("particles/p/effect.json")
            assertEquals(listOf(ReloadedResource("test", "particle_effect", "p", ok = true, reattached = 1)), result.resources)
            server.tick()
            assertEquals(listOf("minecraft:flame", "minecraft:end_rod"), server.platform.particles.sent.last().second.map { it.particle })
            // Ticks 5 to 9 are what's left of its ten.
            server.tick(4)
            assertEquals(emptyList(), server.logs)
            server.tick()
            assertEquals(listOf("end finished"), server.logs)
        }
    }

    @Test
    fun `a shorter particle effect finishes a live one past its end`() {
        effectServer().use { server ->
            server.tick(6)
            server.write("particles/p/effect.json", effect(3))
            server.reload("particles/p/effect.json")
            server.tick()
            assertEquals(listOf("end finished"), server.logs)
        }
    }

    @Test
    fun `a particle effect with errors keeps its last good version, and a deleted one ends`() {
        effectServer().use { server ->
            server.tick(2)
            server.write("particles/p/effect.json", """{ "duration": 0 }""")
            val broken = server.reload("particles/p/effect.json").resources.single()
            assertFalse(broken.ok)
            assertTrue(broken.problems.any { it.code == "particle.duration" }, "${broken.problems}")
            server.platform.commands.runConsole("again")
            assertEquals(listOf("true Effect: 2"), server.logs, "the last good version still plays")

            server.delete("particles/p/effect.json")
            val deleted = server.reload("particles/p/effect.json").resources.single()
            assertTrue(deleted.ok)
            assertEquals(listOf("true Effect: 2", "end removed"), server.logs)
            server.platform.commands.runConsole("again")
            assertTrue("no particle effect \"p\" in this project" in server.logs.last(), server.logs.last())
        }
    }

    @Test
    fun `a particle effect that never had a good version can't be played`() {
        TestServer(
            mapOf(
                "particles/p/effect.json" to """{ "duration": 0 }""",
                "modules/m/init.lua" to """
                    local ok, err = pcall(nf.particles.play, "p", vec3(0, 64, 0))
                    log(tostring(ok) .. " " .. tostring(err))
                """
            )
        ).use { server ->
            val line = server.logs.single()
            assertTrue("particle effect \"p\" has errors, and there's no earlier version to play" in line, line)
        }
    }
}
