package dev.netherforge.plugin.events
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.testkit.BlockAt
import dev.netherforge.plugin.testkit.FakePlatform
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's events scripts hear ([dev.netherforge.plugin.platform.GameEvents], generated
 * from the spec): payloads, cancelling, writable fields and their bounds, where each is heard,
 * and the watched ones delivered only while someone listens. Raised as the fake server raises
 * them ([FakePlatform.raise]).
 */
class GameEventTest {
    private fun module(source: String) = mapOf("modules/t/init.lua" to source)

    /** A server running [source] with Alex already online, so the module can find him as it loads. */
    private fun server(source: String): TestServer = TestServer(module(source), start = false).also {
        it.player("Alex")
        it.start()
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    /** The events, as the server delivers them: a watched one only while it's watched. */
    private val TestServer.game get() = platform.raise

    private fun item(kind: String, count: Int? = null) = ItemData(ItemDef(kind, count = count))

    // ---- A player's state and input ----

    @Test
    fun `input is watched only while someone listens, and says every key`() {
        server(
            """
                local subscription
                nf.commands.register("listen", function()
                  subscription = nf.players.get("Alex"):on("input", function(event)
                    log("input", tostring(event.forward), tostring(event.backward), tostring(event.left), tostring(event.right),
                      tostring(event.jump), tostring(event.sneak), tostring(event.sprint))
                  end)
                end)
                nf.commands.register("stop", function() subscription:cancel() end)
                """
        ).use { server ->
            fun press() = server.game.playerInput(GameEvent.PlayerInput(server.alex.ref, true, false, false, true, true, false, true))
            assertFalse(WatchedEvent.PLAYER_INPUT in server.platform.watched)
            press()
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.PLAYER_INPUT in server.platform.watched)
            press()
            server.platform.commands.runConsole("stop")
            press()
            assertFalse(WatchedEvent.PLAYER_INPUT in server.platform.watched)
            assertEquals(listOf("input\ttrue\tfalse\tfalse\ttrue\ttrue\tfalse\ttrue"), server.logs)
        }
    }

