package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotPack
import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.menu.MenuType
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeType
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.ItemView
import dev.netherforge.plugin.platform.ItemViewOps
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import dev.netherforge.plugin.platform.ResourcePackOps
import dev.netherforge.plugin.platform.WindowSpec
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DialogOps]: dialogs built per show. What's pressed comes back only from a client, which the bot scenario covers. */
abstract class DialogOpsContract : PlatformContract() {
    private val dialogs: DialogOps get() = platform.dialogs

    @Test
    fun `every kind of dialog shows to a player online, and closes`() {
        val player = join()
        main {
            val buttons = listOf(DialogButton("yes", "<green>Yes"), DialogButton("no"))
            for (type in DialogType.entries) {
                val file = DialogFile(
                    title = "<gold>Contract",
                    type = type,
                    buttons = if (type ==
                        DialogType.NOTICE
                    ) {
                        buttons.take(1)
                    } else {
                        buttons
                    }
                )
                val listed = if (type == DialogType.DIALOG_LIST) listOf(DialogSpec("inner", DialogFile(title = "Inner"))) else emptyList()
                assertTrue(dialogs.show(player.uuid, DialogSpec("contract_$type", file, listed)), "$type")
            }
            assertTrue(dialogs.close(player.uuid))
            assertFalse(dialogs.show(UUID.randomUUID(), DialogSpec("x", DialogFile(title = "x"))))
            assertFalse(dialogs.close(UUID.randomUUID()))
        }
    }

    @Test
    fun `the player's screen shows the dialog as built, only theirs, until it's closed`() {
        val player = join()
        val other = join(at(2))
        val file = DialogFile(
            title = "<gold>Contract <i>terms",
            type = DialogType.CONFIRMATION,
            buttons = listOf(DialogButton("yes", "<green>Agree"), DialogButton("no"))
        )
        main { assertTrue(dialogs.show(player.uuid, DialogSpec("contract_terms", file))) }
        val shown = awaitScreen(player, "the dialog") { it.dialog != null }.dialog!!
        assertEquals(listOf("confirmation", "Contract terms", listOf("Agree", "no")), listOf(shown.type, shown.title, shown.buttons))
        settled()
        assertNull(screen(other).dialog, "shown to someone else")
        main { assertTrue(dialogs.close(player.uuid)) }
        awaitScreen(player, "no dialog") { it.dialog == null }
    }
}

/** [ResourcePackOps]: offering a pack, which the player's game answers. */
abstract class ResourcePackOpsContract : PlatformContract() {
    private val packs: ResourcePackOps get() = platform.resourcePacks

    @Test
    fun `a pack is offered to a player online, whose answer is heard`() {
        val player = join()
        val pack = PackOffer(UUID.randomUUID(), "http://127.0.0.1:1/pack.zip", "0".repeat(40), required = false, prompt = "<gold>Please")
        main { assertTrue(packs.send(player.uuid, pack)) }
        eventually("the player's answer") { events.of("resourcePackStatus").any { it == listOf(player.uuid, pack.id, "declined") } }
        main { assertFalse(packs.send(UUID.randomUUID(), pack)) }
    }

    @Test
    fun `the player's client is offered the pack's url and hash, and an offer under the same id replaces it`() {
        val player = join()
        val id = UUID.randomUUID()
        val first = PackOffer(id, "http://127.0.0.1:1/first.zip", "ab".repeat(20), required = false, prompt = null)
        main { assertTrue(packs.send(player.uuid, first)) }
        awaitScreen(player, "the pack") { state -> state.resourcePacks.any { it.id == id.toString() && it.status == "declined" } }
        val again = PackOffer(id, "http://127.0.0.1:1/second.zip", "cd".repeat(20), required = true, prompt = "<red>Needed")
        main { assertTrue(packs.send(player.uuid, again)) }
        val state = awaitScreen(player, "the second pack") { state -> state.resourcePacks.any { it.url == again.url } }
        assertEquals(
            listOf(BotPack(id.toString(), again.url, again.sha1, "declined")),
            state.resourcePacks.filter { it.id == id.toString() },
            "one pack under that id"
        )
    }
}

/** [ProjectItemOps]: stacks of project items, stamped with their look and rewritten when it changes. */
abstract class ProjectItemOpsContract : PlatformContract() {
    private val items: ProjectItemOps get() = platform.projectItems

