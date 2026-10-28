@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.Composer
import dev.netherforge.format.cutscene.CompiledCutscene
import dev.netherforge.format.cutscene.CutsceneCompiler
import dev.netherforge.format.cutscene.CutsceneKind
import dev.netherforge.format.editor.AnimationInfo
import dev.netherforge.format.editor.CutsceneFailed
import dev.netherforge.format.editor.CutsceneResult
import dev.netherforge.format.editor.CutsceneShot
import dev.netherforge.format.editor.CutsceneTrail
import dev.netherforge.format.editor.EffectStepFailed
import dev.netherforge.format.editor.EffectStepResult
import dev.netherforge.format.editor.EffectStepped
import dev.netherforge.format.editor.LootPreview
import dev.netherforge.format.editor.LootPreviewDrop
import dev.netherforge.format.editor.PoseFailed
import dev.netherforge.format.editor.PoseResult
import dev.netherforge.format.editor.Posed
import dev.netherforge.format.editor.PosedNode
import dev.netherforge.format.editor.TerrainFailed
import dev.netherforge.format.editor.TerrainPreviewResult
import dev.netherforge.format.editor.TerrainScriptError
import dev.netherforge.format.editor.TerrainStructureInput
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loot.LootContext
import dev.netherforge.format.loot.LootDrop
import dev.netherforge.format.loot.LootRoller
import dev.netherforge.format.loot.LootTableFile
import dev.netherforge.format.loot.LootTableKind
import dev.netherforge.format.lua.WasmoonPlatform
import dev.netherforge.format.particle.ColorCurve
import dev.netherforge.format.particle.ColorKey
import dev.netherforge.format.particle.CompiledEffect
import dev.netherforge.format.particle.EffectSampler
import dev.netherforge.format.particle.NumberCurve
import dev.netherforge.format.particle.NumberKey
import dev.netherforge.format.particle.ParticleEffectCompiler
import dev.netherforge.format.particle.ParticleEffectKind
import dev.netherforge.format.particle.formatColor
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.terrain.TerrainPreview
import dev.netherforge.format.terrain.TerrainScriptFailure
import dev.netherforge.format.terrain.TerrainScripts
import dev.netherforge.format.terrain.TerrainValidator
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.js.Promise
import kotlin.random.Random

// The previews: what the server would make of a centity, cutscene, terrain, particle effect or loot roll, compiled
// once and asked as often as the editor likes.

/**
 * A centity compiled once, to pose at any clip and time: what the viewport
 * holds while it plays, so a frame is sampling and composing, not parsing.
 * When it doesn't compile, [pose] answers with the problems that stop it.
 */
class CentityPoser internal constructor(private val compiled: CompiledCentity?, private val problems: List<Problem>) {
    /** World matrices posed by [animation] at [time] seconds (the base pose for null): a [PoseResult]. */
    fun pose(animation: String?, time: Double): String {
        val compiled = compiled ?: return encode(PoseResult.serializer(), PoseFailed(problems))
        val clip = animation?.let { compiled.animation(it) }
        val locals = if (clip != null) Composer.pose(compiled, clip, time) else compiled.nodes.map { it.transform }
        val world = Composer.world(compiled, locals)
        return encode(
            PoseResult.serializer(),
            Posed(
                compiled.nodes.mapIndexed { i, node ->
                    PosedNode(node.name, compiled.nodes.getOrNull(node.parentIndex)?.name, world[i].values.toList())
                },
                compiled.animations.map { AnimationInfo(it.name, it.length, it.loop, it.autoplay) }
            )
        )
    }
}

/** Parses, validates and compiles a centity once, for [CentityPoser.pose] to pose as often as it likes. */
fun centityPoser(id: String, text: String): CentityPoser {
    val path = CentityKind.pathOf(id)
    val parsed = CentityKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return CentityPoser(null, listOf(parsed.problem))
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val sink = ProblemSink(path)
    CentityValidator.validate(file, sink, null, null)
    if (sink.problems.hasErrors) return CentityPoser(null, sink.problems)
    return CentityPoser(CentityCompiler.compile(id, file), emptyList())
}

/** [centityPoser] then one [CentityPoser.pose]: a [PoseResult]. */
fun poseCentity(id: String, text: String, animation: String?, time: Double): String = centityPoser(id, text).pose(animation, time)

