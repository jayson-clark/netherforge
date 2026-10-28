package dev.netherforge.plugin.bots

/**
 * `bots:` in `config.yml`: whether scripts may join bots (`nf.bots`, for a
 * package that declares `requires.bots`), off by default (the editor's dev
 * server always allows them), and at most [max] of them online at once
 * across every package. The dev bridge's own bots (tests, coding agents)
 * aren't held to either.
 */
data class BotsConfig(val enabled: Boolean = false, val max: Int = 20)
