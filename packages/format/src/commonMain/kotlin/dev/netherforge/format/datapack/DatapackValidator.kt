package dev.netherforge.format.datapack

import dev.netherforge.format.Location
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.json.JsonElement

/**
 * What NetherForge checks of a datapack passed through ([DatapackKind]), and nothing more: `pack.mcmeta`'s formats
 * and overlays, that every file is a worldgen entry or tag where the game looks for one and reads as JSON, that it's
 * in a namespace the project may write (its own, or `minecraft` to replace one of the game's), that it doesn't
 * collide with what NetherForge writes itself or with another datapack, and the references [WorldgenReferences]
 * finds: in the project's namespace against the project, in the game's against the game's registries. Whether a file
 * is a valid entry of its registry (its fields, a density function's arguments) only the server knows: it says so
 * as it starts (`runtime.datapack`).
 */
object DatapackValidator {
    /** Checks [meta] and its files with what [ctx] knows on its own: the game, not the project. */
    fun validate(meta: PackMeta, ctx: ResourceContext) {
        val sink = ctx.sink
        val range = PackFormat.range(meta.pack.minFormat, meta.pack.maxFormat)
        val target = ctx.game?.dataPackFormat?.let(PackFormat::of)
        if (range == null) {
            sink.report(ProblemCodes.DATAPACK_FORMAT, FORMAT_RULE, "$.pack")
        } else {
            if (target != null && target !in range) {
                sink.report(
                    ProblemCodes.DATAPACK_VERSION,
                    "This datapack is written for data pack formats ${range.start} to ${range.endInclusive}, and Minecraft " +
                        "${ctx.game?.minecraftVersion} reads $target: check its files against that version's worldgen and " +
                        "change min_format or max_format (or add an overlay for it)",
                    "$.pack"
                )
            }
        }
        val seen = HashSet<String>()
        meta.overlays?.entries?.forEachIndexed { index, overlay ->
            val at = "$.overlays.entries[$index]"
            if (PackFormat.range(overlay.minFormat, overlay.maxFormat) == null) sink.report(ProblemCodes.DATAPACK_FORMAT, FORMAT_RULE, at)
            val directory = overlay.directory
            when {
                !DatapackLayout.OVERLAY.matches(directory) || directory == DatapackLayout.DATA ->
                    sink.report(
                        ProblemCodes.DATAPACK_OVERLAY,
                        "\"$directory\" isn't an overlay's folder: lowercase letters, digits, _, - and ., and not \"${DatapackLayout.DATA}\"",
                        "$at.directory"
                    )
                !seen.add(directory) -> sink.report(ProblemCodes.DATAPACK_OVERLAY, "\"$directory\" is listed twice", "$at.directory")
            }
        }
        val overlays = DatapackLayout.overlaysOf(meta)
        // The game's data is the target version's: it's asked only about the files a server of that version reads
        // (the pack's own and its overlays for that format; with no format to go by, the pack's own).
        val compiled = CompiledDatapack.of(meta, ctx.files)
        val read = when {
            target != null && compiled != null -> compiled.filesFor(target).values.map { it.file }.toSet()
            else -> compiled?.files.orEmpty().filter { it.overlay == null }.map { it.file }.toSet()
        }
        val problems = mutableListOf<Problem>()
        for (file in ctx.files.sorted()) {
            if (file == PackMeta.FILE_NAME) continue
            val at = ctx.pathIn(DatapackKind, file)
            val path = when (val placed = DatapackLayout.place(file, overlays)) {
                is DatapackLayout.Placed.Wrong -> {
                    problems += ProblemCodes.DATAPACK_FILE.at(at, placed.message)
                    continue
                }
                is DatapackLayout.Placed.Ok -> placed.path
            }
            val json = when (val parsed = ctx.json(file, at)) {
                null -> continue
                is CanonicalJson.Parsed.Failed -> {
                    problems += parsed.problem
                    continue
                }
                is CanonicalJson.Parsed.Ok -> parsed.value
            }
            againstGame(path, json, ctx.game?.takeIf { file in read }, at, problems)
        }
        ctx.report(problems)
    }

