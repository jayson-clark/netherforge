package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.InstanceInfo
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.ProfilerSubscribeParams
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.bridge.SpawnParams
import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.eventually
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `examples/showcase` on headless Paper, hot-reloaded over the bridge the way the
 * editor does on save: modules starting on real stacks, each kind of resource
 * reloading, typed commands as Brigadier nodes swapped by a reload, the
 * resource pack served at its hash, a live centity reattached to a new script
 * and an error located, and what survives a restart.
 */
class ReloadScenario : Scenario("reload") {
    private lateinit var tower: InstanceInfo

    @Test
    @Order(1)
    fun `the plugin says hello with the server's version and the project`() {
        assertEquals(Adapter.minecraft, running.hello.minecraft)
        assertEquals(project.root.toRealPath(), Path.of(running.hello.project).toRealPath())
        assertEquals(Bridge.PROTOCOL, running.hello.protocol)
    }

    @Test
    @Order(2)
    fun `a method the plugin doesn't have is answered, and so is a control request`() {
        val unknown = editor.call("debugger/attach")
        assertEquals(JsonRpc.METHOD_NOT_FOUND to "Unknown method \"debugger/attach\"", unknown.code to unknown.error)
        assertTrue(editor.request(Bridge.ping, Unit).first.ok)
    }

    @Test
    @Order(2)
    fun `the profiler streams every tick's steps once subscribed, and nf profile writes a report`() {
        assertTrue(editor.request(Bridge.profilerSubscribe, ProfilerSubscribeParams(on = true)).first.ok)
        val sample = editor.next { it is ProfileSample && it.ticks.size == 20 } as ProfileSample
        assertEquals(
            listOf("timers", "async", "events", "world", "effects", "upkeep", "accounts", "save"),
            sample.ticks[0].phases.keys.toList()
        )
        assertTrue(sample.ticks.all { it.nanos > 0 }, "$sample")
        assertTrue(editor.request(Bridge.profilerSubscribe, ProfilerSubscribeParams(on = false)).first.ok)

        editor.run("nf profile 1")
        val written = editor.next { it is Log && it.message.startsWith("Profile written to ") } as Log
        assertTrue(written.message.contains("profile-"), written.message)
    }

    @Test
    @Order(3)
    fun `modules start on real stacks, menus and recipes`() {
        // an item's script data round-trips through the real stack; nothing else needed raw
        editor.logged("item data", "minecraft:gold_nugget", "true", "3", "b", "nil")
        editor.logged("typed item data", "vec3(1, 2.5, 3)", "true", "float")
        editor.logged("no item data", "nil")
        editor.logged("saved data", "1", "vec3(4, 5, 6)")

        // a hopper menu is a real five-slot window, and every item component survives a real stack
        editor.logged("hopper", "5", "1")
        editor.logged("components", "16", "epic", "2", "minecraft:attack_damage", "main_hand", "nil", "it:speed", "add_multiplied_total")
        editor.logged("adventure", "minecraft:stone", "minecraft:grass_block")
        editor.logged("food", "4", "2.5", "true", "0.8")
        editor.logged("cooldown", "2.0", "it:pearls", "nil")
        editor.logged("hold", "true", "nil", "nil")
        editor.logged("use effects", "1.0", "true", "nil", "nil")
        editor.logged("stackable sword", "false", "true")

        // the project's recipes and a script's, on the real server
        editor.logged(
            "recipes",
            "candy,crystal_glass,cut_crystal_glass,glow_lamp,glow_pickaxe,glow_shard,ruby,ruby_dust,ruby_sword,it_planks"
        )
    }

    @Test
    @Order(4)
    fun `saving a resource's script reloads that resource`() {
        for ((path, key) in listOf(
            "menus/shop/script.lua" to "menu:shop",
            "dialogs/welcome/script.lua" to "dialog:welcome",
            "items/ruby/script.lua" to "item:ruby",
            "recipes/ruby_sword.json" to "recipe:ruby_sword"
        )) {
            val resource = editor.reload(path).resources.single()
            assertEquals(key, resource.label)
            assertTrue(resource.ok, "${resource.problems}")
        }
    }

