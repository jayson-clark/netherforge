package dev.netherforge.plugin.events
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.WatchedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server events: payloads, cancelling, writable fields, and each on `Player`. */
class ServerEventTest {
    private fun module(source: String) = mapOf("modules/t/init.lua" to source)

    /** A server running [source] with Alex already online, so the module can find him as it loads. */
    private fun server(source: String): TestServer = TestServer(module(source), start = false).also {
        it.player("Alex")
        it.start()
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    private fun item(kind: String, count: Int? = null) = ItemData(ItemDef(kind, count = count))

    private val stone = BlockRef("world", 1, 63, 2, "minecraft:stone", "minecraft:stone")

    @Test
    fun `chat can be cancelled, reworded and reformatted, and is only waited for while someone listens`() {
        server(
            """
                local alex = nf.players.get("Alex")
                local listening
                nf.commands.register("listen", function()
                  listening = nf.on("player_chat", function(event)
                    log(event.name, event.player:name(), event.message, event.format, tostring(event.cancelled))
                    if event.message == "secret" then event:cancel() return end
                    event.message = event.message:upper()
                    event.format = "<gray><player>: <message>"
                    log(pcall(function() event.format = nil end))
                  end)
                  alex:on("chat", function(event) log("alex first") end)
                end)
                nf.commands.register("stop", function() listening:cancel() end)
                """
        ).use { server ->
            val alex = server.alex
            val players = server.platform.players
            assertTrue(players.chat(alex, "before"), "nobody listens: it goes out as it is")
            assertFalse(WatchedEvent.PLAYER_CHAT in server.platform.watched)
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.PLAYER_CHAT in server.platform.watched)
            assertTrue(players.chat(alex, "hello"))
            assertFalse(players.chat(alex, "secret"), "a handler cancelled it")
            server.platform.commands.runConsole("stop")
            assertTrue(WatchedEvent.PLAYER_CHAT in server.platform.watched, "the player's own handler still listens")
            assertEquals(listOf("\\<Alex> before", "<gray>Alex: HELLO"), players.chat)
            assertEquals(
                listOf(
                    "alex first",
                    "player_chat\tAlex\thello\t$DEFAULT_CHAT_FORMAT\tfalse",
                    "false\tmodules/t/init.lua:9: bad value for event.format (string expected, got nil)",
                    "alex first",
                    "player_chat\tAlex\tsecret\t$DEFAULT_CHAT_FORMAT\tfalse"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `an interaction says which hand, face and item, and using an item can be stopped on its own`() {
        server(
            """
                nf.on("player_interact", function(event)
                  log("interact", event.click, event.hand, tostring(event.face), event.item and event.item.kind or "empty",
                    event.block and event.block:kind() or "air")
                  if event.hand == "off_hand" then event:cancel() end
                end)
                nf.on("player_use_item", function(event)
                  log("use", event.item.kind, event.item.count, event.hand)
                  event:cancel()
                end)
                """
        ).use { server ->
            val alex = server.alex
            val runtime = server.runtime
            assertFalse(runtime.events.playerInteract(alex.ref, ClickButton.RIGHT, stone, "up", item("minecraft:bread", 3), "main_hand"))
            assertTrue(runtime.events.playerInteract(alex.ref, ClickButton.RIGHT, null, null, null, "off_hand"))
            assertTrue(runtime.events.playerUseItem(alex.ref, item("minecraft:bread", 3), "main_hand"))
            assertEquals(
                listOf(
                    "interact\tright\tmain_hand\tup\tminecraft:bread\tminecraft:stone",
                    "interact\tright\toff_hand\tnil\tempty\tair",
                    "use\tminecraft:bread\t3\tmain_hand"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `moves are watched only while someone listens, block by block, and can be undone`() {
        server(
            """
                local alex = nf.players.get("Alex")
                local subscription
                nf.commands.register("listen", function()
                  subscription = alex:on("move", function(event)
                    log("move", event.from.world:name(), tostring(event.from.position), tostring(event.to.position), event.to.yaw)
                    if event.to.position.x > 5 then event:cancel() end
                  end)
                end)
                nf.commands.register("stop", function() subscription:cancel() end)
                """
        ).use { server ->
            val alex = server.alex
            val players = server.platform.players
            assertTrue(players.move(alex, Location("world", 1.5, 64.0, 0.5)))
            assertFalse(WatchedEvent.PLAYER_MOVE in server.platform.watched)
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.PLAYER_MOVE in server.platform.watched)
            assertTrue(players.move(alex, Location("world", 1.9, 64.0, 0.5, yaw = 45.0)), "the same block: not heard")
            assertTrue(players.move(alex, Location("world", 2.5, 64.0, 0.5, yaw = 90.0)))
            assertFalse(players.move(alex, Location("world", 6.5, 64.0, 0.5)), "a handler cancelled it")
            assertEquals(2.5, alex.location.x, "they stayed where they were")
            server.platform.commands.runConsole("stop")
            assertFalse(WatchedEvent.PLAYER_MOVE in server.platform.watched)
            assertEquals(
                listOf(
                    "move\tworld\tvec3(1.9, 64, 0.5)\tvec3(2.5, 64, 0.5)\t90.0",
                    "move\tworld\tvec3(2.5, 64, 0.5)\tvec3(6.5, 64, 0.5)\t0.0"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a teleport can be cancelled or sent elsewhere, and changing world is heard`() {
        server(
            """
                local alex = nf.players.get("Alex")
                local nether = nf.worlds.get("nether")
                nf.on("player_teleport", function(event)
                  log("teleport", event.cause, tostring(event.from.position), tostring(event.to.position))
                  if event.to.position.y > 200 then event:cancel() end
                  if event.to.position.x == 10 then event.to = nether:location(vec3(1, 70, 1)) end
                  if event.to.position.x == 20 then event.to = event.to:with_position(vec3(21, 64, 0)) end
                  log(pcall(function() event.to = vec3(0, 0, 0) end))
                  log(pcall(function() event.from = event.to end))
                end)
                alex:on("change_world", function(event)
                  log("world", event.from:name(), event.to:name(), tostring(event.player == alex))
                end)
                nf.commands.register("go", { arguments = { { name = "x", type = "number" } } }, function(event)
                  log("went", tostring(alex:teleport(vec3(event.arguments.x, 64, 0))))
                end)
                nf.commands.register("high", function() log("went", tostring(alex:teleport(vec3(0, 300, 0)))) end)
                """
        ).use { server ->
            val alex = server.alex
            server.platform.commands.runConsole("go 10")
            assertEquals(Location("nether", 1.0, 70.0, 1.0), alex.location, "the handler sent them to the nether, facing kept")
            server.platform.commands.runConsole("high")
            assertEquals("nether", alex.location.world)
            alex.location = Location("world", 0.0, 64.0, 0.0, yaw = 30.0)
            server.platform.commands.runConsole("go 20")
            assertEquals(Location("world", 21.0, 64.0, 0.0, yaw = 30.0), alex.location)
            val bad = "false\tmodules/t/init.lua:8: bad value for event.to (Location expected, got table)"
            val readOnly = "false\tmodules/t/init.lua:9: event.from can't be assigned on a player_teleport event (writable: to)"
            assertEquals(
                listOf(
                    "teleport\tplugin\tvec3(0.5, 64, 0.5)\tvec3(10, 64, 0)",
                    bad,
                    readOnly,
                    "world\tworld\tnether\ttrue",
                    "went\ttrue",
                    "teleport\tplugin\tvec3(1, 70, 1)\tvec3(0, 300, 0)",
                    bad,
                    readOnly,
                    "went\tfalse",
                    "teleport\tplugin\tvec3(0, 64, 0)\tvec3(20, 64, 0)",
                    bad,
                    readOnly,
                    "went\ttrue"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a death's message, inventory and drops can be changed, then everyone's entity_death hears it`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("death", function(event)
                  log("death", event.cause, tostring(event.killer), tostring(event.message), tostring(event.keep_inventory), #event.drops,
                    event.drops[1].kind)
                  event.message = "<red>gone"
                  event.keep_inventory = true
                end)
                nf.on("entity_death", function(event)
                  log("entity_death", tostring(event.entity == alex), #event.drops, event.experience)
                  table.insert(event.drops, { kind = "minecraft:paper" })
                  event.experience = 0
                end)
                nf.commands.register("die", function() alex:damage(100) end)
                """
        ).use { server ->
            val alex = server.alex
            alex.inventorySlots[0] = item("minecraft:diamond", 2)
            // A player drops seven points of experience a level, as the game does.
            alex.numbers[EntityNumber.LEVEL] = 3.0
            server.platform.commands.runConsole("die")
            val entities = server.platform.worldEntities
            assertEquals(alex.id to 0, entities.deaths.last())
            assertEquals(listOf(item("minecraft:paper")), entities.drops.last(), "keeping the inventory emptied the drops; nf added paper")
            assertEquals("<red>gone" to true, entities.playerDeaths.last())
            assertEquals(item("minecraft:diamond", 2), alex.inventorySlots[0], "they kept it")
            assertEquals(
                listOf("death\tcustom\tnil\tAlex died\tfalse\t1\tminecraft:diamond", "entity_death\ttrue\t0\t21"),
                server.logs
            )
        }
    }

    @Test
    fun `a death's drops can be replaced, and untouched ones come back unchanged`() {
        server(
            """
                nf.on("player_death", function(event)
                  if event.player:name() == "Steve" then
                    event.drops = { { kind = "minecraft:bread", count = 4 } }
                    event.message = nil
                  end
                end)
                """
        ).use { server ->
            val alex = server.alex
            val steve = server.player("Steve")
            val carried = listOf(item("minecraft:diamond", 2), item("minecraft:gold_ingot", 5))
            val left = server.runtime.events.playerDied(alex.ref, steve.id, "entity_attack", "Alex died", false, carried, 7)
            assertNull(left.drops, "nobody changed them")
            assertEquals("Alex died", left.message)
            val changed = server.runtime.events.playerDied(steve.ref, null, "fall", "Steve fell", false, carried, 7)
            assertEquals(listOf(item("minecraft:bread", 4)), changed.drops)
            assertNull(changed.message)
            assertFalse(changed.keepInventory)
        }
    }

    @Test
    fun `where a player respawns can be changed`() {
        server(
            """
                nf.on("player_respawn", function(event)
                  log("respawn", tostring(event.location.position), event.location.world:name())
                  event.location = nf.worlds.default():location(vec3(5, 70, 5), 90, 0)
                end)
                """
        ).use { server ->
            val alex = server.alex
            val spawn = Location("world", 0.5, 64.0, 0.5)
            val respawn = GameEvent.PlayerRespawn(alex.ref, spawn)
            server.platform.raise.playerRespawn(respawn)
            assertEquals(Location("world", 5.0, 70.0, 5.0, 90.0, 0.0), respawn.location)
            assertEquals(listOf("respawn\tvec3(0.5, 64, 0.5)\tworld"), server.logs)
        }
    }

    @Test
    fun `dropping, picking up, eating, swapping hands, sneaking and commands are heard and the cancellable ones stopped`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("drop_item", function(event) log("drop", event.item.kind) event:cancel() end)
                nf.on("player_pickup_item", function(event)
                  log("pickup", event.item.kind, event.item.count, event.entity:kind())
                  event:cancel()
                end)
                alex:on("consume_item", function(event) log("consume", event.item.kind) event:cancel() end)
                alex:on("swap_hands", function(event)
                  log("swap", event.player:name(), event.item.kind, tostring(event.off_hand_item), tostring(event.in_inventory))
                  event:cancel()
                end)
                nf.on("player_sneak", function(event) log("sneak", tostring(event.sneaking)) end)
                nf.on("player_command", function(event)
                  log("command", event.input)
                  if event.input:find("^stop") then event:cancel() end
                end)
                """
        ).use { server ->
            val alex = server.alex
            val runtime = server.runtime
            val bread = item("minecraft:bread", 3)
            val dropped = server.platform.worldEntities.spawnItem("world", dev.netherforge.format.math.Vec3(0.0, 64.0, 0.0), bread)!!
            assertTrue(runtime.events.playerDropItem(alex.ref, bread))
            assertTrue(runtime.events.playerPickupItem(alex.ref, bread, dropped))
            assertTrue(runtime.events.playerConsumeItem(alex.ref, bread))
            assertTrue(server.platform.raise.playerSwapHands(GameEvent.PlayerSwapHands(alex.ref, bread, null, false)))
            server.platform.raise.playerSneak(GameEvent.PlayerSneak(alex.ref, true))
            assertFalse(server.platform.raise.playerCommand(GameEvent.PlayerCommand(alex.ref, "give @s minecraft:diamond")))
            assertTrue(server.platform.raise.playerCommand(GameEvent.PlayerCommand(alex.ref, "stop")))
            assertEquals(
                listOf(
                    "drop\tminecraft:bread",
                    "pickup\tminecraft:bread\t3\tminecraft:item",
                    "consume\tminecraft:bread",
                    "swap\tAlex\tminecraft:bread\tnil\tfalse",
                    "sneak\ttrue",
                    "command\tgive @s minecraft:diamond",
                    "command\tstop"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a broken block's drops and experience are writable, checked as they're assigned`() {
        server(
            """
                local world = nf.worlds.default()
                world:on("block_break", function(event)
                  log("world", event.state, #event.drops, event.experience)
                  log(pcall(function() event.drops = { { kind = "minecraft:bread", colour = "red" } } end))
                  log(pcall(function() event.drops = "bread" end))
                end)
                nf.on("block_break", function(event)
                  if event.block:position().x == 1 then
                    event.drops = { { kind = "minecraft:diamond", count = 2 } }
                    event.experience = 10
                  elseif event.block:position().x == 2 then
                    event.drops[2] = { kind = "minecraft:nothing_like_it" }
                  end
                end)
                """
        ).use { server ->
            val alex = server.alex
            val gold = listOf(item("minecraft:gold_ingot"))
            val changed = server.runtime.events.blockBreak(alex.ref, stone, { gold }, 3)
            assertEquals(listOf(item("minecraft:diamond", 2)), changed?.drops)
            assertEquals(10, changed?.experience)
            val untouched = server.runtime.events.blockBreak(alex.ref, stone.copy(x = 3), { gold }, 3)
            assertNull(untouched?.drops, "nobody changed them")
            assertEquals(3, untouched?.experience)
            val broken = server.runtime.events.blockBreak(alex.ref, stone.copy(x = 2), { gold }, 3)
            assertNull(broken?.drops, "an item put in place that the server doesn't have leaves the drops as they were")
            assertTrue(
                server.platform.log.lines.any { "event.drops[2]" in it && "block_break" in it },
                "and says so: ${server.platform.log.lines}"
            )
            val world = "world\tminecraft:stone\t1\t3"
            val badItem = "false\tmodules/t/init.lua:4: event.drops[1]: items have no field \"colour\""
            assertTrue(server.logs.first().startsWith(world), server.logs.toString())
            assertTrue(server.logs[1].startsWith(badItem), server.logs.toString())
            assertEquals(
                "false\tmodules/t/init.lua:5: bad value for event.drops (list of Item expected, got string)",
                server.logs[2]
            )
        }
    }

    @Test
    fun `a placed block says what it was placed against`() {
        server(
            """
                nf.worlds.default():on("block_place", function(event)
                  log("place", event.state, tostring(event.against:position()))
                  event:cancel()
                end)
                """
        ).use { server ->
            val alex = server.alex
            val placed = BlockRef("world", 1, 64, 2, "minecraft:glass", "minecraft:glass")
            assertTrue(server.platform.raise.blockPlace(GameEvent.BlockPlace(alex.ref, placed, placed.state, stone)))
            assertEquals(listOf("place\tminecraft:glass\tvec3(1, 63, 2)"), server.logs)
        }
    }

    @Test
    fun `an entity's drops are writable on it and on nf`() {
        server(
            """
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                pig:on("death", function(event)
                  log("pig", #event.drops, event.drops[1].kind)
                  event.drops = {}
                end)
                nf.on("entity_death", function(event)
                  log("nf", #event.drops)
                  event.drops = { { kind = "minecraft:diamond" } }
                end)
                nf.commands.register("kill", function() pig:damage(100) end)
                """
        ).use { server ->
            server.platform.worldEntities.mobs.values.single().loot += item("minecraft:bread", 2)
            server.platform.commands.runConsole("kill")
            assertEquals(listOf(item("minecraft:diamond")), server.platform.worldEntities.drops.last())
            assertEquals(listOf("pig\t1\tminecraft:bread", "nf\t0"), server.logs)
        }
    }
}
