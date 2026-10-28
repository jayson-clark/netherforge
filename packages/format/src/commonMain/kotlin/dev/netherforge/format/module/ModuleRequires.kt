package dev.netherforge.format.module

import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ProjectSource

/**
 * Which modules a project's Lua `require`s, read off the text: a `require("name")` or `require "name"` with a
 * literal name, outside a `--` comment. A name is the module whose id is its first part (`combat.damage` is
 * `combat`), unless the requiring file's own folder has it (a script's files beside it, a module's own files) or
 * it's the requiring module's own id. A name built at run time isn't seen, so this only ever says more modules
 * are unused than are.
 */
object ModuleRequires {
    private val REQUIRE = Regex("""\brequire\s*\(?\s*(["'])([A-Za-z0-9_.]+)\1""")

    /** The ids of the modules [source]'s `.lua` files require. */
    fun of(source: ProjectSource): Set<String> {
        val files = source.files().toSet()
        val required = HashSet<String>()
        for (path in files) {
            if (!path.endsWith(".lua")) continue
            val text = source.read(path) ?: continue
            val from = Kinds.classify(path)
            val folder = from?.rest?.takeIf { it.isNotEmpty() }?.let { path.dropLast(it.length + 1) }
            for (name in names(text)) {
                val joined = name.replace('.', '/')
                if (folder != null && ("$folder/$joined.lua" in files || "$folder/$joined/${ModuleKind.ENTRY}" in files)) continue
                val id = name.substringBefore('.')
                if (from?.kind == ModuleKind.id && from.id == id) continue
                required += id
            }
        }
        return required
    }

    /** The literal names [text] requires, in order. */
    fun names(text: String): List<String> =
        text.lineSequence().flatMap { line -> REQUIRE.findAll(line.substringBefore("--")).map { it.groupValues[2] } }.toList()
}
