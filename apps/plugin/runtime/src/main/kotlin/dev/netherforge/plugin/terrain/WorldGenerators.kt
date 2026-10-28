package dev.netherforge.plugin.terrain
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.lua.LuajavaPlatform
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.terrain.CompiledScript
import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainFile
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.terrain.TerrainScriptFailure
import dev.netherforge.format.terrain.TerrainScripts
import dev.netherforge.format.world.StructureKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.block.CustomBlocks
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.ProjectGenerator
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.world.ManagedWorlds
import java.nio.file.Path

/**
 * The project's terrains (`terrain/<id>.json`), handed to the
 * adapter, which runs them on the server's chunk threads
 * ([WorldManagerOps.publishGenerators][dev.netherforge.plugin.platform.WorldManagerOps.publishGenerators]).
 *
 * **Nothing here runs a generator.** A generator is format's
 * [CompiledTerrain] (pure, immutable, no Lua, no services), and the chunk
 * threads call it while this service sits on the main thread: all it does is
 * give the adapter the compiled files and, for each block a file names that
 * is the project's own (in a layer, the stone, the floor, an ore or a
 * decoration), the note block state it's held as ([CustomBlocks.stateOf];
 * [CustomBlocks] adopts those states as the blocks when a chunk loads, so a
 * block needs nothing from here once its chunk is made), and each biome its
 * areas name as the server knows it (a project biome's `<namespace>:<id>`,
 * which the server has from the start-up datapack). The structures
 * decorations place are read here too, with the server's own structure
 * loader ([StructureOps.template][dev.netherforge.plugin.platform.StructureOps.template]),
 * and linked in ([CompiledTerrain.withStructures]). A reload publishes the
 * whole set again: chunks generated from then on use the new files, chunks
 * already made keep what they have.
 *
 * **A file's script** (`terrain/<id>.lua`, W5.6) runs on the chunk threads
 * too, in Lua states of format's own ([TerrainScripts], on luajava), never
 * this session's: no `nf`, nothing of the server, the same blocks for a seed
 * every time. Here it's only read (the script and the modules it may
 * `require`, so a chunk thread never reads a file), checked once (its body
 * run, so a script that doesn't load is a problem at once) and published
 * with its generator. Saving the script or a module publishes again; what
 * its stages fail at on the chunk threads comes back through
 * [TerrainScriptFailures], said once per publish on the main thread.
 *
 * It also makes the worlds `netherforge.json` names with a `terrain` or a
 * `dimensionType` when the session starts ([ManagedWorlds.ensureMade]), after the blocks have
 * started, so the chunks it generates are looked at for custom blocks; and
 * says whether the server's main world, whose generator the server took as
 * it started ([StartupGenerators]), still has the one the manifest names.
 */
