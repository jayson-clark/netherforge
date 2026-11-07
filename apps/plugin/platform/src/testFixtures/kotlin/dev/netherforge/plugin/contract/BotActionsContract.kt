package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.bridge.BotTargetKind
import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PickAnswer
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.platform.WindowSpec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a bot does reaches the runtime as a player's would: each action a
 * client can take, and the events the server raises for it (a watched one
 * only while watched), with what a cancelled one leaves undone. On Paper the
 * bot's packets go through the server's own handling; the fake's bots raise
 * what it raises, so the two can't drift.
 */
abstract class BotActionsContract : PlatformContract() {
    private fun sneaks(player: PlayerRef) = events.heard<GameEvent.PlayerSneak>("playerSneak").filter { it.player == player }

    private fun inputs(player: PlayerRef) = events.heard<GameEvent.PlayerInput>("playerInput").filter { it.player == player }

    private fun moves(player: PlayerRef) = events.heard<GameEvent.PlayerMove>("playerMove").filter { it.player == player }

    /** The arguments of each call of the hand-written event [method] whose first argument is [player]. */
    private fun calls(method: String, player: PlayerRef) = events.of(method).filter { it.first() == player }

    private fun holding(player: PlayerRef, item: ItemData?, slot: String = "main_hand") {
        main { assertTrue(platform.worldEntities.setEquipment(player.uuid, slot, item), "gave ${player.name} $item") }
    }

    private fun held(player: PlayerRef, slot: String = "main_hand") = main { platform.worldEntities.equipment(player.uuid, slot) }

    private fun count(item: ItemData?) = item?.let { it.def.count ?: 1 }

    /** Dropped items near the origin go when the test ends, so they're nobody else's to pick up. */
    private fun sweepItems() = afterwards {
        for (entity in platform.worldEntities.list(world)) {
            val at = entity.location
            if (entity.kind == "minecraft:item" && kotlin.math.abs(at.x - origin.x) < 16 && kotlin.math.abs(at.z - origin.z) < 16) {
                platform.worldEntities.remove(entity.id)
            }
        }
    }

    @Test
    fun `sneaking is heard, and the keys behind it only while input is watched`() {
        val player = join()
        act(player, BotAction.Sneak(true))
        eventually("the sneak heard") { sneaks(player).map { it.sneaking } == listOf(true) }
        watching(WatchedEvent.PLAYER_INPUT)
        act(player, BotAction.Sneak(false))
        eventually("the release heard") { sneaks(player).map { it.sneaking } == listOf(true, false) }
        val keys = inputs(player)
        assertEquals(listOf(false), keys.map { it.sneak }, "only while watched: $keys")
        main { platform.watch(WatchedEvent.PLAYER_INPUT, false) }
        act(player, BotAction.Sneak(true))
        eventually("the second sneak heard") { sneaks(player).size == 3 }
        ticks(2)
        assertEquals(1, inputs(player).size, "no longer watched")
    }

    @Test
    fun `sprinting is heard`() {
        val player = join()
        act(player, BotAction.Sprint(true))
        act(player, BotAction.Sprint(false))
        eventually("both heard") {
            events.heard<GameEvent.PlayerSprint>("playerSprint").filter { it.player == player }.map { it.sprinting } == listOf(true, false)
        }
    }

    @Test
    fun `a jump is heard going up`() {
        val player = join()
        act(player, BotAction.Jump)
        eventually("the jump heard") { events.heard<GameEvent.PlayerJump>("playerJump").any { it.player == player } }
        val jump = events.heard<GameEvent.PlayerJump>("playerJump").first { it.player == player }
        assertTrue(jump.to.y > jump.from.y, "$jump")
        assertEquals(block(origin).let { it.first to it.third }, block(jump.from).let { it.first to it.third })
        ticks(20)
    }

    @Test
    fun `walking is heard block by block, only while moves are watched`() {
        val player = join()
        act(player, BotAction.WalkTo(origin.x + 2, origin.z))
        assertEquals(emptyList(), moves(player), "not watched")
        main { assertEquals(block(at(2)), block(platform.players.location(player.uuid)!!)) }

        watching(WatchedEvent.PLAYER_MOVE)
        act(player, BotAction.WalkTo(origin.x, origin.z))
        val walked = moves(player)
        assertEquals(2, walked.size, "one move into each block: $walked")
        for (move in walked) {
            assertTrue(floor(move.from.x) - floor(move.to.x) == 1.0, "a step west into the next block: $move")
            assertEquals(floor(origin.z), floor(move.to.z))
        }
        main { platform.watch(WatchedEvent.PLAYER_MOVE, false) }
        act(player, BotAction.WalkTo(origin.x + 2, origin.z))
        assertEquals(2, moves(player).size, "no longer watched")
    }