/**
 * A cutscene compiled once, to ask where its camera is at any time: what the
 * editor's preview holds while it plays. The server's own sampling
 * ([dev.netherforge.format.cutscene.CameraPath]), so the preview shows the
 * shot the player sees. When it doesn't compile, both answers are the problems
 * that stop it.
 */
class CutsceneDirector internal constructor(private val cutscene: CompiledCutscene?, private val problems: List<Problem>) {
    /** The camera at [time] seconds, held at the first and last keys outside the keyed range: a [CutsceneResult]. */
    fun shot(time: Double): String {
        val cutscene = cutscene ?: return encode(CutsceneResult.serializer(), CutsceneFailed(problems))
        val pose = cutscene.poseAt(time)
        return encode(CutsceneResult.serializer(), CutsceneShot(cutscene.length, pose.position, pose.yaw, pose.pitch))
    }

    /** The camera's position at [count] evenly spaced times from 0 to the end (at least 2): a [CutsceneResult]. */
    fun trail(count: Int): String {
        val cutscene = cutscene ?: return encode(CutsceneResult.serializer(), CutsceneFailed(problems))
        val steps = maxOf(count, 2) - 1
        val points = (0..steps).map { cutscene.poseAt(cutscene.length * it / steps).position }
        return encode(CutsceneResult.serializer(), CutsceneTrail(cutscene.length, points))
    }
}

/** Parses, validates and compiles a cutscene once, for [CutsceneDirector] to answer as often as it likes. */
fun cutsceneDirector(id: String, text: String): CutsceneDirector {
    val path = CutsceneKind.pathOf(id)
    val parsed = CutsceneKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return CutsceneDirector(null, listOf(parsed.problem))
    val (compiled, problems) = CutsceneCompiler.compileChecked(id, (parsed as CanonicalJson.Parsed.Ok).value, path)
    return CutsceneDirector(compiled, problems)
}

/**
 * A terrain compiled once, to draw as often as the editor likes:
 * the server's own generator ([dev.netherforge.format.terrain.TerrainGenerator]) for a seed, so
 * the maps are the world the server makes. [seed] is a whole number as text (a JS number
 * can't hold every seed). When the file doesn't compile, every answer is the problems that stop it.
 */
class TerrainPreviewer internal constructor(
    private val terrain: CompiledTerrain?,
    private val problems: List<Problem>,
    private val sources: Map<String, String>
) {
    /** The generator last asked for, kept while the seed and heights stay the same: its script's Lua state with it. */
    private var bound: Pair<Triple<Long, Int, Int>, TerrainGenerator>? = null

    /**
     * What the file's script failed at, each message once: in the drawing being made, and its body's failure (which
     * happens once, as the generator's Lua state is made) for as long as that generator is kept.
     */
    private val failures = LinkedHashMap<String, TerrainScriptFailure>()

    /** A [cells] by [cells] map, a cell every [step] blocks from ([x0], [z0]), of a world from [minY] to [maxY]: a [TerrainPreviewResult]. */
    fun map(seed: String, x0: Int, z0: Int, cells: Int, step: Int, minY: Int, maxY: Int): String {
        val generator = generator(seed, minY, maxY) ?: return failed()
        failures.values.removeAll { it.stage != "load" }
        return encode(TerrainPreviewResult.serializer(), TerrainPreview.map(generator, x0, z0, cells, step, ::scriptErrors))
    }

    /** [width] columns of the ground from block [from] along x (at z [at]) or, when [alongX] is false, along z: a [TerrainPreviewResult]. */
    fun slice(seed: String, alongX: Boolean, at: Int, from: Int, width: Int, minY: Int, maxY: Int): String {
        val generator = generator(seed, minY, maxY) ?: return failed()
        failures.values.removeAll { it.stage != "load" }
        return encode(TerrainPreviewResult.serializer(), TerrainPreview.slice(generator, alongX, at, from, width, ::scriptErrors))
    }

    /** Lets go of the script's Lua state: call it when the previewer won't be asked again. */
    fun close() {
        bound?.second?.close()
        bound = null
        failures.clear()
    }

    private fun generator(seed: String, minY: Int, maxY: Int): TerrainGenerator? {
        val terrain = terrain ?: return null
        val key = Triple(seed.toLongOrNull() ?: 0L, minY, maxY)
        bound?.takeIf { it.first == key }?.let { return it.second }
        close()
        val scripts = terrain.script?.let {
            val lua = checkNotNull(terrainLua) { "a generator with a script needs loadTerrainLua to have finished" }
            TerrainScripts(lua, sources) { failure -> failures.getOrPut(failure.message) { failure } }
        }
        return terrain.bind(key.first, minY, maxY, scripts).also { bound = key to it }
    }

    private fun scriptErrors(): List<TerrainScriptError> = failures.values.take(MAX_SCRIPT_ERRORS).map {
        val (file, line) = it.location
        TerrainScriptError(it.stage, it.message, file, line)
    }

    private fun failed() = encode(TerrainPreviewResult.serializer(), TerrainFailed(problems))

    private companion object {
        /** The most failures one drawing reports: a script that fails at every column says so a few times, not thousands. */
        const val MAX_SCRIPT_ERRORS = 8
    }
}

