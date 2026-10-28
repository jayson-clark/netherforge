package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.TeamLook
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Scoreboard teams, the player list and the line under name tags, against
 * the fake server: what scripts set reaches the main scoreboard under the
 * project's `nf.` names, a reload or a stop takes the project's teams away
 * (and only those), and the player list's changes outlive a rejoin where
 * the API says so.
 */
class TeamTest {
    @Test
    fun `a team is made on the main scoreboard under the project's name, with every option`() {
        val result = LuaChecks.run(
            """
            local red = nf.teams.create("red", {
              display_name = "<red>Red Team", prefix = "<red>[R] ", suffix = " <gray>*", color = "red",
              friendly_fire = false, see_invisible_teammates = false, nametags = "hide_for_other_teams",
              collision = "push_own_team",
            })
            check("name", red:name(), "red")
            check("exists", red:exists(), true)
            check("same handle", nf.teams.get("red"), red)
            check("display name", red:display_name(), "<red>Red Team")
            check("prefix", red:prefix(), "<red>[R] ")
            check("suffix", red:suffix(), " <gray>*")
            check("color", red:color(), "red")
            check("friendly fire", red:has_friendly_fire(), false)
            check("invisible", red:can_see_invisible_teammates(), false)
            check("nametags", red:nametags(), "hide_for_other_teams")
            check("collision", red:collision(), "push_own_team")

            local plain = nf.teams.create("blue.2")
            check("default display name", plain:display_name(), "blue.2")
            check("default prefix", plain:prefix(), "")
            check("default color", plain:color(), nil)
            check("default friendly fire", plain:has_friendly_fire(), true)
            check("default invisible", plain:can_see_invisible_teammates(), true)
            check("default nametags", plain:nametags(), "always")
            check("default collision", plain:collision(), "always")
            check("all", #nf.teams.all(), 2)
            check("all in order", nf.teams.all()[1], red)
            check("none", nf.teams.get("green"), nil)

            check("set prefix", plain:set_prefix("<blue>[B] "), true)
            check("set suffix", plain:set_suffix("!"), true)
            check("set display name", plain:set_display_name("Blue"), true)
            check("set color", plain:set_color("dark_blue") and plain:color(), "dark_blue")
            check("set friendly fire", plain:set_friendly_fire(false) and plain:has_friendly_fire(), false)
            check("set invisible", plain:set_can_see_invisible_teammates(false) and plain:can_see_invisible_teammates(), false)
            check("set nametags", plain:set_nametags("never") and plain:nametags(), "never")
            check("set collision", plain:set_collision("push_other_teams") and plain:collision(), "push_other_teams")
            check("clear color", plain:set_color() and plain:color(), nil)

            fails("taken", function() nf.teams.create("red") end, "already has a team called \"red\"")
            fails("bad name", function() nf.teams.create("red team") end, "letters, digits")
            fails("empty name", function() nf.teams.create("") end, "letters, digits")
            fails("bad key", function() nf.teams.create("x", { colour = "red" }) end, "colour")
            fails("bad color", function() nf.teams.create("x", { color = "pink" }) end, "pink")
            fails("bad nametags", function() plain:set_nametags("sometimes") end, "sometimes")
            check("nothing made by mistakes", #nf.teams.all(), 2)
            """,
            after = { server ->
                val teams = server.platform.teams.teams
                assertEquals(listOf("nf.red", "nf.blue.2"), teams.keys.toList())
                assertEquals(
                    TeamLook("<red>Red Team", "<red>[R] ", " <gray>*", "red", false, false, "hide_for_other_teams", "push_own_team"),
                    teams.getValue("nf.red").look
                )
                assertEquals(
                    TeamLook("Blue", "<blue>[B] ", "!", null, false, false, "never", "push_other_teams"),
                    teams.getValue("nf.blue.2").look
                )
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `players and entities join one team at a time, and say which project team they're in`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local world = nf.worlds.default()
            local pig = world:spawn_entity("minecraft:pig", vec3(2, 64, 2))
            local red = nf.teams.create("red")
            local blue = nf.teams.create("blue")
            -- In a team someone else made, which doesn't count as the project's.
            check("in vanilla's", alex:team(), nil)
            check("add player", red:add_member(alex), true)
            check("add pig", red:add_member(pig), true)
            check("has player", red:has_member(alex), true)
            check("has pig", red:has_member(pig), true)
            check("player's team", alex:team(), red)
            check("pig's team", pig:team(), red)
            local members = red:members()
            check("members", #members, 2)
            local seen = {}
            for _, member in ipairs(members) do seen[member] = true end
            check("alex among them", seen[alex], true)
            check("pig among them", seen[pig], true)

            check("move to blue", blue:add_member(alex), true)
            check("out of red", red:has_member(alex), false)
            check("now blue", alex:team(), blue)
            check("remove", blue:remove_member(alex), true)
            check("removed", alex:team(), nil)
            check("remove again", blue:remove_member(alex), false)

            -- Offline players stay members, by name, and can be added.
            local bo = nf.players.get("Bo")
            check("add offline", red:add_member(bo), true)
            check("offline's team", bo:team(), red)
            check("offline not among members here", #red:members(), 1)

            pig:remove()
            check("gone pig", pig:team(), nil)
            check("gone pig can't join", blue:add_member(pig), false)

            check("remove team", red:remove(), true)
            check("gone", red:exists(), false)
            check("gone add", red:add_member(alex), false)
            check("gone has", red:has_member(bo), false)
            check("gone members", #red:members(), 0)
            check("gone prefix", red:prefix(), nil)
            check("gone set prefix", red:set_prefix("x"), false)
            check("gone remove", red:remove(), false)
            check("gone lookup", nf.teams.get("red"), nil)
            check("offline in no team now", bo:team(), nil)
            """,
            setup = { server ->
                server.player("Alex")
                server.platform.players.known[UUID.nameUUIDFromBytes("Bo".toByteArray())] =
                    PlayerRef(UUID.nameUUIDFromBytes("Bo".toByteArray()), "Bo")
                server.platform.teams.create("vanilla", TeamLook("Vanilla"))
                server.platform.teams.addEntry("vanilla", "Alex")
            },
            after = { server ->
                assertEquals(listOf("nf.blue", "vanilla"), server.platform.teams.teams.keys.sorted())
                assertEquals(emptySet(), server.platform.teams.entries("vanilla"), "joining ours took Alex out of it")
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a reload takes the script's teams away and its body makes them again, leaving others' teams alone`() {
        val module = """
            local red = nf.teams.create("red", { prefix = "<red>VERSION " })
            log("teams " .. #nf.teams.all())
            nf.commands.register("join", function() red:add_member(nf.players.get("Alex")) end)
        """.trimIndent()
        TestServer(mapOf("modules/teams/init.lua" to module.replace("VERSION", "v1")), start = false).use { server ->
            val alex = server.player("Alex")
            val teams = server.platform.teams
            teams.create("vanilla", TeamLook("Vanilla"))
            teams.create("other_plugin.red", TeamLook("Theirs"))
            // A team of ours a crash left on the scoreboard, members and all.
            teams.create("nf.stale", TeamLook("Stale"))
            teams.addEntry("nf.stale", "Alex")
            teams.setBelowName("Alex", "stale")
            server.start()
            assertEquals(setOf("vanilla", "other_plugin.red", "nf.red"), teams.teams.keys, "the crash's leftovers went")
            assertEquals(emptyMap(), teams.belowNames)
            server.platform.commands.runConsole("join")
            assertEquals(setOf("Alex"), teams.entries("nf.red"))

            server.write("modules/teams/init.lua", module.replace("VERSION", "v2"))
            server.reload("modules/teams/init.lua")
            assertEquals(listOf("teams 1", "teams 1"), server.logs)
            assertEquals("<red>v2 ", teams.teams.getValue("nf.red").look.prefix)
            assertEquals(emptySet(), teams.entries("nf.red"), "a fresh team: the body adds members again")
            assertEquals(setOf("vanilla", "other_plugin.red", "nf.red"), teams.teams.keys)

            // A team made in a handler belongs to the module too.
            server.platform.commands.runConsole("join")
            server.runtime.disable()
            assertEquals(setOf("vanilla", "other_plugin.red"), teams.teams.keys, "stopping takes every project team away")
            assertEquals(null, teams.teamOf(alex.ref.name))
            server.start()
        }
    }

    @Test
    fun `the player list's name and order last until they leave, and being out of someone's list until it's undone`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local bo = nf.players.get("Bo")
            check("default tab name", alex:tab_name(), "Alex")
            check("set tab name", alex:set_tab_name("<gold>* Alex"), true)
            check("tab name", alex:tab_name(), "<gold>* Alex")
            check("default order", alex:tab_order(), 0)
            check("set order", alex:set_tab_order(5), true)
            check("order", alex:tab_order(), 5)
            fails("huge order", function() alex:set_tab_order(1 << 40) end, "32 bits")
            check("listed", alex:is_listed_for(bo), true)
            check("unlist", alex:set_listed_for(bo, false), true)
            check("unlisted", alex:is_listed_for(bo), false)
            check("still in own list", bo:is_listed_for(alex), true)
            check("below name", alex:set_below_name("<red>12 hearts"), true)
            check("read below name", alex:below_name(), "<red>12 hearts")
            check("none below bo", bo:below_name(), nil)

            local cy = nf.players.get("Cy")
            check("offline tab name", cy:tab_name(), nil)
            check("offline set", cy:set_tab_name("x"), false)
            check("offline listed", alex:is_listed_for(cy), false)
            check("offline set listed", alex:set_listed_for(cy, false), false)
            check("offline below", cy:set_below_name("x"), false)
            """,
            setup = { server ->
                server.player("Alex")
                server.player("Bo")
                val cy = UUID.nameUUIDFromBytes("Cy".toByteArray())
                server.platform.players.known[cy] = PlayerRef(cy, "Cy")
            },
            after = { server ->
                val list = server.platform.playerList
                val alex = server.platform.players.byId.values.first { it.ref.name == "Alex" }
                val bo = server.platform.players.byId.values.first { it.ref.name == "Bo" }
                assertEquals("<gold>* Alex", list.name(alex.ref.uuid))
                assertEquals(5, list.order(alex.ref.uuid))
                assertEquals(false, list.isListed(bo.ref.uuid, alex.ref.uuid))
                assertEquals(mapOf("Alex" to "<red>12 hearts"), server.platform.teams.belowNames)

                // Alex leaves and comes back: the server forgot it all; the runtime takes them out of Bo's list again.
                list.quit(alex.ref.uuid)
                server.platform.raise.playerQuit(GameEvent.PlayerQuit(alex.ref, null))
                assertEquals(emptyMap(), server.platform.teams.belowNames, "the line under their name goes when they leave")
                assertNull(server.runtime.session.teams.belowName(alex.ref.uuid))
                server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
                assertEquals(false, list.isListed(bo.ref.uuid, alex.ref.uuid))
                assertEquals("Alex", list.name(alex.ref.uuid), "their own name again")

                // Bo leaves and comes back: Alex is out of their list again.
                list.quit(bo.ref.uuid)
                server.platform.raise.playerQuit(GameEvent.PlayerQuit(bo.ref, null))
                server.platform.raise.playerJoin(GameEvent.PlayerJoin(bo.ref, false, null))
                assertEquals(false, list.isListed(bo.ref.uuid, alex.ref.uuid))
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `putting someone back in a list, and lines under name tags going when the project stops`() {
        val result = LuaChecks.run(
            """
            local alex, bo = nf.players.get("Alex"), nf.players.get("Bo")
            alex:set_listed_for(bo, false)
            check("back", alex:set_listed_for(bo, true), true)
            check("listed again", alex:is_listed_for(bo), true)
            alex:set_below_name("<gold>VIP")
            bo:set_below_name("<gray>Guest")
            check("take one away", bo:set_below_name() and bo:below_name(), nil)
            alex:set_tab_name("<gold>Alex")
            check("tab name back", alex:set_tab_name() and alex:tab_name(), "Alex")
            """,
            setup = { server ->
                server.player("Alex")
                server.player("Bo")
            },
            after = { server ->
                val (alex, bo) = listOf("Alex", "Bo").map { name ->
                    server.platform.players.byId.values.first { it.ref.name == name }.ref.uuid
                }
                assertEquals(true, server.platform.playerList.isListed(bo, alex))
                assertEquals(mapOf("Alex" to "<gold>VIP"), server.platform.teams.belowNames)
                server.runtime.disable()
                assertEquals(emptyMap(), server.platform.teams.belowNames)
                server.start()
            }
        )
        assertEquals(listOf("done"), result)
    }
}
