package dev.netherforge.format.module

/** A module: a folder of Lua under `modules/`. [files] are its `.lua` files, relative to that folder, sorted. */
data class ModuleInfo(val id: String, val files: List<String>) {
    val hasInit: Boolean get() = ModuleKind.ENTRY in files
}
