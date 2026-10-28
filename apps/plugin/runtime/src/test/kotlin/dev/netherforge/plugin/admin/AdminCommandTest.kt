package dev.netherforge.plugin.admin
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/nf`, run as a player. */
class AdminCommandTest {

    @Test
    fun `spawn, list, find, tp and kill`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            assertTrue(commands.run(alex, "nf spawn tower").single().startsWith("<green>Spawned tower"))
            commands.run(alex, "netherforge spawn lamp")
            assertTrue(commands.run(alex, "nf spawn nope").single().startsWith("<red>no centity \"nope\""))

            val listed = commands.run(alex, "nf list")
            assertEquals("<gold>2 spawned:", listed.first())
            assertEquals(3, listed.size)
            assertEquals(2, commands.run(alex, "nf find lamp").size)

            val tower = server.runtime.session.centities.all().first { it.centity == "tower" }
            alex.location = alex.location.copy(x = 50.0)
            commands.run(alex, "nf tp ${tower.id.toString().take(8)}")
            assertEquals(tower.anchor.x, alex.location.x)

            assertEquals(listOf("<green>Removed 1."), commands.run(alex, "nf kill tower"))
            assertEquals(listOf("<green>Removed 1."), commands.run(alex, "nf kill all"))
            assertTrue(server.runtime.session.centities.all().isEmpty())
        }
    }

    @Test
    fun `reload reports each resource and modules shows what's running`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            assertEquals(listOf("<green>centity:tower: reloaded"), commands.run(alex, "nf reload centities/tower/script.lua"))
            assertEquals(listOf("<green>showcase: reloaded"), commands.run(alex, "nf reload"))
            // Every booth of the expo has a module of its own (`modules/(booths)/<id>_booth`), with no commands.
            val modules = commands.run(alex, "nf modules")
            assertEquals(32, modules.count { "_booth <white>running" in it })
            assertEquals(
                listOf(
                    "<green>altar <white>running",
                    "<green>companion <white>running <gray>/companion",
                    "<green>expo <white>running <gray>/tour /expo",
                    "<green>flyby <white>running <gray>/flyby",
                    "<green>greeter <white>running <gray>/tower /shop /visits",
                    // The library's, which examples/showcase depends on, run beside its own.
                    "<green>library:greetings <white>running",
                    "<green>library:phrases <white>running",
                    "<green>milestones <white>running <gray>/milestones",
                    "<green>quests <white>running <gray>/quests",
                    "<green>ranks <white>running <gray>/perm",
                    "<green>rewards <white>running <gray>/treasure",
                    "<green>seasons <white>running <gray>/wisps"
                ),
                modules.filterNot { "_booth <white>running" in it }
            )
        }
    }

    @Test
    fun `completion offers subcommands, centities and players`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            // Completion, like the server's, only offers what they may use.
            alex.permissions += NetherForgeRuntime.PERMISSION
            assertEquals(listOf("spawn"), commands.complete(alex, "nf sp"))
            assertEquals(
                listOf(
                    "bouncer", "crate", "crystal", "dart", "display_item", "door", "dummy", "kiosk", "lamp", "owl", "placard", "pool",
                    "runner", "shrine_keeper", "tortoise", "tower", "wisp"
                ),
                commands.complete(alex, "nf spawn ")
            )
            assertEquals(listOf("Alex"), commands.complete(alex, "nf spawn tower A"))
        }
    }

    private val stored = mapOf(
        "centities/c/centity.json" to TestServer.scriptedCentity(),
        "centities/c/script.lua" to "this:data().hp = 10",
        "modules/m/init.lua" to """
            nf.data("shop").open = true
            nf.commands.register("visit", function(event) event.player:data().visits = 3 end)
        """
    )

    @Test
    fun `data shows what the store keeps, as it is now`() {
        TestServer(stored).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            val instance = server.runtime.session.centities.spawn("c", Location("world", 1.0, 64.0, 2.0))!!
            commands.run(alex, "visit")

            val sizes = commands.run(alex, "nf data")
            assertTrue(sizes.first().startsWith("<gold>netherforge.db"), sizes.first())
            // Saved tables are saved first, so what's shown is now, not the last autosave.
            assertTrue("<yellow>named_data <white>1 <gray>(test 1)" in sizes, "$sizes")
            assertTrue("<yellow>instances <white>1" in sizes, "$sizes")
            assertTrue("<yellow>player_data <white>1" in sizes, "$sizes")

            val rows = commands.run(alex, "nf data instances")
            assertEquals("<gold>instances <gray>(1 row)", rows.first())
            assertTrue("id=<white>${instance.id}" in rows[1] && "centity=<white>test:c" in rows[1], rows[1])

            assertEquals(
                listOf("<gold>player Alex <gray>(12 bytes)", "<white>{", "<white>    \"visits\": 3", "<white>}"),
                commands.run(alex, "nf data show player Alex")
            )
            assertEquals("<white>    \"hp\": 10", commands.run(alex, "nf data show centity ${instance.id.toString().take(8)}")[2])
            assertEquals("<white>    \"open\": true", commands.run(alex, "nf data show named test:shop")[2])
            assertEquals(listOf("<gray>Nothing is saved for nf.data(\"nope\") of test."), commands.run(alex, "nf data show named nope"))
            assertEquals(listOf("<red>there's no table \"nope\" in the store"), commands.run(alex, "nf data nope"))

            alex.permissions += NetherForgeRuntime.PERMISSION
            assertEquals(listOf("show"), commands.complete(alex, "nf data sh"))
            assertEquals(listOf("player"), commands.complete(alex, "nf data show pl"))
        }
    }

    @Test
    fun `data export writes every table as JSON, saved tables as the JSON they are`() {
        TestServer(stored).use { server ->
            val alex = server.player("Alex")
            server.runtime.session.centities.spawn("c", Location("world", 1.0, 64.0, 2.0))!!
            assertEquals(listOf("<gold>Exporting the store…"), server.platform.commands.run(alex, "nf data export"))
            server.tick()
            val said = alex.messages.last()
            assertTrue(said.startsWith("<green>Exported the store to "), said)
            val export = Json.parseToJsonElement(Path.of(said.removePrefix("<green>Exported the store to ")).readText()).jsonObject
            val tables = export.getValue("tables").jsonObject
            assertEquals(
                Json.parseToJsonElement("""[{"namespace":"test","name":"shop","value":{"open":true}}]"""),
                tables.getValue("named_data")
            )
            assertEquals("test:c", tables.getValue("instances").jsonArray.single().jsonObject.getValue("centity").jsonPrimitive.content)
            assertEquals(1.0, tables.getValue("instances").jsonArray.single().jsonObject.getValue("x").jsonPrimitive.double)
            assertTrue("migrations" in tables)
        }
    }
}
