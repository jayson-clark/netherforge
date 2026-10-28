package dev.netherforge.plugin.lua

/**
 * What the sandbox holds scripts to, beyond each script's instruction budget
 * (its file's `budget`). The prelude's hook enforces them; the defaults and
 * why they're those numbers are in the plugin-sandbox skill ("The sandbox").
 */
data class SandboxLimits(
    /** VM instructions between two count hooks, each of which charges the running call's budget. */
    val hookEvery: Int = 1000,
    /** Count hooks between two looks at the clock for the time limit (sooner when capped library calls do enough work). */
    val deadlineEvery: Int = 10,
    /** Count hooks between two looks at how much memory scripts use (and at the end of every collection cycle). */
    val memoryEvery: Int = 1,
    /** How long one call into a script may run, nested calls in included, before it's stopped. */
    val deadlineMillis: Int = 1000,
    /** How much memory every script together may use: past it, the script holding the most is stopped. */
    val memoryMegabytes: Int = 512,
    /** The longest string a library function makes for a script (`string.rep`, `string.format`, `table.concat`...). */
    val maxStringBytes: Int = 16 * 1024 * 1024,
    /** The most work one library call may do, in steps of about a nanosecond (a pattern's worst case, a sort). */
    val maxWork: Double = 1e8
) {
    init {
        require(hookEvery > 0 && deadlineEvery > 0 && memoryEvery > 0) { "hook intervals must be positive" }
        require(deadlineMillis > 0 && memoryMegabytes > 0 && maxStringBytes > 0 && maxWork > 0) { "limits must be positive" }
    }
}
