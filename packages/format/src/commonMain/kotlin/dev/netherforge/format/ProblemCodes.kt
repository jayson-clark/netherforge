package dev.netherforge.format

import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefScope

/**
 * One kind of problem: its stable [code] (what tests and the editor's filters
 * key on), its [severity], what it means in a sentence, and the spec page
 * that explains the rule ([page], relative to `docs/`).
 *
 * Every problem anything reports is one of [ProblemCodes], so a code can't
 * be made up at a call site: the catalogue page (`docs/format/problems.md`)
 * and the editor's constants (`PROBLEM_CODES`) list exactly these.
 */
class ProblemCode internal constructor(val code: String, val severity: Severity, val summary: String, val page: String) {
    /** This code's heading on the catalogue page: `resource_pack.sound-key` → `pack-sound-key`. */
    val anchor: String get() = code.replace('.', '-')

    /** A problem of this kind in [file]. */
    fun at(
        file: String,
        message: String,
        path: String? = null,
        related: List<Location> = emptyList(),
        line: Int? = null,
        column: Int? = null
    ): Problem = Problem(severity, file, message, path, line, column, code, related)

    override fun toString(): String = code
}

/**
 * The catalogue of problems: every [ProblemCode] format, the plugin and the
 * editor report, grouped by what they're about. A new rule adds its code
 * here; `UPDATE_GOLDEN=1 pnpm test` then rewrites `docs/format/problems.md`.
 */
object ProblemCodes {
    private val registry = LinkedHashMap<String, ProblemCode>()

    /** Every code, in catalogue order. */
    val all: Collection<ProblemCode> get() = registry.values

    fun byCode(code: String): ProblemCode? = registry[code]

    /**
     * What a reference to a resource of [kind] that names nothing is:
     * `reference.<the resource kind>` (`reference.loot-table`). Every
     * [RefScope.RESOURCE] kind has one (`ReferencesTest` checks).
     */
    fun missing(kind: RefKind): ProblemCode {
        val name = when (kind.scope) {
            RefScope.DATAPACK -> kind.noun.replace(' ', '-')
            else -> requireNotNull(kind.resourceKind) { "${kind.noun} doesn't name a resource" }.replace('_', '-')
        }
        return requireNotNull(byCode("reference.$name")) { "no reference code for ${kind.noun}" }
    }

    private fun error(code: String, page: String, summary: String) = add(code, Severity.ERROR, page, summary)

    private fun warning(code: String, page: String, summary: String) = add(code, Severity.WARNING, page, summary)

    private fun add(code: String, severity: Severity, page: String, summary: String): ProblemCode {
        check(code !in registry) { "two problem codes are \"$code\"" }
        return ProblemCode(code, severity, summary, page).also { registry[code] = it }
    }

    private const val PROJECT = "format/project.md"
    private const val REFERENCES = "format/references.md"
    private const val PACKAGES = "format/packages.md"
    private const val CENTITY = "format/centity.md"
    private const val MENU = "format/menu.md"
    private const val DIALOG = "format/dialog.md"
    private const val ITEM = "format/item.md"
    private const val RECIPE = "format/recipe.md"
    private const val LOOT = "format/loot.md"
    private const val BLOCK = "format/block.md"
    private const val GAME_BLOCK = "format/game-block.md"
    private const val ADVANCEMENT = "format/advancement.md"
    private const val WORLDS = "format/worlds.md"
    private const val MIGRATION = "format/migrations.md"
    private const val PARTICLE = "format/particle-effect.md"
    private const val CUTSCENE = "format/cutscene.md"
    private const val TERRAIN = "format/terrain.md"
    private const val BIOME = "format/biome.md"
    private const val SKY = "format/sky.md"
    private const val SCREEN = "format/screen.md"
    private const val SCREEN_FONT = "format/screen-font.md"
    private const val VIDEO = "format/video.md"
    private const val BUFF = "format/buff.md"
    private const val DATAPACK = "format/datapack.md"
    private const val WASM = "format/wasm.md"
    private const val DIMENSION_TYPE = "format/dimension-type.md"
    private const val RESOURCE_PACK = "format/resource-pack.md"
    private const val GUIDE_DEV_LOOP = "guide/dev-loop.md"
    private const val SETTINGS = "format/settings.md"
    private const val WORKSPACE = "format/workspace.md"

    // ---- the project and its files ------------------------------------------

    val PARSE =
        error(
            "parse",
            PROJECT,
            "The file isn't JSON, or doesn't have the shape its kind needs (an unknown key, a missing one, a wrong type)."
        )
    val PROJECT_NO_MANIFEST = error("project.no-manifest", PROJECT, "The folder has no `netherforge.json`, so it isn't a project.")
    val PROJECT_FORMAT_VERSION =
        error("project.format-version", PROJECT, "The project is in a format version other than the one this NetherForge reads.")
    val PROJECT_NAMESPACE = error("project.namespace", PROJECT, "`namespace` isn't a usable namespace: lowercase letters, digits and `_`.")
    val PROJECT_NAMESPACE_RESERVED =
        error("project.namespace-reserved", PROJECT, "`namespace` is one the game, a server or NetherForge itself uses.")
    val PROJECT_VERSION = error("project.version", PROJECT, "`version` isn't a semantic version like `1.2.0`.")
    val PROJECT_MINECRAFT = error("project.minecraft", PROJECT, "`minecraft` isn't a Minecraft version.")
    val PROJECT_MINECRAFT_OLD =
        error("project.minecraft-old", PROJECT, "`minecraft` is older than the oldest version NetherForge supports.")
    val PROJECT_FEATURE =
        error("project.feature", PROJECT, "Something the project uses arrived in a newer Minecraft than `minecraft` names.")
    val PROJECT_NAME = warning("project.name", PROJECT, "The project has no name.")
    val PROJECT_WORLD_NAME = error("project.world-name", PROJECT, "A name in `managedWorlds` or `worlds` isn't a world name.")
    val PROJECT_WORLD_RULE =
        error(
            "project.world-rule",
            PROJECT,
            "A world's `gameRules` names no rule id, or gives one a value that isn't true, false or a whole number."
        )
    val PROJECT_WORLD_SPAWN = error("project.world-spawn", PROJECT, "A world's spawn limit or interval in `worlds` is below 0.")
    val PROJECT_WORLD_HEIGHT =
        error(
            "project.world-height",
            WORLDS,
            "A world in `worlds` names a terrain and a dimension, and a height the terrain names is outside that dimension's build limits."
        )
    val PROJECT_PERMISSION_NODE = error("project.permission-node", PROJECT, "A node in `allow.permissions` isn't a permission node.")
    val PROJECT_REQUIRES_HOST = error("project.requires-host", PROJECT, "A host in `requires.http` isn't a host name, or `*.` and one.")
    val PROJECT_REQUIRES_PLUGIN =
        error("project.requires-plugin", PROJECT, "A name in `requires.plugins` isn't a plugin's name in lowercase.")
    val PROJECT_SETTING_NAME = error("project.setting-name", SETTINGS, "A setting's name in `settings` isn't an id.")
    val PROJECT_SETTING_DESCRIPTION =
        warning("project.setting-description", SETTINGS, "A setting has no description for whoever runs the project to read.")
    val PROJECT_SETTING_RANGE = error("project.setting-range", SETTINGS, "A setting's `min` is more than its `max`.")
    val PROJECT_SETTING_CHOICES =
        error("project.setting-choices", SETTINGS, "A choice setting offers no choices, an empty one, or one twice.")
    val PROJECT_SETTING_DEFAULT = error("project.setting-default", SETTINGS, "A setting's `default` isn't one of its own values.")
    val PROJECT_ID = error("project.id", PROJECT, "A resource's folder or file name isn't an id, so it's ignored.")
    val PROJECT_GROUP =
        error("project.group", PROJECT, "A group folder's name isn't an id in parentheses, so everything in it is ignored.")
    val PROJECT_DUPLICATE_ID =
        error(
            "project.duplicate-id",
            PROJECT,
            "Two resources of one kind have the same id in different group folders; only the first is read."
        )
    val PROJECT_MISSING_FILE = warning("project.missing-file", PROJECT, "A resource folder has no main file, so it's ignored.")
    val PROJECT_STRAY_FILE =
        warning("project.stray-file", PROJECT, "A file sits where no resource of the folder's kind can be, so it's ignored.")
    val FONT_MINECRAFT = error("font.minecraft", PROJECT, "`fonts/default.json`'s `minecraft` isn't a Minecraft version.")
    val FONT_CODE_POINT = error("font.code-point", PROJECT, "A key in `fonts/default.json`'s `advances` isn't a code point.")
    val FONT_ADVANCE = error("font.advance", PROJECT, "An advance in `fonts/default.json` is negative.")
    val FONT_STALE =
        warning(
            "font.stale",
            PROJECT,
            "`fonts/default.json` holds another Minecraft version's advances; the editor rewrites it once that version's client is imported."
        )
    val FONT_NEEDED =
        warning("font.needed", PROJECT, "Something has to measure text on the server, but `fonts/default.json` is missing or stale.")

    // ---- references between resources --------------------------------------

