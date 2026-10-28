package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.ModelDisplay
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/** A node that shows a resource pack look (a model with elements), and a script swapping it frame by frame. */
class ModelDisplayTest {
    private val model = """
        { "textures": { "0": "gem" }, "elements": [{ "from": [6, 0, 6], "to": [10, 12, 10], "faces": { "north": { "texture": "#0" } } }] }
    """.trimIndent()

    private val files = mapOf(
        "resource_packs/mobs/pack.json" to
            """{ "items": { "wing_1": { "model": "wing_1.json" }, "wing_2": { "model": "wing_2.json" }, "flat": { "texture": "gem.png" } } }""",
        "resource_packs/mobs/models/wing_1.json" to model,
        "resource_packs/mobs/models/wing_2.json" to model,
        "resource_packs/mobs/textures/gem.png" to ByteArray(0),
        "centities/bat/centity.json" to """
            { "nodes": {
                "wing": { "display": { "type": "model", "model": "mobs/wing_1" } },
                "body": { "display": { "type": "block", "block": "minecraft:stone" } }
            } }
        """.trimIndent()
    )

    @Test
    fun `set_model shows another look on the same display entity`() {
        var bat = ""
        val result = LuaChecks.run(
            """
            local bat = assert(nf.centities.spawn("bat", vec3(0, 64, 0)))
            local wing = assert(bat:node("wing"))
            check("kind", wing:display_kind(), "model")
            check("model", wing:model(), "mobs/wing_1")
            check("swapped", wing:set_model("mobs/wing_2"), true)
            check("now", wing:model(), "mobs/wing_2")
            check("a texture look is a look too", wing:set_model("mobs/flat"), true)
            check("not a model display", bat:node("body"):set_model("mobs/wing_1"), false)
            check("body has none", bat:node("body"):model(), nil)
            fails("unknown look", function() wing:set_model("mobs/tail") end, "has no item model \"tail\"")
            log("id " .. bat:id())
            """.trimIndent(),
            files,
            after = { server ->
                bat = server.logs.single { it.startsWith("id ") }.removePrefix("id ")
            }
        )
        assertEquals(listOf("id $bat", "done"), result)
    }

    @Test
    fun `a model is drawn turned a half turn about Y, a block is not`() {
        TestServer(files).use { server ->
            val instance = server.runtime.session.centities.spawn(
                "bat",
                dev.netherforge.plugin.platform.Location("world", 0.0, 64.0, 0.0)
            )!!
            server.tick(1)
            // The client draws a model a half turn about Y from the matrix it's sent, so the sent matrix carries the
            // half turn back: at its rest pose the node's +Z is sent as -Z, and the client's turn lands it on +Z.
            val wing = server.platform.entities.display(instance.id, "wing").pose!!.matrix
            assertEquals(-1.0, wing.values[10], 1e-9, "the sent pose turns the model a half turn")
            assertEquals(0.0, wing.values[8], 1e-9)
            val body = server.platform.entities.display(instance.id, "body").pose!!.matrix
            assertEquals(1.0, body.values[10], 1e-9, "a block keeps its +Z")
        }
    }

    @Test
    fun `swapping a look is an update of the entity, not a respawn`() {
        TestServer(files).use { server ->
            val instance = server.runtime.session.centities.spawn(
                "bat",
                dev.netherforge.plugin.platform.Location("world", 0.0, 64.0, 0.0)
            )!!
            server.tick(1)
            val wing = server.platform.entities.display(instance.id, "wing")
            assertEquals(ModelDisplay(ResourceRef("mobs/wing_1")), wing.display)
            val spawned = server.platform.entities.spawned
            for (frame in listOf("mobs/wing_2", "mobs/wing_1", "mobs/wing_2")) {
                server.runtime.session.centities.find(instance.id)!!.let {
                    it.setDisplay(it.indexOf("wing")!!, ModelDisplay(ResourceRef(frame)))
                }
                server.tick(1)
                assertEquals(ModelDisplay(ResourceRef(frame)), server.platform.entities.display(instance.id, "wing").display)
            }
            assertEquals(spawned, server.platform.entities.spawned, "nothing spawned again")
            assertEquals(wing.id, server.platform.entities.display(instance.id, "wing").id)
        }
    }
}
