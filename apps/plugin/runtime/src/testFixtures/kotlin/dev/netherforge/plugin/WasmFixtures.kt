package dev.netherforge.plugin

import com.dylibso.chicory.wabt.Wat2Wasm

/**
 * The WebAssembly modules runtime tests run, as WebAssembly text built by wabt's wat2wasm (itself WebAssembly on
 * Chicory), so every byte a test runs is readable here.
 */
object WasmFixtures {
    /** A module from its WebAssembly text, for a test with a module of its own. */
    fun build(wat: String): ByteArray = Wat2Wasm.parse(wat.trimIndent())

    /**
     * A library: numbers of every type, state kept between calls, text in and out through its own bump allocator
     * (which counts what it's asked to free), a trap, a loop that never ends, and memory growth.
     */
    val library: ByteArray by lazy {
        Wat2Wasm.parse(
            """
            (module
              (memory (export "memory") 1)
              (global ${'$'}heap (mut i32) (i32.const 1024))
              (global ${'$'}count (mut i32) (i32.const 0))
              (global ${'$'}frees (mut i32) (i32.const 0))
              (data (i32.const 16) "hello")
              (func ${'$'}alloc (export "alloc") (param ${'$'}size i32) (result i32)
                (local ${'$'}at i32)
                (local.set ${'$'}at (global.get ${'$'}heap))
                (global.set ${'$'}heap (i32.add (global.get ${'$'}heap) (local.get ${'$'}size)))
                (local.get ${'$'}at))
              (func (export "dealloc") (param i32 i32)
                (global.set ${'$'}frees (i32.add (global.get ${'$'}frees) (i32.const 1))))
              (func (export "frees") (result i32) (global.get ${'$'}frees))
              (func (export "add") (param i32 i32) (result i32) (i32.add (local.get 0) (local.get 1)))
              (func (export "wide") (param i64 i64) (result i64) (i64.mul (local.get 0) (local.get 1)))
              (func (export "half") (param f64) (result f64) (f64.div (local.get 0) (f64.const 2)))
              (func (export "pair") (result i32 f32) (i32.const 7) (f32.const 1.5))
              (func (export "count") (result i32)
                (global.set ${'$'}count (i32.add (global.get ${'$'}count) (i32.const 1)))
                (global.get ${'$'}count))
              (func (export "length") (param i32 i32) (result i32) (local.get 1))
              (func (export "upper") (param ${'$'}p i32) (param ${'$'}n i32) (result i64)
                (local ${'$'}out i32) (local ${'$'}i i32) (local ${'$'}c i32)
                (local.set ${'$'}out (call ${'$'}alloc (local.get ${'$'}n)))
                (block ${'$'}done
                  (loop ${'$'}each
                    (br_if ${'$'}done (i32.ge_u (local.get ${'$'}i) (local.get ${'$'}n)))
                    (local.set ${'$'}c (i32.load8_u (i32.add (local.get ${'$'}p) (local.get ${'$'}i))))
                    (if (i32.and (i32.ge_u (local.get ${'$'}c) (i32.const 97)) (i32.le_u (local.get ${'$'}c) (i32.const 122)))
                      (then (local.set ${'$'}c (i32.sub (local.get ${'$'}c) (i32.const 32)))))
                    (i32.store8 (i32.add (local.get ${'$'}out) (local.get ${'$'}i)) (local.get ${'$'}c))
                    (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                    (br ${'$'}each)))
                (i64.or (i64.shl (i64.extend_i32_u (local.get ${'$'}out)) (i64.const 32)) (i64.extend_i32_u (local.get ${'$'}n))))
              (func (export "greet") (result i32 i32) (i32.const 16) (i32.const 5))
              (func (export "trap") unreachable)
              (func (export "spin") (loop ${'$'}forever (br ${'$'}forever)))
              (func (export "grow") (param i32) (result i32) (memory.grow (local.get 0)))
            )
            """.trimIndent()
        )
    }

