package dev.netherforge.plugin.wasm
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.WasmFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nf.wasm` from scripts, on real modules ([WasmFixtures]) run by Chicory: calls and their types, text through a
 * module's memory, traps and time limits, programs run to their end and programs that keep running, the read-only
 * data folder, the owner's limits, what a package must declare, and what a reload restarts.
 */
class WasmModulesTest {
    private fun server(
        script: String,
        modules: Map<String, ByteArray> = mapOf("math" to WasmFixtures.library, "echo" to WasmFixtures.echo),
        extra: Map<String, Any> = emptyMap(),
        requires: String = """{ "wasm": true }""",
        wasm: WasmConfig = WasmConfig()
    ) = TestServer(
        mapOf<String, Any>(TestServer.MANIFEST to TestServer.manifest(requires = requires), "modules/t/init.lua" to script) +
            modules.mapKeys { "wasm/${it.key}/module.wasm" } + extra,
        wasm = wasm
    )

    /** Runs [body] in a task and ticks until it's done, the server's log lines what it logged. */
    private fun task(body: String, ticks: Int = 20, vararg settings: Pair<String, Any>): List<String> = server(
        "nf.task(function()\n$body\nend)",
        extra = settings.toMap()
    ).use { server ->
        server.tick(ticks)
        assertTrue(server.errors.isEmpty(), server.errors.toString())
        server.logs
    }

    @Test
    fun `an instance's functions take and give every number type, and keep their state between calls`() {
        val logs = task(
            """
            local lib, err = nf.wasm.instantiate("math")
            log(tostring(err), lib:module())
            local sum = lib:call("add", { 2, 3 })[1]
            log(sum, math.type(sum))
            log(lib:call("wide", { 3000000000, 3 })[1])
            log(lib:call("half", { 5 })[1])
            local pair = lib:call("pair")
            log(pair[1], pair[2])
            log(lib:call("count")[1], lib:call("count")[1])
            """
        )
        assertEquals(listOf("nil\tmath", "5\tinteger", "9000000000", "2.5", "7\t1.5", "1\t2"), logs)
    }

    @Test
    fun `strings go in through the module's memory and text comes back out of it, each freed after`() {
        val logs = task(
            """
            local lib = nf.wasm.instantiate("math")
            log(lib:call("length", { "héllo" })[1])
            log((lib:call_text("upper", { "abc" })))
            log((lib:call_text("greet")))
            log(lib:call("frees")[1])
            """
        )
        assertEquals(listOf("6", "ABC", "hello", "4"), logs)
    }

    @Test
    fun `nf wasm functions lists what a module exports with its types`() {
        val logs = task(
            """
            nf.wait(2)
            for _, fn in ipairs(nf.wasm.functions("math")) do
              if fn.name == "upper" or fn.name == "pair" then
                log(fn.name, table.concat(fn.params, ","), table.concat(fn.results, ","))
              end
            end
            log(#nf.wasm.functions("echo"), nf.wasm.functions("echo")[1].name)
            """
        )
        assertEquals(listOf("pair\t\ti32,f32", "upper\ti32,i32\ti64", "1\t_start"), logs)
    }

    @Test
    fun `what a script gets wrong in a call is an error at its line`() {
        for ((call, error) in listOf(
            """lib:call("nope")""" to """exports no function "nope"""",
            """lib:call("add", { 1 })""" to "add takes (i32, i32), but it was given fewer arguments",
            """lib:call("add", { 1, 2, 3 })""" to "args[3] is one too many",
            """lib:call("add", { 1.5, 2 })""" to "args[1] is 1.5, but add takes an i32 there: give an integer",
            """lib:call("add", { 1, true })""" to "args[2] is a boolean",
            """lib:call("add", { 1, nil, 3 })""" to "has a hole at 2",
            """lib:call("add", { 1 << 40, 1 })""" to "doesn't fit add's i32",
            """lib:call_text("add", { 1, 2 })""" to "add returns (i32), not text",
            """nf.wasm.instantiate("missing")""" to "no WebAssembly module \"missing\" in this project"
        )) {
            server("nf.task(function()\n  lib = nf.wasm.instantiate(\"math\")\n  nf.wait(1)\n  $call\nend)").use { server ->
                server.tick(4)
                assertEquals(1, server.errors.size, "$call: ${server.errors}")
                assertTrue(server.errors.single().message.contains(error), "$call: ${server.errors.single().message}")
                assertEquals(4, server.errors.single().source?.line, call)
            }
        }
    }

    @Test
    fun `a call that traps fails, and its instance is closed after`() {
        val logs = task(
            """
            local lib = nf.wasm.instantiate("math")
            local results, err = lib:call("trap")
            log(tostring(results), err)
            log(tostring(lib:call("add", { 1, 2 })), select(2, lib:call("add", { 1, 2 })))
            log(tostring(lib:close()), lib:module())
            """
        )
        assertEquals("nil", logs[0].substringBefore('\t'))
        assertTrue(logs[0].contains("trap failed") && logs[0].contains("unreachable"), logs[0])
        assertEquals(listOf("nil\tthe instance is closed", "false\tmath"), logs.drop(1))
    }

    @Test
    fun `a call that traps after taking a string says why, without running the module's free`() {
        // Its free fails differently: had it run after the trap, its failure would be what the script read.
        val faulty = WasmFixtures.build(
            """
            (module
              (memory (export "memory") 1)
              (func (export "alloc") (param i32) (result i32) (i32.const 64))
              (func (export "dealloc") (param i32 i32) (drop (i32.load (i32.const 1000000))))
              (func (export "fail") (param i32 i32) unreachable)
            )
            """
        )
        server(
            """
            nf.task(function()
              local lib = nf.wasm.instantiate("faulty")
              log(select(2, lib:call("fail", { "text" })))
            end)
            """,
            modules = mapOf("faulty" to faulty)
        ).use { server ->
            server.tick(20)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val err = server.logs.single()
            assertTrue(err.contains("fail failed") && err.contains("unreachable") && !err.contains("out of bounds"), err)
        }
    }

    @Test
    fun `a call past max-call-seconds is stopped and its instance closed`() {
        server(
            """
            nf.task(function()
              local lib = nf.wasm.instantiate("math")
              local _, err = lib:call("spin")
              log(err)
              log(select(2, lib:call("count")))
            end)
            """,
            wasm = WasmConfig(maxCallSeconds = 1)
        ).use { server ->
            server.tick(4)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf(
                    "spin took longer than 1 seconds (wasm.max-call-seconds in config.yml), so it was stopped and the instance closed",
                    "the instance is closed"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a program runs to its end with its input, and gives what it printed and its exit code`() {
        val logs = task(
            """
            local result = nf.wasm.run("echo", { stdin = "hi\nerr\nthere\n" })
            log(result.exit_code, result.stdout, result.stderr)
            log(nf.wasm.run("echo", { stdin = "quit\n" }).exit_code)
            local _, err = nf.wasm.run("math")
            log(err)
            """
        )
        assertEquals(
            listOf(
                "0\techo: hi\necho: there\n\techo: err\n",
                "3",
                "module \"math\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
            ),
            logs
        )
    }

    @Test
    fun `a started program talks line by line until its input ends, then exits`() {
        server(
            """
            local echo = nf.wasm.start("echo")
            echo:on("output", function(event) log(event.stream, event.line) end)
            echo:on("end", function(event) log("end", event.reason, event.exit_code, echo:state()) end)
            echo:send("one")
            echo:send("err")
            nf.after(3, function()
              echo:send("two")
              echo:close_input()
              log("sent after close:", echo:send("three"))
            end)
            """
        ).use { server ->
            server.tick(8)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf("stdout\techo: one", "stderr\techo: err", "sent after close:\tfalse", "stdout\techo: two", "end\texited\t0\tended"),
                server.logs
            )
        }
    }

    @Test
    fun `a program ends with its exit code, when stopped, and when its script unloads`() {
        server(
            """
            local quits = nf.wasm.start("echo")
            quits:on("end", function(event) log("quits", event.reason, event.exit_code) end)
            quits:send("quit")
            local spins = nf.wasm.start("echo")
            spins:on("end", function(event) log("spins", event.reason, tostring(event.exit_code)) end)
            spins:send("spin")
            nf.after(1, function() log("stopped", spins:stop(), spins:stop(), spins:state()) end)
            kept = nf.wasm.start("echo")
            kept:on("end", function(event) log("kept heard its end", event.reason) end)
            """,
            wasm = WasmConfig(maxProcessesPerPackage = 3)
        ).use { server ->
            server.tick(5)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            // When the first program exits beside the second one's stop is the threads' business: not the order.
            assertEquals(setOf("quits\texited\t3", "spins\tstopped\tnil", "stopped\ttrue\tfalse\tended"), server.logs.toSet())
            server.write("modules/t/init.lua", "log('again')")
            server.reload("modules/t/init.lua")
            // Its own handlers went with the script, so only the program's going says it ended.
            assertEquals(null, server.runtime.session.wasm.program(3))
        }
    }

    @Test
    fun `a module reads its data folder, read-only, and nothing outside it`() {
        server(
            """
            nf.task(function()
              local result = nf.wasm.run("reader")
              log(result.stdout, result.exit_code)
            end)
            """,
            modules = mapOf("reader" to WasmFixtures.reader),
            extra = mapOf("wasm/reader/words/hello.txt" to "hello from the data folder", "wasm/escape.txt" to "secret")
        ).use { server ->
            server.tick(4)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            // EROFS (69) opening it for writing, ENOTCAPABLE (76) climbing out of the root.
            assertEquals(listOf("hello from the data folder\n\t6976"), server.logs)
        }
    }

    @Test
    fun `the owner's limits on instances, programs and memory fail or end what's over them`() {
        server(
            """
            nf.task(function()
              local first = nf.wasm.instantiate("math")
              log(select(2, nf.wasm.instantiate("math")))
              first:close()
              log(tostring(nf.wasm.instantiate("math") ~= nil))
              local one = nf.wasm.start("echo")
              local two = nf.wasm.start("echo")
              two:on("end", function(event) log(event.reason, event.detail) end)
              nf.wait(2)
              local grown = nf.wasm.instantiate("math")
            end)
            """,
            wasm = WasmConfig(maxInstancesPerPackage = 1, maxProcessesPerPackage = 1)
        ).use { server ->
            server.tick(6)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf(
                    "package \"test\" already holds 1 WebAssembly instances, the server's limit (wasm.max-instances-per-package in config.yml): close some",
                    "true",
                    "limit\tpackage \"test\" already has 1 WebAssembly programs running, the server's limit (wasm.max-processes-per-package in config.yml)"
                ),
                server.logs
            )
        }
        // Memory: an instance may grow to the per-instance cap and no further; past the total, it can't start.
        server(
            """
            nf.task(function()
              local lib = nf.wasm.instantiate("math")
              log(lib:call("grow", { 1 })[1], lib:call("grow", { 100 })[1])
              log(select(2, nf.wasm.instantiate("math")))
            end)
            """,
            wasm = WasmConfig(maxMemoryMb = 1, maxMemoryTotalMb = 1)
        ).use { server ->
            server.tick(4)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals("1\t-1", server.logs[0])
            assertTrue(server.logs[1].startsWith("the server's WebAssembly memory is taken"), server.logs[1])
        }
    }

    @Test
    fun `running modules is something a package declares`() {
        server("nf.wasm.functions(\"math\")", requires = "{}").use { server ->
            server.tick()
            assertTrue(server.errors.single().message.contains("nf.wasm.functions needs wasm"), server.errors.toString())
        }
    }

    @Test
    fun `a module the server can't run is a problem, and running it fails saying why`() {
        server(
            """
            nf.task(function()
              log(select(2, nf.wasm.instantiate("host")))
              log(select(2, nf.wasm.instantiate("junk")))
              log(select(2, nf.wasm.instantiate("bad")))
              log(select(2, nf.wasm.instantiate("vector")))
            end)
            """,
            modules = mapOf(
                "host" to WasmFixtures.importsTheHost,
                "junk" to "not a module".toByteArray(),
                "bad" to WasmFixtures.invalidCode,
                "vector" to WasmFixtures.build(
                    """
                    (module
                      (func (export "sum") (param i32 i32) (result i32)
                        (i32x4.extract_lane 0 (i32x4.add (i32x4.splat (local.get 0)) (i32x4.splat (local.get 1))))))
                    """
                )
            )
        ).use { server ->
            server.tick(4)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            // What the format sees in a module (its imports, its framing) is an error there, and it never runs.
            assertEquals("module \"host\" has errors, so it can't run: see the problems", server.logs[0])
            assertEquals("module \"junk\" has errors, so it can't run: see the problems", server.logs[1])
            // What only compiling it finds is the runtime's: code that doesn't validate, SIMD neither the compiler nor the interpreter runs.
            assertTrue(
                server.logs[2].startsWith("module \"bad\" couldn't be compiled: module.wasm isn't a valid WebAssembly module"),
                server.logs[2]
            )
            assertEquals(
                "module \"vector\" couldn't be compiled: module.wasm uses SIMD (i32x4_splat), which the server doesn't run: " +
                    "build it without -msimd128 (or Rust's simd128 target feature)",
                server.logs[3]
            )
            val problems = server.runtime.session.problems().map { it.code to it.file }
            assertTrue("wasm.import" to "wasm/host/module.wasm" in problems, problems.toString())
            assertTrue("wasm.invalid" to "wasm/junk/module.wasm" in problems, problems.toString())
            assertTrue("runtime.wasm" to "wasm/bad/module.wasm" in problems, problems.toString())
            assertTrue("runtime.wasm" to "wasm/vector/module.wasm" in problems, problems.toString())
        }
    }

    @Test
    fun `a script that ran a module restarts when the module changes`() {
        server(
            """
            log("started")
            nf.task(function() log(nf.wasm.instantiate("math"):call("count")[1]) end)
            """
        ).use { server ->
            server.tick(3)
            server.writeBytes("wasm/math/module.wasm", WasmFixtures.library)
            val result = server.reload("wasm/math/module.wasm")
            server.tick(3)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(listOf("started", "1", "started", "1"), server.logs)
            assertEquals(listOf("wasm:math", "module:t"), result.resources.map { "${it.kind}:${it.id}" })
        }
    }
}
