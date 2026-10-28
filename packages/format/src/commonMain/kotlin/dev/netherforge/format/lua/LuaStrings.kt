package dev.netherforge.format.lua

/** The string literals of Lua source, read as Lua reads them: comments skipped, escapes and long brackets understood. */
object LuaStrings {
    /** Every string literal in [source], in order, with its escapes worked out (a `\z`, `\x`, `\u` or decimal one too). */
    fun literals(source: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '-' && i + 1 < n && source[i + 1] == '-' -> {
                    val level = longOpen(source, i + 2)
                    i =
                        if (level >=
                            0
                        ) {
                            longClose(source, i + 2 + level + 2, level).second
                        } else {
                            source.indexOf('\n', i).let { if (it < 0) n else it }
                        }
                }
                c == '[' && longOpen(source, i) >= 0 -> {
                    val level = longOpen(source, i)
                    var start = i + level + 2
                    // A newline straight after the opening bracket isn't part of the string.
                    if (start < n && source[start] == '\r') start++
                    if (start < n && source[start] == '\n') start++
                    val (text, next) = longClose(source, start, level)
                    out += text
                    i = next
                }
                c == '"' || c == '\'' -> {
                    val (text, next) = quoted(source, i + 1, c)
                    out += text
                    i = next
                }
                else -> i++
            }
        }
        return out
    }

    /** A token of Lua source as [tokens] reads it: a name (or a number), a string literal's text, or one punctuation character. */
    data class Token(val kind: Kind, val text: String, val line: Int) {
        enum class Kind { NAME, STRING, PUNCT }
    }

    /**
     * [source] as tokens, enough to follow calls: names (keywords and numbers too), string literals
     * with their escapes worked out, and every other character on its own (`..` is two `.`). Comments
     * and whitespace are skipped. Each token has its 1-based line.
     */
    fun tokens(source: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        var line = 1
        val n = source.length
        fun advance(to: Int) {
            for (k in i until minOf(to, n)) if (source[k] == '\n') line++
            i = to
        }
        while (i < n) {
            val c = source[i]
            when {
                c == '-' && i + 1 < n && source[i + 1] == '-' -> {
                    val level = longOpen(source, i + 2)
                    advance(
                        if (level >= 0) {
                            longClose(source, i + 2 + level + 2, level).second
                        } else {
                            source.indexOf('\n', i).let { if (it < 0) n else it }
                        }
                    )
                }
                c == '[' && longOpen(source, i) >= 0 -> {
                    val at = line
                    val level = longOpen(source, i)
                    var start = i + level + 2
                    if (start < n && source[start] == '\r') start++
                    if (start < n && source[start] == '\n') start++
                    val (text, next) = longClose(source, start, level)
                    out += Token(Token.Kind.STRING, text, at)
                    advance(next)
                }
                c == '"' || c == '\'' -> {
                    val at = line
                    val (text, next) = quoted(source, i + 1, c)
                    out += Token(Token.Kind.STRING, text, at)
                    advance(next)
                }
                c.isLetterOrDigit() || c == '_' -> {
                    var j = i
                    while (j < n && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    out += Token(Token.Kind.NAME, source.substring(i, j), line)
                    i = j
                }
                c.isWhitespace() -> advance(i + 1)
                else -> {
                    out += Token(Token.Kind.PUNCT, c.toString(), line)
                    i++
                }
            }
        }
        return out
    }

    /** The level of a long bracket opening at [at] (`[[` is 0, `[==[` 2), or -1 when there's none. */
    private fun longOpen(source: String, at: Int): Int {
        if (at >= source.length || source[at] != '[') return -1
        var j = at + 1
        while (j < source.length && source[j] == '=') j++
        return if (j < source.length && source[j] == '[') j - at - 1 else -1
    }

    /** The text from [start] to the closing long bracket of [level], and where reading carries on after it. */
    private fun longClose(source: String, start: Int, level: Int): Pair<String, Int> {
        val close = "]" + "=".repeat(level) + "]"
        val end = source.indexOf(close, start)
        return if (end <
            0
        ) {
            source.substring(minOf(start, source.length)) to source.length
        } else {
            source.substring(start, end) to end + close.length
        }
    }

    /** A quoted string's text from [start] (just past its opening [quote]), and where reading carries on after it. */
    private fun quoted(source: String, start: Int, quote: Char): Pair<String, Int> {
        val text = StringBuilder()
        var i = start
        while (i < source.length) {
            val c = source[i]
            when {
                c == quote -> return text.toString() to i + 1
                c == '\n' -> return text.toString() to i
                c == '\\' && i + 1 < source.length -> {
                    val e = source[i + 1]
                    i += 2
                    when (e) {
                        'n' -> text.append('\n')
                        't' -> text.append('\t')
                        'r' -> text.append('\r')
                        'a' -> text.append('\u0007')
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000C')
                        'v' -> text.append('\u000B')
                        'z' -> while (i < source.length && source[i].isWhitespace()) i++
                        'x' -> {
                            source.substring(i, minOf(i + 2, source.length)).toIntOrNull(16)?.let { text.append(it.toChar()) }
                            i += 2
                        }
                        'u' -> {
                            val close = source.indexOf('}', i)
                            if (close > i) {
                                source.substring(i + 1, close).toIntOrNull(16)?.let { text.append(it.toChar()) }
                                i = close + 1
                            }
                        }
                        else -> if (e.isDigit()) {
                            var j = i - 1
                            while (j < source.length && j < i + 2 && source[j].isDigit()) j++
                            text.append(source.substring(i - 1, j).toInt().toChar())
                            i = j
                        } else {
                            text.append(e)
                        }
                    }
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        return text.toString() to i
    }
}
