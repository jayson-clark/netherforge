package dev.netherforge.plugin.script

/**
 * `files:` in `config.yml`: how much each package may keep in its own folder
 * of `nf.files` (`plugins/NetherForge/data/<namespace>/`), in bytes and in
 * entries (files and folders), so no package can fill the server's disk.
 */
data class FilesConfig(val maxBytesPerPackage: Long = 64L * 1024 * 1024, val maxEntriesPerPackage: Int = 10_000)
