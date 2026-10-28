package dev.netherforge.plugin.integration

import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * The engine's built-ins on a real server: a centity's `body` falling onto, walking along and
 * stopping against the game's own collision shapes (a slab is half a block), the spatial queries
 * over centities' hitboxes and a real zombie's box, `world:flood` through a room of the game's
 * blocks, a temporary centity gone with the module that spawned it, and `properties`.
 */
class BuiltinsScenario : Scenario("builtins") {
    override fun prepare(project: TestProject) {
        // A game rule netherforge.json sets on the main world.
        project.setWorld("world", """{ "gameRules": { "keep_inventory": true } }""")
    }

    @Test
    @Order(1)
    fun `a body lands on a slab, steps along it, and stops at a wall`() {
        // No player keeps the fixture's chunks loaded, so a centity there wouldn't tick.
        editor.run("forceload add 192 192 223 223")
        editor.run("it-builtins body")
        editor.logged("spawned", "true", "0.6")
        // The slab's top is half a block up from the ground's.
        editor.logged("landed", "-61.500", "true")
        // Off the slabs' end and on to the wall at x = 215: flush against it, half its width (0.3) short.
        editor.logged("walked", "214.700", "-62.000", "-1.0", "true", "true")
    }

    @Test
    @Order(2)
    fun `queries find a centity's hitbox and a zombie's box, nearest first`() {
        editor.run("it-builtins queries")
        editor.logged("queries", "2", "true", "true", "true", "true")
        editor.logged("bounds", "-62.000", "-61.400")
    }

    @Test
    @Order(3)
    fun `a flood fills a closed room's air and stops at its walls`() {
        editor.run("it-builtins flood")
        editor.logged("flood", "18", "nil", "42", "minecraft:air")
    }

    @Test
    @Order(4)
    fun `a temporary centity goes with the module that spawned it`() {
        editor.run("it-builtins temporary")
        editor.logged("temporary", "true", "false", "3")
        editor.reload("modules/it_builtins/init.lua")
        editor.run("it-builtins after_reload")
        editor.logged("after reload", "1", "true")
    }

    @Test
    @Order(5)
    fun `a rope is strung, and a tween and an additive animation pose a real centity`() {
        editor.run("it-builtins layers")
        // Four half-block links over two blocks, the first centred a quarter of the way; the squash halves the
        // script's size; the tween has lifted it a block.
        editor.logged("layers", "208.250", "-58.000", "false", "1.000", "1.000")
    }

    @Test
    @Order(6)
    fun `a walk opens a door in its way, and shuts it behind it`() {
        editor.run("it-builtins doors")
        // Without open_doors the wall stops it; with them it walks through, and the door is shut again.
        editor.logged("doors", "true", "true", "215.500", "true")
    }

    @Test
    @Order(7)
    fun `netherforge json's game rules are set on the world`() {
        editor.run("it-builtins rules")
        editor.logged("rules", "true")
        editor.run("forceload remove 192 192 223 223")
    }
}