    val REFERENCE_SYNTAX =
        error(
            "reference.syntax",
            REFERENCES,
            "A reference isn't written as `id` (in the project's own namespace) or `namespace:id`, in the shape its kind takes."
        )
    val REFERENCE_NAMESPACE =
        error("reference.namespace", REFERENCES, "A reference names a namespace that's neither the project's own nor a package's.")
    val REFERENCE_ITEM = error("reference.item", REFERENCES, "A reference names a project item that doesn't exist.")
    val REFERENCE_DIALOG = error("reference.dialog", REFERENCES, "A reference names a dialog that doesn't exist.")
    val REFERENCE_LOOT_TABLE = error("reference.loot-table", REFERENCES, "A reference names a loot table that doesn't exist.")
    val REFERENCE_BUFF = error("reference.buff", REFERENCES, "A reference names a buff that doesn't exist.")
    val REFERENCE_ADVANCEMENT = error("reference.advancement", REFERENCES, "A reference names an advancement that doesn't exist.")
    val REFERENCE_BLOCK = error("reference.block", REFERENCES, "A reference names a project block that doesn't exist.")
    val REFERENCE_CENTITY = error("reference.centity", REFERENCES, "A reference names a centity that doesn't exist.")
    val REFERENCE_TERRAIN = error("reference.terrain", REFERENCES, "A reference names a terrain that doesn't exist.")
    val REFERENCE_STRUCTURE = error("reference.structure", REFERENCES, "A reference names a project structure that doesn't exist.")
    val REFERENCE_BIOME =
        error(
            "reference.biome",
            REFERENCES,
            "A reference names a project biome, or a biome of the project's datapacks, that doesn't exist."
        )
    val REFERENCE_PLACED_FEATURE =
        error("reference.placed-feature", REFERENCES, "A reference names a placed feature the project's datapacks don't define.")
    val REFERENCE_DIMENSION_TYPE = error("reference.dimension-type", REFERENCES, "A reference names a dimension type that doesn't exist.")
    val REFERENCE_VIDEO = error("reference.video", REFERENCES, "A reference names a video that doesn't exist.")
    val REFERENCE_RESOURCE_PACK =
        error(
            "reference.resource-pack",
            REFERENCES,
            "A reference to a skin, glyph, item model, tooltip, block model or sound names a resource pack that doesn't exist."
        )
    val REFERENCE_RESOURCE_PACK_KEY =
        error(
            "reference.resource-pack-key",
            REFERENCES,
            "A reference names a skin, glyph, item model, tooltip, block model or sound its resource pack doesn't define."
        )
    val REFERENCE_NOT_EXPORTED =
        error("reference.not-exported", REFERENCES, "A reference names something of a package's that the package doesn't export.")

    // ---- packages ---------------------------------------------------------------

    val PACKAGE_NAME =
        error(
            "package.name",
            PACKAGES,
            "A key in `dependencies` isn't a usable namespace, or is the project's own or a reserved one."
        )
    val PACKAGE_PATH =
        error("package.path", PACKAGES, "A dependency's `path` isn't a relative folder path with `/` between its parts.")
    val PACKAGE_SOURCE =
        error(
            "package.source",
            PACKAGES,
            "A dependency doesn't name exactly one source (`path`, or `git` with an optional `rev`)."
        )
    val PACKAGE_GIT_URL =
        error(
            "package.git-url",
            PACKAGES,
            "A dependency's `git` isn't a repository URL NetherForge fetches from (`https://`, `ssh://`, `user@host:path` or `file://`)."
        )
    val PACKAGE_GIT_REV = error("package.git-rev", PACKAGES, "A dependency's `rev` isn't the name of a branch, a tag or a commit.")
    val PACKAGE_GIT_PATH =
        error(
            "package.git-path",
            PACKAGES,
            "A package from git depends on a folder by `path`, which isn't there for anyone who fetches it."
        )
    val PACKAGE_GIT = error("package.git", PACKAGES, "A package couldn't be fetched from its git repository.")
    val PACKAGE_HASH =
        error(
            "package.hash",
            PACKAGES,
            "A package from git isn't what `netherforge.lock` pins for its commit (its files hash to something else), so it isn't loaded."
        )
    val PACKAGE_MISSING =
        error("package.missing", PACKAGES, "There's no project (no readable `netherforge.json`) where a dependency points.")
    val PACKAGE_NAMESPACE =
        error("package.namespace", PACKAGES, "The project a dependency points at has another namespace than the dependency's name.")
    val PACKAGE_CONFLICT =
        error("package.conflict", PACKAGES, "Two packages in the dependency tree have one namespace: there can be only one of each.")
    val PACKAGE_CYCLE = error("package.cycle", PACKAGES, "Packages depend on each other in a loop.")
    val PACKAGE_EXPORT_KIND = error("package.export-kind", PACKAGES, "A key in `exports` isn't the folder of a kind of resource.")
    val PACKAGE_EXPORT_MISSING = error("package.export-missing", PACKAGES, "`exports` names a resource the project doesn't have.")
    val LOCK_MISSING =
        warning(
            "lock.missing",
            PACKAGES,
            "The project has dependencies but no `netherforge.lock`; the editor or `netherforge lock` writes it."
        )
    val LOCK_STALE =
        warning(
            "lock.stale",
            PACKAGES,
            "`netherforge.lock` doesn't say what the dependencies resolve to now; the editor or `netherforge lock` rewrites it."
        )

    // ---- scripts and modules --------------------------------------------------

    val SCRIPT_PATH = error("script.path", PROJECT, "`script.file` isn't a `.lua` file inside the resource's folder.")
    val SCRIPT_MISSING = error("script.missing", PROJECT, "The file `script.file` names doesn't exist.")
    val SCRIPT_BUDGET = warning("script.budget", PROJECT, "`script.budget` is below the least a script gets, so it's raised.")
    val SCRIPT_FILE_NAME = warning("script.file-name", PROJECT, "A Lua file beside a script is named so `require` can't reach it.")
    val MODULE_FILE_NAME = error("module.file-name", PROJECT, "A module's Lua file is named so `require` can't reach it.")
    val MODULE_EMPTY = warning("module.empty", PROJECT, "A module has no `.lua` files.")
    val MODULE_NO_INIT = warning("module.no-init", PROJECT, "A module has no `init.lua` and no script requires it, so it never runs.")

    // ---- centities --------------------------------------------------------------

