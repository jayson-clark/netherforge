package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** examples/showcase on the Rust runtime, through FFM, on the testkit's fake server: it starts with nothing wrong. */
class ShowcaseTest {
    @Test
    fun `the showcase starts and ticks on the Rust runtime with no script failing`() {
        Native.load(Path.of(System.getProperty("netherforge.ffi.library")))
        val showcase = Path.of(System.getenv("NETHERFORGE_REPO")).resolve("examples/showcase")
        val platform = FakePlatform()
        val runtime = NativeRuntime(platform)
        val plugin = Files.createTempDirectory("nf-ffi-showcase")
        val started = runtime.start(
            pluginFolder = plugin,
            serverFolder = plugin.parent,
            serverIp = "",
            settings = mapOf("project" to showcase.toString()),
            properties = emptyMap(),
            environment = emptyMap()
        )
        try {
            assertTrue(started, platform.log.lines.joinToString("\n"))
            repeat(40) { runtime.tick() }
            val wrong = platform.log.lines.filter { "WARN" in it || "ERROR" in it || "error" in it.lowercase() }
            assertEquals(emptyList(), wrong, platform.log.lines.joinToString("\n"))
        } finally {
            runtime.stop()
        }
    }
}
