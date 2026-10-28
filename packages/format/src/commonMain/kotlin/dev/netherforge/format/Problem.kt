package dev.netherforge.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING
}

/** A place in a project: a file, and a JSON path into it when it's about a value (`$.glyphs`). */
@Serializable
data class Location(val file: String, val path: String? = null)

/**
 * One thing wrong with a project, located as precisely as we can say.
 *
 * [file] is project-relative with `/` separators on every OS. [path] is a JSON
 * path into that file (`$.nodes.top.display`) when the problem is about a
 * value; [line] and [column] are 1-based and present when we know them, which
 * is always for a parse error and never for a semantic one. [related] are the
 * other places it's about: for a reference, the other end (the pack a glyph
 * should be in, the item whose kind a stack contradicts).
 */
@Serializable
data class Problem(
    val severity: Severity,
    val file: String,
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    /** Stable identifier for the kind of problem ([ProblemCodes]), for tests and for filtering in the editor. */
    val code: String? = null,
    val related: List<Location> = emptyList()
)

/** Collects problems for one file without repeating the file name at every call. */
class ProblemSink(val file: String) {
    val problems = mutableListOf<Problem>()

    /** A problem of kind [code] (its severity is the code's), at [path] in this file. */
    fun report(code: ProblemCode, message: String, path: String? = null, related: List<Location> = emptyList()) {
        problems += code.at(file, message, path, related)
    }
}

val List<Problem>.hasErrors: Boolean get() = any { it.severity == Severity.ERROR }