    @Test
    fun `a cancelled move puts them back`() {
        val player = join()
        watching(WatchedEvent.PLAYER_MOVE)
        events.cancelling += "playerMove"
        runCatching { act(player, BotAction.WalkTo(origin.x + 2, origin.z, timeoutTicks = 40)) }
        events.cancelling.clear()
        assertTrue(moves(player).isNotEmpty(), "the move was heard")
        ticks(5)
        main { assertEquals(block(origin), block(platform.players.location(player.uuid)!!), "still where they stood") }
    }

    @Test
    fun `chat is heard only while it's watched`() {
        val player = join()
        act(player, BotAction.Chat("not watched"))
        ticks(2)
        watching(WatchedEvent.PLAYER_CHAT)
        act(player, BotAction.Chat("hello there"))
        eventually("the line heard") { events.heard<GameEvent.PlayerChat>("playerChat").any { it.player == player } }
        assertEquals(
            listOf("hello there"),
            events.heard<GameEvent.PlayerChat>("playerChat").filter {
                it.player == player
            }.map { it.message }
        )
    }

    /** A handler that counts its runs. */
    private class Counting : CommandHandler {
        val runs = CopyOnWriteArrayList<String>()

        override fun run(sender: CommandSender, label: String, input: CommandInput) {
            runs += input.text
        }
    }

    @Test
    fun `a command typed is heard before it runs, and a cancelled one doesn't run`() {
        val player = join()
        val name = "nfcbotcommand${COMMANDS.incrementAndGet()}"
        val handler = Counting()
        main {
            val syntax = CommandSyntax(arguments = listOf(ArgumentSyntax("words", ArgumentType.TEXT, optional = true)))
            assertTrue(platform.commands.register(CommandSpec(name, syntax = syntax), handler))
        }
        afterwards { platform.commands.unregister(name) }
        act(player, BotAction.Command("/$name first"))
        eventually("it ran") { handler.runs.toList() == listOf("first") }
        assertEquals(
            listOf("$name first"),
            events.heard<GameEvent.PlayerCommand>("playerCommand").filter { it.player == player }.map { it.input }
        )
        events.cancelling += "playerCommand"
        act(player, BotAction.Command("$name second"))
        eventually("heard") { events.heard<GameEvent.PlayerCommand>("playerCommand").count { it.player == player } == 2 }
        ticks(2)
        assertEquals(listOf("first"), handler.runs.toList(), "the cancelled one didn't run")
    }

    @Test
    fun `holding another slot is heard, and a cancelled change keeps the slot`() {
        val player = join()
        act(player, BotAction.SelectSlot(4))
        eventually("heard") { events.heard<GameEvent.PlayerChangeSlot>("playerChangeSlot").any { it.player == player } }
        val change = events.heard<GameEvent.PlayerChangeSlot>("playerChangeSlot").single { it.player == player }
        assertEquals(0 to 4, change.from to change.to)
        main { assertEquals(4.0, platform.worldEntities.number(player.uuid, EntityNumber.HELD_SLOT)) }
        events.cancelling += "playerChangeSlot"
        act(player, BotAction.SelectSlot(6))
        eventually("heard") { events.heard<GameEvent.PlayerChangeSlot>("playerChangeSlot").count { it.player == player } == 2 }
        ticks(2)
        main { assertEquals(4.0, platform.worldEntities.number(player.uuid, EntityNumber.HELD_SLOT)) }
    }

    @Test
    fun `swapping hands is heard, and swaps them unless cancelled`() {
        val player = join()
        holding(player, item("minecraft:diamond", 2))
        events.cancelling += "playerSwapHands"
        act(player, BotAction.SwapHands)
        eventually("heard") { events.heard<GameEvent.PlayerSwapHands>("playerSwapHands").any { it.player == player } }
        val heard = events.heard<GameEvent.PlayerSwapHands>("playerSwapHands").first { it.player == player }
        assertEquals(listOf<Any?>("minecraft:diamond", null, false), listOf(heard.item?.def?.kind, heard.offHandItem, heard.inInventory))
        ticks(2)
        assertEquals("minecraft:diamond", held(player)?.def?.kind, "cancelled: still in the main hand")
        events.cancelling.clear()
        act(player, BotAction.SwapHands)
        eventually("swapped") { held(player) == null && held(player, "off_hand")?.def?.kind == "minecraft:diamond" }
        assertEquals(2, events.heard<GameEvent.PlayerSwapHands>("playerSwapHands").count { it.player == player })
    }