    /** Checks [meta]'s files against the project: namespaces, collisions, and references to the project's own. */
    fun crossCheck(meta: PackMeta, ctx: KindContext) {
        val home = ctx.references.home
        val problems = mutableListOf<Problem>()
        val own = ownEntries(ctx)
        val datapacks = ctx.models(DatapackKind).mapValues { (id, other) -> DatapackLayout.entries(other, ctx.filesOf(DatapackKind, id)) }
        val defined = datapacks.values.flatten().filter { it.namespace == home }.map { Defined(it.registry, it.path, it.tag) }.toSet() +
            own.keys.map { Defined(it.first, it.second, false) }
        // Every file another datapack writes, by where it goes: the first (by id) to write one keeps it.
        val earlier = datapacks.filterKeys { it < ctx.id }.values.flatten().associateBy { it.target }
        for (path in datapacks[ctx.id].orEmpty()) {
            val at = ctx.pathIn(DatapackKind, path.file)
            when {
                path.namespace == home -> if (!ResourceRef.RESOURCE_PATH.matches(path.path)) {
                    problems += ProblemCodes.DATAPACK_FILE.at(
                        at,
                        "\"${path.path}\" isn't an id the project's references can name: lowercase letters, digits and _, in folders"
                    )
                    continue
                }
                path.namespace == GameIds.NAMESPACE && !ctx.inPackage -> {}
                else -> {
                    val may = if (ctx.inPackage) {
                        "its own namespace, \"$home\""
                    } else {
                        "the project's namespace, \"$home\", or \"${GameIds.NAMESPACE}\""
                    }
                    problems += ProblemCodes.DATAPACK_NAMESPACE.at(at, "A datapack here writes only $may, not \"${path.namespace}\"")
                    continue
                }
            }
            own[path.registry to path.path]?.takeIf { path.namespace == home && !path.tag }?.let { (kind, id) ->
                problems += ProblemCodes.DATAPACK_CONFLICT.at(
                    at,
                    "The ${kind.id.replace('_', ' ')} \"$id\" is \"${path.key}\" in ${path.registry} too: rename one of them",
                    related = listOf(Location(ctx.placeOf(kind, id).path))
                )
            }
            earlier[path.target]?.let { other ->
                val owner = datapacks.entries.first { (_, paths) -> other in paths }.key
                problems += ProblemCodes.DATAPACK_CONFLICT.at(
                    at,
                    "Datapack \"$owner\" writes ${path.target} too: keep it in one of them",
                    related = listOf(Location(ctx.placeOf(DatapackKind, owner).file(other.file)))
                )
            }
            val json = (ctx.json(path.file, at) as? CanonicalJson.Parsed.Ok)?.value ?: continue
            for (found in referencesIn(path, json) ?: continue) {
                val (tag, key) = keyOf(found.text) ?: continue
                when (key.namespace) {
                    GameIds.NAMESPACE -> {}
                    home -> if (found.targets.none { Defined(it, key.path, tag) in defined }) {
                        // Said in the target version's words when its game data says which name the registry has there.
                        val registry = found.targets.firstOrNull { ctx.game?.registry(registryKey(it)) != null } ?: found.targets.first()
                        problems += ProblemCodes.DATAPACK_REFERENCE.at(
                            at,
                            "The project has no ${named(registry, key, tag)}: " +
                                "a datapack's ${DatapackLayout.entryFile(key, registry, tag)} would be it",
                            found.path
                        )
                    }
                    else -> if (key.namespace !in ctx.references.packages) {
                        problems += ProblemCodes.DATAPACK_REFERENCE.at(
                            at,
                            "\"${key.namespace}\" is neither this project's namespace (\"$home\"), the game's, nor a package's it depends on",
                            found.path
                        )
                    }
                }
            }
        }
        ctx.report(problems)
    }

    private data class Defined(val registry: String, val path: String, val tag: Boolean)

    /** What the project's own resources write into the start-up datapack, by registry and path: which resource does. */
    private fun ownEntries(ctx: KindContext): Map<Pair<String, String>, Pair<KindSpec<*, *>, String>> {
        val out = HashMap<Pair<String, String>, Pair<KindSpec<*, *>, String>>()
        for (kind in Kinds.all) entriesOf(kind, ctx, out)
        return out
    }

