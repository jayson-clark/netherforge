package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotParams
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotTeam
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.InstanceInfo
import dev.netherforge.format.bridge.PlayerPositionParams
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bots: fake players (the `NetherForgeBots` plugin) on an online-mode server,
 * as the editor runs one. What only a player can do (join, chat, run
 * commands, click centities, use menus, items, recipes and dialogs) and what
 * only a player sees (the resource pack, titles, sidebars, boss bars, teams
 * and the player list, per-player hiding and views), each through the
 * plugin's real paths. Anything player-facing gets a step here.
 */
class BotScenario : Scenario("bots") {
    override val server = PaperServer(onlineMode = true, maxPlayers = 2)

    private val bots by lazy { Bots(editor) }

    /** Event numbers to read a bot's events from. */
    private var since = 0
    private lateinit var tester: BotInfo
    private lateinit var tower: InstanceInfo

    override fun prepare(project: TestProject) {
        // Banning someone takes moderation, which the project declares it requires.
        val manifest = "netherforge.json"
        check("\"requires\": {" in project.read(manifest)) { "examples/showcase declares no requires" }
        project.write(manifest, project.read(manifest).replace("\"requires\": {", "\"requires\": {\n    \"moderation\": true,"))
    }

    @Test
    @Order(1)
    fun `a bot joins, is welcomed, loads the pack, and answers the first-join dialog`() {
        // No mobs wandering in to fight the bots.
        editor.run("difficulty peaceful")
        // The configuration phase, the world, then the greeter's welcome and its first-join dialog.
        val (joined, tester) = editor.request(BotsExtension.join, BotJoinParams("Tester"))
        assertTrue(joined.ok, joined.error)
        this.tester = tester!!
        // The editor's "use my position": where the one player online stands, to the block.
        val (where, position) = editor.request(Bridge.playerPosition, PlayerPositionParams())
        assertTrue(where.ok, where.error)
        assertEquals("Tester" to tester.world, position!!.player to position.world)
        val near = abs(position.x - tester.x) <= 1 && abs(position.y - tester.y) <= 1 && abs(position.z - tester.z) <= 1
        assertTrue(near, "$position")
        bots.said("Tester", 0, "Welcome, Tester!")
        // The greeter requires the library's greetings (examples/library, a package examples/showcase depends on).
        bots.said("Tester", 0, "Well met, Tester!")
        val welcome = bots.heard("Tester", 0, BotEvent.Dialog::class.java).dialog
        assertEquals("Welcome!", welcome.title)
        assertEquals(listOf("nickname"), welcome.inputs.map { it.key })
        assertEquals(JsonPrimitive("Tester"), welcome.inputs.single().initial)
        // The resource pack: downloaded from the plugin's pack server and checked against its hash.
        bots.heard("Tester", 0, BotEvent.ResourcePack::class.java) { it.status == "successfully_loaded" }

        // The dialog's button, with what was typed into it, reaches the dialog's script.
        since = bots.act("Tester", BotAction.DialogButton(button = "Done", inputs = mapOf("nickname" to JsonPrimitive("Robo")))).since
        bots.said("Tester", since, "Nice to meet you, Robo!")
        bots.eventually("Tester") { it.dialog == null }
    }

    @Test
    @Order(2)
    fun `a project command opens the shop, a cancelled click takes nothing, and the close button closes it`() {
        since = bots.act("Tester", BotAction.Command("/shop")).since
        bots.heard("Tester", since, BotEvent.MenuOpen::class.java) { it.title.endsWith("Village shop") }
        bots.said("Tester", since, "Welcome to the shop, Robo!")
        val shop = bots.eventually("Tester") { it.menu != null }.menu!!
        assertEquals(27, shop.size)
        assertEquals("Ruby", shop.items.single { it.slot == 13 }.name)
        // The library's gem, sold by the project's shop, in the library's look.
        val gem = shop.items.single { it.slot == 12 }
        assertEquals("Gem" to "minecraft:emerald", gem.name to gem.item)
        since = bots.act("Tester", BotAction.ClickSlot(13)).since
        bots.said("Tester", since, "Rubies are sold out.")
        assertEquals(emptyList(), bots.state("Tester").inventory)
        since = bots.act("Tester", BotAction.ClickSlot(15)).since
        bots.heard("Tester", since, BotEvent.MenuClose::class.java)
    }