    @Test
    fun `clicks, the swap-hands key and drags in its own inventory are heard`() {
        val player = join()
        holding(player, item("minecraft:diamond", 2))
        main { assertEquals(0.0, platform.worldEntities.number(player.uuid, EntityNumber.HELD_SLOT)) }
        // The inventory screen numbers the hotbar from 36 and the main rows from 9.
        events.cancelling += "playerSwapHands"
        act(player, BotAction.ClickSlot(36, BotClick.OFF_HAND))
        eventually("the swap heard") { events.heard<GameEvent.PlayerSwapHands>("playerSwapHands").any { it.player == player } }
        val click = events.heard<GameEvent.PlayerClickInventory>("playerClickInventory").single { it.player == player }
        assertEquals(
            listOf<Any?>("crafting", InventoryRef.Player(player.uuid), true, 0, "swap_offhand", "minecraft:diamond"),
            listOf(click.kind, click.inventory, click.own, click.index, click.click, click.item?.def?.kind)
        )
        val swap = events.heard<GameEvent.PlayerSwapHands>("playerSwapHands").single { it.player == player }
        assertEquals(listOf<Any?>("minecraft:diamond", true), listOf(swap.item?.def?.kind, swap.inInventory))
        ticks(2)
        assertEquals("minecraft:diamond", held(player)?.def?.kind, "cancelled: still in the hotbar")
        act(player, BotAction.ClickSlot(36))
        act(player, BotAction.Drag(listOf(9, 10)))
        eventually("the drag heard") { events.heard<GameEvent.PlayerDragInventory>("playerDragInventory").any { it.player == player } }
        val drag = events.heard<GameEvent.PlayerDragInventory>("playerDragInventory").single { it.player == player }
        assertEquals(listOf<Any?>(emptyList<Int>(), listOf(9, 10), "left"), listOf(drag.indices, drag.ownIndices, drag.click))
    }

    @Test
    fun `dropping is heard with what's dropped, and a cancelled drop keeps it`() {
        val player = join()
        sweepItems()
        holding(player, item("minecraft:stick", 3))
        events.cancelling += "playerDropItem"
        act(player, BotAction.Drop())
        eventually("heard") { calls("playerDropItem", player).isNotEmpty() }
        ticks(2)
        assertEquals(3, count(held(player)), "cancelled: all still held")
        events.cancelling.clear()
        act(player, BotAction.Drop())
        eventually("one dropped") { count(held(player)) == 2 }
        act(player, BotAction.Drop(all = true))
        eventually("the rest dropped") { held(player) == null }
        val dropped = calls("playerDropItem", player).map { it[1] as ItemData }
        assertEquals(listOf(1, 1, 2), dropped.map { count(it) }, "$dropped")
        assertTrue(dropped.all { it.def.kind == "minecraft:stick" })
    }

    @Test
    fun `an item at their feet is picked up, unless cancelled`() {
        val player = join()
        sweepItems()
        events.cancelling += "playerPickupItem"
        val refused = drop(item("minecraft:diamond", 2), origin)
        eventually("the pickup heard") { calls("playerPickupItem", player).isNotEmpty() }
        ticks(2)
        main {
            assertNotNull(platform.worldEntities.info(refused), "cancelled: still on the ground")
            platform.worldEntities.remove(refused)
        }
        assertEquals(refused, calls("playerPickupItem", player).first()[2])
        events.cancelling.clear()
        events.clear()
        val taken = drop(item("minecraft:diamond", 2), origin)
        eventually("picked up") { held(player)?.def?.kind == "minecraft:diamond" }
        val (_, picked, entity) = calls("playerPickupItem", player).single()
        assertEquals(taken, entity)
        assertEquals("minecraft:diamond" to 2, (picked as ItemData).def.kind to count(picked))
        main { assertNull(platform.worldEntities.info(taken), "gone from the ground") }
    }