    @Test
    fun `slots, swings, sprinting, flying, jumps and game modes are heard and the cancellable ones stopped`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("change_slot", function(event) log("slot", event.from, event.to) event:cancel() end)
                nf.on("player_swing", function(event) log("swing", event.hand) event:cancel() end)
                nf.on("player_sprint", function(event) log("sprint", tostring(event.sprinting)) end)
                alex:on("fly", function(event) log("fly", tostring(event.flying)) event:cancel() end)
                nf.on("player_jump", function(event) log("jump", tostring(event.from.position), tostring(event.to.position)) end)
                nf.on("player_change_game_mode", function(event)
                  log("mode", event.game_mode, event.cause)
                  if event.game_mode == "creative" then event:cancel() end
                end)
                """
        ).use { server ->
            val alex = server.alex.ref
            val game = server.game
            assertTrue(game.playerChangeSlot(GameEvent.PlayerChangeSlot(alex, 0, 4)))
            assertTrue(game.playerSwing(GameEvent.PlayerSwing(alex, "off_hand")))
            game.playerSprint(GameEvent.PlayerSprint(alex, true))
            assertTrue(game.playerFly(GameEvent.PlayerFly(alex, true)))
            assertFalse(game.playerJump(GameEvent.PlayerJump(alex, Location("world", 1.0, 64.0, 2.0), Location("world", 1.0, 64.5, 2.0))))
            assertTrue(game.playerChangeGameMode(GameEvent.PlayerChangeGameMode(alex, "creative", "command")))
            assertFalse(game.playerChangeGameMode(GameEvent.PlayerChangeGameMode(alex, "survival", "plugin")))
            assertEquals(
                listOf(
                    "slot\t0\t4",
                    "swing\toff_hand",
                    "sprint\ttrue",
                    "fly\ttrue",
                    "jump\tvec3(1, 64, 2)\tvec3(1, 64.5, 2)",
                    "mode\tcreative\tcommand",
                    "mode\tsurvival\tplugin"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `food, experience, kicks and advancements can be changed`() {
        server(
            """
                nf.on("player_change_food", function(event)
                  log("food", event.food, event.item and event.item.kind or "hungry")
                  if event.item then event.food = 99 else event:cancel() end
                end)
                nf.on("player_gain_experience", function(event) event.amount = event.amount * 2 end)
                nf.on("player_change_level", function(event) log("level", event.from, event.to) end)
                nf.on("player_kick", function(event)
                  log("kick", event.text, tostring(event.message), event.cause)
                  if event.cause == "flying_player" then event:cancel() return end
                  event.text = "<red>Bye"
                  event.message = nil
                end)
                nf.on("player_complete_advancement", function(event)
                  log("advancement", event.advancement, tostring(event.message))
                  event.message = nil
                end)
                """
        ).use { server ->
            val alex = server.alex.ref
            val game = server.game
            assertTrue(game.playerChangeFood(GameEvent.PlayerChangeFood(alex, 19, null)))
            val eating = GameEvent.PlayerChangeFood(alex, 18, item("minecraft:bread"))
            assertFalse(game.playerChangeFood(eating))
            assertEquals(20, eating.food, "kept to a full bar")
            val experience = GameEvent.PlayerGainExperience(alex, 7)
            game.playerGainExperience(experience)
            assertEquals(14, experience.amount)
            game.playerChangeLevel(GameEvent.PlayerChangeLevel(alex, 3, 4))
            assertTrue(game.playerKick(GameEvent.PlayerKick(alex, "Flying is not enabled", "Alex left", "flying_player")))
            val kick = GameEvent.PlayerKick(alex, "Kicked", "Alex left", "kick_command")
            assertFalse(game.playerKick(kick))
            assertEquals("<red>Bye" to null, kick.text to kick.message)
            val advancement = GameEvent.PlayerAdvancement(alex, "minecraft:story/mine_diamond", "Alex got Diamonds!")
            game.playerCompleteAdvancement(advancement)
            assertNull(advancement.message)
            assertEquals(
                listOf(
                    "food\t19\thungry",
                    "food\t18\tminecraft:bread",
                    "level\t3\t4",
                    "kick\tFlying is not enabled\tAlex left\tflying_player",
                    "kick\tKicked\tAlex left\tkick_command",
                    "advancement\tminecraft:story/mine_diamond\tAlex got Diamonds!"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `healing is heard on the entity or the player, then nf, and its amount is writable`() {
        server(
            """
                local alex = nf.players.get("Alex")
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                pig:on("heal", function(event) log("pig", event.amount, event.cause) event.amount = 1 end)
                alex:on("heal", function(event) log("alex", event.entity:name(), event.cause) event:cancel() end)
                nf.on("entity_heal", function(event) log("nf", event.amount) end)
                """
        ).use { server ->
            val game = server.game
            val pig = server.platform.worldEntities.mobs.values.single().id
            val heal = GameEvent.EntityHeal(pig, 4.0, "eating")
            assertFalse(game.entityHeal(heal))
            assertEquals(1.0, heal.amount)
            assertTrue(game.entityHeal(GameEvent.EntityHeal(server.alex.ref.uuid, 2.0, "satiated")))
            // Cancelling doesn't stop it going on to `nf`: `event:stop()` does.
            assertEquals(listOf("pig\t4.0\teating", "nf\t1", "alex\tAlex\tsatiated", "nf\t2.0"), server.logs)
        }
    }

    @Test
    fun `letting go of an item and wearing one out are heard`() {
        server(
            """
                nf.on("player_stop_using_item", function(event) log("stop", event.item.kind, event.ticks) end)
                nf.players.get("Alex"):on("break_item", function(event) log("break", event.item.kind) end)
                """
        ).use { server ->
            val game = server.game
            game.playerStopUsingItem(GameEvent.PlayerStopUsingItem(server.alex.ref, item("minecraft:bow"), 12))
            game.playerBreakItem(GameEvent.PlayerItem(server.alex.ref, item("minecraft:iron_pickaxe")))
            assertEquals(listOf("stop\tminecraft:bow\t12", "break\tminecraft:iron_pickaxe"), server.logs)
        }
    }

    // ---- Combat and projectiles ----

    @Test
    fun `a launch is heard by its shooter then nf, a hit by the projectile then nf, and both can be cancelled`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("launch_projectile", function(event)
                  log("alex launched", event.projectile:kind(), event.shooter:name())
                end)
                nf.on("projectile_launch", function(event)
                  log("launch", event.projectile:kind(), event.shooter and event.shooter:name() or "nobody")
                  if not event.shooter then event:cancel() end
                end)
                nf.commands.register("watch", function()
                  local arrow = nf.worlds.default():entities({ kind = "minecraft:arrow" })[1]
                  arrow:on("hit", function(event)
                    log("hit", tostring(event.block:position()), event.face, tostring(event.entity))
                    event:cancel()
                  end)
                end)
                nf.on("projectile_hit", function(event) log("nf hit", event.shooter:name()) end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref.uuid
            val arrow = server.platform.worldEntities.spawn("minecraft:arrow", Location("world", 0.0, 64.0, 0.0), SpawnSetup())!!
            assertFalse(game.projectileLaunch(GameEvent.ProjectileLaunch(arrow, alex)))
            assertTrue(game.projectileLaunch(GameEvent.ProjectileLaunch(arrow, null)), "a dispenser's, cancelled")
            server.platform.commands.runConsole("watch")
            val stone = BlockRef("world", 1, 63, 2, "minecraft:stone", "minecraft:stone")
            assertTrue(game.projectileHit(GameEvent.ProjectileHit(arrow, alex, null, stone, "up")))
            assertEquals(
                listOf(
                    "alex launched\tminecraft:arrow\tAlex",
                    "launch\tminecraft:arrow\tAlex",
                    "launch\tminecraft:arrow\tnobody",
                    "hit\tvec3(1, 63, 2)\tup\tnil",
                    "nf hit\tAlex"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `bows, targets, burning and status effects are heard on the entity then nf`() {
        server(
            """
                local alex = nf.players.get("Alex")
                local zombie = nf.worlds.default():spawn_entity("zombie", vec3(0, 64, 0))
                alex:on("shoot_bow", function(event)
                  log("shoot", event.bow.kind, event.projectile:kind(), event.force, event.hand)
                  event:cancel()
                end)
                local targeting
                nf.commands.register("target", function()
                  targeting = zombie:on("target", function(event)
                    log("target", event.target and event.target:name() or "none", event.cause)
                    if not event.target then event:cancel() end
                  end)
                end)
                nf.commands.register("stop", function() targeting:cancel() end)
                zombie:on("combust", function(event) log("combust", event.ticks, tostring(event.block:position())) event.ticks = 40 end)
                nf.on("entity_combust", function(event) log("nf combust", event.ticks) end)
                alex:on("change_effect", function(event)
                  log("effect", event.effect, event.action, event.cause, tostring(event.from), event.to.amplifier, event.to.ticks)
                  event:cancel()
                end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref.uuid
            val zombie = server.platform.worldEntities.mobs.values.single().id
            val arrow = server.platform.worldEntities.spawn("minecraft:arrow", Location("world", 0.0, 64.0, 0.0), SpawnSetup())!!
            assertTrue(game.entityShootBow(GameEvent.EntityShootBow(alex, item("minecraft:bow"), arrow, 0.5, "main_hand")))
            assertFalse(WatchedEvent.ENTITY_TARGET in server.platform.watched)
            assertFalse(game.entityTarget(GameEvent.EntityTarget(zombie, null, "forgot_target")), "not watched: never heard")
            server.platform.commands.runConsole("target")
            assertTrue(WatchedEvent.ENTITY_TARGET in server.platform.watched)
            assertFalse(game.entityTarget(GameEvent.EntityTarget(zombie, alex, "closest_player")))
            assertTrue(game.entityTarget(GameEvent.EntityTarget(zombie, null, "forgot_target")))
            server.platform.commands.runConsole("stop")
            assertFalse(WatchedEvent.ENTITY_TARGET in server.platform.watched)
            val lava = BlockRef("world", 0, 63, 0, "minecraft:lava", "minecraft:lava[level=0]")
            val combust = GameEvent.EntityCombust(zombie, 160, null, lava)
            assertFalse(game.entityCombust(combust))
            assertEquals(40, combust.ticks)
            val speed = StatusEffectData("minecraft:speed", 600, 1)
            assertTrue(game.entityChangeEffect(GameEvent.EntityChangeEffect(alex, "minecraft:speed", "added", "potion_drink", null, speed)))
            assertEquals(
                listOf(
                    "shoot\tminecraft:bow\tminecraft:arrow\t0.5\tmain_hand",
                    "target\tAlex\tclosest_player",
                    "target\tnone\tforgot_target",
                    "combust\t160\tvec3(0, 63, 0)",
                    "nf combust\t40",
                    "effect\tminecraft:speed\tadded\tpotion_drink\tnil\t1\t600"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `an explosion can keep its blocks, change its yield or be cancelled, from an entity or a block`() {
        server(
            """
                local creeper = nf.worlds.default():spawn_entity("creeper", vec3(0, 64, 0))
                creeper:on("explode", function(event)
                  log("creeper", #event.blocks, tostring(event.blocks[1]:position()), tostring(event.location.position), event.yield)
                  event.breaks_blocks = false
                end)
                nf.worlds.default():on("block_explode", function(event)
                  log("bed", event.state, tostring(event.entity), tostring(event.location.position))
                  event.yield = 2
                end)
                nf.on("block_explode", function(event) if event.yield > 1 then event:cancel() end end)
                """
        ).use { server ->
            val game = server.game
            val creeper = server.platform.worldEntities.mobs.values.single().id
            val dirt = BlockRef("world", 0, 63, 0, "minecraft:dirt", "minecraft:dirt")
            val creeping = GameEvent.Explode(creeper, null, null, Location("world", 0.0, 64.0, 0.0), { listOf(dirt) }, true, 0.5)
            assertFalse(game.entityExplode(creeping))
            assertEquals(false to 0.5, creeping.breaksBlocks to creeping.yield)
            val bed = BlockRef("world", 4, 70, 4, "minecraft:red_bed", "minecraft:red_bed[facing=north,occupied=false,part=head]")
            val bedExplosion = GameEvent.Explode(null, bed, bed.state, Location("world", 4.5, 70.5, 4.5), { listOf(dirt) }, true, 1.0)
            assertTrue(game.blockExplode(bedExplosion), "the world's handler raised the yield; nf's cancelled it")
            assertEquals(1.0, bedExplosion.yield, "kept to a chance")
            var asked = false
            val elsewhere = GameEvent.Explode(UUID.randomUUID(), null, null, creeping.location, {
                asked = true
                emptyList()
            }, true, 1.0)
            assertFalse(game.entityExplode(elsewhere))
            assertFalse(asked, "nobody listens to that entity: its blocks are never worked out")
            assertEquals(
                listOf(
                    "creeper\t1\tvec3(0, 63, 0)\tvec3(0, 64, 0)\t0.5",
                    "bed\tminecraft:red_bed[facing=north,occupied=false,part=head]\tnil\tvec3(4.5, 70.5, 4.5)"
                ),
                server.logs
            )
        }
    }

    // ---- The world simulating itself ----

    private fun block(x: Int, y: Int, z: Int, state: String) = BlockRef("world", x, y, z, state.substringBefore('['), state)

    @Test
    fun `fire, pistons, signs, breaking and drops are heard by the world then nf`() {
        server(
            """
                local world = nf.worlds.default()
                world:on("block_ignite", function(event)
                  log("ignite", tostring(event.block:position()), event.cause, event.player:name(), tostring(event.source))
                  event:cancel()
                end)
                nf.on("block_burn", function(event) log("burn", tostring(event.source:position())) end)
                world:on("piston_extend", function(event)
                  log(event.name, event.direction, tostring(event.sticky), #event.blocks)
                  event:stop()
                end)
                nf.on("piston_extend", function() log("never: the world's handler stopped it") end)
                nf.on("piston_retract", function(event) log(event.name, event.direction) event:cancel() end)
                nf.on("sign_change", function(event) log("sign", event.side, event.lines[1], #event.lines) end)
                nf.on("block_start_break", function(event)
                  log("start", event.item and event.item.kind or "hand", tostring(event.instant))
                  event.instant = true
                end)
                nf.on("block_drop_item", function(event) log("drop", event.state, #event.items) event:cancel() end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            val planks = block(1, 64, 1, "minecraft:oak_planks")
            assertTrue(game.blockIgnite(GameEvent.BlockIgnite(planks, "flint_and_steel", alex, alex.uuid, null)))
            assertFalse(game.blockBurn(GameEvent.BlockBurn(planks, block(1, 65, 1, "minecraft:fire[age=0]"))))
            assertFalse(game.pistonExtend(GameEvent.Piston(block(0, 64, 0, "minecraft:piston"), "east", false, listOf(planks))))
            assertTrue(game.pistonRetract(GameEvent.Piston(block(0, 64, 0, "minecraft:sticky_piston"), "east", true, emptyList())))
            assertFalse(
                game.signChange(GameEvent.SignChange(alex, block(2, 64, 2, "minecraft:oak_sign"), "front", listOf("Shop", "", "", "")))
            )
            val start = GameEvent.BlockStartBreak(alex, planks, null, false)
            assertFalse(game.blockStartBreak(start))
            assertTrue(start.instant)
            val chest = "minecraft:chest[facing=north,type=single,waterlogged=false]"
            assertTrue(game.blockDropItem(GameEvent.BlockDropItem(alex, block(3, 64, 3, chest), chest) { listOf(item("minecraft:dirt")) }))
            assertEquals(
                listOf(
                    "ignite\tvec3(1, 64, 1)\tflint_and_steel\tAlex\tnil",
                    "burn\tvec3(1, 65, 1)",
                    "piston_extend\teast\tfalse\t1",
                    "piston_retract\teast",
                    "sign\tfront\tShop\t4",
                    "start\thand\tfalse",
                    "drop\tminecraft:chest[facing=north,type=single,waterlogged=false]\t1"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `the busiest block events are watched only while someone listens`() {
        server(
            """
                local subscriptions = {}
                nf.commands.register("listen", function()
                  local world = nf.worlds.default()
                  for _, name in ipairs({ "block_spread", "block_grow", "block_fade", "block_form" }) do
                    table.insert(subscriptions, world:on(name, function(event)
                      log(event.name, event.state, event.source and tostring(event.source:position()) or "nil",
                        event.entity and event.entity:name() or "nil")
                      event:cancel()
                    end))
                  end
                  table.insert(subscriptions, nf.on("block_flow", function(event) log("flow", event.face, tostring(event.to:position())) end))
                  table.insert(subscriptions, nf.on("leaves_decay", function(event) log("decay") event:cancel() end))
                  table.insert(subscriptions, nf.on("block_redstone", function(event) log("redstone", event.from, event.to) event.to = 99 end))
                  table.insert(subscriptions, nf.on("chunk_load", function(event) log("chunk", event.world:name(), event.x, event.z, tostring(event.generated)) end))
                end)
                nf.commands.register("stop", function()
                  for _, subscription in ipairs(subscriptions) do subscription:cancel() end
                end)
                """
        ).use { server ->
            val watched = listOf(
                WatchedEvent.BLOCK_SPREAD,
                WatchedEvent.BLOCK_FLOW,
                WatchedEvent.BLOCK_GROW,
                WatchedEvent.BLOCK_FADE,
                WatchedEvent.BLOCK_FORM,
                WatchedEvent.LEAVES_DECAY,
                WatchedEvent.BLOCK_REDSTONE,
                WatchedEvent.CHUNK_LOAD
            )
            val game = server.game
            val dirt = block(0, 64, 0, "minecraft:dirt")
            assertTrue(watched.none { it in server.platform.watched })
            assertFalse(game.leavesDecay(GameEvent.Block(dirt)), "not watched: never heard")
            server.platform.commands.runConsole("listen")
            assertTrue(watched.all { it in server.platform.watched })
            val grass = "minecraft:grass_block[snowy=false]"
            assertTrue(game.blockSpread(GameEvent.BlockChange(dirt, grass, block(1, 64, 0, grass), null)))
            assertTrue(game.blockGrow(GameEvent.BlockChange(dirt, "minecraft:wheat[age=7]", null, null)))
            assertTrue(game.blockFade(GameEvent.BlockChange(dirt, "minecraft:water[level=0]", null, null)))
            assertTrue(game.blockForm(GameEvent.BlockChange(dirt, "minecraft:frosted_ice[age=0]", null, server.alex.ref.uuid)))
            assertFalse(game.blockFlow(GameEvent.BlockFlow(dirt, block(0, 63, 0, "minecraft:air"), "down")))
            assertTrue(game.leavesDecay(GameEvent.Block(dirt)))
            val power = GameEvent.BlockRedstone(dirt, 0, 7)
            game.blockRedstone(power)
            assertEquals(15, power.to, "kept to the most a block can have")
            game.chunkLoad(GameEvent.ChunkLoad("world", 2, -3, true))
            server.platform.commands.runConsole("stop")
            assertTrue(watched.none { it in server.platform.watched })
            assertEquals(
                listOf(
                    "block_spread\tminecraft:grass_block[snowy=false]\tvec3(1, 64, 0)\tnil",
                    "block_grow\tminecraft:wheat[age=7]\tnil\tnil",
                    "block_fade\tminecraft:water[level=0]\tnil\tnil",
                    "block_form\tminecraft:frosted_ice[age=0]\tnil\tAlex",
                    "flow\tdown\tvec3(0, 63, 0)",
                    "decay",
                    "redstone\t0\t7",
                    "chunk\tworld\t2\t-3\ttrue"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `weather, lightning, chunks unloading and worlds are heard`() {
        server(
            """
                local world = nf.worlds.default()
                world:on("weather_change", function(event)
                  log("weather", event.world:name(), tostring(event.raining), event.cause)
                  event:cancel()
                end)
                nf.on("thunder_change", function(event) log("thunder", tostring(event.thundering)) end)
                nf.on("lightning_strike", function(event)
                  log("lightning", tostring(event.location.position), event.cause)
                  event:cancel()
                end)
                world:on("chunk_unload", function(event) log("unload", event.x, event.z) end)
                nf.on("world_load", function(event) log("load", event.world:name()) end)
                nf.on("world_unload", function(event) log("unloading", event.world:name()) end)
                """
        ).use { server ->
            val game = server.game
            assertTrue(game.weatherChange(GameEvent.WeatherChange("world", true, "natural")))
            assertFalse(game.thunderChange(GameEvent.ThunderChange("world", false, "command")))
            val bolt = server.platform.worldEntities.spawn("minecraft:pig", Location("world", 5.0, 64.0, 5.0), SpawnSetup())!!
            assertTrue(game.lightningStrike(GameEvent.LightningStrike("world", Location("world", 5.0, 64.0, 5.0), bolt, "weather")))
            game.chunkUnload(GameEvent.Chunk("world", 1, 2))
            game.worldLoad(GameEvent.World("arena"))
            game.worldUnload(GameEvent.World("arena"))
            assertEquals(
                listOf(
                    "weather\tworld\ttrue\tnatural",
                    "thunder\tfalse",
                    "lightning\tvec3(5, 64, 5)\tweather",
                    "unload\t1\t2",
                    "load\tarena",
                    "unloading\tarena"
                ),
                server.logs
            )
        }
    }

    // ---- Mounts and vehicles ----

    @Test
    fun `getting on and off is heard by the rider then nf, and vehicles are heard on nf`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("mount", function(event) log("alex mounts", event.vehicle:kind()) end)
                nf.on("entity_mount", function(event) log("mount", event.entity:kind(), event.vehicle:kind()) event:cancel() end)
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                pig:on("dismount", function(event) log("pig dismounts") event:cancel() end)
                local moving
                nf.commands.register("listen", function()
                  moving = nf.on("vehicle_move", function(event)
                    log("move", event.vehicle:kind(), tostring(event.from.position), tostring(event.to.position))
                  end)
                end)
                nf.commands.register("stop", function() moving:cancel() end)
                nf.on("vehicle_damage", function(event) log("damage", event.attacker:name(), event.amount) event.amount = 1 end)
                nf.on("vehicle_destroy", function(event) log("destroy", tostring(event.attacker)) event:cancel() end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref.uuid
            val pig = server.platform.worldEntities.mobs.values.single().id
            assertTrue(game.entityMount(GameEvent.Mount(alex, pig)))
            assertTrue(game.entityDismount(GameEvent.Mount(pig, alex)))
            assertFalse(WatchedEvent.VEHICLE_MOVE in server.platform.watched)
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.VEHICLE_MOVE in server.platform.watched)
            game.vehicleMove(GameEvent.VehicleMove(pig, Location("world", 0.5, 64.0, 0.5), Location("world", 1.5, 64.0, 0.5)))
            server.platform.commands.runConsole("stop")
            assertFalse(WatchedEvent.VEHICLE_MOVE in server.platform.watched)
            val damage = GameEvent.VehicleDamage(pig, alex, 4.0)
            assertFalse(game.vehicleDamage(damage))
            assertEquals(1.0, damage.amount)
            assertTrue(game.vehicleDestroy(GameEvent.VehicleDestroy(pig, null)))
            assertEquals(
                listOf(
                    "alex mounts\tminecraft:pig",
                    "mount\tminecraft:player\tminecraft:pig",
                    "pig dismounts",
                    "move\tminecraft:pig\tvec3(0.5, 64, 0.5)\tvec3(1.5, 64, 0.5)",
                    "damage\tAlex\t4.0",
                    "destroy\tnil"
                ),
                server.logs
            )
        }
    }

    // ---- Vanilla inventories ----

    @Test
    fun `opening and closing a vanilla inventory says what it is, and opening can be stopped`() {
        server(
            """
                nf.players.get("Alex"):on("open_inventory", function(event)
                  log("open", event.kind, event.inventory and event.inventory:kind() or "none", tostring(event.block:position()))
                  if event.kind == "workbench" then event:cancel() end
                end)
                nf.on("player_close_inventory", function(event) log("close", event.kind) end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            val chest = block(4, 64, 4, "minecraft:chest[facing=north,type=single,waterlogged=false]")
            server.platform.worlds.blocks[BlockAt("world", 4, 64, 4)] =
                "minecraft:chest[facing=north,type=single,waterlogged=false]"
            val inventory = InventoryRef.Block("world", 4, 64, 4)
            assertFalse(game.playerOpenInventory(GameEvent.PlayerInventory(alex, "chest", inventory, chest)))
            assertTrue(
                game.playerOpenInventory(GameEvent.PlayerInventory(alex, "workbench", null, block(5, 64, 5, "minecraft:crafting_table")))
            )
            game.playerCloseInventory(GameEvent.PlayerInventory(alex, "chest", inventory, chest))
            assertEquals(
                listOf("open\tchest\tchest\tvec3(4, 64, 4)", "open\tworkbench\tnone\tvec3(5, 64, 5)", "close\tchest"),
                server.logs
            )
        }
    }

    @Test
    fun `a click in a player's own inventory says where and what, and can be stopped`() {
        server(
            """
                local alex = nf.players.get("Alex")
                alex:on("click_inventory", function(event)
                  log("player", event.kind, tostring(event.own), tostring(event.inventory == alex:inventory()),
                    tostring(event.index), event.click, tostring(event.hotbar_index),
                    event.item and event.item.kind or "none", event.cursor_item and event.cursor_item.kind or "none")
                  if event.index == 40 then event:cancel() end
                end)
                nf.on("player_click_inventory", function(event) log("nf", tostring(event.index)) end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            val own = InventoryRef.Player(alex.uuid)
            val sword = item("minecraft:iron_sword")
            assertFalse(
                game.playerClickInventory(
                    GameEvent.PlayerClickInventory(alex, "crafting", own, true, 3, "left", null, sword, null)
                )
            )
            assertTrue(
                game.playerClickInventory(
                    GameEvent.PlayerClickInventory(alex, "crafting", own, true, 40, "number_key", 2, null, sword)
                )
            )
            game.playerClickInventory(GameEvent.PlayerClickInventory(alex, "chest", null, false, null, "left", null, null, null))
            assertEquals(
                listOf(
                    "player\tcrafting\ttrue\ttrue\t3\tleft\tnil\tminecraft:iron_sword\tnone",
                    "nf\t3",
                    "player\tcrafting\ttrue\ttrue\t40\tnumber_key\t2\tnone\tminecraft:iron_sword",
                    "nf\t40",
                    "player\tchest\tfalse\tfalse\tnil\tleft\tnil\tnone\tnone",
                    "nf\tnil"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a drag in a player's own inventory or a chest says over which slots, and can be stopped`() {
        server(
            """
                nf.players.get("Alex"):on("drag_inventory", function(event)
                  log("drag", event.kind, event.inventory and event.inventory:kind() or "none", table.concat(event.indices, ","),
                    table.concat(event.own_indices, ","), event.click, event.cursor_item.kind)
                  if #event.indices > 0 then event:cancel() end
                end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            server.platform.worlds.blocks[BlockAt("world", 4, 64, 4)] =
                "minecraft:chest[facing=north,type=single,waterlogged=false]"
            val chest = InventoryRef.Block("world", 4, 64, 4)
            val bread = item("minecraft:bread", 4)
            assertFalse(
                game.playerDragInventory(GameEvent.PlayerDragInventory(alex, "crafting", null, emptyList(), listOf(9, 10), "right", bread))
            )
            assertTrue(
                game.playerDragInventory(GameEvent.PlayerDragInventory(alex, "chest", chest, listOf(0, 1), listOf(0), "left", bread))
            )
            assertEquals(
                listOf("drag\tcrafting\tnone\t\t9,10\tright\tminecraft:bread", "drag\tchest\tchest\t0,1\t0\tleft\tminecraft:bread"),
                server.logs
            )
        }
    }

    @Test
    fun `what a player carries on the cursor reads back as set, and empties`() {
        server(
            """
                nf.commands.register("cursor", function(ctx)
                  local player = ctx.player
                  log("empty", tostring(player:cursor_item()))
                  log("set", tostring(player:set_cursor_item({ kind = "minecraft:emerald", count = 3 })))
                  local carried = player:cursor_item()
                  log("carried", carried.kind, carried.count)
                  log("cleared", tostring(player:set_cursor_item()), tostring(player:cursor_item()))
                  log(pcall(function() player:set_cursor_item({ kind = "minecraft:emerald", colour = 1 }) end))
                end)
                """
        ).use { server ->
            server.platform.commands.run(server.alex, "cursor")
            assertEquals("empty\tnil", server.logs[0])
            assertEquals(listOf("set\ttrue", "carried\tminecraft:emerald\t3", "cleared\ttrue\tnil"), server.logs.subList(1, 4))
            assertTrue(server.logs[4].startsWith("false\t") && "colour" in server.logs[4], server.logs[4])
        }
    }

    @Test
    fun `a crafting grid's result can be replaced or taken away, checked as it's assigned`() {
        server(
            """
                nf.on("player_prepare_craft", function(event)
                  local slots = {}
                  for slot, item in pairs(event.ingredients) do table.insert(slots, slot .. "=" .. item.kind) end
                  table.sort(slots)
                  log("prepare", tostring(event.recipe), table.concat(slots, ","), event.result and event.result.kind or "nothing")
                  if event.ingredients[4] and event.ingredients[4].kind == "minecraft:diamond" then
                    event.result = { kind = "minecraft:gold_ingot", name = "<gold>Star" }
                  elseif event.recipe == "minecraft:oak_planks" then
                    log(pcall(function() event.result = { kind = "minecraft:oak_planks", colour = 1 } end))
                    event.result = nil
                  end
                end)
                nf.on("player_craft", function(event)
                  log("craft", event.recipe, event.item.kind, tostring(event.shift))
                  event:cancel()
                end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            val diamond = item("minecraft:diamond")
            fun prepare(recipe: String?, ingredients: Map<Int, ItemData>, result: ItemData?): ItemData? {
                val event = GameEvent.PlayerPrepareCraft(alex, recipe, ingredients, result, false)
                game.playerPrepareCraft(event)
                return event.result
            }
            val star = prepare(null, mapOf(4 to diamond), null)
            assertEquals("minecraft:gold_ingot", star?.def?.kind)
            assertEquals("<gold>Star", star?.def?.name)
            val log = item("minecraft:oak_log")
            assertNull(prepare("minecraft:oak_planks", mapOf(0 to log), item("minecraft:oak_planks", 4)))
            val sticks = item("minecraft:stick", 4)
            assertTrue(prepare("minecraft:stick", mapOf(1 to log), sticks) === sticks, "unchanged: the very item asked with")
            assertTrue(game.playerCraft(GameEvent.PlayerCraft(alex, "minecraft:stick", sticks, true)))
            assertEquals(5, server.logs.size, server.logs.joinToString("\n"))
            assertEquals("prepare\tnil\t4=minecraft:diamond\tnothing", server.logs[0])
            assertEquals("prepare\tminecraft:oak_planks\t0=minecraft:oak_log\tminecraft:oak_planks", server.logs[1])
            assertTrue(
                server.logs[2].startsWith("false\t") && "event.result" in server.logs[2] && "colour" in server.logs[2],
                server.logs[2]
            )
            assertEquals("prepare\tminecraft:stick\t1=minecraft:oak_log\tminecraft:stick", server.logs[3])
            assertEquals("craft\tminecraft:stick\tminecraft:stick\ttrue", server.logs[4])
        }
    }

    @Test
    fun `anvils, smithing, grindstones, enchanting and furnaces can change what they make`() {
        server(
            """
                nf.on("player_prepare_anvil", function(event)
                  log("anvil", event.left.kind, tostring(event.right), event.rename, event.cost)
                  event.cost = 1
                  event.result.name = "<red>" .. event.rename
                end)
                nf.on("player_prepare_smithing", function(event) log("smithing", event.base.kind) event.result = nil end)
                nf.on("player_prepare_grindstone", function(event) log("grindstone", event.top.kind) end)
                nf.on("player_enchant_item", function(event)
                  log("enchant", event.item.kind, event.enchantments["minecraft:sharpness"], event.cost)
                  event.cost = 0
                end)
                nf.worlds.default():on("furnace_smelt", function(event)
                  log("smelt", event.source.kind, event.result.kind)
                  if event.source.kind == "minecraft:iron_ore" then
                    event.result = { kind = "minecraft:gold_ingot", count = 2 }
                  else
                    event:cancel()
                  end
                end)
                """
        ).use { server ->
            val game = server.game
            val alex = server.alex.ref
            val sword = item("minecraft:paper")
            val anvil = GameEvent.PlayerPrepareAnvil(alex, sword, null, "Biter", sword, 5)
            game.playerPrepareAnvil(anvil)
            assertEquals(1, anvil.cost)
            assertEquals("<red>Biter", anvil.result?.def?.name, "a result changed in place is read back too")
            val smithing = GameEvent.PlayerPrepareSmithing(
                alex,
                item("minecraft:netherite_upgrade_smithing_template"),
                item("minecraft:diamond_sword"),
                item("minecraft:netherite_ingot"),
                item("minecraft:netherite_sword")
            )
            game.playerPrepareSmithing(smithing)
            assertNull(smithing.result)
            val clean = item("minecraft:paper")
            val grindstone = GameEvent.PlayerPrepareGrindstone(alex, sword, null, clean)
            game.playerPrepareGrindstone(grindstone)
            assertTrue(grindstone.result === clean)
            val table = block(6, 64, 6, "minecraft:enchanting_table")
            val enchant = GameEvent.PlayerEnchantItem(alex, table, sword, mapOf("minecraft:sharpness" to 3), 30)
            assertFalse(game.playerEnchantItem(enchant))
            assertEquals(0, enchant.cost)
            val furnace = block(7, 64, 7, "minecraft:furnace[facing=north,lit=true]")
            val iron = GameEvent.FurnaceSmelt(furnace, item("minecraft:iron_ore"), item("minecraft:iron_ingot"))
            assertFalse(game.furnaceSmelt(iron))
            assertEquals(item("minecraft:gold_ingot", 2), iron.result)
            assertTrue(game.furnaceSmelt(GameEvent.FurnaceSmelt(furnace, item("minecraft:sand"), item("minecraft:glass"))))
            assertEquals(
                listOf(
                    "anvil\tminecraft:paper\tnil\tBiter\t5",
                    "smithing\tminecraft:diamond_sword",
                    "grindstone\tminecraft:paper",
                    "enchant\tminecraft:paper\t3\t30",
                    "smelt\tminecraft:iron_ore\tminecraft:iron_ingot",
                    "smelt\tminecraft:sand\tminecraft:glass"
                ),
                server.logs
            )
        }
    }

    // ---- The server list ----

    @Test
    fun `a server list ping is watched only while someone listens, and its answer can be changed or withheld`() {
        server(
            """
                local subscription
                nf.commands.register("listen", function()
                  subscription = nf.on("server_list_ping", function(event)
                    log("ping", event.address, event.description, event.online, event.max_players, tostring(event.hide_players))
                    if event.address == "203.0.113.9" then event:cancel() return end
                    event.description = "<gold>Arena night"
                    event.max_players = event.online + 1
                    event.hide_players = true
                  end)
                end)
                nf.commands.register("stop", function() subscription:cancel() end)
                """
        ).use { server ->
            val game = server.game
            assertFalse(WatchedEvent.SERVER_LIST_PING in server.platform.watched)
            server.platform.commands.runConsole("listen")
            assertTrue(WatchedEvent.SERVER_LIST_PING in server.platform.watched)
            val ping = GameEvent.ServerListPing("203.0.113.7", "A Minecraft Server", 3, 20, false)
            assertFalse(game.serverListPing(ping))
            assertEquals(GameEvent.ServerListPing("203.0.113.7", "<gold>Arena night", 3, 4, true), ping)
            assertTrue(game.serverListPing(GameEvent.ServerListPing("203.0.113.9", "A Minecraft Server", 3, 20, false)))
            server.platform.commands.runConsole("stop")
            assertFalse(WatchedEvent.SERVER_LIST_PING in server.platform.watched)
            assertEquals(
                listOf("ping\t203.0.113.7\tA Minecraft Server\t3\t20\tfalse", "ping\t203.0.113.9\tA Minecraft Server\t3\t20\tfalse"),
                server.logs
            )
        }
    }
}