internal class WorldGenerators(
    private val platform: Platform,
    private val log: RuntimeLog,
    private val blocks: () -> CustomBlocks,
    private val worlds: () -> ManagedWorlds,
    private val snapshot: () -> ProjectSnapshot,
    /** The file at a project or package path, as the project's files resolve it. */
    private val files: (String) -> Path?,
    /** Every file of the project (project paths) and its packages (package paths), as last loaded. */
    private val listFiles: () -> Collection<String>,
    /** The text of the file at a project or package path. */
    private val read: (String) -> String?,
    private val startup: StartupGenerators,
    private val problemsChanged: () -> Unit
) : RuntimeService {
    override val name get() = "terrains"

    private var generators: Map<String, ProjectGenerator> = emptyMap()
    private var problems: List<Problem> = emptyList()
    private val failures = TerrainScriptFailures()

    /** What the worlds `netherforge.json` makes couldn't have, from the last start. */
    private var worldProblems: List<Problem> = emptyList()

    /** The generators as the project names them (`hills`, a package's `acme:hills`), sorted. */
    fun ids(): List<String> = generators.keys.sorted()

    fun has(id: String): Boolean = id in generators

    override fun define(project: SessionProject) {
        publish(project.running(TerrainKind))
        startup.check(project.snapshot)
    }

    override fun start() {
        val snapshot = snapshot()
        worldProblems = worlds().ensureMade(snapshot.manifest?.worlds.orEmpty(), ::generatorOf) { dimensionKey(snapshot, it) }
        if (worldProblems.isNotEmpty()) problemsChanged()
    }

    /**
     * Generators, and the project's biomes and datapacks (which define biomes too): the server learns a biome only as it
     * starts (the start-up datapack, whose check says a change needs a restart), so all a biome's or a datapack's reload
     * changes here is whether an area naming one is told it has problems.
     */
    override val reloads: Set<KindSpec<*, *>> get() = setOf(TerrainKind, BiomeKind, DatapackKind)

    /** Data the adapter's chunk threads read: nothing runs on it, nothing restarts. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) batch.data(Resource(kind, id))
        publish(batch.snapshot.running(TerrainKind))
        problemsChanged()
    }

    /**
     * A block's state can move when the project's blocks change: what's written for one moves with it. A structure a
     * decoration places is read again when it's saved ([StructureStore] has the adapter forget the old file first).
     * A script may `require` modules, read as it's published: a saved module is read again.
     */
    override val follows: Set<String> get() = setOf(BlockKind.id, StructureKind.id, ModuleKind.id)

    override fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) {
        val running = batch.snapshot.running(TerrainKind)
        if (kind == ModuleKind.id && running.values.none { it.script != null }) return
        publish(running)
        problemsChanged()
    }

    override fun problems(): List<Problem> = problems + worldProblems + failures.problems() + startup.problems()

    /** What the scripts failed at on the chunk threads since the last tick. */
    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.UPKEEP && failures.drain(log::warn)) problemsChanged()
    }

    private fun generatorOf(reference: String): String? = generatorId(snapshot(), reference)

    private fun publish(running: Map<String, CompiledTerrain>) {
        val found = mutableListOf<Problem>()
        val snapshot = snapshot()
        failures.restart()
        val listed = listFiles()
        generators = resolveGenerators(
            running,
            snapshot.namespace,
            runningBiomes(snapshot),
            blocks()::stateOf,
            { name -> structureFile(snapshot, name, files)?.let(platform.structures::template) },
            scriptBlocks = { id, terrain ->
                // The blocks its script names in full join its palette, so it needn't list them.
                val sources = terrain.script?.let { sourcesFor(id, it, listed, read) }.orEmpty()
                val game = platform.game
                terrain.withScriptBlocks(sources.values, { game.block(it) != null }) { blocks().stateOf(it) != null }
            },
            scriptsOf = { id, terrain ->
                scriptsFor(id, terrain, listed, read, failures.reporter(id))?.also { scripts ->
                    scripts.check(terrain)?.let { failures.add(id, it, log::warn) }
                }
            }
        ) { id, message ->
            log.warn("terrain $id: $message")
            found += ProblemCodes.RUNTIME_TERRAIN.at(snapshot.placeOf(TerrainKind, id).path, message)
        }
        problems = found
        platform.worldManager.publishGenerators(generators)
    }

    internal companion object {
        /** The id a `netherforge.json` reference to a generator names, as the generators are published (`hills`, a package's `acme:hills`); null when it names none. */
        fun generatorId(snapshot: ProjectSnapshot, reference: String): String? {
            val key = snapshot.references.resolve(RefKind.TERRAIN, reference) ?: return null
            return if (key.namespace == snapshot.namespace) key.path else "${key.namespace}:${key.path}"
        }

        /**
         * What generator [id]'s script runs with, or null when [terrain] has none: luajava, the script and the
         * modules of its package read from [files] (project paths, and package paths for a package's generator,
         * `acme:hills`), and [failed] told what it fails at.
         */
        fun scriptsFor(
            id: String,
            terrain: CompiledTerrain,
            files: Collection<String>,
            read: (String) -> String?,
            failed: (TerrainScriptFailure) -> Unit
        ): TerrainScripts? {
            val script = terrain.script ?: return null
            return TerrainScripts(LuajavaPlatform, sourcesFor(id, script, files, read), failed)
        }

        /** The Lua generator [id]'s [script] may run: itself and the modules of its package, read from [files]. */
        fun sourcesFor(id: String, script: CompiledScript, files: Collection<String>, read: (String) -> String?): Map<String, String> {
            val namespace = id.substringBefore(':', "").ifEmpty { null }
            val own = files.map(PackagePaths::split).filter { it.first == namespace }.map { it.second }
            return TerrainScripts.sourcesOf(script, own) { read(namespace?.let { ns -> PackagePaths.of(ns, it) } ?: it) }
        }

        /** The dimension type a `netherforge.json` reference names, as the server knows it (`showcase:deep`); null when it names none. */
        fun dimensionKey(snapshot: ProjectSnapshot, reference: String): String? =
            snapshot.references.resolve(RefKind.DIMENSION_TYPE, reference)?.toString()

        /** The server keys of the project's biomes without errors and its datapacks': the ones in the start-up datapack. */
        fun runningBiomes(snapshot: ProjectSnapshot): Set<String> =
            snapshot.running(BiomeKind).keys.mapNotNull { BiomeKind.keyOf(it, snapshot.namespace) }.toSet() + DatapackKind.biomes(snapshot)

        /** The file of the structure a generator names, the project's own or a package's, as [files] resolves its path. */
        fun structureFile(snapshot: ProjectSnapshot, name: String, files: (String) -> Path?): Path? {
            val key = snapshot.references.resolve(RefKind.STRUCTURE, name) ?: return null
            // A package's is at its package path, in whichever group folder it is.
            return files(snapshot.placeOf(StructureKind, key.relativeTo(snapshot.namespace).text).path)
        }

        /**
         * Each generator linked to the structures its decorations place ([templateOf] reads one; one that can't be read
         * isn't placed), every block of its palette as the state written into a chunk ([stateOf] gives a project
         * block's; one with no state is the file's stone instead), and every biome as the server's key: a project biome
         * with problems isn't in the start-up datapack ([biomes] are the ones that are), so the adapter makes its areas
         * plains. [problem] is told about each of these. A file with a script runs it with what [scriptsOf] gives.
         */
        fun resolveGenerators(
            running: Map<String, CompiledTerrain>,
            home: String,
            biomes: Set<String>,
            stateOf: (String) -> String?,
            templateOf: (String) -> StructureTemplate?,
            /** [terrain] with the blocks its script names added to its palette; as it is by default. */
            scriptBlocks: (id: String, terrain: CompiledTerrain) -> CompiledTerrain = { _, terrain -> terrain },
            scriptsOf: (id: String, terrain: CompiledTerrain) -> TerrainScripts?,
            problem: (id: String, message: String) -> Unit
        ): Map<String, ProjectGenerator> = running.mapValues { (id, compiled) ->
            val terrain = scriptBlocks(id, link(compiled, templateOf) { problem(id, it) })
            val stone = terrain.palette[terrain.stone]
            val fallback = (stone as? TerrainBlock.Vanilla)?.state ?: TerrainFile.DEFAULT_STONE
            val states = terrain.palette.map { block ->
                when (block) {
                    is TerrainBlock.Vanilla -> block.state
                    is TerrainBlock.Custom -> stateOf(block.name) ?: fallback.also {
                        problem(
                            id,
                            "The block \"${block.name}\" has no state the server can hold it in (see the problems of its blocks), " +
                                "so $fallback is generated in its place"
                        )
                    }
                }
            }
            val keys = terrain.biomes.associateWith { biome ->
                val key = requireNotNull(BiomeKind.keyOf(biome, home)) { "a compiled generator's biome \"$biome\" is a biome" }
                if (!key.startsWith("${GameIds.NAMESPACE}:") && key !in biomes) {
                    problem(
                        id,
                        "The biome \"$biome\" has problems (see its file), so its areas are plains until it's fixed and the server restarts"
                    )
                }
                key
            }
            ProjectGenerator(terrain, states, keys, scriptsOf(id, terrain))
        }

        /** [terrain] with the structures its decorations place; one that can't be read isn't placed, and [problem] is told. */
        private fun link(terrain: CompiledTerrain, templateOf: (String) -> StructureTemplate?, problem: (String) -> Unit): CompiledTerrain {
            if (terrain.structures.isEmpty()) return terrain
            val templates = HashMap<String, StructureTemplate>()
            for (name in terrain.structures) {
                val template = templateOf(name)
                if (template == null) {
                    problem("The structure \"$name\" can't be read as one (see the problems of its file), so it isn't placed")
                } else {
                    templates[name] = template
                }
            }
            return terrain.withStructures(templates)
        }
    }
}
