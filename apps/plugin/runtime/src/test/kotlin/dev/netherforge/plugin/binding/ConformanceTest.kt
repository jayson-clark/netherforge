package dev.netherforge.plugin.binding
import dev.netherforge.plugin.NfvFiles
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.WasmFixtures
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.web.WebCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runtime exposes exactly what `packages/api/` declares. Most of that is
 * now the compiler's job: the bindings, the Kotlin interfaces the runtime
 * implements, the event registry and the payload classes are all generated
 * from the spec. What's left to check is what the generator can't see: the globals
 * each surface gets, what the sandbox removes, the hand-written functions
 * (`impl: "lua"`), and that every generated binding works when called.
 *
 * Reads the generated `packages/api/generated/api.json`, so a spec change
 * without `pnpm generate` fails here.
 */
class ConformanceTest {
    private val spec: JsonObject = Json.parseToJsonElement(
        TestServer.repo().resolve("packages/api/generated/api.json").readText()
    ).jsonObject

    /** What Lua itself provides, which the spec doesn't list. */
    private val standard = setOf(
        "_G", "_VERSION", "assert", "error", "getmetatable", "ipairs", "next", "pairs", "pcall", "rawequal", "rawget",
        "rawlen", "rawset", "select", "setmetatable", "tonumber", "tostring", "type", "xpcall",
        "coroutine", "math", "string", "table", "utf8"
    )