    @Test
    @Order(3)
    fun `a project item is given, used, and rewritten when its file changes`() {
        bots.act("Tester", BotAction.Command("ruby"))
        val ruby = bots.eventually("Tester") { it.inventory.any { item -> item.name == "Ruby" } }.inventory.single()
        assertEquals("minecraft:paper", ruby.item)
        assertEquals(2, ruby.count)
        assertEquals(listOf("Warm to the touch"), ruby.lore)
        since = bots.act("Tester", BotAction.UseItem()).since
        bots.said("Tester", since, "The ruby glows in your hand.")
        // Its look rewritten, keeping the stack's count and data.
        val item = "items/ruby/item.json"
        project.write(item, project.read(item).replace("<red>Ruby", "<red>Polished ruby"))
        val result = editor.reload(item)
        assertEquals(listOf(true to 1), result.resources.map { it.ok to it.reattached }, "Tester's stack was rewritten")
        val polished = bots.eventually("Tester") { it.inventory.singleOrNull()?.name == "Polished ruby" }.inventory.single()
        assertEquals(2, polished.count)
        since = bots.act("Tester", BotAction.Command("rubycheck")).since
        bots.said("Tester", since, "held ruby x2 found=it")
    }

    @Test
    @Order(4)
    fun `a project recipe is learnt, refused with the wrong items, and crafted with rubies`() {
        since = bots.act("Tester", BotAction.Command("learn")).since
        bots.said("Tester", since, "learned true true")
        val at = bots.state("Tester")
        val bench = Triple(Math.floor(at.x).toInt() + 2, Math.floor(at.y).toInt(), Math.floor(at.z).toInt())
        editor.run("setblock ${bench.first} ${bench.second} ${bench.third} minecraft:crafting_table")
        editor.run("give Tester minecraft:stick 1")
        editor.run("give Tester minecraft:paper 2")
        bots.eventually("Tester") { it.inventory.size == 3 }
        bots.act("Tester", BotAction.UseBlock(bench.first, bench.second, bench.third, face = "west"))
        bots.eventually("Tester") { it.menu?.type == "minecraft:crafting" }
        // The crafting table's own slots: the result (0), then the grid (1 to 9); the hotbar is 37 on. Plain paper
        // where its rubies go: the server matches paper; the plugin checks the stacks' item ids.
        for (click in listOf(
            BotAction.ClickSlot(39),
            BotAction.ClickSlot(2, BotClick.RIGHT),
            BotAction.ClickSlot(5, BotClick.RIGHT),
            BotAction.ClickSlot(38),
            BotAction.ClickSlot(8)
        )) {
            bots.act("Tester", click)
        }
        val refused = bots.eventually("Tester") { it.menu?.items?.map { item -> item.slot } == listOf(2, 5, 8) }.menu!!
        assertEquals(listOf("minecraft:paper", "minecraft:paper", "minecraft:stick"), refused.items.map { it.item })
        for (click in listOf(
            BotAction.ClickSlot(2),
            BotAction.ClickSlot(5),
            BotAction.ClickSlot(5),
            BotAction.ClickSlot(39),
            BotAction.ClickSlot(37),
            BotAction.ClickSlot(2, BotClick.RIGHT),
            BotAction.ClickSlot(5, BotClick.RIGHT)
        )) {
            bots.act("Tester", click)
        }
        val crafting = bots.eventually("Tester") { it.menu?.items?.any { item -> item.slot == 0 } == true }.menu!!
        val sword = crafting.items.single { it.slot == 0 }
        assertEquals("minecraft:iron_sword", sword.item)
        assertEquals("Ruby sword", sword.name)
        since = bots.act("Tester", BotAction.ClickSlot(0, BotClick.SHIFT_LEFT)).since
        bots.said("Tester", since, "crafted ruby_sword")
        bots.eventually("Tester") { it.inventory.any { item -> item.name == "Ruby sword" } }
        bots.act("Tester", BotAction.Command("closeinv"))
        bots.eventually("Tester") { it.menu == null }
        // Out of the way of the walk below.
        editor.run("setblock ${bench.first} ${bench.second} ${bench.third} minecraft:air")
    }

