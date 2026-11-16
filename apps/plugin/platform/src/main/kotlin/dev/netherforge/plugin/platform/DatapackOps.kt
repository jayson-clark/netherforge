package dev.netherforge.plugin.platform

import dev.netherforge.format.datapack.GameDatapack
/**
 * The start-up datapack (format's `StartupDatapack`): what the server loaded
 * as the project's while it started, and what building one again needs. The
 * adapter builds it before the worlds load, from the project's files alone
 * ([dev.netherforge.plugin.datapack.StartupDatapackFiles]), and the runtime
 * builds it again after a reload to tell whether the server must restart.
 */
interface DatapackOps {
    /**
     * The datapack the server loaded as it started, its files' bytes by path:
     * empty when it loaded none (the project put nothing in one, or there was
     * no project to read).
     */
    val started: Map<String, ByteArray>

    /**
     * The refusal on record the server started without the project's datapacks for
     * ([dev.netherforge.plugin.datapack.DatapackRefusal]: it refused [started] with them the last time); null when it
     * started with them, as it does unless they refused it.
     */
    val refused: dev.netherforge.plugin.datapack.DatapackRefusal?

    /** The server's data pack format, `[major, minor]` (from the game's `version.json`); null when it doesn't say. */
    val format: List<Int>?

    /**
     * The name of the world the server makes as it starts, its main world (`level-name`), as the adapter's start-up
     * knew it when it built [started]: whose dimension type the pack replaces when `netherforge.json` names one for
     * it. Null when the adapter can't say.
     */
    val mainWorld: String?

    /** The game's own data pack, what the start-up datapack builds on (a sky's twins of its biomes); none by default. */
    val game: GameDatapack get() = GameDatapack.NONE

    /**
     * MiniMessage [text] as the game's JSON text component, in the form this
     * server's datapacks read, `<glyph:…>` tags drawn as [glyph] answers
     * their reference (nothing for null). Any thread: the adapter's start-up
     * calls it before there's a main thread to speak of.
     */
    fun textJson(text: String, glyph: (reference: String) -> String?): String
}
