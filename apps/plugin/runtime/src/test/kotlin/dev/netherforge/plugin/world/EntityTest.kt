package dev.netherforge.plugin.world
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `Entity` and what `Player` adds to it, against the fake server: reading
 * and changing entities, their saved tables, their events, and the methods
 * that answer nil or false on entities they don't apply to.
 */
class EntityTest {
    @Test
    fun `an entity's handle is the class it is, with the methods that class has`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local pig = world:spawn_entity("minecraft:pig", world:location(vec3(3, 64, 4), 90, 0), {
              custom_name = "<pink>Wilbur", tags = { "farm" }, velocity = vec3(0, 0.5, 0),
            })
            check("kind", pig:kind(), "minecraft:pig")
            check("exists", pig:exists(), true)
            check("not a player", pig:is_player(), false)
            check("living", pig:is_living(), true)
            check("a mob", pig:is_mob(), true)
            check("a Mob handle", getmetatable(pig), "Mob")
            check("same handle", world:entities({ kind = "pig" })[1], pig)
            check("id", nf.json.encode({ pig:id() }):len() > 30, true)
            check("position", pig:position(), vec3(3, 64, 4))
            check("world", pig:world(), world)
            check("yaw", pig:yaw(), 90)
            check("eyes", pig:eye_position().y > 64, true)
            check("velocity", pig:velocity(), vec3(0, 0.5, 0))
            check("add velocity", pig:add_velocity(vec3(1, 0, 0)), true)
            check("added", pig:velocity(), vec3(1, 0.5, 0))
            check("name", pig:name(), "<pink>Wilbur")
            check("custom name", pig:custom_name(), "<pink>Wilbur")
            check("unname", pig:set_custom_name(), true)
            check("kind's name", pig:name(), "<lang:entity.minecraft.pig>")
            check("tags", table.concat(pig:tags(), ","), "farm")
            check("add tag", pig:add_tag("pet"), true)
            check("again", pig:add_tag("pet"), false)
            check("has tag", pig:has_tag("pet"), true)
            check("sorted", table.concat(pig:tags(), ","), "farm,pet")
            check("remove tag", pig:remove_tag("farm"), true)
            fails("bad tag", function() pig:add_tag("no spaces") end, "isn't a tag")
            check("glow", pig:set_glowing(true), true)
            check("glowing", pig:is_glowing(), true)
            check("silent", pig:set_silent(true) and pig:is_silent(), true)
            check("gravity", pig:set_gravity(false) and not pig:has_gravity(), true)
            check("invulnerable", pig:set_invulnerable(true) and pig:is_invulnerable(), true)
            check("visible", pig:is_visible(), true)
            check("look at", pig:look_at(vec3(3, 64, 10)), true)
            check("looks south", math.abs(pig:yaw()) < 0.001, true)
            check("set pitch", pig:set_pitch(30), true)
            check("pitch", pig:pitch(), 30)
            check("teleport", pig:teleport(vec3(10, 64, 10)), true)
            check("moved", pig:position(), vec3(10, 64, 10))
            check("kept facing", pig:pitch(), 30)
            check("health", pig:health(), 10)
            check("max health", pig:max_health(), 10)
            check("set health", pig:set_health(5), true)
            fails("too healthy", function() pig:set_health(30) end, "health must be from 0 to max_health()")
            check("heal", pig:heal(100), true)
            check("healed to max", pig:health(), 10)
            fails("unknown effect", function() pig:add_effect("minecraft:flight", 20) end, "no status effect")
            check("effect", pig:add_effect("speed", 100, { amplifier = 1 }), true)
            check("has effect", pig:has_effect("minecraft:speed"), true)
            local effect = pig:effects()[1]
            check("effect table", effect.effect .. " " .. effect.ticks .. " " .. effect.amplifier .. " " .. tostring(effect.particles), "minecraft:speed 100 1 true")
            check("remove effect", pig:remove_effect("speed"), true)
            check("equipment", pig:set_equipment("head", { kind = "minecraft:diamond" }), true)
            check("worn", pig:equipment("head").kind, "minecraft:diamond")
            check("ai", pig:has_ai(), true)
            check("no ai", pig:set_ai(false) and not pig:has_ai(), true)
            check("no dropped item's methods", pig.item, nil)
            check("none at all", pig.set_pickup_delay, nil)
            check("no inventory", pig:inventory(), nil)