    @Test
    @Order(5)
    fun `the screen shows a title, an action bar, a sidebar and a boss bar`() {
        bots.act("Tester", BotAction.Command("screen"))
        val screen = bots.eventually("Tester") { it.sidebar != null }
        assertEquals("Big title", screen.title)
        assertEquals("small print", screen.subtitle)
        assertEquals("Action!", screen.actionBar)
        assertEquals(listOf("Gold: 10", "Silver: 3"), screen.sidebar?.lines)
        assertEquals("Coins", screen.sidebar?.title)
        assertEquals(listOf("Wave 2"), screen.bossBars.map { it.name })
    }

    @Test
    @Order(6)
    fun `chat and sneaking reach scripts, and walking passes the server's movement checks`() {
        since = bots.act("Tester", BotAction.Chat("hello <b>there")).since
        bots.said("Tester", since, "You said: hello <b>there")
        since = bots.act("Tester", BotAction.Sneak(true)).since
        bots.said("Tester", since, "sneaking true")
        bots.act("Tester", BotAction.Sneak(false))

        val start = bots.state("Tester")
        val walked = bots.act("Tester", BotAction.WalkTo(start.x + 4, start.z))
        assertTrue(abs(walked.x - (start.x + 4)) < 0.5, "walked to ${walked.x}")
        assertEquals(start.y, walked.y, "on flat ground")
    }

    @Test
    @Order(7)
    fun `a centity's node is clicked by name, attacked, and clicked where the bot looks`() {
        since = bots.act("Tester", BotAction.Command("tower 2")).since
        bots.said("Tester", since, "A tower rises 2 blocks ahead.")
        tower = editor.request(Bridge.instances, Unit).second!!.single()
        // Its script counts turns in turns.lua, the file beside it that it requires: this tower's own copy. It says so on
        // the action bar.
        for ((turn, click) in listOf(
            BotAction.Interact(centity = tower.uuid, node = "cap"),
            BotAction.Attack(centity = tower.uuid),
            BotAction.Interact()
        ).withIndex()) {
            since = bots.act("Tester", click).since
            try {
                bots.heard("Tester", since, BotEvent.ActionBar::class.java) {
                    it.text == "The tower turns. Turns of this tower: ${turn + 1}"
                }
            } catch (e: AssertionError) {
                throw AssertionError("click ${turn + 1}, $click: ${e.message}", e)
            }
        }
    }

    @Test
    @Order(8)
    fun `a second bot turns the pack down, and per-player hiding hides the tower from one of them`() {
        val (other, _) = editor.request(
            BotsExtension.join,
            BotJoinParams("Other", BotPosition(tower.x - 2, tower.y, tower.z + 0.5), BotPackAnswer.DECLINE)
        )
        assertTrue(other.ok, other.error)
        bots.eventually("Other") { it.resourcePacks.singleOrNull()?.status == "declined" }
        bots.eventually("Other") { state -> state.entities.any { it.uuid == tester.uuid } }
        assertEquals(
            listOf("Other", "Tester"),
            editor.request(BotsExtension.list, Unit).second!!.map { it.name }.sorted()
        )

        // The tower's entities are no longer sent to Tester, and can't be clicked; Other still can.
        bots.act("Tester", BotAction.Command("hidetowers"))
        bots.eventually("Tester") { state -> state.entities.none { it.type == "minecraft:interaction" } }
        assertTrue("doesn't know entity" in bots.refused("Tester", BotAction.Interact(centity = tower.uuid)))
        since = bots.act("Other", BotAction.Interact(centity = tower.uuid)).since
        bots.heard("Other", since, BotEvent.ActionBar::class.java) { it.text.startsWith("The tower turns.") }
    }

