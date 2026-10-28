package dev.netherforge.format.wasm

import kotlinx.serialization.Serializable

/**
 * What a WebAssembly module's bytes say about it, as far as checking a project needs: what it imports, what it
 * exports and its memory. A host reads it with [WasmReader.read] and hands it to format through
 * [dev.netherforge.format.project.ProjectSource.wasm] (the plugin off the disk, the CLI from the file it read), so
 * format itself never reads a binary file. [error] says why the bytes aren't a module format can read; the rest is
 * then empty.
 */
@Serializable
data class WasmInfo(
    val error: String? = null,
    val imports: List<WasmImport> = emptyList(),
    val exports: List<WasmExport> = emptyList(),
    /** The module's memory, its own or the one it imports; null when it has none. */
    val memory: WasmMemory? = null,
    /** Whether it has a start function, which runs as it's instantiated. */
    val start: Boolean = false
) {
    /** The functions it exports, by name. */
    val functions: Map<String, WasmExport> get() = exports.filter { it.kind == WasmExtern.FUNCTION }.associateBy { it.name }

    /** Whether it's a WASI command: it exports `_start`, which runs it as a program. */
    val command: Boolean get() = WasmImports.COMMAND_ENTRY in functions
}

/** One import: [module] and [name] are its two-part name (`wasi_snapshot_preview1`, `fd_write`). */
@Serializable
data class WasmImport(val module: String, val name: String, val kind: WasmExtern)

/**
 * One export. A function's [params] and [results] are its value types (`i32`, `i64`, `f32`, `f64`, `v128`,
 * `funcref`, `externref`, or `ref` for any other reference); null for anything else, and for a function whose type
 * format couldn't read (a type of the GC proposal's).
 */
@Serializable
data class WasmExport(val name: String, val kind: WasmExtern, val params: List<String>? = null, val results: List<String>? = null)

/**
 * A memory's limits in 64 KiB pages; [maximum] null when it may grow to whatever the server allows. [shared] is a
 * threads build's, [memory64] one addressed by 64-bit numbers.
 */
@Serializable
data class WasmMemory(
    val minimum: Long,
    val maximum: Long? = null,
    val shared: Boolean = false,
    val memory64: Boolean = false,
    val imported: Boolean = false
)

/** What an import or export is. */
@Serializable
enum class WasmExtern {
    FUNCTION,
    TABLE,
    MEMORY,
    GLOBAL,
    TAG
}

/**
 * What the server lets a module import: WASI's preview 1 functions and nothing else. A module gets no host
 * function a package could use to reach the server, so whatever it computes, it can only answer.
 */
object WasmImports {
    /** The one module a module may import from. */
    const val WASI = "wasi_snapshot_preview1"

    /** The export a WASI command runs. */
    const val COMMAND_ENTRY = "_start"

    /** What a module built for WASI threads imports to start one, which the server doesn't provide. */
    const val THREAD_SPAWN = "thread-spawn"

    /** Every function of WASI preview 1. */
    val FUNCTIONS: Set<String> = setOf(
        "args_get", "args_sizes_get", "environ_get", "environ_sizes_get", "clock_res_get", "clock_time_get",
        "fd_advise", "fd_allocate", "fd_close", "fd_datasync", "fd_fdstat_get", "fd_fdstat_set_flags",
        "fd_fdstat_set_rights", "fd_filestat_get", "fd_filestat_set_size", "fd_filestat_set_times", "fd_pread",
        "fd_prestat_get", "fd_prestat_dir_name", "fd_pwrite", "fd_read", "fd_readdir", "fd_renumber", "fd_seek",
        "fd_sync", "fd_tell", "fd_write", "path_create_directory", "path_filestat_get", "path_filestat_set_times",
        "path_link", "path_open", "path_readlink", "path_remove_directory", "path_rename", "path_symlink",
        "path_unlink_file", "poll_oneoff", "proc_exit", "proc_raise", "sched_yield", "random_get", "sock_accept",
        "sock_recv", "sock_send", "sock_shutdown"
    )

    /** Whether the server provides [import]. */
    fun provided(import: WasmImport): Boolean = import.kind == WasmExtern.FUNCTION && import.module == WASI && import.name in FUNCTIONS

    /** Whether [import] is one a module built for threads makes: its shared memory, or `wasi.thread-spawn`. */
    fun threaded(import: WasmImport): Boolean = import.kind == WasmExtern.MEMORY || (import.module == "wasi" && import.name == THREAD_SPAWN)
}