    private val JsonObject.name get() = getValue("name").jsonPrimitive.content

    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }

    private fun JsonObject.names(key: String): Set<String> = list(key).map { it.name }.toSet()

    private val classes = spec.list("classes")

    private val handleClasses = classes.filter { it.getValue("methods").jsonPrimitive.boolean }

    /** The namespaces only a test run has (`nf.test`): the script test runner's tests cover them, since a server never has them. */
    private val testOnly = classes.filter { it["testOnly"]?.jsonPrimitive?.boolean == true }.map { it.name }.toSet()

    /** `nf` and the namespaces under it (`nf.server`), the test-only ones aside. */
    private val namespaces = classes.filter { !it.getValue("methods").jsonPrimitive.boolean && it.name !in testOnly }

    /** What a namespace has on a server: its functions and its fields (the namespaces under it), the test-only ones aside. */
    private fun JsonObject.members(): Set<String> =
        names("functions") + list("fields").filter { it.getValue("type").jsonPrimitive.content !in testOnly }.map { it.name }

    private val shapes = spec.list("shapes").map { it.name }.toSet()

    /** Value types (`Vec3`, `Location`): Lua tables the prelude implements, crossing by value. */
    private val values = spec.list("values")

    /** Each type alias (`Text`) and the primitive it is: a sample of one is a sample of that. */
    private val aliases = spec.list("aliases").associate {
        it.getValue("name").jsonPrimitive.content to
            it.getValue("type").jsonPrimitive.content
    }

    /** [type] with every alias in it written as what it is. */
    private fun plain(type: String): String =
        aliases.entries.fold(type) { written, (alias, primitive) -> written.replace(Regex("\\b$alias\\b"), primitive) }

    private fun JsonObject.isLua() = this["impl"]?.jsonPrimitive?.content == "lua"

    private fun surface(name: String): JsonObject = spec.list("surfaces").single { it.name == name }

    private fun specClass(name: String): JsonObject = classes.single { it.name == name }

    private class Scopes(
        val module: Scope,
        val centity: Scope,
        val menu: Scope,
        val dialog: Scope,
        val item: Scope,
        val block: Scope,
        val screen: Scope
    ) {
        val all get() = listOf(module, centity, menu, dialog, item, block, screen)
    }

    /** Each resource script logs what class its `this` is. */
    private val logThis = "log(tostring(this and getmetatable(this)))"

    private fun withScopes(check: (LuaHost, Scopes, TestServer) -> Unit) {
        TestServer(
            mapOf(
                "modules/m/init.lua" to logThis,
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to logThis,
                "menus/i/menu.json" to """{ "shared": true, "script": { "file": "script.lua" } }""",
                "menus/i/script.lua" to logThis,
                "dialogs/d/dialog.json" to """{ "title": "D", "buttons": [{ "key": "ok" }], "script": { "file": "script.lua" } }""",
                "dialogs/d/script.lua" to logThis,
                "items/g/item.json" to """{ "kind": "minecraft:bread", "script": { "file": "script.lua" } }""",
                "items/g/script.lua" to logThis,
                "blocks/b/block.json" to """{ "script": { "file": "script.lua" } }""",
                "blocks/b/script.lua" to logThis,
                "screens/s/screen.json" to """{ "width": 1, "height": 1, "script": { "file": "script.lua" } }""",
                "screens/s/script.lua" to logThis
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))
            val scopes = server.runtime.session.scripts.scopes()
            check(
                server.runtime.session.scripts.host!!,
                Scopes(
                    scopes.single { it.owner is ScopeOwner.Module },
                    scopes.single { it.owner is ScopeOwner.CentityScript },
                    scopes.single { it.owner is ScopeOwner.MenuScript },
                    scopes.single { it.owner is ScopeOwner.DialogScript },
                    scopes.single { it.owner is ScopeOwner.ItemScript },
                    scopes.single { it.owner is ScopeOwner.BlockScript },
                    scopes.single { it.owner is ScopeOwner.ScreenScript }
                ),
                server
            )
        }
    }

    private fun LuaHost.globals(scope: Scope): Set<String> = (keys(emptyList(), scope.id) + keys(listOf("base"))).toSet() - standard

    @Test
    fun `every constant the docs quote is the runtime's`() {
        val constants = spec.list("constants")
        assertTrue(constants.isNotEmpty(), "api.json lists no constants")
        for (constant in constants) {
            val value = constant.getValue("value").jsonPrimitive.double
            for (where in constant.getValue("runtime").jsonArray.map { it.jsonPrimitive.content }) {
                val (owner, name) = where.split('#')
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val actual = (field.get(null) as Number).toDouble()
                assertEquals(value, actual, "${constant.name} is $value in the spec but $actual in $where")
            }
        }
    }

    @Test
    fun `every surface has exactly the declared globals`() = withScopes { host, scopes, _ ->
        val shared = spec.names("globals")
        assertEquals(shared + surface("module").names("globals"), host.globals(scopes.module))
        assertEquals(shared + surface("centity").names("globals"), host.globals(scopes.centity))
        assertEquals(shared + surface("menu").names("globals"), host.globals(scopes.menu))
        assertEquals(shared + surface("dialog").names("globals"), host.globals(scopes.dialog))
        assertEquals(shared + surface("item").names("globals"), host.globals(scopes.item))
        assertEquals(shared + surface("block").names("globals"), host.globals(scopes.block))
        assertEquals(shared + surface("screen").names("globals"), host.globals(scopes.screen))
        for (gone in listOf("inventory", "dialog", "slot", "button")) {
            for (scope in scopes.all) assertTrue(gone !in host.globals(scope), "$gone in ${scope.owner.label}")
        }
    }

    @Test
    fun `this is the class each surface declares`() = withScopes { _, _, server ->
        fun declared(name: String) = surface(name).list("globals").single { it.name == "this" }.getValue("type").jsonPrimitive.content
        // Start order (the session's services): modules, items, menus, dialogs, screens, blocks, then the spawned centity.
        assertEquals(
            listOf(
                "nil",
                declared("item"),
                declared("menu"),
                declared("dialog"),
                declared("screen"),
                declared("block"),
                declared("centity")
            ),
            server.logs
        )
    }

    @Test
    fun `what the sandbox removes is really gone`() = withScopes { host, scopes, _ ->
        val removed = spec.getValue("removed").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(removed.isNotEmpty())
        val everything = scopes.all.flatMap { host.keys(emptyList(), it.id) } + host.keys(listOf("base"))
        assertEquals(emptySet(), removed.intersect(everything.toSet()))
    }

    /** Each namespace's path from a scope's globals: `nf.server` is `nf`, then `server`. */
    private fun JsonObject.path(): List<String> = name.split('.')

    @Test
    fun `nf and every namespace under it have exactly the declared functions`() = withScopes { host, scopes, _ ->
        for (namespace in namespaces) {
            val declared = namespace.members()
            for (scope in scopes.all) {
                assertEquals(declared, host.keys(namespace.path(), scope.id).toSet(), "${namespace.name} in ${scope.owner.label}")
            }
        }
    }

    @Test
    fun `a server has no nf-test`() = withScopes { host, scopes, _ ->
        assertTrue(testOnly.isNotEmpty())
        for (name in testOnly) {
            for (scope in scopes.all) {
                assertTrue(
                    name.removePrefix("nf.") !in host.keys(listOf("nf"), scope.id),
                    "$name in ${scope.owner.label}"
                )
            }
        }
    }

    /** A class's methods: its own, and those of every class up its chain. */
    private fun methods(cls: JsonObject): Set<String> {
        val parent = cls["extends"]?.jsonPrimitive?.content ?: return cls.names("functions")
        return cls.names("functions") + methods(specClass(parent))
    }

    /** Each class that extends another, by name, with the class it extends. */
    private val parents = handleClasses.mapNotNull { cls -> cls["extends"]?.jsonPrimitive?.content?.let { cls.name to it } }.toMap()

    @Test
    fun `every handle class has exactly the declared methods`() = withScopes { host, _, _ ->
        assertEquals(handleClasses.map { it.name }.toSet(), host.keys(listOf("classes")).toSet())
        for (cls in handleClasses) {
            assertEquals(methods(cls), host.keys(listOf("classes", cls.name)).toSet(), "methods of ${cls.name}")
        }
    }

    /** The prelude defines by hand exactly the functions the spec marks `impl: "lua"`; the generated bindings define the rest. */
    @Test
    fun `the hand-written functions are exactly the impl lua ones`() = withScopes { host, _, _ ->
        for (cls in classes) {
            val declared = cls.list("functions").filter { it.isLua() }.map { it.name }.toSet()
            assertEquals(declared, host.keys(listOf("handwritten", cls.name)).toSet(), "hand-written functions of ${cls.name}")
        }
        assertEquals(emptySet(), host.keys(listOf("handwritten")).toSet() - classes.map { it.name }.toSet())
        // Every event object's methods: the base shape `Event`'s, written in the event core.
        val event = spec.list("shapes").single { it.name == "Event" }
        assertEquals(event.names("functions"), host.keys(listOf("event_methods")).toSet())
    }

    /**
     * Hand-written functions take the declared parameters, by name and in
     * order, so an argument error (`bad argument 'ticks'`) names what the docs
     * and completions call it. (Generated ones take them by construction.)
     */
    @Test
    fun `hand-written functions take the declared parameters`() = withScopes { host, scopes, _ ->
        val wrong = mutableListOf<String>()
        for (cls in classes) {
            for (fn in cls.list("functions").filter { it.isLua() }) {
                val declared = fn.list("params").map { it.name }
                val actual = if (cls.getValue("methods").jsonPrimitive.boolean) {
                    host.params(listOf("classes", cls.name, fn.name))?.let { (names, varargs) -> names.drop(1) to varargs }
                } else {
                    host.params(cls.path() + fn.name, scopes.module.id)
                } ?: continue
                val (names, varargs) = actual
                val ok = if (varargs) declared.take(names.size) == names else declared == names
                if (!ok) wrong += "${cls.name}.${fn.name}: declared (${declared.joinToString()}), takes (${names.joinToString()})"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `every value type has exactly the declared methods, taking the declared parameters`() = withScopes { host, _, _ ->
        assertEquals(values.map { it.name }.toSet(), host.keys(listOf("values")).toSet())
        val wrong = mutableListOf<String>()
        for (value in values) {
            assertEquals(value.names("functions"), host.keys(listOf("values", value.name)).toSet(), "methods of ${value.name}")
            for (fn in value.list("functions")) {
                val declared = fn.list("params").map { it.name }
                val (names, _) = host.params(listOf("values", value.name, fn.name)) ?: continue
                val takes = names.drop(1)
                if (takes != declared) wrong += "${value.name}.${fn.name}: declared ($declared), takes ($takes)"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    // ---- every function, called with values of its declared types ------------------------

    /** The events a class has, its own and those up its chain. */
    private fun eventsOf(name: String): List<String> {
        val cls = classes.singleOrNull { it.name == name } ?: return emptyList()
        val parent = cls["extends"]?.jsonPrimitive?.content
        return cls.list("events").map { it.name } + (parent?.let(::eventsOf) ?: emptyList())
    }

    /** Every function there is, with its class, generated and hand-written. */
    private val functions by lazy { classes.filter { it.name !in testOnly }.flatMap { cls -> cls.list("functions").map { cls to it } } }

    private fun JsonObject.isMethod() = getValue("methods").jsonPrimitive.boolean

    private fun JsonObject.waits() = this["waits"]?.jsonPrimitive?.boolean == true

    private fun JsonObject.isAsync() = this["async"]?.jsonPrimitive?.boolean == true

    /** How a script calls [fn]: `Player:ban`, `nf.server.set_motd`. */
    private fun label(cls: JsonObject, fn: JsonObject) = "${cls.name}${if (cls.isMethod()) ":" else "."}${fn.name}"

    /** The callee and its leading arguments: `h.Player.ban, h.Player`, or `nf.server.set_motd`. */
    private fun target(cls: JsonObject, fn: JsonObject) =
        if (cls.isMethod()) listOf("h.${cls.name}.${fn.name}", "h.${cls.name}") else listOf("${cls.name}.${fn.name}")

    /** A Lua expression for an argument of [param]'s type: a live handle for a handle class, a project name for a `names` parameter. */
    private fun sample(param: JsonObject): String {
        val type = plain(param.getValue("type").jsonPrimitive.content).removeSuffix("?").removeSurrounding("(", ")")
        val handles = handleClasses.map { it.name }
        return when {
            // A string union: its first choice.
            type.startsWith("\"") -> type.substringBefore('|')
            type in handles -> "h.$type"
            type == "string" || type == "nf.Event" -> when (param["names"]?.jsonPrimitive?.content) {
                "centity" -> "\"c\""
                "menu" -> "\"i\""
                "button" -> "\"ok\""
                "dialog" -> "\"d\""
                "particle_effect" -> "\"p\""
                "cutscene" -> "\"cs\""
                "screen" -> "\"s\""
                "video" -> "\"v\""
                "node" -> "\"root\""
                // A custom event, as `emit` raises it.
                else -> if (type == "nf.Event") {
                    "\"tick\""
                } else if (param.name == "event") {
                    "\"smoke:x\""
                } else {
                    "\"x\""
                }
            }
            // A class's event names: its first event.
            type.endsWith(".Event") -> "\"${eventsOf(type.removeSuffix(".Event")).firstOrNull() ?: "x"}\""
            type == "number" -> "1.5"
            // At least 1, as a count of ticks must be.
            type == "integer" -> "1"
            type == "integer[]" -> "{ 0 }"
            type == "table<integer, Item>" -> "{ [0] = { kind = \"minecraft:stone\" } }"
            type == "boolean" -> "true"
            type == "Item" -> "{ kind = \"minecraft:bread\" }"
            type == "string|ItemMatch" -> "\"minecraft:bread\""
            type == "string[]" -> "{ \"a\", \"b\" }"
            type == "Vec3" -> "vec3(1, 2, 3)"
            type == "Location" -> "h.Player:location()"
            type == "Location|Vec3" -> "vec3(0, 64, 0)"
            // An offset or a face: the offset.
            type.startsWith("Vec3|\"") -> "vec3(0, 1, 0)"
            // Somewhere or something to go: the handle, the crossing a place can't show.
            type == "Location|Vec3|Entity" -> "h.Entity"
            // One of several: the last, so the class name picks the right one.
            type == "Location|Vec3|Entity|Centity" -> "h.Centity"
            // A name or a handle: the project's one.
            type == "string|MenuTemplate" -> "\"i\""
            type == "string|Dialog" -> "\"d\""
            // A command's definition or, in its place, its handler: the handler.
            type == "CommandDefinition|fun(event: CommandEvent)" -> "function() end"
            // A union of handles (and `nf`, for `nf.wait_for`): its first.
            type.split('|').all { it in handles || it == "nf" } -> "h.${type.substringBefore('|')}"
            // A definition table: an empty one is a whole menu, and a mistake for a dialog (which is fine).
            type == "table" -> "{}"
            // A table of a shape: one with every field set, so a required one is there.
            type in shapes -> sampleOf(type, functions = true) ?: "{}"
            type == "any" -> "{ a = 1 }"
            type.startsWith("fun") || type.startsWith("async fun") -> "function() end"
            else -> sampleOf(type, functions = true) ?: error("no sample for $type")
        }
    }

    /**
     * A Lua table describing [type] for the harness's `conforms`: what a value of it may be,
     * however deep (a list's items, a shape's fields, a union's members).
     */
    private fun describe(type: LuaTypes.Node): String = when (type) {
        is LuaTypes.Node.Name -> when (val name = aliases[type.name] ?: type.name) {
            "any", "unknown" -> "{ k = \"any\" }"
            "nil" -> "{ k = \"nil\" }"
            "string", "number", "integer", "boolean", "function", "table" -> "{ k = \"prim\", name = \"$name\" }"
            in handleNames, in values.map { it.name } -> "{ k = \"class\", name = \"$name\" }"
            in shapes -> "{ k = \"shape\", name = \"$name\" }"
            // A class's event names (`nf.Event`).
            else -> if (name.endsWith(".Event")) "{ k = \"prim\", name = \"string\" }" else error("no description of $name")
        }
        is LuaTypes.Node.Literal -> "{ k = \"lit\", value = \"${type.value}\" }"
        is LuaTypes.Node.Optional -> "{ k = \"opt\", inner = ${describe(type.inner)} }"
        is LuaTypes.Node.List -> "{ k = \"list\", item = ${describe(type.item)} }"
        is LuaTypes.Node.Map -> "{ k = \"map\", key = ${describe(type.key)}, value = ${describe(type.value)} }"
        is LuaTypes.Node.Union -> "{ k = \"union\", members = { ${type.members.joinToString(", ") { describe(it) }} } }"
        LuaTypes.Node.Function -> "{ k = \"prim\", name = \"function\" }"
    }

    private fun describe(type: String): String = describe(LuaTypes(type).parse())

    /** Every shape's fields, its own and those of the shapes it extends, as `conforms` reads them. */
    private fun shapeTable(): String {
        val byName = spec.list("shapes").associateBy { it.name }
        fun fields(shape: JsonObject): List<JsonObject> =
            shape.list("fields") + (shape["extends"]?.jsonPrimitive?.content?.let { fields(byName.getValue(it)) } ?: emptyList())
        return byName.values.joinToString(",\n", "{\n", "\n}") { shape ->
            val described = fields(shape).joinToString(", ") { "${it.name} = ${describe(it.getValue("type").jsonPrimitive.content)}" }
            "  ${shape.name} = { $described }"
        }
    }

    /** The handles every call is made on, the harness's checks, and `conforms`, which holds a value to a description. */
    private fun harness(): String = """
        local h = {}
        h.Centity = nf.centities.spawn("c", vec3(0, 64, 0))
        h.Node = h.Centity:node("root")
        h.Player = nf.players.get("Alex")
        h.Menu = nf.menus.shared("i")
        h.Slot = h.Menu:slot(0)
        h.MenuTemplate = nf.menus.create({ rows = 1 })
        h.Dialog = nf.dialogs.get("d")
        h.Button = h.Dialog:button("ok")
        h.ProjectItem = nf.items.get("g")
        h.ProjectBlock = nf.blocks.get("b")
        h.Effect = nf.particles.play("p", vec3(0, 64, 0), { loop = true })
        -- .invalid never resolves: the connections end "failed" without reaching anyone.
        h.HttpStream = nf.http.stream({ url = "https://conformance.invalid/feed" })
        h.WebSocket = nf.http.websocket({ url = "wss://conformance.invalid/socket" })
        h.Route = nf.web.route("GET", "/smoke", function() end)
        h.OAuthFlow = nf.web.oauth({ name = "smoke", authorize_url = "https://conformance.invalid/a", token_url = "https://conformance.invalid/t", client_id = "c" })
        -- A program of a library (it has no _start) ends "failed" at the next tick; an instance takes a tick to make,
        -- so another module made it before this one started (see everything).
        h.WasmProcess = nf.wasm.start("w")
        h.WasmInstance = require("wasm_sample").instance
        h.NetworkChannel = nf.network.channel("smoke")
        h.NetworkMap = nf.network.map("smoke")
        -- A request's response, which only a route's handler is handed: the test makes one request (everything).
        nf.web.route("GET", "/sample", function(_, response) h.WebResponse = response end)
        h.Cutscene = nf.cutscenes.play(h.Player, "cs")
        h.Screen = nf.screens.get("s")
        h.Playback = h.Screen:play("v", { loop = true })
        h.File = nf.files.get("smoke.txt")
        h.Subscription = nf.on("tick", function() end)
        h.Task = nf.after(100, function() end)
        h.Random = nf.random.new(1)
        h.Image = nf.images.fill(2, 2, "#336699")
        h.World = nf.worlds.default()
        h.Block = h.World:block(vec3(0, 64, 0))
        h.CustomBlock = h.ProjectBlock:place(h.World:block(vec3(12, 64, 12)):location())
        h.WorldBorder = h.World:border()
        h.Entity = h.World:spawn_entity("minecraft:chest_minecart", vec3(4, 64, 4))
        h.Mob = h.World:spawn_entity("minecraft:pig", vec3(2, 64, 2))
        h.Living = h.World:spawn_entity("minecraft:armor_stand", vec3(6, 64, 6))
        h.DroppedItem = h.World:spawn_item(vec3(8, 64, 8), { kind = "minecraft:bread" })
        h.Mannequin = h.World:spawn_entity("minecraft:mannequin", vec3(10, 64, 10))
        h.Bot = nf.bots.all()[1]
        h.Inventory = h.Player:inventory()
        h.Hud = h.Player:hud()
        h.BossBar = nf.bossbars.create()
        h.Sidebar = h.Player:sidebar()
        h.Team = nf.teams.create("smoke")
        h.Schedule = nf.schedule.daily("18:00", function() end)
        h.Database = nf.db()
        local parents = { ${parents.entries.joinToString(", ") { (cls, parent) -> "$cls = \"$parent\"" }} }
        local shapes = ${shapeTable()}

        local function kind_of(value)
          local mt = getmetatable(value)
          if type(mt) == "string" then
            return mt
          end
          return math.type(value) or type(value)
        end

        -- Whether a value of class `kind` is a `want`: it, or below it in its chain.
        local function is_a(kind, want)
          while kind ~= nil do
            if kind == want then
              return true
            end
            kind = parents[kind]
          end
          return false
        end

        -- Nil when `value` is what `d` describes, else what's wrong, at `path`.
        local function conforms(value, d, path, depth)
          local k = d.k
          if k == "any" or depth > 5 then
            return nil
          elseif k == "opt" then
            return value ~= nil and conforms(value, d.inner, path, depth) or nil
          elseif k == "nil" then
            return value ~= nil and (path .. " is " .. kind_of(value) .. ", declared nil") or nil
          elseif k == "prim" then
            local ok
            if d.name == "integer" then
              ok = math.type(value) == "integer"
            elseif d.name == "table" then
              ok = type(value) == "table" and getmetatable(value) == nil
            else
              ok = type(value) == d.name
            end
            return not ok and (path .. " is " .. kind_of(value) .. ", declared " .. d.name) or nil
          elseif k == "lit" then
            return value ~= d.value and (path .. " is " .. tostring(value) .. ", declared \"" .. d.value .. "\"") or nil
          elseif k == "class" then
            return not is_a(kind_of(value), d.name) and (path .. " is " .. kind_of(value) .. ", declared " .. d.name) or nil
          elseif k == "union" then
            for _, member in ipairs(d.members) do
              if conforms(value, member, path, depth) == nil then
                return nil
              end
            end
            return path .. " is " .. kind_of(value) .. ", none of its union"
          end
          if type(value) ~= "table" or getmetatable(value) ~= nil then
            return path .. " is " .. kind_of(value) .. ", declared a table"
          end
          if k == "list" then
            local count = 0
            for _ in pairs(value) do
              count = count + 1
            end
            if count ~= #value then
              return path .. " isn't a list"
            end
            for i, item in ipairs(value) do
              local wrong = conforms(item, d.item, path .. "[" .. i .. "]", depth + 1)
              if wrong then
                return wrong
              end
            end
          elseif k == "map" then
            for key, item in pairs(value) do
              local wrong = conforms(key, d.key, path .. " key", depth + 1)
                or conforms(item, d.value, path .. "." .. tostring(key), depth + 1)
              if wrong then
                return wrong
              end
            end
          elseif k == "shape" then
            local fields = shapes[d.name]
            for key, item in pairs(value) do
              if fields[key] == nil then
                return path .. "." .. tostring(key) .. " isn't a field of " .. d.name
              end
            end
            for name, field in pairs(fields) do
              local wrong = conforms(value[name], field, path .. "." .. name, depth + 1)
              if wrong then
                return wrong
              end
            end
          end
          return nil
        end

        local function failed(label, message)
          if message:find("Exception") or message:find("java") or message:find("attempt to") then
            log("FAIL " .. label .. ": " .. message)
          end
        end

        -- Holds what a call returned to its declared returns (a nilable function may answer one nil).
        local function check(label, expected, ok, ...)
          if not ok then
            return failed(label, tostring((...)))
          end
          local count = select("#", ...)
          local nilable = false
          for _, want in ipairs(expected) do
            nilable = nilable or want.k == "opt" or want.k == "any"
          end
          if nilable and count == 1 and (...) == nil then
            return
          end
          if count ~= #expected then
            log("FAIL " .. label .. ": " .. count .. " results, declared " .. #expected)
            return
          end
          for i, want in ipairs(expected) do
            local wrong = conforms((select(i, ...)), want, "result " .. i, 0)
            if wrong then
              log("FAIL " .. label .. ": " .. wrong)
            end
          end
        end
    """.trimIndent()

    /**
     * A project with one of everything the calls need, every power allowed, and Alex online; [module] runs once he is.
     * Hundreds of calls in one script take well past the default second on a busy CI runner, and these tests are
     * about what the calls do, not how long they take, so the time limit is generous.
     */
    private fun everything(module: String, sandbox: SandboxLimits = SandboxLimits(deadlineMillis = 60_000), check: (TestServer) -> Unit) {
        val server = TestServer(
            mapOf(
                // Every power, so moderation and grants reach Kotlin rather than stopping at the check.
                TestServer.MANIFEST to TestServer.manifest(
                    allow = """{ "permissions": ["x"] }""",
                    requires =
                    """{ "console": true, "moderation": true, "db": true, """ +
                        """"http": ["example.com", "conformance.invalid"], "web": true, "bots": true, """ +
                        """"wasm": true, "network": true, """ +
                        """"plugins": ["vault", "placeholderapi"] }"""
                ),
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "-- nothing",
                "menus/i/menu.json" to """{ "shared": true }""",
                "dialogs/d/dialog.json" to """{ "title": "D", "buttons": [{ "key": "ok" }] }""",
                "items/g/item.json" to """{ "kind": "minecraft:bread" }""",
                "blocks/b/block.json" to """{}""",
                "particles/p/effect.json" to """{ "duration": 20, "emitters": { "e": { "particle": "minecraft:flame", "burst": 1 } } }""",
                "cutscenes/cs.json" to
                    """{ "camera": { "position": [{ "time": 0, "value": [0, 64, 0] }, { "time": 5, "value": [0, 64, 9] }], "rotation": [{ "time": 0, "yaw": 0, "pitch": 0 }] } }""",
                "screens/s/screen.json" to """{ "width": 1, "height": 1 }""",
                "videos/v/video.json" to """{ "source": "source.mp4", "width": 1, "height": 1, "audio": false }""",
                "videos/v/video.nfv" to NfvFiles.write(1, 1, 20, NfvFiles.solid(8, 4, 10)),
                "wasm/w/module.wasm" to WasmFixtures.library,
                "modules/wasm_sample/init.lua" to
                    "local sample = {}\nnf.wasm.instantiate(\"w\", function(instance) sample.instance = instance end)\nreturn sample",
                "modules/smoke/init.lua" to "-- started by the test, once a player is online"
            ),
            start = false,
            sandbox = sandbox
        )
        server.use {
            // The plugins the project declares are on the server, with an economy, so those calls reach Kotlin too.
            server.platform.plugins.enable("vault")
            server.platform.plugins.enable("placeholderapi")
            server.platform.vault.register()
            server.platform.vault.accounts[server.player("Alex").ref.uuid] = 100.0
            // A bot online for `Bot`'s calls, as the bridge joins one.
            server.platform.bots.join(BotJoin("Botty")) {}
            server.platform.scheduler.runPending()
            // An instance of a module arrives a tick after it's asked for: made, and ticked for, before the harness
            // starts, so nothing it makes (a connection, which a tick would end) is older than it was.
            server.start()
            server.tick(2)
            server.write("modules/smoke/init.lua", module)
            server.reload("modules/smoke/init.lua")
            server.runtime.session.web.request(WebCall("GET", "/test/sample", null, emptyMap(), ByteArray(0), "127.0.0.1") { _, _ -> })
            check(server)
        }
    }

    /**
     * Calls every function once against the fake platform, generated and
     * hand-written (but those that wait, below), with arguments of the declared
     * types, and holds what comes back to the declared returns however deep:
     * each item of a list, each field of a table of a shape (and no field it
     * doesn't declare), each member of a union. A marshaling mistake (a wrong push
     * count, a Kotlin exception, a handle not rebuilt, a field the spec doesn't
     * have) shows up here. Errors a script could cause (no such animation) are
     * fine; Kotlin exceptions aren't. Calls that remove things go last, so the
     * rest see them alive.
     */
    @Test
    fun `every function can be called and returns the declared types`() {
        val calls = functions.filter { (_, fn) -> !fn.waits() }
            .sortedBy { (_, fn) ->
                fn.name in
                    setOf("remove", "delete", "cancel", "close_for", "close_all", "kick", "ban", "unload", "leave")
            }
        val lines = calls.map { (cls, fn) ->
            val args = target(cls, fn) + fn.list("params").filter { it.name != "..." }.map(::sample)
            // An async function given its callback (every optional parameter is given) returns nothing: its answer goes to the callback.
            val expected = if (fn.isAsync()) {
                ""
            } else {
                fn.list("returns").joinToString(", ") {
                    describe(it.getValue("type").jsonPrimitive.content)
                }
            }
            "check(\"${label(cls, fn)}\", { $expected }, pcall(${args.joinToString(", ")}))"
        }
        everything(
            """
            ${harness()}

            -- In a command handler, for the one thing only a command hands out: its sender.
            nf.commands.register("smoke", function(event)
              h.Sender = event.sender
            ${lines.joinToString("\n")}
            log("called ${calls.size}")
            end)
            """.trimIndent()
        ) { server ->
            assertTrue(server.platform.commands.runConsole("smoke"), "no /smoke: ${server.errors.map { it.message }}")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(emptyList(), server.logs.filter { it.startsWith("FAIL") })
            assertEquals(listOf("called ${calls.size}"), server.logs)
            assertTrue(calls.size > 300, "only ${calls.size} functions")
        }
    }

    /**
     * What every function costs a script, held to `testdata/function-costs.tsv`
     * ([CostGolden]): each called as above (its sample arguments, made in the
     * call, are part of what it costs), once to warm up and once counted, each
     * in its own command, so its own budget. A function dearer than its golden
     * is a regression; one much cheaper, a golden to rewrite.
     */
    @Test
    fun `every function costs what its golden says`() {
        val calls = functions.filter { (_, fn) -> !fn.waits() }
            .sortedBy { (_, fn) ->
                fn.name in
                    setOf("remove", "delete", "cancel", "close_for", "close_all", "kick", "ban", "unload", "leave")
            }
        val lines = calls.map { (cls, fn) ->
            val args = target(cls, fn) + fn.list("params").filter { it.name != "..." }.map(::sample)
            "  { \"${label(cls, fn)}\", function() return ${args.first()}(${args.drop(1).joinToString(", ")}) end },"
        }
        everything(
            """
            ${harness()}
            local calls = {
            ${lines.joinToString("\n")}
            }
            local next_call = 1
            local function cost(f)
              local before = nf.instructions_left()
              pcall(f)
              return before - nf.instructions_left()
            end
            local base = cost(function() end)
            nf.commands.register("cost", function(event)
              h.Sender = event.sender
              local call = calls[next_call]
              next_call = next_call + 1
              cost(call[2])
              log("COST", call[1], cost(call[2]) - base)
            end)
            """.trimIndent(),
            CostGolden.EXACT
        ) { server ->
            repeat(calls.size) { server.platform.commands.runConsole("cost") }
            assertEquals(emptyList(), server.errors.map { it.message })
            val costs = server.logs.filter { it.startsWith("COST\t") }.associate { line ->
                val (_, name, cost) = line.split('\t')
                name to cost.toInt()
            }
            assertEquals(calls.size, costs.size)
            CostGolden.check(
                "function-costs.tsv",
                "Instructions each API function costs a script, called once with sample arguments of its\n" +
                    "declared types (ConformanceTest). UPDATE_GOLDEN=1 rewrites it.",
                costs
            )
        }
    }

    /**
     * Every argument is checked against its declared type: each function,
     * called with values of the declared types but one, gets a thread (a value
     * no declared type but `any` takes) for that one, and must refuse it with
     * an error naming it (`bad argument 'ticks'`), whether it's generated (the
     * codecs) or hand-written (the prelude's `want`). Run in a task, so the
     * functions that wait get as far as their arguments.
     */
    @Test
    fun `every function refuses an argument of another type, naming it`() {
        val calls = functions.flatMap { (cls, fn) ->
            val params = fn.list("params").filter { it.name != "..." }
            params.mapIndexedNotNull { index, param ->
                val type = plain(param.getValue("type").jsonPrimitive.content)
                if (type == "any" || type == "any?") return@mapIndexedNotNull null
                val args = target(cls, fn) + params.mapIndexed { at, it -> if (at == index) "wrong" else sample(it) }
                "refuses(\"${label(cls, fn)}\", \"${param.name}\", pcall(${args.joinToString(", ")}))"
            }
        }
        everything(
            """
            ${harness()}
            local wrong = coroutine.create(function() end)

            local function refuses(label, param, ok, message)
              message = tostring(message)
              if ok then
                log("FAIL " .. label .. " took a thread for " .. param)
              elseif not message:find("'" .. param .. "'", 1, true) then
                log("FAIL " .. label .. " with a thread for " .. param .. ": " .. message)
              end
            end

            nf.commands.register("smoke", function(event)
              h.Sender = event.sender
              nf.task(function()
            ${calls.joinToString("\n")}
                log("refused ${calls.size}")
              end)
            end)
            """.trimIndent()
        ) { server ->
            assertTrue(server.platform.commands.runConsole("smoke"), "no /smoke: ${server.errors.map { it.message }}")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(emptyList(), server.logs.filter { it.startsWith("FAIL") })
            assertEquals(listOf("refused ${calls.size}"), server.logs)
            assertTrue(calls.size > 400, "only ${calls.size} arguments")
        }
    }

    /**
     * What the spec says waits (`waits`) is what can only be called in a task:
     * each refuses an event handler, saying to start a task, and gives its
     * declared returns in one. Every other function was called outside a task
     * above.
     */
    @Test
    fun `the functions that wait are exactly those that need a task`() {
        val waiting = functions.filter { (_, fn) -> fn.waits() }
        assertEquals(setOf("nf.wait", "nf.wait_until", "nf.wait_for", "Dialog:ask"), waiting.map { (cls, fn) -> label(cls, fn) }.toSet())
        val outside = waiting.map { (cls, fn) ->
            val args = target(cls, fn) + fn.list("params").filter { it.name != "..." }.map(::sample)
            "outside(\"${label(cls, fn)}\", pcall(${args.joinToString(", ")}))"
        }
        everything(
            """
            ${harness()}

            local function outside(label, ok, message)
              if ok or not tostring(message):find("nf.task", 1, true) then
                log("FAIL " .. label .. " outside a task: " .. tostring(message))
              end
            end

            h.Player = nf.players.get("Alex")
            nf.once("tick", function()
            ${outside.joinToString("\n")}
            end)
            nf.task(function()
              check("nf.wait", { ${describeReturns("nf", "wait")} }, pcall(nf.wait, 0))
              check("nf.wait_until", { ${describeReturns("nf", "wait_until")} }, pcall(nf.wait_until, function() return true end))
              check("nf.wait_for", { { k = "any" } }, pcall(nf.wait_for, nf, "tick"))
              log("waited")
            end)
            """.trimIndent()
        ) { server ->
            server.tick()
            server.tick()
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("waited"), server.logs)
        }
    }

    private fun describeReturns(owner: String, name: String) = specClass(owner).list("functions").single { it.name == name }.list("returns")
        .joinToString(", ") { describe(it.getValue("type").jsonPrimitive.content) }

    // ---- a round trip of every shape through its codec --------------------------------

    /**
     * Every shape that crosses (an option table, a record, a payload) reads a
     * table with every field set to a value of its declared type, pushes what
     * it read back, and reads that again to the same thing: so each field's
     * codec takes what the spec says it holds, lists, maps, unions and nested
     * shapes included. (The second read is compared with a third: pushing an
     * item fills in what the first left out, so it's the round trip after that
     * which must change nothing.)
     */
    @Test
    fun `every shape that crosses survives a round trip through its codec`() {
        val crossing = spec.list("shapes").mapNotNull { shape ->
            val codec = runCatching { Class.forName("dev.netherforge.plugin.binding.${shape.name}\$Codec") }.getOrNull()
                ?: return@mapNotNull null
            val sample = sampleOf(shape.name) ?: return@mapNotNull null
            Triple(shape.name, codec.getField("INSTANCE").get(null) as LuaCodec<*>, sample)
        }
        assertTrue(crossing.size > 50, "only ${crossing.size} shapes cross")
        val server = TestServer(
            mapOf(
                TestServer.MANIFEST to
                    TestServer.manifest(requires = """{ "http": ["conformance.invalid"], "web": true, "wasm": true, "network": true }"""),
                "wasm/w/module.wasm" to WasmFixtures.library,
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "-- nothing",
                "menus/i/menu.json" to """{ "shared": true }""",
                "dialogs/d/dialog.json" to """{ "title": "D", "buttons": [{ "key": "ok" }] }""",
                "blocks/b/block.json" to """{}""",
                "particles/p/effect.json" to """{ "duration": 20, "emitters": { "e": { "particle": "minecraft:flame", "burst": 1 } } }""",
                "cutscenes/cs.json" to
                    """{ "camera": { "position": [{ "time": 0, "value": [0, 64, 0] }, { "time": 5, "value": [0, 64, 9] }], "rotation": [{ "time": 0, "yaw": 0, "pitch": 0 }] } }""",
                "screens/s/screen.json" to """{ "width": 1, "height": 1 }""",
                "videos/v/video.json" to """{ "source": "source.mp4", "width": 1, "height": 1, "audio": false }""",
                "videos/v/video.nfv" to NfvFiles.write(1, 1, 20, NfvFiles.solid(8, 4, 10)),
                "modules/samples/init.lua" to "-- written once a player is online"
            ),
            start = false
        )
        server.use {
            server.player("Alex")
            server.write(
                "modules/samples/init.lua",
                """
                h = {}
                h.Centity = nf.centities.spawn("c", vec3(0, 64, 0))
                h.Node = h.Centity:node("root")
                h.Player = nf.players.get("Alex")
                h.Menu = nf.menus.shared("i")
                h.Slot = h.Menu:slot(0)
                h.Dialog = nf.dialogs.get("d")
                h.Button = h.Dialog:button("ok")
                h.Effect = nf.particles.play("p", vec3(0, 64, 0), { loop = true })
                h.HttpStream = nf.http.stream({ url = "https://conformance.invalid/feed" })
                h.WebSocket = nf.http.websocket({ url = "wss://conformance.invalid/socket" })
                h.Route = nf.web.route("GET", "/smoke", function() end)
                h.OAuthFlow = nf.web.oauth({ name = "smoke", authorize_url = "https://conformance.invalid/a", token_url = "https://conformance.invalid/t", client_id = "c" })
                h.WasmProcess = nf.wasm.start("w")
                h.NetworkChannel = nf.network.channel("smoke")
                h.NetworkMap = nf.network.map("smoke")
                h.Cutscene = nf.cutscenes.play(h.Player, "cs")
                h.Screen = nf.screens.get("s")
                h.Playback = h.Screen:play("v", { loop = true })
                h.World = nf.worlds.default()
                h.Block = h.World:block(vec3(0, 64, 0))
                h.CustomBlock = nf.blocks.get("b"):place(h.World:block(vec3(12, 64, 12)):location())
                h.Entity = h.World:spawn_entity("minecraft:chest_minecart", vec3(4, 64, 4))
                h.Mob = h.World:spawn_entity("minecraft:pig", vec3(2, 64, 2))
                h.Living = h.World:spawn_entity("minecraft:armor_stand", vec3(6, 64, 6))
                h.DroppedItem = h.World:spawn_item(vec3(8, 64, 8), { kind = "minecraft:bread" })
                h.Inventory = h.Player:inventory()
                h.Hud = h.Player:hud()
                samples = {}
                ${crossing.joinToString("\n") { (name, _, sample) -> "samples.$name = $sample" }}
                """.trimIndent()
            )
            server.start()
            assertEquals(emptyList(), server.errors.map { it.message })
            val host = server.runtime.session.scripts.host!!
            val scope = server.runtime.session.scripts.scopes().single { it.owner is ScopeOwner.Module }.id
            val wrong = crossing.mapNotNull { (name, codec, _) ->
                @Suppress("UNCHECKED_CAST")
                val typed = codec as LuaCodec<Any?>
                runCatching {
                    host.at(listOf("samples", name), scope) { call, index ->
                        val first = typed.read(call, index, name)
                        typed.push(call, first)
                        val second = typed.read(call, call.lua.top, name)
                        typed.push(call, second)
                        val third = typed.read(call, call.lua.top, name)
                        if (second != third) "$name: $second came back as $third" else null
                    }
                }.getOrElse { "$name: ${it.message}" }
            }
            assertEquals(emptyList(), wrong)
        }
    }

    private val handleNames by lazy { handleClasses.map { it.name }.toSet() }

    /**
     * A Lua expression for a value of [type], or null for one no sample can stand for (`any`, or a
     * function unless [functions] says one will do).
     */
    private fun sampleOf(type: String, functions: Boolean = false): String? = sample(LuaTypes(plain(type)).parse(), 0, functions)

    private fun sample(type: LuaTypes.Node, depth: Int, functions: Boolean): String? = when (type) {
        is LuaTypes.Node.Name -> when (type.name) {
            "string", "nf.Event" -> "\"x\""
            "number" -> "1.5"
            "integer" -> "2"
            "boolean" -> "true"
            "any", "table", "nil" -> null
            "Vec3" -> "vec3(1, 2, 3)"
            "Location" -> "h.Player:location()"
            "Item", "ItemMatch" -> "{ kind = \"minecraft:bread\" }"
            in handleNames -> "h.${type.name}"
            else -> spec.list("shapes").singleOrNull { it.name == type.name }?.takeIf { depth < 4 }?.let { shape ->
                val fields = shape.list("fields").map { field ->
                    val value = sample(LuaTypes(plain(field.getValue("type").jsonPrimitive.content)).parse(), depth + 1, functions)
                    val required = !LuaTypes(field.getValue("type").jsonPrimitive.content).parse().optional
                    if (value == null && required) return@let null
                    value?.let { "${field.name} = $it" }
                }
                "{ ${fields.filterNotNull().joinToString(", ")} }"
            }
        }
        is LuaTypes.Node.Literal -> "\"${type.value}\""
        is LuaTypes.Node.Optional -> sample(type.inner, depth, functions)
        is LuaTypes.Node.List -> sample(type.item, depth, functions)?.let { "{ $it }" }
        is LuaTypes.Node.Map -> sample(type.value, depth, functions)?.let { value ->
            when (val key = type.key) {
                is LuaTypes.Node.Literal -> "{ [\"${key.value}\"] = $value }"
                is LuaTypes.Node.Name -> if (key.name == "integer") "{ [1] = $value }" else "{ a = $value }"
                else -> null
            }
        }
        is LuaTypes.Node.Union -> type.members.firstNotNullOfOrNull { sample(it, depth, functions) }
        LuaTypes.Node.Function -> if (functions) "function() end" else null
    }
}

/** Just enough of the spec's type grammar (`luaType.ts`) to make samples from a type's text. */
private class LuaTypes(private val text: String) {
    sealed interface Node {
        val optional: Boolean get() = this is Optional || (this is Union && members.any { it.optional })

        data class Name(val name: String) : Node {
            override val optional get() = name == "nil" || name == "any"
        }
        data class Literal(val value: String) : Node
        data class Optional(val inner: Node) : Node
        data class List(val item: Node) : Node
        data class Map(val key: Node, val value: Node) : Node
        data class Union(val members: kotlin.collections.List<Node>) : Node
        data object Function : Node
    }

    private var at = 0

    fun parse(): Node = union().also { skip() }

    private fun skip() {
        while (at < text.length && text[at] == ' ') at++
    }

    private fun eat(token: String): Boolean {
        skip()
        if (!text.startsWith(token, at)) return false
        at += token.length
        return true
    }

    private fun union(): Node {
        val members = mutableListOf(postfix())
        while (eat("|")) members += postfix()
        return members.singleOrNull() ?: Node.Union(members)
    }

    private fun postfix(): Node {
        var node = primary()
        while (true) {
            node = when {
                eat("[]") -> Node.List(node)
                eat("?") -> Node.Optional(node)
                else -> return node
            }
        }
    }

    private fun primary(): Node {
        skip()
        return when {
            eat("(") -> union().also { eat(")") }
            eat("\"") -> Node.Literal(text.substring(at, text.indexOf('"', at))).also { at = text.indexOf('"', at) + 1 }
            eat("async fun(") || eat("fun(") -> {
                var open = 1
                while (open > 0) {
                    if (text[at] == '(') open++
                    if (text[at] == ')') open--
                    at++
                }
                if (eat(":")) postfix()
                Node.Function
            }
            eat("table<") -> {
                val key = union()
                eat(",")
                Node.Map(key, union()).also { eat(">") }
            }
            else -> {
                val start = at
                while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '.')) at++
                Node.Name(text.substring(start, at))
            }
        }
    }
}