    @Test
    fun `using the held item at nothing is heard as a right click and a use`() {
        val player = join()
        holding(player, item("minecraft:stick"))
        act(player, BotAction.Look(0.0, -90.0))
        act(player, BotAction.UseItem())
        eventually("heard") { calls("playerUseItem", player).isNotEmpty() }
        val click = calls("playerInteract", player).single()
        assertEquals(listOf<Any?>(ClickButton.RIGHT, null, null), click.subList(1, 4))
        assertEquals("minecraft:stick" to "main_hand", (click[4] as ItemData).def.kind to click[5])
        val use = calls("playerUseItem", player).single()
        assertEquals("minecraft:stick" to "main_hand", (use[1] as ItemData).def.kind to use[2])
    }

    @Test
    fun `right-clicking a block is heard, and a block held is placed against it`() {
        val player = join()
        val (x, y, z) = block(at(2, -1))
        act(player, BotAction.UseBlock(x, y, z, "up"))
        eventually("heard") { calls("playerInteract", player).isNotEmpty() }
        val click = calls("playerInteract", player).single()
        assertEquals(ClickButton.RIGHT, click[1])
        assertEquals(Triple(x, y, z), (click[2] as dev.netherforge.plugin.platform.BlockRef).let { Triple(it.x, it.y, it.z) })
        assertEquals(listOf<Any?>("up", null, "main_hand"), click.subList(3, 6))

        holding(player, item("minecraft:oak_planks", 2))
        afterwards { platform.blocks.set(world, x, y + 1, z, "minecraft:air", false) }
        act(player, BotAction.UseBlock(x, y, z, "up"))
        eventually("placed") { platform.blocks.get(world, x, y + 1, z)?.state == "minecraft:oak_planks" }
        val placed = events.heard<GameEvent.BlockPlace>("blockPlace").single { it.player == player }
        assertEquals(Triple(x, y + 1, z), Triple(placed.block.x, placed.block.y, placed.block.z))
        assertEquals("minecraft:oak_planks", placed.state)
        assertEquals(Triple(x, y, z), Triple(placed.against.x, placed.against.y, placed.against.z))
        assertEquals(1, count(held(player)), "one used")
    }

    @Test
    fun `a cancelled place leaves the block and the stack`() {
        val player = join()
        val (x, y, z) = block(at(2, -1))
        holding(player, item("minecraft:oak_planks", 2))
        afterwards { platform.blocks.set(world, x, y + 1, z, "minecraft:air", false) }
        events.cancelling += "blockPlace"
        act(player, BotAction.UseBlock(x, y, z, "up"))
        eventually("heard") { events.heard<GameEvent.BlockPlace>("blockPlace").any { it.player == player } }
        ticks(2)
        main { assertEquals("minecraft:air", platform.blocks.get(world, x, y + 1, z)?.state) }
        assertEquals(2, count(held(player)))
    }

    @Test
    fun `mining a block is heard from the first hit to the break`() {
        val player = join()
        main { place(at(2), "minecraft:oak_planks") }
        val (x, y, z) = block(at(2))
        act(player, BotAction.BreakBlock(x, y, z, "west"))
        main { assertEquals("minecraft:air", platform.blocks.get(world, x, y, z)?.state) }
        val click = calls("playerInteract", player).single()
        assertEquals(ClickButton.LEFT, click[1])
        assertEquals("west", click[3])
        val start = events.heard<GameEvent.BlockStartBreak>("blockStartBreak").single { it.player == player }
        assertEquals("minecraft:oak_planks" to false, start.block.id to start.instant)
        val broken = calls("blockBreak", player).single()[1] as dev.netherforge.plugin.platform.BlockRef
        assertEquals(Triple(x, y, z), Triple(broken.x, broken.y, broken.z))
        assertEquals("minecraft:oak_planks", broken.id)
    }

    @Test
    fun `letting go of a block before it breaks is heard`() {
        val player = join()
        main { place(at(2), "minecraft:bedrock") }
        val (x, y, z) = block(at(2))
        assertFails { act(player, BotAction.BreakBlock(x, y, z, "west")) }
        eventually("heard") { calls("blockStopBreak", player).isNotEmpty() }
        val stopped = calls("blockStopBreak", player).single()[1] as dev.netherforge.plugin.platform.BlockRef
        assertEquals(Triple(x, y, z), Triple(stopped.x, stopped.y, stopped.z))
        assertEquals("minecraft:bedrock", stopped.id)
        main { assertEquals("minecraft:bedrock", platform.blocks.get(world, x, y, z)?.state) }
    }

