package dev.netherforge.plugin.binding
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What scripts do all the time, in instructions of their budget, held to
 * `testdata/scenario-costs.tsv` ([CostGolden]): vector maths, a handle's
 * methods and its transforms, handing over item tables, JSON, events, a
 * handler's way in, subscribing among many others, spawning a scripted
 * centity. `ConformanceTest` holds every function's own cost; these are the
 * paths around and between them, and how a cost grows with what's there
 * (subscribing as the hundred-and-first costs what the first did).
 */
class ApiCostTest {
    /** Each scenario: its name and the Lua it runs, once to warm up and once counted. */
    private val scenarios = linkedMapOf(
        // Vectors.
        "vec3(x, y, z)" to "local _ = vec3(1, 2, 3)",
        "v.x" to "local _ = v.x",
        "v + w" to "local _ = v + w",
        "v * 2" to "local _ = v * 2",
        "v:length()" to "local _ = v:length()",
        "v:distance(w)" to "local _ = v:distance(w)",
        // A handle's methods: its own, one up its chain, a Vec3 either way, a handle back.
        "centity:id()" to "local _ = centity:id()",
        "mob:id() (Entity's, on a Mob)" to "local _ = mob:id()",
        "mob:position()" to "local _ = mob:position()",
        "mob:location()" to "local _ = mob:location()",
        "mob:set_velocity(v)" to "mob:set_velocity(v)",
        "mob:is_mob()" to "local _ = mob:is_mob()",
        "node:translation()" to "local _ = node:translation()",
        "node:set_translation(v)" to "node:set_translation(v)",
        "world:block(v)" to "local _ = world:block(v)",
        "block:kind()" to "local _ = block:kind()",
        "centity:data().hp" to "local _ = centity:data().hp",
        "world:blocks, 6 × 6 × 6 as classes" to "local _ = world:blocks(v, w + vec3(4, 4, 4), classes)",
        "6 × 6 × 6 blocks, each world:block():is_solid()" to
            "for x = 0, 5 do for y = 0, 5 do for z = 0, 5 do local _ = world:block(v + vec3(x, y, z)):is_solid() end end end",
        "player:send_message(text)" to "player:send_message('hi')",
        // Hand-written functions.
        "nf.math.clamp" to "local _ = nf.math.clamp(1, 2, 3)",
        "random:integer(1, 6)" to "local _ = random:integer(1, 6)",
        "random:number()" to "local _ = random:number()",
        // Item tables, read in Kotlin.
        "set_item({ kind, count })" to "inventory:set_item(0, { kind = 'minecraft:bread', count = 4 })",
        "set_item(a rich item)" to "inventory:set_item(1, rich)",
        "set_item(an item with data)" to
            "inventory:set_item(2, { kind = 'minecraft:bread', data = { owner = 'Alex', tier = 2, tags = { 'a', 'b' }, at = 1.5 } })",
        "give_item({ item })" to "player:give_item({ item = 'ruby' })",
        "inventory:item(i)" to "local _ = inventory:item(1)",
        "nf.items.id(stack)" to "local _ = nf.items.id(stack)",
        "set_item(a stack read back)" to "inventory:set_item(3, stack)",
        // JSON.
        "nf.json.encode(a small table)" to "local _ = nf.json.encode({ a = 1, b = 'x', c = { 1, 2 } })",
        "nf.json.encode(floats)" to "local _ = nf.json.encode({ a = 1.5, b = { 0.1, 2.25 } })",
        "nf.json.encode(a typed value)" to "local _ = nf.json.encode({ at = v })",
        "nf.json.decode(a small object)" to "local _ = nf.json.decode('{\"a\":1,\"b\":\"x\",\"c\":[1,2]}')",
        // Events.
        "nf.emit, nobody listening" to "nf.emit('cost:none', { a = 1 })",
        "nf.emit, one listening" to "nf.emit('cost:one', { a = 1 })",
        "centity:emit, nobody listening" to "centity:emit('cost:none', { a = 1 })",
        "nf.emit, ten listening" to "nf.emit('cost:ten', { a = 1 })",
        "nf.emit, a handler reading three fields" to "nf.emit('cost:read', { a = 1, b = 2, c = 3 })",
        "nf.on + cancel" to "nf.on('tick', f):cancel()",
        "nf.on + cancel, among 100 others" to "nf.on('cost:many', f):cancel()",
        "mob:on + cancel" to "mob:on('damage', f):cancel()",
        "nf.after + cancel" to "nf.after(5, f):cancel()",
        // Scopes.
        "spawn a scripted centity" to "nf.centities.spawn('scripted', v)",
        "spawn a centity with no script" to "nf.centities.spawn('plain', v)"
    )