    @Test
    @Order(9)
    fun `teams and the player list, and a module reload making its team again`() {
        // Tester has a sidebar, so its own scoreboard copies the team; Other sees the server's.
        editor.run("team add vanilla")
        since = bots.act("Tester", BotAction.Command("jointeam")).since
        bots.said("Tester", since, "team true <gold>Star Tester")
        val red = BotTeam(
            name = "nf.red", displayName = "red", prefix = "[R] ", suffix = " *", color = "red", friendlyFire = false,
            seeInvisibleTeammates = true, nametags = "hideForOtherTeams", collision = "pushOwnTeam", members = listOf("Tester")
        )
        for (bot in listOf("Tester", "Other")) {
            val seen = bots.eventually(bot) { state -> red in state.teams }
            assertTrue(seen.teams.any { it.name == "vanilla" }, "$bot sees the other team: ${seen.teams}")
            val entry = bots.eventually(bot) { state -> state.playerList.any { it.name == "Tester" && it.belowName == "12 hearts" } }
                .playerList.single { it.name == "Tester" }
            assertEquals("Star Tester", entry.displayName)
            assertEquals(7, entry.order)
            // Out of Other's list, still in their own.
            assertEquals(bot == "Tester", entry.listed, "$bot's list")
        }
        // Other is in Tester's list, and has no line under their name.
        val otherEntry = bots.state("Tester").playerList.single { it.name == "Other" }
        assertTrue(otherEntry.listed)
        assertEquals(null, otherEntry.belowName)

        // Reloading the module takes its team away and the body makes it again, without members; the server's stays.
        val reloaded = editor.reload("modules/screen/init.lua")
        assertTrue(reloaded.resources.single().ok, "${reloaded.resources}")
        for (bot in listOf("Tester", "Other")) {
            val fresh = bots.eventually(bot) { state -> red.copy(members = emptyList()) in state.teams }
            assertTrue(fresh.teams.any { it.name == "vanilla" }, "$bot still sees the other team: ${fresh.teams}")
        }
    }

    @Test
    @Order(10)
    fun `what only one player is shown, a block, equipment, a book, another's eyes, a compass and a view distance`() {
        since = bots.act("Tester", BotAction.Command("illusions")).since
        val other = bots.state("Other").uuid
        bots.heard("Tester", since, BotEvent.BlockChange::class.java) { it.state == "minecraft:gold_block" }
        bots.heard("Tester", since, BotEvent.Equipment::class.java) {
            it.entity == other && it.slot == "head" && it.item == "minecraft:carved_pumpkin"
        }
        bots.heard("Tester", since, BotEvent.BookOpen::class.java) { it.pages == listOf("Page one", "Page two") }
        bots.heard("Tester", since, BotEvent.Camera::class.java) { it.entity == other }
        bots.heard("Tester", since, BotEvent.Camera::class.java) { it.entity == null }
        bots.said("Tester", since, "illusions true true compass=100,70,-20 distance=true")
        assertEquals("survival", bots.eventually("Tester") { it.gameMode == "survival" }.gameMode)
        // Other was shown nothing of it.
        assertTrue(bots.events("Other", 0).none { it is BotEvent.BookOpen || it is BotEvent.Camera })
    }

    @Test
    @Order(11)
    fun `permissions, advancements and when people played, on the real server`() {
        // The project's grant (from the welcome dialog); one outside allow.permissions refused.
        since = bots.act("Tester", BotAction.Command("perms")).since
        bots.said("Tester", since, "perms true false true true")
        since = bots.act("Tester", BotAction.Command("adv")).since
        bots.said("Tester", since, "adv true true 1 0 true false")
        since = bots.act("Tester", BotAction.Command("played")).since
        bots.said("Tester", since, "played true true 2")

        // The example's milestones: /adv's diamonds made Tester a VIP (the advancement handler's grant), which the
        // console's /perm takes away and gives back. /milestones reads Minecraft's own advancements by key.
        fun milestones(): List<String> {
            val asked = bots.act("Tester", BotAction.Command("milestones")).since
            return bots.heard("Tester", asked, BotEvent.Chat::class.java) { it.text.startsWith("Milestones") }.text.lines()
        }
        val progress = milestones()
        assertEquals(listOf("Milestones", "✘ Stone Age", "✘ Acquire Hardware", "✘ Diamonds!", "✘ Nether"), progress.take(5))
        assertTrue(progress[5].startsWith("Biomes visited: ") && " of " in progress[5], progress[5])
        assertEquals("You're a VIP.", progress.last())
        editor.run("perm unset Tester showcase.vip")
        assertEquals(6, milestones().size, "no longer a VIP")
        editor.run("perm grant Tester showcase.vip")
        assertEquals("You're a VIP.", milestones().last())

        // The example's rewards: /treasure rolls loot/treasure.json for them (its player pool, and the game's
        // dungeon chest through the server's own loot table when a pick lands there) into their inventory.
        val rolled = bots.act("Tester", BotAction.Command("treasure")).since
        val found = bots.heard("Tester", rolled, BotEvent.Chat::class.java) { it.text.startsWith("You found ") }.text
        val stacks = Regex("You found (\\d+) stacks of treasure.").matchEntire(found)?.groupValues?.get(1)?.toInt()
        assertTrue(stacks != null && stacks >= 1, "the player pool always gives something: $found")
    }