    val CENTITY_NO_NODES = error("centity.no-nodes", CENTITY, "A centity has no nodes.")
    val CENTITY_NODE_NAME = error("centity.node-name", CENTITY, "A node's name isn't usable.")
    val CENTITY_UNKNOWN_PARENT = error("centity.unknown-parent", CENTITY, "A node's `parent` names no node.")
    val CENTITY_SELF_PARENT = error("centity.self-parent", CENTITY, "A node is its own parent.")
    val CENTITY_CYCLE = error("centity.cycle", CENTITY, "Nodes are each other's parents in a loop.")
    val CENTITY_BLOCK_STATE = error("centity.block-state", CENTITY, "A block display's `block` isn't a block state.")
    val CENTITY_UNKNOWN_BLOCK = error("centity.unknown-block", CENTITY, "A block display names a block the target version doesn't have.")
    val CENTITY_BLOCK_PROPERTY =
        error("centity.block-property", CENTITY, "A block display's block state has a property, or a value, its block doesn't.")
    val CENTITY_ITEM_ID = error("centity.item-id", CENTITY, "An item display's `item` isn't an item id.")
    val CENTITY_UNKNOWN_ITEM = error("centity.unknown-item", CENTITY, "An item display names an item the target version doesn't have.")
    val CENTITY_LINE_WIDTH = error("centity.line-width", CENTITY, "A text display's `lineWidth` isn't positive.")
    val CENTITY_BACKGROUND = error("centity.background", CENTITY, "A text display's `background` isn't `#AARRGGBB`.")
    val CENTITY_COLOR = error("centity.color", CENTITY, "A rect display's `color` isn't `#RRGGBB` or `#AARRGGBB`.")
    val CENTITY_DISPLAY_SIZE = error("centity.display-size", CENTITY, "A rect or image display's `size` isn't two lengths more than 0.")
    val CENTITY_IMAGE_SIZE =
        error("centity.image-size", CENTITY, "An image display's `width` and `height` are more pixels than it can draw.")
    val CENTITY_HITBOX_BOTH = error("centity.hitbox-both", CENTITY, "A hitbox has both `boxes` and `shape: \"collision\"`.")
    val CENTITY_HITBOX_COLLISION =
        error("centity.hitbox-collision", CENTITY, "`shape: \"collision\"` is on a node without a block display.")
    val CENTITY_HITBOX_SIZE = error("centity.hitbox-size", CENTITY, "A hitbox box isn't larger at `max` than at `min` on every axis.")
    val CENTITY_HITBOX_EMPTY = warning("centity.hitbox-empty", CENTITY, "A hitbox has no boxes, so nothing can click it.")
    val CENTITY_HITBOX_STALE = warning("centity.hitbox-stale", CENTITY, "A hitbox was fitted to a display that has changed since.")
    val CENTITY_ANIMATION_NAME = error("centity.animation-name", CENTITY, "An animation's name isn't usable.")
    val CENTITY_ANIMATION_NODE = error("centity.animation-node", CENTITY, "An animation drives a node that doesn't exist.")
    val CENTITY_ANIMATION_EMPTY = warning("centity.animation-empty", CENTITY, "An animation has no tracks.")
    val CENTITY_ANIMATION_LENGTH = error("centity.animation-length", CENTITY, "An animation's `length` isn't positive.")
    val CENTITY_KEYS_PAST_LENGTH =
        warning("centity.keys-past-length", CENTITY, "An animation has keyframes after its `length`, which never play.")
    val CENTITY_TRACK_EMPTY = warning("centity.track-empty", CENTITY, "An animation track has no keyframes.")
    val CENTITY_KEY_TIME = error("centity.key-time", CENTITY, "A keyframe's time is before 0.")
    val CENTITY_PHYSICS_MASS = error("centity.physics-mass", CENTITY, "A body's `mass` isn't positive.")
    val CENTITY_PHYSICS_COLLIDER =
        error("centity.physics-collider", CENTITY, "A collider isn't larger at `max` than at `min` on every axis.")
    val CENTITY_PHYSICS_RANGE = warning("centity.physics-range", CENTITY, "A physics value outside 0 to 1 is clamped.")
    val CENTITY_PHYSICS_STUCK = warning("centity.physics-stuck", CENTITY, "A body's `maxSpeed` is 0, so it never moves.")
    val CENTITY_PHYSICS_FALLS = warning("centity.physics-falls", CENTITY, "A body collides with nothing, so nothing stops it falling.")
    val BUFF_COLOR = error("buff.color", BUFF, "A buff's `color` isn't `#rrggbb`.")
    val BUFF_STAT = error("buff.stat", BUFF, "A buff's `stats` has a name that isn't one, or a value that isn't a number.")
    val BUFF_TICKS = error("buff.ticks", BUFF, "A buff's `maxTicks` is below 1.")
    val CENTITY_HEALTH = error("centity.health", CENTITY, "A centity's `health` has a value it can't have.")
    val CENTITY_ROPE =
        error(
            "centity.rope",
            CENTITY,
            "A rope node's `links` or `linkLength` is out of range, or it has no display, or has a hitbox or physics."
        )
    val CENTITY_BODY_SIZE = error("centity.body-size", CENTITY, "A `body`'s `width` or `height` isn't positive, or is too big.")
    val CENTITY_BODY_RANGE = warning("centity.body-range", CENTITY, "A `body` value out of its range is clamped.")
    val CENTITY_SPAWNING_WORLD = error("centity.spawning-world", CENTITY, "A world in `spawning.worlds` isn't a usable world name.")
    val CENTITY_SPAWNING_BIOME =
        error("centity.spawning-biome", CENTITY, "A biome or biome tag in `spawning.biomes` isn't one the target version has.")
    val CENTITY_SPAWNING_BLOCK =
        error("centity.spawning-block", CENTITY, "A block or block tag in `spawning.blocks` isn't one the target version has.")
    val CENTITY_SPAWNING_RANGE =
        error("centity.spawning-range", CENTITY, "A `spawning` range has its `min` above its `max`, or a light level outside 0 to 15.")
    val CENTITY_SPAWNING_NUMBER =
        error("centity.spawning-number", CENTITY, "`spawning`'s `weight`, `cap`, `group` or `despawnDistance` isn't positive.")
    val CENTITY_SPAWNING_TIME =
        error("centity.spawning-time", CENTITY, "A tick of the day in `spawning.time` is outside 0 to 23999.")
    val CENTITY_SPAWNING_WHEN =
        error("centity.spawning-when", CENTITY, "`spawning.when` isn't a condition's name: an id, or `ns:id` for a package's.")
    val CENTITY_SPAWNING_DESPAWN =
        warning(
            "centity.spawning-despawn",
            CENTITY,
            "`spawning.despawnDistance` is within the spawner's reach, so a natural centity may go as it appears."
        )

    // ---- menus ----------------------------------------------------------------

    val MENU_ROWS = error("menu.rows", MENU, "A chest's `rows` is outside 1 to 6.")
    val MENU_ROWS_FIXED = error("menu.rows-fixed", MENU, "`rows` is set on a menu type whose size is fixed.")
    val MENU_SLOT_KEY = error("menu.slot-key", MENU, "A key in `slots` isn't a slot index.")
    val MENU_SLOT_RANGE = error("menu.slot-range", MENU, "A slot index is past the menu's last slot.")
    val MENU_SLOT_EMPTY = warning("menu.slot-empty", MENU, "A slot entry has no item.")

    // ---- dialogs --------------------------------------------------------------

    val DIALOG_BUTTON_COUNT = error("dialog.button-count", DIALOG, "The dialog has more buttons than its type shows.")
    val DIALOG_CONFIRMATION = warning("dialog.confirmation", DIALOG, "A confirmation dialog hasn't both a yes and a no button.")
    val DIALOG_COLUMNS = error("dialog.columns", DIALOG, "`columns` is outside 1 to 8.")
    val DIALOG_COLUMNS_TYPE = error("dialog.columns-type", DIALOG, "`columns` is set on a dialog that isn't `multi_action`.")
    val DIALOG_LIST_TYPE = error("dialog.list-type", DIALOG, "`dialogs` is set on a dialog that isn't a `dialog_list`.")
    val DIALOG_LIST_EMPTY = warning("dialog.list-empty", DIALOG, "A `dialog_list` lists no dialogs.")
    val DIALOG_BODY_KEY = error("dialog.body-key", DIALOG, "A body element's `key` isn't usable.")
    val DIALOG_BODY_DUPLICATE = error("dialog.body-duplicate", DIALOG, "Two body elements have the same `key`.")
    val DIALOG_BODY_EMPTY = warning("dialog.body-empty", DIALOG, "A message has no text.")
    val DIALOG_INPUT_KEY = error("dialog.input-key", DIALOG, "An input's `key` isn't usable.")
    val DIALOG_INPUT_DUPLICATE = error("dialog.input-duplicate", DIALOG, "Two inputs have the same `key`.")
    val DIALOG_BUTTON_KEY = error("dialog.button-key", DIALOG, "A button's `key` isn't usable.")
    val DIALOG_BUTTON_DUPLICATE = error("dialog.button-duplicate", DIALOG, "Two buttons have the same `key`.")
    val DIALOG_MAX_LENGTH = error("dialog.max-length", DIALOG, "A text input's `maxLength` is below 1.")
    val DIALOG_LINES = error("dialog.lines", DIALOG, "A text input's `lines` is below 1.")
    val DIALOG_OPTIONS_EMPTY = error("dialog.options-empty", DIALOG, "A `single_option` input has no options.")
    val DIALOG_OPTION_DUPLICATE = error("dialog.option-duplicate", DIALOG, "Two options of one input have the same `id`.")
    val DIALOG_OPTION_INITIAL = warning("dialog.option-initial", DIALOG, "More than one option starts selected; the first wins.")
    val DIALOG_RANGE = error("dialog.range", DIALOG, "A `number_range` input's `end` isn't above its `start`.")
    val DIALOG_RANGE_STEP = error("dialog.range-step", DIALOG, "A `number_range` input's `step` isn't positive.")
    val DIALOG_RANGE_INITIAL = error("dialog.range-initial", DIALOG, "A `number_range` input's `initial` is outside its range.")

    // ---- items ----------------------------------------------------------------

