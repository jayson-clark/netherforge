package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.script.PerformanceConfig
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What many scripted centities cost the runtime: a three-node mob (body, head, name tag) whose script does what a
 * wandering mob's does, against cheaper ways of writing it, at 50 and 200 instances. It measures the main thread's
 * time in the runtime and its scripts on the fake platform (no Paper, no network), and counts the entity updates the
 * runtime asks the platform for, which are what Paper sends to every player in range. Not a pass/fail test: run it by
 * hand (`NETHERFORGE_BENCH=1 node tools/gradle.mjs :plugin:runtime:test --tests '*CentityBenchmark*' -i`).
 */
@EnabledIfEnvironmentVariable(named = "NETHERFORGE_BENCH", matches = "1")
class CentityBenchmark {
    private val centity = """
        {
          "nodes": {
            "body": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
            "head": { "parent": "body", "transform": { "translation": [0, 1, 0] }, "display": { "type": "block", "block": "minecraft:stone" } },
            "tag": { "parent": "body", "transform": { "translation": [0, 2, 0] }, "display": { "type": "text", "text": "Mob" } }
          },
          "animations": {
            "bob": {
              "loop": "loop", "length": 2,
              "tracks": { "head": { "rotation": [ { "time": 0, "value": [0, 0, 0] }, { "time": 1, "value": [0, 90, 0] }, { "time": 2, "value": [0, 0, 0] } ] } }
            }
          },
          "script": { "file": "script.lua" }
        }
    """.trimIndent()

    private val cases = linkedMapOf(
        // Walks in a circle and turns its head every tick: two calls in, a teleport and a rotation.
        "walk + head, every tick" to """
            local t = math.random() * 100
            local origin = this:position()
            local head = this:node("head")
            this:on("tick", function()
              t = t + 0.05
              this:teleport(origin + vec3(math.cos(t) * 3, 0, math.sin(t) * 3))
              head:set_rotation(vec3(0, (t * 57) % 360, 0))
            end)
        """,
        // The same, every 4 ticks: what the eye can't tell from smooth for a slow walk (displays interpolate).
        "walk + head, every 4 ticks" to """
            local t = math.random() * 100
            local origin = this:position()
            local head = this:node("head")
            this:on("tick", function()
              t = t + 0.2
              this:teleport(origin + vec3(math.cos(t) * 3, 0, math.sin(t) * 3))
              head:set_rotation(vec3(0, (t * 57) % 360, 0))
            end, { every = 4 })
        """,
        // Every tick, but nothing changes: the cost of a call in alone.
        "empty tick handler" to """
            this:on("tick", function() end)
        """,
        // No script per tick: a looping animation the runtime plays.
        "animation only" to """
            this:play_animation("bob")
        """,
        // Walking by pathing: the runtime moves it, the script only picks where.
        "move_to, script every 40 ticks" to """
            local origin = this:position()
            this:on("tick", function()
              this:move_to(origin + vec3(math.random(-6, 6), 0, math.random(-6, 6)))
            end, { every = 40 })
        """
    )

    @Test
    fun `what many scripted centities cost`() {
        for ((name, script) in cases) {
            for (count in listOf(50, 200)) {
                val (micros, updates) = measure(script, count)
                println(
                    "bench ${name.padEnd(32)} x${count.toString().padEnd(4)} ${"%.2f".format(micros / 1000.0)} ms a tick " +
                        "(${"%.1f".format(micros.toDouble() / count)} µs each), ${"%.1f".format(updates)} entity updates a tick"
                )
            }
        }
    }

    /** The best mean tick time over 20-tick batches after warming up, and the entity updates a tick. */
    private fun measure(script: String, count: Int): Pair<Long, Double> {
        val spawner = """
            -- Ten a tick: a spawn runs the centity's script's body, which counts against the spawner's budget.
            local next = 0
            nf.on("tick", function()
              for _ = 1, 10 do
                if next < $count then
                  nf.centities.spawn("mob", vec3((next % 20) * 8, 64, math.floor(next / 20) * 8))
                  next = next + 1
                end
              end
            end)
        """.trimIndent()
        val files = mapOf(
            "centities/mob/centity.json" to centity,
            "centities/mob/script.lua" to script.trimIndent(),
            "modules/spawn/init.lua" to spawner
        )
        TestServer(files, performance = PerformanceConfig(warnMillis = 0.0)).use { server ->
            server.player("Alex")
            server.tick(400)
            assertEquals(emptyList(), server.errors.map { it.message })
            val entities = server.platform.entities
            fun updates() = entities.all.values.sumOf { it.poses + it.teleports }
            var best = Long.MAX_VALUE
            val before = updates()
            repeat(30) {
                val start = System.nanoTime()
                server.tick(20)
                best = minOf(best, (System.nanoTime() - start) / 20 / 1000)
            }
            return best to (updates() - before) / 600.0
        }
    }
}