    @Test
    fun `picking a block is heard, and what's answered is brought to hand`() {
        val player = join()
        main { place(at(2), "minecraft:oak_planks") }
        val (x, y, z) = block(at(2))
        val inventory = InventoryRef.Player(player.uuid)
        main { assertTrue(platform.inventories.setItem(inventory, 12, item("minecraft:diamond", 3))) }
        afterwards { platform.inventories.setItem(inventory, 12, null) }
        afterwards { events.pick = null }
        events.pick = PickAnswer(12, null)
        act(player, BotAction.PickBlock(x, y, z))
        eventually("heard") { calls("pickBlock", player).isNotEmpty() }
        val (_, picked, held) = calls("pickBlock", player).single()
        assertEquals(Triple(x, y, z), (picked as dev.netherforge.plugin.platform.BlockRef).let { Triple(it.x, it.y, it.z) })
        assertEquals("minecraft:diamond", (held as List<*>).filterIsInstance<ItemData>().single().def.kind)
        // Swapped from slot 12 into the first empty hotbar slot, from the held one on, which is held.
        eventually("brought to hand") { held(player)?.def?.kind == "minecraft:diamond" }
        assertEquals(3, count(held(player)))
        main { assertNull(platform.inventories.contents(inventory)!![12]) }
    }

    @Test
    fun `right-clicking an entity is heard once, for the hand`() {
        val player = join()
        val pig = spawn("minecraft:pig", at(2), ai = false)
        eventually("sent to them") { bots.state(player.name).entities.any { it.uuid == pig.toString() } }
        act(player, BotAction.Interact(entity = pig.toString()))
        eventually("heard") { calls("playerInteractEntity", player).isNotEmpty() }
        ticks(2)
        assertEquals(listOf(listOf<Any?>(player, pig, "main_hand")), calls("playerInteractEntity", player))
    }

    @Test
    fun `clicking an entity NetherForge made is heard as a click on it, with either button`() {
        val player = join()
        val box = main { tagged(platform.entities.spawnHitbox(at(2), 1.0, 1.0, EntityTag(UUID.randomUUID(), "box", EntityRole.HITBOX))) }
        eventually("sent to them") { bots.state(player.name).entities.any { it.uuid == box.toString() } }
        ticks(2)
        act(player, BotAction.Interact(entity = box.toString()))
        act(player, BotAction.Attack(entity = box.toString()))
        eventually("both clicks heard") { events.of("entityClicked").size == 2 }
        val clicks = events.of("entityClicked")
        assertEquals(listOf<Any?>(box to ClickButton.RIGHT, box to ClickButton.LEFT), clicks.map { it[0] to it[2] })
        assertTrue(clicks.all { it[1] == player && it[3] != null }, "by them, with where they looked: $clicks")
        assertEquals(emptyList(), calls("playerInteractEntity", player), "not heard as a vanilla entity's")
    }

    @Test
    fun `a bot's look is the server's, so where it faces is where its clicks aim`() {
        val player = join()
        act(player, BotAction.Look(90.0, 30.0))
        val turned = assertNotNull(main { platform.players.eye(player.uuid) })
        // Yaw 90 faces west (-x), pitch 30 looks down.
        assertTrue(
            kotlin.math.abs(turned.dx + 0.866) < 0.01 && kotlin.math.abs(turned.dy + 0.5) < 0.01 && kotlin.math.abs(turned.dz) < 0.01,
            "faced $turned"
        )

        val point = at(3, 1, 2)
        act(player, BotAction.LookAt(point.x, point.y, point.z))
        val ray = assertNotNull(main { platform.players.eye(player.uuid) })
        val toward = doubleArrayOf(point.x - ray.x, point.y - ray.y, point.z - ray.z)
        val length = kotlin.math.sqrt(toward.sumOf { it * it })
        val cos = (toward[0] * ray.dx + toward[1] * ray.dy + toward[2] * ray.dz) / length
        assertTrue(cos > 0.999, "faced $ray, not toward $point")
        assertEquals(
            main {
                bots.state(player.name).yaw
            },
            main { platform.players.eye(player.uuid)!! }.let { Math.toDegrees(kotlin.math.atan2(-it.dx, it.dz)) },
            0.5
        )
    }

