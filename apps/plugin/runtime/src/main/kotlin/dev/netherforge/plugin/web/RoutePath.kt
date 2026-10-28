package dev.netherforge.plugin.web

import java.io.ByteArrayOutputStream

/**
 * A route's path under its package's own (`/replays/:id`, or `/files/` and a `*rest`):
 * literal segments, `:name` for any one segment, and a last `*name` for the
 * rest. [matches] takes a request's decoded segments (after the namespace)
 * and gives the parameters, or null.
 */
class RoutePath private constructor(val text: String, private val segments: List<Segment>) {
    private sealed interface Segment {
        data class Literal(val text: String) : Segment

        data class Param(val name: String) : Segment

        data class Rest(val name: String) : Segment
    }

    fun matches(path: List<String>): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        for ((index, segment) in segments.withIndex()) {
            when (segment) {
                is Segment.Rest -> {
                    if (index >= path.size) return null
                    params[segment.name] = path.subList(index, path.size).joinToString("/")
                    return params
                }
                is Segment.Literal -> if (path.getOrNull(index) != segment.text) return null
                is Segment.Param -> params[segment.name] = path.getOrNull(index) ?: return null
            }
        }
        return params.takeIf { path.size == segments.size }
    }

    /**
     * How specific it is, for choosing between routes that both match: a
     * literal segment beats a `:name` there, which beats a `*name`.
     */
    val rank: List<Int> get() = segments.map {
        if (it is Segment.Literal) {
            0
        } else if (it is Segment.Param) {
            1
        } else {
            2
        }
    }

    /** Whether this and [other] would answer exactly the same paths (`/a/:x` and `/a/:y` do). */
    fun sameAs(other: RoutePath): Boolean = segments.size == other.segments.size &&
        segments.zip(other.segments).all { (a, b) ->
            when (a) {
                is Segment.Literal -> b == a
                is Segment.Param -> b is Segment.Param
                is Segment.Rest -> b is Segment.Rest
            }
        }

    companion object {
        private val LITERAL = Regex("^[A-Za-z0-9._~-]+$")
        private val NAME = Regex("^[a-z_][a-z0-9_]*$")

        /** [text] as a route's path, or why it isn't one. */
        fun parse(text: String): Result<RoutePath> {
            if (!text.startsWith("/")) return Result.failure(IllegalArgumentException("a path starts with /"))
            if (text == "/") return Result.success(RoutePath(text, emptyList()))
            val parts = text.substring(1).split('/')
            val segments = ArrayList<Segment>()
            val names = HashSet<String>()
            for ((index, part) in parts.withIndex()) {
                val segment = when {
                    part.isEmpty() -> return fail("it has an empty segment (two slashes, or one at the end)")
                    part.startsWith(":") || part.startsWith("*") -> {
                        val name = part.substring(1)
                        if (!NAME.matches(name)) return fail("\"$part\": a parameter's name is lowercase letters, digits and _")
                        if (!names.add(name)) return fail("it names \"$name\" twice")
                        if (part[0] == '*') {
                            if (index != parts.lastIndex) return fail("\"$part\" takes the rest of the path, so it comes last")
                            Segment.Rest(name)
                        } else {
                            Segment.Param(name)
                        }
                    }
                    !LITERAL.matches(part) || part == "." || part == ".." ->
                        return fail("\"$part\" isn't a segment: letters, digits, -, _, . and ~, or :name or *name")
                    else -> Segment.Literal(part)
                }
                segments += segment
            }
            return Result.success(RoutePath(text, segments))
        }

        private fun fail(why: String): Result<RoutePath> = Result.failure(IllegalArgumentException(why))

        /** A request's raw path as decoded segments (`/a%20b/c` → `a b`, `c`), or null when it can't be decoded. A trailing slash is dropped. */
        fun segments(rawPath: String): List<String>? {
            val parts = rawPath.removePrefix("/").split('/').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
            if (parts.any { it.isEmpty() }) return null
            return parts.map {
                decode(it, plusIsSpace = false) ?: return null
            }.takeIf { decoded -> decoded.none { it == ".." || it == "." } }
        }

        /** Percent-decodes [text] as UTF-8 (and, in a query, `+` as a space); null when a `%` isn't followed by two hex digits. */
        fun decode(text: String, plusIsSpace: Boolean): String? {
            if ('%' !in text && !(plusIsSpace && '+' in text)) return text
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= text.length) return null
                        val byte = text.substring(i + 1, i + 3).toIntOrNull(HEX) ?: return null
                        out.write(byte)
                        i += 3
                        continue
                    }
                    c == '+' && plusIsSpace -> out.write(' '.code)
                    else -> out.write(c.toString().toByteArray())
                }
                i++
            }
            return out.toString(Charsets.UTF_8)
        }

        /** `a=1&b=x+y` as decoded names and values, the first of a repeated name kept; undecodable pairs skipped. */
        fun query(raw: String?): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in raw.orEmpty().split('&')) {
                if (pair.isEmpty()) continue
                val name = decode(pair.substringBefore('='), plusIsSpace = true) ?: continue
                val value = decode(pair.substringAfter('=', ""), plusIsSpace = true) ?: continue
                out.putIfAbsent(name, value)
            }
            return out
        }

        /** [values] as a query string, names sorted, each part encoded. */
        fun encodeQuery(values: Map<String, String>): String =
            values.entries.sortedBy { it.key }.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

        fun encode(text: String): String = java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        private const val HEX = 16
    }
}
