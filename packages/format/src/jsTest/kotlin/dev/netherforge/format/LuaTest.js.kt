package dev.netherforge.format

import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.lua.WasmoonPlatform
import kotlin.js.Promise

/** A promise the test framework waits for: `Promise` without its type argument, which an `actual typealias` can't carry. */
@JsName("Promise")
external class TestPromise

actual typealias LuaTest = TestPromise

/** wasmoon's module, loaded once for every test. */
private val platform: Promise<WasmoonPlatform> by lazy { WasmoonPlatform.load(null) }

actual fun withLua(body: (LuaPlatform) -> Unit): LuaTest = platform.then { body(it) }.unsafeCast<TestPromise>()