    @Test
    fun `a click aims where the bot looks, or at the point it's given, not at the middle of what it clicks`() {
        val player = join()
        // A wide, flat box, like a board on a table: its middle is far from its corners.
        val board =
            main { tagged(platform.entities.spawnHitbox(at(2), 2.0, 0.25, EntityTag(UUID.randomUUID(), "board", EntityRole.HITBOX))) }
        eventually("sent to them") { bots.state(player.name).entities.any { it.uuid == board.toString() } }
        val middle = at(2)
        val corner = Triple(middle.x - 0.8, middle.y + 0.25, middle.z + 0.8)
        fun aimedAt(point: Triple<Double, Double, Double>): Boolean {
            val sight = events.of("entityClicked").last()[3] as Ray
            val toward = doubleArrayOf(point.first - sight.x, point.second - sight.y, point.third - sight.z)
            val length = kotlin.math.sqrt(toward.sumOf { it * it })
            return (toward[0] * sight.dx + toward[1] * sight.dy + toward[2] * sight.dz) / length > 0.999
        }

        act(player, BotAction.LookAt(corner.first, corner.second, corner.third))
        act(player, BotAction.Interact())
        eventually("the click heard") { events.of("entityClicked").size == 1 }
        assertTrue(aimedAt(corner), "a click on what it looks at keeps its look: ${events.of("entityClicked").last()}")

        val other = Triple(middle.x + 0.8, middle.y + 0.25, middle.z - 0.8)
        act(player, BotAction.Interact(entity = board.toString(), x = other.first, y = other.second, z = other.third))
        eventually("the second click heard") { events.of("entityClicked").size == 2 }
        assertTrue(aimedAt(other), "a click given a point aims there: ${events.of("entityClicked").last()}")

        // Turned once more and clicked at once: the click is where it looks now, not where it looked before.
        val third = Triple(middle.x - 0.5, middle.y + 0.2, middle.z - 0.5)
        act(player, BotAction.LookAt(third.first, third.second, third.third))
        act(player, BotAction.Interact())
        eventually("the third click heard") { events.of("entityClicked").size == 3 }
        assertTrue(aimedAt(third), "a click right after turning aims where it turned to: ${events.of("entityClicked").last()}")
    }

    @Test
    fun `what a bot looks at is its target, the nearest entity in front of the block it faces`() {
        val player = join()
        val (x, y, z) = block(at(2, -1))
        act(player, BotAction.LookAt(x + 0.5, y + 1.0, z + 0.5))
        val onBlock = assertNotNull(main { bots.state(player.name).target }, "looking at the ground")
        assertEquals(BotTargetKind.BLOCK, onBlock.kind)
        assertEquals(Triple(x, y, z), Triple(onBlock.blockX, onBlock.blockY, onBlock.blockZ))
        assertEquals("up", onBlock.face)
        assertEquals(main { platform.blocks.get(world, x, y, z)!!.state }, onBlock.state)
        assertNull(onBlock.entity)

        val box = main { tagged(platform.entities.spawnHitbox(at(2), 1.0, 1.0, EntityTag(UUID.randomUUID(), "box", EntityRole.HITBOX))) }
        eventually("sent to them") { bots.state(player.name).entities.any { it.uuid == box.toString() } }
        val where = at(2)
        act(player, BotAction.LookAt(where.x, where.y + 0.5, where.z))
        val onBox = assertNotNull(main { bots.state(player.name).target }, "looking at the hitbox")
        assertEquals(BotTargetKind.ENTITY, onBox.kind)
        assertEquals(box.toString(), onBox.entity)
        assertEquals("minecraft:interaction", onBox.type)
        assertTrue(onBox.distance < onBlock.distance, "the hitbox is in front of the ground: $onBox")

        act(player, BotAction.Look(0.0, -90.0))
        assertNull(main { bots.state(player.name).target }, "nothing but sky above")
    }

    @Test
    fun `attacking an entity hurts it, and the arm swings`() {
        val player = join()
        val pig = spawn("minecraft:pig", at(2), ai = false)
        eventually("sent to them") { bots.state(player.name).entities.any { it.uuid == pig.toString() } }
        act(player, BotAction.Attack(entity = pig.toString()))
        eventually("the hit heard") { events.of("entityDamaged").any { it[0] == pig } }
        val (_, amount, cause, attacker) = events.of("entityDamaged").first { it[0] == pig }
        assertEquals("entity_attack" to player.uuid, cause to attacker)
        assertTrue((amount as Double) > 0.0, "$amount")
        eventually("the swing heard") { events.heard<GameEvent.PlayerSwing>("playerSwing").any { it.player == player } }
        assertEquals("main_hand", events.heard<GameEvent.PlayerSwing>("playerSwing").first { it.player == player }.hand)
    }