/** The Lua terrain scripts run on in JS, once [loadTerrainLua] has loaded it. */
private var terrainLua: WasmoonPlatform? = null

/**
 * Loads the Lua a terrain's script runs on (Lua 5.4 in WebAssembly, wasmoon's), once: what
 * [terrainPreviewer] needs before it previews a generator with a script. [wasmUri] is where wasmoon's
 * `glue.wasm` is (a bundler's URL for it), or null for beside wasmoon's own script (Node).
 */
fun loadTerrainLua(wasmUri: String?): Promise<Unit> = terrainLua?.let { Promise.resolve(Unit) }
    ?: WasmoonPlatform.load(wasmUri).then { terrainLua = it }

/**
 * Parses, validates (shapes only: the editor's own validation knows the game) and compiles a generator for
 * [TerrainPreviewer]. [structuresJson] is the project structures its decorations name that the editor could
 * read, a map from the name the file gives each to a [TerrainStructureInput] (null for none): one left out
 * isn't placed, as on a server that can't read it. [sourcesJson] is the Lua its script may run, a map from
 * project path to text: `terrain/<id>.lua` and the project's module files (null for none); [blocksJson] the project's
 * block ids (null for none), so a script that names one in full places it without listing it. A generator with a
 * script needs [loadTerrainLua] to have finished; one whose script isn't among the sources draws the file's own
 * ground and says the script failed to load.
 */
fun terrainPreviewer(id: String, text: String, structuresJson: String?, sourcesJson: String?, blocksJson: String?): TerrainPreviewer {
    val path = TerrainKind.pathOf(id)
    val parsed = TerrainKind.parse(text, path)
    val sources = sourcesJson?.let { CanonicalJson.json.decodeFromString<Map<String, String>>(it) }.orEmpty()
    if (parsed is CanonicalJson.Parsed.Failed) return TerrainPreviewer(null, listOf(parsed.problem), sources)
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val sink = ProblemSink(path)
    TerrainValidator.validate(file, sink, null)
    if (sink.problems.hasErrors) return TerrainPreviewer(null, sink.problems, sources)
    val structures = structuresJson?.let {
        CanonicalJson.json.decodeFromString<Map<String, TerrainStructureInput>>(it)
    }.orEmpty().mapValues { (_, s) ->
        StructureTemplate(s.size[0], s.size[1], s.size[2], s.palette, s.blocks.toIntArray())
    }
    // Without the game's facts here, any game block state the script names in full is taken; a project block, when
    // it's one of [blocksJson]'s.
    val blocks = blocksJson?.let { CanonicalJson.json.decodeFromString<List<String>>(it) }.orEmpty().toSet()
    val compiled = TerrainCompiler.compile(file, TerrainKind.scriptPathOf(id)).withStructures(structures)
    return TerrainPreviewer(compiled.withScriptBlocks(sources.values, { true }, blocks::contains), emptyList(), sources)
}

/** What a new `terrain/<id>.lua` starts as. */
fun newTerrainScript(): String = TerrainKind.scriptTemplate()

/**
 * A particle effect compiled once and stepped tick by tick, for the editor's
 * preview: the server's own sampler, so the preview draws the points the
 * server would send. When it doesn't compile, [step] answers with the problems
 * that stop it.
 */
