package dev.netherforge.format.codegen

import dev.netherforge.format.game.FeatureTable
import dev.netherforge.format.game.MinecraftVersion

/**
 * `docs/format/features.md`: [FeatureTable.CURRENT] as a table, one row per feature (its id, the version it
 * arrived in, what it is). Written by the golden test (`UPDATE_GOLDEN=1 pnpm test`), never by hand; the Rust port
 * reads it (`crates/format/build.rs`), so there's one table.
 */
object FeatureCatalogue {
    const val PATH = "docs/format/features.md"

    fun render(): String = buildString {
        append("<!-- Generated from format's FeatureTable by its golden test (UPDATE_GOLDEN=1 pnpm test). Do not edit. -->\n\n")
        append("# Features by Minecraft version\n\n")
        append("A project may target any Minecraft version from ${MinecraftVersion.OLDEST_SUPPORTED} on (`minecraft` in ")
        append("`netherforge.json`). What NetherForge does that an older version can't is listed here with the version it ")
        append("needs: a file that uses it in a project for an older version is the problem ")
        append("[`project.feature`](problems.md#project-feature), and a script that does gets an error.\n\n")
        append("| Feature | Needs Minecraft | What it is |\n")
        append("| - | - | - |\n")
        for (feature in FeatureTable.CURRENT.all) {
            append("| `${feature.id}` | ${feature.since} | ${feature.what} |\n")
        }
    }
}
