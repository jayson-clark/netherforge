package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Problems
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project's advancements on a real server: the start-up datapack the
 * plugin builds before the worlds load (`examples/showcase`'s quest tree, and the
 * library's tab under its own namespace), granted and read by the example's
 * scripts for a player, and a change to one asking for the restart that
 * brings it in.
 */
class AdvancementScenario : Scenario("advancements") {
    override val server = PaperServer(onlineMode = true)

    // Not cached: a restart replaces the editor connection the bots talk through.
    private val bots get() = Bots(editor)

    /** The start-up datapack the plugin wrote into its folder, as the server loaded it. */
    private fun datapack(path: String) = server.folder.resolve("plugins/NetherForge/datapack").resolve(path).readText()

    private fun said(command: String, text: String) {
        val since = bots.act("Quester", BotAction.Command(command)).since
        bots.said("Quester", since, text)
    }

    @Test
    @Order(1)
    fun `the server starts with the project's advancements and its package's`() {
        val hunt = datapack("data/showcase/advancement/treasure_hunter.json")
        assertTrue("\"parent\": \"showcase:adventurer\"" in hunt, hunt)
        assertTrue("\"minecraft:item_model\": \"showcase:ui/ruby\"" in hunt, hunt)
        val collector = datapack("data/library/advancement/gem_collector.json")
        assertTrue("\"minecraft:item_model\": \"library:gems/gem\"" in collector, collector)
        // The welcome dialog is on the pause screen: the same datapack holds it, and the server loaded it (it would refuse to start otherwise).
        val welcome = datapack("data/showcase/dialog/welcome.json")
        assertTrue("\"id\": \"showcase:dialog/welcome/press/0\"" in welcome, welcome)
        assertTrue("showcase:welcome" in datapack("data/minecraft/tags/dialog/pause_screen_additions.json"))
        editor.run("difficulty peaceful")
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams("Quester"))
        assertTrue(joined.ok, joined.error)
        // Joining met the root's criterion (the quests module's grant).
        said("quests", "Quests\n✔ Adventurer\nTreasure Hunter: 0 of 3 rolls")
    }

    @Test
    @Order(2)
    fun `scripts meet a quest a criterion at a time, and the package's tab and the project's leaf under it`() {
        repeat(2) { bots.act("Quester", BotAction.Command("treasure")) }
        said("quests", "Quests\n✔ Adventurer\nTreasure Hunter: 2 of 3 rolls")
        said("treasure", "Treasure Hunter! Here's a diamond for the road.")
        said(
            "advs",
            "advs adventurer=true treasure_hunter=true diamonds=true library:gem_collector=false gem_hoarder=false"
        )
        said("gems", "gems true true")
        said(
            "advs",
            "advs adventurer=true treasure_hunter=true diamonds=true library:gem_collector=true gem_hoarder=true"
        )
    }

    @Test
    @Order(3)
    fun `saving an advancement as it is needs no restart, and changing one does`() {
        // The plugin builds the datapack again as it built it at start: the same bytes.
        assertFalse(editor.reload("advancements/treasure_hunter.json").restart)
        assertFalse(editor.reload("netherforge.json").restart)
        said("quests", "Quests\n✔ Adventurer\nTreasure Hunter: 3 of 3 rolls")

        val file = "advancements/treasure_hunter.json"
        project.write(file, project.read(file).replace("\"Treasure Hunter\"", "\"Treasure Seeker\""))
        val result = editor.reload(file)
        assertTrue(result.restart)
        assertTrue(result.resources.single().ok)
        // A loaded machine may report a slow script in a frame of its own: wait for the one that says restart.
        editor.next { it is Problems && it.problems.any { problem -> problem.code == "runtime.restart" } }
    }

    @Test
    @Order(4)
    fun `a restart brings the change in`() {
        restart()
        assertTrue("Treasure Seeker" in datapack("data/showcase/advancement/treasure_hunter.json"))
        assertFalse(editor.reload("advancements/treasure_hunter.json").restart)
        // Progress is the player's, kept in the world across the restart.
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams("Quester"))
        assertTrue(joined.ok, joined.error)
        said("quests", "Quests\n✔ Adventurer\nTreasure Hunter: 3 of 3 rolls")
    }
}
