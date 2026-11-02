package dev.netherforge.plugin.world

import java.nio.file.Path

/** `worlds:` in `config.yml`: where `nf.worlds.import` may take worlds from, and how many may be loaded at once. */
data class WorldsConfig(
    /** The folder imported worlds come from (a tool puts them there); null: importing is off. */
    val importRoot: Path? = null,
    /** The most worlds loaded at once, the server's own included; 0 for any number. */
    val maxLoaded: Int = 0
)