            local cart = world:spawn_entity("minecraft:chest_minecart", vec3(0, 64, 0))
            check("an Entity handle", getmetatable(cart), "Entity")
            check("not living", cart:is_living(), false)
            check("not a mob", cart:is_mob(), false)
            check("no health", cart.health, nil)
            check("no damage", cart.damage, nil)
            check("no effects", cart.effects, nil)
            check("no ai", cart.set_ai, nil)
            fails("a mob's method on it", function() world:spawn_entity("pig", vec3(0, 64, 0)).set_ai(cart, true) end, "call Mob methods with ':' on a Mob")
            check("cart inventory", cart:inventory():kind(), "chest")
            check("cart holder", cart:inventory():holder(), cart)
            check("ride", cart:add_passenger(pig), true)
            check("passengers", cart:passengers()[1], pig)
            check("vehicle", pig:vehicle(), cart)
            check("get off", cart:remove_passenger(pig), true)
            check("no vehicle", pig:vehicle(), nil)

            local bread = world:spawn_item(vec3(1, 64, 1), { kind = "minecraft:bread", count = 3 })
            check("a DroppedItem handle", getmetatable(bread), "DroppedItem")
            check("item", bread:item().count, 3)
            check("set item", bread:set_item({ kind = "minecraft:diamond" }), true)
            check("pickup", bread:set_pickup_delay(40), true)
            check("pickup delay", bread:pickup_delay(), 40)
            fails("never and more", function() bread:set_pickup_delay(40000) end, "from 0 to 32767")

