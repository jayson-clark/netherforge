package dev.netherforge.format.codegen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity

/**
 * `docs/format/problems.md`: every [ProblemCodes] entry, one heading each
 * (its [dev.netherforge.format.ProblemCode.anchor]), with its severity, what
 * it means and the page that explains the rule. Written by the golden test
 * (`UPDATE_GOLDEN=1 pnpm test`), never by hand.
 */
object ProblemCatalogue {
    const val PATH = "docs/format/problems.md"

    fun render(): String = buildString {
        append("<!-- Generated from format's ProblemCodes by its golden test (UPDATE_GOLDEN=1 pnpm test). Do not edit. -->\n\n")
        append("# Problems\n\n")
        append("Every problem NetherForge reports has a stable code: the editor shows it beside the message, ")
        append("and `netherforge check` prints it. An error stops the resource from running (the server keeps ")
        append("its last good version); a warning doesn't. Each problem points at a file, and at the value in it ")
        append("when it's about one; a broken reference also points at its other end.\n\n")
        for ((area, codes) in ProblemCodes.all.groupBy { it.code.substringBefore('.') }) {
            append("## ${AREAS[area] ?: area}\n\n")
            for (code in codes) {
                append("### `${code.code}` {#${code.anchor}}\n\n")
                val severity = if (code.severity == Severity.ERROR) "Error" else "Warning"
                append("$severity. ${code.summary} See [${title(code.page)}](${link(code.page)}).\n\n")
            }
        }
    }.trimEnd() + "\n"

    /** The catalogue lives in `docs/format/`; [page] is relative to `docs/`. */
    private fun link(page: String) = if (page.startsWith("format/")) page.removePrefix("format/") else "../$page"

    private fun title(page: String) = TITLES[page] ?: page

    private val AREAS = mapOf(
        "parse" to "Reading files",
        "project" to "The project",
        "font" to "The default font",
        "reference" to "References",
        "package" to "Packages",
        "lock" to "The lock file",
        "script" to "Scripts",
        "module" to "Modules",
        "centity" to "Centities",
        "menu" to "Menus",
        "dialog" to "Dialogs",
        "item" to "Items",
        "recipe" to "Recipes",
        "loot" to "Loot tables",
        "block" to "Blocks",
        "advancement" to "Advancements",
        "biome" to "Biomes",
        "particle" to "Particle effects",
        "cutscene" to "Cutscenes",
        "resource_pack" to "Resource packs",
        "runtime" to "The server",
        "particles" to "Particles on the server"
    )

    private val TITLES = mapOf(
        "format/project.md" to "the project format",
        "format/references.md" to "references",
        "format/packages.md" to "packages",
        "format/centity.md" to "centities",
        "format/menu.md" to "menus",
        "format/dialog.md" to "dialogs",
        "format/item.md" to "items",
        "format/recipe.md" to "recipes",
        "format/loot.md" to "loot tables",
        "format/block.md" to "blocks",
        "format/advancement.md" to "advancements",
        "format/biome.md" to "biomes",
        "format/particle-effect.md" to "particle effects",
        "format/cutscene.md" to "cutscenes",
        "format/resource-pack.md" to "resource packs",
        "guide/dev-loop.md" to "the dev loop"
    )
}
