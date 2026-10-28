package dev.netherforge.plugin.binding

/** `no menu "x" in this project (did you mean "y"?)`: the error for a name the project doesn't have. */
internal fun notInProject(kind: String, name: String, known: List<String>): String {
    val close = known.minByOrNull { distance(it, name) }?.takeIf { distance(it, name) <= 2 }
    return "no $kind \"$name\" in this project" + close?.let { " (did you mean \"$it\"?)" }.orEmpty()
}

private fun distance(a: String, b: String): Int {
    val row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var previous = row[0]
        row[0] = i
        for (j in 1..b.length) {
            val saved = row[j]
            row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = saved
        }
    }
    return row[b.length]
}

/**
 * A format message as a script reads it: a file field named outside quotes
 * (`maxStackSize must be between 1 and 99`) in Lua's spelling (`max_stack_size`).
 * What's quoted is a value, and stays as it was.
 */
internal fun luaSpelling(message: String): String = message.split('"').mapIndexed { index, part ->
    if (index % 2 == 1) part else part.replace(Regex("\\b[a-z]+(?:[A-Z][a-z0-9]*)+\\b")) { snake(it.value) }
}.joinToString("\"")

private fun snake(key: String): String = key.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
