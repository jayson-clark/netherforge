package dev.netherforge.format.project

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.settings.SettingDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** `netherforge.json` at the project root. Its presence is what makes a folder a project. */
@Serializable
data class ProjectManifest(
    @SerialName("\$schema") val schema: String? = null,
    /** The project file format this was written in. See [FormatVersion]. */
    val formatVersion: Int,
    val name: String,
    /**
     * What everything the project registers or persists is named under
     * (`shop:ruby`), and where its references resolve when they name no
     * namespace: lowercase letters, digits and `_`, unique among the packages
     * a server loads, and none of [Names.RESERVED_NAMESPACES].
     */
    val namespace: String,
    /** The project's own version, as a package others depend on sees it: Semantic Versioning, `1.2.0`. */
    val version: String,
    /** The Minecraft version the project targets: `26.3`. */
    val minecraft: String,
    /**
     * Worlds on the server this project may unload and delete, besides the
     * ones its scripts create (`world:unload`). Any script can find and load
     * any world; only these, never the server's main one, can be taken away.
     */
    val managedWorlds: List<String>? = null,
    /**
     * Settings for worlds on the server, by the world's name (the same names
     * as [managedWorlds]): applied when the project loads and whenever such a
     * world is loaded or created, so a script's `world:set_spawn_limit` lasts
     * until the next reload.
     */
    val worlds: Map<String, WorldConfig>? = null,
    /**
     * What this package's scripts need beyond what every package may do: its
     * declared capabilities ([Requirement]). The runtime holds every call to
     * the package whose code makes it, and whoever runs the server sees the
     * sum across the dependency tree. Nothing, when it's absent.
     */
    val requires: ProjectRequires? = null,
    /** Permission nodes the project's scripts may grant. Nothing, when it's absent. */
    val allow: ProjectAllow? = null,
    /**
     * The packages this project uses, by their namespace: what a reference
     * like `acme_economy:coin` or `require("acme_economy:api")` names. A
     * package is another project.
     */
    val dependencies: Map<String, Dependency>? = null,
    /**
     * What projects that depend on this one may use, by kind folder:
     * `{ "modules": ["api"], "items": ["coin"], "resource_packs": ["ui"] }` (a
     * resource pack exports every entry in it). Everything else is the package's own.
     */
    val exports: Map<String, List<String>>? = null,
    /**
     * What whoever runs the project may change without touching its
     * scripts, by name: each setting's type, default and description.
     * Scripts read them with `nf.config(name)`; a server keeps its owner's
     * values in `plugins/NetherForge/settings/<namespace>.json`.
     */
    val settings: Map<String, SettingDef>? = null,
    /**
     * The commands that write the project's generated files (`node tools/items.mjs`), run in order from the project's
     * folder by `netherforge generate`, which then formats the project. Never by the server.
     */
    val generate: List<String>? = null
) {
    /** Whether [id], a resource of [kind], is one this project exports. */
    fun exports(kind: KindSpec<*, *>, id: String): Boolean = exports?.get(kind.folder)?.contains(id) == true

    companion object {
        const val FILE_NAME = "netherforge.json"
        const val SCHEMA = ".netherforge/schema/netherforge.schema.json"
    }
}

/**
 * What `netherforge.json` says about one world: how the game spawns mobs in
 * it (each map is by spawn category and holds only the categories the
 * project sets; the server's own setting (`bukkit.yml`) stays for the rest),
 * the [terrain] a world the project makes is generated with, and the
 * [dimensionType] it's made with.
 */
@Serializable
data class WorldConfig(
    /** The mob cap per category (`world:set_spawn_limit`): whole numbers, 0 or more. */
    val spawnLimits: Map<SpawnCategory, Int>? = null,
    /** Ticks between spawn attempts per category (`world:set_spawn_interval`): whole numbers, 0 or more. */
    val spawnIntervals: Map<SpawnCategory, Int>? = null,
    /**
     * Game rules, by the game's id (`keep_inventory`, `minecraft:spawn_mobs`), each `true`/`false` or a whole number as
     * the rule takes (`world:set_game_rule`). A rule this server's Minecraft doesn't have is skipped, and said in its log.
     */
    val gameRules: Map<String, JsonPrimitive>? = null,
    /**
     * A terrain of the project (`terrain/<id>.json`): the world
     * is made with it, when the project loads and the server has no world by
     * this name (and loaded with it when the server has it saved). Not for the
     * server's main world, which generates as the server says.
     */
    @Ref(RefKind.TERRAIN) val terrain: ResourceRef? = null,
    /** The seed the world is made with when it's made here. Default: a random one. */
    val seed: Long? = null,
    /**
     * A dimension type of the project (`dimension_types/<id>.json`): its build
     * limits, light and rules. A world the project makes is made with it (and
     * keeps it: a world made already keeps the one it has); for the server's
     * main world, the start-up datapack gives the game's overworld type its
     * values, so the main world has it from the next start.
     */
    @Ref(RefKind.DIMENSION_TYPE) val dimensionType: ResourceRef? = null
) {
    val isEmpty: Boolean
        get() = spawnLimits.isNullOrEmpty() &&
            spawnIntervals.isNullOrEmpty() &&
            gameRules.isNullOrEmpty() &&
            terrain == null &&
            seed == null &&
            dimensionType == null
}