    @Test
    fun `a swing at nothing is heard`() {
        val player = join()
        act(player, BotAction.Look(0.0, -90.0))
        act(player, BotAction.Swing)
        eventually("heard") { events.heard<GameEvent.PlayerSwing>("playerSwing").any { it.player == player } }
        assertEquals(listOf("main_hand"), events.heard<GameEvent.PlayerSwing>("playerSwing").filter { it.player == player }.map { it.hand })
    }

    @Test
    fun `a bot moved by the server is heard teleporting`() {
        val player = join()
        val to = at(3, 0, 2)
        act(player, BotAction.Teleport(to.x, to.y, to.z))
        val teleport = events.heard<GameEvent.PlayerTeleport>("playerTeleport").single { it.player == player }
        assertEquals("plugin", teleport.cause)
        assertEquals(block(to), block(teleport.to))
        main { assertEquals(block(to), block(platform.players.location(player.uuid)!!)) }
    }

    @Test
    fun `a death is heard, and pressing respawn brings them back`() {
        val player = join()
        main { assertTrue(platform.worldEntities.damage(player.uuid, 1000.0, null)) }
        eventually("the death heard") { calls("playerDied", player).isNotEmpty() }
        eventually("the death screen") { bots.state(player.name).dead }
        act(player, BotAction.Respawn)
        eventually("respawned") {
            !bots.state(player.name).dead &&
                events.heard<GameEvent.PlayerRespawn>("playerRespawn").any { it.player == player }
        }
        val respawn = events.heard<GameEvent.PlayerRespawn>("playerRespawn").single { it.player == player }
        assertEquals(world, respawn.location.world)
        main { assertEquals(20.0, platform.worldEntities.number(player.uuid, EntityNumber.HEALTH)) }
    }

    @Test
    fun `eating takes its time, is heard once it's eaten, and fills them up`() {
        val player = join()
        main { assertTrue(platform.worldEntities.setNumber(player.uuid, EntityNumber.FOOD, 10.0)) }
        holding(player, item("minecraft:bread", 2))
        act(player, BotAction.UseItem())
        ticks(5)
        assertEquals(emptyList(), calls("playerConsumeItem", player), "still eating")
        eventually("eaten") { calls("playerConsumeItem", player).isNotEmpty() }
        assertEquals("minecraft:bread", (calls("playerConsumeItem", player).single()[1] as ItemData).def.kind)
        eventually("fed") { platform.worldEntities.number(player.uuid, EntityNumber.FOOD) == 15.0 }
        val fed = events.heard<GameEvent.PlayerChangeFood>("playerChangeFood").single { it.player == player }
        assertEquals(15 to "minecraft:bread", fed.food to fed.item?.def?.kind)
        assertEquals(1, count(held(player)))
    }

    @Test
    fun `letting go before it's eaten is heard, and eats nothing`() {
        val player = join()
        main { assertTrue(platform.worldEntities.setNumber(player.uuid, EntityNumber.FOOD, 10.0)) }
        holding(player, item("minecraft:bread", 2))
        act(player, BotAction.UseItem())
        ticks(5)
        act(player, BotAction.ReleaseItem)
        eventually("let go") { events.heard<GameEvent.PlayerStopUsingItem>("playerStopUsingItem").any { it.player == player } }
        val stopped = events.heard<GameEvent.PlayerStopUsingItem>("playerStopUsingItem").single { it.player == player }
        assertEquals("minecraft:bread", stopped.item.def.kind)
        assertTrue(stopped.ticks in 1 until 32, "${stopped.ticks} ticks")
        ticks(40)
        assertEquals(emptyList(), calls("playerConsumeItem", player))
        assertEquals(2, count(held(player)))
    }

    @Test
    fun `opening a container is heard, and so is closing it`() {
        val player = join()
        main { place(at(2), "minecraft:chest") }
        val (x, y, z) = block(at(2))
        act(player, BotAction.UseBlock(x, y, z, "west"))
        eventually("opened") { events.heard<GameEvent.PlayerInventory>("playerOpenInventory").any { it.player == player } }
        val opened = events.heard<GameEvent.PlayerInventory>("playerOpenInventory").single { it.player == player }
        assertEquals("chest", opened.kind)
        assertEquals(dev.netherforge.plugin.platform.InventoryRef.Block(world, x, y, z), opened.inventory)
        act(player, BotAction.CloseMenu)
        eventually("closed") { events.heard<GameEvent.PlayerInventory>("playerCloseInventory").any { it.player == player } }
        assertEquals(
            opened.inventory,
            events.heard<GameEvent.PlayerInventory>("playerCloseInventory").single {
                it.player == player
            }.inventory
        )
    }

