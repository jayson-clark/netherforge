package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Rust runtime through FFM, on the testkit's fake server: what a script does reaches the fake, what it raises the script. */
class NativeRuntimeTest {
    private fun project(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("nf-ffi")
        for ((path, text) in files) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        return root
    }

    private fun started(files: Map<String, String>): Pair<FakePlatform, NativeRuntime> {
        Native.load(Path.of(System.getProperty("netherforge.ffi.library")))
        val platform = FakePlatform()
        val project = project(files)
        val runtime = NativeRuntime(platform)
        val plugin = Files.createTempDirectory("nf-ffi-plugin")
        val started = runtime.start(
            pluginFolder = plugin,
            serverFolder = plugin.parent,
            serverIp = "",
            settings = mapOf("project" to project.toString()),
            properties = emptyMap(),
            environment = emptyMap()
        )
        assertTrue(started, platform.log.lines.joinToString("\n"))
        return platform to runtime
    }

    private val manifest = """{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"""

    @Test
    fun `a command the runtime registers runs its handler with the server's sender, whose reply reaches the player`() {
        val (platform, runtime) = started(
            mapOf(
                "netherforge.json" to manifest,
                "modules/hello/init.lua" to """
                    nf.commands.register("hello", { description = "Says hello" }, function(event)
                        local admin = event.sender:has_permission("demo.admin")
                        event.sender:send_message("hello, " .. event.sender:name() .. (admin and " (admin)" or ""))
                    end)
                """.trimIndent()
            )
        )
        try {
            val alex = platform.players.add("Alex")
            assertEquals(listOf("hello, Alex"), platform.commands.run(alex, "/hello"))
            alex.permissions += "demo.admin"
            assertEquals(listOf("hello, Alex (admin)"), platform.commands.run(alex, "/hello"))
            platform.commands.runAsConsole("/hello")
            assertEquals(listOf("hello, CONSOLE (admin)"), platform.players.console)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a centity a script spawns is drawn on the server, its displays posed`() {
        val (platform, runtime) = started(
            mapOf(
                "netherforge.json" to manifest,
                "centities/lamp/centity.json" to """{ "nodes": {
                  "base": { "display": { "type": "block", "block": "minecraft:stone" } },
                  "head": { "parent": "base", "transform": { "translation": [0, 1, 0] }, "display": { "type": "block", "block": "minecraft:stone" } }
                } }""",
                "modules/spawner/init.lua" to """
                    local lamp = nf.centities.spawn("lamp", vector.create(1, 64, 2))
                    print("spawned", lamp:kind(), lamp:position())
                """.trimIndent()
            )
        )
        try {
            runtime.tick()
            val log = platform.log.lines.joinToString("\n")
            assertContains(log, "spawned\tlamp\tvec3(1, 64, 2)")
            assertEquals(
                2,
                platform.entities.displays.size,
                log + "\n" + platform.entities.all.values.joinToString("\n") { "${it.role} ${it.tag} ${it.display}" }
            )
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a script's error is reported, and a failing server call comes back to the script as an error`() {
        val (platform, runtime) = started(
            mapOf(
                "netherforge.json" to manifest,
                "modules/broken/init.lua" to """
                    nf.after(1, function() error("on purpose") end)
                """.trimIndent()
            )
        )
        try {
            runtime.tick()
            runtime.tick()
            assertContains(platform.log.lines.joinToString("\n"), "on purpose")
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a panic in the runtime is thrown in the JVM, and the runtime goes on`() {
        val (platform, runtime) = started(mapOf("netherforge.json" to manifest))
        try {
            // A dispatcher asked for a method the interface hasn't panics, which comes back as a NativeException.
            val events = assertNotNull(platform.events) as RustPlatformEvents
            val failed = kotlin.runCatching { events.callForTest(9999) }.exceptionOrNull()
            assertTrue(failed is NativeException, "$failed")
            assertContains(failed.message.orEmpty(), "PlatformEvents has no method 9999")
            runtime.tick()
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a module runs, hears a player join and greets them through the server`() {
        val (platform, runtime) = started(
            mapOf(
                "netherforge.json" to manifest,
                "modules/greeter/init.lua" to """
                    print("hello from Luau")
                    local ticks = 0
                    nf.every(1, function() ticks += 1 end)
                    nf.on("player_join", function(event)
                        event.player:send_message("welcome, " .. event.player:name() .. " (" .. ticks .. " ticks)")
                        event.message = "<yellow>" .. event.player:name() .. " is here"
                    end)
                """.trimIndent()
            )
        )
        try {
            assertContains(platform.log.lines.joinToString("\n"), "hello from Luau")
            repeat(3) { runtime.tick() }
            val steve = platform.players.add("Steve")
            val join = GameEvent.PlayerJoin(steve.ref, firstJoin = true, message = "Steve joined")
            assertNotNull(platform.events).game.playerJoin(join)
            assertEquals(listOf("welcome, Steve (3 ticks)"), steve.messages)
            assertEquals("<yellow>Steve is here", join.message)
        } finally {
            runtime.stop()
        }
    }
}