    private fun <T> entriesOf(
        kind: KindSpec<T, *>,
        ctx: KindContext,
        into: MutableMap<Pair<String, String>, Pair<KindSpec<*, *>, String>>
    ) {
        for ((id, value) in ctx.models(kind)) {
            for ((registry, paths) in kind.datapackEntries(id, value)) for (path in paths) into.getOrPut(registry to path) { kind to id }
        }
    }

    /** Checks one file's [json] with what the [game] knows: its registry, a `minecraft` file's replacing something, and the game's ids it names. */
    private fun againstGame(path: DatapackPath, json: JsonElement, game: GameData?, at: String, problems: MutableList<Problem>) {
        val found = referencesIn(path, json)
        if (found == null) {
            problems +=
                ProblemCodes.DATAPACK_TAG.at(
                    at,
                    "A tag is { \"values\": [...] } (and \"replace\": true or false), each an id, a #tag or { \"id\": … }"
                )
            return
        }
        for (reference in found) {
            if (keyOf(reference.text) == null) {
                problems +=
                    ProblemCodes.DATAPACK_REFERENCE.at(
                        at,
                        "\"${reference.text}\" isn't an id, like \"${GameIds.NAMESPACE}:…\"",
                        reference.path
                    )
            }
        }
        game ?: return
        if (game.registry(registryKey(path.registry)) == null) {
            problems += ProblemCodes.DATAPACK_REGISTRY.at(
                at,
                "Minecraft ${game.minecraftVersion} has no registry ${path.registry}, so the game ignores this file"
            )
            return
        }
        if (path.namespace == GameIds.NAMESPACE) {
            val replaces = if (path.tag) {
                game.tag(
                    registryKey(path.registry),
                    path.key.toString()
                ) != null
            } else {
                game.has(path.registry, path.key)
            }
            if (!replaces) {
                problems += ProblemCodes.DATAPACK_OVERRIDE.at(
                    at,
                    "Minecraft ${game.minecraftVersion} has no ${named(path.registry, path.key, path.tag)} " +
                        "to replace: put new ones in the project's namespace"
                )
            }
        }
        for (reference in found) {
            val (tag, key) = keyOf(reference.text) ?: continue
            if (key.namespace != GameIds.NAMESPACE) continue
            val registry = reference.targets.firstOrNull { game.registry(registryKey(it)) != null } ?: continue
            val has = if (tag) game.tag(registryKey(registry), key.toString()) != null else game.has(registry, key)
            if (!has) {
                problems += ProblemCodes.DATAPACK_REFERENCE.at(
                    at,
                    "Minecraft ${game.minecraftVersion} has no ${named(registry, key, tag)}",
                    reference.path
                )
            }
        }
    }

    /** The references in one file: a tag's values, or what [WorldgenReferences.RULES] find in an entry; null for a tag that isn't one. */
    private fun referencesIn(path: DatapackPath, json: JsonElement): List<WorldgenReferences.Found>? =
        if (path.tag) WorldgenReferences.tagValues(path.registry, json) else WorldgenReferences.find(path.registry, json)

    /** A reference as the game reads one (bare is the game's), and whether it's a tag; null when it isn't an id. */
    private fun keyOf(text: String): Pair<Boolean, ResourceKey>? {
        val tag = text.startsWith("#")
        val id = text.removePrefix("#")
        if (!GameIds.isValid(id)) return null
        val normalized = GameIds.normalize(id)
        return tag to ResourceKey(normalized.substringBefore(':'), normalized.substringAfter(':'))
    }

    private fun GameData.has(registry: String, key: ResourceKey): Boolean =
        registry(registryKey(registry))?.contains(key.toString()) == true

    /** The game's name for the registry whose datapack folder is [registry]: `minecraft:worldgen/biome`. */
    fun registryKey(registry: String) = RegistryKey("${GameIds.NAMESPACE}:$registry")

    /** An entry or tag of [registry] in a message: `placed feature "showcase:rocks"`, `biome tag "#showcase:wet"`. */
    private fun named(registry: String, key: ResourceKey, tag: Boolean) =
        if (tag) "${noun(registry)} tag \"#$key\"" else "${noun(registry)} \"$key\""

    /** A registry folder in a message's words: `worldgen/placed_feature` is a placed feature. */
    private fun noun(registry: String) = registry.substringAfterLast('/').replace('_', ' ')

    private const val FORMAT_RULE =
        "min_format and max_format are each a data pack format (a number, or [major, minor]), the first no later than the second"
}
