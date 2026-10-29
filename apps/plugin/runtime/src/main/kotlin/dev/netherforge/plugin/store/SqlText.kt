package dev.netherforge.plugin.store

/**
 * What a script's SQL text may be: exactly one statement. JDBC runs the
 * first statement of a text and quietly drops the rest, so a second one
 * (`...; DROP TABLE x`) would be ignored rather than refused, which no one
 * wants to find out later: it's refused here, saying to use a transaction.
 */
internal object SqlText {
    /** Why [sql] isn't one statement, or null when it is: a `;` ends it, and only whitespace and comments may follow. */
    fun problem(sql: String): String? {
        var ended = false
        var content = false

        fun meets(): String? {
            if (ended) return "it has more than one statement: give one statement per call (a transaction takes several)"
            content = true
            return null
        }

        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == ';' -> {
                    if (!content) return "it is empty"
                    ended = true
                    i++
                }
                c.isWhitespace() -> i++
                c == '-' && sql.startsWith("--", i) -> i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                c == '/' && sql.startsWith("/*", i) -> i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    meets()?.let { return it }
                    val close = if (c == '[') ']' else c
                    // A doubled quote inside is two strings side by side: the same to a scan that only looks for the end.
                    i = sql.indexOf(close, i + 1).let { if (it < 0) sql.length else it + 1 }
                }
                else -> {
                    meets()?.let { return it }
                    i++
                }
            }
        }
        return if (content) null else "it is empty"
    }

    /**
     * Why [sql] can't run on a package's own SQLite database, or null: a
     * statement that reaches another file. `ATTACH` (another package's
     * database, the runtime's store, a new file anywhere), `VACUUM` (`VACUUM
     * INTO` writes a copy anywhere; the engine runs every `VACUUM` through an
     * attach, which the connection's `SQLITE_LIMIT_ATTACHED` of 0 refuses too)
     * and the pragmas that name a folder on the server's disk. Told by the
     * statement's first words, as [problem] has made sure there's one.
     */
    fun sqliteProblem(sql: String): String? {
        val words = leadingWords(sql, 4)
        val first = words.firstOrNull()?.lowercase() ?: return null
        if (first in OTHER_FILES) {
            return "${first.uppercase()} isn't allowed: a package's database is one file, and this would reach another"
        }
        if (first == "pragma") {
            // `PRAGMA name`, or `PRAGMA schema.name`.
            val name = (if (words.getOrNull(2) == ".") words.getOrNull(3) else words.getOrNull(1))?.lowercase() ?: return null
            if (name in FOLDER_PRAGMAS) return "PRAGMA $name isn't allowed: it names a folder on the server's disk"
        }
        return null
    }

    /** Up to [count] of [sql]'s first words (identifiers, and `.` between them), past whitespace and comments. */
    private fun leadingWords(sql: String, count: Int): List<String> {
        val words = ArrayList<String>()
        var i = 0
        while (i < sql.length && words.size < count) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && sql.startsWith("--", i) -> i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                c == '/' && sql.startsWith("/*", i) -> i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                c == '.' -> {
                    words += "."
                    i++
                }
                c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    val end = sql.indexOf(close, i + 1).let { if (it < 0) sql.length else it }
                    words += sql.substring(i + 1, end)
                    i = end + 1
                }
                c.isLetterOrDigit() || c == '_' -> {
                    val start = i
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                    words += sql.substring(start, i)
                }
                else -> return words
            }
        }
        return words
    }

    private val OTHER_FILES = setOf("attach", "detach", "vacuum")
    private val FOLDER_PRAGMAS = setOf("temp_store_directory", "data_store_directory")
}
