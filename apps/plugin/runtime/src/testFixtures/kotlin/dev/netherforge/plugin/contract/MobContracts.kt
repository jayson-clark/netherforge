package dev.netherforge.plugin.contract

import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.AttributeOps
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.GoalCallbacks
import dev.netherforge.plugin.platform.GoalControl
import dev.netherforge.plugin.platform.GoalInfo
import dev.netherforge.plugin.platform.MobGoalOps
import dev.netherforge.plugin.platform.PathfindingOps
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AttributeOps]: an entity's attributes, worked out the game's way. */
abstract class AttributeOpsContract : PlatformContract() {
    private val attributes: AttributeOps get() = platform.attributes

    @Test
    fun `a base value reads back as set, and modifiers apply the game's way`() {
        val pig = spawn("minecraft:pig")
        main {
            assertEquals(0.0, attributes.base(pig, ARMOR))
            assertTrue(attributes.setBase(pig, ARMOR, 4.0))
            assertEquals(4.0, attributes.base(pig, ARMOR))
            assertEquals(4.0, attributes.value(pig, ARMOR))
            assertTrue(attributes.addModifier(pig, ARMOR, AttributeModifierData("netherforge:plus", 2.0, AttributeOperation.ADD_VALUE)))
            assertTrue(
                attributes.addModifier(pig, ARMOR, AttributeModifierData("netherforge:half", 0.5, AttributeOperation.ADD_MULTIPLIED_BASE))
            )
            assertTrue(
                attributes.addModifier(pig, ARMOR, AttributeModifierData("netherforge:more", 0.25, AttributeOperation.ADD_MULTIPLIED_TOTAL))
            )
            assertNear((4.0 + 2.0) * 1.5 * 1.25, attributes.value(pig, ARMOR))
            assertEquals(
                setOf("netherforge:plus", "netherforge:half", "netherforge:more"),
                attributes.modifiers(pig, ARMOR)!!.map { it.id }.toSet()
            )
            assertTrue(
                attributes.addModifier(pig, ARMOR, AttributeModifierData("netherforge:plus", 1.0, AttributeOperation.ADD_VALUE)),
                "replaces"
            )
            assertEquals(1.0, attributes.modifiers(pig, ARMOR)!!.single { it.id == "netherforge:plus" }.amount)
            assertTrue(attributes.removeModifier(pig, ARMOR, "netherforge:plus"))
            assertFalse(attributes.removeModifier(pig, ARMOR, "netherforge:plus"))
            assertTrue(attributes.removeModifier(pig, ARMOR, "netherforge:half"))
            assertTrue(attributes.removeModifier(pig, ARMOR, "netherforge:more"))
            assertEquals(emptyList(), attributes.modifiers(pig, ARMOR))
        }
    }

    @Test
    fun `an entity without an attribute answers nothing`() {
        val pig = spawn("minecraft:pig")
        val drop = drop(item("minecraft:diamond"))
        main {
            assertNull(attributes.value(pig, "minecraft:attack_damage"), "pigs don't attack")
            assertNull(attributes.base(drop, ARMOR), "an item has no attributes")
            assertFalse(attributes.setBase(drop, ARMOR, 1.0))
            assertNull(attributes.modifiers(drop, ARMOR))
            assertFalse(attributes.addModifier(drop, ARMOR, AttributeModifierData("netherforge:x", 1.0, AttributeOperation.ADD_VALUE)))
            assertNull(attributes.value(UUID.randomUUID(), ARMOR))
        }
    }

    @Test
    fun `players have attributes too`() {
        val player = join()
        main {
            assertNear(0.1, attributes.base(player.uuid, "minecraft:movement_speed"))
            assertEquals(1.0, attributes.base(player.uuid, "minecraft:attack_damage"))
            assertEquals(1.0, attributes.value(player.uuid, "minecraft:scale"))
        }
    }

    private companion object {
        const val ARMOR = "minecraft:armor"
    }
}

/** [PathfindingOps]: a mob's own navigation. */
abstract class PathfindingOpsContract : PlatformContract() {
    private val paths: PathfindingOps get() = platform.pathfinding

    @Test
    fun `a mob walks a path it finds, ending at the block it was sent to`() {
        val pig = spawn("minecraft:pig", at(2, 0, -8))
        // A mob in the air finds no path: one just spawned hasn't landed yet.
        eventually("the pig landing") { platform.worldEntities.flag(pig, EntityFlag.ON_GROUND) == true }
        main {
            val to = at(8, 0, -8)
            assertTrue(paths.moveTo(pig, Vec3(to.x, to.y, to.z), 1.0))
            assertTrue(paths.hasPath(pig))
            val (x, y, z) = block(to)
            assertEquals(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), paths.pathEnd(pig))
            assertTrue(paths.stop(pig))
            assertFalse(paths.hasPath(pig))
            assertNull(paths.pathEnd(pig))
        }
    }

    @Test
    fun `only mobs walk paths`() {
        val drop = drop(item("minecraft:diamond"))
        val player = join()
        main {
            for (id in listOf(drop, player.uuid, UUID.randomUUID())) {
                assertFalse(paths.moveTo(id, Vec3(origin.x + 3, origin.y, origin.z), 1.0))
                assertFalse(paths.hasPath(id))
                assertNull(paths.pathEnd(id))
                assertFalse(paths.stop(id))
            }
        }
    }
}