    /**
     * A program that reads its input a byte at a time and answers each line with `echo: <line>`; `err` goes to its
     * standard error, `quit` exits with 3, `spin` never returns, and the end of its input exits with 0.
     */
    val echo: ByteArray by lazy {
        Wat2Wasm.parse(
            """
            (module
              (import "wasi_snapshot_preview1" "fd_read" (func ${'$'}fd_read (param i32 i32 i32 i32) (result i32)))
              (import "wasi_snapshot_preview1" "fd_write" (func ${'$'}fd_write (param i32 i32 i32 i32) (result i32)))
              (import "wasi_snapshot_preview1" "proc_exit" (func ${'$'}proc_exit (param i32)))
              (memory (export "memory") 1)
              (data (i32.const 24) "\n")
              (data (i32.const 100) "echo: ")
              (data (i32.const 300) "quit")
              (data (i32.const 304) "spin")
              (data (i32.const 308) "err")
              (func (export "_start")
                (local ${'$'}n i32) (local ${'$'}out i32)
                (loop ${'$'}next
                  (i32.store (i32.const 0) (i32.const 16))
                  (i32.store (i32.const 4) (i32.const 1))
                  (drop (call ${'$'}fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
                  (if (i32.eqz (i32.load (i32.const 8))) (then (return)))
                  (if (i32.eq (i32.load8_u (i32.const 16)) (i32.const 10))
                    (then
                      (if (i32.and (i32.eq (local.get ${'$'}n) (i32.const 4)) (i32.eq (i32.load (i32.const 200)) (i32.load (i32.const 300))))
                        (then (call ${'$'}proc_exit (i32.const 3))))
                      (if (i32.and (i32.eq (local.get ${'$'}n) (i32.const 4)) (i32.eq (i32.load (i32.const 200)) (i32.load (i32.const 304))))
                        (then (loop ${'$'}forever (br ${'$'}forever))))
                      (local.set ${'$'}out (i32.const 1))
                      (if (i32.and (i32.eq (local.get ${'$'}n) (i32.const 3))
                                   (i32.eq (i32.and (i32.load (i32.const 200)) (i32.const 0xFFFFFF)) (i32.and (i32.load (i32.const 308)) (i32.const 0xFFFFFF))))
                        (then (local.set ${'$'}out (i32.const 2))))
                      (i32.store (i32.const 32) (i32.const 100)) (i32.store (i32.const 36) (i32.const 6))
                      (i32.store (i32.const 40) (i32.const 200)) (i32.store (i32.const 44) (local.get ${'$'}n))
                      (i32.store (i32.const 48) (i32.const 24)) (i32.store (i32.const 52) (i32.const 1))
                      (drop (call ${'$'}fd_write (local.get ${'$'}out) (i32.const 32) (i32.const 3) (i32.const 8)))
                      (local.set ${'$'}n (i32.const 0))
                      (br ${'$'}next)))
                  (i32.store8 (i32.add (i32.const 200) (local.get ${'$'}n)) (i32.load8_u (i32.const 16)))
                  (local.set ${'$'}n (i32.add (local.get ${'$'}n) (i32.const 1)))
                  (br ${'$'}next)))
            )
            """.trimIndent()
        )
    }