    val ITEM_KIND = error("item.kind", ITEM, "An item has no `kind` (and names no project item), or its `kind` isn't an item id.")
    val ITEM_UNKNOWN = error("item.unknown", ITEM, "An item's `kind` is an item the target version doesn't have.")
    val ITEM_KIND_MISMATCH = error("item.kind-mismatch", ITEM, "A stack of a project item gives a `kind` other than the item's.")
    val ITEM_COUNT = error("item.count", ITEM, "`count` is outside 1 to 99, or above `maxStackSize`.")
    val ITEM_DAMAGE = error("item.damage", ITEM, "`damage` is negative.")
    val ITEM_MAP = error("item.map", ITEM, "`map` is negative.")
    val ITEM_ENCHANTMENT = error("item.enchantment", ITEM, "A key in `enchantments` isn't an enchantment id.")
    val ITEM_UNKNOWN_ENCHANTMENT = error("item.unknown-enchantment", ITEM, "An enchantment the target version doesn't have.")
    val ITEM_ENCHANTMENT_LEVEL = error("item.enchantment-level", ITEM, "An enchantment level is outside 1 to 255.")
    val ITEM_COLOR = error("item.color", ITEM, "`color` isn't `#RRGGBB`.")
    val ITEM_MAX_STACK_SIZE = error("item.max-stack-size", ITEM, "`maxStackSize` is outside 1 to 99.")
    val ITEM_MAX_STACK_SIZE_DURABLE = error("item.max-stack-size-durable", ITEM, "An item with durability has a `maxStackSize` above 1.")
    val ITEM_ATTRIBUTE = error("item.attribute", ITEM, "A modifier's `attribute` isn't an attribute id.")
    val ITEM_UNKNOWN_ATTRIBUTE = error("item.unknown-attribute", ITEM, "A modifier's attribute is one the target version doesn't have.")
    val ITEM_ATTRIBUTE_ID = error("item.attribute-id", ITEM, "A modifier's `id` isn't a namespaced id, or two modifiers share one.")
    val ITEM_ATTRIBUTE_AMOUNT = error("item.attribute-amount", ITEM, "A modifier's `amount` isn't a number.")
    val ITEM_BLOCK = error("item.block", ITEM, "An entry of `canBreak` or `canPlaceOn` isn't a block, a `#tag` of blocks or `*`.")
    val ITEM_UNKNOWN_BLOCK =
        error("item.unknown-block", ITEM, "An entry of `canBreak` or `canPlaceOn` is a block or block tag the target version doesn't have.")
    val ITEM_TOOLTIP_COMPONENT =
        error("item.tooltip-component", ITEM, "An entry of `hiddenTooltipComponents` isn't a tooltip line an item can hide.")
    val ITEM_UNKNOWN_TOOLTIP_COMPONENT =
        error(
            "item.unknown-tooltip-component",
            ITEM,
            "An entry of `hiddenTooltipComponents` is a component the target version doesn't have."
        )
    val ITEM_FOOD = error("item.food", ITEM, "A `food` value is out of range.")
    val ITEM_COOLDOWN = error("item.cooldown", ITEM, "A cooldown's `seconds` isn't more than 0.")
    val ITEM_COOLDOWN_GROUP = error("item.cooldown-group", ITEM, "A cooldown's `group` isn't a namespaced id.")
    val ITEM_HOLD_FOOD = error("item.hold-food", ITEM, "An item has both `hold` and `food`.")
    val ITEM_HOLD_ANIMATION = error("item.hold-animation", ITEM, "A hold's `animation` isn't one of the game's use animations.")
    val ITEM_USE_EFFECTS = error("item.use-effects", ITEM, "A `useEffects` value is out of range.")
    val ITEM_DRAW_WITHOUT_AMMO = error("item.draw-without-ammo", ITEM, "`drawWithoutAmmo` is on an item that isn't a bow.")

    // ---- recipes --------------------------------------------------------------

    val RECIPE_MISSING = error("recipe.missing", RECIPE, "A recipe lacks a field its type needs.")
    val RECIPE_FIELD = error("recipe.field", RECIPE, "A recipe has a field its type doesn't take.")
    val RECIPE_PATTERN =
        error(
            "recipe.pattern",
            RECIPE,
            "A shaped recipe's `pattern` isn't 1 to 3 equal rows of 1 to 3 characters, each in `key` or a space."
        )
    val RECIPE_KEY =
        error("recipe.key", RECIPE, "A shaped recipe's `key` has a key that isn't one character, or one the pattern doesn't use.")
    val RECIPE_STATION = error("recipe.station", RECIPE, "A station recipe's `stations` or `stationRange` isn't usable.")
    val RECIPE_INGREDIENTS = error("recipe.ingredients", RECIPE, "A shapeless recipe has no ingredients, or more than 9.")
    val RECIPE_INGREDIENT = error("recipe.ingredient", RECIPE, "An ingredient isn't an item id (other than air) or an item tag.")
    val RECIPE_UNKNOWN_ITEM = error("recipe.unknown-item", RECIPE, "An ingredient names an item the target version doesn't have.")
    val RECIPE_UNKNOWN_TAG = error("recipe.unknown-tag", RECIPE, "An ingredient names an item tag the target version doesn't have.")
    val RECIPE_EXPERIENCE = error("recipe.experience", RECIPE, "`experience` is negative.")
    val RECIPE_COOKING_TIME = error("recipe.cooking-time", RECIPE, "`cookingTime` is below 1 tick.")
    val RECIPE_CATEGORY = error("recipe.category", RECIPE, "`category` isn't one of the recipe book tabs its type has.")

    // ---- loot tables ----------------------------------------------------------

    val LOOT_POOL_NAME = error("loot.pool-name", LOOT, "A pool's name isn't usable.")
    val LOOT_RANGE =
        error("loot.range", LOOT, "A `rolls` or `count` range is negative, has its `max` below its `min`, or goes past the most allowed.")
    val LOOT_NO_ENTRIES = warning("loot.no-entries", LOOT, "A pool has no entries, so it never gives anything.")
    val LOOT_WEIGHT = error("loot.weight", LOOT, "An entry's `weight` is below 1.")
    val LOOT_ITEM_COUNT = error("loot.item-count", LOOT, "An item entry's `item` has a `count`: the entry's `count` says how many.")
    val LOOT_VANILLA = error("loot.vanilla", LOOT, "A game loot table's id isn't a namespaced id.")
    val LOOT_UNKNOWN_VANILLA = error("loot.unknown-vanilla", LOOT, "A game loot table the target version doesn't have.")
    val LOOT_CHANCE = error("loot.chance", LOOT, "A `chance` condition's chance is outside 0 to 1.")
    val LOOT_TOOL = error("loot.tool", LOOT, "A `tool` condition's tool isn't an item id (other than air) or an item tag.")
    val LOOT_UNKNOWN_TOOL =
        error("loot.unknown-tool", LOOT, "A `tool` condition names an item or item tag the target version doesn't have.")
    val LOOT_ENCHANTMENT =
        error("loot.enchantment", LOOT, "An `enchantment` condition's enchantment isn't an id, or its `level` is below 1.")
    val LOOT_UNKNOWN_ENCHANTMENT =
        error("loot.unknown-enchantment", LOOT, "An `enchantment` condition names an enchantment the target version doesn't have.")
    val LOOT_CYCLE = error("loot.cycle", LOOT, "A loot table includes itself, through its entries or theirs.")

    // ---- blocks -----------------------------------------------------------------

    val BLOCK_HARDNESS = error("block.hardness", BLOCK, "A block's `hardness` is below -1 or isn't a number.")
    val BLOCK_TICK = error("block.tick", BLOCK, "A block's `tick` isn't a whole number of ticks from 1 to an hour.")
    val BLOCK_FALLS =
        error("block.falls", BLOCK, "A block `falls`, but it's held as tripwire or drawn by a centity, which can't fall.")
    val BLOCK_SOUND =
        error("block.sound", BLOCK, "A block's `sound` isn't a kind of block the game has place and break sounds for.")
    val BLOCK_REQUIRES_TOOL =
        warning("block.requires-tool", BLOCK, "A block `requiresTool` but names no `tool`, so no tool is the right one and nothing drops.")
    val BLOCK_CARRIERS =
        error("block.carriers", BLOCK, "The project has more blocks of one carrier than the game has states of it to hold them in.")
    val BLOCK_CARRIER =
        warning(
            "block.carrier",
            BLOCK,
            "Something about a block its carrier doesn't do: a hardness tripwire ignores, or a model a note block hides its neighbours' faces around."
        )
    val BLOCK_VANILLA_LOOKS =
        warning(
            "block.vanilla-looks",
            BLOCK,
            "`client/vanilla.json` lacks the game's look of a block custom blocks are held in, or is another version's."
        )

    // ---- the game's blocks ------------------------------------------------------

    val GAME_BLOCK_BLOCKS =
        error("game_block.blocks", GAME_BLOCK, "A game block file's `blocks` is empty, or an entry isn't a block id or a `#tag`.")
    val GAME_BLOCK_UNKNOWN =
        error("game_block.unknown", GAME_BLOCK, "A game block file names a block or block tag the target version doesn't have.")
    val GAME_BLOCK_TWICE =
        error(
            "game_block.twice",
            GAME_BLOCK,
            "One of the game's blocks is named twice: by two files (the project's or its packages'), or twice in one (a tag names " +
                "every block in it)."
        )
    val GAME_BLOCK_HARDNESS = error("game_block.hardness", GAME_BLOCK, "A game block file's `hardness` is below -1 or isn't a number.")
    val GAME_BLOCK_CARRIER =
        error("game_block.carrier", GAME_BLOCK, "A game block file names a block the project's own blocks, or a package's, are held in.")
    val GAME_BLOCK_REQUIRES_TOOL =
        warning(
            "game_block.requires-tool",
            GAME_BLOCK,
            "`requiresTool: false` without `drops`: the game's own drops keep the game's rule, so the wrong tool still gets nothing."
        )

    // ---- migrations -------------------------------------------------------------

    val MIGRATION_NAME =
        error(
            "migration.name",
            MIGRATION,
            "A migration file isn't named `NNN_name.sql` (three digits, then lowercase letters, digits and _)."
        )
    val MIGRATION_DUPLICATE = error("migration.duplicate", MIGRATION, "Two migration files have the same number.")
    val MIGRATION_GAP = error("migration.gap", MIGRATION, "Migration numbers don't run 001, 002, 003 with nothing skipped.")
    val MIGRATION_FAILED =
        error("migration.failed", MIGRATION, "A migration couldn't be applied to the package's database, so scripts can't use it.")

    // ---- advancements -----------------------------------------------------------