    @Test
    fun `what scripts do all the time costs what its golden says`() {
        val module = """
            local v, w = vec3(1, 64, 2), vec3(3, 65, 4)
            local world = nf.worlds.default()
            local centity = nf.centities.spawn("scripted", vec3(0, 64, 0))
            local node = centity:node("root")
            local mob = world:spawn_entity("minecraft:pig", vec3(2, 64, 2))
            local block = world:block(v)
            local random = nf.random.new(1)
            local player = nf.players.get("Alex")
            local inventory = player:inventory()
            local rich = {
              kind = "minecraft:diamond_sword", name = "Blade", lore = { "sharp", "old" },
              enchantments = { ["minecraft:sharpness"] = 5 }, unbreakable = true,
              attribute_modifiers = { { attribute = "minecraft:attack_damage", amount = 2.5, operation = "add_value" } },
              data = { owner = "Alex", tier = 2, tags = { "a", "b" }, nested = { level = 1, done = false } },
            }
            inventory:set_item(1, rich)
            local stack = inventory:item(1)
            local classes = { what = "class" }
            local function f() end
            for _ = 1, 100 do
              nf.on("cost:many", f)
            end
            nf.on("cost:one", f)
            for _ = 1, 10 do
              nf.on("cost:ten", f)
            end
            nf.on("cost:read", function(event)
              local _ = event.a + event.b + event.c
            end)

            local scenarios = {
            ${scenarios.entries.joinToString("\n") { (name, lua) -> "  { ${luaString(name)}, function() $lua end }," }}
            }
            local function cost(fn)
              local before = nf.instructions_left()
              fn()
              return before - nf.instructions_left()
            end
            local base = cost(function() end)
            local next_scenario = 1
            nf.commands.register("cost", function()
              local scenario = scenarios[next_scenario]
              next_scenario = next_scenario + 1
              cost(scenario[2])
              log("COST", scenario[1], cost(scenario[2]) - base)
            end)

            -- A handler's way in: what its scope is charged before its first line runs.
            local first = nf.instructions_left()
            local asking = first - nf.instructions_left()
            nf.on("tick", function()
              log("COST", "a tick handler, before its first line", ${ScriptDef.DEFAULT_BUDGET} - nf.instructions_left() - asking)
            end)
        """.trimIndent()
        val server = TestServer(
            mapOf(
                "items/ruby/item.json" to """{ "kind": "minecraft:emerald", "name": "Ruby" }""",
                "centities/scripted/centity.json" to TestServer.scriptedCentity(),
                "centities/scripted/script.lua" to "-- nothing",
                "centities/plain/centity.json" to """{ "nodes": { "root": {} } }""",
                "modules/t/init.lua" to module
            ),
            start = false,
            sandbox = CostGolden.EXACT
        )
        server.use {
            server.player("Alex")
            server.start()
            server.tick()
            repeat(scenarios.size) { server.platform.commands.runConsole("cost") }
            assertEquals(emptyList(), server.errors.map { it.message })
            val costs = server.logs.filter { it.startsWith("COST\t") }.associate { line ->
                val (_, name, cost) = line.split('\t')
                name to cost.toInt()
            }
            assertEquals(scenarios.size + 1, costs.size, "${server.logs}")
            CostGolden.check(
                "scenario-costs.tsv",
                "Instructions what scripts do all the time costs them (ApiCostTest). UPDATE_GOLDEN=1 rewrites it.",
                costs
            )
        }
    }

    private fun luaString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