/** [MobGoalOps]: a mob's AI goals, the game's and ones the runtime adds. */
abstract class MobGoalOpsContract : PlatformContract() {
    private val goals: MobGoalOps get() = platform.mobGoals

    /** A goal that wants to run, counting what the AI calls. */
    private class Counting(val start: Boolean) : GoalCallbacks {
        val starts = AtomicInteger()
        val ticks = AtomicInteger()
        val stops = AtomicInteger()

        override fun shouldStart() = start

        override fun shouldContinue() = true

        override fun start() {
            starts.incrementAndGet()
        }

        override fun tick() {
            ticks.incrementAndGet()
        }

        override fun stop() {
            stops.incrementAndGet()
        }
    }

    private fun goal(mob: UUID, key: String): GoalInfo? = goals.goals(mob)?.firstOrNull { it.key == key }

    @Test
    fun `a mob has the game's goals, and nothing else has any`() {
        val zombie = spawn("minecraft:zombie", ai = false)
        val drop = drop(item("minecraft:diamond"))
        main {
            val all = assertNotNull(goals.goals(zombie))
            assertTrue(all.isNotEmpty())
            assertTrue(all.all { it.key.startsWith("minecraft:") })
            assertTrue(all.any { GoalControl.TARGET in it.controls }, "a zombie has targeting goals")
            assertNull(goals.goals(drop))
            assertNull(goals.goals(UUID.randomUUID()))
        }
    }

    @Test
    fun `a goal added is listed as given, replaces one with its key, and is taken off by key or by itself`() {
        val pig = spawn("minecraft:pig", ai = false)
        main {
            val first = Counting(start = false)
            assertTrue(goals.add(pig, KEY, 3, setOf(GoalControl.MOVE), first))
            assertEquals(GoalInfo(KEY, 3, setOf(GoalControl.MOVE), false), goal(pig, KEY))
            val second = Counting(start = false)
            assertTrue(goals.add(pig, KEY, 4, setOf(GoalControl.LOOK, GoalControl.JUMP), second))
            assertEquals(GoalInfo(KEY, 4, setOf(GoalControl.LOOK, GoalControl.JUMP), false), goal(pig, KEY))
            assertEquals(1, goals.goals(pig)!!.count { it.key == KEY })
            assertFalse(goals.remove(pig, first), "replaced already")
            assertTrue(goals.remove(pig, second))
            assertNull(goal(pig, KEY))
            assertTrue(goals.add(pig, KEY, 4, emptySet(), second))
            assertEquals(GoalInfo(KEY, 4, emptySet(), false), goal(pig, KEY), "a goal claiming nothing")
            assertEquals(listOf(KEY), goals.remove(pig, KEY))
            assertEquals(emptyList(), goals.remove(pig, KEY))
            assertNull(goals.remove(UUID.randomUUID(), KEY))
            assertFalse(goals.add(UUID.randomUUID(), KEY, 1, emptySet(), first))
        }
    }

    @Test
    fun `a targeting goal goes with the targeting goals`() {
        val pig = spawn("minecraft:pig", ai = false)
        main {
            assertTrue(goals.add(pig, KEY, 1, setOf(GoalControl.TARGET), Counting(start = false)))
            assertEquals(setOf(GoalControl.TARGET), goal(pig, KEY)?.controls)
        }
    }

    @Test
    fun `clearing keeps only the goals named`() {
        val pig = spawn("minecraft:pig", ai = false)
        main {
            assertTrue(goals.add(pig, KEY, 1, setOf(GoalControl.MOVE), Counting(start = false)))
            val before = goals.goals(pig)!!.map { it.key }.toSet()
            val removed = goals.clear(pig, setOf(KEY))!!
            assertEquals(before - KEY, removed.toSet())
            assertEquals(listOf(KEY), goals.goals(pig)!!.map { it.key })
            assertNull(goals.clear(UUID.randomUUID(), emptySet()))
        }
    }

    @Test
    fun `the AI starts and ticks a goal that wants to run, and stops it when taken off`() {
        // A mob near no player doesn't think on Paper.
        join()
        val pig = spawn("minecraft:pig", at(2))
        val goal = Counting(start = true)
        main { assertTrue(goals.add(pig, KEY, 0, setOf(GoalControl.MOVE, GoalControl.LOOK, GoalControl.JUMP), goal)) }
        eventually("the goal starting") { goal.ticks.get() > 0 }
        main {
            assertEquals(1, goal.starts.get())
            assertEquals(true, goal(pig, KEY)?.running)
            assertEquals(listOf(KEY), goals.remove(pig, KEY))
        }
        assertEquals(1, goal.stops.get())
    }

    private companion object {
        const val KEY = "netherforge:contract_goal"
    }
}
