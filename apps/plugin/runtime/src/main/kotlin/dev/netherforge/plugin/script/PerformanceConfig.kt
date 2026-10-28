package dev.netherforge.plugin.script

/**
 * When a script is slow enough to warn about (`performance:` in `config.yml`,
 * read on dev servers too): its average over the last [warnTicks] ticks
 * above [warnMillis] milliseconds a tick. 0 turns the warning off.
 */
data class PerformanceConfig(val warnMillis: Double = 10.0, val warnTicks: Int = 20)
