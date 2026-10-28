package dev.netherforge.plugin.command
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `nf.commands.register` with typed arguments, run and completed through the
 * fake platform: parsing, defaults, usage messages, subcommands, permissions,
 * `players_only`, completion (built in and the script's own) and the checks
 * on a definition.
 */
class CommandTest {
    private fun module(source: String) = TestServer.example("showcase") + mapOf("modules/t/init.lua" to source.trimIndent())

    @Test
    fun `arguments arrive typed by their declaration`() {
        TestServer(
            module(
                """
                nf.commands.register("typed", {
                  arguments = {
                    { name = "count", type = "integer", min = 1, max = 10 },
                    { name = "scale", type = "number" },
                    { name = "flag", type = "boolean" },
                    { name = "mode", type = "choice", choices = { "fast", "slow" } },
                    { name = "who", type = "player" },
                    { name = "where", type = "position" },
                  },
                }, function(event)
                  local a = event.arguments
                  log(math.type(a.count), a.count, a.scale, a.flag, a.mode, a.who:name(), a.who == event.player, a.where)
                end)

                nf.commands.register("things", {
                  arguments = {
                    { name = "kind", type = "centity_kind" },
                    { name = "menu", type = "menu" },
                    { name = "dialog", type = "dialog" },
                    { name = "block", type = "block_state" },
                    { name = "item", type = "item" },
                    { name = "world", type = "world" },
                    { name = "everyone", type = "players" },
                    { name = "one", type = "entity" },
                    { name = "many", type = "entities" },
                    { name = "rest", type = "text" },
                  },
                }, function(event)
                  local a = event.arguments
                  log(a.kind, a.menu, a.dialog:id(), a.block, a.item.kind, a.world:name(), #a.everyone, a.one == event.player, #a.many, a.rest)
                end)

                nf.commands.register("poke", {
                  arguments = { { name = "target", type = "centity" }, { name = "at", type = "location" } },
                }, function(event)
                  log(event.arguments.target:kind(), event.arguments.at)
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.player("Sam")
            val commands = server.platform.commands
            assertEquals(emptyList(), commands.run(alex, "typed 3 2.5 true slow @s ~ ~1 5"))
            assertEquals("integer\t3\t2.5\ttrue\tslow\tAlex\ttrue\tvec3(0.5, 65, 5.5)", server.logs.last())

            commands.items["diamond[enchantment_glint_override=true]"] = ItemData(ItemDef("minecraft:diamond"), "opaque")
            commands.run(alex, "things tower shop welcome oak_stairs[facing=east] diamond world @a Alex @e the rest  of it")
            assertEquals(
                "tower\tshop\twelcome\tminecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]\t" +
                    "minecraft:diamond\tworld\t2\ttrue\t2\tthe rest  of it",
                server.logs.last()
            )
            // Item text with components is read by the server.
            commands.run(alex, "things tower shop welcome stone diamond[enchantment_glint_override=true] world Sam Sam Sam x")
            assertTrue(server.logs.last().startsWith("tower\tshop\twelcome\tminecraft:stone\tminecraft:diamond\tworld\t1\tfalse\t1"))

            val tower = server.runtime.session.centities.spawn("tower", Location("world", 10.0, 64.0, 10.0))!!
            alex.location = Location("world", 1.0, 64.0, 2.0, yaw = 90.0, pitch = 10.0)
            commands.run(alex, "poke ${tower.id} 1 2 3")
            assertEquals("tower\tlocation(\"world\", vec3(1.5, 2, 3.5), 90, 10)", server.logs.last())
            // ^ is local to where they look: one block forwards, facing west (yaw 90), level.
            alex.location = Location("world", 1.0, 64.0, 2.0, yaw = 90.0, pitch = 0.0)
            commands.run(alex, "poke ${tower.id} ^ ^ ^1")
            assertEquals("tower\tlocation(\"world\", vec3(0, 64, 2), 90, 0)", server.logs.last())
        }
    }

    @Test
    fun `optional arguments get their defaults, and mistakes get a usage message`() {
        TestServer(
            module(
                """
                nf.commands.register("give-coins", {
                  arguments = {
                    { name = "target", type = "player", default = false },
                    { name = "amount", type = "integer", min = 1, default = 1 },
                  },
                }, function(event)
                  local a = event.arguments
                  log((a.target or event.player):name(), a.amount)
                end)
                nf.commands.register("need", { arguments = { { name = "what", type = "word" } } }, function() end)
                nf.commands.register("go", { arguments = { { name = "where", type = "world" } } }, function() end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.player("Sam")
            val commands = server.platform.commands
            commands.run(alex, "give-coins")
            commands.run(alex, "give-coins Sam")
            commands.run(alex, "give-coins Sam 5")
            assertEquals(listOf("Alex\t1", "Sam\t1", "Sam\t5"), server.logs)

            // The server reads what's typed and answers a mistake there itself, before the runtime sees it.
            assertEquals(listOf("<red><amount> must be at least 1, not 0."), commands.run(alex, "give-coins Sam 0"))
            assertEquals(listOf("<red><amount> must be a whole number, not \"lots\"."), commands.run(alex, "give-coins Sam lots"))
            assertEquals(listOf("<red><target>: no player \"Bob\" is online."), commands.run(alex, "give-coins Bob"))
            assertEquals(listOf("<red>Too many arguments: \"3 4\"."), commands.run(alex, "give-coins Sam 2 3 4"))
            assertEquals(listOf("<red>Missing <what>."), commands.run(alex, "need"))
            // What only the runtime knows (the project's names) it answers with the usage.
            assertEquals(
                listOf("<red><where>: no world called \"moon\" (worlds: world, nether, hills, sky).", "<gray>Usage: /go <where>"),
                commands.run(alex, "go moon")
            )
            assertEquals(3, server.logs.size, "nothing ran for the mistakes")
        }
    }

    @Test
    fun `subcommands nest, with their own permissions, and players_only refuses the console`() {
        TestServer(
            module(
                """
                nf.commands.register("store", {
                  players_only = true,
                  subcommands = {
                    open = { handler = function(event) log("open", event.player:name()) end },
                    admin = {
                      permission = "shop.admin",
                      subcommands = {
                        give = {
                          arguments = {
                            { name = "who", type = "player" },
                            { name = "item", type = "choice", choices = { "apple", "bread" } },
                          },
                          handler = function(event)
                            log("give", event.arguments.who:name(), event.arguments.item, event.input, event.label)
                          end,
                        },
                      },
                    },
                  },
                })
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            assertEquals(listOf("<gray>Usage: /store open"), commands.run(alex, "store"), "a subcommand they can't use isn't listed")
            commands.run(alex, "store open")
            assertEquals(listOf("<red>You don't have permission to use /store admin."), commands.run(alex, "store admin give Alex apple"))
            assertEquals(listOf("<red>Unknown subcommand \"nope\"."), commands.run(alex, "store nope"))

            alex.permissions += "shop.admin"
            commands.run(alex, "store admin give Alex apple")
            assertEquals(listOf("open\tAlex", "give\tAlex\tapple\tadmin give Alex apple\tstore"), server.logs)
            assertEquals(
                listOf("<gray>Usage: /store admin …", "<gray>Usage: /store open"),
                commands.run(alex, "store")
            )
            assertEquals(listOf("<red>Missing <item>."), commands.run(alex, "store admin give Alex"))

            assertEquals(listOf("<red>Only players can use /store open."), commands.runAsConsole("store open"))
            assertEquals(2, server.logs.size)
        }
    }

    @Test
    fun `completion offers subcommands, what each type takes, and the script's own suggestions`() {
        TestServer(
            module(
                """
                nf.commands.register("store", {
                  subcommands = {
                    open = { handler = function() end },
                    admin = {
                      permission = "shop.admin",
                      arguments = {
                        { name = "who", type = "player" },
                        { name = "item", type = "choice", choices = { "apple", "bread" } },
                        { name = "where", type = "position", default = false },
                      },
                      handler = function() end,
                    },
                  },
                })
                nf.commands.register("warp", {
                  arguments = {
                    { name = "group", type = "choice", choices = { "home", "spawn" } },
                    {
                      name = "name",
                      type = "word",
                      complete = function(event, partial)
                        return { event.arguments.group .. "_a", event.arguments.group .. "_b", "other" }
                      end,
                    },
                  },
                }, function() end)
                nf.commands.register("bad", {
                  arguments = { { name = "x", type = "word", complete = function() return 42 end } },
                }, function() end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.player("Sam")
            val commands = server.platform.commands
            assertEquals(listOf("open"), commands.complete(alex, "store "))
            alex.permissions += "shop.admin"
            assertEquals(listOf("admin", "open"), commands.complete(alex, "store "))
            assertEquals(listOf("admin"), commands.complete(alex, "store a"))
            assertEquals(listOf("Alex", "Sam", "@p", "@r", "@s"), commands.complete(alex, "store admin "))
            assertEquals(listOf("Sam"), commands.complete(alex, "store admin s"))
            assertEquals(listOf("bread"), commands.complete(alex, "store admin Sam b"))
            assertEquals(listOf("~ ~ ~"), commands.complete(alex, "store admin Sam bread "))
            assertEquals(listOf("~ ~"), commands.complete(alex, "store admin Sam bread 1 "))
            assertEquals(emptyList(), commands.complete(alex, "store admin Bob "), "nothing after an argument that doesn't read")

            assertEquals(listOf("home", "spawn"), commands.complete(alex, "warp "))
            assertEquals(listOf("home_a", "home_b"), commands.complete(alex, "warp home h"))
            assertEquals(listOf("spawn_a", "spawn_b", "other"), commands.complete(alex, "warp spawn "))

            // A complete that answers nonsense is a script error like any other.
            assertEquals(emptyList(), commands.complete(alex, "bad "))
            assertTrue("must return a list of strings" in server.errors.single().message, server.errors.single().message)
        }
    }

    @Test
    fun `a definition with a mistake in it is an error that names it`() {
        TestServer(
            module(
                """
                local function try(definition, handler)
                  if handler == nil then
                    handler = function() end
                  end
                  local ok, problem = pcall(nf.commands.register, "x", definition, handler)
                  log(problem)
                end
                try({ descripton = "typo" })
                try({ arguments = { { name = "a", type = "string" } } })
                try({ arguments = { { name = "a", type = "word", typ = "x" } } })
                try({ arguments = { { type = "word" } } })
                try({ arguments = { { name = "a", type = "word", default = "x" }, { name = "b", type = "word" } } })
                try({ arguments = { { name = "a", type = "text" }, { name = "b", type = "word" } } })
                try({ arguments = { { name = "a", type = "choice" } } })
                try({ arguments = { { name = "a", type = "integer", default = "1" } } })
                try({ arguments = { { name = "a", type = "integer", min = 1, default = 0 } } })
                try({ arguments = { { name = "a", type = "word", min = 1 } } })
                try({ arguments = { { name = "a", type = "word" }, { name = "a", type = "word" } } })
                try({ subcommands = { add = { handle = function() end } } })
                try({}, false)
                log(nf.commands.register("x", { subcommands = { add = { handler = function() end } } }))
                """
            )
        ).use { server ->
            val types = ArgumentType.entries.joinToString(", ") { "\"${it.luaName}\"" }
            assertEquals(
                listOf(
                    "unknown field 'definition.descripton' (fields: aliases, arguments, description, permission, players_only, subcommands)",
                    "bad argument 'definition.arguments[1].type' (one of $types expected, got \"string\")",
                    "unknown field 'definition.arguments[1].typ' (fields: choices, complete, default, max, min, name, type)",
                    "bad argument 'definition.arguments[1].name' (string expected, got nil)",
                    "argument 'b' in 'definition' has no default but comes after 'a', which has one: optional arguments must come last",
                    "argument 'a' in 'definition' is text, which takes the rest of the line, so it must be the last",
                    "'definition.arguments[1]' is a choice, so it needs choices",
                    "bad argument 'definition.arguments[1].default' (whole number or false expected, got string)",
                    "'definition.arguments[1].default' (0) is outside min and max",
                    "'definition.arguments[1].min' is only for integer and number arguments",
                    "'definition': two arguments called 'a'",
                    "unknown field 'definition.subcommands.add.handle' (fields: arguments, description, handler, permission, players_only, subcommands)",
                    "bad argument 'handler' (function or nil expected, got boolean)",
                    "true"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a definition without a handler needs subcommands, and arguments need a handler`() {
        TestServer(
            module(
                """
                log(select(2, pcall(nf.commands.register, "x", { arguments = { { name = "a", type = "word" } } })))
                log(select(2, pcall(nf.commands.register, "y", {})))
                log(nf.commands.register("tp", function() end))
                """
            )
        ).use { server ->
            assertEquals(
                listOf(
                    "'definition' has arguments but no handler to give them to",
                    "'definition' has neither a handler nor subcommands",
                    "false"
                ),
                server.logs
            )
            assertFalse("x" in server.platform.commands.registered)
        }
    }

    @Test
    fun `every argument type can be declared, and left out with a default of false`() {
        val declared = ArgumentType.entries.map { it.luaName }

        // text takes the rest of the line, so it goes last.
        val arguments = declared.sortedBy { it == "text" }.joinToString(",\n") { name ->
            val choices = if (name == "choice") ", choices = { \"a\" }" else ""
            "{ name = \"a_$name\", type = \"$name\", default = false$choices }"
        }
        TestServer(module("log(nf.commands.register(\"all\", { arguments = { $arguments } }, function() end))")).use { server ->
            assertEquals(listOf("true"), server.logs)
            val alex = server.player("Alex")
            assertEquals(emptyList(), server.platform.commands.run(alex, "all"))
        }
    }
}
