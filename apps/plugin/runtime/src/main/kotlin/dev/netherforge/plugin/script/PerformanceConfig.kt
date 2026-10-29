package dev.netherforge.plugin.script

/**
 * When a script is slow enough to warn about (`performance:` in `config.yml`,
 * read on dev servers too): its average over the last [warnTicks] ticks
 * above [warnMillis] milliseconds a tick. 0 turns the warning off.
 */
data class PerformanceConfig(
    val warnMillis: Double = 10.0,
    val warnTicks: Int = 20,
    /**
     * How long one script's code may run in a tick, across all its call-ins, before its timers and event
     * handlers wait for the next tick. 0 turns it off. Enforced by the sandbox ([dev.netherforge.plugin.lua.SandboxLimits.maxTickMillis]).
     */
    val maxTickMillis: Double = 25.0
)
