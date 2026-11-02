package dev.netherforge.format.project

/**
 * A version as Semantic Versioning 2.0.0 orders them: [major], [minor], [patch], then a version with a [prerelease]
 * (`1.0.0-beta.2`) before the same one without; build metadata (`+…`) never counts.
 */
data class SemVer(val major: Long, val minor: Long, val patch: Long, val prerelease: List<String> = emptyList()) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return compareValues(other.prerelease.size, prerelease.size).coerceIn(-1, 1)
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    /** The same version without its prerelease: what a range's prerelease rule compares. */
    val release: SemVer get() = copy(prerelease = emptyList())

    override fun toString() = "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else prerelease.joinToString(".", "-")

    companion object {
        /** [text] as a version, or null when it isn't one ([Names.isVersion]). */
        fun parse(text: String): SemVer? {
            val match = Names.SEMVER.matchEntire(text) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major.toLongOrNull() ?: return null,
                minor.toLongOrNull() ?: return null,
                patch.toLongOrNull() ?: return null,
                if (pre.isEmpty()) emptyList() else pre.split('.')
            )
        }
    }
}

/**
 * The versions a dependency takes (`"version"` in `netherforge.json`'s `dependencies`), written as npm writes ranges,
 * a subset of it: `*` (any), `1.2.3` or `=1.2.3` (exactly), `^1.2.3` (the same major: `>=1.2.3 <2.0.0`; for `0.x`
 * the same minor, and for `0.0.x` the same patch), `~1.2.3` (the same minor: `>=1.2.3 <1.3.0`), and comparisons
 * `>`, `>=`, `<`, `<=`. Comparisons with spaces between them must all hold; `||` between sets takes either.
 *
 * A prerelease version (`2.0.0-beta.1`) is in a range only when one of the comparisons of the set it matches names a
 * prerelease of the same `major.minor.patch`, as npm has it: `^1.0.0` never takes `2.0.0-beta.1`, nor `1.5.0-rc.1`.
 */
class VersionRange private constructor(val text: String, private val sets: List<List<Comparison>>) {
    private enum class Op { EQ, GT, GE, LT, LE }

    private class Comparison(val op: Op, val version: SemVer) {
        fun holds(v: SemVer): Boolean {
            val c = v.compareTo(version)
            return when (op) {
                Op.EQ -> c == 0
                Op.GT -> c > 0
                Op.GE -> c >= 0
                Op.LT -> c < 0
                Op.LE -> c <= 0
            }
        }
    }

    /** Whether [version] (a package's `version`) is in the range. */
    operator fun contains(version: SemVer): Boolean = sets.any { set ->
        set.all { it.holds(version) } &&
            (version.prerelease.isEmpty() || set.any { it.version.prerelease.isNotEmpty() && it.version.release == version.release })
    }

    /** Whether [version] is a version and in the range. */
    fun admits(version: String): Boolean = SemVer.parse(version)?.let { it in this } ?: false

    override fun toString() = text

    companion object {
        /** What a range may be, as an error says it. */
        const val RULE = "a version range like ^1.2.0, ~1.2.0, >=1.0.0 <2.0.0, 1.2.3 or *"

        /** [text] as a range, or null when it isn't one ([RULE]). */
        fun parse(text: String): VersionRange? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val sets = trimmed.split("||").map { part ->
                val words = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) return null
                words.flatMap { comparisons(it) ?: return null }
            }
            return VersionRange(trimmed, sets)
        }

        private fun comparisons(word: String): List<Comparison>? {
            if (word == "*") return emptyList()
            val op = listOf(">=", "<=", ">", "<", "=", "^", "~").firstOrNull { word.startsWith(it) }
            val version = SemVer.parse(word.removePrefix(op.orEmpty())) ?: return null
            return when (op) {
                null, "=" -> listOf(Comparison(Op.EQ, version))
                ">" -> listOf(Comparison(Op.GT, version))
                ">=" -> listOf(Comparison(Op.GE, version))
                "<" -> listOf(Comparison(Op.LT, version))
                "<=" -> listOf(Comparison(Op.LE, version))
                "~" -> listOf(Comparison(Op.GE, version), Comparison(Op.LT, SemVer(version.major, version.minor + 1, 0)))
                else -> {
                    val below = when {
                        version.major > 0 -> SemVer(version.major + 1, 0, 0)
                        version.minor > 0 -> SemVer(0, version.minor + 1, 0)
                        else -> SemVer(0, 0, version.patch + 1)
                    }
                    listOf(Comparison(Op.GE, version), Comparison(Op.LT, below))
                }
            }
        }
    }
}
