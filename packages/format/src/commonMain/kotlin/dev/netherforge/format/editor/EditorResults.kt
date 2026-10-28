package dev.netherforge.format.editor

import dev.netherforge.format.Problem
import dev.netherforge.format.centity.LoopMode
import dev.netherforge.format.codegen.TsName
import dev.netherforge.format.game.Box
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.EffectSpawn
import dev.netherforge.format.project.PackageSource
import dev.netherforge.format.project.RequirementUse
import dev.netherforge.format.ref.RefUse
import dev.netherforge.format.text.TextStyle
import dev.netherforge.format.workspace.WorkspaceFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * What the editor gets back from format's JS exports (jsMain's *Exports.kt).
 * They live here, not beside the exports, so the contract generator can see
 * them: the editor's TypeScript types for them are generated, never written.
 */

/** A document in its one written form. */
@Serializable
class CanonicalResult(
    /** The canonical text, or null when the input doesn't parse. */
    val text: String?,
    val problems: List<Problem>
)

/** A project's problems, and the names in it the editor offers in pickers. */
@Serializable
class ProjectOutline(
    val name: String?,
    /** `netherforge.json`'s namespace: what references naming none resolve in. Null when the manifest doesn't read. */
    val namespace: String?,
    val minecraft: String?,
    /** Every resource that read (possibly with semantic problems): kind (`KindSpec.id`) → ids, for every kind. */
    val resources: Map<String, List<String>>,
    /** The group folders of each resource in one (`(weapons)/(swords)`), by kind then id; one in its kind's folder isn't here. */
    val groups: Map<String, Map<String, String>> = emptyMap(),
    /** Pack id → the keys of what it defines (referenced as `<pack>/<key>`). */
    val resourcePacks: Map<String, ResourcePackOutline>,
    /** Module id → its .lua files, relative to the module folder. */
    val modules: Map<String, List<String>>,
    /** Every problem: the project's, its dependencies', and each package's at package paths (`acme:items/coin/item.json`). */
    val problems: List<Problem>,
    /** The packages the project depends on, their dependencies' included, by namespace. */
    val packages: Map<String, PackageOutline> = emptyMap(),
    /**
     * The `netherforge.lock` the packages make, canonical, or null while one
     * has no version or hash; the editor writes it when the file says otherwise.
     */
    val lock: String? = null,
    /**
     * Every requirement the project and its packages declare (`requires`), each
     * with who declares it: what running the project asks of the server
     * (`Requirement.combined`).
     */
    val requirements: List<RequirementUse> = emptyList(),
    /**
     * For each of the game's registries a reference can name the project's entries of (`worldgen/biome`,
     * `worldgen/placed_feature`), what it names in the project's namespace, sorted: its own resources of that kind
     * and what its datapacks define. What a biome's features or a generator's areas suggest besides the game's.
     */
    val registryNames: Map<String, List<String>> = emptyMap(),
    /**
     * The game's textures the project's resource packs and its packages' redraw (`textures`), sorted: what the editor
     * looks up in the client for `client/vanilla.json`.
     */
    val replacedTextures: List<String> = emptyList(),
    /** The game's models the project's resource packs and its packages' redraw (`models`), sorted, as [replacedTextures]. */
    val replacedModels: List<String> = emptyList()
)

/**
 * One validation of an open project: its [outline], and the files read and
 * validated afresh to get it (the rest were kept from the last validation,
 * unchanged; see `ProjectCache`): project paths, and a package's at package
 * paths (`acme:items/coin/item.json`).
 */
@Serializable
class ProjectValidation(val outline: ProjectOutline, val validated: List<String>)

/** A package the project depends on: where it is, what it is, and what it has. */
@Serializable
class PackageOutline(
    /**
     * Where its files are, which the host reads them by: a folder relative
     * to the project's (`../economy`), or `git:<commit>`, a git package's
     * checkout in the cache.
     */
    val location: String,
    /** Where it came from, as `netherforge.lock` records it. */
    val origin: PackageSource,
    val name: String?,
    val version: String?,
    /** Every resource that read: kind (`KindSpec.id`) → ids, for every kind. */
    val resources: Map<String, List<String>>,
    /** What it exports, by kind folder (`netherforge.json`'s `exports`). */
    val exports: Map<String, List<String>>,
    /** The group folders of each resource in one, by kind then id, as [ProjectOutline.groups]. */
    val groups: Map<String, Map<String, String>> = emptyMap()
)