class ParticleEffectSampler internal constructor(
    private val effect: CompiledEffect?,
    private val problems: List<Problem>,
    private val seed: Int
) {
    private var sampler = effect?.let { EffectSampler(it, seed.toLong()) }

    /** The current tick's spawns in effect space, then advances: an [EffectStepResult]. */
    fun step(loop: Boolean): String {
        val sampler = sampler ?: return encode(EffectStepResult.serializer(), EffectStepFailed(problems))
        val tick = sampler.tick
        val spawns = sampler.step(loop)
        val radii = sampler.effect.emitters.mapNotNull { emitter ->
            emitter.radiusCurve?.let { emitter.name to it.at(tick.toDouble()) }
        }.toMap()
        return encode(EffectStepResult.serializer(), EffectStepped(tick, spawns, radii, sampler.finished))
    }

    /** Back to tick 0 with the same seed, so a replay draws the same points. */
    fun reset() {
        sampler = effect?.let { EffectSampler(it, seed.toLong()) }
    }
}

/** Parses, validates and compiles a particle effect once, for [ParticleEffectSampler.step] to step through. */
fun particleEffectSampler(id: String, text: String, seed: Int): ParticleEffectSampler {
    val path = ParticleEffectKind.pathOf(id)
    val parsed = ParticleEffectKind.parse(text, path)
    if (parsed is CanonicalJson.Parsed.Failed) return ParticleEffectSampler(null, listOf(parsed.problem), seed)
    val file = (parsed as CanonicalJson.Parsed.Ok).value
    val (compiled, problems) = ParticleEffectCompiler.compileChecked(id, file, path, null)
    return ParticleEffectSampler(compiled, problems, seed)
}

/**
 * A curve channel's value at [tick], as the sampler reads it: [keysJson] is
 * the channel's keys (`NumberKey[]`, or `ColorKey[]` when [color]). JSON: a
 * number, a `#rrggbb` string, or null for no keys. For seeding a new key
 * with the value the curve already has there.
 */
fun particleCurveAt(keysJson: String, color: Boolean, tick: Double): String {
    if (color) {
        val keys = CanonicalJson.json.decodeFromString(ListSerializer(ColorKey.serializer()), keysJson)
        if (keys.isEmpty()) return "null"
        return encode(String.serializer(), formatColor(ColorCurve.of(keys).at(tick)))
    }
    val keys = CanonicalJson.json.decodeFromString(ListSerializer(NumberKey.serializer()), keysJson)
    if (keys.isEmpty()) return "null"
    return encode(Double.serializer(), NumberCurve.of(keys).at(tick))
}

/**
 * One roll of loot table [table] (`showcase:treasure`, `library:gems`) as the
 * server would roll it with no player, tool or luck, from [seed].
 * [tablesJson] is every loot table there is, the project's and its packages',
 * by its full name, each text as its file says (`{ "showcase:treasure": "{…}" }`):
 * a table's names mean what they do in its own package, as the server reads
 * them, so what a roll gives names items in full (`library:gem`). JSON: a
 * `LootPreview`.
 */
fun rollLoot(tablesJson: String, table: String, seed: Int): String {
    val texts = CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), tablesJson)
    fun read(key: ResourceKey): LootTableFile? {
        val text = texts[key.toString()] ?: return null
        val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
        val qualified = ReferenceIndex.qualify(LootTableKind.serializer.descriptor, element, key.namespace)
        return runCatching { CanonicalJson.json.decodeFromJsonElement(LootTableKind.serializer, qualified) }.getOrNull()
    }
    val preview = try {
        val start = ResourceKey.parse(table) ?: throw IllegalStateException("\"$table\" isn't a loot table's full name")
        val file = read(start) ?: throw IllegalStateException("\"$table\" doesn't read")
        val roller = LootRoller { reference ->
            val key = ResourceKey.parse(reference.text) ?: return@LootRoller null
            read(key) ?: throw IllegalStateException("there's no loot table \"$reference\" to roll")
        }
        val drops = roller.roll(file, LootContext.NONE, Random(seed)).map { drop ->
            when (drop) {
                is LootDrop.Stack -> LootPreviewDrop(item = drop.item, count = drop.count)
                is LootDrop.Vanilla -> LootPreviewDrop(table = drop.table)
            }
        }
        LootPreview(drops)
    } catch (e: IllegalStateException) {
        LootPreview(emptyList(), e.message)
    }
    return encode(LootPreview.serializer(), preview)
}
