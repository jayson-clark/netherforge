package dev.netherforge.plugin.contract

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.WorldEntityOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WorldEntityOps]: vanilla entities by UUID, players included. */
abstract class WorldEntityOpsContract : PlatformContract() {
    private val entities: WorldEntityOps get() = platform.worldEntities
    private val gone = UUID.randomUUID()

    @Test
    fun `what can be spawned`() {
        main {
            assertTrue(entities.spawnable("minecraft:pig"))
            assertTrue(entities.spawnable("minecraft:zombie"))
            assertTrue(entities.spawnable("minecraft:armor_stand"))
            assertFalse(entities.spawnable("minecraft:player"))
            assertFalse(entities.spawnable("minecraft:item"))
            assertFalse(entities.spawnable("minecraft:nf_no_such_entity"))
        }
    }

    @Test
    fun `a spawned entity is set up before its spawn is heard, and is found by UUID`() {
        val pig = spawn("minecraft:pig", at(4), SpawnSetup(customName = "<red>Bob", tags = listOf("contract"), data = """{"n":1}"""))
        main {
            val info = assertNotNull(entities.info(pig))
            assertEquals("minecraft:pig", info.kind)
            assertEquals(world, info.location.world)
            assertTrue(info.living)
            assertNull(info.player)
            assertEquals(setOf("contract"), info.tags)
            assertEquals("<red>Bob", entities.customName(pig))
            assertEquals("<red>Bob", entities.name(pig))
            assertEquals("""{"n":1}""", entities.data(pig))
            assertTrue(entities.list(world).any { it.id == pig })
            assertEquals(
                listOf(GameEvent.EntitySpawn(pig, world, "custom")),
                events.heard<GameEvent.EntitySpawn>("entitySpawn").filter {
                    it.entity ==
                        pig
                }
            )
        }
    }

    @Test
    fun `a cancelled spawn makes nothing`() {
        events.cancelling += "entitySpawn"
        main {
            assertNull(entities.spawn("minecraft:pig", at(4), SpawnSetup()))
            val heard = events.heard<GameEvent.EntitySpawn>("entitySpawn").single { it.cause == "custom" }
            assertNull(entities.info(heard.entity))
            assertNull(entities.spawn("minecraft:pig", Location(MISSING_WORLD, 0.0, 0.0, 0.0), SpawnSetup()))
        }
    }

    @Test
    fun `a player is an entity too, and never removed as one`() {
        val player = join()
        main {
            val info = assertNotNull(entities.info(player.uuid))
            assertEquals("minecraft:player", info.kind)
            assertEquals(player, info.player)
            assertTrue(info.living)
            assertEquals(player.name, entities.name(player.uuid))
            assertTrue(entities.list(world).any { it.id == player.uuid })
            assertFalse(entities.remove(player.uuid))
        }
    }

    @Test
    fun `an entity's name is its kind's until it has its own`() {
        val pig = spawn("minecraft:pig")
        main {
            assertNull(entities.customName(pig))
            assertEquals("<lang:entity.minecraft.pig>", entities.name(pig))
            assertTrue(entities.setCustomName(pig, "<gold>Ham"))
            assertEquals("<gold>Ham", entities.name(pig))
            assertTrue(entities.setCustomName(pig, null))
            assertNull(entities.customName(pig))
            assertNull(entities.name(gone))
            assertFalse(entities.setCustomName(gone, "x"))
        }
    }

    @Test
    fun `motion, place and facing read back as set`() {
        val pig = spawn("minecraft:pig")
        main {
            assertTrue(entities.setVelocity(pig, Vec3(0.0, 0.5, 0.25)))
            assertEquals(Vec3(0.0, 0.5, 0.25), entities.velocity(pig))
            val to = at(6, 0, 3)
            assertTrue(entities.teleport(pig, to))
            assertEquals(block(to), block(entities.info(pig)!!.location))
            assertTrue(entities.setRotation(pig, 90.0, 10.0))
            val facing = entities.info(pig)!!.location
            assertNear(90.0, facing.yaw)
            assertNear(10.0, facing.pitch)
            assertNull(entities.velocity(gone))
            assertFalse(entities.setVelocity(gone, Vec3.ZERO))
            assertFalse(entities.teleport(gone, to))
            assertFalse(entities.teleport(pig, Location(MISSING_WORLD, 0.0, 0.0, 0.0)))
            assertFalse(entities.setRotation(gone, 0.0, 0.0))
        }
    }

