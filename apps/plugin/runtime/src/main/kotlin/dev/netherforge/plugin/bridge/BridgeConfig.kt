package dev.netherforge.plugin.bridge
import java.nio.file.Path

data class BridgeConfig(
    val port: Int,
    val token: String,
    /**
     * How long a dev server keeps running without its editor before it stops
     * itself. Every editor run listens on a new port, so a server whose
     * editor quit, crashed or was killed can never reconnect: without this it
     * would hold the port (and the world) until someone found and killed it.
     */
    val abandonAfterMillis: Long = DEFAULT_ABANDON_AFTER_MILLIS,
    /**
     * The server's folder, which the editor started it in: paths the bridge
     * answers with (`save_world`) are relative to it, because the editor
     * reads files from its dev server's folder and nowhere else.
     */
    val serverDirectory: Path = Path.of("").toAbsolutePath()
) {
    companion object {
        const val DEFAULT_ABANDON_AFTER_MILLIS = 15_000L
    }
}