    @Test
    fun `clicks in a project menu, above and below, and a drag are heard, and so is closing it`() {
        val player = join()
        val window = UUID.randomUUID()
        main {
            platform.menus.create(window, WindowSpec(MenuType.CHEST, 9, "<gold>Contract"))
            assertTrue(platform.menus.setItem(window, 4, item("minecraft:diamond", 2)))
            assertTrue(platform.menus.open(window, player.uuid))
        }
        afterwards { platform.menus.destroy(window) }
        holding(player, item("minecraft:stick"))
        // Locked, as a script's menu usually is: nothing moves, so each click is the same on every server.
        events.cancelling += "menuClicked"
        act(player, BotAction.ClickSlot(4))
        act(player, BotAction.ClickSlot(9 + 27, BotClick.SHIFT_LEFT))
        eventually("both clicks heard") { events.of("menuClicked").size == 2 }
        val (above, below) = events.of("menuClicked").map { it[1] as MenuClick }
        assertEquals(listOf<Any?>(window, window), events.of("menuClicked").map { it[0] })
        assertEquals(
            listOf<Any?>(player, 4, true, "left", "minecraft:diamond"),
            listOf(above.player, above.slot, above.top, above.click, above.item?.def?.kind)
        )
        assertEquals(
            listOf<Any?>(player, 0, false, "shift_left", true, "minecraft:stick"),
            listOf(below.player, below.slot, below.top, below.click, below.movesItems, below.item?.def?.kind)
        )
        events.cancelling += "menuDragged"
        main { assertTrue(platform.menus.setCursor(window, player.uuid, item("minecraft:stick", 4))) }
        act(player, BotAction.Drag(listOf(0, 1)))
        eventually("the drag heard") { events.of("menuDragged").isNotEmpty() }
        val (_, dragger, slots, cursor) = events.of("menuDragged").single()
        assertEquals(listOf<Any?>(player, listOf(0, 1), "minecraft:stick"), listOf(dragger, slots, (cursor as ItemData?)?.def?.kind))
        main { platform.menus.setCursor(window, player.uuid, null) }
        act(player, BotAction.CloseMenu)
        eventually("the close heard") { events.of("menuClosed").isNotEmpty() }
        assertEquals(listOf(listOf<Any?>(window, player)), events.of("menuClosed"))
    }

    @Test
    fun `a dialog's button is heard pressed with every input's answer, and escape is heard closing it`() {
        val player = join()
        val dialog = DialogFile(
            title = "<gold>Contract",
            type = DialogType.MULTI_ACTION,
            inputs = listOf(TextInput("name", "Name"), BooleanInput("sure", "Sure?")),
            buttons = listOf(DialogButton("yes", "<green>Yes"), DialogButton("no", "No"))
        )
        main { assertTrue(platform.dialogs.show(player.uuid, DialogSpec("contract_ask", dialog))) }
        eventually("on screen") { bots.state(player.name).dialog != null }
        act(player, BotAction.DialogButton(button = "Yes", inputs = mapOf("name" to JsonPrimitive("Robo"))))
        eventually("the press heard") { events.of("dialogPressed").isNotEmpty() }
        assertEquals(
            listOf(listOf<Any?>(player, "contract_ask", "yes", mapOf("name" to "Robo", "sure" to "false"))),
            events.of("dialogPressed")
        )
        main { assertTrue(platform.dialogs.show(player.uuid, DialogSpec("contract_ask", dialog))) }
        eventually("on screen again") { bots.state(player.name).dialog != null }
        act(player, BotAction.DialogClose)
        eventually("the close heard") { events.of("dialogClosed").isNotEmpty() }
        assertEquals(listOf(listOf<Any?>(player, "contract_ask")), events.of("dialogClosed"))
    }

    @Test
    fun `flying is heard only from a player who may fly`() {
        val player = join()
        act(player, BotAction.Fly(true))
        ticks(2)
        assertEquals(emptyList(), events.heard<GameEvent.PlayerFly>("playerFly").filter { it.player == player }, "survival can't fly")
        main { assertTrue(platform.players.setGameMode(player.uuid, "creative")) }
        act(player, BotAction.Fly(true))
        act(player, BotAction.Fly(false))
        eventually("both heard") {
            events.heard<GameEvent.PlayerFly>("playerFly").filter { it.player == player }.map { it.flying } == listOf(true, false)
        }
    }

    private companion object {
        val COMMANDS = AtomicInteger()
    }
}
