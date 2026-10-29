package dev.netherforge.format.terrain

import dev.netherforge.format.LuaTest
import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import dev.netherforge.format.withLua
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A terrain's Lua stages: the same blocks on the JVM (luajava) and in JS (wasmoon), the budget, a
 * failing stage leaving the file's own result, the sandbox, and the checks on the file. Every test runs on both
 * platforms ([withLua]).
 */
class TerrainScriptTest {
    private val script = "terrain/t.lua"

    private fun file(json: String): TerrainFile {
        val parsed = TerrainKind.parse(json, "terrain/t.json")
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        return parsed.value
    }

    private fun compile(json: String, path: String = script): CompiledTerrain = TerrainCompiler.compile(file(json), path)

    /** A small world with a script, its blocks listed, and [extra] more of the file's keys. */
    private fun world(extra: String = ""): String = """{
        "terrain": { "base": 64, "seaLevel": 40, "noises": { "n": { "noise": { "frequency": 0.02 }, "amplitude": 4 } } },
        "layers": [{ "block": "minecraft:grass_block" }],
        "script": { "budget": 20000, "blocks": ["minecraft:gold_block"], "noises": { "a": {} } }$extra
    }"""

    private class Run(val failures: MutableList<TerrainScriptFailure> = mutableListOf())

    private fun scripts(lua: LuaPlatform, source: String, run: Run, more: Map<String, String> = emptyMap()) =
        TerrainScripts(lua, mapOf(script to source) + more) { run.failures += it }

    private fun hash(buffer: ChunkBuffer): Int {
        var hash = 0x811C9DC5.toInt()
        for (index in buffer.blocks) hash = (hash xor index) * 0x01000193
        return hash
    }

    // ---- the same on every platform -----------------------------------------------------------------------

    /** The fixture's world: heights, biomes, and chunks hashed, for two seeds. */
    private fun summary(lua: LuaPlatform): List<String> {
        val base = "packages/format/testdata/terrain/script"
        val json = TestFiles.read("$base/terrain/ridges.json")!!
        val compiled = TerrainCompiler.compile(file(json), "terrain/ridges.lua")
        val sources = TestFiles.list(base).filter { it.endsWith(".lua") }.associateWith { TestFiles.read("$base/$it")!! }
        val run = Run()
        val lines = mutableListOf<String>()
        for (seed in listOf(7L, -123456789012345L)) {
            val generator = compiled.bind(seed, -64, 320, TerrainScripts(lua, sources) { run.failures += it })
            lines += "seed $seed heights ${(0 until 8).joinToString(",") { generator.surfaceAt(it * 41 - 150, it * -29 + 60).toString() }}"
            for ((cx, cz) in listOf(0 to 0, -4 to 3, 5 to -2)) {
                val buffer = generator.generate(cx, cz)
                val counts = IntArray(compiled.palette.size)
                for (index in buffer.blocks) counts[index]++
                lines += "seed $seed chunk $cx,$cz hash ${hash(buffer)} counts ${counts.joinToString(",")}"
            }
            generator.close()
        }
        assertEquals(emptyList(), run.failures)
        return lines
    }

