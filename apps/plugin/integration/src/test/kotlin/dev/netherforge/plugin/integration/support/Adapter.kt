package dev.netherforge.plugin.integration.support

import dev.netherforge.format.game.MinecraftVersion
import java.nio.file.Path

/**
 * What this run is against, from the system properties `integrationTest-<minecraft>`
 * sets (see the integration project's build file): one adapter's jars and its
 * Paper server.
 */
object Adapter {
    private fun property(name: String): String = System.getProperty("netherforge.it.$name")
        ?: error("missing -Dnetherforge.it.$name; run through Gradle (integrationTest-<minecraft>)")

    /** The adapter's Minecraft version, `26.3`. */
    val minecraft: String get() = property("minecraft")

    /** Since 26.1 a world is a dimension inside the main world's storage; before, a folder of its own. */
    val worldsAreDimensions: Boolean get() = MinecraftVersion.of(minecraft) >= MinecraftVersion.of("26.1")

    val java: String get() = property("java")
    val plugin: Path get() = Path.of(property("plugin"))
    val contract: Path get() = Path.of(property("contract"))
    val paper: Path get() = Path.of(property("paper"))

    /** The server's folder: Paper's downloads are kept there between runs, the world isn't. */
    val server: Path get() = Path.of(property("server"))

    val example: Path get() = Path.of(property("example"))

    /** `src/test/fixtures`: files scenarios lay over the example. */
    val fixtures: Path get() = Path.of(property("fixtures"))
}