    /**
     * A program that prints its data file `words/hello.txt`, then exits with what opening a file for writing
     * answered times 100 plus what opening `../escape.txt` answered (WASI's error numbers): 6976 is EROFS then
     * ENOTCAPABLE.
     */
    val reader: ByteArray by lazy {
        Wat2Wasm.parse(
            """
            (module
              (import "wasi_snapshot_preview1" "path_open"
                (func ${'$'}path_open (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
              (import "wasi_snapshot_preview1" "fd_read" (func ${'$'}fd_read (param i32 i32 i32 i32) (result i32)))
              (import "wasi_snapshot_preview1" "fd_write" (func ${'$'}fd_write (param i32 i32 i32 i32) (result i32)))
              (import "wasi_snapshot_preview1" "proc_exit" (func ${'$'}proc_exit (param i32)))
              (memory (export "memory") 1)
              (data (i32.const 1000) "words/hello.txt")
              (data (i32.const 1100) "../escape.txt")
              (func (export "_start")
                (local ${'$'}err i32) (local ${'$'}written i32) (local ${'$'}escaped i32)
                (local.set ${'$'}err (call ${'$'}path_open (i32.const 3) (i32.const 0) (i32.const 1000) (i32.const 15)
                  (i32.const 0) (i64.const 2) (i64.const 0) (i32.const 0) (i32.const 0)))
                (if (local.get ${'$'}err) (then (call ${'$'}proc_exit (local.get ${'$'}err))))
                (i32.store (i32.const 8) (i32.const 2000)) (i32.store (i32.const 12) (i32.const 512))
                (drop (call ${'$'}fd_read (i32.load (i32.const 0)) (i32.const 8) (i32.const 1) (i32.const 16)))
                (i32.store (i32.const 24) (i32.const 2000)) (i32.store (i32.const 28) (i32.load (i32.const 16)))
                (drop (call ${'$'}fd_write (i32.const 1) (i32.const 24) (i32.const 1) (i32.const 16)))
                (local.set ${'$'}written (call ${'$'}path_open (i32.const 3) (i32.const 0) (i32.const 1000) (i32.const 15)
                  (i32.const 0) (i64.const 64) (i64.const 0) (i32.const 0) (i32.const 4)))
                (local.set ${'$'}escaped (call ${'$'}path_open (i32.const 3) (i32.const 0) (i32.const 1100) (i32.const 13)
                  (i32.const 0) (i64.const 2) (i64.const 0) (i32.const 0) (i32.const 4)))
                (call ${'$'}proc_exit (i32.add (i32.mul (local.get ${'$'}written) (i32.const 100)) (local.get ${'$'}escaped))))
            )
            """.trimIndent()
        )
    }

    /**
     * A module framed right, as format reads it, whose code doesn't validate: `bad` says it returns an i32 and
     * leaves an i64 (wat2wasm won't write that, so its `i32.const 7` is patched to `i64.const 7`).
     */
    val invalidCode: ByteArray by lazy {
        val bytes = Wat2Wasm.parse("(module (func (export \"bad\") (result i32) (i32.const 7)))")
        val at = (bytes.size - 3 downTo 0).first {
            bytes[it] == 0x41.toByte() &&
                bytes[it + 1] == 0x07.toByte() &&
                bytes[it + 2] == 0x0B.toByte()
        }
        bytes.copyOf().also { it[at] = 0x42 }
    }

    /** `spin(n)`: n rounds of a 64-bit linear congruential generator, for [dev.netherforge.plugin.wasm.WasmBenchmark]. */
    val loop: ByteArray by lazy {
        Wat2Wasm.parse(
            """
            (module
              (func (export "spin") (param ${'$'}n i32) (result i64)
                (local ${'$'}i i32) (local ${'$'}x i64)
                (local.set ${'$'}x (i64.const 1))
                (block ${'$'}done
                  (loop ${'$'}next
                    (br_if ${'$'}done (i32.ge_u (local.get ${'$'}i) (local.get ${'$'}n)))
                    (local.set ${'$'}x (i64.add (i64.mul (local.get ${'$'}x) (i64.const 6364136223846793005)) (i64.const 1442695040888963407)))
                    (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                    (br ${'$'}next)))
                (local.get ${'$'}x))
            )
            """.trimIndent()
        )
    }

    /** A module that imports something the server doesn't provide. */
    val importsTheHost: ByteArray by lazy {
        Wat2Wasm.parse(
            """
            (module
              (import "env" "teleport" (func ${'$'}teleport (param i32)))
              (func (export "go") (call ${'$'}teleport (i32.const 1)))
            )
            """.trimIndent()
        )
    }
}
