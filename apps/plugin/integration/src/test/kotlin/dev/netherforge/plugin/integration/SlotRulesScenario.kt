package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menu slots with rules and a slot that shows what a player wears, clicked by a bot through the game's own
 * inventory clicks: only a rule's `max` of a stack goes in (the rest stays on the cursor, as the engine moves it
 * itself), a stack the slot doesn't take stays put, and a helmet put in the head slot is worn.
 */
class SlotRulesScenario : Scenario("builtins") {
    override val server = PaperServer(onlineMode = true, maxPlayers = 1)

    private val bots by lazy { Bots(editor) }

    /** A 1-row window: its own 9 slots, then the inventory's 27, then the hotbar's 9 (36 on). */
    private val hotbar = 36

    @Test
    @Order(1)
    fun `only max goes in, a stack the slot doesn't take is refused, and a helmet is worn`() {
        editor.run("difficulty peaceful")
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams("Tester"))
        assertTrue(joined.ok, joined.error)
        bots.act("Tester", BotAction.Command("it-builtins gear"))
        editor.logged("gear", "head")
        // The window shows the bot's own inventory below its slots: hotbar slot n is the window's 36 + n.
        val held = bots.eventually("Tester") { it.menu != null && it.inventory.size == 2 }.inventory
        val diamonds = hotbar + held.first { it.item == "minecraft:diamond" }.slot
        val helmet = hotbar + held.first { it.item == "minecraft:iron_helmet" }.slot
        assertTrue(held.all { it.slot < 9 }, "given to the hotbar: $held")

        // Five diamonds onto the cursor, then into the ring slot: one goes in, four stay on the cursor.
        bots.act("Tester", BotAction.ClickSlot(diamonds))
        bots.eventually("Tester") { it.menu?.cursor?.count == 5 }
        bots.act("Tester", BotAction.ClickSlot(0))
        val after = bots.eventually("Tester") { it.menu?.cursor?.count == 4 }.menu!!
        assertEquals(1, after.items.first { it.slot == 0 }.count)
        // As the server has them: none left in the inventory, four on the cursor, one in the storage.
        bots.act("Tester", BotAction.Command("it-builtins count"))
        editor.logged("count", "0", "4", "1")
        // The head slot doesn't take diamonds: nothing moves.
        bots.act("Tester", BotAction.ClickSlot(1))
        val refused = bots.eventually("Tester") { it.menu?.cursor?.count == 4 }.menu!!
        assertNull(refused.items.firstOrNull { it.slot == 1 })
        // The diamonds back, and the helmet into the head slot: worn.
        bots.act("Tester", BotAction.ClickSlot(diamonds))
        bots.act("Tester", BotAction.Command("it-builtins count"))
        editor.logged("count", "4", "0", "1")
        // The bot's own picture of its hotbar lags after a click the server moved by hand (it guessed the whole stack
        // went in), so from here what counts is the server's.
        bots.act("Tester", BotAction.ClickSlot(helmet))
        bots.act("Tester", BotAction.ClickSlot(1))
        bots.act("Tester", BotAction.Command("it-builtins worn"))
        editor.logged("worn", "minecraft:iron_helmet", "minecraft:diamondx1")
    }
}