/**
 * What resolving a project's dependencies needs next ([PackageInputs] hasn't
 * got them yet): the folders to read, by location (`../economy`,
 * `git:<commit>`), and the git repositories to fetch. Ask again once they're
 * there, since a package's own dependencies are only known once it's read.
 */
@Serializable
class PackagesNeeded(val locations: List<String>, val git: List<GitFetchRequest>)

/**
 * A git dependency to fetch into the cache: [url] at exactly [commit] when
 * the lock pins one (nothing to do when its checkout is there already),
 * otherwise at [rev] (its default branch when null). Answered in
 * [PackageInputs.git] under [key].
 */
@Serializable
class GitFetchRequest(val key: String, val url: String, val rev: String?, val commit: String?)

/** A [GitFetchRequest] answered: the [commit] now in the cache, or the [error] that kept it out. */
@Serializable
class GitFetched(val commit: String? = null, val error: String? = null)

/** A package folder the host read: its files (`{ path: text | null }`, as a project's are given) and its content hash. */
@Serializable
class PackageFolder(val files: Map<String, String?>, val hash: String? = null)

/**
 * What the host found of a project's packages, as `loadProject` takes it:
 * every folder read, by location (null: no project there), and every git
 * fetch, by its request's key.
 */
@Serializable
class PackageInputs(val folders: Map<String, PackageFolder?> = emptyMap(), val git: Map<String, GitFetched> = emptyMap())

@Serializable
class ResourcePackOutline(
    val skins: List<String>,
    val glyphs: List<String>,
    val items: List<String>,
    val tooltips: List<String>,
    val equipment: List<String>,
    val blocks: List<String>,
    /** Every sound event's key (`menu/open`): each `.ogg` under `sounds/`, and `pack.json`'s `sounds`. */
    val sounds: List<String>
)

/** Every reference to something ([dev.netherforge.format.ref.RefTarget]): what renaming it rewrites and deleting it breaks. */
@Serializable
class Usages(val uses: List<RefUse>)

/** A centity's nodes in world space, or why it couldn't be posed. */
@Serializable
sealed interface PoseResult

@Serializable
@SerialName("posed")
@TsName("Posed")
class Posed(val nodes: List<PosedNode>, val animations: List<AnimationInfo>) : PoseResult

@Serializable
@SerialName("failed")
@TsName("PoseFailed")
class PoseFailed(val problems: List<Problem>) : PoseResult

@Serializable
class PosedNode(
    val name: String,
    val parent: String?,
    /** Column-major 4×4 world matrix. */
    val matrix: List<Double>
)

@Serializable
class AnimationInfo(val name: String, val length: Double, val loop: LoopMode, val autoplay: Boolean)

/** One tick of a particle effect's timeline, or why the effect can't play. */
@Serializable
sealed interface EffectStepResult

@Serializable
@SerialName("stepped")
@TsName("EffectStepped")
class EffectStepped(
    /** The timeline tick these spawns are for. */
    val tick: Int,
    /** In effect space. */
    val spawns: List<EffectSpawn>,
    /** Each ring, disc and sphere emitter's radius at [tick], by emitter name. */
    val radii: Map<String, Double>,
    /** The effect has played its last tick (never, when looping). */
    val finished: Boolean
) : EffectStepResult

@Serializable
@SerialName("failed")
@TsName("EffectStepFailed")
class EffectStepFailed(val problems: List<Problem>) : EffectStepResult

/** Every pack's skins and glyphs as the server places them, or (when a pack doesn't parse) none, with the problems. */
@Serializable
class ResourcePacksPreview(val resourcePacks: Map<String, ResourcePackPreview>, val problems: List<Problem>)