    @Test
    fun `a removed entity is gone`() {
        val pig = spawn("minecraft:pig")
        main {
            assertTrue(entities.remove(pig))
            assertNull(entities.info(pig))
            assertFalse(entities.remove(pig))
            assertTrue(entities.list(world).none { it.id == pig })
        }
    }

    @Test
    fun `yes-or-no properties, where the entity has them`() {
        val pig = spawn("minecraft:pig")
        val stand = spawn("minecraft:armor_stand", at(5))
        val player = join()
        main {
            assertEquals(false, entities.flag(pig, EntityFlag.GLOWING))
            assertTrue(entities.setFlag(pig, EntityFlag.GLOWING, true))
            assertEquals(true, entities.flag(pig, EntityFlag.GLOWING))
            for (flag in listOf(EntityFlag.CUSTOM_NAME_VISIBLE, EntityFlag.SILENT, EntityFlag.INVULNERABLE)) {
                assertEquals(false, entities.flag(pig, flag), "$flag")
                assertTrue(entities.setFlag(pig, flag, true))
                assertEquals(true, entities.flag(pig, flag), "$flag")
            }
            for (flag in listOf(EntityFlag.VISIBLE, EntityFlag.GRAVITY, EntityFlag.AI)) {
                assertEquals(true, entities.flag(pig, flag), "$flag")
                assertTrue(entities.setFlag(pig, flag, false))
                assertEquals(false, entities.flag(pig, flag), "$flag")
            }
            assertFalse(entities.setFlag(pig, EntityFlag.ON_GROUND, false), "not settable")
            for (flag in listOf(EntityFlag.SNEAKING, EntityFlag.SPRINTING, EntityFlag.FLYING, EntityFlag.CAN_FLY)) {
                assertNull(entities.flag(pig, flag), "a pig isn't a player: $flag")
                assertFalse(entities.setFlag(pig, flag, true), "a pig isn't a player: $flag")
            }
            assertNull(entities.flag(stand, EntityFlag.AI), "an armour stand has no AI")
            assertFalse(entities.setFlag(stand, EntityFlag.AI, true))
            assertNull(entities.flag(player.uuid, EntityFlag.AI))
            assertEquals(false, entities.flag(player.uuid, EntityFlag.SNEAKING))
            assertEquals(false, entities.flag(player.uuid, EntityFlag.CAN_FLY))
            assertFalse(entities.setFlag(player.uuid, EntityFlag.FLYING, true), "not allowed to fly")
            assertTrue(entities.setFlag(player.uuid, EntityFlag.CAN_FLY, true))
            assertTrue(entities.setFlag(player.uuid, EntityFlag.FLYING, true))
            assertEquals(true, entities.flag(player.uuid, EntityFlag.FLYING))
            assertFalse(entities.setFlag(player.uuid, EntityFlag.SNEAKING, true), "not settable")
            assertNull(entities.flag(gone, EntityFlag.GLOWING))
        }
    }

