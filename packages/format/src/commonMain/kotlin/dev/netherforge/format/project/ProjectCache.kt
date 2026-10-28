package dev.netherforge.format.project

/**
 * What [Projects.load] keeps between loads of one project, so that a load
 * after an edit reads and validates only the files that changed.
 *
 * Each entry is one file read and validated on its own: a resource (by its
 * main file, or its folder for a kind without one) as [KindSpec.validate]
 * left it, or a project document (`netherforge.json`, `fonts/default.json`).
 * An entry is kept while everything it was worked out from is equal to what
 * it was: the file's text (its content, compared whole, so an unchanged file
 * is never read again and a changed one always is), the files in the
 * resource's folder, the game data and the target version. Everything that
 * looks across files (references, [KindSpec.crossCheck]) is done again on
 * every load, from the kept entries. A file that's gone is forgotten by the
 * next load.
 *
 * One cache is one project: the editor keeps one per open project (in its
 * validation worker), the plugin and the CLI load once without one. A
 * dependency package keeps its entries apart, under [scope] (its namespace).
 */
class ProjectCache {
    private class Entry(val inputs: List<Any?>, val result: Any?)

    private val entries = HashMap<String, Entry>()
    private val scopes = HashMap<String, ProjectCache>()
    private var used = HashSet<String>()
    private var fresh = mutableListOf<String>()

    /**
     * The files the last load read and validated afresh, in the order it
     * did: every file on the first load, then only those whose entry was
     * missing or out of date.
     */
    var validated: List<String> = emptyList()
        private set

    /** The cache for the files of [name] (a dependency package), kept apart from this one's. */
    fun scope(name: String): ProjectCache = scopes.getOrPut(name, ::ProjectCache)

    /** Forgets the packages the project no longer depends on. */
    internal fun retainScopes(names: Set<String>) {
        scopes.keys.retainAll(names)
    }

    internal fun begin() {
        used = HashSet()
        fresh = mutableListOf()
    }

    /** What [compute] answered for [file] the last time [inputs] were these, or its answer now. */
    internal fun <V> keep(file: String, vararg inputs: Any?, compute: () -> V): V {
        used += file
        val key = inputs.toList()
        val entry = entries[file]
        @Suppress("UNCHECKED_CAST")
        if (entry != null && entry.inputs == key) return entry.result as V
        fresh += file
        return compute().also { entries[file] = Entry(key, it) }
    }

    internal fun end() {
        entries.keys.retainAll(used)
        validated = fresh.toList()
    }
}