    val ADVANCEMENT_ICON =
        error("advancement.icon", ADVANCEMENT, "An icon names neither a game item (`kind`) nor a project item (`item`), or both.")
    val ADVANCEMENT_UNKNOWN_ICON =
        error("advancement.unknown-icon", ADVANCEMENT, "An icon's `kind` is an item the target version doesn't have.")
    val ADVANCEMENT_BACKGROUND =
        warning("advancement.background", ADVANCEMENT, "A tree's root has no `background`, or an advancement that isn't a root has one.")
    val ADVANCEMENT_CRITERIA = error("advancement.criteria", ADVANCEMENT, "An advancement has no criteria, so nothing could complete it.")
    val ADVANCEMENT_CRITERION_NAME = error("advancement.criterion-name", ADVANCEMENT, "A criterion's name isn't usable.")
    val ADVANCEMENT_TRIGGER =
        error("advancement.trigger", ADVANCEMENT, "A criterion's `trigger` isn't a namespaced id, or it has `conditions` without one.")
    val ADVANCEMENT_UNKNOWN_TRIGGER =
        error("advancement.unknown-trigger", ADVANCEMENT, "A criterion's `trigger` is one the target version doesn't have.")
    val ADVANCEMENT_REQUIREMENTS =
        error(
            "advancement.requirements",
            ADVANCEMENT,
            "`requirements` has an empty group, names a criterion the advancement doesn't have, or leaves one of its criteria out."
        )
    val ADVANCEMENT_EXPERIENCE = error("advancement.experience", ADVANCEMENT, "`experience` is negative.")
    val ADVANCEMENT_CYCLE = error("advancement.cycle", ADVANCEMENT, "An advancement is its own parent, through its parents or theirs.")

    // ---- structures -------------------------------------------------------------

    val STRUCTURE_BIOMES =
        error("structure.biomes", WORLDS, "A structure's `biomes` is empty, holds something that isn't a biome id, or a tag beside others.")
    val STRUCTURE_UNKNOWN_BIOME =
        error("structure.unknown-biome", WORLDS, "A structure's `biomes` names a biome or tag the target version doesn't have.")
    val STRUCTURE_SPREAD =
        error("structure.spread", WORLDS, "`spacing`, `separation` or `salt` is out of range, or `separation` isn't below `spacing`.")
    val STRUCTURE_RANGE = error("structure.range", WORLDS, "`depth`, `maxDistance`, or a pool element's `weight` is out of range.")
    val STRUCTURE_POOL = error("structure.pool", WORLDS, "A pool's name isn't usable (or is `start`), or the pool has no elements.")
    val STRUCTURE_POOL_ELEMENT = error("structure.pool-element", WORLDS, "A pool element names a structure the project doesn't have.")

    // ---- builds -----------------------------------------------------------------

    val BUILD_PART_NAME =
        warning("build.part-name", WORLDS, "A file in a build's folder isn't one of its parts (`<x>_<y>_<z>.nbt`), so it's ignored.")
    val BUILD_PART_MISSING = error("build.part-missing", WORLDS, "A build's grid of parts has a gap: a part it needs isn't there.")
    val BUILD_SIZE = error("build.size", WORLDS, "A build has more parts along an axis than a build may.")
    val BUILD_ID_TAKEN = error("build.id-taken", WORLDS, "A build has the id of one of the project's structures.")

    // ---- WebAssembly modules ----------------------------------------------------

    val WASM_INVALID = error("wasm.invalid", WASM, "A module's `module.wasm` isn't a WebAssembly module the server can run.")
    val WASM_IMPORT =
        error(
            "wasm.import",
            WASM,
            "A module imports something the server doesn't provide: a module may import WASI's preview 1 functions and nothing else."
        )
    val WASM_THREADS =
        error("wasm.threads", WASM, "A module was built for threads (shared memory, `wasi.thread-spawn`), which the server doesn't run.")
    val WASM_MEMORY = error("wasm.memory", WASM, "A module's memory is addressed by 64-bit numbers, which the server doesn't run.")
    val WASM_EXPORTS = warning("wasm.exports", WASM, "A module exports no function, so nothing can call it.")

    // ---- particle effects -------------------------------------------------------

    val PARTICLE_DURATION = error("particle.duration", PARTICLE, "`duration` is outside the ticks an effect may last.")
    val PARTICLE_EMITTERS_EMPTY = warning("particle.emitters-empty", PARTICLE, "An effect has no emitters, so it shows nothing.")
    val PARTICLE_BUDGET = error("particle.budget", PARTICLE, "An effect can spawn more points in one tick than the limit.")
    val PARTICLE_EMITTER_NAME = error("particle.emitter-name", PARTICLE, "An emitter's name isn't usable.")
    val PARTICLE_ID = error("particle.id", PARTICLE, "An emitter's `particle` isn't a particle id.")
    val PARTICLE_UNKNOWN = error("particle.unknown", PARTICLE, "An emitter's particle is one the target version doesn't have.")
    val PARTICLE_UNSUPPORTED = error("particle.unsupported", PARTICLE, "An emitter's particle takes options effects can't send.")
    val PARTICLE_OPTION = error("particle.option", PARTICLE, "An emitter sets an option its particle doesn't take, or lacks one it needs.")
    val PARTICLE_COLOR = error("particle.color", PARTICLE, "A colour isn't `#RRGGBB`.")
    val PARTICLE_SIZE = error("particle.size", PARTICLE, "`size` is outside the sizes the game draws.")
    val PARTICLE_BLOCK_STATE = error("particle.block-state", PARTICLE, "An emitter's `blockState` isn't a block state.")
    val PARTICLE_UNKNOWN_BLOCK =
        error("particle.unknown-block", PARTICLE, "An emitter's `blockState` names a block the target version doesn't have.")
    val PARTICLE_BLOCK_PROPERTY =
        error("particle.block-property", PARTICLE, "An emitter's block state has a property, or a value, its block doesn't.")
    val PARTICLE_EMISSION = error("particle.emission", PARTICLE, "An emitter has both or neither of `burst` and `rate`.")
    val PARTICLE_BURST = error("particle.burst", PARTICLE, "`burst` is outside the points one burst may spawn.")
    val PARTICLE_RATE = error("particle.rate", PARTICLE, "`rate` isn't more than 0 and at most the limit.")
    val PARTICLE_EVERY = error("particle.every", PARTICLE, "`every` is set without `burst`, or isn't at least 1.")
    val PARTICLE_WINDOW = error("particle.window", PARTICLE, "An emitter's `start` or `end` is outside the effect.")
    val PARTICLE_COUNT = error("particle.count", PARTICLE, "`count` is set without `motion: \"random\"`, or is out of range.")
    val PARTICLE_SPREAD = error("particle.spread", PARTICLE, "`spread` is set without `motion: \"random\"`, or is negative.")
    val PARTICLE_SPEED = error("particle.speed", PARTICLE, "`speed` is negative.")
    val PARTICLE_DIRECTION = error("particle.direction", PARTICLE, "`motion: \"direction\"` has no `direction`.")
    val PARTICLE_SHAPE = error("particle.shape", PARTICLE, "A shape's size or extent isn't usable.")
    val PARTICLE_DISTRIBUTION = error("particle.distribution", PARTICLE, "Even spacing on a shape that can't space points evenly.")
    val PARTICLE_SPIN = error("particle.spin", PARTICLE, "`spin` on a shape that can't spin.")
    val PARTICLE_CURVE_CHANNEL = error("particle.curve-channel", PARTICLE, "A curve drives a value the emitter doesn't have.")
    val PARTICLE_CURVE_CONFLICT = error("particle.curve-conflict", PARTICLE, "A value is set and driven by a curve too.")
    val PARTICLE_CURVE_TIME = error("particle.curve-time", PARTICLE, "A curve key's time is outside the effect.")
    val PARTICLE_CURVE_DUPLICATE = error("particle.curve-duplicate", PARTICLE, "Two keys of one curve are at the same tick.")

    // ---- world generation -------------------------------------------------------

