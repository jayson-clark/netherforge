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
}