@Serializable
class ResourcePackPreview(
    val skins: Map<String, SkinPreview>,
    val glyphs: Map<String, GlyphPreview>,
    val blocks: Map<String, BlockPreview> = emptyMap()
)

/** A block look's faces as the pack's model draws them: each face's texture under the pack's `textures/`, or null when some face has none (the pack doesn't build). */
@Serializable
class BlockPreview(val faces: Map<String, String>?, val particle: String?)

/** A skin with the character and placement the server uses (see `CompiledResourcePack.Skin`). */
@Serializable
class SkinPreview(
    val char: String,
    /**
     * The front of a window title that draws this skin: space characters,
     * [char], and (with an [advance]) the spaces back to where the title
     * started, so the prefix is zero wide.
     */
    val titlePrefix: String,
    val height: Int,
    val ascent: Int,
    /** Pixels from where the title starts to where the picture starts. */
    val offset: Int,
    /** The skin character's advance; null when its picture wasn't measured. */
    val advance: Int?,
    /** Under the pack's `textures/`. */
    val texture: String
)

@Serializable
class GlyphPreview(
    val char: String,
    val height: Int,
    val ascent: Int,
    /** Its advance in text; null when its picture wasn't measured. */
    val advance: Int?,
    val texture: String
)

/** The box a fixed text display fills, or null for one that turns to face the viewer. */
@Serializable
class FitResult(val box: Box?)

@Serializable
class TextLayout(val lines: List<TextLine>)

@Serializable
class TextLine(
    /** Pixels. */
    val width: Int,
    /** The range of the source text this line shows, tags included. */
    val start: Int,
    val end: Int
)

/** MiniMessage as the characters a preview draws (format's `MiniMessagePass`). */
@Serializable
class StyledText(val chars: List<StyledChar>)

/** One drawn character, one `<glyph:…>` tag's picture, or a tag that moves the cursor (`<space>`, `<at>`). */
@Serializable
class StyledChar(
    /** One code point; empty for a glyph or a move. */
    val char: String,
    /** For a glyph tag: the reference it names (`ui/coin`). */
    val glyph: String? = null,
    /** Where it is in the source text. */
    val at: Int,
    val style: TextStyle,
    /** For a `<space:N>`: how far the cursor moves, in pixels. */
    val move: Int? = null,
    /** For an `<at>` that moves: what follows, up to the one with [overlayEnd], is drawn over the line from here. */
    val overlay: StyledOverlay? = null,
    /** Where the latest [overlay] ends. */
    val overlayEnd: Boolean? = null
)

/** An `<at:N>` as a preview draws it (`SpaceTags.At`): `from` is `cursor`, `start`, `center` or `end`, `align` `left`, `center` or `right`. */
@Serializable
class StyledOverlay(val x: Int, val from: String, val align: String)

/**
 * One roll of a loot table as the editor previews it: what each pick gave,
 * in order (a stack of an item, or one of the game's tables the server would
 * roll there), or why it couldn't roll ([error]: a table it includes isn't
 * there or doesn't read).
 */
@Serializable
class LootPreview(val drops: List<LootPreviewDrop>, val error: String? = null)

/**
 * A server-owner setting's value as the editor's settings form reads it
 * (`readSetting`): in its one written form ([value], JSON), or why it can't
 * be one ([error], `"12" isn't a whole number from 1 to 10`).
 */
@Serializable
class SettingValueResult(val value: JsonElement? = null, val error: String? = null)

/** A stack ([count] of [item]), or one of the game's loot tables ([table]). */
@Serializable
class LootPreviewDrop(val item: ItemDef? = null, val count: Int? = null, val table: String? = null)

/** A cutscene's camera at one time, its whole path, or why it can't play. */
@Serializable
sealed interface CutsceneResult

@Serializable
@SerialName("shot")
@TsName("CutsceneShot")
class CutsceneShot(
    /** Seconds the cutscene lasts. */
    val length: Double,
    val position: Vec3,
    /** Degrees, not wrapped. */
    val yaw: Double,
    val pitch: Double
) : CutsceneResult