    val TERRAIN_NAME = error("terrain.name", TERRAIN, "A noise, cave, ore, decoration or biome area's name isn't an id.")
    val TERRAIN_LIMIT =
        error("terrain.limit", TERRAIN, "A file holds more layers, noises, caves, ores, decorations or biome areas than the limit.")
    val TERRAIN_BLOCK =
        error("terrain.block", TERRAIN, "A block isn't a block state, or the target version has no such block or property.")
    val TERRAIN_ONE_BLOCK =
        error(
            "terrain.one-block",
            TERRAIN,
            "A layer, the stone, the floor or a decoration names no block, or more than one of `block`, `customBlock` and `structure`."
        )
    val TERRAIN_NOISE = error("terrain.noise", TERRAIN, "A noise's frequency, octaves, lacunarity or gain is out of range.")
    val TERRAIN_HEIGHT =
        error("terrain.height", TERRAIN, "The terrain's base, sea level, an amplitude or a biome area's terrain is out of range.")
    val TERRAIN_BORDER =
        error("terrain.border", TERRAIN, "The blend radius or the jitter of the borders between biome areas is out of range.")
    val TERRAIN_LAYER = error("terrain.layer", TERRAIN, "A layer's or the floor's thickness is out of range.")
    val TERRAIN_CAVE = error("terrain.cave", TERRAIN, "A cave's threshold, heights or depth is out of range.")
    val TERRAIN_ORE = error("terrain.ore", TERRAIN, "An ore names no block or two, or its size, veins or heights are out of range.")
    val TERRAIN_DECORATION =
        error(
            "terrain.decoration",
            TERRAIN,
            "A decoration's count, chance, threshold or heights are out of range, or a structure is given a placement it can't have."
        )
    val TERRAIN_AREA =
        error("terrain.area", TERRAIN, "A `biomes` list of an ore, cave or decoration names a biome area the file doesn't have.")
    val TERRAIN_CUSTOM_BLOCK =
        error("terrain.custom-block", TERRAIN, "A custom block a terrain places is drawn by a centity, which a terrain can't place.")
    val TERRAIN_BIOME =
        error("terrain.biome", TERRAIN, "A biome area's game biome isn't a biome id, or the target version has no such biome.")
    val TERRAIN_CLIMATE =
        error(
            "terrain.climate",
            TERRAIN,
            "A biome area's temperature, humidity or other climate range is outside -1 to 1, its minimum is above its " +
                "maximum, or it names a climate value the file's `climate.noises` doesn't declare."
        )
    val TERRAIN_VOLUME =
        error(
            "terrain.volume",
            TERRAIN,
            "A biome area limited by height (`y`, `depth` or `surface`) has layers or terrain of its own, its range is empty, " +
                "the islands float over one, or every area of the file is one."
        )
    val TERRAIN_DENSITY =
        error(
            "terrain.density",
            TERRAIN,
            "A 3D noise's squash or a scale is out of range, the islands' height, thickness or threshold is, or a biome area has a " +
                "density in a file without one."
        )
    val TERRAIN_SCRIPT =
        error("terrain.script", TERRAIN, "A file's `script` asks for a budget or more blocks or loot tables than a script may have.")
    val TERRAIN_SCRIPT_MISSING =
        error("terrain.script-missing", TERRAIN, "A file has a `script`, but there's no `terrain/<id>.lua` beside it.")
    val TERRAIN_SCRIPT_UNUSED =
        warning("terrain.script-unused", TERRAIN, "A `terrain/<id>.lua` is beside a file with no `script`, so it never runs.")
    val TERRAIN_SCRIPT_FAILED =
        warning(
            "terrain.script-failed",
            TERRAIN,
            "A terrain's script didn't load, raised an error or ran past its budget: what it failed at is the file's own " +
                "result (a column's height, a chunk's stage)."
        )

    // ---- biomes -----------------------------------------------------------------

    val BIOME_CLIMATE = error("biome.climate", BIOME, "A biome's downfall is outside 0 to 1, or its temperature outside -2 to 2.")
    val BIOME_COLOR = error("biome.color", BIOME, "A colour isn't written `#rrggbb`.")
    val BIOME_PARTICLE =
        error(
            "biome.particle",
            BIOME,
            "The ambient particle isn't a particle the target version has, takes options, or its probability isn't more than 0 and at most 1."
        )
    val BIOME_SOUND =
        error(
            "biome.sound",
            BIOME,
            "A sound isn't a sound event the target version has, or a delay, chance or distance of the sounds or music is out of range."
        )
    val BIOME_SPAWN =
        error(
            "biome.spawn",
            BIOME,
            "A spawn names no entity type the target version has, or its weight or group size is out of range."
        )
    val BIOME_SPAWN_COST =
        error(
            "biome.spawn-cost",
            BIOME,
            "A spawn cost names no entity type the target version has, or its charge or budget isn't more than 0."
        )
    val BIOME_FEATURE =
        error("biome.feature", BIOME, "A feature isn't a placed feature the target version has, or it's listed twice in one step.")
    val SKY_COLOR = error("sky.color", SKY, "A sky's colour isn't written `#rrggbb` (or `#aarrggbb`, for the clouds).")
    val SKY_EMPTY = error("sky.empty", SKY, "A sky gives no colour at all.")
    val SKY_FADE = error("sky.fade", SKY, "A sky's fade is outside 1 to 8 steps.")
    val SKY_NIGHT = warning("sky.night", SKY, "A sky that doesn't darken at night gives no sky or fog colour to hold.")

    // ---- screens, their fonts and videos ------------------------------------

    val SCREEN_SIZE = error("screen.size", SCREEN, "A screen's (or a video's) width or height isn't 1 to 16 maps.")
    val SCREEN_COLOR = error("screen.color", SCREEN, "A screen's colour isn't written `#rrggbb`.")
    val SCREEN_WORLD = error("screen.world", SCREEN, "Where a screen hangs names something that isn't a world name.")
    val SCREEN_RANGE = error("screen.range", SCREEN, "A screen's `range` or `clickRange` is outside what it may be.")
    val SCREEN_VOLUME = error("screen.volume", SCREEN, "A screen's video's `volume` isn't 0 to 1.")
    val SCREEN_PLACE = warning("screen.place", SCREEN, "Two screens hang in the same block, so one covers part of the other.")
    val SCREEN_FONT_ID = error("screen_font.id", SCREEN_FONT, "A screen font is called `default`, which is the game's own map font.")
    val SCREEN_FONT_FILE =
        error("screen_font.file", SCREEN_FONT, "A screen font's `file` isn't a picture or font file of the right kind in its folder.")
    val SCREEN_FONT_MISSING = error("screen_font.missing", SCREEN_FONT, "A screen font's picture or font file isn't in its folder.")
    val SCREEN_FONT_CHARS =
        error("screen_font.chars", SCREEN_FONT, "A bitmap screen font lists no characters, or rows of different lengths.")
    val SCREEN_FONT_FIELD = error("screen_font.field", SCREEN_FONT, "A screen font sets a field of the other type of font.")
    val SCREEN_FONT_SIZE = error("screen_font.size", SCREEN_FONT, "A screen font's size, spacing or line height is outside what it may be.")
    val SCREEN_FONT_GLYPH =
        error(
            "screen_font.glyph",
            SCREEN_FONT,
            "A pixels screen font's glyph isn't one character drawn in rows of `#` and `.`, all as tall."
        )
    val SCREEN_FONT_GRID =
        error("screen_font.grid", SCREEN_FONT, "A bitmap screen font's picture doesn't divide into its grid of characters.")
    val VIDEO_FPS = error("video.fps", VIDEO, "A video's `fps` isn't 1 to 20.")
    val VIDEO_RANGE = error("video.range", VIDEO, "A video's `start` or `end` isn't a time in it.")
    val VIDEO_SOURCE = error("video.source", VIDEO, "A video's `source` isn't a file in its folder.")
    val VIDEO_NOT_CONVERTED = warning("video.not-converted", VIDEO, "A video hasn't been converted for screens yet, so it can't play.")
    val VIDEO_AUDIO_MISSING = warning("video.audio-missing", VIDEO, "A video keeps its sound, but its converted sound isn't there.")
    val VIDEO_STALE =
        warning("video.stale", VIDEO, "A video's converted frames were made for other settings than its file has now: convert it again.")
    val BIOME_FEATURE_ORDER =
        error(
            "biome.feature-order",
            BIOME,
            "Two of the project's biomes list the same two features of a step in opposite orders, which the game refuses in one world."
        )

    // ---- datapacks passed through ------------------------------------------------

    val DATAPACK_FORMAT =
        error(
            "datapack.format",
            DATAPACK,
            "A `min_format` or `max_format` in `pack.mcmeta` isn't a data pack format (a number, or `[major, minor]`), " +
                "or the first is later than the second."
        )
    val DATAPACK_VERSION =
        error(
            "datapack.version",
            DATAPACK,
            "The datapack isn't written for the data pack format of the Minecraft version it runs on, so it's left out."
        )
    val DATAPACK_OVERLAY =
        error("datapack.overlay", DATAPACK, "An overlay's `directory` isn't a usable folder name, is `data`, or is listed twice.")
    val DATAPACK_FILE =
        error(
            "datapack.file",
            DATAPACK,
            "A file of the datapack isn't a worldgen entry or worldgen tag of the game's (`data/<namespace>/worldgen/<registry>/…` " +
                "or `data/<namespace>/tags/worldgen/<registry>/…`, as `.json`), or its id isn't usable."
        )
    val DATAPACK_REGISTRY =
        warning(
            "datapack.registry",
            DATAPACK,
            "A file is in a folder of `worldgen/` that's no registry of the target version's: the game ignores it."
        )
    val DATAPACK_NAMESPACE =
        error(
            "datapack.namespace",
            DATAPACK,
            "A file is in a namespace other than the project's own or `minecraft` (a package's datapacks only write its own)."
        )
    val DATAPACK_OVERRIDE =
        error(
            "datapack.override",
            DATAPACK,
            "A `minecraft` file replaces nothing of the game's: the game's namespace is only for replacing its own; new entries go in the project's."
        )
    val DATAPACK_CONFLICT =
        error(
            "datapack.conflict",
            DATAPACK,
            "Two of the project's datapacks, or a datapack and a resource of the project's, write the same entry."
        )
    val DATAPACK_TAG =
        error("datapack.tag", DATAPACK, "A tag file isn't `{ \"values\": [...] }` with ids, `#tags` or `{ \"id\": … }` in it.")
    val DATAPACK_REFERENCE =
        error(
            "datapack.reference",
            DATAPACK,
            "A worldgen file names an entry (a feature, a biome, a noise…) that neither the game nor the project has, or a namespace it can't name."
        )

