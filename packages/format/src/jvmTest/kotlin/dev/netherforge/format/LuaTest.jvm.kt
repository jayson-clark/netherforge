package dev.netherforge.format

import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.lua.LuajavaPlatform

actual typealias LuaTest = Unit

actual fun withLua(body: (LuaPlatform) -> Unit): LuaTest = body(LuajavaPlatform)