    @Test
    @Order(12)
    fun `a script hears a bot walk and be teleported`() {
        since = bots.act("Tester", BotAction.Command("listen")).since
        bots.said("Tester", since, "listening")
        val start = bots.state("Tester")
        since = bots.act("Tester", BotAction.WalkTo(start.x + 2, start.z)).since
        bots.heard("Tester", since, BotEvent.Chat::class.java) { it.text.startsWith("move ") }
        // Clear of the tower, on the same flat ground.
        since = bots.act("Tester", BotAction.Teleport(start.x, start.y, start.z - 6)).since
        bots.said("Tester", since, "teleport plugin")
        assertEquals(start.z - 6, bots.state("Tester").z, 0.01)
    }

    /** The hotbar slot holding [kind], selected. */
    private fun hold(kind: String) {
        val slot = bots.eventually("Tester") { state -> state.inventory.any { it.item == kind && it.slot < 9 } }
            .inventory.first { it.item == kind && it.slot < 9 }.slot
        bots.act("Tester", BotAction.SelectSlot(slot))
    }

    @Test
    @Order(13)
    fun `drops, a cancelled drop, swapping hands and picking an item up are heard`() {
        // The hotbar is full of what the steps before gave.
        editor.run("clear Tester")
        bots.eventually("Tester") { it.inventory.isEmpty() }
        for ((kind, count) in listOf("minecraft:stick" to 3, "minecraft:cobblestone" to 8, "minecraft:bread" to 2)) {
            editor.run("give Tester $kind $count")
            bots.eventually("Tester") { state -> state.inventory.any { it.item == kind } }
        }
        hold("minecraft:stick")
        since = bots.act("Tester", BotAction.Drop()).since
        bots.said("Tester", since, "no dropping sticks")
        assertEquals(3, bots.state("Tester").inventory.single { it.item == "minecraft:stick" }.count)

        // Straight down, so what's dropped lands at their feet and is picked up again once it may be.
        bots.act("Tester", BotAction.Look(0.0, 90.0))
        hold("minecraft:cobblestone")
        since = bots.act("Tester", BotAction.Drop()).since
        bots.said("Tester", since, "drop minecraft:cobblestone 1")
        bots.said("Tester", since, "pickup minecraft:cobblestone 1")
        bots.eventually("Tester") { state -> state.inventory.single { it.item == "minecraft:cobblestone" }.count == 8 }

        since = bots.act("Tester", BotAction.SwapHands).since
        bots.said("Tester", since, "swap")
        since = bots.act("Tester", BotAction.SwapHands).since
        bots.said("Tester", since, "swap")
    }