    @Test
    @Order(5)
    fun `typed commands are Brigadier nodes whose arguments arrive typed`() {
        editor.run("it-typed 5 1 2 3")
        editor.logged("typed 5 vec3(1.5, 2, 3.5) alpha")
        editor.run("it-typed sub true")
        editor.logged("sub true")
        // Brigadier refuses what doesn't fit (above max), and the adapter what Brigadier's word type can't tell (a
        // word that isn't one of the choices); neither reaches the runtime. Commands run in order, so by the time
        // the next one logs, those two would have.
        editor.tryRun("it-typed 99 1 2 3")
        editor.tryRun("it-typed 5 1 2 3 gamma")
        editor.run("it-typed 6 1 2 3 beta")
        editor.logged("typed 6 vec3(1.5, 2, 3.5) beta")
        // A block state as the server read it: every property filled in.
        editor.run("it-typed 7 1 2 3 beta oak_stairs[facing=east]")
        editor.logged("typed 7 vec3(1.5, 2, 3.5) beta minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
        editor.assertNone("a refused command reached the script") { it is Log && it.message.startsWith("typed") }
    }

    @Test
    @Order(6)
    fun `a module reload swaps its commands in the live dispatcher`() {
        // a new one, and an old name with new arguments
        project.add("typed-v2")
        val swapped = editor.reload("modules/typed/init.lua")
        assertTrue(swapped.resources.single().ok, "${swapped.resources}")
        editor.run("it-renamed 7")
        editor.logged("renamed 7")
        editor.tryRun("it-typed 5 1 2 3")
        editor.run("it-typed hello")
        editor.logged("typed again hello")
        editor.assertNone("a refused command reached the script") { it is Log && it.message.startsWith("typed") }
    }

    @Test
    @Order(7)
    fun `the resource pack is built from the project's resource packs, served at its hash, and rebuilt when a texture changes`() {
        val pack = editor.reload("resource_packs/ui/pack.json").resources.single().also { assertTrue(it.ok, "${it.problems}") }.pack!!
        assertEquals(pack.sha1, fetchSha1(pack.url!!))

        val ruby = project.file("resource_packs/ui/textures/item/ruby.png")
        Files.write(ruby, Files.readAllBytes(ruby) + byteArrayOf(0))
        val next = editor.reload("resource_packs/ui/textures/item/ruby.png").resources.single().pack!!
        assertTrue(next.sha1 != pack.sha1, "a changed texture changes the hash")
        assertEquals(next.sha1, fetchSha1(next.url!!))
    }

    @Test
    @Order(8)
    fun `a live centity reattaches to its edited script`() {
        // spawn: nobody's online, so at the world spawn
        val (spawned, tower) = editor.request(Bridge.spawn, SpawnParams("tower"))
        assertTrue(spawned.ok, spawned.error)
        assertEquals("tower", tower!!.centity)
        this.tower = tower
        // (The showcase's centities booth has a tower of its own, which reattaches with it.)
        val towers = towers()
        assertTrue(tower.uuid in towers, "$towers")

        project.write("centities/tower/script.lua", towerScript("v2"))
        val resource = editor.reload("centities/tower/script.lua").resources.single()
        assertEquals("centity:tower", resource.label)
        assertTrue(resource.ok, "${resource.problems}")
        assertEquals(towers.size, resource.reattached)
        val log = editor.logged("tower v2 loaded")
        assertEquals(SourceRef("centities/tower/script.lua", 2), log.source)
    }

    @Test
    @Order(9)
    fun `a broken script's error comes back located`() {
        expectScriptErrors("the tower's script is broken on purpose") { it.source?.file == "centities/tower/script.lua" }
        project.write("centities/tower/script.lua", "do\n  log(\"unclosed\"\nend\n")
        assertFalse(editor.reload("centities/tower/script.lua").resources.single().ok)
        // The tower's own error, by its file: not any other error that came meanwhile.
        val error = editor.next(what = "the tower's error") {
            it is ScriptError && it.source?.file == "centities/tower/script.lua"
        } as ScriptError
        assertEquals(SourceRef("centities/tower/script.lua", 3), error.source)
    }

    @Test
    @Order(10)
    fun `after a restart the tower is still there, on the same entities, running its fixed script`() {
        project.write("centities/tower/script.lua", towerScript("v3"))
        lateinit var saved: PaperServer.SavedEntities
        restart {
            // every node that draws or can be clicked got its entity, and the index remembers them
            saved = server.entities()
            assertEquals(setOf("base", "keep", "cap", "flag"), saved.displays.keys)
            assertEquals(setOf("base", "keep", "cap"), saved.hitboxes.keys)
        }
        editor.logged("tower v3 loaded", seconds = 120)
        // a module's saved table came back from the first run, typed
        editor.logged("saved data", "2", "vec3(4, 5, 6)")
        assertTrue(tower.uuid in towers())

        // and it found them: dropping the flag node (and `flutter`, the animation that waves it) removes the flag's entity
        val definition = "centities/tower/centity.json"
        val file = Json.parseToJsonElement(project.read(definition)).jsonObject
        val nodes = JsonObject(file.getValue("nodes").jsonObject - "flag")
        val animations = JsonObject(file.getValue("animations").jsonObject - "flutter")
        project.write(definition, JsonObject(file + ("nodes" to nodes) + ("animations" to animations)).toString())
        assertTrue(editor.reload(definition).resources.single().ok)
        // Nobody's online, so nothing has loaded the tower's chunk since the restart. Load it as a player walking up
        // would; the plugin reconciles once the entities come back with it.
        editor.run("forceload add ${tower.x.toInt()} ${tower.z.toInt()}")
        val after = eventually("the flag's entity gone from the store", poll = server::entities) { "flag" !in it.displays }
        assertEquals(saved.displays - "flag", after.displays, "the same base, keep and cap displays; the flag's is gone")
        assertEquals(saved.hitboxes, after.hitboxes)
    }

    private fun towerScript(version: String) = project.fixture("edits/tower.lua").replace("VERSION", version)

    /** The live towers: the one this scenario spawned, and the showcase's expo's own. */
    private fun towers(): List<String> = editor.request(Bridge.instances, Unit).second!!.filter { it.centity == "tower" }.map { it.uuid }

    /** Downloads [url] and answers the SHA-1 of what came back, failing on anything but a 200. */
    private fun fetchSha1(url: String): String {
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode(), "GET $url")
        return MessageDigest.getInstance("SHA-1").digest(response.body()).joinToString("") { "%02x".format(it) }
    }
}
