package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real mobs and centities finding their way: attributes and the server's own
 * pathfinder on a pig, a golem's goals (their priorities read from the server
 * by the adapter's `PaperVersion`) and goals written in Lua, and a centity
 * walking over the blocks' live collision shapes.
 */
class MobScenario : Scenario("mobs") {
    @Test
    @Order(1)
    fun `attributes on a real pig, and a walk the server's own pathfinder finds`() {
        editor.run("forceload add 192 192 223 223")
        editor.run("it-mobs")
        editor.logged("attributes", "true", "true", "true", "showcase:it_hurry/add_multiplied_base", "true", "true", "true", "nil", "true")
        editor.logged("walking", "true", "true", "true")
        editor.logged("walked", "true")
    }

    @Test
    @Order(2)
    fun `a real mob's goals are listed, removed and cleared, and Lua goals run in its AI`() {
        // The fixture's broken goal fails on purpose, and the runtime takes it off.
        expectScriptErrors("showcase:it_broken's tick fails") { it.source?.file == "modules/mobs/init.lua" && "it_broken" in it.message }
        editor.run("it-goals")
        editor.logged("goals", "true", "true", "true", "true", "true", "true", "true")
        val boom = editor.next(what = "the broken goal's error") { it is ScriptError && "it-goals boom" in it.message } as ScriptError
        assertTrue("goal showcase:it_broken's tick failed" in boom.message, boom.message)
        assertEquals("modules/mobs/init.lua", boom.source?.file)
        editor.logged("goal run", "true", "true", "true", "true", "true", "true")
    }

    @Test
    @Order(3)
    fun `a centity walks round a wall and up a step over the real blocks, then flies back`() {
        editor.run("it-paths")
        editor.logged("centity walking", "true", "true", "true")
        editor.logged("centity walked", "true", "true", "true")
        editor.logged("centity flew", "true", "true", "true", "true")
        editor.assertNone("a centity failed to tick") { it is Log && "failed to tick" in it.message }
        editor.run("forceload remove 192 192 223 223")
    }
}
