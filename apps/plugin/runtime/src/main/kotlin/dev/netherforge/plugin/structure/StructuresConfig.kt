package dev.netherforge.plugin.structure

/**
 * `structures:` in `config.yml`, read on dev servers too: how fast `world:place_gradually` and
 * `world:fill_gradually` go. Every one going shares [blocksPerTick] blocks a tick, in turn, so two scripts can't
 * double the load; a package has at most [maxJobsPerPackage] going at once, past which one fails at once.
 */
data class StructuresConfig(val blocksPerTick: Int = 8192, val maxJobsPerPackage: Int = 4)
