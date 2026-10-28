package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.StructureMarker
import dev.netherforge.plugin.platform.WatchedEvent
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.block.Furnace
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Marker
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.HandlerList
import org.bukkit.plugin.RegisteredListener
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.BoundingBox
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every listener the Paper adapter has for the events scripts hear
 * ([GameEvents], generated from the spec, and the hand-written
 * [PlatformEvents]) fires on a real server, each with its payload: what the
 * world does by itself, what players do that only a real game makes
 * happen (bows, food, crafting tables, signs), and the watched listeners
 * registered only while they're watched. The generic suites (run on the fake
 * too) cover what both servers do; this covers the rest, on Paper alone, and
 * runs last: its final test checks that between them the suites saw every
 * event the platform can raise.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(Int.MAX_VALUE)
class PaperGameEventsTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    private val bukkit: World get() = Bukkit.getWorld(world)!!

    /** The ground's height: grass at [ground], bedrock below. */
    private val ground get() = floor(origin.y).toInt() - 1

    /** A corner of the patch of ground the world-changing tests work in, away from where the other suites stand. */
    private val x0 get() = floor(origin.x).toInt() - REGION_OFFSET
    private val z0 get() = floor(origin.z).toInt() - REGION_OFFSET

    /** A spot in the patch: [dx], [dz] blocks in, standing on the ground. */
    private fun spot(dx: Int, dz: Int) = Location(world, x0 + dx + 0.5, ground + 1.0, z0 + dz + 0.5)

    private fun set(x: Int, y: Int, z: Int, state: String, physics: Boolean = true) =
        bukkit.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(state), physics)

    /** The patch put back when the test ends: grass, air above it, and nothing left standing in it. */
    private fun restoring() = afterwards {
        for (x in x0 until x0 + REGION) {
            for (z in z0 until z0 + REGION) {
                set(x, ground, z, "minecraft:grass_block", false)
                for (y in ground + 1..ground + REGION_HEIGHT) set(x, y, z, "minecraft:air", false)
            }
        }
        val box = BoundingBox(
            x0.toDouble(),
            ground.toDouble(),
            z0.toDouble(),
            x0 + REGION + 0.0,
            ground + REGION_HEIGHT + 1.0,
            z0 + REGION + 0.0
        )
        for (entity in bukkit.getNearbyEntities(box)) if (entity !is Player) entity.remove()
    }

    /** Game rule [rule] is [value] until the test ends. */
    private fun <T : Any> rule(rule: org.bukkit.GameRule<T>, value: T) {
        val was = main { bukkit.getGameRuleValue(rule) }
        main { bukkit.setGameRule(rule, value) }
        afterwards { if (was != null) bukkit.setGameRule(rule, was) }
    }

    /** Waits up to [ticks] ticks for a call of the generated event [method] that [matching] accepts, and answers it. */
    private inline fun <reified T : GameEvent> expect(
        method: String,
        ticks: Int = 200,
        crossinline matching: (T) -> Boolean = { true }
    ): T {
        eventually(method, ticks) { events.heard<T>(method).any { matching(it) } }
        return events.heard<T>(method).first { matching(it) }
    }

    /** Waits up to [ticks] ticks for a call of the hand-written event [method] that [matching] accepts. */
    private fun expectCall(method: String, ticks: Int = 200, matching: (List<Any?>) -> Boolean = { true }): List<Any?> {
        eventually(method, ticks) { events.of(method).any(matching) }
        return events.of(method).first(matching)
    }

    private fun give(player: PlayerRef, slot: Int, item: ItemData) {
        main { assertTrue(platform.inventories.setItem(InventoryRef.Player(player.uuid), slot, item), "gave ${player.name} $item") }
    }

    private fun stack(kind: String, count: Int = 1, damage: Int? = null) = ItemData(ItemDef(kind = kind, count = count, damage = damage))

    // ---- watching ----------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `each watched event's listener is registered only while it's watched`() {
        val plugin = Bukkit.getPluginManager().getPlugin("NetherForgeContract")!!
        fun registered(): Set<RegisteredListener> = main { HandlerList.getRegisteredListeners(plugin).toSet() }
        val before = registered()
        for (event in WatchedEvent.entries) {
            main { platform.watch(event, true) }
            val added = registered() - before
            assertEquals(1, added.size, "$event: one listener while watched, not $added")
            main { platform.watch(event, true) }
            assertEquals(added, registered() - before, "$event: watching twice registers it once")
            main { platform.watch(event, false) }
            assertEquals(before, registered(), "$event: gone once it isn't watched")
        }
    }

    // ---- entities ----------------------------------------------------------------------------------

    @Test
    @Order(2)
    fun `an entity spawns, heals, takes an effect, rides, and catches fire`() {
        restoring()
        val pig = spawn("minecraft:pig", spot(2, 2), ai = false)
        assertEquals("custom", expect<GameEvent.EntitySpawn>("entitySpawn") { it.entity == pig }.cause)
        main {
            assertTrue(platform.worldEntities.damage(pig, 4.0, null))
            (Bukkit.getEntity(pig) as LivingEntity).heal(2.0)
        }
        val heal = expect<GameEvent.EntityHeal>("entityHeal") { it.entity == pig }
        assertEquals(2.0 to "custom", heal.amount to heal.cause)

        main { assertTrue(platform.worldEntities.addEffect(pig, StatusEffectData("minecraft:speed", 100))) }
        val effect = expect<GameEvent.EntityChangeEffect>("entityChangeEffect") { it.entity == pig }
        assertEquals(listOf("minecraft:speed", "added", "plugin"), listOf(effect.effect, effect.action, effect.cause))
        assertEquals(100, effect.to?.ticks)

        val rider = spawn("minecraft:zombie", spot(2, 2), ai = false)
        main { assertTrue(platform.worldEntities.addPassenger(pig, rider)) }
        expect<GameEvent.Mount>("entityMount") { it.entity == rider && it.vehicle == pig }
        main { assertTrue(platform.worldEntities.removePassenger(pig, rider)) }
        expect<GameEvent.Mount>("entityDismount") { it.entity == rider && it.vehicle == pig }

        val at = spot(2, 2)
        main { set(floor(at.x).toInt(), ground + 1, floor(at.z).toInt(), "minecraft:fire") }
        val burning = expect<GameEvent.EntityCombust>("entityCombust") { it.entity == pig || it.entity == rider }
        assertTrue(burning.ticks > 0, "$burning")
        assertEquals("minecraft:fire", burning.block?.id)
    }

    @Test
    @Order(3)
    fun `a mob picking a target is heard only while targets are watched`() {
        restoring()
        val player = join(spot(6, 6))
        main { platform.players.setGameMode(player.uuid, "survival") }
        val unheard = spawn("minecraft:zombie", spot(2, 6))
        eventually("the first zombie targets them, unheard", ticks = 200) { platform.worldEntities.target(unheard) == player.uuid }
        assertEquals(emptyList(), events.heard<GameEvent.EntityTarget>("entityTarget"))
        main { platform.worldEntities.remove(unheard) }

        watching(WatchedEvent.ENTITY_TARGET)
        val heard = spawn("minecraft:zombie", spot(2, 6))
        val target = expect<GameEvent.EntityTarget>("entityTarget") { it.entity == heard && it.target == player.uuid }
        assertEquals("closest_player", target.cause)
    }

    @Test
    @Order(4)
    fun `explosions of an entity and of nothing`() {
        restoring()
        val at = spot(4, 4)
        val tnt = main { bukkit.spawn(org.bukkit.Location(bukkit, at.x, at.y, at.z), TNTPrimed::class.java) { it.fuseTicks = 1 }.uniqueId }
        val explosion = expect<GameEvent.Explode>("entityExplode") { it.entity == tnt }
        assertTrue(explosion.breaksBlocks && explosion.blocks().isNotEmpty(), "it breaks blocks: ${explosion.blocks()}")

        val there = spot(8, 8)
        main { assertTrue(platform.worlds.explode(world, Vec3(there.x, there.y, there.z), 1.0, false, true)) }
        val blast = expect<GameEvent.Explode>("blockExplode") {
            kotlin.math.abs(it.location.x - there.x) < 1 &&
                kotlin.math.abs(it.location.z - there.z) < 1
        }
        assertEquals(null, blast.entity)
    }

    // ---- the weather, chunks and the world -----------------------------------------------------------

    @Test
    @Order(5)
    fun `rain, thunder and a lightning strike`() {
        restoring()
        afterwards { platform.worlds.setWeather(world, "clear", null) }
        main { assertTrue(platform.worlds.setWeather(world, "thunder", 600)) }
        assertEquals(
            true to "plugin",
            expect<GameEvent.WeatherChange>("weatherChange") {
                it.world == world
            }.let { it.raining to it.cause }
        )
        assertEquals(true, expect<GameEvent.ThunderChange>("thunderChange") { it.world == world }.thundering)
        val at = spot(6, 6)
        main { assertTrue(platform.worlds.strikeLightning(world, Vec3(at.x, at.y, at.z), false)) }
        val strike = expect<GameEvent.LightningStrike>("lightningStrike") { it.world == world }
        assertEquals(floor(at.x) to floor(at.z), floor(strike.location.x) to floor(strike.location.z))
    }

    @Test
    @Order(6)
    fun `chunks load and generate only heard while watched, and unload with their entities, and a world saves`() {
        main { assertTrue(platform.worlds.loadChunk(world, FAR, FAR)) }
        ticks(2)
        assertEquals(emptyList(), events.heard<GameEvent.ChunkLoad>("chunkLoad"), "not watched")

        watching(WatchedEvent.CHUNK_LOAD)
        main { assertTrue(platform.worlds.loadChunk(world, FAR + 1, FAR)) }
        val loaded = expect<GameEvent.ChunkLoad>("chunkLoad") { it.x == FAR + 1 && it.z == FAR }
        assertEquals(world, loaded.world)

        // Held loaded, as the entities in a chunk that's only loaded aren't in the world.
        main { bukkit.setChunkForceLoaded(FAR + 1, FAR, true) }
        eventually("its entities loaded") { bukkit.getChunkAt(FAR + 1, FAR).isEntitiesLoaded }
        val middle = Location(world, (FAR + 1) * 16 + 8.5, ground + 1.0, FAR * 16 + 8.5)
        val pig = main { platform.worldEntities.spawn("minecraft:pig", middle, dev.netherforge.plugin.platform.SpawnSetup()) }!!
        main {
            bukkit.setChunkForceLoaded(FAR + 1, FAR, false)
            bukkit.getChunkAt(FAR + 1, FAR).unload(true)
        }
        expect<GameEvent.Chunk>("chunkUnload") { it.x == FAR + 1 && it.z == FAR }
        expectCall("entitiesUnloading") { pig in (it[0] as List<*>) }
        main { assertTrue(platform.worlds.loadChunk(world, FAR + 1, FAR)) }
        expectCall("entitiesLoaded") { pig in (it[1] as List<*>) }
        main {
            platform.worldEntities.remove(pig)
            bukkit.getChunkAt(FAR + 1, FAR).unload(true)
            bukkit.getChunkAt(FAR, FAR).unload(true)
        }

        main { bukkit.save() }
        expectCall("worldSaving") { it[0] == world }

        // A chunk nobody has been to is generated as it's loaded: heard once, and only while watched.
        main { assertTrue(platform.worlds.loadChunk(world, FAR + 3, -FAR)) }
        ticks(2)
        assertEquals(emptyList(), events.heard<GameEvent.Chunk>("chunkGenerated").filter { it.z == -FAR }, "not watched")
        watching(WatchedEvent.CHUNK_GENERATED)
        main { assertTrue(platform.worlds.loadChunk(world, FAR + 4, -FAR)) }
        assertEquals(world, expect<GameEvent.Chunk>("chunkGenerated") { it.x == FAR + 4 && it.z == -FAR }.world)
        main { bukkit.getChunkAt(FAR + 4, -FAR).unload(true) }
        eventually("the new chunk unloaded") { !bukkit.isChunkLoaded(FAR + 4, -FAR) }
        main { assertTrue(platform.worlds.loadChunk(world, FAR + 4, -FAR)) }
        ticks(2)
        assertEquals(
            listOf(FAR + 4 to -FAR),
            events.heard<GameEvent.Chunk>("chunkGenerated").filter { it.z == -FAR }.map { it.x to it.z },
            "loaded again, it isn't new"
        )
        main {
            bukkit.getChunkAt(FAR + 4, -FAR).unload(true)
            bukkit.getChunkAt(FAR + 3, -FAR).unload(true)
        }
    }

    // ---- blocks changing by themselves (watched) -----------------------------------------------------

    @Test
    @Order(7)
    fun `water flows, and meeting lava forms obsidian`() {
        restoring()
        watching(WatchedEvent.BLOCK_FLOW)
        watching(WatchedEvent.BLOCK_FORM)
        main {
            // A trench in the grass, so the water stays in it.
            for (dx in 0..2) set(x0 + 2 + dx, ground, z0 + 2, "minecraft:air", false)
            set(x0 + 2, ground, z0 + 2, "minecraft:water")
        }
        val flow = expect<GameEvent.BlockFlow>("blockFlow") { it.block.x == x0 + 2 && it.block.z == z0 + 2 }
        assertEquals("east", flow.face)
        main {
            set(x0 + 2, ground, z0 + 6, "minecraft:air", false)
            set(x0 + 3, ground, z0 + 6, "minecraft:air", false)
            set(x0 + 2, ground, z0 + 6, "minecraft:lava")
            set(x0 + 3, ground, z0 + 6, "minecraft:water")
        }
        val formed = expect<GameEvent.BlockChange>("blockForm") { it.block.z == z0 + 6 }
        assertTrue(formed.state.startsWith("minecraft:obsidian") || formed.state.startsWith("minecraft:cobblestone"), formed.state)
    }

    @Test
    @Order(8)
    fun `grass spreads, a cactus grows, ice melts and leaves decay`() {
        restoring()
        val player = join(spot(6, 6))
        main { platform.players.setGameMode(player.uuid, "creative") }
        for (event in listOf(WatchedEvent.BLOCK_SPREAD, WatchedEvent.BLOCK_GROW, WatchedEvent.BLOCK_FADE, WatchedEvent.LEAVES_DECAY)) {
            watching(event)
        }
        main {
            // Lit by sea lanterns (it's night), so grass may spread and ice melts.
            set(x0 + 1, ground, z0 + 1, "minecraft:dirt")
            set(x0 + 2, ground + 1, z0 + 1, "minecraft:sea_lantern")
            // A cactus: it grows by random ticks whatever the light and water.
            set(x0 + 1, ground, z0 + 3, "minecraft:sand")
            set(x0 + 1, ground + 1, z0 + 3, "minecraft:cactus[age=15]")
            // Walled in, the lantern on the fourth side: its meltwater would flow over the dirt, and grass doesn't spread
            // under water (the ice melting first was this test's flake). Not beside the cactus, which a solid block breaks.
            set(x0 + 1, ground + 1, z0 + 6, "minecraft:ice")
            set(x0 + 2, ground + 1, z0 + 6, "minecraft:sea_lantern")
            set(x0, ground + 1, z0 + 6, "minecraft:stone")
            set(x0 + 1, ground + 1, z0 + 5, "minecraft:stone")
            set(x0 + 1, ground + 1, z0 + 7, "minecraft:stone")
            set(x0 + 1, ground + 1, z0 + 8, "minecraft:oak_leaves[persistent=false]")
        }
        rule(GameRules.RANDOM_TICK_SPEED, FAST_RANDOM_TICKS)
        expect<GameEvent.BlockChange>("blockSpread", ticks = RANDOM_TICK_PATIENCE) { it.block.x == x0 + 1 && it.block.z == z0 + 1 }
        expect<GameEvent.BlockChange>("blockGrow", ticks = RANDOM_TICK_PATIENCE) { it.block.x == x0 + 1 && it.block.z == z0 + 3 }
        expect<GameEvent.BlockChange>("blockFade", ticks = RANDOM_TICK_PATIENCE) { it.block.x == x0 + 1 && it.block.z == z0 + 6 }
        expect<GameEvent.Block>("leavesDecay", ticks = RANDOM_TICK_PATIENCE) { it.block.x == x0 + 1 && it.block.z == z0 + 8 }
    }

    @Test
    @Order(9)
    fun `redstone powers a wire, and a piston pushes and pulls`() {
        restoring()
        watching(WatchedEvent.BLOCK_REDSTONE)
        main {
            set(x0 + 2, ground + 1, z0 + 2, "minecraft:redstone_wire")
            set(x0 + 3, ground + 1, z0 + 2, "minecraft:redstone_block")
        }
        val power = expect<GameEvent.BlockRedstone>("blockRedstone") { it.block.x == x0 + 2 && it.block.z == z0 + 2 }
        assertEquals(0 to 15, power.from to power.to)
        main {
            set(x0 + 2, ground + 1, z0 + 6, "minecraft:piston[facing=up]")
            set(x0 + 3, ground + 1, z0 + 6, "minecraft:redstone_block")
        }
        val push = expect<GameEvent.Piston>("pistonExtend") { it.block.x == x0 + 2 && it.block.z == z0 + 6 }
        assertEquals("up" to false, push.direction to push.sticky)
        ticks(4)
        main { set(x0 + 3, ground + 1, z0 + 6, "minecraft:air") }
        expect<GameEvent.Piston>("pistonRetract") { it.block.x == x0 + 2 && it.block.z == z0 + 6 }
    }

    @Test
    @Order(10)
    fun `flint and steel lights a fire, and fire burns planks`() {
        restoring()
        val player = join(spot(1, 1))
        give(player, 0, stack("minecraft:flint_and_steel"))
        act(player, BotAction.UseBlock(x0 + 3, ground, z0 + 1, "up"))
        val lit = expect<GameEvent.BlockIgnite>("blockIgnite") { it.player == player }
        assertEquals(Triple(x0 + 3, ground + 1, "flint_and_steel"), Triple(lit.block.x, lit.block.y, lit.cause))

        main {
            for (dx in 4..9) {
                for (dz in 4..9) {
                    set(x0 + dx, ground, z0 + dz, "minecraft:oak_planks", false)
                    // With physics: a fire only burns once its first tick is scheduled.
                    set(x0 + dx, ground + 1, z0 + dz, "minecraft:fire")
                }
            }
        }
        val burnt = expect<GameEvent.BlockBurn>("blockBurn", ticks = 600) { it.block.x >= x0 && it.block.x < x0 + REGION }
        assertEquals("minecraft:oak_planks", burnt.block.id)
    }

    // ---- what players do that only a real game makes happen ---------------------------------------------

    @Test
    @Order(11)
    fun `an experience orb picked up is heard, and so is the level it brings`() {
        restoring()
        val player = join(spot(2, 2))
        main {
            val at = spot(2, 2)
            bukkit.spawn(org.bukkit.Location(bukkit, at.x, at.y, at.z), ExperienceOrb::class.java) { it.experience = 10 }
        }
        assertEquals(10, expect<GameEvent.PlayerGainExperience>("playerGainExperience") { it.player == player }.amount)
        val level = expect<GameEvent.PlayerChangeLevel>("playerChangeLevel") { it.player == player }
        assertEquals(0 to 1, level.from to level.to)
    }

    @Test
    @Order(12)
    fun `a bow drawn and let go shoots an arrow, which lands`() {
        restoring()
        val player = join(spot(1, 6))
        give(player, 0, stack("minecraft:bow"))
        give(player, 9, stack("minecraft:arrow", 4))
        act(player, BotAction.Look(-90.0, 30.0))
        act(player, BotAction.UseItem())
        ticks(25)
        act(player, BotAction.ReleaseItem)
        val stopped = expect<GameEvent.PlayerStopUsingItem>("playerStopUsingItem") { it.player == player }
        assertEquals("minecraft:bow", stopped.item.def.kind)
        assertTrue(stopped.ticks >= 20, "${stopped.ticks} ticks drawn")
        val shot = expect<GameEvent.EntityShootBow>("entityShootBow") { it.entity == player.uuid }
        assertEquals("minecraft:bow" to "main_hand", shot.bow?.def?.kind to shot.hand)
        assertTrue(shot.force > 0.9, "${shot.force}")
        expect<GameEvent.ProjectileLaunch>("projectileLaunch") { it.projectile == shot.projectile && it.shooter == player.uuid }
        val hit = expect<GameEvent.ProjectileHit>("projectileHit") { it.projectile == shot.projectile }
        assertNotNull(hit.block, "it hit the ground")
    }

    @Test
    @Order(13)
    fun `a golden apple is eaten, its effects heal them, and food can change`() {
        val player = join()
        main { assertTrue(platform.worldEntities.setNumber(player.uuid, EntityNumber.HEALTH, 10.0)) }
        give(player, 0, stack("minecraft:golden_apple"))
        act(player, BotAction.UseItem())
        expectCall("playerConsumeItem") { it[0] == player }
        val effect = expect<GameEvent.EntityChangeEffect>("entityChangeEffect") {
            it.entity == player.uuid &&
                it.effect == "minecraft:regeneration"
        }
        assertEquals("added" to "food", effect.action to effect.cause)
        // Not the first heal: a full stomach heals on its own too ("satiated").
        expect<GameEvent.EntityHeal>("entityHeal", ticks = 200) { it.entity == player.uuid && it.cause == "magic_regen" }
        assertEquals(
            "minecraft:golden_apple",
            expect<GameEvent.PlayerChangeFood>("playerChangeFood") {
                it.player == player
            }.item?.def?.kind
        )
    }

    @Test
    @Order(14)
    fun `a worn-out tool breaks on a block, and the block's drops are heard`() {
        rule(GameRules.BLOCK_DROPS, true)
        val player = join()
        main { place(at(2), "minecraft:oak_planks") }
        give(player, 0, stack("minecraft:wooden_axe", damage = WORN_OUT))
        val (x, y, z) = block(at(2))
        act(player, BotAction.BreakBlock(x, y, z, "west"))
        assertEquals("minecraft:wooden_axe", expect<GameEvent.PlayerItem>("playerBreakItem") { it.player == player }.item.def.kind)
        val drops = expect<GameEvent.BlockDropItem>("blockDropItem") { it.player == player }
        assertEquals(listOf("minecraft:oak_planks"), drops.items().map { it.def.kind })
        afterwards {
            for (entity in bukkit.getNearbyEntities(
                BoundingBox.of(org.bukkit.Location(bukkit, at(2).x, at(2).y, at(2).z), 3.0, 3.0, 3.0)
            )) {
                if (entity !is Player) entity.remove()
            }
        }
    }

    @Test
    @Order(15)
    fun `a sign placed is written`() {
        val player = join()
        give(player, 0, stack("minecraft:oak_sign"))
        val (x, y, z) = block(at(2, -1))
        afterwards { platform.blocks.set(world, x, y + 1, z, "minecraft:air", false) }
        act(player, BotAction.UseBlock(x, y, z, "up"))
        eventually("the sign stands") { platform.blocks.get(world, x, y + 1, z)?.state?.startsWith("minecraft:oak_sign") == true }
        act(player, BotAction.EditSign(x, y + 1, z, listOf("Hello", "there")))
        val sign = expect<GameEvent.SignChange>("signChange") { it.player == player }
        assertEquals(listOf("Hello", "there", "", ""), sign.lines)
        assertEquals("front" to Triple(x, y + 1, z), sign.side to Triple(sign.block.x, sign.block.y, sign.block.z))
    }

    /** Bot [player] right-clicks the block at [at], whose menu opens: [menu] (`minecraft:crafting`). */
    private fun open(player: PlayerRef, at: Location, menu: String) {
        val (x, y, z) = block(at)
        act(player, BotAction.UseBlock(x, y, z, "west"))
        eventually("$menu open") { bots.state(player.name).menu?.type == menu }
    }

    @Test
    @Order(16)
    fun `crafting is prepared and done`() {
        val player = join()
        main { place(at(2), "minecraft:crafting_table") }
        give(player, 0, stack("minecraft:oak_log"))
        open(player, at(2), "minecraft:crafting")
        expect<GameEvent.PlayerInventory>("playerOpenInventory") { it.player == player && it.kind == "workbench" }
        // The table's slots: the result (0), the grid (1 to 9), the inventory (10 to 36), the hotbar (37 on).
        act(player, BotAction.ClickSlot(37))
        act(player, BotAction.ClickSlot(1))
        val prepared = expect<GameEvent.PlayerPrepareCraft>("playerPrepareCraft") { it.player == player && it.result != null }
        assertEquals("minecraft:oak_planks", prepared.result?.def?.kind)
        act(player, BotAction.ClickSlot(0, BotClick.SHIFT_LEFT))
        val crafted = expect<GameEvent.PlayerCraft>("playerCraft") { it.player == player }
        assertEquals("minecraft:oak_planks" to true, crafted.item.def.kind to crafted.shift)
        act(player, BotAction.CloseMenu)
        expect<GameEvent.PlayerInventory>("playerCloseInventory") { it.player == player }
    }

    @Test
    @Order(17)
    fun `an anvil, a smithing table and a grindstone prepare what's put in them`() {
        val player = join()
        give(player, 0, stack("minecraft:diamond_sword"))
        // Each one's own slots, then the inventory, then the hotbar: where the sword is, and where it goes.
        for ((block, menu, slots) in listOf(
            Triple("minecraft:anvil", "minecraft:anvil", 30 to 0),
            Triple("minecraft:smithing_table", "minecraft:smithing", 31 to 1),
            Triple("minecraft:grindstone[face=floor,facing=north]", "minecraft:grindstone", 30 to 0)
        )) {
            main { place(at(2), block) }
            open(player, at(2), menu)
            act(player, BotAction.ClickSlot(slots.first))
            act(player, BotAction.ClickSlot(slots.second))
            act(player, BotAction.CloseMenu)
            eventually("the sword back") { bots.state(player.name).inventory.any { it.item == "minecraft:diamond_sword" } }
        }
        assertEquals(
            "minecraft:diamond_sword",
            expect<GameEvent.PlayerPrepareAnvil>("playerPrepareAnvil") {
                it.player == player &&
                    it.left != null
            }.left?.def?.kind
        )
        assertEquals(
            "minecraft:diamond_sword",
            expect<GameEvent.PlayerPrepareSmithing>("playerPrepareSmithing") {
                it.player == player &&
                    it.base != null
            }.base?.def?.kind
        )
        assertEquals(
            "minecraft:diamond_sword",
            expect<GameEvent.PlayerPrepareGrindstone>("playerPrepareGrindstone") {
                it.player == player &&
                    it.top != null
            }.top?.def?.kind
        )
    }

    @Test
    @Order(18)
    fun `an item is enchanted`() {
        val player = join()
        main {
            place(at(2), "minecraft:enchanting_table")
            assertTrue(platform.worldEntities.setNumber(player.uuid, EntityNumber.LEVEL, 30.0))
        }
        give(player, 0, stack("minecraft:diamond_sword"))
        give(player, 1, stack("minecraft:lapis_lazuli", 3))
        open(player, at(2), "minecraft:enchantment")
        // The table's slots: the item (0), the lapis (1), the inventory (2 to 28), the hotbar (29 on).
        for (slot in listOf(29, 0, 30, 1)) act(player, BotAction.ClickSlot(slot))
        ticks(2)
        act(player, BotAction.ClickButton(0))
        val enchanted = expect<GameEvent.PlayerEnchantItem>("playerEnchantItem") { it.player == player }
        assertEquals("minecraft:diamond_sword", enchanted.item.def.kind)
        assertTrue(enchanted.enchantments.isNotEmpty() && enchanted.cost > 0, "$enchanted")
        act(player, BotAction.CloseMenu)
    }

    @Test
    @Order(19)
    fun `a furnace smelts`() {
        main { place(at(-3), "minecraft:furnace") }
        val (x, y, z) = block(at(-3))
        val furnace = InventoryRef.Block(world, x, y, z)
        main {
            assertTrue(platform.inventories.setItem(furnace, 0, stack("minecraft:raw_iron")))
            assertTrue(platform.inventories.setItem(furnace, 1, stack("minecraft:coal")))
        }
        ticks(2)
        // Nearly done already, so the test doesn't wait the ten seconds smelting takes.
        main {
            val state = bukkit.getBlockAt(x, y, z).state as Furnace
            state.cookTime = (state.cookTimeTotal - 2).toShort()
            state.update()
        }
        val smelt = expect<GameEvent.FurnaceSmelt>("furnaceSmelt", ticks = 300) { it.block.x == x && it.block.z == z }
        assertEquals("minecraft:raw_iron" to "minecraft:iron_ingot", smelt.source.def.kind to smelt.result.def.kind)
        afterwards { platform.inventories.clear(furnace) }
    }

    @Test
    @Order(20)
    fun `a minecart's moves are heard while watched, and it's hit and broken`() {
        restoring()
        val player = join(spot(3, 3))
        main {
            platform.players.setGameMode(player.uuid, "creative")
            for (dx in 0..8) set(x0 + dx, ground + 1, z0 + 5, "minecraft:rail[shape=east_west]", false)
        }
        val cart = spawn("minecraft:minecart", spot(1, 5))
        main { platform.worldEntities.setVelocity(cart, Vec3(0.6, 0.0, 0.0)) }
        ticks(20)
        assertEquals(emptyList(), events.heard<GameEvent.VehicleMove>("vehicleMove"), "not watched")
        watching(WatchedEvent.VEHICLE_MOVE)
        main { platform.worldEntities.setVelocity(cart, Vec3(-0.6, 0.0, 0.0)) }
        val moved = expect<GameEvent.VehicleMove>("vehicleMove") { it.vehicle == cart }
        assertTrue(floor(moved.from.x) != floor(moved.to.x), "$moved")
        main { platform.worldEntities.setVelocity(cart, Vec3.ZERO) }
        main { platform.worldEntities.teleport(cart, spot(3, 5)) }
        ticks(2)
        act(player, BotAction.Attack(entity = cart.toString()))
        assertEquals(player.uuid, expect<GameEvent.VehicleDamage>("vehicleDamage") { it.vehicle == cart }.attacker)
        assertEquals(player.uuid, expect<GameEvent.VehicleDestroy>("vehicleDestroy") { it.vehicle == cart }.attacker)
    }

    @Test
    @Order(21)
    fun `a server list ping is heard only while it's watched`() {
        ping()
        assertEquals(emptyList(), events.heard<GameEvent.ServerListPing>("serverListPing"), "not watched")
        watching(WatchedEvent.SERVER_LIST_PING)
        ping()
        val ping = expect<GameEvent.ServerListPing>("serverListPing")
        assertEquals("127.0.0.1" to Bukkit.getMaxPlayers(), ping.address to ping.maxPlayers)
    }

    /**
     * Asks the server for its status as the multiplayer screen does: a
     * handshake for the status state, then the status request, and reads the
     * answer. Off the main thread, which answers it.
     */
    private fun ping() {
        Socket("127.0.0.1", Bukkit.getPort()).use { socket ->
            socket.soTimeout = PING_TIMEOUT_MILLIS
            val out = DataOutputStream(socket.getOutputStream())
            fun packet(body: ByteArrayOutputStream.() -> Unit) {
                val bytes = ByteArrayOutputStream().apply(body).toByteArray()
                out.write(ByteArrayOutputStream().apply { varInt(bytes.size) }.toByteArray())
                out.write(bytes)
            }
            packet {
                varInt(0)
                varInt(-1)
                val host = "127.0.0.1".toByteArray()
                varInt(host.size)
                write(host)
                write(Bukkit.getPort() shr 8)
                write(Bukkit.getPort() and 0xff)
                varInt(1)
            }
            packet { varInt(0) }
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.varInt()
            assertTrue(length > 0, "the server answered")
            input.readNBytes(length)
        }
    }

    private fun ByteArrayOutputStream.varInt(value: Int) {
        var rest = value
        while (true) {
            if (rest and 0x7f.inv() == 0) return write(rest)
            write((rest and 0x7f) or 0x80)
            rest = rest ushr 7
        }
    }

    private fun DataInputStream.varInt(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = readUnsignedByte()
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    // ---- what only the real server's own machinery raises -----------------------------------------------

    /** A service nobody uses, registered with Bukkit's services manager. */
    private interface ThrowawayService

    @Test
    @Order(22)
    fun `plugins changing is heard when a service is registered and unregistered`() {
        val owner = Bukkit.getPluginManager().getPlugin("NetherForgeContract")!!
        val service = object : ThrowawayService {}
        events.clear()
        main { Bukkit.getServicesManager().register(ThrowawayService::class.java, service, owner, ServicePriority.Normal) }
        afterwards { Bukkit.getServicesManager().unregister(ThrowawayService::class.java, service) }
        expectCall("pluginsChanged")
        val registered = events.of("pluginsChanged").size
        main { Bukkit.getServicesManager().unregister(ThrowawayService::class.java, service) }
        eventually("the unregistering heard") { events.of("pluginsChanged").size > registered }
    }

    @Test
    @Order(23)
    fun `a custom click action of a dialog on a player's screen is heard with the action's id`() {
        val player = join()
        val key = Key.key("netherforge_contract", "pressed")
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(Component.text("Contract")).build())
                .type(
                    DialogType.notice(
                        ActionButton.builder(Component.text("Press me")).action(DialogAction.customClick(key, null)).build()
                    )
                )
        }
        main { Bukkit.getPlayer(player.uuid)!!.showDialog(dialog) }
        eventually("on screen") { bots.state(player.name).dialog != null }
        act(player, BotAction.DialogButton(button = "Press me"))
        val heard = expectCall("customClicked") { it[0] == player }
        assertEquals("netherforge_contract:pressed", heard[1])
    }

    @Test
    @Order(24)
    fun `a structure marker is heard when its chunk's entities load`() {
        val cx = FAR + 2
        val tag = StructureMarker.TAG_PREFIX + "contract"
        main { assertTrue(platform.worlds.loadChunk(world, cx, FAR)) }
        main { bukkit.setChunkForceLoaded(cx, FAR, true) }
        eventually("its entities loaded") { bukkit.getChunkAt(cx, FAR).isEntitiesLoaded }
        val at = org.bukkit.Location(bukkit, cx * 16 + 8.5, ground + 1.0, FAR * 16 + 8.5)
        val marker = main { bukkit.spawn(at, Marker::class.java) { it.addScoreboardTag(tag) }.uniqueId }
        afterwards {
            bukkit.setChunkForceLoaded(cx, FAR, false)
            bukkit.getEntity(marker)?.remove()
            bukkit.getChunkAt(cx, FAR).unload(true)
        }
        events.clear()
        main {
            bukkit.setChunkForceLoaded(cx, FAR, false)
            bukkit.getChunkAt(cx, FAR).unload(true)
        }
        eventually("the chunk unloaded") { !bukkit.isChunkLoaded(cx, FAR) }
        main { assertTrue(platform.worlds.loadChunk(world, cx, FAR)) }
        val heard = expectCall("structureMarkersLoaded") { call -> (call[0] as List<*>).any { (it as StructureMarker).id == marker } }
        val found = (heard[0] as List<*>).map { it as StructureMarker }.first { it.id == marker }
        assertEquals("contract", found.centity)
        assertEquals(world, found.at.world)
    }

    @Test
    @Order(25)
    fun `a bow that needs no ammo is drawn and shoots with none`() {
        restoring()
        val player = join(spot(1, 6))
        main { assertTrue(platform.players.setGameMode(player.uuid, "survival")) }
        events.ammoFree = true
        afterwards { events.ammoFree = false }
        give(player, 0, stack("minecraft:bow"))
        act(player, BotAction.Look(-90.0, 30.0))
        act(player, BotAction.UseItem())
        expectCall("drawsWithoutAmmo") { (it[0] as ItemData).def.kind == "minecraft:bow" }
        ticks(25)
        act(player, BotAction.ReleaseItem)
        val shot = expect<GameEvent.EntityShootBow>("entityShootBow") { it.entity == player.uuid }
        assertEquals("minecraft:bow", shot.bow?.def?.kind)
    }

    @Test
    @Order(26)
    fun `the world breaking a watched block is heard, and a watched falling block's neighbour changing and its landing`() {
        restoring()
        rule(GameRules.BLOCK_DROPS, true)
        main { platform.blocks.watch(setOf("minecraft:torch"), setOf("minecraft:sand")) }
        afterwards { platform.blocks.watch(emptySet(), emptySet()) }
        val torch = x0 + 2
        val sand = x0 + 6
        val z = z0 + 2
        main {
            set(torch, ground + 1, z, "minecraft:stone", false)
            set(torch, ground + 2, z, "minecraft:torch", false)
            set(sand, ground + 1, z, "minecraft:stone", false)
            set(sand, ground + 2, z, "minecraft:sand", false)
        }
        // What holds each up goes: the torch is broken by the world, and the sand falls onto the grass.
        main {
            set(torch, ground + 1, z, "minecraft:air")
            set(sand, ground + 1, z, "minecraft:air")
        }
        val broke = expectCall("worldBroke") { (it[0] as BlockRef).let { b -> b.x == torch && b.z == z } }[0] as BlockRef
        assertEquals("minecraft:torch" to ground + 2, broke.state to broke.y)
        expectCall("blockUpdated") { (it[0] as BlockRef).let { b -> b.x == sand && b.y == ground + 2 && b.z == z } }
        val landed = expectCall("fallingLanded") { (it[1] as BlockRef).let { b -> b.x == sand && b.z == z } }[1] as BlockRef
        assertEquals("minecraft:sand" to ground + 1, landed.state to landed.y)
    }

    // ---- between them, the suites heard everything ------------------------------------------------------

    @Test
    @Order(Int.MAX_VALUE)
    fun `between them the suites saw the server raise every event the platform can`() {
        // Synthetic methods are Kotlin's default-argument bridges (menuDragged$default), not events.
        fun names(type: Class<*>) = type.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
        val generated = names(GameEvents::class.java)
        // The tick happens every tick and no suite reads it.
        val handWritten = names(PlatformEvents::class.java) - setOf("tick", "getGame")
        val unseen = (generated + handWritten) - events.looked
        assertEquals(emptySet(), unseen, "no suite saw the server raise these on Paper")
    }

    private companion object {
        /** How far the patch the world-changing tests use is from the origin, and its size. */
        const val REGION_OFFSET = 24
        const val REGION = 12
        const val REGION_HEIGHT = 5

        /** Random ticks enough that grass, wheat, ice and leaves change within seconds. */
        const val FAST_RANDOM_TICKS = 1000

        /**
         * How long a change random ticks make may take. Each is a chance, not a schedule (grass, for one, spreads only
         * when a nearby grass block's tick happens to pick the dirt), so the limit is generous: a run that passes
         * returns as soon as the change comes, and a short limit only fails on the unlucky ones.
         */
        const val RANDOM_TICK_PATIENCE = 2400

        /** A wooden tool's durability is 59: one more use breaks it. */
        const val WORN_OUT = 58

        /** Chunks nobody stands near. */
        const val FAR = 60

        const val PING_TIMEOUT_MILLIS = 10_000
    }
}