    @Test
    fun `numbers, where the entity has them`() {
        val pig = spawn("minecraft:pig")
        val zombie = spawn("minecraft:zombie", at(5), ai = false)
        val player = join()
        main {
            assertEquals(10.0, entities.number(pig, EntityNumber.MAX_HEALTH), "a pig's")
            assertEquals(20.0, entities.number(zombie, EntityNumber.MAX_HEALTH), "a zombie's")
            assertEquals(entities.number(pig, EntityNumber.MAX_HEALTH), entities.number(pig, EntityNumber.HEALTH), "spawned well")
            assertTrue(entities.setNumber(pig, EntityNumber.HEALTH, 4.0))
            assertEquals(4.0, entities.number(pig, EntityNumber.HEALTH))
            assertFalse(entities.setNumber(pig, EntityNumber.MAX_HEALTH, 4.0), "not settable")
            assertNull(entities.number(pig, EntityNumber.FOOD), "a pig isn't a player")
            assertFalse(entities.setNumber(pig, EntityNumber.FOOD, 4.0))
            assertNull(entities.number(pig, EntityNumber.PICKUP_DELAY))

            assertEquals(20.0, entities.number(player.uuid, EntityNumber.HEALTH))
            assertEquals(20.0, entities.number(player.uuid, EntityNumber.FOOD))
            assertEquals(5.0, entities.number(player.uuid, EntityNumber.SATURATION))
            assertEquals(0.0, entities.number(player.uuid, EntityNumber.LEVEL))
            assertEquals(0.0, entities.number(player.uuid, EntityNumber.EXPERIENCE_PROGRESS))
            assertNear(0.2, entities.number(player.uuid, EntityNumber.WALK_SPEED))
            assertNear(0.1, entities.number(player.uuid, EntityNumber.FLY_SPEED))
            assertEquals(0.0, entities.number(player.uuid, EntityNumber.HELD_SLOT))
            assertNotNull(entities.number(player.uuid, EntityNumber.PING))
            assertFalse(entities.setNumber(player.uuid, EntityNumber.PING, 1.0), "not settable")
            for ((number, value) in listOf(
                EntityNumber.FOOD to 7.0,
                EntityNumber.SATURATION to 2.0,
                EntityNumber.LEVEL to 3.0,
                EntityNumber.EXPERIENCE_PROGRESS to 0.5,
                EntityNumber.WALK_SPEED to 0.3,
                EntityNumber.FLY_SPEED to 0.05,
                EntityNumber.HELD_SLOT to 4.0
            )) {
                assertTrue(entities.setNumber(player.uuid, number, value), "$number")
                assertNear(value, entities.number(player.uuid, number), message = "$number")
            }
            assertNull(entities.number(gone, EntityNumber.HEALTH))
            assertFalse(entities.setNumber(gone, EntityNumber.HEALTH, 1.0))
        }
    }

    @Test
    fun `scoreboard tags and script data`() {
        val pig = spawn("minecraft:pig")
        main {
            assertTrue(entities.addTag(pig, "a"))
            assertFalse(entities.addTag(pig, "a"), "already")
            assertEquals(setOf("a"), entities.info(pig)?.tags)
            assertTrue(entities.removeTag(pig, "a"))
            assertFalse(entities.removeTag(pig, "a"), "not any more")
            assertNull(entities.data(pig))
            assertTrue(entities.setData(pig, """{"k":"v"}"""))
            assertEquals("""{"k":"v"}""", entities.data(pig))
            assertTrue(entities.setData(pig, null))
            assertNull(entities.data(pig))
            assertFalse(entities.addTag(gone, "a"))
            assertFalse(entities.setData(gone, "{}"))
        }
    }

    @Test
    fun `hiding from an online player`() {
        val pig = spawn("minecraft:pig")
        val player = join()
        main {
            assertTrue(entities.hide(player.uuid, pig))
            assertTrue(entities.show(player.uuid, pig))
            assertFalse(entities.hide(UUID.randomUUID(), pig))
            assertFalse(entities.hide(player.uuid, gone))
        }
    }

    @Test
    fun `riding`() {
        val pig = spawn("minecraft:pig")
        val stand = spawn("minecraft:armor_stand", at(5))
        main {
            assertEquals(emptyList(), entities.passengers(pig))
            assertTrue(entities.addPassenger(pig, stand))
            assertEquals(listOf(stand), entities.passengers(pig))
            assertEquals(pig, entities.vehicle(stand))
            assertNull(entities.vehicle(pig))
            assertTrue(entities.removePassenger(pig, stand))
            assertFalse(entities.removePassenger(pig, stand), "not riding any more")
            assertNull(entities.vehicle(stand))
            assertEquals(emptyList(), entities.passengers(pig))
            assertNull(entities.passengers(gone))
            assertFalse(entities.addPassenger(pig, gone))
        }
    }