    @Test
    fun `a stack is stamped with its item's look, and rewritten once the look changes`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald", name = "<red>Ruby"), "look1")
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("ruby"), count = 2))))
            assertTrue(platform.inventories.setItem(inventory, 1, item("minecraft:emerald")))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("minecraft:emerald", stack.def.kind)
            assertEquals("look1", stack.look)
            assertEquals(0, items.refresh(inventory), "current")
        }
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:diamond", name = "<red>Big Ruby"), "look2")
        main {
            assertEquals(1, items.refresh(inventory))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("minecraft:diamond", stack.def.kind)
            assertEquals(2, stack.def.count, "the stack's own count stays")
            assertEquals("look2", stack.look)
            // The new stack went by its item's `render` handlers (the runtime's) as it was made.
            assertTrue(events.of("itemRendered").any { (it[0] as ItemData).def.kind == "minecraft:diamond" }, "rendered")
            assertEquals("minecraft:emerald", platform.inventories.contents(inventory)!![1]?.def?.kind, "not a project item")
            assertEquals(0, items.refresh(inventory))
            assertEquals(0, items.refresh(InventoryRef.Player(UUID.randomUUID())), "nobody's")
        }
    }

    @Test
    fun `a stack of an item worn as equipment carries its look and slot, and reads them back as a file writes them`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        val crown = EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.HEAD)
        server.itemLooks["crown"] = ItemLook(ItemDef(kind = "minecraft:paper", equipment = crown), "look1")
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("crown")))))
            assertEquals(crown, platform.inventories.contents(inventory)!![0]!!.def.equipment)
            // Written in full on a stack of its own: still the project's look, read back bare.
            val boots = EquipmentDef(ResourceRef("${ContractServer.NAMESPACE}:gear/ruby"), EquipSlot.FEET)
            assertTrue(platform.inventories.setItem(inventory, 1, ItemData(ItemDef(kind = "minecraft:paper", equipment = boots))))
            assertEquals(
                EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.FEET),
                platform.inventories.contents(inventory)!![1]!!.def.equipment
            )
            assertEquals(null, platform.inventories.contents(inventory)!![2]?.def?.equipment)
        }
    }

    @Test
    fun `stacks of an item the project no longer has are left alone`() {
        val player = join()
        val inventory = InventoryRef.EnderChest(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald"), "look1")
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = ResourceRef("ruby"))))) }
        server.itemLooks.remove("ruby")
        main {
            assertEquals(0, items.refresh(inventory))
            assertEquals(ResourceRef("ruby"), platform.inventories.contents(inventory)!![0]?.def?.item)
        }
    }

    @Test
    fun `a stack names its item in the project's namespace however it was written, and reads back as a file writes it`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.itemLooks["ruby"] = ItemLook(ItemDef(kind = "minecraft:emerald"), "look1")
        main {
            val explicit = ResourceRef("${ContractServer.NAMESPACE}:ruby")
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(item = explicit))))
            val stack = platform.inventories.contents(inventory)!![0]!!
            assertEquals(ResourceRef("ruby"), stack.def.item)
            assertEquals("look1", stack.look)
        }
    }
}

/** [RecipeOps]: the project's recipes on the server, and players' recipe books. */
abstract class RecipeOpsContract : PlatformContract() {
    private val recipes: RecipeOps get() = platform.recipes
    private val paper =
        RecipeFile(type = RecipeType.SHAPELESS, ingredients = listOf(Ingredient.of("minecraft:stick")), result = ItemDef("minecraft:paper"))