@Serializable
@SerialName("trail")
@TsName("CutsceneTrail")
class CutsceneTrail(
    val length: Double,
    /** The camera's position at evenly spaced times from 0 to [length], first and last included. */
    val points: List<Vec3>
) : CutsceneResult

@Serializable
@SerialName("failed")
@TsName("CutsceneFailed")
class CutsceneFailed(val problems: List<Problem>) : CutsceneResult

/** What a terrain's preview draws, or why it can't. */
@Serializable
sealed interface TerrainPreviewResult

/**
 * The terrain seen from above: [cells] by [cells] columns, a cell every [step]
 * blocks from the column ([x0], [z0]), row by row (`z` rows, `x` across).
 */
@Serializable
@SerialName("map")
@TsName("TerrainMap")
class TerrainMap(
    val x0: Int,
    val z0: Int,
    val cells: Int,
    val step: Int,
    /** The y of each column's top block. */
    val heights: List<Int>,
    /** The index in [areaNames] of each column's biome area. */
    val areas: List<Int>,
    /** The names of the generator's biome areas, in the order [areas] counts them. */
    val areaNames: List<String>,
    /** The vanilla biome of each area, in the same order. */
    val biomes: List<String>,
    val seaLevel: Int,
    val minY: Int,
    val maxY: Int,
    /** The decorations' names, in the order [decorations] counts them. */
    val decorationNames: List<String>,
    /**
     * Where decorations on the surface and the sea floor start, as pairs of a cell (an index of [heights]) and a
     * decoration (an index of [decorationNames]): empty unless [decorationsShown].
     */
    val decorations: List<Int>,
    /** Whether [decorations] were worked out: only for a map of few enough chunks (a close one). */
    val decorationsShown: Boolean,
    /** How the file's script failed while this was drawn (where it did, the file's own result is drawn). */
    val scriptErrors: List<TerrainScriptError>
) : TerrainPreviewResult

/**
 * A failure of a terrain's script, as the preview shows it: the [stage] (`load`, `height`, `terrain` or
 * `decorate`), Lua's [message], and the [file] and [line] it names (the script's, or a module's it required).
 */
@Serializable
class TerrainScriptError(val stage: String, val message: String, val file: String, val line: Int?)

/** A block of the generator's palette: a block id, or a project block's name when [custom]. */
@Serializable
class TerrainPaletteEntry(val label: String, val custom: Boolean)

/**
 * The ground along a line (the x axis at z [at] when [axis] is "x", else the z axis at x [at]): [width]
 * columns from block [from] along it. A column is its blocks from [minY] up, as pairs
 * of an index of [palette] and how many in a row (index 0 is air).
 */
@Serializable
@SerialName("slice")
@TsName("TerrainSlice")
class TerrainSlice(
    val axis: String,
    val from: Int,
    val at: Int,
    val width: Int,
    val minY: Int,
    val maxY: Int,
    val palette: List<TerrainPaletteEntry>,
    val columns: List<List<Int>>,
    val seaLevel: Int,
    /** How the file's script failed while this was drawn (where it did, the file's own result is drawn). */
    val scriptErrors: List<TerrainScriptError>
) : TerrainPreviewResult

@Serializable
@SerialName("failed")
@TsName("TerrainFailed")
class TerrainFailed(val problems: List<Problem>) : TerrainPreviewResult

/**
 * A project structure as the editor read it from its `.nbt`, for the preview to place where a decoration names
 * it ([dev.netherforge.format.terrain.StructureTemplate]): its [size] (x, y, z), its [palette] of block states,
 * and [blocks], four numbers per block (x, y, z and its index in [palette]).
 */
@Serializable
class TerrainStructureInput(val size: List<Int>, val palette: List<String>, val blocks: List<Int>)

/** A workspace file as read and checked (`checkWorkspace`). */
@Serializable
class WorkspaceResult(
    /** The file, or null when it doesn't parse. */
    val workspace: WorkspaceFile?,
    /** The project folders it names, expanded and in order: those that are projects. */
    val members: List<String>,
    val problems: List<Problem>
)