    @Test
    @Order(14)
    fun `placing and breaking a block, eating, clicking a block and a mob are heard`() {
        val at = bots.state("Tester")
        val (x, y, z) = Triple(Math.floor(at.x).toInt() + 1, Math.floor(at.y).toInt(), Math.floor(at.z).toInt())
        hold("minecraft:cobblestone")
        since = bots.act("Tester", BotAction.UseBlock(x, y - 1, z)).since
        bots.said("Tester", since, "place minecraft:cobblestone")
        bots.said("Tester", since, "click right minecraft:grass_block up")
        since = bots.act("Tester", BotAction.BreakBlock(x, y, z)).since
        // Cobblestone dropped by a bare hand gives nothing, as in the game.
        bots.said("Tester", since, "break minecraft:cobblestone 0")

        since = bots.act("Tester", BotAction.Command("hungry")).since
        bots.said("Tester", since, "hungry")
        hold("minecraft:bread")
        since = bots.act("Tester", BotAction.UseItem()).since
        bots.said("Tester", since, "use minecraft:bread main_hand")
        bots.said("Tester", since, "eat minecraft:bread")
        bots.eventually("Tester") { state -> state.food > 10 && state.inventory.single { it.item == "minecraft:bread" }.count == 1 }

        editor.run("summon minecraft:cow ${x + 1} $y $z {NoAI:1b}")
        val cow = bots.eventually("Tester") { state -> state.entities.any { it.type == "minecraft:cow" } }
            .entities.first { it.type == "minecraft:cow" }
        since = bots.act("Tester", BotAction.Interact(entity = cow.uuid)).since
        bots.said("Tester", since, "interact minecraft:cow main_hand")
        editor.run("kill @e[type=minecraft:cow]")
    }

    @Test
    @Order(15)
    fun `a death is heard and reworded, the inventory is kept, and the respawn is moved by a script`() {
        val before = bots.state("Tester").inventory.sumOf { it.count }
        since = bots.state("Tester").events
        editor.run("kill Tester")
        bots.heard("Tester", since, BotEvent.Death::class.java) { "Tester fell" in it.message }
        assertTrue(bots.eventually("Tester") { it.dead }.dead)
        since = bots.act("Tester", BotAction.Respawn).since
        bots.said("Tester", since, "respawn")
        val alive = bots.eventually("Tester") { !it.dead }
        assertEquals(3.5, alive.x, 0.01)
        assertEquals(3.5, alive.z, 0.01)
        assertEquals(before, alive.inventory.sumOf { it.count }, "the inventory was kept")
    }

    @Test
    @Order(16)
    fun `a package's database counts joins, written at a callback and read by a waiting task`() {
        // The greeter's join counted Tester once, in the project's own SQLite file; the command reads it back.
        since = bots.act("Tester", BotAction.Command("visits")).since
        bots.said("Tester", since, "You've joined 1 time(s).")
        assertTrue(server.folder.resolve("plugins/NetherForge/databases/showcase.db").toFile().isFile)
    }

    @Test
    @Order(17)
    fun `leaving is a quit everyone hears about, and a ban keeps a player out until it's lifted`() {
        since = bots.state("Tester").events
        assertTrue(editor.request(BotsExtension.leave, BotParams("Other")).first.ok)
        bots.said("Tester", since, "Other left the game")
        assertTrue("no bot named" in bots.refused("Other", BotAction.Jump))

        val (ruled, _) = editor.request(BotsExtension.join, BotJoinParams("Rule"))
        assertTrue(ruled.ok, ruled.error)
        since = bots.act("Tester", BotAction.Command("moderate Rule")).since
        bots.said("Tester", since, "moderate true true true true")
        // Kicked: the bot is gone (and with it its events), and coming back is refused with the ban's reason.
        assertTrue("no bot named" in bots.refused("Rule", BotAction.Jump))
        val (refusedJoin, _) = editor.request(BotsExtension.join, BotJoinParams("Rule"))
        assertFalse(refusedJoin.ok, "a banned player joined")
        assertTrue("Testing bans" in refusedJoin.error!!, refusedJoin.error)
        since = bots.act("Tester", BotAction.Command("liftban Rule")).since
        bots.said("Tester", since, "liftban true false")
        val (welcomeBack, _) = editor.request(BotsExtension.join, BotJoinParams("Rule"))
        assertTrue(welcomeBack.ok, welcomeBack.error)
        assertTrue(editor.request(BotsExtension.leave, BotParams("Rule")).first.ok)
        // Tester stays online: stopping the server must take it along cleanly.
    }
}
