package dev.netherforge.plugin.pack

/**
 * Where the resource pack is served from (`resource-pack:` in `config.yml`).
 *
 * By default the plugin serves it itself; [externalUrl] says the owner serves
 * it instead, and the plugin writes the zip for them to upload.
 */
data class PackConfig(
    /** Off: nothing is sent to players (the pack is still built, so glyphs and skins still resolve). */
    val enabled: Boolean = true,
    /** The address the built-in server listens on. */
    val bind: String = "127.0.0.1",
    /** Its port; 0 picks a free one. */
    val port: Int = 0,
    /** The base URL players fetch from, when it isn't `http://<bind>:<port>` (a public host name, a proxy). */
    val publicUrl: String? = null,
    /** Serve it yourself from this URL: the plugin writes `resource-pack.zip` to its folder and sends this with its hash. */
    val externalUrl: String? = null,
    /** Players who decline it are disconnected. */
    val required: Boolean = false,
    /** MiniMessage shown on the client's prompt. */
    val prompt: String? = null
)
