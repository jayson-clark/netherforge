package dev.netherforge.plugin.screen
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.NfvFiles
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.ServerAddress
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.testkit.FakeScreens
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screens on the fake server: what each player's game is sent of them (and only what changed, within a tick's
 * budget), players' own views, the frames a screen hangs in and the clicks and aim they catch, map numbers kept
 * across restarts, videos with their sound, and text, shapes and pictures.
 *
 * Colours are the fake's made-up palette (`FakePlatform.MAP_COLORS`): its brightest shade of white is 10, of red 14.
 */
class ScreenTest {
    private val black = 4
    private val white = 10
    private val red = 14

    private fun FakeScreens.sentTo(player: java.util.UUID) = sent.filter { it.player == player }

    @Test
    fun `a screen's script draws it, and every player near it is sent only what changed`() {
        val files = mapOf(
            "screens/board/screen.json" to """{ "width": 2, "height": 1, "script": { "file": "script.lua" } }""",
            "screens/board/script.lua" to """
                this:clear("#ff0000")
                log(this:item(0, 0).map, this:item(1, 0).map, this:width(), this:height())
            """.trimIndent(),
            "modules/t/init.lua" to """nf.commands.register("dot", function() nf.screens.get("board"):set_pixel(130, 5, "#ffffff") end)"""
        )
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            val (left, right) = server.logs.single().split('\t').map { it.toInt() }
            assertEquals(Screens.MAP_BASE, left, "the first screen's first map")
            assertEquals("${left}\t${left + 1}\t256\t128", server.logs.single())
            server.tick()
            val screens = server.platform.screens
            assertEquals(red, screens.pixel(alex.ref.uuid, left, 0, 0))
            assertEquals(red, screens.pixel(alex.ref.uuid, right, 127, 127))

            screens.sent.clear()
            server.platform.commands.runConsole("dot")
            server.tick()
            assertEquals(listOf(FakeScreens.Sent(alex.ref.uuid, right, 2, 5, 1, 1)), screens.sent)
            assertEquals(white, screens.pixel(alex.ref.uuid, right, 2, 5))

            // Nothing changed: nothing sent.
            screens.sent.clear()
            server.tick(3)
            assertEquals(emptyList(), screens.sent)
        }
    }

    @Test
    fun `what doesn't fit a player's budget waits for the next tick`() {
        val files = mapOf(
            "modules/t/init.lua" to """
                local big = nf.screens.create({ width = 4, height = 4, background = "#ffffff" })
                log(big:item(3, 3).map)
            """.trimIndent()
        )
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            val last = server.logs.single().toInt()
            server.tick()
            val budget = ScreensConfig().bytesPerTick
            assertTrue(server.platform.screens.bytesTo(alex.ref.uuid) <= budget, "one tick's budget at most")
            assertTrue(server.platform.screens.bytesTo(alex.ref.uuid) > 0)
            server.tick(5)
            assertEquals(16 * 128 * 128, server.platform.screens.bytesTo(alex.ref.uuid), "every map once, whole")
            assertEquals(white, server.platform.screens.pixel(alex.ref.uuid, last, 127, 127))
        }
    }

    @Test
    fun `the smallest budget the owner may set still sends a whole map a tick`() {
        val files = mapOf("modules/t/init.lua" to """nf.screens.create({ width = 2, height = 1, background = "#ffffff" })""")
        val settings = mapOf("project" to "p", "screens" to mapOf("bytes-per-tick" to 1))
        val smallest = RuntimeConfig.read(
            Path.of("plugin").toAbsolutePath(),
            settings,
            ServerAddress(Path.of("srv").toAbsolutePath(), "")
        )!!.screens
        assertEquals(ScreensConfig.MIN_BYTES_PER_TICK, smallest.bytesPerTick)
        TestServer(files, start = false, screens = smallest).use { server ->
            val alex = server.player("Alex")
            server.start()
            server.tick()
            assertEquals(128 * 128, server.platform.screens.bytesTo(alex.ref.uuid), "one whole map in the first tick")
            server.tick()
            assertEquals(2 * 128 * 128, server.platform.screens.bytesTo(alex.ref.uuid), "and the other in the next")
        }
    }

    @Test
    fun `a player's own view is theirs alone, until it's released`() {
        val files = mapOf(
            "modules/t/init.lua" to LuaChecks.command(
                """
                local screen = nf.screens.create({ width = 1, height = 1, key = "own" })
                screen:clear("#000000")
                local bob = nf.players.get("Bob")
                local mine = screen:for_player(bob)
                check("same view", screen:for_player(bob), mine)
                check("whose", mine:player(), bob)
                check("everyone's", screen:player(), nil)
                mine:clear("#ff0000")
                check("bob sees red", mine:pixel(0, 0), nf.screens.map_color("#ff0000"))
                check("the rest see black", screen:pixel(0, 0), "#000000")
                fails("another's view", function() mine:for_player(nf.players.get("Alex")) end, "another player's own view")
                log(screen:item().map)
                """.trimIndent()
            ),
            "modules/u/init.lua" to
                """nf.commands.register("release", function() log(nf.screens.all()[1]:for_player(nf.players.get("Bob")):release()) end)"""
        )
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            val bob = server.player("Bob")
            server.start()
            val output = server.runChecks()
            val map = output.first().toInt()
            assertEquals(LuaChecks.DONE, output.drop(1))
            server.tick()
            assertEquals(black, server.platform.screens.pixel(alex.ref.uuid, map, 3, 3))
            assertEquals(red, server.platform.screens.pixel(bob.ref.uuid, map, 3, 3))
            server.platform.commands.runConsole("release")
            assertEquals("true", server.logs.last())
            server.tick()
            assertEquals(black, server.platform.screens.pixel(bob.ref.uuid, map, 3, 3), "back to what everyone sees")
        }
    }

    @Test
    fun `a hung screen catches clicks on its frames and from afar, and players' aim`() {
        val files = mapOf(
            "screens/wall/screen.json" to """
                { "width": 2, "height": 1, "place": { "world": "world", "x": 0, "y": 65, "z": 5, "facing": "north" },
                  "script": { "file": "script.lua" } }
            """.trimIndent(),
            "screens/wall/script.lua" to """
                this:on("click", function(event) log("click", event.x, event.y, event.click, event.player:name()) end)
                this:on("hover", function(event) log("hover", event.x, event.y) end)
                this:on("leave", function(event) log("leave", event.player:name()) end)
            """.trimIndent()
        )
        TestServer(files, start = false).use { server ->
            // In front of the left map (as they face it), looking south at it from 4.4 blocks away.
            val alex = server.player("Alex")
            alex.location = Location("world", 0.75, 64.0, 0.6, yaw = 0.0, pitch = 0.0)
            server.start()
            server.tick()
            val frames = server.platform.screens.frames
            assertEquals(2, frames.size, "a frame a map")
            assertTrue(frames.values.all { it.facing.name == "NORTH" && it.glowing })
            assertEquals(listOf("hover\t32\t48"), server.logs.filter { it.startsWith("hover") }, "aimed at the left map")

            val frame = frames.keys.first()
            val sight = server.platform.players.eye(alex.ref.uuid)
            assertTrue(server.runtime.events.entityClicked(frame, alex.ref, ClickButton.RIGHT, sight))
            assertEquals("click\t32\t48\tright\tAlex", server.logs.last())

            // From afar, past the game's reach: a swing aimed at it.
            server.tick()
            alex.location = alex.location.copy(z = -10.0)
            assertTrue(server.runtime.events.playerInteract(alex.ref, ClickButton.LEFT, null, null, null, "main_hand"))
            assertEquals("click\t32\t48\tleft\tAlex", server.logs.last())

            // Looking away: a leave, and a swing that's the world's.
            alex.location = alex.location.copy(yaw = 180.0)
            server.tick()
            assertEquals("leave\tAlex", server.logs.last())
            assertTrue(!server.runtime.events.playerInteract(alex.ref, ClickButton.LEFT, null, null, null, "main_hand"))

            // Taken down by a reload that moves it nowhere: its frames go.
            server.write("screens/wall/screen.json", """{ "width": 2, "height": 1 }""")
            server.reload("screens/wall/screen.json")
            server.tick()
            assertTrue(server.platform.entities.all.values.none { it.role.name == "SCREEN" }, "no frames left")
        }
    }

    @Test
    fun `a project screen and a keyed one keep their maps across a restart`() {
        val files = mapOf(
            "screens/board/screen.json" to """{ "width": 1, "height": 1 }""",
            "modules/t/init.lua" to """
                local keyed = nf.screens.create({ width = 1, height = 1, key = "scores" })
                local loose = nf.screens.create({ width = 1, height = 1 })
                log(nf.screens.get("board"):item().map, keyed:item().map, loose:item().map, keyed:name())
            """.trimIndent()
        )
        TestServer(files).use { server ->
            val first = server.logs.single().split('\t')
            assertEquals("scores", first[3])
            server.restart()
            val second = server.logs.last().split('\t')
            assertEquals(first[0], second[0], "the project's screen")
            assertEquals(first[1], second[1], "the keyed one")
            assertTrue(second[2] != first[1] && second[2] != first[0], "a loose one never takes a kept number")
        }
    }

    @Test
    fun `a keyed screen a script removes gives its numbers up, and no number is ever given twice`() {
        val files = mapOf(
            "modules/t/init.lua" to """
                local loose = nf.screens.create({ width = 1, height = 1 })
                local keyed = nf.screens.create({ width = 1, height = 1, key = "round" })
                log(loose:item().map, keyed:item().map)
                nf.commands.register("again", function()
                  local before = keyed:item().map
                  keyed:remove()
                  keyed = nf.screens.create({ width = 1, height = 1, key = "round" })
                  log(before, keyed:item().map)
                end)
            """.trimIndent()
        )
        TestServer(files).use { server ->
            val (loose, keyed) = server.logs.single().split('\t').map { it.toInt() }
            server.platform.commands.runConsole("again")
            val (before, after) = server.logs.last().split('\t').map { it.toInt() }
            assertEquals(keyed, before)
            assertTrue(after > keyed, "made again, it has new numbers")
            server.tick()
            server.runtime.saveData()
            assertEquals(1, server.runtime.store.screenMaps.all().size, "the given-up key keeps nothing")
            server.restart()
            val (looseAgain, keyedAgain) = server.logs.last().split('\t').map { it.toInt() }
            assertEquals(after, keyedAgain, "the key's numbers now are its new ones")
            assertTrue(looseAgain > after && looseAgain != loose, "a loose screen's number isn't given again after a restart")
        }
    }

    @Test
    fun `a video plays a frame a tick, loops, pauses, seeks and ends, with its sound`() {
        // Six seconds at 20 frames a second, alternating black and white.
        val frames = NfvFiles.solid(120, black, white)
        val files = mapOf(
            "videos/clip/video.json" to """{ "source": "source.mp4", "width": 1, "height": 1 }""",
            "videos/clip/video.nfv" to NfvFiles.write(1, 1, 20, frames, keyInterval = 10, audio = true),
            "videos/clip/audio.ogg" to byteArrayOf(1),
            "videos/clip/audio/0.ogg" to byteArrayOf(1),
            "videos/clip/audio/1.ogg" to byteArrayOf(1),
            "screens/tv/screen.json" to """{ "width": 1, "height": 1 }""",
            "modules/t/init.lua" to """
                local tv = nf.screens.get("tv")
                local show
                nf.commands.register("play", function()
                  show = tv:play("clip")
                  show:on("end", function(event) log("end", event.reason) end)
                  log("length", show:length(), show:video(), show:screen() == tv)
                end)
                nf.commands.register("pause", function() log("pause", show:pause(), show:is_paused()) end)
                nf.commands.register("resume", function() log("resume", show:resume()) end)
                nf.commands.register("seek", function() log("seek", show:seek(4.9)) end)
                nf.commands.register("missing", function() log(pcall(tv.play, tv, "nope")) end)
            """.trimIndent()
        )
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            val sounds = server.platform.sounds
            server.platform.commands.runConsole("play")
            assertEquals("length\t6.0\tclip\ttrue", server.logs.last())
            assertEquals(listOf("Alex test:video.clip record 1.0 1.0"), sounds.played, "the whole sound, from the start")
            server.tick()
            val map = Screens.MAP_BASE
            assertEquals(white, server.platform.screens.pixel(alex.ref.uuid, map, 0, 0), "frame 1")
            server.tick()
            assertEquals(black, server.platform.screens.pixel(alex.ref.uuid, map, 0, 0), "frame 2")

            server.platform.commands.runConsole("pause")
            assertEquals("pause\ttrue\ttrue", server.logs.last())
            assertTrue("Alex test:video.clip" in sounds.stopped, "paused, silent")
            server.tick(3)
            assertEquals(black, server.platform.screens.pixel(alex.ref.uuid, map, 0, 0), "held")

            // Moved through, its sound picks up at the next piece: the one starting at 5 seconds.
            server.platform.commands.runConsole("resume")
            server.platform.commands.runConsole("seek")
            sounds.played.clear()
            server.tick(3)
            assertEquals(listOf("Alex test:video.clip.1 record 1.0 1.0"), sounds.played)

            server.tick(30)
            assertEquals("end\tfinished", server.logs.last())

            server.platform.commands.runConsole("missing")
            assertTrue("no video \"nope\"" in server.logs.last(), server.logs.last())
        }
    }

    @Test
    fun `frames converted for other settings than the video's file are video_stale, until they match`() {
        val files = mapOf(
            "videos/clip/video.json" to """{ "source": "source.mp4", "width": 2, "height": 1, "audio": false }""",
            "videos/clip/video.nfv" to NfvFiles.write(1, 1, 20, NfvFiles.solid(2, black))
        )
        TestServer(files).use { server ->
            val stale = server.runtime.session.problems().single { it.code == "video.stale" }
            assertEquals("videos/clip/video.json", stale.file)
            assertTrue("1 by 1 maps" in stale.message, stale.message)
            server.write("videos/clip/video.json", """{ "source": "source.mp4", "width": 1, "height": 1, "audio": false }""")
            server.reload("videos/clip/video.json")
            assertTrue(server.runtime.session.problems().none { it.code == "video.stale" })
        }
    }

    @Test
    fun `text, shapes and pictures are drawn in map colours`() {
        val picture = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFFFF0000.toInt())
            setRGB(1, 0, 0x00000000)
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(picture, "png", it) }.toByteArray()
        val body = """
            local screen = nf.screens.create({ width = 1, height = 1, background = "#000000" })
            local size = screen:text(0, 0, "A")
            check("text width", size.width, 5)
            check("text height", size.height, 7)
            check("its border", screen:pixel(0, 0), "#ffffff")
            check("its hollow", screen:pixel(2, 3), "#000000")
            local two = nf.screens.measure_text("AB", { scale = 2 })
            check("measured", two.width, 22)
            screen:rectangle(10, 10, 10, 10, { fill = "#ff0000", outline = "#ffffff" })
            check("outline", screen:pixel(10, 10), "#ffffff")
            check("fill", screen:pixel(15, 15), nf.screens.map_color("#ff0000"))
            screen:circle(60, 60, 5, { fill = "#ffffff" })
            check("circle middle", screen:pixel(60, 60), "#ffffff")
            check("circle outside", screen:pixel(66, 60), "#000000")
            screen:line(0, 100, 20, 100, "#ffffff", 3)
            check("line", screen:pixel(10, 101), "#ffffff")
            screen:polygon({ 100, 0, 127, 0, 127, 27 }, { fill = "#ffffff" })
            check("polygon", screen:pixel(120, 5), "#ffffff")
            check("drawn", screen:image(40, 40, "ui/logo", { dither = "none" }), true)
            check("picture", screen:pixel(40, 40), nf.screens.map_color("#ff0000"))
            check("transparent pixel draws nothing", screen:pixel(41, 40), "#000000")
            screen:copy(40, 40, 1, 1, 0, 120)
            check("copied", screen:pixel(0, 120), nf.screens.map_color("#ff0000"))
            screen:set_pixels(0, 125, 2, { "#ffffff", "clear" })
            local row = screen:pixels(0, 125, 2, 1)
            check("set pixels", row[1] .. " " .. row[2], "#ffffff clear")
            fails("a bad colour", function() screen:fill(0, 0, 1, 1, "red") end, "isn't a colour")
            fails("no style", function() screen:circle(0, 0, 1, {}) end, "a fill, an outline or both")
            fails("an unknown font", function() screen:text(0, 0, "x", { font = "nope" }) end, "no screen font \"nope\"")
            fails("no such picture", function() screen:image(0, 0, "ui/nope") end, "has no texture")
            fails("too big", function() nf.screens.create({ width = 17, height = 1 }) end, "1 to 16 maps")
            check("fonts", table.concat(nf.screens.fonts(), ","), "default")
        """.trimIndent()
        val output = LuaChecks.run(
            body,
            mapOf("resource_packs/ui/pack.json" to "{}", "resource_packs/ui/textures/logo.png" to png)
        ) { it.player("Alex") }
        assertEquals(LuaChecks.DONE, output)
    }

    @Test
    fun `a project screen can't be removed, and a script's goes with its script`() {
        val files = mapOf(
            "screens/board/screen.json" to """{ "width": 1, "height": 1 }""",
            "modules/t/init.lua" to LuaChecks.command(
                """
                fails("the project's", function() nf.screens.get("board"):remove() end, "it can't be removed")
                fails("not there", function() nf.screens.get("nope") end, "no screen \"nope\"")
                local mine = nf.screens.create({ width = 1, height = 1 })
                check("there", mine:exists(), true)
                check("removed", mine:remove(), true)
                check("gone", mine:exists(), false)
                check("twice", mine:remove(), false)
                """.trimIndent()
            )
        )
        TestServer(files).use { server ->
            assertEquals(LuaChecks.DONE, server.runChecks())
        }
    }

    @Test
    fun `the showcase's video booth, a player presses Play on the controls, and the video plays`() {
        TestServer(TestServer.example("showcase"), start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.severity == dev.netherforge.format.Severity.ERROR })
            server.platform.raise.playerJoin(dev.netherforge.plugin.platform.GameEvent.PlayerJoin(alex.ref, true, null))
            // The expo builds its booths a step at a time from a second after it loads (how many ticks that takes goes by
            // how long each step took), and the video booth hangs its two screens on its back wall.
            val screens = server.runtime.session.screens
            var waited = 0
            while (screens.find("cinema")!!.placement == null || screens.find("controls")!!.placement == null) {
                server.tick()
                assertTrue(++waited < 20 * 60, "the video booth wasn't built within a minute")
            }
            assertEquals(null, screens.find("cinema")!!.playbacks[null])

            // The video booth is the 13th: west of the avenue, its row starting at z 104. Its controls (2 by 2) hang facing
            // east with their top-left map at -16 68 114. Stand in the booth, aim at the middle of Play (pixel 68, 84) and swing.
            alex.location = Location("world", -9.5, 65.0, 113.5)
            // Screens show to whoever is near: a moment for the booth's to find Alex there.
            server.tick(20)
            val target = doubleArrayOf(-15.9375, 69.0 - 84.0 / 128, 115.0 - 68.0 / 128)
            val eye = doubleArrayOf(-9.5, 65.0 + 1.62, 113.5)
            val d = DoubleArray(3) { target[it] - eye[it] }
            val length = kotlin.math.sqrt(d.sumOf { it * it })
            val yaw = Math.toDegrees(kotlin.math.atan2(-d[0], d[2]))
            val pitch = -Math.toDegrees(kotlin.math.asin(d[1] / length))
            alex.location = alex.location.copy(yaw = yaw, pitch = pitch)
            assertTrue(server.runtime.events.playerInteract(alex.ref, ClickButton.LEFT, null, null, null, "main_hand"))
            val show = assertNotNullOrFail(screens.find("cinema")!!.playbacks[null])
            assertEquals("intro", show.video)
            assertEquals(60, show.header.frames)
            server.tick(100)
            assertEquals(show, screens.find("cinema")!!.playbacks[null], "still playing at five seconds")
            server.tick(30)
            // Sixty frames at 10 a second is six seconds: done, and the poster's back.
            assertEquals(null, screens.find("cinema")!!.playbacks[null])
        }
    }

    private fun <T : Any> assertNotNullOrFail(value: T?): T = value ?: throw AssertionError("expected a value")
}