    @Test
    fun `damage is heard first, then dealt`() {
        val pig = spawn("minecraft:pig")
        val zombie = spawn("minecraft:zombie", at(5), ai = false)
        main {
            assertTrue(entities.damage(pig, 3.0, null))
            assertEquals(7.0, entities.number(pig, EntityNumber.HEALTH))
            assertEquals(listOf(listOf<Any?>(pig, 3.0, "custom", null)), events.of("entityDamaged").filter { it[0] == pig })
            assertTrue(entities.damage(zombie, 2.0, pig))
            assertEquals(listOf(listOf<Any?>(zombie, 2.0, "entity_attack", pig)), events.of("entityDamaged").filter { it[0] == zombie })
            assertFalse(entities.damage(gone, 1.0, null))
        }
    }

    @Test
    fun `cancelled damage isn't dealt`() {
        val pig = spawn("minecraft:pig")
        events.cancelling += "entityDamaged"
        main {
            assertTrue(entities.damage(pig, 3.0, null))
            assertEquals(10.0, entities.number(pig, EntityNumber.HEALTH))
        }
    }

    @Test
    fun `an entity at no health dies, and its death is heard`() {
        val pig = spawn("minecraft:pig")
        main { assertTrue(entities.setNumber(pig, EntityNumber.HEALTH, 0.0)) }
        assertEquals(1, events.of("entityDied").count { it[0] == pig })
        eventually("the pig's body going") { entities.info(pig) == null }
    }

    @Test
    fun `status effects read back as added`() {
        val pig = spawn("minecraft:pig")
        main {
            assertTrue(entities.effectExists("minecraft:speed"))
            assertFalse(entities.effectExists("minecraft:nf_no_such_effect"))
            assertEquals(emptyList(), entities.effects(pig))
            assertTrue(entities.addEffect(pig, StatusEffectData("minecraft:speed", 200, 1)))
            assertTrue(entities.addEffect(pig, StatusEffectData("minecraft:regeneration", -1, 0, ambient = true, particles = false)))
            val effects = entities.effects(pig)!!.sortedBy { it.effect }
            assertEquals(
                listOf(
                    StatusEffectData("minecraft:regeneration", -1, 0, ambient = true, particles = false),
                    StatusEffectData("minecraft:speed", 200, 1)
                ),
                effects
            )
            assertTrue(entities.addEffect(pig, StatusEffectData("minecraft:speed", 100, 0)), "replaces")
            assertEquals(StatusEffectData("minecraft:speed", 100, 0), entities.effects(pig)!!.single { it.effect == "minecraft:speed" })
            assertTrue(entities.removeEffect(pig, "minecraft:speed"))
            assertFalse(entities.removeEffect(pig, "minecraft:speed"))
            assertNull(entities.effects(gone))
            assertFalse(entities.addEffect(gone, StatusEffectData("minecraft:speed", 1)))
        }
    }

    @Test
    fun `equipment reads back as set, and a player's is in their inventory`() {
        val zombie = spawn("minecraft:zombie", at(5), ai = false)
        val player = join()
        main {
            assertTrue(entities.setEquipment(zombie, "main_hand", item("minecraft:iron_sword")))
            assertEquals("minecraft:iron_sword", entities.equipment(zombie, "main_hand")?.def?.kind)
            assertTrue(entities.setEquipment(zombie, "main_hand", null))
            assertNull(entities.equipment(zombie, "main_hand"))
            assertTrue(entities.setEquipment(player.uuid, "off_hand", item("minecraft:bread", 3)))
            val bread = entities.equipment(player.uuid, "off_hand")!!.def
            assertEquals("minecraft:bread", bread.kind)
            assertEquals(3, bread.count)
            val inventory = platform.inventories.contents(dev.netherforge.plugin.platform.InventoryRef.Player(player.uuid))!!
            assertEquals("minecraft:bread", inventory[40]?.def?.kind, "the off hand is slot 40")
            assertFalse(entities.setEquipment(zombie, "main_hand", item("minecraft:nf_no_such_item")))
            assertNull(entities.equipment(gone, "head"))
        }
    }

    @Test
    fun `a mob's target`() {
        val zombie = spawn("minecraft:zombie", at(5), ai = false)
        val pig = spawn("minecraft:pig")
        val stand = spawn("minecraft:armor_stand", at(6))
        main {
            assertTrue(entities.setTarget(zombie, pig))
            assertEquals(pig, entities.target(zombie))
            assertTrue(entities.setTarget(zombie, null))
            assertNull(entities.target(zombie))
            assertFalse(entities.setTarget(stand, pig), "an armour stand isn't a mob")
            assertNull(entities.target(stand))
        }
    }

