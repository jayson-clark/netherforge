package dev.netherforge.format.bridge

import dev.netherforge.format.Problem
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The dev bridge: how a running editor and the plugin on its dev server talk.
 *
 * Transport: the editor listens on `127.0.0.1:<port>` and starts the server
 * with `-Dnetherforge.bridge.port=<port>` and the environment variable
 * `NETHERFORGE_BRIDGE_TOKEN`. The plugin connects when it enables (and
 * reconnects if the connection drops). Frames are newline-delimited JSON-RPC
 * 2.0 ([JsonRpc]), one message or batch per line.
 *
 * The plugin's first frame is the [Bridge.hello] request, carrying the token
 * and [Bridge.PROTOCOL]. The editor closes a connection whose first frame
 * isn't a hello with its token, and answers one of another protocol with a
 * [Bridge.PROTOCOL_MISMATCH] error saying so; either way nothing else
 * travels on it. After the hello the editor sends requests ([Bridge.requests],
 * plus each [BridgeExtension]'s) and the plugin sends notifications
 * ([Bridge.events]) and streams ([Bridge.streams]); the debugger's DAP
 * messages ([Bridge.dap]) go both ways as notifications. A request for a method
 * the plugin doesn't have is answered with [JsonRpc.METHOD_NOT_FOUND]. The
 * bridge exists only on a dev server: a production server never opens it.
 */

/** Which thread the plugin answers a request on. */
enum class BridgeThread {
    /** The server's main thread, where everything the runtime touches lives: the default. */
    MAIN,

    /**
     * The bridge's own thread, at once. For control requests that must be
     * answered while the main thread is busy or stopped (the debugger,
     * profiler subscriptions): they may touch only what's safe from any thread.
     */
    BRIDGE
}

/**
 * A request: its method, the shape of its params and of its result. A
 * request without params takes [Unit] (sent without `params`); one without
 * an answer returns [Unit] (answered with `"result": null`).
 */
class BridgeMethod<P, R>(
    val name: String,
    val params: KSerializer<P>,
    val result: KSerializer<R>,
    val thread: BridgeThread = BridgeThread.MAIN
) {
    val takesParams: Boolean get() = params.descriptor.serialName != UNIT
    val answers: Boolean get() = result.descriptor.serialName != UNIT

    fun encodeParams(value: P): JsonElement? = if (takesParams) Bridge.json.encodeToJsonElement(params, value) else null

    /** Absent params read as `{}`, so a method whose params are all optional may be sent without any. */
    fun decodeParams(json: JsonElement?): P = Bridge.json.decodeFromJsonElement(params, json ?: JsonObject(emptyMap()))

    fun encodeResult(value: R): JsonElement = if (answers) Bridge.json.encodeToJsonElement(result, value) else JsonNull

    fun decodeResult(json: JsonElement): R = Bridge.json.decodeFromJsonElement(result, if (answers) json else JsonObject(emptyMap()))

    fun request(id: Int, params: P): RpcRequest = RpcRequest(JsonPrimitive(id), name, encodeParams(params))

    override fun toString() = name
}

/** A notification: sent as it happens, never answered. */
class BridgeEvent<P>(val name: String, val params: KSerializer<P>) {
    fun notification(value: P) = RpcNotification(name, Bridge.json.encodeToJsonElement(params, value))

    fun decode(json: JsonElement?): P = Bridge.json.decodeFromJsonElement(params, json ?: JsonObject(emptyMap()))

    override fun toString() = name
}

/**
 * A stream: high-rate data, sent as notifications that each carry a batch,
 * `{"items": [...]}`, in order. The sender coalesces whatever is waiting into
 * one notification, so a burst costs one frame rather than one per item.
 */
class BridgeStream<T>(val name: String, val item: KSerializer<T>) {
    private val batch = ListSerializer(item)

    fun notification(items: List<T>) = RpcNotification(name, JsonObject(mapOf(ITEMS to Bridge.json.encodeToJsonElement(batch, items))))

    fun decode(json: JsonElement?): List<T> = Bridge.json.decodeFromJsonElement(
        batch,
        (json as? JsonObject)?.get(ITEMS) ?: JsonArray(emptyList())
    )

    override fun toString() = name

    companion object {
        const val ITEMS = "items"
    }
}

/**
 * Requests outside the core protocol, under their own namespace
 * (`bots/join`): what only some servers have. A server without the
 * extension answers its methods with [JsonRpc.METHOD_NOT_FOUND].
 */
class BridgeExtension(val namespace: String, val methods: List<BridgeMethod<*, *>>) {
    init {
        for (method in methods) require(method.name.startsWith("$namespace/")) { "$method isn't in $namespace/" }
    }
}

private const val UNIT = "kotlin.Unit"

// ---- plugin → editor -------------------------------------------------------

@Serializable
data class HelloParams(
    val token: String,
    /** [Bridge.PROTOCOL] of the plugin's build. */
    val protocol: Int,
    val pluginVersion: String,
    val minecraft: String,
    /** Absolute path of the project directory the plugin loaded. */
    val project: String
)

/** The editor accepted the hello; [protocol] is its own (the same). */
@Serializable
data class HelloResult(val protocol: Int)

/** The problems the plugin found loading the project; replaces any earlier set. */
@Serializable
data class Problems(val problems: List<Problem>)

/** Live state worth showing in the editor's status bar. Sent on change, at most once a second. */
@Serializable
data class Status(val players: List<String>, val instances: Int)

/** What the console stream carries, in the order it happened. */
@Serializable
sealed interface ConsoleEntry

/** A line of runtime output. [source] is set when it came from a script. */
@Serializable
@SerialName("log")
data class Log(val level: LogLevel, val message: String, val source: SourceRef? = null) : ConsoleEntry

/** A Lua error, located in the project so the editor can open it. */
@Serializable
@SerialName("script_error")
data class ScriptError(val message: String, val source: SourceRef? = null, val traceback: String? = null) : ConsoleEntry

// ---- the profiler -----------------------------------------------------------

/**
 * One batch of the profiler stream: a run of server ticks, every one of them
 * measured exactly (`System.nanoTime` around each step and each call into a
 * script, not sampled). Sent about once a second while the editor subscribes.
 */
@Serializable
data class ProfileSample(
    val ticks: List<ProfileTick>,
    /** Every script scope that ran over these ticks, by its own time. */
    val scopes: List<ScopeTime> = emptyList(),
    /** Every function the runtime called into over these ticks (a handler, a timer, a task's resume…), by its own time. */
    val handlers: List<HandlerTime> = emptyList()
)

/** One server tick, as NetherForge spent it. Times are nanoseconds. */
@Serializable
data class ProfileTick(
    val tick: Long,
    /** NetherForge's whole tick. */
    val nanos: Long,
    /** Each step of the tick, by name, in the order they run (`timers`, `async`, `events`, `world`…): what [nanos] is made of. */
    val phases: Map<String, Long>,
    /** What scripts' own code took, wherever in the tick it ran. */
    val scripts: Long = 0,
    /** The scopes that took the most this tick, the most first (at most three). */
    val top: List<ScopeTime> = emptyList(),
    /**
     * What the steps are made of, by path: each service's share of a step
     * (`world/centities`) and the parts a service times itself
     * (`world/centities/sync`). A part's time is inside its parent's.
     */
    val parts: Map<String, Long> = emptyMap()
)

/**
 * A script scope's own time: a module, or one centity instance's, menu
 * window's, dialog's or item's script (`centity tower 1a2b3c4d`).
 */
@Serializable
data class ScopeTime(
    val scope: String,
    val nanos: Long,
    val calls: Int,
    /** Its most expensive tick. */
    val max: Long = 0
)

/**
 * The own time of one function the runtime calls: [kind] is the event a
 * handler is for (`tick`, `player_join`), or `load` (a script's body),
 * `timer`, `task`, `command`, `complete`, `goal`, `callback`. [source] is
 * where the function starts (null when it isn't project code).
 */
@Serializable
data class HandlerTime(
    /** The script it belongs to, as logs name it (`centity tower`, `module shop`). */
    val script: String,
    val kind: String,
    val source: SourceRef? = null,
    val calls: Int,
    val nanos: Long,
    /** Its slowest single call. */
    val max: Long,
    /** How many scopes ran it (each instance of a centity has its own). */
    val scopes: Int = 1
)

/** Starts ([on]) or stops the profiler stream. */
@Serializable
data class ProfilerSubscribeParams(val on: Boolean)

// ---- editor → plugin -------------------------------------------------------

/**
 * Reload these project-relative paths. The plugin maps each path to the
 * resource that owns it and reloads that resource once.
 */
@Serializable
data class ReloadParams(val paths: List<String>)

/** Spawn a centity in front of [player] (or the first online player). */
@Serializable
data class SpawnParams(val centity: String, val player: String? = null)

/** Run a server console command. */
@Serializable
data class CommandParams(val line: String)

/**
 * Play particle effect [effect] three blocks in front of [player]'s eyes,
 * facing them (or the first online player's, or at the default world's
 * spawn), owned by the runtime rather than a script. [loop] overrides the
 * file's.
 */
@Serializable
data class PlayParticleEffectParams(val effect: String, val player: String? = null, val loop: Boolean? = null)

/** Where an online player stands, to the block: [player] (a name or UUID), or the first online when null. */
@Serializable
data class PlayerPositionParams(val player: String? = null)

/**
 * Save the box from [from] to [to] (both corners included, in any order) in
 * [world] as a structure file, with the entities in it when [entities], the
 * way `world:save_structure` does, but into the answer rather than a file.
 * The editor writes it into the project; the plugin never writes project files.
 */
@Serializable
data class SaveStructureParams(val world: String, val from: BlockPos, val to: BlockPos, val entities: Boolean = false)

/** Save [world] and flush it to disk, for the editor to copy as a map. */
@Serializable
data class SaveWorldParams(val world: String)

// ---- shared shapes ---------------------------------------------------------

@Serializable
enum class LogLevel {
    @SerialName("debug")
    DEBUG,

    @SerialName("info")
    INFO,

    @SerialName("warn")
    WARN,

    @SerialName("error")
    ERROR
}

/** A place in the project: `centities/tower/root.lua`, line 12. */
@Serializable
data class SourceRef(val file: String, val line: Int? = null)

@Serializable
data class ReloadResult(
    val resources: List<ReloadedResource>,
    /**
     * The server must restart for the change to take effect: something it
     * learns only as it starts (the start-up datapack: advancements) is no
     * longer what it started with. The editor restarts its dev server; on
     * another server, `/nf reload` says so.
     */
    val restart: Boolean = false
)

/**
 * One resource a reload touched, named as format's `classify` names a path:
 * [pkg] (the package, by its namespace), [kind] and [id]. A whole package
 * reloaded (its `netherforge.json` changed) has neither kind nor id.
 */
@Serializable
data class ReloadedResource(
    @SerialName("package") val pkg: String,
    /** The kind's id in format's registry (`centity`, `module`, `pack`…); null for the whole package. */
    val kind: String? = null,
    /** The resource's id in its package; null for the whole package. */
    val id: String? = null,
    val ok: Boolean,
    /**
     * What kept running on the new version: live instances moved onto the new
     * definition for a centity; open windows kept (contents and viewers) for an
     * menu; players the rebuilt resource pack was sent to for a pack.
     */
    val reattached: Int = 0,
    val problems: List<Problem> = emptyList(),
    /** For a pack: the resource pack the plugin built from every pack in the project. Absent when nothing was built. */
    val pack: PackBuild? = null
) {
    /** How a person reads it: `centity:tower`, or the package's namespace for the whole package. */
    val label: String get() = if (kind == null || id == null) pkg else "$kind:$id"
}

/** One build of the project's resource pack. */
@Serializable
data class PackBuild(
    /** Lowercase hex SHA-1 of the zip: changes only when the content does. */
    val sha1: String,
    /** Where clients fetch it, or null when the plugin isn't serving it (an external URL with no upload yet, or packs disabled). */
    val url: String? = null,
    /** The zip's size in bytes. */
    val bytes: Int
)

@Serializable
data class InstanceInfo(val uuid: String, val centity: String, val world: String, val x: Double, val y: Double, val z: Double)

/** A block position. */
@Serializable
data class BlockPos(val x: Int, val y: Int, val z: Int)

@Serializable
data class PlayerPosition(val player: String, val world: String, val x: Int, val y: Int, val z: Int)

@Serializable
data class LoadedWorld(
    val name: String,
    /** `normal`, `nether` or `end`. */
    val environment: String,
    /** The server's main world, which a project never unloads. */
    val main: Boolean
)

@Serializable
data class SavedStructure(
    /** The structure file (gzipped NBT, Minecraft's structure format), base64. */
    val nbt: String,
    /** Its size in blocks. */
    val size: BlockPos
)

/**
 * A saved world's files, as `/`-separated paths relative to the server's
 * folder (the editor copies them from its dev server's folder and nowhere
 * else). Since Minecraft 26.1 every world is a dimension of the main world's
 * storage: [level] is that storage's `level.dat`, [dimension] the world's own
 * folder (`world/dimensions/minecraft/<name>`: its region files and saved
 * data).
 */
@Serializable
data class SavedWorld(
    val level: String,
    val dimension: String,
    val main: Boolean,
    /** The world's spawn, which [level] holds only for the main world. */
    val spawn: WorldSpawn
)

@Serializable
data class WorldSpawn(val x: Int, val y: Int, val z: Int, val yaw: Double = 0.0, val pitch: Double = 0.0)

object Bridge {
    /**
     * The protocol's version. Bump it for any change one side can't read
     * from the other: the editor refuses a plugin of another version (and
     * the plugin says why) rather than half-understanding it.
     */
    const val PROTOCOL = 1

    /**
     * One JSON value per line; no pretty printing, so a frame never contains
     * a newline. Unknown keys are ignored, unlike in project files.
     */
    val json = kotlinx.serialization.json.Json(CanonicalJson.json) {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    const val PORT_PROPERTY = "netherforge.bridge.port"
    const val TOKEN_ENV = "NETHERFORGE_BRIDGE_TOKEN"
    const val PROJECT_PROPERTY = "netherforge.project"

    /** The editor's package cache (`<data>/packages`), where a dev server finds git packages' checkouts ([dev.netherforge.format.project.Packages.gitCheckout]). */
    const val PACKAGE_CACHE_PROPERTY = "netherforge.packages"

    /**
     * A dev server the editor runs in a dev network: the hub's `host:port` (the editor's proxy), this server's name
     * there, and the hub's secret (in the environment, never on the command line). They put the server in that
     * network whatever its `config.yml` says.
     */
    const val NETWORK_HUB_PROPERTY = "netherforge.network.hub"
    const val NETWORK_SERVER_PROPERTY = "netherforge.network.server"
    const val NETWORK_SECRET_ENV = "NETHERFORGE_NETWORK_SECRET"

    // Error codes beyond the spec's, in its range for implementation-defined server errors.

    /** The request was understood and couldn't be done; the message says why (`no player "Steve" online`). */
    const val REQUEST_FAILED = -32000

    /** The editor refused a hello of another [PROTOCOL]; the message says which versions met. */
    const val PROTOCOL_MISMATCH = -32001

    // ---- plugin → editor ----

    /** The plugin's first frame on every connection. */
    val hello = BridgeMethod("hello", HelloParams.serializer(), HelloResult.serializer(), BridgeThread.BRIDGE)

    /** On load, every reload, every connect: the full set. */
    val problems = BridgeEvent("problems", Problems.serializer())

    /** Online players and instance count, on change, at most once a second. */
    val status = BridgeEvent("status", Status.serializer())

    /** Runtime log lines and script errors. */
    val console = BridgeStream("console", ConsoleEntry.serializer())

    /** The profiler's measurements, batched about once a second, while the editor subscribes ([profilerSubscribe]). */
    val profiler = BridgeStream("profiler", ProfileSample.serializer())

    /** Every package's server-owner settings, whenever one changed (from the editor, `/nf settings` or its dialog) or the project restarted. */
    val settingsChanged = BridgeEvent("settings_changed", ServerSettings.serializer())

    /**
     * The debugger's channel, both ways: each notification carries one Debug
     * Adapter Protocol message as DAP defines it (a request, a response or an
     * event: `seq`, `type`, …), untouched by the bridge. The plugin is the
     * debug adapter and the editor its client: the editor sends requests
     * (`initialize`, `attach`, `setBreakpoints`, `stackTrace`, `continue`…)
     * and the plugin answers them and sends events (`stopped`, `continued`).
     * The plugin reads them on the bridge's thread, so they're heard while a
     * breakpoint holds the main thread. Rides the bridge's connection, hello
     * and token: a dev server only.
     */
    val dap = BridgeEvent("dap", JsonElement.serializer())

    val events: List<BridgeEvent<*>> = listOf(problems, status, settingsChanged, dap)

    /** Notifications the editor sends the plugin: the debugger's channel ([dap]) is the only one. */
    val notifications: List<BridgeEvent<*>> = listOf(dap)
    val streams: List<BridgeStream<*>> = listOf(console, profiler)

    // ---- editor → plugin ----

    val reload = BridgeMethod("reload", ReloadParams.serializer(), ReloadResult.serializer())

    /** Answers with where the centity spawned. */
    val spawn = BridgeMethod("spawn", SpawnParams.serializer(), InstanceInfo.serializer())

    /** Live centity instances, including inert ones whose centity is missing. */
    val instances = BridgeMethod("instances", Unit.serializer(), ListSerializer(InstanceInfo.serializer()))

    /** What the server knows about the game, from its live registries. Takes a moment: once per version. */
    val exportGameData = BridgeMethod("export_game_data", Unit.serializer(), GameDataBundle.serializer())

    /** Runs a console command; fails when the server didn't run it. */
    val command = BridgeMethod("command", CommandParams.serializer(), Unit.serializer())

    val playParticleEffect = BridgeMethod("play_particle_effect", PlayParticleEffectParams.serializer(), Unit.serializer())

    /** Ends every effect [playParticleEffect] started. */
    val stopParticleEffects = BridgeMethod("stop_particle_effects", Unit.serializer(), Unit.serializer())

    /** Fails when nobody (or not that player) is online. */
    val playerPosition = BridgeMethod("player_position", PlayerPositionParams.serializer(), PlayerPosition.serializer())

    /** The loaded worlds, the main one first. */
    val worlds = BridgeMethod("worlds", Unit.serializer(), ListSerializer(LoadedWorld.serializer()))

    val saveStructure = BridgeMethod("save_structure", SaveStructureParams.serializer(), SavedStructure.serializer())

    /** Answers where the saved world's files are, relative to the server's folder. */
    val saveWorld = BridgeMethod("save_world", SaveWorldParams.serializer(), SavedWorld.serializer())

    /** Every package's server-owner settings and the values the server runs with. */
    val settings = BridgeMethod("settings", Unit.serializer(), ServerSettings.serializer())

    /**
     * Sets a server-owner setting (or puts it back to its default), as `/nf
     * settings set` does: written to the server's settings file, heard as
     * `setting_changed` or by restarting the scripts that read it. Fails with
     * why when the value isn't one the setting can have. Answers every
     * package's settings as they are then.
     */
    val setSetting = BridgeMethod("set_setting", SetSettingParams.serializer(), ServerSettings.serializer())

    /**
     * Answered at once on the bridge's thread, whatever the main thread is
     * doing: tells a busy (or, with the debugger, paused) server from a gone one.
     */
    val ping = BridgeMethod("ping", Unit.serializer(), Unit.serializer(), BridgeThread.BRIDGE)

    /**
     * Starts or stops the [profiler] stream. A control request: answered on
     * the bridge's thread, so it never waits for a busy main thread.
     */
    val profilerSubscribe =
        BridgeMethod("profiler_subscribe", ProfilerSubscribeParams.serializer(), Unit.serializer(), BridgeThread.BRIDGE)

    /** Every request the editor can send any dev server. */
    val requests: List<BridgeMethod<*, *>> = listOf(
        reload,
        spawn,
        instances,
        exportGameData,
        command,
        playParticleEffect,
        stopParticleEffects,
        playerPosition,
        worlds,
        saveStructure,
        saveWorld,
        settings,
        setSetting,
        ping,
        profilerSubscribe
    )

    /** Requests only some dev servers answer, each under its namespace. */
    val extensions: List<BridgeExtension> = listOf(BotsExtension.extension)

    /** Every request the editor can send: the core's, then each extension's. */
    val allRequests: List<BridgeMethod<*, *>> get() = requests + extensions.flatMap { it.methods }

    /** The request named [name], core or extension, or null. */
    fun request(name: String): BridgeMethod<*, *>? = allRequests.firstOrNull { it.name == name }
}