/**
 * The game's spawn categories, which a world's mob cap and spawn interval are
 * set for. Spelled as the Lua API spells them (the `category` of
 * `world:set_spawn_limit`). The game's `MISC` isn't here: it has no cap.
 */
@Serializable
enum class SpawnCategory {
    @SerialName("monster")
    MONSTER,

    @SerialName("animal")
    ANIMAL,

    @SerialName("water_animal")
    WATER_ANIMAL,

    @SerialName("water_ambient")
    WATER_AMBIENT,

    @SerialName("water_underground_creature")
    WATER_UNDERGROUND_CREATURE,

    @SerialName("ambient")
    AMBIENT,

    @SerialName("axolotl")
    AXOLOTL;

    /** How scripts and the file spell it. */
    val id: String get() = name.lowercase()
}

/**
 * Where a dependency is: exactly one source. [path], a folder relative to
 * this project's own with `/` between segments (`../economy`); or [git], a
 * repository's URL, at [rev] (a branch, a tag or a full commit; the
 * repository's default branch when absent). `netherforge.lock` pins a git
 * dependency to the commit it resolved to. A registry source comes later as
 * another field, as `netherforge.lock`'s `source` already has its place.
 */
@Serializable
data class Dependency(val path: String? = null, val git: String? = null, val rev: String? = null)

/**
 * A package's declared capabilities (`"requires"` in `netherforge.json`):
 * what its scripts may do that a package must ask for, each a [Requirement].
 * A package can't use what it didn't declare, and whoever runs the server
 * sees the sum across the tree ([Requirement.combined]).
 */
@Serializable
data class ProjectRequires(
    /**
     * Whether scripts may run server commands as the console (`nf.server.run`), with the console's permissions:
     * the widest capability there is, shown first wherever the tree's requirements are.
     */
    val console: Boolean? = null,
    /**
     * Whether scripts may kick, ban and unban players, change the whitelist, and
     * change the message of the day and the most players allowed.
     */
    val moderation: Boolean? = null,
    /** Whether scripts may keep a database of the package's own (`nf.db`). */
    val db: Boolean? = null,
    /**
     * Whether scripts may answer web requests to the server (`nf.web`), under
     * the package's own `/<namespace>/`, when the server owner turned them on.
     */
    val web: Boolean? = null,
    /**
     * Whether scripts may join fake players to the server and drive them
     * (`nf.bots`), when the server owner allows bots.
     */
    val bots: Boolean? = null,
    /**
     * Whether scripts may run the package's WebAssembly modules (`nf.wasm`, `wasm/<id>/`), off the main thread
     * within the server owner's limits.
     */
    val wasm: Boolean? = null,
    /**
     * Whether scripts may talk to the other servers of the network the server is part of (`nf.network`): messages,
     * requests, shared maps, presence, and sending players to another server.
     */
    val network: Boolean? = null,
    /**
     * The hosts scripts may make HTTP requests to: a host name
     * (`discord.com`), or `*.` and one for any name under it
     * (`*.example.com`, not `example.com` itself). Kept in order.
     */
    val http: List<String>? = null,
    /** The server plugins scripts may use, by name in lowercase (`vault`). Kept in order. */
    val plugins: List<String>? = null
)

/**
 * Permission nodes a project's scripts may grant, which they can only when
 * `netherforge.json` says so.
 */
@Serializable
data class ProjectAllow(
    /**
     * The permission nodes scripts may grant or deny players
     * (`player:set_permission`): each allows itself and every node under it,
     * so `shop` allows `shop` and `shop.vip`.
     */
    val permissions: List<String>? = null
) {
    /** Whether [node] is one of [permissions] or under one. */
    fun allowsPermission(node: String): Boolean = permissions.orEmpty().any { node == it || node.startsWith("$it.") }
}

object FormatVersion {
    /** What this build reads and writes. A project in any other version is refused, never migrated. */
    const val CURRENT = 1
}