    @Test
    fun `a dropped item's stack`() {
        val drop = drop(item("minecraft:diamond", 3))
        main {
            val info = assertNotNull(entities.info(drop))
            assertEquals("minecraft:item", info.kind)
            assertFalse(info.living)
            val stack = entities.item(drop)!!.def
            assertEquals("minecraft:diamond", stack.kind)
            assertEquals(3, stack.count)
            assertTrue(entities.setItem(drop, item("minecraft:bread", 2)))
            assertEquals("minecraft:bread", entities.item(drop)?.def?.kind)
            assertNotNull(entities.number(drop, EntityNumber.PICKUP_DELAY))
            assertTrue(entities.setNumber(drop, EntityNumber.PICKUP_DELAY, 40.0))
            assertEquals(40.0, entities.number(drop, EntityNumber.PICKUP_DELAY))
            assertNull(entities.number(drop, EntityNumber.HEALTH))
            assertNull(entities.effects(drop), "not living")
            assertFalse(entities.damage(drop, 1.0, null))
            assertNull(entities.spawnItem(world, Vec3(origin.x, origin.y, origin.z), item("minecraft:nf_no_such_item")))
        }
    }

    @Test
    fun `a ray hits the nearest entity's box, leaving out those ignored`() {
        // A lane of its own, away from what other tests leave lying about.
        val lane = origin.z + 7
        val near = spawn("minecraft:pig", at(4, 0, 7), ai = false)
        val far = spawn("minecraft:pig", at(8, 0, 7), ai = false)
        main {
            assertTrue(entities.teleport(near, Location(world, origin.x + 4, origin.y, lane)))
            assertTrue(entities.teleport(far, Location(world, origin.x + 8, origin.y, lane)))
            val from = Vec3(origin.x, origin.y + 0.4, lane)
            val east = Vec3(1.0, 0.0, 0.0)
            val hit = assertNotNull(entities.raycast(world, from, east, 20.0, emptySet()))
            assertEquals(near, hit.id)
            assertNear(origin.x + 4 - 0.45, hit.position.x, 1e-3, "a pig is 0.9 wide")
            assertEquals(Vec3(-1.0, 0.0, 0.0), hit.normal)
            assertEquals(far, entities.raycast(world, from, east, 20.0, setOf(near))?.id)
            assertNull(entities.raycast(world, from, east, 2.0, emptySet()), "out of reach")
            assertNull(entities.raycast(world, from, Vec3(-1.0, 0.0, 0.0), 20.0, emptySet()), "nothing that way")
        }
    }

    @Test
    fun `boxes are every entity's bounding box reaching into a region`() {
        val lane = origin.z + 9
        val pig = spawn("minecraft:pig", at(12, 0, 9), ai = false)
        main {
            assertTrue(entities.teleport(pig, Location(world, origin.x + 12, origin.y, lane)))
            val around = Box(Vec3(origin.x + 11, origin.y, lane - 1), Vec3(origin.x + 13, origin.y + 2, lane + 1))
            val found = entities.boxes(world, around).single { it.id == pig }
            assertEquals("minecraft:pig", found.kind)
            assertFalse(found.player)
            assertNear(origin.x + 12 - 0.45, found.box.min.x, 1e-3, "a pig is 0.9 wide")
            assertNear(origin.y, found.box.min.y, 1e-3, "standing where it was put")
            val beside = Box(Vec3(origin.x + 12.3, origin.y, lane - 1), Vec3(origin.x + 14, origin.y + 2, lane + 1))
            assertTrue(entities.boxes(world, beside).any { it.id == pig }, "its edge reaches past its middle")
            val clear = Box(Vec3(origin.x + 13, origin.y, lane - 1), Vec3(origin.x + 14, origin.y + 2, lane + 1))
            assertTrue(entities.boxes(world, clear).none { it.id == pig }, "a region it doesn't reach")
            assertEquals(emptyList(), entities.boxes(MISSING_WORLD, around))
        }
    }
}
