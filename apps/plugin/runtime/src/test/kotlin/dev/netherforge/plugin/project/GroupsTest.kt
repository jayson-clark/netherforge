package dev.netherforge.plugin.project
import dev.netherforge.format.bridge.ReloadedResource
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Resources in group folders (`centities/(towers)/tower/`) run from where they are, under the ids they always had. */
class GroupsTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    @Test
    fun `scripts, the files beside them and modules run from their group folders`() {
        TestServer(
            mapOf(
                "centities/(towers)/(tall)/c/centity.json" to TestServer.scriptedCentity(),
                "centities/(towers)/(tall)/c/script.lua" to
                    "local h = require('helpers')\nlog(h.name .. ' ' .. require('greeter').hello())",
                "centities/(towers)/(tall)/c/helpers.lua" to "return { name = 'helper' }",
                "modules/(social)/greeter/init.lua" to "local M = {}\nfunction M.hello() return require('greeter.words').hi end\nreturn M",
                "modules/(social)/greeter/words.lua" to "return { hi = 'hi' }"
            )
        ).use { server ->
            assertEquals(emptyList(), server.runtime.session.projectProblems)
            assertNotNull(server.runtime.session.centities.spawn("c", at))
            assertEquals(listOf("helper hi"), server.logs)
        }
    }

    @Test
    fun `moving a centity into a group reloads it from there`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "log('from ' .. 'root')"
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", at)
            server.delete("centities/c/centity.json")
            server.delete("centities/c/script.lua")
            server.write("centities/(towers)/c/centity.json", TestServer.scriptedCentity())
            server.write("centities/(towers)/c/script.lua", "log('from the group')")
            val result = server.reload(
                "centities/c/centity.json",
                "centities/c/script.lua",
                "centities/(towers)/c/centity.json",
                "centities/(towers)/c/script.lua"
            )
            assertEquals(listOf(ReloadedResource("test", "centity", "c", ok = true, reattached = 1)), result.resources)
            assertEquals(listOf("from root", "from the group"), server.logs)
        }
    }

    @Test
    fun `a broken file in a group keeps the last good version running`() {
        TestServer(
            mapOf(
                "items/(gems)/ruby/item.json" to """{ "kind": "minecraft:emerald", "script": { "file": "script.lua" } }""",
                "items/(gems)/ruby/script.lua" to "log('ruby')"
            )
        ).use { server ->
            server.write("items/(gems)/ruby/item.json", "{ not json")
            val result = server.reload("items/(gems)/ruby/item.json")
            val ruby = result.resources.single()
            assertEquals(listOf("item", "ruby", false), listOf(ruby.kind, ruby.id, ruby.ok))
            assertEquals(listOf("items/(gems)/ruby/item.json"), ruby.problems.map { it.file })
            assertNotNull(server.runtime.session.items.look("ruby"), "the last good version is still the item")
        }
    }
}
