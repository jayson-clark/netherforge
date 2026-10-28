package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.PlayParticleEffectParams
import dev.netherforge.format.bridge.SpawnParams
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The script API against the real world with nobody online: physics landing
 * on the ground, how a display is drawn, blocks, rays, particles and sounds,
 * entities and inventories, teams, server events, a package's loot table,
 * and menus, dialogs and particle effects made from scripts. The fixture's modules each register an
 * `it-*` command that logs what it found.
 */
class WorldScenario : Scenario("world") {
    @Test
    @Order(1)
    fun `a physics crate turned to face west falls onto the real ground and settles`() {
        editor.run("forceload add -16 -16 15 15")
        val (dropped, crate) = editor.request(Bridge.spawn, SpawnParams("crate"))
        assertTrue(dropped.ok, dropped.error)
        // so it falls over the blocks west of its anchor
        editor.run("it-turn")
        editor.logged("turned 90 true")
        // glow, brightness, a billboard, easing and view range on a real block display, every tick it syncs
        editor.run("it-look")
        editor.logged("look", "true", "true", "true", "true", "true", "true")
        val settled = editor.next(what = "the crate settling") { it is Log && it.message.startsWith("crate settled at") } as Log
        editor.assertNone("a centity failed to tick") { it is Log && "failed to tick" in it.message }
        val restingY = settled.message.substringAfter("at ").toDouble()
        assertEquals(0.0, restingY, 0.05, "the crate fell 3 blocks onto the ground under its anchor (y ${crate!!.y})")
        editor.run("nf kill crate")
        editor.run("forceload remove -16 -16 15 15")
        editor.run("forceload add 192 192 223 223")
    }

    @Test
    @Order(2)
    fun `blocks, rays at a block's shape and a hitbox, particles, sounds, the clock and the weather`() {
        editor.run("it-world")
        editor.logged(
            "world", "true", "minecraft:gold_block", "true", "true", "vec3(200.5, -61, 200.5)",
            "true", "vec3(-1, 0, 0)", "true", "true", "6000", "rain"
        )
    }

    @Test
    @Order(3)
    fun `entities, inventories, boss bars and an entity's saved table`() {
        editor.run("it-entities")
        editor.logged(
            "entities", "minecraft:zombie", "<red>Bob", "true", "false", "true", "minecraft:diamond_helmet", "true",
            "true", "vec3(210.5, -62, 210.5)", "3", "custom", "chest", "10", "4", "10", "true", "3", "true", "true", "1"
        )
    }

    @Test
    @Order(4)
    fun `teams on the real scoreboard`() {
        editor.run("it-teams")
        editor.logged("teams", "true", "true", "true", "true", "blue", "never", "true", "true", "true")
        editor.logged("team removed", "true", "false", "true", "0")
    }

    @Test
    @Order(5)
    fun `server events scripts can change, and game events`() {
        editor.run("it-events")
        editor.logged("events", "true", "true", "3", "0")
        editor.run("it-game-events")
        editor.logged("game events", "true/plugin", "custom", "minecraft:zombie>minecraft:pig", "false", "minecraft:speed/added/plugin")
    }

    @Test
    @Order(6)
    fun `a package's loot table rolled from the project and filled into a real chest`() {
        editor.run("it-loot")
        editor.logged("loot", "library:gem", "0", "true", "library:gem", "true")
    }

    @Test
    @Order(7)
    fun `menus and dialogs made in Lua, and particle effects played for real ticks`() {
        editor.run("it-created")
        editor.logged("created", "5", "minecraft:emerald", "true", "ok,cancel", "sparkle")
        editor.logged("effect after", "true", "true")
        // the editor's "Play on server": nobody's online, so at the world spawn; and Stop
        val (played, _) = editor.request(Bridge.playParticleEffect, PlayParticleEffectParams("shockwave", loop = true))
        assertTrue(played.ok, played.error)
        editor.logged("Playing particle effect shockwave")
        val (unknown, _) = editor.request(Bridge.playParticleEffect, PlayParticleEffectParams("nope"))
        assertFalse(unknown.ok)
        assertTrue(editor.request(Bridge.stopParticleEffects, Unit).first.ok)
        editor.assertNone("a particle couldn't be played") { it is Log && "particle" in it.message && "Couldn't" in it.message }
        editor.run("forceload remove 192 192 223 223")
    }
}
