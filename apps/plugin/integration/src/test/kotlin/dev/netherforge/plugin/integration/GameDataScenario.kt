package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's game data, exported by the dev server from its live
 * registries (see the game-data skill): blocks and their states, collision
 * shapes, particles, and every registry and tag by name, read from the
 * server itself by the adapter's own `ServerRegistries`.
 */
class GameDataScenario : Scenario() {
    private lateinit var game: GameDataBundle

    @Test
    @Order(1)
    fun `game data comes from the live server, collision included`() {
        val (exported, game) = editor.request(Bridge.exportGameData, Unit, seconds = 180)
        assertTrue(exported.ok, exported.error)
        this.game = game!!
        assertEquals(Adapter.minecraft, game.minecraft)
        assertEquals(GameDataBundle.SCHEMA, game.schema)
        assertTrue("minecraft:stone" in game.blocks)
        assertTrue(game.blocks.getValue("minecraft:oak_stairs").properties.getValue("facing").containsAll(listOf("north", "east")))
        val stone = game.collision.getValue("minecraft:stone").single()
        assertEquals(
            listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
            listOf(stone.min.x, stone.min.y, stone.min.z, stone.max.x, stone.max.y, stone.max.z)
        )
        assertEquals(ParticleDataKind.DUST, game.particles["minecraft:dust"])
        println("Game data export: ${Bridge.json.encodeToString(GameDataBundle.serializer(), game).length} characters")
    }

    @Test
    @Order(2)
    fun `every registry is there by its own name, static, worldgen and reloadable alike, with its tags`() {
        assertEquals(true, game.has(RegistryKey.ITEM, "minecraft:red_banner"))
        assertEquals(true, game.has(RegistryKey.ATTRIBUTE, "minecraft:attack_damage"))
        assertEquals(true, game.has(RegistryKey.ENCHANTMENT, "minecraft:sharpness"))
        assertEquals(true, game.has(RegistryKey("minecraft:worldgen/biome"), "minecraft:plains"))
        assertEquals(true, game.has(RegistryKey("minecraft:loot_table"), "minecraft:chests/simple_dungeon"))
        assertEquals(true, game.has(RegistryKey.TRIGGER_TYPE, "minecraft:inventory_changed"))
        assertTrue("minecraft:oak_log" in game.tag(RegistryKey.ITEM, "minecraft:logs").orEmpty())
    }
}