            fails("unknown kind", function() world:spawn_entity("minecraft:dragonfly", vec3(0, 64, 0)) end, "isn't an entity this server can spawn")
            fails("bad data", function() world:spawn_entity("pig", vec3(0, 64, 0), { data = { f = function() end } }) end, "a function can't be saved")
            check("remove", pig:remove(), true)
            check("gone", pig:exists(), false)
            check("nil once gone", pig:position(), nil)
            check("false once gone", pig:set_glowing(true), false)
            check("no tags once gone", #pig:tags(), 0)
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a player is a living entity, not a mob`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            check("is a player", alex:is_player(), true)
            check("kind", alex:kind(), "minecraft:player")
            check("a Player handle", getmetatable(alex), "Player")
            check("living", alex:is_living() and alex:health(), 20)
            check("not a mob", alex:is_mob(), false)
            check("can't be removed", alex:remove(), false)
            check("still here", alex:exists(), true)
            check("no ai", alex.set_ai, nil)
            check("no target", alex.target, nil)
            check("no item", alex.item, nil)
            check("listed as a player", nf.worlds.default():entities({ kind = "player" })[1], alex)
            check("filter living", #nf.worlds.default():entities({ living = false }), 0)
            local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 2))
            check("ride a pig", pig:add_passenger(alex), true)
            check("rider is the player", pig:passengers()[1], alex)
            fails("near without radius", function() nf.worlds.default():entities({ near = vec3(0, 0, 0) }) end, "go together")
            check("nearby", #nf.worlds.default():entities({ near = vec3(0, 64, 0), radius = 1 }), 1)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `an entity's table is saved on it and comes back typed`() {
        val files = mapOf(
            "modules/t/init.lua" to LuaChecks.command(
                """
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0), { data = { owner = "Alex" } })
                local data = pig:data()
                check("starts with options.data", data.owner, "Alex")
                check("same table", pig:data(), data)
                data.home = vec3(1, 2, 3)
                data.friend = nf.players.get("Alex")
                data.self = pig
                nf.data("t").pig = pig
                """
            ) + "\nnf.commands.register(\"read\", function()\n" +
                "  local pig = nf.data(\"t\").pig\n" +
                "  local data = pig:data()\n" +
                "  log(data.owner, tostring(data.home), data.friend == nf.players.get(\"Alex\"), data.self == pig, getmetatable(pig))\n" +
                "end)\n"
        )
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            server.platform.commands.runConsole("run")
            assertEquals(LuaChecks.DONE, server.output())
            val pig = server.platform.worldEntities.mobs.values.single()
            assertEquals("{\"owner\":\"Alex\"}", pig.data, "only options.data until a save")
            server.runtime.events.worldSaving("world")
            val saved = assertNotNull(pig.data)
            assertTrue("\"\$vec3\":[1,2,3]" in saved, saved)
            assertTrue("\"\$entity\":[\"${pig.id}\"]" in saved, saved)
            // A player is an entity, saved by UUID alone.
            assertTrue("\"\$entity\":[\"${alex.ref.uuid}\"]" in saved, saved)
            server.restart()
            server.platform.commands.runConsole("read")
            // Saved as an entity, back as the class it is.
            assertEquals("Alex\tvec3(1, 2, 3)\ttrue\ttrue\tMob", server.logs.last())
        }
    }

    @Test
    fun `damage, death and interact are heard by the entity, then nf`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local pig = world:spawn_entity("pig", vec3(0, 64, 0))
            local alex = nf.players.get("Alex")
            local heard = {}
            pig:on("damage", function(event)
              heard[#heard + 1] = "pig " .. event.cause .. " " .. event.amount .. " " .. tostring(event.attacker == alex)
              event.amount = event.amount * 2
            end)
            nf.on("entity_damage", function(event)
              heard[#heard + 1] = "nf " .. event.amount .. " " .. tostring(event.entity == pig or event.entity == alex)
            end)
            pig:damage(3, alex)
            check("doubled", pig:health(), 4)
            local shield = alex:on("damage", function(event) event:cancel() end)
            alex:damage(5)
            check("player spared", alex:health(), 20)
            shield:cancel()
            pig:on("death", function(event)
              heard[#heard + 1] = "death " .. tostring(event.killer == alex) .. " " .. event.experience
              event.experience = 50
            end)
            pig:on("interact", function(event)
              heard[#heard + 1] = "interact " .. event.hand
            end)
            alex:on("interact_entity", function(event)
              heard[#heard + 1] = "alex interacts " .. tostring(event.entity == pig)
              event:cancel()
            end)
            nf.on("player_interact_entity", function(event) heard[#heard + 1] = "nf interact" end)
            nf.commands.register("kill", function() pig:damage(100, alex) end)
            nf.commands.register("log", function() log(table.concat(heard, " | ")) end)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val pig = server.platform.worldEntities.mobs.values.single()
                val alex = server.platform.players.byId.values.single()
                assertTrue(
                    server.platform.worldEntities.interact(alex, pig.id, "off_hand"),
                    "a handler cancelled it: ${server.logs} ${server.errors}"
                )
                server.platform.commands.runConsole("kill")
                assertEquals(pig.id to 50, server.platform.worldEntities.deaths.last())
                server.platform.commands.runConsole("log")
            }
        )
        assertEquals(
            listOf(
                "done",
                "pig entity_attack 3.0 true | nf 6.0 true | nf 5.0 true | interact off_hand | alex interacts true | nf interact | " +
                    "pig entity_attack 100.0 true | nf 200.0 true | death true 5"
            ),
            result
        )
    }

    @Test
    fun `an unknown event on an entity is an error, and a tick isn't one of its events`() {
        val result = LuaChecks.run(
            """
            local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
            fails("tick", function() pig:on("tick", function() end) end, "tick")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a world hears what spawns in it, and can stop it`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local causes = {}
            world:on("entity_spawn", function(event)
              causes[#causes + 1] = event.cause .. " " .. event.entity:kind()
              if event.entity:kind() == "minecraft:zombie" then
                event:cancel()
              end
            end)
            nf.on("entity_spawn", function(event) event.entity:add_tag("seen") end)
            local pig = world:spawn_entity("pig", vec3(0, 64, 0))
            check("tagged by nf", pig:has_tag("seen"), true)
            check("zombie stopped", world:spawn_entity("zombie", vec3(0, 64, 0)), nil)
            check("causes", table.concat(causes, ","), "custom minecraft:pig,custom minecraft:zombie")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `rays and targets hit entities, players included`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local alex = nf.players.get("Alex")
            local zombie = world:spawn_entity("zombie", vec3(5.5, 64, 5))
            local hit = world:raycast(vec3(5.5, 65, 0), vec3(0, 0, 1), 20)
            check("hit the zombie", hit.entity, zombie)
            check("where", hit.position.z, 4.7)
            check("no block", hit.block, nil)
            check("ignored", world:raycast(vec3(5.5, 65, 0), vec3(0, 0, 1), 20, { ignore = { zombie }, blocks = false }), nil)
            check("not entities", world:raycast(vec3(5.5, 65, 0), vec3(0, 0, 1), 20, { entities = false, blocks = false }), nil)
            local ahead = world:spawn_entity("zombie", vec3(0.5, 64, 5))
            check("alex sees the zombie ahead", alex:target_entity(), ahead)
            check("not that far", alex:target_entity(3), nil)
            check("looks at the ground", alex:target_block(), nil)
            alex:set_pitch(90)
            check("ground below", alex:target_block():position(), vec3(0, 63, 0))
            check("no centity", alex:target_centity(), nil)
            ahead:remove()
            check("the player from behind", world:raycast(vec3(0.5, 65, -3), vec3(0, 0, 1), 20).entity, alex)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a hidden entity stays hidden from someone who comes back`() {
        val result = LuaChecks.run(
            """
            local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
            local alex = nf.players.get("Alex")
            check("hide", pig:hide_from(alex), true)
            check("hidden", pig:is_hidden_from(alex), true)
            nf.data("t").pig = pig
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val pig = server.platform.worldEntities.mobs.values.single()
                val alex = server.platform.players.byId.values.single()
                assertEquals(setOf(alex.ref.uuid), pig.hiddenFrom)
                // The server forgets it when they leave.
                pig.hiddenFrom.clear()
                server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
                assertEquals(setOf(alex.ref.uuid), pig.hiddenFrom)
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `what a script hid shows again when the session ends`() {
        val result = LuaChecks.run(
            """
            local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
            check("hide", pig:hide_from(nf.players.get("Alex")), true)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val pig = server.platform.worldEntities.mobs.values.single()
                assertEquals(1, pig.hiddenFrom.size)
                // Set on the server's thing, so the session's: a full reload undoes it, and the scripts hide what they hide again.
                server.runtime.reloadAll()
                assertEquals(emptySet(), pig.hiddenFrom)
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a hidden entity is hidden again when its chunk loads`() {
        val result = LuaChecks.run(
            """
            local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
            local alex = nf.players.get("Alex")
            check("hide", pig:hide_from(alex), true)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val pig = server.platform.worldEntities.mobs.values.single()
                val alex = server.platform.players.byId.values.single()
                // The server forgets it when the chunk unloads.
                pig.hiddenFrom.clear()
                server.runtime.events.entitiesLoaded(emptyMap(), listOf(UUID.randomUUID()))
                assertEquals(emptySet(), pig.hiddenFrom)
                server.runtime.events.entitiesLoaded(emptyMap(), listOf(pig.id))
                assertEquals(setOf(alex.ref.uuid), pig.hiddenFrom)
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `player methods reach the player`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            check("display name", alex:display_name(), "Alex")
            check("set display name", alex:set_display_name("<red>Al"), true)
            check("changed", alex:display_name(), "<red>Al")
            check("actionbar", alex:send_actionbar("<gold>hi"), true)
            check("title", alex:send_title("Round 2", "Fight", { stay = 40 }), true)
            check("clear title", alex:clear_title(), true)
            fails("negative stay", function() alex:send_title("x", nil, { stay = -1 }) end, "can't be negative")
            check("op", alex:is_operator(), false)
            check("game_mode", alex:game_mode(), "survival")
            check("can't fly yet", alex:set_flying(true), false)
            check("creative", alex:set_game_mode("creative"), true)
            check("creative may fly", alex:can_fly(), true)
            fails("bad game_mode", function() alex:set_game_mode("god") end, "bad argument 'game_mode'")
            check("food", alex:food(), 20)
            check("set food", alex:set_food(5), true)
            fails("too much food", function() alex:set_food(21) end, "food must be from 0 to 20")
            check("saturation capped", pcall(alex.set_saturation, alex, 6), false)
            check("level", alex:set_level(3) and alex:level(), 3)
            check("progress", alex:set_experience_progress(0.5) and alex:experience_progress(), 0.5)
            fails("too far", function() alex:set_experience_progress(2) end, "progress must be from 0 to 1")
            check("give experience", alex:give_experience(7), true)
            check("may fly", alex:set_can_fly(true) and alex:can_fly(), true)
            check("flying", alex:set_flying(true) and alex:is_flying(), true)
            check("walk speed", alex:walk_speed(), 0.2)
            check("set walk speed", alex:set_walk_speed(0.4) and alex:walk_speed(), 0.4)
            check("fly speed", alex:fly_speed(), 0.1)
            check("sneaking", alex:is_sneaking(), false)
            check("locale", alex:locale(), "en_us")
            check("ping", alex:ping(), 42)
            check("held slot", alex:held_slot(), 0)
            check("select", alex:set_held_slot(4), true)
            fails("slot 9", function() alex:set_held_slot(9) end, "index must be from 0 to 8")
            check("hold", alex:set_held_item({ kind = "minecraft:diamond", count = 2 }), true)
            check("held", alex:held_item().count, 2)
            check("held is slot 4", alex:inventory():item(4).kind, "minecraft:diamond")
            check("offhand", alex:set_off_hand_item({ kind = "minecraft:bread" }) and alex:off_hand_item().kind, "minecraft:bread")
            check("offhand is 40", alex:inventory():item(40).kind, "minecraft:bread")
            check("cooldown", alex:item_cooldown("ender_pearl"), 0)
            check("set cooldown", alex:set_item_cooldown("minecraft:ender_pearl", 20), true)
            check("cooldown left", alex:item_cooldown("minecraft:ender_pearl"), 20)
            check("group", alex:set_item_cooldown("shop:wands", 40) and alex:item_cooldown("shop:wands"), 40)
            fails("bad key", function() alex:item_cooldown("Not A Key") end, "isn't an item kind or a cooldown group")
            check("tab", alex:tab_header(), nil)
            check("set tab", alex:set_tab_header("<gold>Server") and alex:tab_header(), "<gold>Server")
            check("footer", alex:set_tab_footer("bye") and alex:tab_footer(), "bye")
            check("pack", alex:resource_pack_status(), nil)
            check("sidebar handle", alex:sidebar(), alex:sidebar())
            check("ender chest", alex:ender_chest():kind(), "ender_chest")
            check("open it", alex:open_inventory(alex:ender_chest()), true)
            check("viewing", alex:ender_chest():viewers()[1], alex)
            check("close it", alex:close_inventory(), true)
            check("closed", #alex:ender_chest():viewers(), 0)
            check("kick", alex:kick("<red>Bye"), true)
            check("gone", alex:exists(), false)
            check("offline nil", alex:food(), nil)
            check("offline false", alex:send_actionbar("x"), false)
            check("offline inventory", alex:inventory(), nil)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val alex = server.platform.players.known.values.single()
                assertEquals("<red>Bye", server.platform.players.byId[alex.uuid]?.kicked ?: "<red>Bye")
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `give_item drops what doesn't fit at their feet`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local inventory = alex:inventory()
            for slot = 0, 35 do
              inventory:set_item(slot, { kind = "minecraft:diamond", count = 64 })
            end
            inventory:set_item(7, { kind = "minecraft:bread", count = 60 })
            check("dropped", alex:give_item({ kind = "minecraft:bread", count = 10 }), 6)
            check("topped up", inventory:item(7).count, 64)
            local dropped = nf.worlds.default():entities({ kind = "minecraft:item" })[1]
            check("at their feet", dropped:item().count, 6)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `resource pack status follows what the player's game said about NetherForge's pack`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to
                    "nf.commands.register(\"status\", function(event) log(tostring(event.player:resource_pack_status())) end)"
            )
        ).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            commands.run(alex, "status")
            server.runtime.events.resourcePackStatus(alex.ref.uuid, dev.netherforge.plugin.pack.Packs.PACK_ID, "pending")
            commands.run(alex, "status")
            server.runtime.events.resourcePackStatus(alex.ref.uuid, UUID.randomUUID(), "failed")
            commands.run(alex, "status")
            server.runtime.events.resourcePackStatus(alex.ref.uuid, dev.netherforge.plugin.pack.Packs.PACK_ID, "loaded")
            commands.run(alex, "status")
            server.runtime.events.resourcePackStatus(alex.ref.uuid, dev.netherforge.plugin.pack.Packs.PACK_ID, null)
            commands.run(alex, "status")
            assertEquals(listOf("nil", "pending", "pending", "loaded", "nil"), server.logs)
        }
    }

    @Test
    fun `entity command arguments are entity handles`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("which", {
                      arguments = { { name = "one", type = "entity" } },
                    }, function(event)
                      local one = event.arguments.one
                      log(one:kind(), tostring(one:is_player()))
                    end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val pig = server.platform.worldEntities.spawn(
                "minecraft:pig",
                Location("world", 0.0, 64.0, 0.0),
                dev.netherforge.plugin.platform.SpawnSetup()
            )!!
            val commands = server.platform.commands
            commands.run(alex, "which $pig")
            commands.run(alex, "which Alex")
            assertEquals(listOf("minecraft:pig\tfalse", "minecraft:player\ttrue"), server.logs)
            val missing = commands.run(alex, "which ${UUID.randomUUID()}")
            assertTrue(missing.any { "no entity with the UUID" in it }, missing.toString())
        }
    }

    @Test
    fun `numbers a player's methods set are the platform's`() {
        TestServer(
            mapOf("modules/t/init.lua" to "nf.commands.register(\"fill\", function(event) event.player:set_food(3) end)")
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "fill")
            assertEquals(3.0, alex.numbers[EntityNumber.FOOD])
            assertEquals(null, alex.inventorySlots[0])
            alex.inventorySlots[0] = ItemData(ItemDef("minecraft:bread", count = 2))
            assertEquals(2, alex.inventorySlots[0]?.def?.count)
        }
    }
}