    @Test
    fun aScriptMakesTheSameWorldOnEveryPlatform(): LuaTest = withLua { lua ->
        val actual = summary(lua)
        val golden = "packages/format/testdata/terrain/script.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(golden, actual.joinToString("\n") + "\n")
        } else {
            assertEquals(TestFiles.read(golden)!!.lines().filter { it.isNotBlank() }, actual)
        }
    }

    @Test
    fun theFixturesStagesChangeTheWorld(): LuaTest = withLua { lua ->
        val base = "packages/format/testdata/terrain/script"
        val compiled = TerrainCompiler.compile(file(TestFiles.read("$base/terrain/ridges.json")!!), "terrain/ridges.lua")
        val sources = TestFiles.list(base).filter { it.endsWith(".lua") }.associateWith { TestFiles.read("$base/$it")!! }
        val scripted = compiled.bind(7, -64, 320, TerrainScripts(lua, sources) {})
        val plain = compiled.bind(7, -64, 320)
        val heights = (0 until 64).map { scripted.surfaceAt(it * 13, it * 7) to plain.surfaceAt(it * 13, it * 7) }
        assertTrue(heights.any { (a, b) -> a > b + 3 }, "ridges rise over the file's ground: $heights")
        val glowstone = compiled.palette.indexOfFirst { it.label == "minecraft:glowstone" }
        val chunk = scripted.generate(1, 1)
        assertEquals(1, chunk.blocks.count { it == glowstone }, "one glowing block in each chunk")
        assertEquals(0, plain.generate(1, 1).blocks.count { it == glowstone })
    }

    @Test
    fun aChunkIsTheSameWhicheverOrderAndThreadStateMadeIt(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val source = """
            local terrain = ...
            return {
              decorate = function(chunk)
                for _ = 1, 20 do
                  local x, z = chunk:min_x() + math.random(0, 15), chunk:min_z() + math.random(0, 15)
                  chunk:set(x, terrain.height(x, z) + 1, z, "minecraft:gold_block")
                end
              end,
            }
        """
        val first = compiled.bind(3, -64, 320, scripts(lua, source, Run()))
        val a = first.generate(2, 5).blocks
        first.generate(-7, 1)
        val b = first.generate(2, 5).blocks
        val c = compiled.bind(3, -64, 320, scripts(lua, source, Run())).generate(2, 5).blocks
        assertContentEquals(a, b)
        assertContentEquals(a, c)
        assertFalse(a.contentEquals(compiled.bind(4, -64, 320, scripts(lua, source, Run())).generate(2, 5).blocks))
    }

    // ---- the budget ------------------------------------------------------------------------------------------

    @Test
    fun aStageThatRunsPastItsBudgetLeavesTheFilesChunk(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val run = Run()
        val source = """
            return {
              terrain = function(chunk)
                chunk:fill(chunk:min_x(), 0, chunk:min_z(), chunk:min_x() + 15, 200, chunk:min_z() + 15, "minecraft:gold_block")
                -- A pcall can't catch its way past the budget.
                pcall(function() while true do end end)
                while true do end
              end,
            }
        """
        val buffer = compiled.bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
        assertContentEquals(compiled.bind(1, -64, 320).generate(0, 0).blocks, buffer.blocks, "the file's own chunk")
        val failure = run.failures.single()
        assertEquals("terrain", failure.stage)
        assertTrue(failure.message.matches(Regex("""terrain/t\.lua:6: ran past its budget of 20000 instructions""")), failure.message)
        assertEquals(script to 6, failure.location)
    }

    @Test
    fun aPatternThatWouldBacktrackForMinutesIsRefusedAtTheScriptsLine(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val run = Run()
        // One call of the library is one instruction to the hook: the caps (the plugin's own) refuse it up front.
        val source = """
            return {
              terrain = function(chunk)
                local s = ("a"):rep(10000)
                local found = s:find(".-.-.-.-b")
              end,
            }
        """
        compiled.bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
        val failure = run.failures.single()
        assertTrue("terrain/t.lua:5: string.find: the pattern \".-.-.-.-b\" could take up to" in failure.message, failure.message)
    }

    @Test
    fun aLoopThatEndsCollectionCyclesIsStillHeldToItsBudget(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val run = Run()
        // Each cycle re-arms the hook, which starts its count over; the re-arm is charged what that count could hold.
        val source = """
            return {
              terrain = function(chunk)
                local s = ("x"):rep(64 * 1024)
                while true do local u = s .. "a" end
              end,
            }
        """
        compiled.bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
        val failure = run.failures.single()
        assertTrue(failure.message.matches(Regex("""terrain/t\.lua:\d: ran past its budget of 20000 instructions""")), failure.message)
    }

    @Test
    fun aHeightThatFailsIsTheFilesHeightThere(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val run = Run()
        val source = """
            return {
              height = function(x, z, height)
                if x < 0 then error("west of 0") end
                if x > 100 then return "high" end
                return height + 5
              end,
            }
        """
        val generator = compiled.bind(1, -64, 320, scripts(lua, source, run))
        val plain = compiled.bind(1, -64, 320)
        assertEquals(plain.surfaceAt(10, 4) + 5, generator.surfaceAt(10, 4))
        assertEquals(plain.surfaceAt(-10, 4), generator.surfaceAt(-10, 4))
        assertEquals(plain.surfaceAt(110, 4), generator.surfaceAt(110, 4))
        assertEquals(
            listOf("terrain/t.lua:4: west of 0", "terrain/t.lua:3: the height stage must return a number, not string"),
            run.failures.map { it.message }
        )
    }

    // ---- the sandbox -----------------------------------------------------------------------------------------

    @Test
    fun aScriptSeesNoServerAndNoWayOut(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            -- Of nf, only nf.math: nothing of the server.
            assert(next(nf) == "math" and next(nf, "math") == nil)
            for _, name in ipairs({ "io", "os", "debug", "load", "loadfile", "dofile", "collectgarbage", "print", "package" }) do
              if _G[name] ~= nil then error(name .. " is there") end
            end
            assert(getmetatable("") == false, "the string metatable is hidden")
            assert(math.randomseed == nil and string.dump == nil)
            assert(not pcall(setmetatable, {}, { __gc = function() end }))
            assert(not pcall(string.rep, "x", 1e9))
            local ok, problem = pcall(require, "nowhere")
            assert(not ok and problem:find("no module \"nowhere\""), problem)
            return {}
        """
        compile(world()).bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
        assertEquals(emptyList(), run.failures.map { it.message })
    }

    @Test
    fun whatAScriptGetsWrongIsSaidAtItsLine(): LuaTest = withLua { lua ->
        fun failures(source: String): List<String> {
            val run = Run()
            compile(world()).bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
            return run.failures.map { "${it.stage}: ${it.message}" }
        }
        assertEquals(listOf("load: terrain/t.lua:1: unexpected symbol near '+'"), failures("+"))
        assertEquals(
            listOf(
                "load: terrain/t.lua: \"heigth\" isn't a stage: a script's stages are height, density, area, biome, terrain and decorate"
            ),
            failures("return { heigth = function() end }")
        )
        assertEquals(
            listOf("load: terrain/t.lua:1: \"b\" isn't one of the noises the file's script.noises declares"),
            failures("local terrain = ... local b = terrain.noise('b') return {}")
        )
        val placed = failures("return { terrain = function(chunk)\n chunk:set(0, 0, 0, 'minecraft:diamond_block') end }")
        assertEquals(1, placed.size)
        assertTrue(
            placed[0].startsWith("terrain: terrain/t.lua:2: \"minecraft:diamond_block\" isn't a block this generator places"),
            placed[0]
        )
        assertEquals(
            listOf("terrain: terrain/t.lua:1: bad argument #2 to 'fill' (whole number expected, got a number with a fraction)"),
            failures("return { terrain = function(chunk) chunk:fill(0, 0.5, 0, 1, 1, 1, 'minecraft:gold_block') end }")
        )
        assertEquals(
            listOf("height: terrain/t.lua:1: terrain.height can't be asked from the height stage: use the height the stage is given"),
            failures("local terrain = ... return { height = function(x, z) local h = terrain.height(x, z) return h end }").take(1)
        )
    }

    @Test
    fun aScriptThatTakesTooMuchMemoryFails(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """return { terrain = function()
            local s = "x"
            while true do s = s .. s end
        end }"""
        compile(world().replace("\"budget\": 20000", "\"budget\": 100000000")).bind(1, -64, 320, scripts(lua, source, run)).generate(0, 0)
        val failure = run.failures.single()
        assertTrue(failure.message.endsWith("MB of memory"), failure.message)
    }

    @Test
    fun theAreaBiomeAndBlocksAreTheGenerators(): LuaTest = withLua { lua ->
        val compiled = compile(
            world(
                """, "biomes": { "only": { "biome": "minecraft:desert" } },
                "stone": { "block": "minecraft:deepslate" }"""
            )
        )
        val run = Run()
        val source = """
            local terrain = ...
            return {
              decorate = function(chunk)
                local x, z = chunk:min_x(), chunk:min_z()
                local top = terrain.height(x, z)
                assert(terrain.area(x, z) == "only" and terrain.biome(x, z) == "minecraft:desert")
                assert(chunk:block(x, top, z) == "minecraft:grass_block", chunk:block(x, top, z))
                assert(chunk:block(x, top - 1, z) == "minecraft:deepslate")
                assert(chunk:block(x, top + 1, z) == "minecraft:air")
                assert(chunk:block(x - 1, top, z) == nil and chunk:block(x, terrain.max_y() + 1, z) == nil)
                assert(terrain.min_y() == -64 and terrain.max_y() == 319 and terrain.sea_level() == 40)
                assert(terrain.seed() == -9007199254740993, tostring(terrain.seed()))
                assert(math.type(top) == "integer")
                local a = terrain.noise("a")
                assert(a:at(x, z) == a:at(x, z) and a:at(x, 0, z) ~= a:at(x, 1, z))
                chunk:fill(x, top + 1, z, x, top + 1, z, "minecraft:deepslate")
              end,
            }
        """
        val generator = compiled.bind(-9007199254740993L, -64, 320, scripts(lua, source, run))
        val buffer = generator.generate(0, 0)
        assertEquals(emptyList(), run.failures.map { it.message })
        assertEquals("minecraft:deepslate", compiled.palette[buffer[0, generator.surfaceAt(0, 0) + 1, 0]].label)
    }

    @Test
    fun aCheckLoadsTheScriptOnce(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val run = Run()
        assertEquals(null, scripts(lua, "return { height = function(x, z, h) return h end }", run).check(compiled))
        val failure = scripts(lua, "local x = = 1", run).check(compiled)
        assertEquals("load", failure?.stage)
        assertEquals(script to 1, failure?.location)
        assertEquals(null, scripts(lua, "+", run).check(compile("{}")), "a file with no script has nothing to check")
        assertEquals(emptyList(), run.failures, "a check tells no one else")
    }

    @Test
    fun aScriptsSourcesAreItselfAndTheModules() {
        val compiled = compile(world())
        val files = listOf("terrain/t.lua", "terrain/other.lua", "modules/a/init.lua", "modules/a/b.lua", "modules/a/data.json", "x.lua")
        assertEquals(
            mapOf(
                "modules/a/b.lua" to "modules/a/b.lua!",
                "modules/a/init.lua" to "modules/a/init.lua!",
                "terrain/t.lua" to "terrain/t.lua!"
            ),
            TerrainScripts.sourcesOf(compiled.script!!, files) { "$it!" }
        )
    }

    @Test
    fun aClosedGeneratorStillWorks(): LuaTest = withLua { lua ->
        val compiled = compile(world())
        val generator = compiled.bind(1, -64, 320, scripts(lua, "return { height = function(x, z, h) return h + 1 end }", Run()))
        val before = generator.surfaceAt(3, 3)
        generator.close()
        assertEquals(before, generator.surfaceAt(3, 3))
        generator.close()
    }

    // ---- the file --------------------------------------------------------------------------------------------

    private fun problems(files: Map<String, String>): List<Pair<String, String?>> =
        Projects.load(MapProjectSource(files + ("netherforge.json" to testManifest())))
            .problems.filter { it.file.startsWith("terrain/") }.map { "${it.file} ${it.code}" to it.path }

    @Test
    fun theScriptAndTheFileMustAgree() {
        assertEquals(
            listOf("terrain/t.json terrain.script-missing" to "$.script"),
            problems(mapOf("terrain/t.json" to """{ "script": {} }"""))
        )
        assertEquals(
            listOf("terrain/t.lua terrain.script-unused" to null),
            problems(mapOf("terrain/t.json" to "{}", "terrain/t.lua" to "return {}"))
        )
        assertEquals(emptyList(), problems(mapOf("terrain/t.json" to """{ "script": {} }""", "terrain/t.lua" to "return {}")))
        assertEquals(
            listOf("terrain/t.lua project.missing-file" to null),
            problems(mapOf("terrain/t.lua" to "return {}"))
        )
    }

    @Test
    fun theScriptsDeclarationsAreChecked() {
        val found = problems(
            mapOf(
                "terrain/t.json" to """{ "script": { "budget": 10, "noises": { "Bad Name": {}, "ok": { "octaves": 0 } },
                    "blocks": ["not a block!"], "customBlocks": ["nope"] } }""",
                "terrain/t.lua" to "return {}"
            )
        )
        assertEquals(
            listOf(
                "terrain/t.json terrain.script" to "$.script.budget",
                "terrain/t.json terrain.name" to "$.script.noises[\"Bad Name\"]",
                "terrain/t.json terrain.noise" to "$.script.noises.ok.octaves",
                "terrain/t.json terrain.block" to "$.script.blocks[0]",
                "terrain/t.json reference.block" to "$.script.customBlocks[0]"
            ).sortedBy { it.second },
            found.sortedBy { it.second }
        )
    }

    @Test
    fun theScriptsBlocksJoinThePaletteAfterTheFiles() {
        val compiled = compile(world())
        val plain = TerrainCompiler.compile(file(world().replace(""""blocks": ["minecraft:gold_block"], """, "")), script)
        assertEquals(plain.palette, compiled.palette.dropLast(1))
        assertEquals("minecraft:gold_block", compiled.palette.last().label)
        assertEquals(script, compiled.script?.file)
        assertEquals(listOf("a"), compiled.script?.noises?.map { it.name })
    }

    @Test
    fun aModuleNameIsTheFilesAModuleHas() {
        assertEquals(listOf("modules/terrain/init.lua"), TerrainScripts.modulePaths("terrain", "@terrain/t.lua"))
        assertEquals(
            listOf("modules/terrain/a/b.lua", "modules/terrain/a/b/init.lua"),
            TerrainScripts.modulePaths("terrain.a.b", "@terrain/t.lua")
        )
        assertEquals(
            listOf("modules/rocks/shapes.lua", "modules/rocks/shapes/init.lua", "modules/shapes/init.lua"),
            TerrainScripts.modulePaths("shapes", "@modules/rocks/init.lua")
        )
        assertEquals(emptyList(), TerrainScripts.modulePaths("../x", "@terrain/t.lua"))
        assertEquals(emptyList(), TerrainScripts.modulePaths("acme:x", "@terrain/t.lua"))
    }
}
