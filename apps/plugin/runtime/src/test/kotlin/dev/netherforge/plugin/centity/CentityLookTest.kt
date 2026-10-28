package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.centity.TextAlignment
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DisplayBrightness
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What scripts change about how a centity is drawn and who sees it: glow, brightness,
 * billboards, text style, item displays, view range, easing, clickability
 * and per-player hiding.
 */
class CentityLookTest {
    private val at = Location("world", 10.0, 64.0, 10.0)

    private val sign = """
        {
          "nodes": {
            "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
            "sword": { "parent": "base", "display": { "type": "item", "item": "minecraft:diamond" } },
            "label": { "parent": "base", "display": { "type": "text", "text": "hi", "shadow": true } },
            "pivot": {}
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun server(script: String) = TestServer(
        mapOf("centities/sign/centity.json" to sign, "centities/sign/script.lua" to script)
    )

    private fun TestServer.spawn() = assertNotNull(runtime.session.centities.spawn("sign", at))

    @Test
    fun `roots and display kinds`() {
        server(
            """
            local names = {}
            for _, node in ipairs(this:roots()) do names[#names + 1] = node:name() end
            log(table.concat(names, ","))
            log(this:node("base"):display_kind(), this:node("sword"):display_kind(), this:node("label"):display_kind(), tostring(this:node("pivot"):display_kind()))
            """
        ).use { server ->
            server.spawn()
            assertEquals(listOf("base,pivot", "block\titem\ttext\tnil"), server.logs)
        }
    }

    @Test
    fun `glow colours show on block and item displays only`() {
        server(
            """
            local label, base = this:node("label"), this:node("base")
            log(base:set_glow_color("#ff8800"), label:set_glow_color("#ff8800"), this:node("pivot"):set_glowing(true))
            log(base:glow_color(), tostring(label:glow_color()), tostring(this:glow_color()))
            log(this:set_glow_color("#00ff00"), this:glow_color())
            log(this:is_glowing(), this:set_glowing(true), this:is_glowing(), label:is_glowing())
            log(select(2, pcall(base.set_glow_color, base, "orange")):match("#RRGGBB") ~= nil)
            """
        ).use { server ->
            val sign = server.spawn()
            assertEquals(
                listOf(
                    "true\tfalse\tfalse",
                    "#ff8800\tnil\tnil",
                    "true\t#00ff00",
                    "false\ttrue\ttrue\ttrue",
                    "true"
                ),
                server.logs
            )
            server.tick()
            val entities = server.platform.entities
            val base = entities.display(sign.id, "base").look!!
            assertTrue(base.glowing)
            assertEquals(0x00ff00, base.glowColor)
            val label = entities.display(sign.id, "label").look!!
            assertTrue(label.glowing)
            assertNull(label.glowColor)
        }
    }

    @Test
    fun `brightness, billboards and easing reach the displays`() {
        server(
            """
            local base, label = this:node("base"), this:node("label")
            log(tostring(base:brightness()), base:billboard(), label:billboard(), base:interpolation_ticks())
            base:set_brightness({ block_light = 15, sky_light = 3 })
            local b = base:brightness()
            log(b.block_light, b.sky_light)
            log(select(2, pcall(base.set_brightness, base, { block_light = 16, sky_light = 0 })):match("0 to 15") ~= nil)
            log(select(2, pcall(base.set_brightness, base, { block_light = 1 })):match("sky_light") ~= nil)
            log(base:set_billboard("vertical"), base:billboard(), this:node("pivot"):set_billboard("center"))
            log(select(2, pcall(base.set_billboard, base, "sideways")):match("expected") ~= nil)
            log(base:set_interpolation_ticks(80), base:interpolation_ticks())
            """
        ).use { server ->
            val sign = server.spawn()
            assertEquals(
                listOf(
                    "nil\tfixed\tcenter\t1",
                    "15\t3",
                    "true",
                    "true",
                    "true\tvertical\tfalse",
                    "true",
                    "true\t80"
                ),
                server.logs
            )
            server.tick()
            val base = server.platform.entities.display(sign.id, "base")
            assertEquals(DisplayBrightness(15, 3), base.look!!.brightness)
            assertEquals(Billboard.VERTICAL, base.look!!.billboard)
            // The teleport duration follows the easing, up to Minecraft's 59.
            assertEquals(59, base.look!!.teleportTicks)
            assertEquals(1, server.platform.entities.display(sign.id, "label").look!!.teleportTicks)
        }
    }

    @Test
    fun `a billboard stands at its node, drawn with its scale only`() {
        val lamp = """
            {
              "nodes": {
                "light": { "transform": { "scale": [0.5, 0.5, 0.5] }, "display": { "type": "block", "block": "minecraft:stone" } },
                "label": {
                  "parent": "light",
                  "transform": { "translation": [1, 2, 0], "rotation": [0, 30, 0] },
                  "display": { "type": "text", "text": "Lamp", "billboard": "center" }
                }
              }
            }
        """
        TestServer(mapOf("centities/lamp/centity.json" to lamp)).use { server ->
            val centities = server.runtime.session.centities
            // Facing east: the label's +X offset comes out along the world's -Z.
            val instance = assertNotNull(centities.spawn("lamp", at, yaw = -90.0))
            server.tick()
            val entities = server.platform.entities
            val anchor = entities.anchor(instance.id).id
            val label = entities.display(instance.id, "label")
            // Not on the anchor entity: the client would turn its offset and rotation to the camera with it.
            assertNull(label.vehicle)
            val spot = label.location
            assertEquals(10.0, spot.x, 1e-9)
            assertEquals(65.0, spot.y, 1e-9)
            assertEquals(9.5, spot.z, 1e-9)
            val drawn = label.pose!!.matrix.values
            val scaleOnly = Matrix4().scale(0.5, 0.5, 0.5).values
            for (i in 0 until 16) assertEquals(scaleOnly[i], drawn[i], 1e-9, "matrix value $i")
            // The block isn't a billboard: it rides the anchor, its offset and turn in its matrix.
            assertEquals(anchor, entities.display(instance.id, "light").vehicle)

            // Made fixed, it gets back on and carries its pose in its matrix again.
            instance.setLook(1, instance.look(1).copy(billboard = Billboard.FIXED))
            server.tick()
            assertEquals(anchor, label.vehicle)
            assertEquals(instance.placed()[1].values, label.pose!!.matrix.values)
        }
    }

    @Test
    fun `a pose change eases over the node's interpolation ticks`() {
        server("this:node(\"base\"):set_interpolation_ticks(10)").use { server ->
            val sign = server.spawn()
            server.tick()
            sign.set(0, dev.netherforge.format.centity.Channel.ROTATION, dev.netherforge.format.math.Vec3(0.0, 45.0, 0.0))
            server.tick()
            assertEquals(10, server.platform.entities.display(sign.id, "base").pose!!.interpolationTicks)
            assertEquals(1, server.platform.entities.display(sign.id, "sword").pose!!.interpolationTicks)
        }
    }

    @Test
    fun `text style is read whole and replaced whole`() {
        server(
            """
            local label = this:node("label")
            local style = label:display_text_style()
            log(tostring(style.background), style.opacity, style.shadow, style.alignment, style.line_width, style.see_through)
            style.background = "#80000000"
            style.opacity = 128
            style.alignment = "left"
            log(label:set_display_text_style(style))
            log(label:set_display_text_style({ shadow = false, line_width = 50 }), label:display_text_style().line_width, tostring(label:display_text_style().background))
            label:set_display_text_style(style)
            log(tostring(this:node("base"):display_text_style()), this:node("base"):set_display_text_style({}))
            log(select(2, pcall(label.set_display_text_style, label, { background = "red" })):match("AARRGGBB") ~= nil)
            log(select(2, pcall(label.set_display_text_style, label, { alignment = "middle" })):match("expected") ~= nil)
            log(select(2, pcall(label.set_display_text_style, label, { colour = "x" })) ~= nil)
            """
        ).use { server ->
            val sign = server.spawn()
            assertEquals(
                listOf(
                    "nil\t255\ttrue\tcenter\t200\tfalse",
                    "true",
                    "true\t50\tnil",
                    "nil\tfalse",
                    "true",
                    "true",
                    "true"
                ),
                server.logs
            )
            server.tick()
            val label = server.platform.entities.display(sign.id, "label")
            val text = label.display as TextDisplay
            assertEquals("#80000000", text.background)
            assertEquals(TextAlignment.LEFT, text.alignment)
            assertEquals(true, text.shadow)
            assertEquals(128, label.look!!.textOpacity)
        }
    }

    @Test
    fun `an item display shows a whole item`() {
        server(
            """
            local sword = this:node("sword")
            log(sword:display_item().kind)
            log(sword:set_display_item({ kind = "minecraft:paper", name = "<gold>Map", glint = true }))
            local item = sword:display_item()
            log(item.kind, item.name, item.glint)
            log(this:node("base"):set_display_item({ kind = "minecraft:paper" }), tostring(this:node("base"):display_item()))
            log(select(2, pcall(sword.set_display_item, sword, { kind = "minecraft:paper", colour = "red" })):match("no field") ~= nil)
            """
        ).use { server ->
            val sign = server.spawn()
            assertEquals(
                listOf("minecraft:diamond", "true", "minecraft:paper\t<gold>Map\ttrue", "false\tnil", "true"),
                server.logs
            )
            server.tick()
            val sword = server.platform.entities.display(sign.id, "sword")
            assertEquals("minecraft:paper", (sword.display as ItemDisplay).item)
            assertEquals("<gold>Map", sword.look!!.item!!.def.name)
        }
    }

    @Test
    fun `view range is in blocks and reaches the displays as Minecraft's multiplier`() {
        server(
            """
            log(this:view_range())
            log(this:set_view_range(128), this:view_range())
            log(select(2, pcall(this.set_view_range, this, -1)):match("negative") ~= nil)
            """
        ).use { server ->
            val sign = server.spawn()
            assertEquals(listOf("64.0", "true\t128.0", "true"), server.logs)
            server.tick()
            assertEquals(2.0, server.platform.entities.display(sign.id, "base").look!!.viewRange)
        }
    }

    @Test
    fun `an unclickable node is drawn but takes no clicks`() {
        server(
            """
            local base = this:node("base")
            base:on("click", function() log("clicked") end)
            log(base:is_clickable())
            base:set_clickable(false)
            log(base:is_clickable(), base:is_visible())
            """
        ).use { server ->
            val sign = server.spawn()
            val alex = server.player("Alex")
            server.tick()
            val hitbox = server.platform.entities.hitbox(sign.id, "base")
            assertEquals(0.01, hitbox.width)
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            assertEquals(listOf("true", "false\ttrue"), server.logs)
            // Still drawn.
            assertEquals(1.0, server.platform.entities.display(sign.id, "base").pose!!.matrix.scale().x, 1e-9)
        }
    }

    @Test
    fun `hiding is per player and comes back after a rejoin and a chunk reload`() {
        server(
            """
            this:node("base"):on("click", function(event) log("clicked by " .. event.player:name()) end)
            nf.on("player_join", function(event)
              if event.player:name() == "Alex" then
                log(this:hide_from(event.player), this:is_hidden_from(event.player))
              end
            end)
            """
        ).use { server ->
            val sign = server.spawn()
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            server.tick()
            val entities = server.platform.entities.of(sign.id)
            assertTrue(entities.all { alex.ref.uuid in it.hiddenFrom }, "every display and hitbox")
            assertTrue(entities.none { sam.ref.uuid in it.hiddenFrom })

            // A hidden player's click goes nowhere.
            val hitbox = server.platform.entities.hitbox(sign.id, "base")
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            server.runtime.events.entityClicked(hitbox.id, sam.ref, ClickButton.RIGHT, null)
            assertEquals(listOf("true\ttrue", "clicked by Sam"), server.logs)

            // The server forgets on quit; the runtime says it again on join.
            server.platform.entities.forget(alex.ref.uuid)
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertTrue(server.platform.entities.of(sign.id).all { alex.ref.uuid in it.hiddenFrom })

            // And on an entity reload.
            server.platform.entities.forget()
            server.runtime.events.entitiesLoaded(server.platform.entities.loadedTagged(), emptyList())
            assertTrue(server.platform.entities.of(sign.id).all { alex.ref.uuid in it.hiddenFrom })
        }
    }

    @Test
    fun `show_to undoes hide_from`() {
        server(
            """
            local alex = nf.players.get("Alex")
            log(this:hide_from(alex), this:is_hidden_from(alex))
            log(this:show_to(alex), this:is_hidden_from(alex))
            """
        ).use { server ->
            val alex = server.player("Alex")
            val sign = server.spawn()
            assertEquals(listOf("true\ttrue", "true\tfalse"), server.logs)
            assertFalse(alex.ref.uuid in sign.hiddenFrom)
            assertTrue(server.platform.entities.of(sign.id).none { alex.ref.uuid in it.hiddenFrom })
        }
    }
}
