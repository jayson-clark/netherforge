package dev.netherforge.format.project

import dev.netherforge.format.advancement.AdvancementKind
import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.block.GameBlockKind
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.buff.BuffKind
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.cutscene.CutsceneKind
import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.dimensiontype.DimensionTypeKind
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.loot.LootTableKind
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.migration.MigrationKind
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.particle.ParticleEffectKind
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.screen.ScreenFontKind
import dev.netherforge.format.screen.ScreenKind
import dev.netherforge.format.screen.VideoKind
import dev.netherforge.format.sky.SkyKind
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.text.DefaultFontFile
import dev.netherforge.format.wasm.WasmModuleKind
import dev.netherforge.format.workspace.WorkspaceKind
import dev.netherforge.format.world.BuildKind
import dev.netherforge.format.world.MapKind
import dev.netherforge.format.world.StructureGenerationKind
import dev.netherforge.format.world.StructureKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The registry of resource kinds: the one list of them. Loading, the JS
 * exports, the contract (schemas and the editor's kind table), the CLI, the
 * plugin's hot reload and the golden tests all go through it, so a new kind
 * is a [KindSpec] added here, its model, its validator and its editor view.
 */
object Kinds {
    /**
     * Every resource kind, in the order one reload batch reloads them (the
     * hot-reload skill): resource packs first (menus' skins and scripts' glyphs read
     * the new build), particle effects and cutscenes (data the restarting
     * scripts may play at once), WebAssembly modules (which the restarting scripts may load at once), modules (which
     * find what required them), the data kinds,
     * items (the looks menus' slots take), recipes (built from those looks),
     * loot tables (data scripts roll), blocks (their drops are loot tables; the
     * world's blocks are drawn as the pack says), the game's blocks' rules (which refuse the blocks' carriers), biomes and dimension types (the server learns them only at start), terrains (the ores' blocks are blocks), advancements (which the server
     * learns only at start: a reload says it needs a restart),
     * then the windows, dialogs, screens and centities whose scripts restart (screen fonts and videos come early:
     * data the restarting screens draw and play).
     */
    val all: List<KindSpec<*, *>> = listOf(
        ResourcePackKind,
        ParticleEffectKind,
        CutsceneKind,
        ScreenFontKind,
        VideoKind,
        WasmModuleKind,
        ModuleKind,
        MigrationKind,
        StructureKind,
        BuildKind,
        MapKind,
        ItemKind,
        RecipeKind,
        LootTableKind,
        BuffKind,
        BlockKind,
        GameBlockKind,
        DatapackKind,
        BiomeKind,
        SkyKind,
        DimensionTypeKind,
        TerrainKind,
        AdvancementKind,
        MenuKind,
        DialogKind,
        ScreenKind,
        CentityKind
    )

    /**
     * Every JSON document format reads and writes: the project's own files,
     * each JSON kind's, then a bundle's manifest (which no project holds).
     */
    val documents: List<DocumentKind<*>> =
        listOf(ManifestKind, LockKind, DefaultFontKind, VanillaKind, StructureGenerationKind, WorkspaceKind) +
            all.filterIsInstance<DocumentResourceKind<*, *>>() + BundleKind

    private val byFolder = all.associateBy { it.folder }

    init {
        check(byFolder.size == all.size) { "two kinds share a folder" }
        check(documents.map { it.id }.toSet().size == documents.size) { "two kinds share an id" }
    }

    fun byId(id: String): KindSpec<*, *>? = all.firstOrNull { it.id == id }

    fun document(id: String): DocumentKind<*>? = documents.firstOrNull { it.id == id }

    /** The kind whose folder [folder] is. */
    fun inFolder(folder: String): KindSpec<*, *>? = byFolder[folder]

    /**
     * What a project path is: which resource it belongs to and what part of
     * it, or a project file format owns. Null for anything else (a README,
     * `.gitignore`, a stray file in a kind's folder, a hidden one). Works
     * from the path alone; it doesn't check that an id or a group is usable
     * ([Names.isId], [Names.isGroup]) or that the file exists. Group folders,
     * `(weapons)`, may come between a kind's folder and a resource
     * (`items/(weapons)/ruby_wand/item.json`): the id is `ruby_wand` all the same.
     *
     * A package path ([PackagePaths]: `acme:items/coin/item.json`) is a file
     * of the dependency `acme`, classified the same way with its [ClassifiedPath.pkg] set.
     */
    fun classify(path: String): ClassifiedPath? {
        val (pkg, local) = PackagePaths.split(path.replace('\\', '/'))
        val found = classifyLocal(local) ?: return null
        return if (pkg == null) found else found.inPackage(pkg)
    }

    private fun classifyLocal(path: String): ClassifiedPath? {
        val normalized = path.trim('/')
        when (normalized) {
            ProjectManifest.FILE_NAME -> return ClassifiedPath(role = PathRole.PROJECT, document = ManifestKind.id)
            LockFile.FILE_NAME -> return ClassifiedPath(role = PathRole.PROJECT, document = LockKind.id)
            DefaultFontFile.FILE -> return ClassifiedPath(role = PathRole.PROJECT, document = DefaultFontKind.id)
            VanillaFile.FILE -> return ClassifiedPath(role = PathRole.PROJECT, document = VanillaKind.id)
        }
        val parts = normalized.split('/')
        val kind = byFolder[parts[0]] ?: return null
        // Group folders, `(weapons)/(swords)`, sit between the kind's folder and the resource's.
        var at = 1
        while (at < parts.size && Names.looksLikeGroup(parts[at])) at++
        val group = parts.subList(1, at).joinToString("/").ifEmpty { null }
        val name = parts.getOrNull(at) ?: return group?.let { ClassifiedPath(kind.id, null, PathRole.GROUP, group = it) }
        // Hidden files (`.DS_Store`, `.gitkeep`) are nobody's.
        if (name.isEmpty() || name.startsWith(".")) return null
        val document = (kind as? DocumentResourceKind<*, *>)?.id
        return when (val layout = kind.layout) {
            is Layout.Folder -> {
                val rest = parts.drop(at + 1).joinToString("/")
                val role = when {
                    parts.size == at + 1 -> PathRole.FOLDER
                    rest == layout.main -> PathRole.MAIN
                    else -> PathRole.FILE
                }
                ClassifiedPath(kind.id, name, role, document.takeIf { role == PathRole.MAIN }, rest, group = group)
            }
            is Layout.SingleFile -> {
                if (parts.size != at + 1) return null
                val companion = layout.companion
                if (companion != null && name.endsWith(companion) && name.length > companion.length) {
                    // The document beside the file: part of its resource, and a document of its own kind.
                    return ClassifiedPath(
                        kind.id,
                        name.removeSuffix(companion),
                        PathRole.FILE,
                        kind.companionDocument?.id,
                        name,
                        group = group
                    )
                }
                if (!name.endsWith(layout.extension) || name.length == layout.extension.length) return null
                ClassifiedPath(
                    kind.id,
                    name.removeSuffix(layout.extension),
                    PathRole.MAIN,
                    document,
                    name.takeIf {
                        companion != null
                    },
                    group = group
                )
            }
        }
    }
}

/** What part of a resource (or the project) a path is. */
@Serializable
enum class PathRole {
    /** The file a resource is read from: its JSON, a single-file kind's one file, a map's `level.dat`. */
    @SerialName("main")
    MAIN,

    /** Any other file in a resource's folder: a script, a texture, a module's Lua. */
    @SerialName("file")
    FILE,

    /** A resource's folder itself (`menus/shop`): what rename and delete act on, and where some problems point. */
    @SerialName("folder")
    FOLDER,

    /** A file of the project's own that format reads: `netherforge.json`, `fonts/default.json`. */
    @SerialName("project")
    PROJECT,

    /** A group folder (`items/(weapons)`): it sorts resources, and is none itself. */
    @SerialName("group")
    GROUP
}

/** [Kinds.classify]'s answer. */
@Serializable
class ClassifiedPath(
    /** The resource kind ([KindSpec.id]); null for a project file. */
    val kind: String? = null,
    /** The resource's id in its package; null for a project file or a group folder. */
    val id: String? = null,
    val role: PathRole,
    /** The document kind format reads this file as ([DocumentKind.id]), or null when it isn't one. */
    val document: String? = null,
    /** For a folder resource: the path inside its folder (`lib/util.lua`; empty for the folder itself). */
    val rest: String? = null,
    /** The dependency a package path is in (its namespace); null for the project's own files. */
    @SerialName("package") val pkg: String? = null,
    /**
     * The group folders the resource (or group folder) is in, as on disk: `(weapons)/(swords)`. Null when it sits
     * in its kind's folder. Groups only sort resources: the id is the same in any.
     */
    val group: String? = null
) {
    internal fun inPackage(namespace: String) = ClassifiedPath(kind, id, role, document, rest, namespace, group)
}

/**
 * How a dependency's files are named beside the project's own: `<namespace>:<path>`
 * (`acme:modules/api/init.lua`), a package path. A project path never holds
 * `:`, so the two can't be confused. Problems in a package, its scripts'
 * chunk names and errors, and the editor's read-only view of it all use them.
 */
object PackagePaths {
    /** [path] inside the package [namespace]. */
    fun of(namespace: String, path: String): String = "$namespace:$path"

    /** A path's package (null for the project's own) and its path inside it. */
    fun split(path: String): Pair<String?, String> {
        val colon = path.indexOf(':')
        if (colon < 0) return null to path
        return path.substring(0, colon) to path.substring(colon + 1)
    }
}
