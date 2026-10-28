package dev.netherforge.format

import dev.netherforge.format.lua.LuaPlatform

/** What a test that runs Lua returns: nothing on the JVM, a promise in JS (whose WebAssembly loads asynchronously). */
expect class LuaTest

/** Runs [body] with the platform's Lua: luajava's on the JVM, wasmoon's in JS once it has loaded. */
expect fun withLua(body: (LuaPlatform) -> Unit): LuaTest