    // ---- dimension types ----------------------------------------------------------

    val DIMENSION_TYPE_HEIGHT =
        error(
            "dimension-type.height",
            DIMENSION_TYPE,
            "`minY` or `height` isn't a multiple of 16, the height is under 16, or the world would reach below -2032 or above 2032."
        )
    val DIMENSION_TYPE_LOGICAL_HEIGHT =
        error("dimension-type.logical-height", DIMENSION_TYPE, "`logicalHeight` is below 0 or above the height.")
    val DIMENSION_TYPE_LIGHT =
        error(
            "dimension-type.light",
            DIMENSION_TYPE,
            "`ambientLight` is outside 0 to 1, a monster spawn light level outside 0 to 15, or its minimum above its maximum."
        )
    val DIMENSION_TYPE_COLOR =
        error("dimension-type.color", DIMENSION_TYPE, "A colour isn't written `#rrggbb` (or `#aarrggbb`, for the clouds).")
    val DIMENSION_TYPE_NUMBER =
        error(
            "dimension-type.number",
            DIMENSION_TYPE,
            "`coordinateScale` is outside 0.00001 to 30000000, or `cloudHeight` outside -2032 to 2032."
        )
    val DIMENSION_TYPE_INFINIBURN =
        error("dimension-type.infiniburn", DIMENSION_TYPE, "`infiniburn` isn't a block tag written `#namespace:path`.")
    val DIMENSION_TYPE_INFINIBURN_TAG =
        warning(
            "dimension-type.infiniburn-tag",
            DIMENSION_TYPE,
            "`infiniburn` names a block tag the target version doesn't have: no fire burns forever."
        )

    // ---- cutscenes -------------------------------------------------------------

    val CUTSCENE_LENGTH = error("cutscene.length", CUTSCENE, "A cutscene's length isn't more than 0 and at most the longest allowed.")
    val CUTSCENE_TRACK_EMPTY = error("cutscene.track-empty", CUTSCENE, "The camera's position or rotation track has no keys.")
    val CUTSCENE_KEY_TIME = error("cutscene.key-time", CUTSCENE, "A key's or cue's time is outside the cutscene.")
    val CUTSCENE_KEY_DUPLICATE = error("cutscene.key-duplicate", CUTSCENE, "Two keys of one track are at the same time.")
    val CUTSCENE_KEY_LIMIT = error("cutscene.key-limit", CUTSCENE, "A track or the cues hold more entries than the limit.")
    val CUTSCENE_POSITION = error("cutscene.position", CUTSCENE, "A camera position is beyond the world's limit.")
    val CUTSCENE_PITCH = error("cutscene.pitch", CUTSCENE, "A camera's pitch is outside -90 to 90 degrees.")
    val CUTSCENE_CUE_EMPTY = error("cutscene.cue-empty", CUTSCENE, "A cue has neither an `event` nor `text`.")
    val CUTSCENE_CUE_EVENT = error("cutscene.cue-event", CUTSCENE, "A cue's `event` isn't a usable name.")
    val CUTSCENE_CUE_DURATION = error("cutscene.cue-duration", CUTSCENE, "A cue's `duration` isn't more than 0, or is set without `text`.")

    // ---- resource packs -------------------------------------------------------

    val RESOURCE_PACK_KEY = error("resource_pack.key", RESOURCE_PACK, "A skin, glyph, item model, tooltip or equipment key isn't an id.")
    val RESOURCE_PACK_ON_DEMAND = error(
        "resource_pack.on-demand",
        RESOURCE_PACK,
        "An `onDemand` pack draws something every player's game needs: blocks, the HUD, or the game's own textures, models or sounds."
    )
    val RESOURCE_PACK_TEXTURE_PATH =
        error("resource_pack.texture-path", RESOURCE_PACK, "A texture isn't a `.png` path inside the resource pack's `textures/` folder.")
    val RESOURCE_PACK_TEXTURE_MISSING = error("resource_pack.texture-missing", RESOURCE_PACK, "A texture names a file that doesn't exist.")
    val RESOURCE_PACK_HEIGHT = error("resource_pack.height", RESOURCE_PACK, "A skin's or glyph's `height` isn't positive.")
    val RESOURCE_PACK_ASCENT =
        error("resource_pack.ascent", RESOURCE_PACK, "A skin's or glyph's `ascent` exceeds its `height`; Minecraft refuses to load it.")
    val RESOURCE_PACK_OFFSET = error("resource_pack.offset", RESOURCE_PACK, "A skin's `offset` is further than one move can go.")
    val RESOURCE_PACK_IMAGE =
        warning(
            "resource_pack.image",
            RESOURCE_PACK,
            "A skin's picture can't be read, so the title's words start after the art instead of over it."
        )
    val RESOURCE_PACK_PICTURE_SIZE =
        error(
            "resource_pack.picture-size",
            RESOURCE_PACK,
            "A skin's, glyph's or HUD element's picture is over 256 pixels tall, or over 256 wide and doesn't cut into equal pieces of 256 or less: Minecraft draws it blank."
        )
    val RESOURCE_PACK_TOO_MANY =
        error("resource_pack.too-many", RESOURCE_PACK, "A resource pack holds more skins than a font has characters for.")
    val RESOURCE_PACK_TOO_MANY_GLYPHS =
        error(
            "resource_pack.too-many-glyphs",
            RESOURCE_PACK,
            "The project's resource packs hold more glyphs between them than a font has characters for."
        )
    val RESOURCE_PACK_TOOLTIP_EMPTY =
        warning("resource_pack.tooltip-empty", RESOURCE_PACK, "A tooltip has neither a background nor a frame.")
    val RESOURCE_PACK_EQUIPMENT_EMPTY =
        warning("resource_pack.equipment-empty", RESOURCE_PACK, "An equipment look has no layer, so nothing is drawn when it's worn.")
    val RESOURCE_PACK_ITEM_LOOK =
        error(
            "resource_pack.item-look",
            RESOURCE_PACK,
            "An item look needs one of `texture`, `block` and `model`, and `parent` only goes with a texture."
        )
    val RESOURCE_PACK_HUD =
        error(
            "resource_pack.hud",
            RESOURCE_PACK,
            "A `hud` element's key is taken by another, or its place, segments or icons aren't ones it can be drawn with."
        )
    val RESOURCE_PACK_HUD_HIDE =
        warning(
            "resource_pack.hud-hide",
            RESOURCE_PACK,
            "`hud.hide` needs the game's HUD sprites, which `client/vanilla.json` lacks or has from another version."
        )
    val RESOURCE_PACK_VANILLA_MISSING =
        warning(
            "resource_pack.vanilla-missing",
            RESOURCE_PACK,
            "`hud.replace`, `textures` or `models` needs the game's sprite, texture and model names and sizes, which `client/vanilla.json` lacks (for a texture or model the editor hasn't looked up yet) or has from another version."
        )
    val RESOURCE_PACK_REPLACE_NAME =
        error(
            "resource_pack.replace-name",
            RESOURCE_PACK,
            "`hud.replace`, `textures` or `models` names a sprite, texture or model the game doesn't have, or one another resource pack replaces too."
        )
    val RESOURCE_PACK_REPLACE_SIZE =
        error(
            "resource_pack.replace-size",
            RESOURCE_PACK,
            "A `textures` replacement of a screen's sheet (`gui/…`, not `gui/sprites/`) or a colour map isn't the pixel size of the game's texture it replaces."
        )
    val RESOURCE_PACK_REPLACE_SHAPE =
        warning(
            "resource_pack.replace-shape",
            RESOURCE_PACK,
            "A `textures` replacement isn't the shape of the game's texture (any size of the same shape is fine), or isn't one square frame of a texture the game animates and has no `.mcmeta` of its own."
        )
    val RESOURCE_PACK_MODEL_FILE =
        error(
            "resource_pack.model-file",
            RESOURCE_PACK,
            "A `model` names a file that isn't in the resource pack's `models/` folder, or isn't a JSON model."
        )
    val RESOURCE_PACK_MODEL =
        error("resource_pack.model", RESOURCE_PACK, "A model file's element, rotation, face or display isn't one the game reads.")
    val RESOURCE_PACK_MODEL_BOUNDS =
        error("resource_pack.model-bounds", RESOURCE_PACK, "A model's element reaches outside -16 to 32, which the game refuses.")
    val RESOURCE_PACK_MODEL_TEXTURE =
        error(
            "resource_pack.model-texture",
            RESOURCE_PACK,
            "A model's face names a texture variable the model doesn't give, or a texture in a namespace nothing serves."
        )
    val RESOURCE_PACK_MODEL_DISPLAY =
        warning("resource_pack.model-display", RESOURCE_PACK, "A model's display translation or scale is past what the game clamps it to.")
    val RESOURCE_PACK_BLOCK_FACES =
        error(
            "resource_pack.block-faces",
            RESOURCE_PACK,
            "A block look has no texture for some face (give it a texture, or one for each face), or has a model and textures both."
        )
    val RESOURCE_PACK_SOUND_KEY =
        error(
            "resource_pack.sound-key",
            RESOURCE_PACK,
            "A key in `sounds` isn't a sound key, or one of the game's sound events (`minecraft:…`)."
        )
    val RESOURCE_PACK_SOUND_EVENT =
        error(
            "resource_pack.sound-event",
            RESOURCE_PACK,
            "A `sounds` key replaces a sound event the target version doesn't have, or one another resource pack replaces too."
        )
    val RESOURCE_PACK_SOUND_REPLACE =
        error(
            "resource_pack.sound-replace",
            RESOURCE_PACK,
            "A replacement of one of the game's sound events lists no `files` to play instead."
        )
    val RESOURCE_PACK_SOUND_NAME =
        error("resource_pack.sound-name", RESOURCE_PACK, "An `.ogg` file under `sounds/` is named so it can't be a sound event.")
    val RESOURCE_PACK_SOUND_FILE =
        warning("resource_pack.sound-file", RESOURCE_PACK, "A file under `sounds/` isn't Ogg Vorbis, so it isn't a sound.")
    val RESOURCE_PACK_SOUND_FILES = error("resource_pack.sound-files", RESOURCE_PACK, "A sound's `files` is empty.")
    val RESOURCE_PACK_SOUND_PATH =
        error(
            "resource_pack.sound-path",
            RESOURCE_PACK,
            "An entry of a sound's `files` isn't an `.ogg` path inside the resource pack's `sounds/` folder."
        )
    val RESOURCE_PACK_SOUND_MISSING = error("resource_pack.sound-missing", RESOURCE_PACK, "A sound plays a file that doesn't exist.")
    val RESOURCE_PACK_SOUND_VOLUME = error("resource_pack.sound-volume", RESOURCE_PACK, "A sound's `volume` isn't positive.")
    val RESOURCE_PACK_SOUND_PITCH = error("resource_pack.sound-pitch", RESOURCE_PACK, "A sound's `pitch` isn't positive.")