    @Test
    fun `a recipe is added, replaced and taken away`() {
        main {
            afterwards { recipes.remove("contract_paper") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper.copy(result = ItemDef("minecraft:paper", count = 2)))), "replaced")
            recipes.resend()
            assertTrue(recipes.remove("contract_paper"))
            assertFalse(recipes.remove("contract_paper"))
            assertFalse(recipes.add(RecipeSpec("contract_bad", paper.copy(result = ItemDef("minecraft:nf_no_such_item")))))
        }
    }

    @Test
    fun `changing recipes keeps what players have done towards an advancement`() {
        val player = join()
        main {
            afterwards { recipes.remove("contract_paper") }
            // Nothing has written this progress to a file yet, and the game reads advancements back from there when recipes change.
            assertTrue(platform.advancements.grant(player.uuid, "minecraft:story/mine_stone"))
            afterwards { platform.advancements.revoke(player.uuid, "minecraft:story/mine_stone") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertTrue(platform.advancements.has(player.uuid, "minecraft:story/mine_stone"))
            assertTrue(recipes.remove("contract_paper"))
            recipes.resend()
            assertTrue(platform.advancements.has(player.uuid, "minecraft:story/mine_stone"))
        }
    }

    @Test
    fun `players discover the project's recipes and Minecraft's`() {
        val player = join()
        main {
            afterwards { recipes.remove("contract_paper") }
            assertTrue(recipes.add(RecipeSpec("contract_paper", paper)))
            assertFalse(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertTrue(recipes.discover(player.uuid, "contract_paper"))
            assertTrue(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertFalse(recipes.discover(player.uuid, "contract_paper"), "already")
            assertTrue(recipes.undiscover(player.uuid, "contract_paper"))
            assertFalse(recipes.hasDiscovered(player.uuid, "contract_paper"))
            assertFalse(recipes.undiscover(player.uuid, "contract_paper"))
            assertTrue(recipes.discover(player.uuid, "minecraft:bread"))
            assertTrue(recipes.hasDiscovered(player.uuid, "minecraft:bread"))
            assertFalse(recipes.discover(player.uuid, "contract_none"), "no such recipe")
            assertFalse(recipes.discover(UUID.randomUUID(), "minecraft:bread"), "offline")
        }
    }
}

/**
 * [ItemViewOps]: a stack looks to the player it's sent to as the runtime says ([RecordingEvents.view]), in their
 * inventory and in a window they have open, while the server, and everyone else, keeps it as it is.
 */
abstract class ItemViewOpsContract : PlatformContract() {
    private val views: ItemViewOps get() = platform.itemViews

    private fun shownLore(player: PlayerRef, slot: Int): List<String>? = screen(player).inventory.firstOrNull { it.slot == slot }?.lore

    @Test
    fun `a stack looks different to one player alone, and stays as it is on the server`() {
        val looker = join()
        val other = join()
        events.view = { player, stack ->
            if (player == looker.uuid && stack.def.kind == "minecraft:emerald") ItemView("Shiny", listOf("Sells for 5")) else null
        }
        afterwards { events.view = null }
        main {
            assertTrue(platform.inventories.setItem(InventoryRef.Player(looker.uuid), 0, item("minecraft:emerald", 3)))
            assertTrue(platform.inventories.setItem(InventoryRef.Player(other.uuid), 0, item("minecraft:emerald", 3)))
        }
        val shown = awaitScreen(looker, "the emerald as it looks to them") { state ->
            state.inventory.any { it.slot == 0 && it.lore == listOf("Sells for 5") }
        }.inventory.first { it.slot == 0 }
        assertEquals(listOf<Any?>("minecraft:emerald", 3, "Shiny"), listOf(shown.item, shown.count, shown.name))
        settled()
        assertEquals(emptyList(), shownLore(other, 0), "another player is sent it as it is")
        main {
            val real = platform.inventories.item(InventoryRef.Player(looker.uuid), 0)!!.def
            assertNull(real.lore, "the server keeps no lore")
            assertNull(real.name)
            assertEquals(3, real.count)
        }
        assertTrue(events.of("itemViewing").isNotEmpty(), "asked whether it may look different first")
        assertTrue(
            events.of("itemViewed").any {
                it[0] == looker.uuid && (it[1] as ItemData).def.lore == null
            },
            "asked with the real stack"
        )
    }

    @Test
    fun `a refresh sends again what now looks different, and only then`() {
        val player = join()
        var lore: List<String>? = null
        events.view = { _, stack -> lore?.takeIf { stack.def.kind == "minecraft:emerald" }?.let { ItemView(null, it) } }
        afterwards { events.view = null }
        main { assertTrue(platform.inventories.setItem(InventoryRef.Player(player.uuid), 0, item("minecraft:emerald"))) }
        awaitScreen(player, "the emerald") { state -> state.inventory.any { it.slot == 0 } }
        settled()
        lore = listOf("In the shop")
        settled()
        assertEquals(emptyList(), shownLore(player, 0), "nothing changed on the server, so nothing is sent")
        main { assertTrue(views.refresh(player.uuid)) }
        awaitScreen(player, "the emerald as it looks now") { shownLore(player, 0) == listOf("In the shop") }
        lore = null
        main { assertTrue(views.refresh(player.uuid)) }
        awaitScreen(player, "the emerald as it is again") { shownLore(player, 0) == emptyList<String>() }
        main { assertFalse(views.refresh(UUID.randomUUID()), "nobody's") }
    }

    @Test
    fun `a window's stacks look different to its viewer, and a click on one is heard with the real stack`() {
        val player = join()
        val window = UUID.randomUUID()
        events.view = { _, stack -> if (stack.def.kind == "minecraft:diamond") ItemView(null, listOf("Sells for 50")) else null }
        afterwards { events.view = null }
        main {
            platform.menus.create(window, WindowSpec(MenuType.CHEST, 9, "<gold>Contract"))
            assertTrue(platform.menus.setItem(window, 4, item("minecraft:diamond", 2)))
            assertTrue(platform.menus.open(window, player.uuid))
        }
        afterwards { platform.menus.destroy(window) }
        awaitScreen(player, "the diamond as it looks to them") { state ->
            state.menu?.items?.any { it.slot == 4 && it.lore == listOf("Sells for 50") } == true
        }
        events.cancelling += "menuClicked"
        act(player, BotAction.ClickSlot(4))
        eventually("the click heard") { events.of("menuClicked").isNotEmpty() }
        val click = events.of("menuClicked").single()[1] as MenuClick
        assertEquals("minecraft:diamond", click.item?.def?.kind)
        assertNull(click.item?.def?.lore, "the server's own stack")
        main { assertNull(platform.menus.item(window, 4)!!.def.lore) }
    }
}
