package dev.netherforge.plugin.contract

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.platform.DisplayBrightness
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityOps
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [EntityOps]: the display and interaction entities centities are drawn with, each carrying its [EntityTag]. */
abstract class EntityOpsContract : PlatformContract() {
    private val entities: EntityOps get() = platform.entities
    private val pose = DisplayPose(Matrix4(), 1, 2.0)
    private fun tag(node: String, role: EntityRole = EntityRole.DISPLAY) = EntityTag(INSTANCE, node, role)

    @Test
    fun `displays and hitboxes are made carrying their tags, and found again by them`() {
        main {
            val block = tagged(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("block")))
            val item = tagged(entities.spawnDisplay(at(0, 2), ItemDisplay("minecraft:diamond"), pose, tag("item")))
            val text = tagged(entities.spawnDisplay(at(0, 2), TextDisplay("<red>Hi"), pose, tag("text")))
            val hitbox = tagged(entities.spawnHitbox(at(0), 1.0, 2.0, tag("box", EntityRole.HITBOX)))
            for (id in listOf(block, item, text, hitbox)) assertTrue(entities.isLoaded(id))
            val found = entities.loadedTagged()
            assertEquals(tag("block"), found[block])
            assertEquals(tag("item"), found[item])
            assertEquals(tag("text"), found[text])
            assertEquals(tag("box", EntityRole.HITBOX), found[hitbox])
        }
    }

    /**
     * Only what this server can show: none of what NetherForge or the game
     * makes is a marker. A structure's real markers are `StructureGenerationScenario`'s on Paper and
     * `StructureSpawnsTest`'s on the fake (the contract server generates no project structures).
     */
    @Test
    fun `no entity but a structure's marker is listed as one`() {
        main {
            val display = tagged(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("not_a_marker")))
            val hitbox = tagged(entities.spawnHitbox(at(0), 1.0, 1.0, tag("not_a_marker", EntityRole.HITBOX)))
            spawn("minecraft:armor_stand")
            val markers = entities.structureMarkers().map { it.id }
            assertFalse(display in markers || hitbox in markers, "$markers")
            assertEquals(emptyList(), markers, "nothing generated here has a marker")
        }
    }

    @Test
    fun `a display of something the server doesn't have isn't made`() {
        main {
            assertNull(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:nf_no_such_block"), pose, tag("bad")))
            assertNull(entities.spawnDisplay(at(0, 2), ItemDisplay("minecraft:nf_no_such_item"), pose, tag("bad")))
            assertTrue(entities.loadedTagged().values.none { it.node == "bad" })
        }
    }

    @Test
    fun `NetherForge's own entities are never anyone else's, no spawn event and never a world entity`() {
        main {
            val id = tagged(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("quiet")))
            val box = tagged(entities.spawnHitbox(at(0), 1.0, 1.0, tag("quiet", EntityRole.HITBOX)))
            assertEquals(emptyList(), events.of("entitySpawn"))
            assertNull(platform.worldEntities.info(id))
            assertNull(platform.worldEntities.info(box))
            assertTrue(platform.worldEntities.list(world).none { it.id == id || it.id == box })
        }
    }

    @Test
    fun `an entity can be made temporary and permanent again, and one that is gone is left alone`() {
        main {
            val id = tagged(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("temporary")))
            entities.setPersistent(id, false)
            entities.setPersistent(id, true)
            assertTrue(entities.isLoaded(id))
            assertTrue(entities.remove(id))
            entities.setPersistent(id, false)
        }
    }

    @Test
    fun `a display shows new content of its own kind, and refuses another kind`() {
        main {
            val id = tagged(entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("swap")))
            assertTrue(entities.updateDisplay(id, BlockDisplay("minecraft:gold_block")))
            assertFalse(entities.updateDisplay(id, TextDisplay("text")))
            assertFalse(entities.updateDisplay(id, ItemDisplay("minecraft:diamond")))
            val text = tagged(entities.spawnDisplay(at(0, 2), TextDisplay("one"), pose, tag("words")))
            assertTrue(entities.updateDisplay(text, TextDisplay("two", billboard = Billboard.CENTER)))
            assertTrue(entities.updateDisplay(UUID.randomUUID(), BlockDisplay("minecraft:stone")), "nothing to refuse")
        }
    }

    @Test
    fun `poses, looks, teleports, sizes and hiding apply to what's there and ignore what isn't`() {
        val viewer = join()
        main {
            val id = tagged(entities.spawnDisplay(at(0, 2), ItemDisplay("minecraft:diamond"), pose, tag("look")))
            val box = tagged(entities.spawnHitbox(at(0), 1.0, 1.0, tag("look", EntityRole.HITBOX)))
            entities.setPose(id, DisplayPose(Matrix4().scale(0.0, 0.0, 0.0), 0, 1.0))
            entities.setPose(id, DisplayPose(Matrix4().translate(0.0, 1.0, 0.0), 3, 4.0))
            entities.setLook(
                id,
                DisplayLook(
                    glowing = true,
                    glowColor = 0xff8800,
                    brightness = DisplayBrightness(15, 15),
                    billboard = Billboard.VERTICAL,
                    viewRange = 2.0,
                    teleportTicks = 3,
                    item = item("minecraft:gold_ingot")
                )
            )
            entities.teleport(id, at(1, 2))
            entities.teleport(box, at(1))
            entities.resizeHitbox(box, 2.0, 3.0)
            entities.setHidden(viewer.uuid, id, true)
            entities.setHidden(viewer.uuid, id, false)
            entities.setHidden(UUID.randomUUID(), id, true)
            val gone = UUID.randomUUID()
            entities.setPose(gone, pose)
            entities.setLook(gone, DisplayLook())
            entities.teleport(gone, at(1))
            entities.resizeHitbox(gone, 1.0, 1.0)
            assertTrue(entities.isLoaded(id))
            assertTrue(entities.isLoaded(box))
        }
    }

    @Test
    fun `a spectator looking through a display is where it is and goes with it, facing as it does`() {
        val player = join()
        val camera = main {
            val id = tagged(entities.spawnDisplay(at(3, 2), TextDisplay("", background = "#00000000"), pose, tag("camera")))
            entities.setLook(id, DisplayLook(teleportTicks = 1))
            assertTrue(platform.players.setGameMode(player.uuid, "spectator"))
            assertTrue(platform.playerViews.setCamera(player.uuid, id), "a display of ours is an entity to look through")
            assertEquals(id, platform.playerViews.camera(player.uuid))
            id
        }
        eventually("the player at the display") {
            platform.players.location(player.uuid)?.let { abs(it.x - at(3).x) < 0.5 && abs(it.y - at(0, 2).y) < 0.5 } == true
        }
        main { entities.teleport(camera, at(6, 3).copy(yaw = 90.0, pitch = 20.0)) }
        eventually("the player where it went, facing as it does") {
            platform.players.location(player.uuid)?.let {
                abs(it.x - at(6).x) < 0.5 && abs(it.y - at(0, 3).y) < 0.5 && abs(it.yaw - 90.0) < 1.0 && abs(it.pitch - 20.0) < 1.0
            } == true
        }
        main {
            assertTrue(platform.playerViews.setCamera(player.uuid, null))
            assertTrue(platform.players.setGameMode(player.uuid, "survival"))
        }
    }

    @Test
    fun `what rides an anchor goes where it goes, and stays on it until it gets off`() {
        val player = join()
        val (anchor, rider) = main {
            val anchor = tagged(entities.spawnAnchor(at(3, 2), tag("", EntityRole.ANCHOR)))
            val rider = tagged(entities.spawnDisplay(at(3, 2), TextDisplay("", background = "#00000000"), pose, tag("rider")))
            val box = tagged(entities.spawnHitbox(at(3, 2), 1.0, 1.0, tag("rider", EntityRole.HITBOX)))
            assertEquals(tag("", EntityRole.ANCHOR), entities.loadedTagged()[anchor])
            assertTrue(entities.mount(anchor, rider))
            assertTrue(entities.mount(anchor, rider), "already on it")
            assertTrue(entities.mount(anchor, box), "an interaction entity rides too")
            assertFalse(entities.mount(anchor, UUID.randomUUID()))
            // Looking through the rider shows where it is.
            assertTrue(platform.players.setGameMode(player.uuid, "spectator"))
            assertTrue(platform.playerViews.setCamera(player.uuid, rider))
            anchor to rider
        }
        fun seenAt(x: Int) = eventually("the player at x = $x") {
            platform.players.location(player.uuid)?.let { abs(it.x - at(x).x) < 0.5 } == true
        }
        seenAt(3)
        main { entities.teleport(anchor, at(6, 2)) }
        seenAt(6)
        main {
            // A rider told to move alone stays on.
            entities.teleport(rider, at(0, 2))
            entities.teleport(anchor, at(7, 2))
        }
        seenAt(7)
        main {
            entities.dismount(rider)
            entities.teleport(rider, at(9, 2))
        }
        seenAt(9)
        main {
            assertTrue(platform.playerViews.setCamera(player.uuid, null))
            assertTrue(platform.players.setGameMode(player.uuid, "survival"))
            entities.dismount(UUID.randomUUID())
        }
    }

    @Test
    fun `a removed entity is gone, and can't be removed twice`() {
        main {
            val id = entities.spawnDisplay(at(0, 2), BlockDisplay("minecraft:stone"), pose, tag("gone"))!!
            assertTrue(entities.remove(id))
            assertFalse(entities.isLoaded(id))
            assertFalse(id in entities.loadedTagged())
            assertFalse(entities.remove(id))
            assertFalse(entities.isLoaded(UUID.randomUUID()))
        }
    }

    private companion object {
        val INSTANCE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0de")
    }
}