    // ---- workspaces -----------------------------------------------------------

    val WORKSPACE_NAME = error("workspace.name", WORKSPACE, "The workspace has no `name`.")
    val WORKSPACE_PROJECT_PATH =
        error("workspace.project-path", WORKSPACE, "An entry in `projects` isn't a relative folder, or one followed by `/*`.")
    val WORKSPACE_MISSING_PROJECT =
        error("workspace.missing-project", WORKSPACE, "A folder in `projects` has no `netherforge.json`, so it isn't a project.")
    val WORKSPACE_EMPTY_PATTERN = warning("workspace.empty-pattern", WORKSPACE, "A `folder/*` in `projects` has no project directly in it.")
    val WORKSPACE_SERVER_NAME =
        error("workspace.server-name", WORKSPACE, "A server's name in `network.servers` isn't lowercase letters, digits, `_` and `-`.")
    val WORKSPACE_SERVER_PROJECT =
        error(
            "workspace.server-project",
            WORKSPACE,
            "A server in `network.servers` runs a folder that isn't one of the workspace's projects."
        )
    val WORKSPACE_ARRIVAL = error("workspace.arrival", WORKSPACE, "`network.arrival` isn't one of the network's servers.")
    val WORKSPACE_UNKNOWN_SERVER =
        warning(
            "workspace.unknown-server",
            WORKSPACE,
            "A script sends players to a server the workspace's dev network doesn't have."
        )

    // ---- the running server ---------------------------------------------------

    val RUNTIME_MINECRAFT_UNSUPPORTED =
        error(
            "runtime.minecraft-unsupported",
            GUIDE_DEV_LOOP,
            "The project targets a Minecraft version this server's NetherForge isn't built for."
        )
    val RUNTIME_UNBUNDLED =
        error(
            "runtime.unbundled",
            PACKAGES,
            "A server without the editor runs a project that has dependencies: it runs bundles `netherforge build` writes, and resolves nothing itself."
        )
    val RUNTIME_BUNDLE_HASH =
        error(
            "runtime.bundle-hash",
            PACKAGES,
            "A package in a bundle isn't what the bundle says it is: a file was changed, added or removed."
        )
    val SETTINGS_FILE =
        warning("settings.file", SETTINGS, "A server's settings file isn't a JSON object, so the package's settings have their defaults.")
    val SETTINGS_VALUE =
        warning("settings.value", SETTINGS, "A server's settings file holds a value a setting can't have, so the setting has its default.")
    val SETTINGS_UNKNOWN =
        warning("settings.unknown", SETTINGS, "A server's settings file holds a value for a setting the package doesn't declare.")
    val RUNTIME_WEB_DISABLED =
        warning(
            "runtime.web-disabled",
            PROJECT,
            "A package declares `requires.web`, but the server owner hasn't turned web routes on (`web.enabled`), so its routes aren't served."
        )
    val RUNTIME_NETWORK_DISABLED =
        warning(
            "runtime.network-disabled",
            PROJECT,
            "A package declares `requires.network`, but the server isn't part of a network (`network.enabled`), so there are no other servers to talk to."
        )
    val RUNTIME_BOTS_DISABLED =
        warning(
            "runtime.bots-disabled",
            PROJECT,
            "A package declares `requires.bots`, but the server owner hasn't allowed bots (`bots.enabled`), so its scripts can't join any."
        )
    val RUNTIME_PLUGIN_MISSING =
        error(
            "runtime.plugin-missing",
            PROJECT,
            "A package declares a plugin in `requires.plugins` that isn't enabled on the server: what needs it fails until it is."
        )
    val RUNTIME_PACK_FORMAT =
        warning(
            "runtime.pack-format",
            GUIDE_DEV_LOOP,
            "The server doesn't say which resource pack format it uses, so no resource pack was built."
        )
    val RUNTIME_BLOCKS =
        error(
            "runtime.blocks",
            BLOCK,
            "The server can't hold the project's blocks: it has no states of their carrier for them, or won't stop working them out itself."
        )
    val RUNTIME_TERRAIN =
        warning(
            "runtime.terrain",
            TERRAIN,
            "A terrain can't be used as written: an ore's custom block has no state to be held in, the server asks for its " +
                "main world's terrain and `netherforge.json` names none, or `netherforge.json` gives the main world a seed (its seed is the server's)."
        )
    val RUNTIME_DIMENSION_TYPE =
        warning(
            "runtime.dimension-type",
            DIMENSION_TYPE,
            "A world can't have the dimension `netherforge.json` names for it: it was made with another (a world keeps the one it was " +
                "made with), or the server hasn't the dimension type (it learns them only at start)."
        )
    val RUNTIME_RESTART =
        warning(
            "runtime.restart",
            GUIDE_DEV_LOOP,
            "Something the server learns only at start (advancements, biomes, dimension types, the main world's terrain) " +
                "changed since it started: it runs the old one until it restarts."
        )
    val RUNTIME_DATAPACK =
        error(
            "runtime.datapack",
            DATAPACK,
            "The server refused the project's datapacks as it started (its own message says why), so it runs without them until " +
                "they change."
        )
    val RUNTIME_WASM =
        error(
            "runtime.wasm",
            WASM,
            "The server couldn't compile a WebAssembly module (its own message says why: it's invalid, too large for the owner's " +
                "limits, or uses something the server doesn't run), so scripts can't load it until it changes."
        )
    val SCRIPT_ERROR = error("script.error", GUIDE_DEV_LOOP, "A script raised an error.")
    val SCRIPT_SLOW = warning("script.slow", GUIDE_DEV_LOOP, "A script takes more of a tick than the server's warning threshold.")
    val PARTICLES_BUDGET =
        warning(
            "particles.budget",
            GUIDE_DEV_LOOP,
            "More particle points were due in one tick than the server sends, so some were skipped."
        )

    /**
     * The codes only the plugin reports, while a project runs on a server: loading a project never does, so no
     * golden can hold one. The runtime's `ProblemCoverageTest` names the test that produces each.
     */
    val reportedAtRuntime: Set<ProblemCode> = setOf(
        RUNTIME_WASM, TERRAIN_SCRIPT_FAILED, MIGRATION_FAILED, VIDEO_STALE, RUNTIME_MINECRAFT_UNSUPPORTED,
        RUNTIME_UNBUNDLED, RUNTIME_BUNDLE_HASH, RUNTIME_PLUGIN_MISSING, RUNTIME_WEB_DISABLED, RUNTIME_NETWORK_DISABLED,
        RUNTIME_BOTS_DISABLED, RUNTIME_BLOCKS, RUNTIME_TERRAIN, RUNTIME_DIMENSION_TYPE, RUNTIME_RESTART, RUNTIME_DATAPACK,
        SETTINGS_FILE, SETTINGS_VALUE, SETTINGS_UNKNOWN, SCRIPT_ERROR, SCRIPT_SLOW, PARTICLES_BUDGET, RUNTIME_PACK_FORMAT
    )
}
