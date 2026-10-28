package dev.netherforge.format.project

/**
 * The naming rules every part of NetherForge agrees on.
 *
 * Resource ids are folder names, Lua strings (`nf.spawn("tower")`,
 * `require("combat")`) and Minecraft resource paths (`shop:ruby`), so one rule
 * has to satisfy all of them: lowercase (folders must not collide on
 * case-insensitive filesystems), no hyphens or dots (they'd need quoting in
 * Lua and mean something in `require`).
 */
object Names {
    const val ID_RULE = "lowercase letters, digits and _, starting with a letter or digit, at most 64 characters"
    const val NODE_NAME_RULE = "letters, digits, _ and -, at most 64 characters"
    const val WORLD_NAME_RULE = "letters, digits, _, - and ., starting with a letter, digit or _, at most 64 characters"

    /** [ID] without its anchors, for patterns built of ids (`PackSounds.KEY`). */
    const val ID_BODY = "[a-z0-9][a-z0-9_]{0,63}"

    /** [SEGMENT] without its anchors, for patterns built of path segments (`ResourcePackFile.TEXTURE_PATH`). */
    const val SEGMENT_BODY = "[A-Za-z0-9_][A-Za-z0-9_.-]*"

    // Public so the contract generator can hand the same patterns to the editor.
    val ID = Regex("^$ID_BODY$")
    val NODE_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * A world's name on the server, as `netherforge.json`'s `managedWorlds` and
     * `nf.worlds.load` take it: a folder name the server already has, so not
     * held to [ID] (`world_nether`, `Lobby`), but never one that could leave
     * the server's folder.
     */
    val WORLD_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$")

    /** A permission node, as `allow.permissions` and `player:set_permission` take it: `shop.vip`. */
    val PERMISSION_NODE = Regex("^(?=.{1,255}$)[a-z0-9_-]+(\\.[a-z0-9_-]+)*$")
    const val PERMISSION_NODE_RULE =
        "lowercase letters, digits, _ and -, in parts separated by dots (like shop.vip), at most 255 characters"

    /** One label of a host name: lowercase letters, digits and `-`, not at either end, at most 63 characters. */
    private const val HOST_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"

    /** A host name in lowercase (`discord.com`), at most 253 characters, as an HTTP request names one. */
    val HOST_NAME = Regex("^(?=.{1,253}$)$HOST_LABEL(?:\\.$HOST_LABEL)*$")

    /**
     * A host `requires.http` lists: a host name, or `*.` and a host name of at
     * least two labels (`*.example.com`) for any name under it.
     */
    val HTTP_HOST = Regex("^(?:(?=.{1,253}$)$HOST_LABEL(?:\\.$HOST_LABEL)*|\\*\\.(?=.{1,253}$)$HOST_LABEL(?:\\.$HOST_LABEL)+)$")
    const val HTTP_HOST_RULE =
        "a host name in lowercase, like discord.com, or *. and one with a dot in it for any name under it, like *.example.com"

    /** A server plugin's name as `requires.plugins` and `plugin:<name>` take it: `vault`. */
    val PLUGIN_NAME = Regex("^[a-z0-9_-]{1,64}$")
    const val PLUGIN_NAME_RULE = "the plugin's name in lowercase: letters, digits, _ and -, at most 64 characters"

    /**
     * A project's namespace (`netherforge.json`'s `namespace`): what everything
     * it registers or persists is named under (`shop:ruby`), and its resource
     * pack's asset namespace. Held to [ID]'s rule, which Minecraft's resource
     * locations, Lua strings and folder names all accept.
     */
    val NAMESPACE = ID
    const val NAMESPACE_RULE = ID_RULE

    /**
     * Namespaces no project may take: the game's own (`minecraft`, `realms`,
     * `brigadier`), the common tag convention's (`c`), the server's (`paper`,
     * `bukkit`, `spigot`) and NetherForge's own (`netherforge`, `nf`).
     */
    val RESERVED_NAMESPACES = setOf("minecraft", "realms", "brigadier", "c", "paper", "bukkit", "spigot", "netherforge", "nf")

    /**
     * A version as `netherforge.json`'s `version` takes it: Semantic
     * Versioning 2.0.0, with its own regular expression (semver.org).
     */
    val SEMVER = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    )

    val SEGMENT = Regex("^$SEGMENT_BODY$")

    /** A Lua file `require` can reach, relative to its module's or resource's folder: `init.lua`, `lib/util.lua`. */
    val LUA_FILE = Regex("^([A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.lua$")

    /**
     * A group folder's name: an id in parentheses, `(weapons)`. Groups sort a kind's resources into folders
     * (`items/(weapons)/ruby_wand/`) without changing their ids, and the parentheses are what tell one from a
     * resource's own folder from the path alone.
     */
    val GROUP = Regex("^\\($ID_BODY\\)$")
    const val GROUP_RULE = "an id in parentheses, like (weapons)"

    fun isId(text: String): Boolean = ID.matches(text)

    /** Whether a path segment is shaped as a group folder, `(` … `)`, usable or not ([isGroup]). */
    fun looksLikeGroup(segment: String): Boolean = segment.length >= 2 && segment.startsWith("(") && segment.endsWith(")")

    fun isGroup(segment: String): Boolean = GROUP.matches(segment)

    fun isNamespace(text: String): Boolean = NAMESPACE.matches(text)

    fun isVersion(text: String): Boolean = SEMVER.matches(text)

    fun isNodeName(text: String): Boolean = NODE_NAME.matches(text)

    fun isWorldName(text: String): Boolean = WORLD_NAME.matches(text)

    fun isPermissionNode(text: String): Boolean = PERMISSION_NODE.matches(text)

    fun isHostName(text: String): Boolean = HOST_NAME.matches(text)

    fun isHttpHost(text: String): Boolean = HTTP_HOST.matches(text)

    fun isPluginName(text: String): Boolean = PLUGIN_NAME.matches(text)

    fun isLuaFile(path: String): Boolean = LUA_FILE.matches(path)

    /**
     * A path that stays inside the folder it's relative to: `/`-separated, no
     * empty, `.` or `..` segments, no leading slash, no backslashes.
     */
    fun isRelativeFile(path: String): Boolean = path.isNotEmpty() && path.split('/').all { it != "." && it != ".." && SEGMENT.matches(it) }
}
