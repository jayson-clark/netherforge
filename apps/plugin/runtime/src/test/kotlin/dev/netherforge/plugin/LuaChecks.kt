package dev.netherforge.plugin

/**
 * The Lua side of a runtime test, written once: a script that checks what it
 * reads and logs only what it got wrong, then `done`. So a test asserts
 * `assertEquals(DONE, result)`, and a failure lists each expression that was
 * wrong (its label, what it got, what it wanted) along with any script error.
 * A script that never ran at all fails too: there's no `done`.
 *
 * - `check(label, got, want)`: `got == want`, compared as Lua compares (handles
 *   by identity, values by `__eq`).
 * - `near(label, got, want[, tolerance])`: numbers, or anything with `-` and
 *   `:length()` (a `Vec3`), within [tolerance] (1e-9).
 * - `fails(label, fn, message)`: `fn()` raises an error whose text contains
 *   [message] (plainly, not as a pattern).
 */
object LuaChecks {
    val HELPERS = """
        local function check(label, got, want)
          if got ~= want then
            log("FAIL " .. label .. ": got " .. tostring(got) .. ", want " .. tostring(want))
          end
        end
        local function near(label, got, want, tolerance)
          tolerance = tolerance or 1e-9
          local off = got ~= nil and (type(want) == "number" and math.abs(got - want) or (got - want):length())
          if not off or off > tolerance then
            log("FAIL " .. label .. ": got " .. tostring(got) .. ", want " .. tostring(want) .. " within " .. tolerance)
          end
        end
        local function fails(label, fn, message)
          local ok, err = pcall(fn)
          if ok then
            log("FAIL " .. label .. ": got no error, want one containing \"" .. message .. "\"")
          elseif not tostring(err):find(message, 1, true) then
            log("FAIL " .. label .. ": got error \"" .. tostring(err) .. "\", want one containing \"" .. message .. "\"")
          end
        end
    """.trimIndent()

    /** What a script that checked everything and got it right leaves: no errors, nothing logged but `done`. */
    val DONE = listOf("done")

    /** A module's file running [body] as its body, with the helpers, logging `done` at the end. */
    fun module(body: String) = "$HELPERS\n$body\nlog(\"done\")\n"

    /** A module's file registering command [name], which runs [body] (with `event`) and logs `done`, with the helpers. */
    fun command(body: String, name: String = "run") =
        "$HELPERS\nnf.commands.register(\"$name\", function(event)\n$body\nlog(\"done\")\nend)\n"

    /**
     * Runs [body] as the console's `/run`, in a module (`modules/t/init.lua`)
     * beside [files] on a fresh server: [setup] runs before the server starts,
     * [after] once the command has. Answers [TestServer.output].
     */
    fun run(
        body: String,
        files: Map<String, Any> = emptyMap(),
        setup: (TestServer) -> Unit = {},
        after: (TestServer) -> Unit = {}
    ): List<String> = serve(command(body), files, setup, after) { it.platform.commands.runConsole("run") }

    /** [run], with [body] as the module's own body, run while the server starts. */
    fun runModuleBody(
        body: String,
        files: Map<String, Any> = emptyMap(),
        setup: (TestServer) -> Unit = {},
        after: (TestServer) -> Unit = {}
    ): List<String> = serve(module(body), files, setup, after) {}

    /** Runs the console's `/[command]` on a running server (a [command] module's) and answers [TestServer.output]. */
    fun TestServer.runChecks(command: String = "run"): List<String> {
        platform.commands.runConsole(command)
        return output()
    }

    private fun serve(
        script: String,
        files: Map<String, Any>,
        setup: (TestServer) -> Unit,
        after: (TestServer) -> Unit,
        started: (TestServer) -> Unit
    ): List<String> = TestServer(files + mapOf("modules/t/init.lua" to script), start = false).use { server ->
        setup(server)
        server.start()
        started(server)
        after(server)
        server.output()
    }
}
